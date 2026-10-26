#!/usr/bin/env bash
set -euo pipefail

# Read configuration once at startup
OTEL_SERVICE_NAME="${OTEL_SERVICE_NAME:-shyCubesLogs}"
NR_LOG_PARTITION="${NR_LOG_PARTITION:-shyCubesLogs}"
OTEL_EXPORTER_OTLP_ENDPOINT="${OTEL_EXPORTER_OTLP_ENDPOINT:-http://localhost:4318}"
APP_LOG_LEVEL="${APP_LOG_LEVEL:-INFO}"

exec python3 -u - "${OTEL_SERVICE_NAME}" "${NR_LOG_PARTITION}" "${OTEL_EXPORTER_OTLP_ENDPOINT}" "${APP_LOG_LEVEL}" "$@" <<'PYEOF'
import json
import os
import queue
import re
import signal
import subprocess
import sys
import threading
import time
import urllib.request

svc_name = sys.argv[1]
partition = sys.argv[2]
endpoint = sys.argv[3].rstrip("/")
raw_level = sys.argv[4].upper()
cmd = sys.argv[5:]

LEVELS = {"TRACE": 0, "DEBUG": 1, "INFO": 2, "WARN": 3, "ERROR": 4, "FATAL": 5}
SEV_NUM = {"TRACE": 1, "DEBUG": 5, "INFO": 9, "WARN": 13, "ERROR": 17, "FATAL": 21}

cfg_level = raw_level if raw_level in LEVELS else "INFO"
cfg_threshold = LEVELS[cfg_level]

# Regex for Paper/Log4j console lines: e.g. [13:20:01 INFO]: [Geyser-Spigot] Done (2.1s)!
LOG_RE = re.compile(r"^\[(?P<time>[0-9:]+)\s+(?P<thread>[^/\]]+?)/?(?P<level>TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL)\]:\s*(?P<msg>.*)$")
ALT_RE = re.compile(r"^\[(?P<time>[0-9:]+)\s+(?P<level>TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL)\]:\s*(?P<msg>.*)$")
ANSI_RE = re.compile(r"\x1b\[[0-9;]*[a-zA-Z]")

log_queue = queue.Queue(maxsize=10000)
stop_event = threading.Event()


def parse_line(raw_line):
    clean = ANSI_RE.sub("", raw_line).rstrip("\r\n")
    m = LOG_RE.match(clean) or ALT_RE.match(clean)
    if m:
        lvl = m.group("level")
        if lvl == "WARNING":
            lvl = "WARN"
        msg = m.group("msg")
        thread = m.groupdict().get("thread") or "main"
        return lvl, thread, msg
    return "INFO", "stdout", clean


def ship_batch(records):
    if not records:
        return
    payload = {
        "resourceLogs": [
            {
                "resource": {
                    "attributes": [
                        {"key": "service.name", "value": {"stringValue": svc_name}},
                        {"key": "appName", "value": {"stringValue": svc_name}},
                        {"key": "partition", "value": {"stringValue": partition}},
                    ]
                },
                "scopeLogs": [
                    {
                        "scope": {"name": "paper.server.stdout"},
                        "logRecords": records,
                    }
                ],
            }
        ]
    }
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        f"{endpoint}/v1/logs",
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=2) as _:
            pass
    except Exception:
        pass


def exporter_worker():
    batch = []
    last_flush = time.monotonic()
    while not stop_event.is_set() or not log_queue.empty():
        try:
            rec = log_queue.get(timeout=0.25)
            batch.append(rec)
        except queue.Empty:
            pass

        now = time.monotonic()
        if batch and (len(batch) >= 50 or (now - last_flush) >= 1.0 or stop_event.is_set()):
            ship_batch(batch)
            batch = []
            last_flush = now

    if batch:
        ship_batch(batch)


worker = threading.Thread(target=exporter_worker, daemon=True)
worker.start()

proc = subprocess.Popen(
    cmd,
    stdin=subprocess.DEVNULL,
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
    text=True,
    bufsize=1,
    encoding="utf-8",
    errors="replace",
)


def forward_signal(signum, _frame):
    try:
        proc.send_signal(signum)
    except Exception:
        pass


signal.signal(signal.SIGTERM, forward_signal)
signal.signal(signal.SIGINT, forward_signal)

for line in proc.stdout:
    sys.stdout.write(line)
    sys.stdout.flush()
    lvl, thread, msg = parse_line(line)
    if not msg:
        continue
    if LEVELS.get(lvl, 2) >= cfg_threshold:
        ts_nano = str(time.time_ns())
        rec = {
            "timeUnixNano": ts_nano,
            "observedTimeUnixNano": ts_nano,
            "severityNumber": SEV_NUM.get(lvl, 9),
            "severityText": lvl,
            "body": {"stringValue": msg},
            "attributes": [
                {"key": "thread.name", "value": {"stringValue": thread}},
                {"key": "log.source", "value": {"stringValue": "minecraft.stdout"}},
            ],
        }
        try:
            log_queue.put_nowait(rec)
        except queue.Full:
            pass

rc = proc.wait()
stop_event.set()
worker.join(timeout=3.0)
sys.exit(rc)
PYEOF

