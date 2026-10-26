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

locate_plugin_jar() {
  if [[ -z "${SOURCE_FILE}" ]]; then
    if compgen -G "${SCRIPT_DIR}/build/libs/*.jar" > /dev/null; then
      SOURCE_FILE="$(ls -t "${SCRIPT_DIR}"/build/libs/*.jar | grep -v -E '(-sources|-javadoc|-dev)\.jar$' | head -n 1)"
    elif compgen -G "${SCRIPT_DIR}/target/*.jar" > /dev/null; then
      SOURCE_FILE="$(ls -t "${SCRIPT_DIR}"/target/*.jar | grep -v -E '(original-|-sources|-javadoc|-shaded)\.jar$' | head -n 1)"
    fi
  fi
}

deploy_steps() {
  # 1. Pull and version structure files from the server via rsync
  instrument INFO "deploy.sync_structures" "${SCRIPT_DIR}/scripts/sync_structures.sh"

  # 2. Build Java & Bedrock resource packs from textures/ (updates resource-pack-sha1 in server.properties before config push)
  if [[ "${PULL_ONLY}" != "true" ]]; then
    instrument INFO "deploy.build_resource_pack" "${SCRIPT_DIR}/scripts/build_resource_pack.sh"
  fi

  # 3. Sync configs
  local has_local_configs
  has_local_configs="$(find "${CONFIGS_ROOT}" -type f \( -name "*.properties" -o -name "*.yml" \) ! -name "*.config.example" 2>/dev/null | head -n 1 || true)"

  if [[ "${PULL_ONLY}" == "true" || "${PULL_CONFIGS}" == "true" || -z "${has_local_configs}" ]]; then
    instrument INFO "deploy.sync_configs" "${SCRIPT_DIR}/scripts/sync_configs.sh" --pull
    if [[ "${PULL_ONLY}" != "true" ]]; then
      # Ensure freshly pulled server.properties gets the latest resource pack SHA1 and push it back
      instrument INFO "deploy.update_resource_pack_config" "${SCRIPT_DIR}/scripts/build_resource_pack.sh"
      instrument INFO "deploy.sync_configs_push" "${SCRIPT_DIR}/scripts/sync_configs.sh" --push
    fi
  else
    instrument INFO "deploy.sync_configs" "${SCRIPT_DIR}/scripts/sync_configs.sh" --push
  fi

  if [[ "${PULL_ONLY}" == "true" ]]; then
    return 0
  fi

  # 4. Ensure systemd service (shycubes.service) is installed and up to date
  instrument INFO "deploy.install_systemd" "${SCRIPT_DIR}/scripts/manage_systemd.sh" --install-only

  # 5. Sync bundled resource packs (.mcpack -> Geyser packs/, .zip/.sha1 -> resourcepacks/)
  instrument INFO "deploy.sync_resource_pack" "${SCRIPT_DIR}/scripts/build_resource_pack.sh" --sync

  # 6. Locate and deploy plugin JAR via rsync
  instrument DEBUG "deploy.locate_jar" locate_plugin_jar

  if [[ -z "${SOURCE_FILE}" || ! -f "${SOURCE_FILE}" ]]; then
    emit_event WARN "deploy.jar.skipped" reason="jar_not_found"
    return 0
  fi

  local dest_name="${PLUGIN_JAR_NAME:-$(basename "${SOURCE_FILE}")}"
  local remote_target="${REMOTE_DIR_TARGET}/${dest_name}"

  instrument INFO "deploy.rsync_jar" run_rsync -az "${SOURCE_FILE}" "${remote_target}"
}

# Entry point span
instrument INFO "deploy.run" deploy_steps
