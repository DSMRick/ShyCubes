#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/common.sh"

stage_synced_files() {
  cd "${REPO_ROOT}"
  git add "${BACKUP_ROOT}" 2>/dev/null || true
  while IFS= read -r -d '' file; do
    git add "${file}"
  done < <(find "${CONFIGS_ROOT}" -type f -name "*.config.example" -print0 2>/dev/null)
}

commit_and_push() {
  cd "${REPO_ROOT}"

  instrument DEBUG "git.stage_files" stage_synced_files

  if git diff --cached --quiet; then
    emit_event INFO "git.sync.no_changes"
    return 0
  fi

  if [[ -z "$(git config user.name || true)" ]]; then
    git config user.name "${GITHUB_ACTOR:-shycubes-bot}"
  fi
  if [[ -z "$(git config user.email || true)" ]]; then
    git config user.email "${GITHUB_ACTOR:-shycubes-bot}@users.noreply.github.com"
  fi

  local version_num="unknown"
  if [[ -f "${BACKUP_ROOT}/.version" ]]; then
    version_num="$(cat "${BACKUP_ROOT}/.version")"
  fi

  local commit_msg="chore(sync): snapshot structures v${version_num} and sanitized configs [skip ci]"
  instrument INFO "git.commit" git commit -m "${commit_msg}"

  local target_branch="${GITHUB_REF_NAME:-main}"
  instrument INFO "git.push" git push origin "HEAD:${target_branch}"
  emit_event INFO "git.sync.pushed" branch="${target_branch}" structures_version="${version_num}"
}

instrument INFO "git.sync.run" commit_and_push

