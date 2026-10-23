# OpenTelemetry Instrumentation Standards

The project uses **OpenTelemetry (OTel)** with the **New Relic Distribution of OpenTelemetry (NRDOT)** collector.

**Follow industry standards**: OTel specification, semantic conventions, standard log levels, W3C trace context. This document only records where we are explicitly choosing something specific or overriding the default. If it isn't written here, do what the standard says.

## 1. Scope

**Everything that runs is traced**: applications, services, shell scripts, deploys, migrations, cron jobs, CI steps, one-off tooling. There is no "too small to instrument".

Every runnable unit has:

1. Its own `OTEL_SERVICE_NAME` (e.g. `orders-api`, `deploy-orders`, `nightly-sync`).
2. A log level set in external config (section 3).
3. Trace context passed to anything it starts, including child processes (`TRACEPARENT`), so a deploy script calling a shell script calling a service is one trace.

Each deploy, job or script execution is one trace, with one span per stage, so a failure is findable by trace and not by scrolling terminal output.

## 2. Architecture

```
application (OTel SDK) --OTLP--> local NRDOT collector --OTLP--> New Relic (US)
```

- The NRDOT collector is **assumed to be installed locally** on its default OTLP ports. Don't install or configure it from application code.
- Applications export only to the local collector (`OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318`, or `:4317` for gRPC). They never talk to New Relic directly.
- The US endpoint and the license key live in the **collector's** config only. Application code never holds either one.

## 3. Log level

Standard levels: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, `FATAL`. The standard threshold behavior applies: a process emits its configured level and everything more severe.

- Set **outside the application** (environment variable, config file or service unit), never hard-coded in source.
- Variable: `APP_LOG_LEVEL`, default `INFO`.
- **One flag controls everything**: spans, events, metrics and logs, including third-party library logging. No separate setting for tracing.
- Read once at startup through a single helper. Instrumentation code never reads the config directly.

### Applying the levels to spans

The levels keep their standard OTel definitions. OTel only defines levels for logs and events, not spans, so this table applies the same definitions to spans. Declare a level on each span when you write it; the span is created only if that level passes the threshold.

| Level | Standard meaning | Spans and telemetry |
|---|---|---|
| `TRACE` | Finest-grained detail | A span for **every method call** |
| `DEBUG` | Diagnostic detail | Spans for methods expected to be **long-running** and methods that make up **major code blocks** (login flow, sync job, batch stage) |
| `INFO` (default) | Normal operational events | Spans for **entry points** (each request, job run or script execution) and **external calls** (anything that leaves the process: HTTP/API, database, queue, device, subprocess) |
| `WARN` | Unexpected, but work continued | Events and logs, not spans |
| `ERROR` | An operation failed | Events and logs, plus error status on the failing span |
| `FATAL` | Process or system can't continue | Events and logs, emitted before exit |

When adding a span, use the lowest-volume level that fits: entry point or crosses a process boundary → `INFO`; major block or takes noticeable time → `DEBUG`; anything else → `TRACE`.

## 4. Spans, events and logs

All go out as OTLP through the collector. No stdout or file logging, no bare `print` / `console.log`.

- **Our code emits events, not logs.** Use a stable `event.name` (lower-case, dot-separated, `domain.noun.verb`, no variable data in the name) with structured attributes and **no free-text message**. Events are smaller than log lines, so they're cheaper and easier to query.
- **Logs are for what we didn't write**: third-party libraries and frameworks, captured through the OTel log bridge for the language's standard logging, plus free-text output from code outside any span (startup, shutdown, background threads).
- **Don't duplicate a span.** No start/end events or logs for something a span already records.

## 5. Implementation

Keep the level check in **one helper** so call sites stay one-liners.

### Python

```python
import logging
import os
from contextlib import contextmanager
from opentelemetry import trace

LEVELS = {"TRACE": 0, "DEBUG": 1, "INFO": 2, "WARN": 3, "ERROR": 4, "FATAL": 5}
PY_LEVELS = {"TRACE": 5, "DEBUG": 10, "INFO": 20, "WARN": 30, "ERROR": 40, "FATAL": 50}

LOG_LEVEL = os.environ.get("APP_LOG_LEVEL", "INFO").upper()
if LOG_LEVEL not in LEVELS:
    LOG_LEVEL = "INFO"

logging.getLogger().setLevel(PY_LEVELS[LOG_LEVEL])   # third-party loggers follow the same threshold
logger = logging.getLogger(__name__)                  # bridged to OTLP by the OTel logging handler
tracer = trace.get_tracer(__name__)


def enabled(level):
    return LEVELS[level] >= LEVELS[LOG_LEVEL]


def emit_event(name, level="INFO", **attrs):
    if enabled(level):
        logger.log(PY_LEVELS[level], "", extra={"event.name": name, **attrs})


@contextmanager
def instrument(name, level="TRACE", **attrs):
    if not enabled(level):
        yield None
        return
    with tracer.start_as_current_span(name, attributes=attrs) as span:
        try:
            yield span
        except Exception as exc:
            span.record_exception(exc)
            span.set_status(trace.StatusCode.ERROR)
            raise
```

```python
def run_sync():                           # entry point
    with instrument("sync.run", level="INFO"):
        sync_all()

def fetch_orders():                       # external call
    with instrument("http.get orders-api", level="INFO", url=ORDERS_URL):
        return requests.get(ORDERS_URL)

def sync_all():                           # major block
    with instrument("sync.all", level="DEBUG"):
        ...

def parse_row(row):                       # everything else
    with instrument("sync.parse_row", level="TRACE"):
        ...

emit_event("sync.retry.attempted", level="WARN", attempt=3, reason="timeout")
```

### Shell scripts

Shell has no OTel SDK, so use a CLI that creates spans and sends OTLP to the local collector (for example [`otel-cli`](https://github.com/equinix-labs/otel-cli); verify the flags against the installed version). Same helper shape as Python:

```bash
#!/usr/bin/env bash
set -euo pipefail

export OTEL_SERVICE_NAME="deploy-orders"
export OTEL_EXPORTER_OTLP_ENDPOINT="http://localhost:4318"
APP_LOG_LEVEL="${APP_LOG_LEVEL:-INFO}"   # set by the caller's config, not edited here

level_num() {
  case "$1" in TRACE) echo 0 ;; DEBUG) echo 1 ;; INFO) echo 2 ;; WARN) echo 3 ;; ERROR) echo 4 ;; FATAL) echo 5 ;; *) echo 2 ;; esac
}

# instrument <level> <span name> <command...>
instrument() {
  local level="$1" name="$2"; shift 2
  if [ "$(level_num "$level")" -ge "$(level_num "$APP_LOG_LEVEL")" ]; then
    otel-cli exec --service "$OTEL_SERVICE_NAME" --name "$name" -- "$@"
  else
    "$@"
  fi
}

instrument INFO  "deploy.run"         ./deploy-steps.sh          # entry point
instrument DEBUG "deploy.build_image" docker build -t orders:latest .
instrument INFO  "deploy.push_image"  docker push orders:latest  # external call
instrument INFO  "deploy.rollout"     kubectl rollout restart deploy/orders
instrument TRACE "deploy.verify"      ./verify.sh
```

Child scripts launched through `instrument` inherit `TRACEPARENT`, so they join the same trace. Wrap every script execution in an `INFO` entry-point span.

Source: [New Relic OTLP endpoint](https://docs.newrelic.com/docs/opentelemetry/best-practices/opentelemetry-otlp/), [NRDOT](https://docs.newrelic.com/docs/opentelemetry/nrdot/).
