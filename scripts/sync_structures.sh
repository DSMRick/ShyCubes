#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/common.sh"

CURRENT_STRUCTURES_DIR="${BACKUP_ROOT}/current/structures"
VERSIONS_DIR="${BACKUP_ROOT}/versions"
VERSION_FILE="${BACKUP_ROOT}/.version"

mkdir -p "${VERSIONS_DIR}"

# 1. Version previous structures snapshot if present
if [[ -d "${CURRENT_STRUCTURES_DIR}" ]] && [[ -n "$(ls -A "${CURRENT_STRUCTURES_DIR}" 2>/dev/null || true)" ]]; then
  prev_version=0
  if [[ -f "${VERSION_FILE}" ]]; then
    prev_version="$(cat "${VERSION_FILE}")"
  else
    for d in "${VERSIONS_DIR}"/*; do
      [[ -d "$d" ]] || continue
      base="$(basename "$d")"
      if [[ "$base" =~ ^[0-9]+$ ]] && (( base > prev_version )); then
        prev_version="$base"
      fi
    done
  fi

  next_version=$(( prev_version + 1 ))
  target_version_dir="${VERSIONS_DIR}/${next_version}"

  echo "[structures] Versioning previous snapshot as version ${next_version}..."
  rm -rf "${target_version_dir}"
  mkdir -p "${target_version_dir}"
  cp -a "${CURRENT_STRUCTURES_DIR}/." "${target_version_dir}/"
  echo "${next_version}" > "${VERSION_FILE}"

  # Retention policy:
  # Keep the previous 10 versions (next_version - 9 .. next_version)
  # AND every 10th version (ending in 0: 10, 20, 30, 40, 50, ...)
  # Destroy all other versions.
  cutoff=$(( next_version - 9 ))
  for d in "${VERSIONS_DIR}"/*; do
    [[ -d "$d" ]] || continue
    v="$(basename "$d")"
    if [[ "$v" =~ ^[0-9]+$ ]]; then
      if (( v < cutoff )) && (( v % 10 != 0 )); then
        echo "[structures] Pruning old version ${v}..."
        rm -rf "$d"
      fi
    fi
  done
fi

# 2. Pull structure files via rsync
mkdir -p "${CURRENT_STRUCTURES_DIR}"
echo "[structures] Syncing world/generated/ via rsync from ${REMOTE_CONN}:${DEPLOY_SERVER_ROOT}/world/generated/ ..."
rsync -az --delete -e "ssh ${SSH_OPTS}" \
  "${REMOTE_CONN}:${DEPLOY_SERVER_ROOT}/world/generated/" \
  "${CURRENT_STRUCTURES_DIR}/"

echo "[structures] Structures synced to ${CURRENT_STRUCTURES_DIR}"

