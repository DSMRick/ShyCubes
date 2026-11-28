package com.shycubes.telemetry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single OpenTelemetry helper compliant with otel.md:
 * - Reads APP_LOG_LEVEL, OTEL_SERVICE_NAME, NR_LOG_PARTITION, OTEL_EXPORTER_OTLP_ENDPOINT once at startup.
 * - One threshold gates spans and structured events.
 * - Exports OTLP/HTTP JSON asynchronously to the local NRDOT collector without blocking server ticks.
 */
public final class OtelTelemetry {

    public enum Level {
        TRACE(0, 1, "TRACE"),
        DEBUG(1, 5, "DEBUG"),
        INFO(2, 9, "INFO"),
        WARN(3, 13, "WARN"),
        ERROR(4, 17, "ERROR"),
        FATAL(5, 21, "FATAL");

        private final int rank;
        private final int otelSeverityNumber;
        private final String severityText;

        Level(int rank, int otelSeverityNumber, String severityText) {
            this.rank = rank;
            this.otelSeverityNumber = otelSeverityNumber;
            this.severityText = severityText;
        }

        public int rank() {
            return rank;
        }

        public int otelSeverityNumber() {
            return otelSeverityNumber;
        }

        public String severityText() {
            return severityText;
        }

        public static Level parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return INFO;
            }
            try {
                return Level.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return INFO;
            }
        }
    }

    public record SpanContext(String traceId, String spanId) {}

    public interface SpanScope extends AutoCloseable {
        void setAttribute(String key, Object value);

        void recordException(Throwable throwable);

        SpanContext context();

        @Override
        void close();
    }

    private static final SpanScope NOOP_SPAN = new SpanScope() {
        @Override
        public void setAttribute(String key, Object value) {}

        @Override
        public void recordException(Throwable throwable) {}

        @Override
        public SpanContext context() {
            return null;
        }

        @Override
        public void close() {}
    };

    private static final int MAX_QUEUE_SIZE = 4096;
    private static final int MAX_BATCH_SIZE = 50;
    private static final long FLUSH_INTERVAL_MS = 1000L;

    private final String serviceName;
    private final String partition;
    private final String endpoint;
    private final Level configuredLevel;
    private final SpanContext rootEnvContext;
    private final SecureRandom random = new SecureRandom();
    private final ThreadLocal<Deque<SpanContext>> activeStack = ThreadLocal.withInitial(ArrayDeque::new);

    private final ArrayBlockingQueue<String> spanQueue = new ArrayBlockingQueue<>(MAX_QUEUE_SIZE);
    private final ArrayBlockingQueue<String> logQueue = new ArrayBlockingQueue<>(MAX_QUEUE_SIZE);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final HttpClient httpClient;
    private final Thread exporterThread;

    public OtelTelemetry() {
        this.serviceName = envOrDefault("OTEL_SERVICE_NAME", "shyCubesLogs");
        this.partition = envOrDefault("NR_LOG_PARTITION", "shyCubesLogs");
        String rawEndpoint = envOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4318");
        this.endpoint = rawEndpoint.endsWith("/") ? rawEndpoint.substring(0, rawEndpoint.length() - 1) : rawEndpoint;
        this.configuredLevel = Level.parse(System.getenv("APP_LOG_LEVEL"));
        this.rootEnvContext = parseTraceparent(System.getenv("TRACEPARENT"));

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        this.exporterThread = new Thread(this::exporterLoop, "shycubes-otel-exporter");
        this.exporterThread.setDaemon(true);
        this.exporterThread.start();
    }

    public Level getConfiguredLevel() {
        return configuredLevel;
    }

    public boolean isEnabled(Level level) {
        return level != null && level.rank() >= configuredLevel.rank();
    }

    public SpanContext currentContext() {
        Deque<SpanContext> stack = activeStack.get();
        if (!stack.isEmpty()) {
            return stack.peek();
        }
        return rootEnvContext;
    }

    public SpanScope startSpan(String name, Level level) {
        return startSpan(name, level, null, Collections.emptyMap());
    }

    public SpanScope startSpan(String name, Level level, Map<String, Object> attrs) {
        return startSpan(name, level, null, attrs);
    }

    public SpanScope startSpan(String name, Level level, SpanContext explicitParent, Map<String, Object> attrs) {
        if (!isEnabled(level)) {
            return NOOP_SPAN;
        }
        SpanContext parent = explicitParent != null ? explicitParent : currentContext();
        String traceId = parent != null ? parent.traceId() : randomHex(16);
        String parentSpanId = parent != null ? parent.spanId() : null;
        String spanId = randomHex(8);
        SpanContext ctx = new SpanContext(traceId, spanId);
        activeStack.get().push(ctx);
        return new ActiveSpan(name, ctx, parentSpanId, attrs);
    }

    /**
     * Emits a structured OTel event (no free-text body) if the level passes APP_LOG_LEVEL.
     */
    public void emitEvent(String eventName, Level level, Map<String, Object> attrs) {
        if (!isEnabled(level) || !running.get()) {
            return;
        }
        long nowNanos = currentEpochNanos();
        SpanContext ctx = currentContext();

        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("event.name", eventName);
        if (attrs != null) {
            merged.putAll(attrs);
        }

        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"timeUnixNano\":\"").append(nowNanos).append('"');
        sb.append(",\"observedTimeUnixNano\":\"").append(nowNanos).append('"');
        sb.append(",\"severityNumber\":").append(level.otelSeverityNumber());
        sb.append(",\"severityText\":\"").append(level.severityText()).append('"');
        sb.append(",\"body\":{\"stringValue\":\"\"}");
        if (ctx != null) {
            sb.append(",\"traceId\":\"").append(ctx.traceId()).append('"');
            sb.append(",\"spanId\":\"").append(ctx.spanId()).append('"');
        }
        sb.append(",\"attributes\":");
        appendAttributesJson(sb, merged);
        sb.append('}');

        logQueue.offer(sb.toString());
    }

    public void emitEvent(String eventName, Level level) {
        emitEvent(eventName, level, Collections.emptyMap());
    }

    public void shutdown() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        exporterThread.interrupt();
        try {
            exporterThread.join(2500L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private final class ActiveSpan implements SpanScope {
        private final String name;
        private final SpanContext context;
        private final String parentSpanId;
        private final long startEpochNanos;
        private final long startNanoTime;
        private final Map<String, Object> attributes = new LinkedHashMap<>();
        private final List<String> eventsJson = new ArrayList<>();
        private boolean error = false;
        private String errorMessage = null;
        private boolean closed = false;

        ActiveSpan(String name, SpanContext context, String parentSpanId, Map<String, Object> initialAttrs) {
            this.name = name;
            this.context = context;
            this.parentSpanId = parentSpanId;
            this.startEpochNanos = currentEpochNanos();
            this.startNanoTime = System.nanoTime();
            if (initialAttrs != null) {
                this.attributes.putAll(initialAttrs);
            }
        }

        @Override
        public void setAttribute(String key, Object value) {
            if (key != null && value != null) {
                attributes.put(key, value);
            }
        }

        @Override
        public void recordException(Throwable throwable) {
            if (throwable == null) {
                return;
            }
            this.error = true;
            this.errorMessage = throwable.getMessage() != null
                    ? throwable.getMessage()
                    : throwable.getClass().getSimpleName();
            Map<String, Object> excAttrs = new LinkedHashMap<>();
            excAttrs.put("exception.type", throwable.getClass().getName());
            if (throwable.getMessage() != null) {
                excAttrs.put("exception.message", throwable.getMessage());
            }
            StringBuilder ev = new StringBuilder(160);
            ev.append("{\"timeUnixNano\":\"").append(currentEpochNanos()).append('"');
            ev.append(",\"name\":\"exception\",\"attributes\":");
            appendAttributesJson(ev, excAttrs);
            ev.append('}');
            eventsJson.add(ev.toString());
        }

        @Override
        public SpanContext context() {
            return context;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            Deque<SpanContext> stack = activeStack.get();
            if (!stack.isEmpty() && stack.peek().equals(context)) {
                stack.pop();
            } else {
                stack.remove(context);
            }
            if (stack.isEmpty()) {
                activeStack.remove();
            }

            long elapsedNanos = Math.max(0L, System.nanoTime() - startNanoTime);
            long endEpochNanos = startEpochNanos + elapsedNanos;

            StringBuilder sb = new StringBuilder(320);
            sb.append("{\"traceId\":\"").append(context.traceId()).append('"');
            sb.append(",\"spanId\":\"").append(context.spanId()).append('"');
            if (parentSpanId != null) {
                sb.append(",\"parentSpanId\":\"").append(parentSpanId).append('"');
            }
            sb.append(",\"name\":\"").append(escapeJson(name)).append('"');
            sb.append(",\"kind\":1");
            sb.append(",\"startTimeUnixNano\":\"").append(startEpochNanos).append('"');
            sb.append(",\"endTimeUnixNano\":\"").append(endEpochNanos).append('"');
            sb.append(",\"attributes\":");
            appendAttributesJson(sb, attributes);
            if (!eventsJson.isEmpty()) {
                sb.append(",\"events\":[").append(String.join(",", eventsJson)).append(']');
            }
            if (error) {
                sb.append(",\"status\":{\"code\":2,\"message\":\"")
                        .append(escapeJson(errorMessage != null ? errorMessage : "error"))
                        .append("\"}");
            } else {
                sb.append(",\"status\":{\"code\":1}");
            }
            sb.append('}');

            spanQueue.offer(sb.toString());
        }
    }

    private void exporterLoop() {
        List<String> spanBatch = new ArrayList<>(MAX_BATCH_SIZE);
        List<String> logBatch = new ArrayList<>(MAX_BATCH_SIZE);
        long lastFlush = System.currentTimeMillis();

        while (running.get() || !spanQueue.isEmpty() || !logQueue.isEmpty()) {
            try {
                String firstSpan = spanQueue.poll(150L, TimeUnit.MILLISECONDS);
                if (firstSpan != null) {
                    spanBatch.add(firstSpan);
                    spanQueue.drainTo(spanBatch, MAX_BATCH_SIZE - spanBatch.size());
                }
                logQueue.drainTo(logBatch, MAX_BATCH_SIZE - logBatch.size());
            } catch (InterruptedException ignored) {
                spanQueue.drainTo(spanBatch, MAX_BATCH_SIZE - spanBatch.size());
                logQueue.drainTo(logBatch, MAX_BATCH_SIZE - logBatch.size());
            }

            long now = System.currentTimeMillis();
            boolean timeElapsed = (now - lastFlush) >= FLUSH_INTERVAL_MS;
            boolean shuttingDown = !running.get();

            if (!spanBatch.isEmpty() && (spanBatch.size() >= MAX_BATCH_SIZE || timeElapsed || shuttingDown)) {
                shipSpans(spanBatch);
                spanBatch.clear();
                lastFlush = now;
            }
            if (!logBatch.isEmpty() && (logBatch.size() >= MAX_BATCH_SIZE || timeElapsed || shuttingDown)) {
                shipLogs(logBatch);
                logBatch.clear();
                lastFlush = now;
            }
        }
    }

    private void shipSpans(List<String> spans) {
        StringBuilder payload = new StringBuilder(512 + spans.size() * 256);
        payload.append("{\"resourceSpans\":[{\"resource\":{\"attributes\":");
        appendResourceAttributes(payload);
        payload.append("},\"scopeSpans\":[{\"scope\":{\"name\":\"com.shycubes\"},\"spans\":[");
        payload.append(String.join(",", spans));
        payload.append("]}]}]}");
        postOtlp("/v1/traces", payload.toString());
    }

    private void shipLogs(List<String> records) {
        StringBuilder payload = new StringBuilder(512 + records.size() * 256);
        payload.append("{\"resourceLogs\":[{\"resource\":{\"attributes\":");
        appendResourceAttributes(payload);
        payload.append("},\"scopeLogs\":[{\"scope\":{\"name\":\"com.shycubes\"},\"logRecords\":[");
        payload.append(String.join(",", records));
        payload.append("]}]}]}");
        postOtlp("/v1/logs", payload.toString());
    }

    private void appendResourceAttributes(StringBuilder sb) {
        Map<String, Object> resourceAttrs = new LinkedHashMap<>();
        resourceAttrs.put("service.name", serviceName);
        resourceAttrs.put("appName", serviceName);
        resourceAttrs.put("partition", partition);
        appendAttributesJson(sb, resourceAttrs);
    }

    private void postOtlp(String path, String jsonBody) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + path))
                    .timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            httpClient.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // Never disrupt gameplay if the local OTLP collector is unavailable
        }
    }

    private String randomHex(int numBytes) {
        byte[] bytes = new byte[numBytes];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(numBytes * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static long currentEpochNanos() {
        return System.currentTimeMillis() * 1_000_000L;
    }

    private static String envOrDefault(String key, String fallback) {
        String val = System.getenv(key);
        return (val == null || val.isBlank()) ? fallback : val.trim();
    }

    private static SpanContext parseTraceparent(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return null;
        }
        String[] parts = traceparent.trim().split("-");
        if (parts.length >= 3 && parts[1].length() == 32 && parts[2].length() == 16) {
            return new SpanContext(parts[1].toLowerCase(Locale.ROOT), parts[2].toLowerCase(Locale.ROOT));
        }
        return null;
    }

    private static void appendAttributesJson(StringBuilder sb, Map<String, Object> attrs) {
        sb.append('[');
        boolean first = true;
        for (Map.Entry<String, Object> entry : attrs.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"key\":\"").append(escapeJson(entry.getKey())).append("\",\"value\":");
            Object v = entry.getValue();
            if (v instanceof Boolean b) {
                sb.append("{\"boolValue\":").append(b).append('}');
            } else if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
                sb.append("{\"intValue\":\"").append(((Number) v).longValue()).append("\"}");
            } else if (v instanceof Double || v instanceof Float) {
                sb.append("{\"doubleValue\":").append(((Number) v).doubleValue()).append('}');
            } else {
                sb.append("{\"stringValue\":\"").append(escapeJson(String.valueOf(v))).append("\"}");
            }
            sb.append('}');
        }
        sb.append(']');
    }

    private static String escapeJson(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(input.length() + 8);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
