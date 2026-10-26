#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/common.sh"

MANIFEST_FILE="${CONFIGS_ROOT}/plugins_manifest.json"
RESTART_AFTER="${1:---restart}"

detect_paper_version() {
  local configured_ver
  configured_ver="$(jq -r '.paper.version // "auto"' "${MANIFEST_FILE}")"
  if [[ "${configured_ver}" != "auto" && -n "${configured_ver}" ]]; then
    echo "${configured_ver}"
    return 0
  fi

  # Try reading the current Minecraft version from version_history.json on the server
  local vh_file="${DEPLOY_SERVER_ROOT}/version_history.json"
  if [[ -f "${vh_file}" ]]; then
    local parsed
    parsed="$(python3 - "${vh_file}" <<'PYEOF'
import json, re, sys
try:
    with open(sys.argv[1], "r", encoding="utf-8") as f:
        data = json.load(f)
    curr = data.get("currentVersion", "")
    m = re.search(r"\(MC:\s*([0-9.]+)\)", curr)
    if m:
        print(m.group(1))
    else:
        m2 = re.search(r"paper-([0-9.]+?)-", curr)
        if m2:
            print(m2.group(1))
except Exception:
    pass
PYEOF
)"
    if [[ -n "${parsed}" ]]; then
      echo "${parsed}"
      return 0
    fi
  fi

  # Fallback: query latest version from PaperMC Fill v2 API
  curl -fsSL "https://api.papermc.io/v2/projects/paper" | jq -r '.versions[-1]'
}

update_paper_jar() {
  local project
  project="$(jq -r '.paper.project // "paper"' "${MANIFEST_FILE}")"
  local target_jar="${DEPLOY_SERVER_ROOT}/$(jq -r '.paper.target_jar // "server.jar"' "${MANIFEST_FILE}")"
  local mc_version
  mc_version="$(detect_paper_version)"

  local builds_json
  if ! builds_json="$(curl -fsSL "https://api.papermc.io/v2/projects/${project}/versions/${mc_version}/builds" 2>/dev/null)"; then
    emit_event WARN "update.paper.skipped" version="${mc_version}" reason="version_lookup_failed"
    return 0
  fi

  local latest_build download_name remote_sha256
  latest_build="$(printf '%s' "${builds_json}" | jq -r '.builds[-1].build // empty')"
  download_name="$(printf '%s' "${builds_json}" | jq -r '.builds[-1].downloads.application.name // empty')"
  remote_sha256="$(printf '%s' "${builds_json}" | jq -r '.builds[-1].downloads.application.sha256 // empty')"

  if [[ -z "${latest_build}" || -z "${download_name}" ]]; then
    emit_event WARN "update.paper.skipped" version="${mc_version}" reason="no_build_found"
    return 0
  fi

  local local_sha256=""
  if [[ -f "${target_jar}" ]]; then
    local_sha256="$(sha256sum "${target_jar}" | awk '{print $1}')"
  fi

  if [[ -n "${remote_sha256}" && "${local_sha256}" == "${remote_sha256}" ]]; then
    emit_event INFO "update.paper.up_to_date" version="${mc_version}" build="${latest_build}"
    return 0
  fi

  local download_url="https://api.papermc.io/v2/projects/${project}/versions/${mc_version}/builds/${latest_build}/downloads/${download_name}"
  local tmp_jar="${target_jar}.tmp"

  instrument INFO "http.get.papermc" curl -fsSL -o "${tmp_jar}" "${download_url}"

  if [[ -n "${remote_sha256}" ]]; then
    local downloaded_sha256
    downloaded_sha256="$(sha256sum "${tmp_jar}" | awk '{print $1}')"
    if [[ "${downloaded_sha256}" != "${remote_sha256}" ]]; then
      rm -f "${tmp_jar}"
      emit_event ERROR "update.paper.checksum_mismatch" expected="${remote_sha256}" actual="${downloaded_sha256}"
      return 1
    fi
  fi

  mv -f "${tmp_jar}" "${target_jar}"
  emit_event INFO "update.paper.updated" version="${mc_version}" build="${latest_build}" sha256="${remote_sha256}"
}

update_single_plugin() {
  local item_json="$1"
  local name type target_jar_name target_path
  name="$(printf '%s' "${item_json}" | jq -r '.name')"
  type="$(printf '%s' "${item_json}" | jq -r '.type')"
  target_jar_name="$(printf '%s' "${item_json}" | jq -r '.target_jar')"
  target_path="${DEPLOY_REMOTE_DIR}/${target_jar_name}"

  mkdir -p "${DEPLOY_REMOTE_DIR}"

  local download_url=""
  local remote_sha256=""

  case "${type}" in
    geyser_download)
      local project platform meta_json
      project="$(printf '%s' "${item_json}" | jq -r '.project')"
      platform="$(printf '%s' "${item_json}" | jq -r '.platform // "spigot"')"
      download_url="$(printf '%s' "${item_json}" | jq -r '.url')"
      if meta_json="$(curl -fsSL "https://download.geysermc.org/v2/projects/${project}/versions/latest/builds/latest" 2>/dev/null)"; then
        remote_sha256="$(printf '%s' "${meta_json}" | jq -r ".downloads.${platform}.sha256 // empty")"
      fi
      ;;
    github_release)
      local repo asset_pattern release_json
      repo="$(printf '%s' "${item_json}" | jq -r '.repo')"
      asset_pattern="$(printf '%s' "${item_json}" | jq -r '.asset_pattern // ".*\\.jar$"')"
      if release_json="$(curl -fsSL "https://api.github.com/repos/${repo}/releases/latest" 2>/dev/null)"; then
        download_url="$(printf '%s' "${release_json}" | jq -r --arg pat "${asset_pattern}" '.assets[] | select(.name | test($pat)) | .browser_download_url' | head -n 1)"
      fi
      ;;
    modrinth)
      local project_id loader versions_json
      project_id="$(printf '%s' "${item_json}" | jq -r '.project')"
      loader="$(printf '%s' "${item_json}" | jq -r '.loader // "paper"')"
      if versions_json="$(curl -fsSL "https://api.modrinth.com/v2/project/${project_id}/version?loaders=%5B%22${loader}%22%5D" 2>/dev/null)"; then
        download_url="$(printf '%s' "${versions_json}" | jq -r '.[0].files[0].url // empty')"
        remote_sha256="$(printf '%s' "${versions_json}" | jq -r '.[0].files[0].hashes.sha512 // empty')"
      fi
      ;;
    hangar)
      local project_slug channel versions_json
      project_slug="$(printf '%s' "${item_json}" | jq -r '.project')"
      channel="$(printf '%s' "${item_json}" | jq -r '.channel // "Release"')"
      if versions_json="$(curl -fsSL "https://hangar.papermc.io/api/v1/projects/${project_slug}/versions?channel=${channel}&limit=1" 2>/dev/null)"; then
        download_url="$(printf '%s' "${versions_json}" | jq -r '.result[0].downloads.PAPER.downloadUrl // .result[0].downloads.PAPER.externalUrl // empty')"
        remote_sha256="$(printf '%s' "${versions_json}" | jq -r '.result[0].downloads.PAPER.fileInfo.sha256Hash // empty')"
      fi
      ;;
    direct_url)
      download_url="$(printf '%s' "${item_json}" | jq -r '.url')"
      ;;
    *)
      emit_event WARN "update.plugin.unknown_type" plugin="${name}" type="${type}"
      return 0
      ;;
  esac

  if [[ -z "${download_url}" ]]; then
    emit_event WARN "update.plugin.no_url" plugin="${name}" type="${type}"
    return 0
  fi

  local local_sha256=""
  if [[ -f "${target_path}" ]]; then
    local_sha256="$(sha256sum "${target_path}" | awk '{print $1}')"
  fi

  if [[ -n "${remote_sha256}" && "${#remote_sha256}" -eq 64 && "${local_sha256}" == "${remote_sha256}" ]]; then
    emit_event INFO "update.plugin.up_to_date" plugin="${name}"
    return 0
  fi

  local tmp_jar="${target_path}.tmp"
  if ! instrument INFO "http.get.plugin.${name}" curl -fsSL -L -o "${tmp_jar}" "${download_url}"; then
    rm -f "${tmp_jar}"
    emit_event ERROR "update.plugin.download_failed" plugin="${name}" url="${download_url}"
    return 1
  fi

  local new_sha256
  new_sha256="$(sha256sum "${tmp_jar}" | awk '{print $1}')"
  if [[ "${local_sha256}" == "${new_sha256}" ]]; then
    rm -f "${tmp_jar}"
    emit_event INFO "update.plugin.up_to_date" plugin="${name}"
    return 0
  fi

  mv -f "${tmp_jar}" "${target_path}"
  emit_event INFO "update.plugin.updated" plugin="${name}" sha256="${new_sha256}"
}

run_update_all() {
  if [[ ! -f "${MANIFEST_FILE}" ]]; then
    emit_event ERROR "update.manifest.missing" path="${MANIFEST_FILE}"
    return 1
  fi

  instrument DEBUG "update.paper" update_paper_jar

  local count
  count="$(jq '.plugins | length' "${MANIFEST_FILE}")"
  local i=0
  while (( i < count )); do
    local item
    item="$(jq -c ".plugins[${i}]" "${MANIFEST_FILE}")"
    local name
    name="$(printf '%s' "${item}" | jq -r '.name')"
    instrument DEBUG "update.plugin.${name}" update_single_plugin "${item}"
    (( i++ ))
  done

  if [[ "${RESTART_AFTER}" == "--restart" ]]; then
    instrument INFO "update.restart_service" "${SCRIPT_DIR}/manage_systemd.sh" --restart
  fi
}

instrument INFO "update.server_and_plugins.run" run_update_all

