#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${REPO_ROOT}/.deploy.env"

if [[ -f "${ENV_FILE}" ]]; then
  # Load private deployment and OTel variables
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
fi

# OpenTelemetry & New Relic configuration (read once at startup)
export OTEL_SERVICE_NAME="${OTEL_SERVICE_NAME:-shyCubesLogs}"
export NR_LOG_PARTITION="${NR_LOG_PARTITION:-shyCubesLogs}"
export OTEL_EXPORTER_OTLP_ENDPOINT="${OTEL_EXPORTER_OTLP_ENDPOINT:-http://localhost:4318}"
export OTEL_RESOURCE_ATTRIBUTES="service.name=${OTEL_SERVICE_NAME},appName=${OTEL_SERVICE_NAME},partition=${NR_LOG_PARTITION}"

level_num() {
  case "${1:-INFO}" in
    TRACE) echo 0 ;;
    DEBUG) echo 1 ;;
    INFO)  echo 2 ;;
    WARN)  echo 3 ;;
    ERROR) echo 4 ;;
    FATAL) echo 5 ;;
    *)     echo 2 ;;
  esac
}

otel_severity_number() {
  case "${1:-INFO}" in
    TRACE) echo 1 ;;
    DEBUG) echo 5 ;;
    INFO)  echo 9 ;;
    WARN)  echo 13 ;;
    ERROR) echo 17 ;;
    FATAL) echo 21 ;;
    *)     echo 9 ;;
  esac
}

# Validate and normalize APP_LOG_LEVEL once
_RAW_LOG_LEVEL="$(printf '%s' "${APP_LOG_LEVEL:-INFO}" | tr '[:lower:]' '[:upper:]')"
case "${_RAW_LOG_LEVEL}" in
  TRACE|DEBUG|INFO|WARN|ERROR|FATAL)
    APP_LOG_LEVEL="${_RAW_LOG_LEVEL}"
    ;;
  *)
    APP_LOG_LEVEL="INFO"
    ;;
esac
export APP_LOG_LEVEL
readonly _CONFIGURED_LEVEL_NUM="$(level_num "${APP_LOG_LEVEL}")"

otel_enabled() {
  local lvl="$1"
  [[ "$(level_num "${lvl}")" -ge "${_CONFIGURED_LEVEL_NUM}" ]]
}

_now_unix_nano() {
  if command -v python3 >/dev/null 2>&1; then
    python3 -c 'import time; print(time.time_ns())'
  else
    echo "$(date +%s)000000000"
  fi
}

_rand_hex() {
  local bytes="$1"
  od -An -N "${bytes}" -tx1 /dev/urandom | tr -d ' \n'
}

_parse_traceparent() {
  local tp="${TRACEPARENT:-}"
  if [[ "${tp}" =~ ^00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$ ]]; then
    _TP_TRACE_ID="${BASH_REMATCH[1]}"
    _TP_SPAN_ID="${BASH_REMATCH[2]}"
  else
    _TP_TRACE_ID="$(_rand_hex 16)"
    _TP_SPAN_ID=""
  fi
}

# emit_event <level> <event.name> [key=value ...]
emit_event() {
  local level="$1"
  local event_name="$2"
  shift 2

  if ! otel_enabled "${level}"; then
    return 0
  fi

  _parse_traceparent
  local ts_nano
  ts_nano="$(_now_unix_nano)"
  local sev_num
  sev_num="$(otel_severity_number "${level}")"

  local attrs_json
  attrs_json="$(jq -n --arg ev "${event_name}" '[{key: "event.name", value: {stringValue: $ev}}]')"

  local kv key val
  for kv in "$@"; do
    key="${kv%%=*}"
    val="${kv#*=}"
    attrs_json="$(jq -n --argjson arr "${attrs_json}" --arg k "${key}" --arg v "${val}" \
      '$arr + [{key: $k, value: {stringValue: $v}}]')"
  done

  local payload
  payload="$(jq -n \
    --arg svc "${OTEL_SERVICE_NAME}" \
    --arg part "${NR_LOG_PARTITION}" \
    --arg t_nano "${ts_nano}" \
    --argjson sev_num "${sev_num}" \
    --arg sev_text "${level}" \
    --arg trace_id "${_TP_TRACE_ID}" \
    --arg span_id "${_TP_SPAN_ID}" \
    --argjson attrs "${attrs_json}" \
    '{
      resourceLogs: [{
        resource: {
          attributes: [
            {key: "service.name", value: {stringValue: $svc}},
            {key: "appName", value: {stringValue: $svc}},
            {key: "partition", value: {stringValue: $part}}
          ]
        },
        scopeLogs: [{
          scope: {name: $svc},
          logRecords: [{
            timeUnixNano: $t_nano,
            observedTimeUnixNano: $t_nano,
            severityNumber: $sev_num,
            severityText: $sev_text,
            body: {stringValue: ""},
            attributes: $attrs,
            traceId: $trace_id,
            spanId: $span_id
          }]
        }]
      }]
    }')"

  curl -sS -m 2 -X POST "${OTEL_EXPORTER_OTLP_ENDPOINT}/v1/logs" \
    -H "Content-Type: application/json" \
    -d "${payload}" >/dev/null 2>&1 || true
}

# instrument <level> <span_name> <command...>
instrument() {
  local level="$1"
  local span_name="$2"
  shift 2

  if ! otel_enabled "${level}"; then
    "$@"
    return $?
  fi

  if command -v otel-cli >/dev/null 2>&1; then
    otel-cli exec --service "${OTEL_SERVICE_NAME}" --name "${span_name}" -- "$@"
    return $?
  fi

  local prev_traceparent="${TRACEPARENT:-}"
  _parse_traceparent
  local trace_id="${_TP_TRACE_ID}"
  local parent_span_id="${_TP_SPAN_ID}"
  local span_id
  span_id="$(_rand_hex 8)"

  export TRACEPARENT="00-${trace_id}-${span_id}-01"

  local start_nano
  start_nano="$(_now_unix_nano)"

  local rc=0
  if "$@"; then
    rc=0
  else
    rc=$?
  fi

  local end_nano
  end_nano="$(_now_unix_nano)"

  if [[ -n "${prev_traceparent}" ]]; then
    export TRACEPARENT="${prev_traceparent}"
  else
    unset TRACEPARENT
  fi

  # OTel status code: 1 = OK, 2 = ERROR
  local status_code=1
  if (( rc != 0 )); then
    status_code=2
  fi

  local payload
  payload="$(jq -n \
    --arg svc "${OTEL_SERVICE_NAME}" \
    --arg part "${NR_LOG_PARTITION}" \
    --arg trace_id "${trace_id}" \
    --arg span_id "${span_id}" \
    --arg parent_id "${parent_span_id}" \
    --arg name "${span_name}" \
    --arg start_nano "${start_nano}" \
    --arg end_nano "${end_nano}" \
    --arg level "${level}" \
    --argjson status_code "${status_code}" \
    --argjson exit_code "${rc}" \
    '{
      resourceSpans: [{
        resource: {
          attributes: [
            {key: "service.name", value: {stringValue: $svc}},
            {key: "appName", value: {stringValue: $svc}},
            {key: "partition", value: {stringValue: $part}}
          ]
        },
        scopeSpans: [{
          scope: {name: $svc},
          spans: [{
            traceId: $trace_id,
            spanId: $span_id,
            parentSpanId: $parent_id,
            name: $name,
            kind: 1,
            startTimeUnixNano: $start_nano,
            endTimeUnixNano: $end_nano,
            attributes: [
              {key: "span.level", value: {stringValue: $level}},
              {key: "process.exit_code", value: {intValue: $exit_code}}
            ],
            status: {code: $status_code}
          }]
        }]
      }]
    }')"

  curl -sS -m 2 -X POST "${OTEL_EXPORTER_OTLP_ENDPOINT}/v1/traces" \
    -H "Content-Type: application/json" \
    -d "${payload}" >/dev/null 2>&1 || true

  return "${rc}"
}

if [[ ! -f "${ENV_FILE}" ]]; then
  emit_event FATAL "deploy.config.missing" env_file="${ENV_FILE}"
  exit 1
fi

DEPLOY_SERVER_ROOT="${DEPLOY_SERVER_ROOT:-/home/opc/mc}"
DEPLOY_REMOTE_DIR="${DEPLOY_REMOTE_DIR:-${DEPLOY_SERVER_ROOT}/plugins}"
BACKUP_ROOT="${REPO_ROOT}/${LOCAL_BACKUP_DIR:-server_data}"
CONFIGS_ROOT="${REPO_ROOT}/server_configs"

SSH_OPTS="-i ${DEPLOY_SSH_KEY} -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new"
REMOTE_CONN="${DEPLOY_USER}@${DEPLOY_HOST}"
