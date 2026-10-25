#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/common.sh"

sanitize_single_file() {
  local file="$1"
  local example_file="${file}.config.example"

  local sed_args=(
    -E
    -e 's/^(management-server-secret=).+$/\1<REDACTED_SECRET>/'
    -e 's/^(management-server-tls-keystore-password=).+$/\1<REDACTED_SECRET>/'
    -e 's/^(rcon\.password=).+$/\1<REDACTED_SECRET>/'
    -e 's/^(server-ip=).+$/\1<YOUR_SERVER_IP>/'
    -e 's/^([[:space:]]*address:[[:space:]]*)[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$/\1<YOUR_SERVER_IP>/'
    -e 's/^([[:space:]]*serverUuid:[[:space:]]*).+$/\1<REDACTED_UUID>/'
    -e 's/^([[:space:]]*uuid:[[:space:]]*)[0-9a-fA-F-]{36}$/\1<REDACTED_UUID>/'
    -e "s/^([[:space:]]*password:[[:space:]]*)['\"]?[^'\"[:space:]]+['\"]?$/\1'<REDACTED_SECRET>'/"
    -e "s/^([[:space:]]*token:[[:space:]]*)['\"]?[^'\"[:space:]]+['\"]?$/\1'<REDACTED_TOKEN>'/"
    -e "s/^([[:space:]]*secret:[[:space:]]*)['\"]?[^'\"[:space:]]+['\"]?$/\1'<REDACTED_SECRET>'/"
  )

  if [[ -n "${DEPLOY_HOST:-}" ]]; then
    sed_args+=(-e "s/${DEPLOY_HOST//./\\.}/<YOUR_SERVER_IP>/g")
  fi

  sed "${sed_args[@]}" "${file}" > "${example_file}"
}

run_sanitize_configs() {
  if [[ ! -d "${CONFIGS_ROOT}" ]]; then
    emit_event WARN "configs.sanitize.skipped" reason="directory_missing"
    return 0
  fi

  while IFS= read -r -d '' file; do
    instrument TRACE "configs.sanitize_file" sanitize_single_file "${file}"
  done < <(find "${CONFIGS_ROOT}" -type f \( -name "*.properties" -o -name "*.yml" \) ! -name "*.config.example" -print0)
}

instrument INFO "configs.sanitize.run" run_sanitize_configs
