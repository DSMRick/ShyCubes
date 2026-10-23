#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/common.sh"

if [[ ! -d "${CONFIGS_ROOT}" ]]; then
  echo "[sanitize] No ${CONFIGS_ROOT} directory found."
  exit 0
fi

echo "[sanitize] Generating sanitized *.config.example files in ${CONFIGS_ROOT}..."

while IFS= read -r -d '' file; do
  example_file="${file}.config.example"

  sed -E \
    -e "s/${DEPLOY_HOST//./\\.}/<YOUR_SERVER_IP>/g" \
    -e 's/^(management-server-secret=).+$/\1<REDACTED_SECRET>/' \
    -e 's/^(management-server-tls-keystore-password=).+$/\1<REDACTED_SECRET>/' \
    -e 's/^(rcon\.password=).+$/\1<REDACTED_SECRET>/' \
    -e 's/^(server-ip=).+$/\1<YOUR_SERVER_IP>/' \
    -e 's/^([[:space:]]*serverUuid:[[:space:]]*).+$/\1<REDACTED_UUID>/' \
    -e 's/^([[:space:]]*uuid:[[:space:]]*)[0-9a-fA-F-]{36}$/\1<REDACTED_UUID>/' \
    -e "s/^([[:space:]]*password:[[:space:]]*)['\"]?[^'\"[:space:]]+['\"]?$/\1'<REDACTED_SECRET>'/" \
    -e "s/^([[:space:]]*token:[[:space:]]*)['\"]?[^'\"[:space:]]+['\"]?$/\1'<REDACTED_TOKEN>'/" \
    -e "s/^([[:space:]]*secret:[[:space:]]*)['\"]?[^'\"[:space:]]+['\"]?$/\1'<REDACTED_SECRET>'/" \
    "${file}" > "${example_file}"

done < <(find "${CONFIGS_ROOT}" -type f \( -name "*.properties" -o -name "*.yml" \) ! -name "*.config.example" -print0)

echo "[sanitize] Done."

