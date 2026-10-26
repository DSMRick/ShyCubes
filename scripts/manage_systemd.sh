#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/common.sh"

SERVICE_NAME="shycubes.service"
UPDATE_SERVICE_NAME="shycubes-update.service"
UPDATE_TIMER_NAME="shycubes-update.timer"
SERVICE_FILE_LOCAL="${CONFIGS_ROOT}/${SERVICE_NAME}"
UPDATE_SERVICE_LOCAL="${CONFIGS_ROOT}/${UPDATE_SERVICE_NAME}"
UPDATE_TIMER_LOCAL="${CONFIGS_ROOT}/${UPDATE_TIMER_NAME}"
OTEL_WRAPPER_LOCAL="${CONFIGS_ROOT}/run_server_with_otel.sh"
MANIFEST_LOCAL="${CONFIGS_ROOT}/plugins_manifest.json"

install_service() {
  if [[ ! -f "${SERVICE_FILE_LOCAL}" ]]; then
    emit_event ERROR "systemd.install.missing_unit" path="${SERVICE_FILE_LOCAL}"
    return 1
  fi

  # Sync unit files, OTel wrapper, start.sh, manifest, and scripts to /home/opc/mc
  instrument DEBUG "systemd.rsync_unit" run_rsync -az \
    "${SERVICE_FILE_LOCAL}" \
    "${UPDATE_SERVICE_LOCAL}" \
    "${UPDATE_TIMER_LOCAL}" \
    "${OTEL_WRAPPER_LOCAL}" \
    "${MANIFEST_LOCAL}" \
    "${CONFIGS_ROOT}/start.sh" \
    "${SERVER_ROOT_TARGET}/"

  if [[ "${DEPLOY_LOCAL}" == "true" ]]; then
    mkdir -p "${DEPLOY_SERVER_ROOT}/scripts"
  else
    instrument DEBUG "systemd.mkdir_scripts" run_remote_cmd "mkdir -p '${DEPLOY_SERVER_ROOT}/scripts'"
  fi

  instrument DEBUG "systemd.rsync_scripts" run_rsync -az \
    "${SCRIPT_DIR}/common.sh" \
    "${SCRIPT_DIR}/manage_systemd.sh" \
    "${SCRIPT_DIR}/update_server_and_plugins.sh" \
    "${SERVER_ROOT_TARGET}/scripts/"

  # Install unit and timer files into /etc/systemd/system/ and enable both the server and the 4:00 AM CT update timer
  instrument INFO "systemd.install_unit" run_remote_cmd \
    sudo cp -f \
      "${DEPLOY_SERVER_ROOT}/${SERVICE_NAME}" \
      "${DEPLOY_SERVER_ROOT}/${UPDATE_SERVICE_NAME}" \
      "${DEPLOY_SERVER_ROOT}/${UPDATE_TIMER_NAME}" \
      "/etc/systemd/system/"

  instrument INFO "systemd.enable_unit" run_remote_cmd \
    sudo bash -c "chmod 644 /etc/systemd/system/${SERVICE_NAME} /etc/systemd/system/${UPDATE_SERVICE_NAME} /etc/systemd/system/${UPDATE_TIMER_NAME} && chmod +x ${DEPLOY_SERVER_ROOT}/start.sh ${DEPLOY_SERVER_ROOT}/run_server_with_otel.sh ${DEPLOY_SERVER_ROOT}/scripts/*.sh && systemctl daemon-reload && systemctl enable ${SERVICE_NAME} && systemctl enable --now ${UPDATE_TIMER_NAME}"
}

restart_service() {
  install_service

  # Stop any unmanaged (orphan) server.jar process not already managed by systemd
  local main_pid
  main_pid="$(run_remote_cmd systemctl show -p MainPID --value "${SERVICE_NAME}" 2>/dev/null || echo 0)"
  if [[ "${main_pid}" == "0" || -z "${main_pid}" ]]; then
    instrument DEBUG "systemd.cleanup_unmanaged" run_remote_cmd \
      bash -c 'pids=$(pgrep -f "java.*server\.jar" || true); if [[ -n "$pids" ]]; then kill -TERM $pids 2>/dev/null || true; for i in {1..25}; do pgrep -f "java.*server\.jar" >/dev/null || break; sleep 1; done; pkill -9 -f "java.*server\.jar" 2>/dev/null || true; fi'
  fi

  instrument INFO "systemd.restart" run_remote_cmd \
    sudo systemctl restart "${SERVICE_NAME}"

  local new_pid
  new_pid="$(run_remote_cmd systemctl show -p MainPID --value "${SERVICE_NAME}" 2>/dev/null || echo 0)"
  if [[ -z "${new_pid}" || "${new_pid}" == "0" ]]; then
    emit_event ERROR "server.restart.failed" reason="systemd_main_pid_zero"
    return 1
  fi

  emit_event INFO "server.restart.completed" pid="${new_pid}" service="${SERVICE_NAME}"
}

MODE="${1:---restart}"
if [[ "${MODE}" == "--install-only" ]]; then
  instrument INFO "systemd.setup.run" install_service
else
  instrument INFO "systemd.restart.run" restart_service
fi
