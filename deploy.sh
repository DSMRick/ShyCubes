#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/scripts/common.sh"

PULL_ONLY=false
PULL_CONFIGS=false
SOURCE_FILE=""

for arg in "$@"; do
  case "$arg" in
    --pull-only)
      PULL_ONLY=true
      ;;
    --pull-configs)
      PULL_CONFIGS=true
      ;;
    *)
      SOURCE_FILE="$arg"
      ;;
  esac
done

# 1. Always pull and version structure files from the server via rsync
"${SCRIPT_DIR}/scripts/sync_structures.sh"

# 2. Sync configs:
#    - If --pull-only or --pull-configs is passed, or if server_configs/ has no live configs yet, pull from server.
#    - Otherwise on deploy, sanitize local edits into *.config.example and push live configs to server via rsync.
HAS_LOCAL_CONFIGS="$(find "${CONFIGS_ROOT}" -type f \( -name "*.properties" -o -name "*.yml" \) ! -name "*.config.example" 2>/dev/null | head -n 1 || true)"

if [[ "${PULL_ONLY}" == "true" || "${PULL_CONFIGS}" == "true" || -z "${HAS_LOCAL_CONFIGS}" ]]; then
  "${SCRIPT_DIR}/scripts/sync_configs.sh" --pull
else
  "${SCRIPT_DIR}/scripts/sync_configs.sh" --push
fi

if [[ "${PULL_ONLY}" == "true" ]]; then
  echo "Pull and versioning complete (--pull-only)."
  exit 0
fi

# 3. Locate and deploy plugin JAR via rsync
if [[ -z "${SOURCE_FILE}" ]]; then
  if compgen -G "${SCRIPT_DIR}/build/libs/*.jar" > /dev/null; then
    SOURCE_FILE="$(ls -t "${SCRIPT_DIR}"/build/libs/*.jar | grep -v -E '(-sources|-javadoc|-dev)\.jar$' | head -n 1)"
  elif compgen -G "${SCRIPT_DIR}/target/*.jar" > /dev/null; then
    SOURCE_FILE="$(ls -t "${SCRIPT_DIR}"/target/*.jar | grep -v -E '(original-|-sources|-javadoc|-shaded)\.jar$' | head -n 1)"
  fi
fi

if [[ -z "${SOURCE_FILE}" || ! -f "${SOURCE_FILE}" ]]; then
  echo "No built JAR file found yet to deploy (structures and configs were synced)."
  echo "To deploy a JAR: ./deploy.sh [path/to/file.jar]"
  exit 0
fi

DEST_NAME="${PLUGIN_JAR_NAME:-$(basename "${SOURCE_FILE}")}"
REMOTE_TARGET="${REMOTE_CONN}:${DEPLOY_REMOTE_DIR}/${DEST_NAME}"

echo "[plugin] Deploying $(basename "${SOURCE_FILE}") -> ${REMOTE_TARGET} via rsync..."
rsync -az -e "ssh ${SSH_OPTS}" "${SOURCE_FILE}" "${REMOTE_TARGET}"
echo "[plugin] Deployment complete!"
