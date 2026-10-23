#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/common.sh"

MODE="${1:---pull}"

mkdir -p "${CONFIGS_ROOT}"

if [[ "${MODE}" == "--pull" ]]; then
  echo "[configs] Pulling server configs via rsync into ${CONFIGS_ROOT}..."

  rsync -az --prune-empty-dirs -e "ssh ${SSH_OPTS}" \
    --include="/start.sh" \
    --include="/server.properties" \
    --include="/bukkit.yml" \
    --include="/spigot.yml" \
    --include="/config/" \
    --include="/config/paper-global.yml" \
    --include="/config/paper-world-defaults.yml" \
    --include="/plugins/" \
    --include="/plugins/*/" \
    --include="/plugins/*/config.yml" \
    --exclude="*" \
    "${REMOTE_CONN}:${DEPLOY_SERVER_ROOT}/" \
    "${CONFIGS_ROOT}/"

  "${SCRIPT_DIR}/sanitize_configs.sh"
  echo "[configs] Pull and sanitization complete."

elif [[ "${MODE}" == "--push" ]]; then
  # Ensure *.config.example files reflect any local edits made before pushing
  "${SCRIPT_DIR}/sanitize_configs.sh"

  echo "[configs] Pushing local configs from ${CONFIGS_ROOT} to ${REMOTE_CONN}:${DEPLOY_SERVER_ROOT} via rsync..."
  rsync -az \
    --exclude="*.config.example" \
    --exclude="README.md" \
    --exclude=".DS_Store" \
    -e "ssh ${SSH_OPTS}" \
    "${CONFIGS_ROOT}/" \
    "${REMOTE_CONN}:${DEPLOY_SERVER_ROOT}/"

  echo "[configs] Push complete."
else
  echo "Usage: $0 [--pull | --push]"
  exit 1
fi

