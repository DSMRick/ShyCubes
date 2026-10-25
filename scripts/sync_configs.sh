#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/common.sh"

MODE="${1:---pull}"

pull_configs() {
  mkdir -p "${CONFIGS_ROOT}"

  instrument INFO "configs.rsync.pull" rsync -az --prune-empty-dirs -e "ssh ${SSH_OPTS}" \
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

  instrument DEBUG "configs.sanitize" "${SCRIPT_DIR}/sanitize_configs.sh"
}

push_configs() {
  instrument DEBUG "configs.sanitize" "${SCRIPT_DIR}/sanitize_configs.sh"

  instrument INFO "configs.rsync.push" rsync -az \
    --exclude="*.config.example" \
    --exclude="README.md" \
    --exclude=".DS_Store" \
    -e "ssh ${SSH_OPTS}" \
    "${CONFIGS_ROOT}/" \
    "${REMOTE_CONN}:${DEPLOY_SERVER_ROOT}/"
}

run_sync_configs() {
  if [[ "${MODE}" == "--pull" ]]; then
    pull_configs
  elif [[ "${MODE}" == "--push" ]]; then
    push_configs
  else
    emit_event ERROR "configs.sync.invalid_mode" mode="${MODE}"
    return 1
  fi
}

instrument INFO "configs.sync.run" run_sync_configs
