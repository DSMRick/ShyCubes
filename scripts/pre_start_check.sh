#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/common.sh"

find_local_repo_checkout() {
  local candidates=(
    "/home/opc/actions-runner/_work/ShyCubes/ShyCubes"
    "/home/opc/ShyCubes"
    "${REPO_ROOT}"
  )
  local dir
  for dir in "${candidates[@]}"; do
    if [[ -d "${dir}/.git" && -f "${dir}/deploy.sh" ]]; then
      echo "${dir}"
      return 0
    fi
  done
  return 1
}

sync_and_deploy_from_git() {
  local repo_dir=""
  if ! repo_dir="$(find_local_repo_checkout)"; then
    emit_event WARN "startup.deploy.skipped" reason="no_local_git_checkout"
    return 0
  fi

  cd "${repo_dir}"

  if instrument INFO "startup.git.fetch" git fetch --prune origin main 2>/dev/null; then
    instrument DEBUG "startup.git.reset" git reset --hard origin/main
  else
    emit_event WARN "startup.git.fetch_failed" repo="${repo_dir}"
  fi

  if [[ -f "./gradlew" ]]; then
    instrument DEBUG "startup.build.gradle" ./gradlew build -x test
  elif [[ -f "./pom.xml" ]]; then
    instrument DEBUG "startup.build.maven" mvn -B package -DskipTests
  fi

  DEPLOY_LOCAL="true" SKIP_SYSTEMD_INSTALL="true" \
    instrument INFO "startup.deploy" ./deploy.sh --pull-configs

  DEPLOY_LOCAL="true" \
    instrument INFO "startup.git.commit_sync" ./scripts/commit_and_push_sync.sh || true
}

run_pre_start() {
  # 1. Pull latest repo changes, build/deploy ShyCubes & resource packs, and sync structures/configs
  instrument DEBUG "startup.sync_and_deploy" sync_and_deploy_from_git

  # 2. Check and update Paper, Geyser-Spigot, Floodgate, and any manifest plugins
  instrument INFO "startup.update_check" "${SCRIPT_DIR}/update_server_and_plugins.sh" --no-restart
}

instrument INFO "server.pre_start.run" run_pre_start

