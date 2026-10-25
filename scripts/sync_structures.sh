#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/common.sh"

CURRENT_STRUCTURES_DIR="${BACKUP_ROOT}/current/structures"
VERSIONS_DIR="${BACKUP_ROOT}/versions"
VERSION_FILE="${BACKUP_ROOT}/.version"

NEXT_VERSION=0

version_previous_snapshot() {
  mkdir -p "${VERSIONS_DIR}"

  if [[ -d "${CURRENT_STRUCTURES_DIR}" ]] && [[ -n "$(ls -A "${CURRENT_STRUCTURES_DIR}" 2>/dev/null || true)" ]]; then
    local prev_version=0
    if [[ -f "${VERSION_FILE}" ]]; then
      prev_version="$(cat "${VERSION_FILE}")"
    else
      for d in "${VERSIONS_DIR}"/*; do
        [[ -d "$d" ]] || continue
        local base
        base="$(basename "$d")"
        if [[ "$base" =~ ^[0-9]+$ ]] && (( base > prev_version )); then
          prev_version="$base"
        fi
      done
    fi

    NEXT_VERSION=$(( prev_version + 1 ))
    local target_version_dir="${VERSIONS_DIR}/${NEXT_VERSION}"

    rm -rf "${target_version_dir}"
    mkdir -p "${target_version_dir}"
    cp -a "${CURRENT_STRUCTURES_DIR}/." "${target_version_dir}/"
    echo "${NEXT_VERSION}" > "${VERSION_FILE}"
    emit_event INFO "structures.snapshot.versioned" version="${NEXT_VERSION}"
  fi
}

prune_old_versions() {
  if (( NEXT_VERSION <= 0 )); then
    return 0
  fi

  local cutoff=$(( NEXT_VERSION - 9 ))
  for d in "${VERSIONS_DIR}"/*; do
    [[ -d "$d" ]] || continue
    local v
    v="$(basename "$d")"
    if [[ "$v" =~ ^[0-9]+$ ]]; then
      if (( v < cutoff )) && (( v % 10 != 0 )); then
        rm -rf "$d"
        emit_event INFO "structures.snapshot.pruned" version="${v}"
      fi
    fi
  done
}

run_sync_structures() {
  instrument DEBUG "structures.version_snapshot" version_previous_snapshot
  instrument DEBUG "structures.prune_versions" prune_old_versions

  mkdir -p "${CURRENT_STRUCTURES_DIR}"
  instrument INFO "structures.rsync.pull" rsync -az --delete -e "ssh ${SSH_OPTS}" \
    "${REMOTE_CONN}:${DEPLOY_SERVER_ROOT}/world/generated/" \
    "${CURRENT_STRUCTURES_DIR}/"
}

instrument INFO "structures.sync.run" run_sync_structures
