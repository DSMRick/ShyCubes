#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
source "${SCRIPT_DIR}/common.sh"

TEXTURES_ROOT="${REPO_ROOT}/textures"
DIST_ROOT="${REPO_ROOT}/resourcepacks/dist"
JAVA_ZIP="${DIST_ROOT}/shycubes-java.zip"
JAVA_SHA1_FILE="${DIST_ROOT}/shycubes-java.zip.sha1"
BEDROCK_MCPACK="${DIST_ROOT}/shycubes-bedrock.mcpack"
BEDROCK_SHA256_FILE="${DIST_ROOT}/shycubes-bedrock.mcpack.sha256"

python3 - "${TEXTURES_ROOT}" "${JAVA_ZIP}" "${BEDROCK_MCPACK}" <<'PYEOF'
import hashlib
import os
import shutil
import sys
import tempfile
import zipfile

textures_root, java_zip, bedrock_zip = sys.argv[1:4]
painting_root = os.path.join(textures_root, "painting")
if not os.path.isdir(painting_root):
    sys.exit(0)

paintings = []
for root, dirs, files in os.walk(painting_root):
    dirs.sort()
    files.sort()
    for name in files:
        if name.lower().endswith(".png"):
            paintings.append(os.path.join(root, name))

if not paintings:
    sys.exit(0)

fixed_dt = (2026, 1, 1, 0, 0, 0)

def add_paintings(src_zip, java_pack):
    if not os.path.isfile(src_zip):
        return

    with tempfile.TemporaryDirectory() as tmp:
        with zipfile.ZipFile(src_zip, "r") as zf:
            zf.extractall(tmp)

        for source in paintings:
            rel = os.path.relpath(source, painting_root).replace(os.sep, "/")
            if java_pack:
                destination = os.path.join(tmp, "assets", "minecraft", "textures", "painting", *rel.split("/"))
            else:
                destination = os.path.join(tmp, "textures", "painting", *rel.split("/"))
            os.makedirs(os.path.dirname(destination), exist_ok=True)
            shutil.copyfile(source, destination)

        os.remove(src_zip)
        with zipfile.ZipFile(src_zip, "w", compression=zipfile.ZIP_DEFLATED) as zf:
            for root, dirs, files in os.walk(tmp):
                dirs.sort()
                files.sort()
                for name in files:
                    full = os.path.join(root, name)
                    rel = os.path.relpath(full, tmp).replace(os.sep, "/")
                    with open(full, "rb") as f:
                        data = f.read()
                    info = zipfile.ZipInfo(rel, fixed_dt)
                    info.compress_type = zipfile.ZIP_DEFLATED
                    info.external_attr = 0o644 << 16
                    zf.writestr(info, data)

add_paintings(java_zip, True)
add_paintings(bedrock_zip, False)

if os.path.isfile(java_zip):
    with open(java_zip, "rb") as f:
        sha1 = hashlib.sha1(f.read()).hexdigest()
    with open(java_zip + ".sha1", "w", encoding="utf-8") as f:
        f.write(sha1 + "\n")

if os.path.isfile(bedrock_zip):
    with open(bedrock_zip, "rb") as f:
        sha256 = hashlib.sha256(f.read()).hexdigest()
    with open(bedrock_zip + ".sha256", "w", encoding="utf-8") as f:
        f.write(sha256 + "\n")
PYEOF

if [[ -f "${JAVA_SHA1_FILE}" ]]; then
  java_sha1="$(tr -d ' \n\r' < "${JAVA_SHA1_FILE}")"
  props_file="${CONFIGS_ROOT}/server.properties"
  if [[ -f "${props_file}" ]]; then
    pack_url="${RESOURCE_PACK_URL:-https://raw.githubusercontent.com/DSMRick/ShyCubes/main/resourcepacks/dist/shycubes-java.zip}?v=${java_sha1:0:12}"
    escaped_url="${pack_url//:/\\:}"
    python3 - "${props_file}" "${escaped_url}" "${java_sha1}" <<'PYEOF'
import sys
props_file, escaped_url, java_sha1 = sys.argv[1:4]
with open(props_file, "r", encoding="utf-8") as f:
    lines = f.read().splitlines()
out = []
for line in lines:
    if line.startswith("resource-pack="):
        out.append(f"resource-pack={escaped_url}")
    elif line.startswith("resource-pack-sha1="):
        out.append(f"resource-pack-sha1={java_sha1}")
    else:
        out.append(line)
with open(props_file, "w", encoding="utf-8") as f:
    f.write("\n".join(out) + "\n")
PYEOF
  fi
fi

if [[ "${1:-}" == "--sync" ]]; then
  run_rsync -az "${BEDROCK_MCPACK}" "${REMOTE_DIR_TARGET}/Geyser-Spigot/packs/shycubes-bedrock.mcpack"
  run_rsync -az "${JAVA_ZIP}" "${JAVA_SHA1_FILE}" "${SERVER_ROOT_TARGET}/resourcepacks/"
fi
