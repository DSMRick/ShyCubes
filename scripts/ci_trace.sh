#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export DEPLOY_LOCAL="${DEPLOY_LOCAL:-true}"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/common.sh"

ACTION="${1:-start}"

start_ci_trace() {
  local span_name="${1:-ci.workflow.run}"
  local trace_id
  trace_id="$(_rand_hex 16)"
  local span_id
  span_id="$(_rand_hex 8)"
  local start_nano
  start_nano="$(_now_unix_nano)"
  local traceparent="00-${trace_id}-${span_id}-01"

  if [[ -n "${GITHUB_ENV:-}" ]]; then
    {
      echo "TRACEPARENT=${traceparent}"
      echo "CI_TRACE_ID=${trace_id}"
      echo "CI_SPAN_ID=${span_id}"
      echo "CI_SPAN_NAME=${span_name}"
      echo "CI_SPAN_START_NANO=${start_nano}"
    } >> "${GITHUB_ENV}"
  fi
}

end_ci_trace() {
  local job_status="${1:-success}"
  if ! otel_enabled INFO; then
    return 0
  fi

  if [[ -z "${CI_TRACE_ID:-}" || -z "${CI_SPAN_ID:-}" || -z "${CI_SPAN_START_NANO:-}" ]]; then
    return 0
  fi

  local end_nano
  end_nano="$(_now_unix_nano)"
  local status_code=1
  local exit_code=0
  if [[ "${job_status}" != "success" ]]; then
    status_code=2
    exit_code=1
  fi

  local payload
  payload="$(jq -n \
    --arg svc "${OTEL_SERVICE_NAME}" \
    --arg part "${NR_LOG_PARTITION}" \
    --arg trace_id "${CI_TRACE_ID}" \
    --arg span_id "${CI_SPAN_ID}" \
    --arg name "${CI_SPAN_NAME:-ci.workflow.run}" \
    --arg start_nano "${CI_SPAN_START_NANO}" \
    --arg end_nano "${end_nano}" \
    --arg workflow "${GITHUB_WORKFLOW:-local}" \
    --arg run_id "${GITHUB_RUN_ID:-0}" \
    --arg actor "${GITHUB_ACTOR:-unknown}" \
    --arg sha "${GITHUB_SHA:-unknown}" \
    --arg job_status "${job_status}" \
    --argjson status_code "${status_code}" \
    --argjson exit_code "${exit_code}" \
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
            parentSpanId: "",
            name: $name,
            kind: 1,
            startTimeUnixNano: $start_nano,
            endTimeUnixNano: $end_nano,
            attributes: [
              {key: "span.level", value: {stringValue: "INFO"}},
              {key: "ci.workflow", value: {stringValue: $workflow}},
              {key: "ci.run_id", value: {stringValue: $run_id}},
              {key: "ci.actor", value: {stringValue: $actor}},
              {key: "git.commit.sha", value: {stringValue: $sha}},
              {key: "ci.job.status", value: {stringValue: $job_status}},
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
}

case "${ACTION}" in
  start)
    shift
    start_ci_trace "$@"
    ;;
  end)
    shift
    end_ci_trace "$@"
    ;;
  *)
    emit_event ERROR "ci.trace.invalid_action" action="${ACTION}"
    exit 1
    ;;
esac

