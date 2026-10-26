#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/common.sh"

TEXTURES_ROOT="${REPO_ROOT}/textures"
DIST_ROOT="${REPO_ROOT}/resourcepacks/dist"
JAVA_ZIP="${DIST_ROOT}/shycubes-java.zip"
JAVA_SHA1_FILE="${DIST_ROOT}/shycubes-java.zip.sha1"
BEDROCK_MCPACK="${DIST_ROOT}/shycubes-bedrock.mcpack"
BEDROCK_SHA256_FILE="${DIST_ROOT}/shycubes-bedrock.mcpack.sha256"
MAPPINGS_OUT_DIR="${DIST_ROOT}/custom_mappings"

# Default public URL for Java clients (GitHub raw URL on main branch; can be overridden via RESOURCE_PACK_URL in .deploy.env)
DEFAULT_PACK_URL="https://raw.githubusercontent.com/DSMRick/ShyCubes/main/resourcepacks/dist/shycubes-java.zip"
PACK_URL="${RESOURCE_PACK_URL:-${DEFAULT_PACK_URL}}"

generate_packs_from_textures() {
  mkdir -p "${DIST_ROOT}" "${MAPPINGS_OUT_DIR}"

  python3 - "${TEXTURES_ROOT}" "${DIST_ROOT}" <<'PYEOF'
import hashlib
import json
import os
import shutil
import struct
import sys
import tempfile
import zipfile
import zlib

textures_root = sys.argv[1]
dist_root = sys.argv[2]

pack_meta_path = os.path.join(textures_root, "pack.json")
with open(pack_meta_path, "r", encoding="utf-8") as f:
    meta = json.load(f)

mappings_path = os.path.join(textures_root, "mappings.json")
mappings_cfg = {"items": []}
if os.path.isfile(mappings_path):
    with open(mappings_path, "r", encoding="utf-8") as f:
        mappings_cfg = json.load(f)

def make_default_icon(path):
    # Generate a clean 64x64 teal/purple ShyCubes PNG icon if textures/pack.png doesn't exist
    width, height = 64, 64
    raw_rows = []
    for y in range(height):
        row = bytearray([0])  # filter type 0
        for x in range(width):
            border = x < 4 or x >= width - 4 or y < 4 or y >= height - 4
            inner = 16 <= x < 48 and 16 <= y < 48
            if border:
                r, g, b, a = (45, 30, 80, 255)
            elif inner:
                r, g, b, a = (110, 200, 230, 255)
            else:
                r, g, b, a = (75, 55, 135, 255)
            row.extend((r, g, b, a))
        raw_rows.append(bytes(row))
    raw_data = b"".join(raw_rows)

    def chunk(chunk_type, data):
        crc = zlib.crc32(chunk_type + data) & 0xFFFFFFFF
        return struct.pack(">I", len(data)) + chunk_type + data + struct.pack(">I", crc)

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    idat = zlib.compress(raw_data, 9)
    png_bytes = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", idat)
        + chunk(b"IEND", b"")
    )
    with open(path, "wb") as out:
        out.write(png_bytes)

icon_path = os.path.join(textures_root, "pack.png")
if not os.path.isfile(icon_path):
    make_default_icon(icon_path)

for subdir in ("items", "blocks", "entity", "gui"):
    os.makedirs(os.path.join(textures_root, subdir), exist_ok=True)
    gitkeep = os.path.join(textures_root, subdir, ".gitkeep")
    if not os.listdir(os.path.join(textures_root, subdir)):
        open(gitkeep, "a").close()

def write_deterministic_zip(src_dir, out_zip_path):
    if os.path.exists(out_zip_path):
        os.remove(out_zip_path)
    fixed_dt = (2026, 1, 1, 0, 0, 0)
    with zipfile.ZipFile(out_zip_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for root, dirs, files in os.walk(src_dir):
            dirs.sort()
            files.sort()
            for fname in files:
                full_path = os.path.join(root, fname)
                rel_path = os.path.relpath(full_path, src_dir).replace(os.sep, "/")
                with open(full_path, "rb") as f:
                    data = f.read()
                zi = zipfile.ZipInfo(filename=rel_path, date_time=fixed_dt)
                zi.compress_type = zipfile.ZIP_DEFLATED
                zi.external_attr = 0o644 << 16
                zf.writestr(zi, data)

with tempfile.TemporaryDirectory() as tmpdir:
    java_dir = os.path.join(tmpdir, "java")
    bedrock_dir = os.path.join(tmpdir, "bedrock")
    os.makedirs(java_dir, exist_ok=True)
    os.makedirs(bedrock_dir, exist_ok=True)

    # 1. Java pack.mcmeta & pack.png
    pack_mcmeta = {
        "pack": {
            "pack_format": meta.get("java_pack_format", 46),
            "supported_formats": {
                "min_inclusive": meta.get("java_supported_formats", [15, 64])[0],
                "max_inclusive": meta.get("java_supported_formats", [15, 64])[1],
            },
            "description": meta.get("description", "ShyCubes Resource Pack"),
        }
    }
    with open(os.path.join(java_dir, "pack.mcmeta"), "w", encoding="utf-8") as f:
        json.dump(pack_mcmeta, f, indent=2, sort_keys=True)
        f.write("\n")
    shutil.copyfile(icon_path, os.path.join(java_dir, "pack.png"))

    # 2. Bedrock manifest.json & pack_icon.png
    manifest = {
        "format_version": 2,
        "header": {
            "name": meta.get("name", "ShyCubes Resource Pack"),
            "description": meta.get("description", "ShyCubes Resource Pack"),
            "uuid": meta.get("bedrock_header_uuid", "b9d8e5f3-82c4-4a6b-8d32-7f4a9c5e3202"),
            "version": meta.get("version", [1, 0, 0]),
            "min_engine_version": meta.get("bedrock_min_engine_version", [1, 21, 0]),
        },
        "modules": [
            {
                "type": "resources",
                "description": meta.get("description", "ShyCubes Resource Pack"),
                "uuid": meta.get("bedrock_module_uuid", "c1e7f6a4-93d5-4b7c-9e43-8a5b0d6f4303"),
                "version": meta.get("version", [1, 0, 0]),
            }
        ],
    }
    with open(os.path.join(bedrock_dir, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, sort_keys=True)
        f.write("\n")
    shutil.copyfile(icon_path, os.path.join(bedrock_dir, "pack_icon.png"))

    item_texture_data = {}
    terrain_texture_data = {}

    # 3. Convert textures: Java vs Bedrock layout
    category_map = [
        ("items", "assets/minecraft/textures/item", "textures/items", item_texture_data),
        ("blocks", "assets/minecraft/textures/block", "textures/blocks", terrain_texture_data),
        ("entity", "assets/minecraft/textures/entity", "textures/entity", None),
        ("gui", "assets/minecraft/textures/gui", "textures/ui", None),
    ]

    texture_count = 0
    for src_sub, java_sub, bedrock_sub, atlas_dict in category_map:
        src_folder = os.path.join(textures_root, src_sub)
        if not os.path.isdir(src_folder):
            continue
        for root, dirs, files in os.walk(src_folder):
            dirs.sort()
            files.sort()
            for fname in files:
                if not fname.lower().endswith(".png"):
                    continue
                texture_count += 1
                full_src = os.path.join(root, fname)
                rel_from_cat = os.path.relpath(full_src, src_folder).replace(os.sep, "/")
                stem = os.path.splitext(rel_from_cat)[0]
                short_name = stem.replace("/", "_")

                java_dest = os.path.join(java_dir, java_sub, rel_from_cat)
                os.makedirs(os.path.dirname(java_dest), exist_ok=True)
                shutil.copyfile(full_src, java_dest)

                # Optional .mcmeta animation file for Java
                if os.path.isfile(full_src + ".mcmeta"):
                    shutil.copyfile(full_src + ".mcmeta", java_dest + ".mcmeta")

                bedrock_dest = os.path.join(bedrock_dir, bedrock_sub, rel_from_cat)
                os.makedirs(os.path.dirname(bedrock_dest), exist_ok=True)
                shutil.copyfile(full_src, bedrock_dest)

                if atlas_dict is not None:
                    atlas_dict[short_name] = {
                        "textures": f"{bedrock_sub}/{stem}"
                    }

    # 4. Process custom item mappings (Java models + 1.21.4+ items + Geyser v2 mappings)
    geyser_items = {}
    by_base_item = {}
    for entry in mappings_cfg.get("items", []):
        name = entry["name"]
        base_item = entry.get("base_item", "minecraft:paper")
        if not base_item.startswith("minecraft:"):
            base_item = "minecraft:" + base_item
        base_short = base_item.split(":", 1)[1]
        cmd = int(entry["custom_model_data"])
        display_name = entry.get("display_name", name)
        parent_model = entry.get("parent_model", "minecraft:item/generated")
        icon = entry.get("icon", name)

        if icon not in item_texture_data:
            item_texture_data[icon] = {"textures": f"textures/items/{icon}"}

        # Java custom model file
        custom_model_path = os.path.join(
            java_dir, "assets/minecraft/models/item", f"{name}.json"
        )
        os.makedirs(os.path.dirname(custom_model_path), exist_ok=True)
        with open(custom_model_path, "w", encoding="utf-8") as f:
            json.dump(
                {
                    "parent": parent_model,
                    "textures": {"layer0": f"minecraft:item/{icon}"},
                },
                f,
                indent=2,
                sort_keys=True,
            )
            f.write("\n")

        by_base_item.setdefault(base_short, []).append((cmd, name))
        geyser_items.setdefault(base_item, []).append(
            {
                "name": name,
                "custom_model_data": cmd,
                "display_name": display_name,
                "icon": icon,
            }
        )

    for base_short, overrides in by_base_item.items():
        overrides.sort(key=lambda x: x[0])
        # Legacy models/item/<base>.json overrides
        base_model_path = os.path.join(
            java_dir, "assets/minecraft/models/item", f"{base_short}.json"
        )
        os.makedirs(os.path.dirname(base_model_path), exist_ok=True)
        with open(base_model_path, "w", encoding="utf-8") as f:
            json.dump(
                {
                    "parent": "minecraft:item/generated",
                    "textures": {"layer0": f"minecraft:item/{base_short}"},
                    "overrides": [
                        {
                            "predicate": {"custom_model_data": cmd},
                            "model": f"minecraft:item/{name}",
                        }
                        for cmd, name in overrides
                    ],
                },
                f,
                indent=2,
                sort_keys=True,
            )
            f.write("\n")

        # Modern 1.21.4+ assets/minecraft/items/<base>.json range_dispatch
        modern_item_path = os.path.join(
            java_dir, "assets/minecraft/items", f"{base_short}.json"
        )
        os.makedirs(os.path.dirname(modern_item_path), exist_ok=True)
        with open(modern_item_path, "w", encoding="utf-8") as f:
            json.dump(
                {
                    "model": {
                        "type": "minecraft:range_dispatch",
                        "property": "minecraft:custom_model_data",
                        "fallback": {
                            "type": "minecraft:model",
                            "model": f"minecraft:item/{base_short}",
                        },
                        "entries": [
                            {
                                "threshold": cmd,
                                "model": {
                                    "type": "minecraft:model",
                                    "model": f"minecraft:item/{name}",
                                },
                            }
                            for cmd, name in overrides
                        ],
                    }
                },
                f,
                indent=2,
                sort_keys=True,
            )
            f.write("\n")

    # 5. Write Bedrock texture atlases
    os.makedirs(os.path.join(bedrock_dir, "textures"), exist_ok=True)
    with open(
        os.path.join(bedrock_dir, "textures/item_texture.json"), "w", encoding="utf-8"
    ) as f:
        json.dump(
            {
                "resource_pack_name": "shycubes",
                "texture_name": "atlas.items",
                "texture_data": item_texture_data,
            },
            f,
            indent=2,
            sort_keys=True,
        )
        f.write("\n")

    with open(
        os.path.join(bedrock_dir, "textures/terrain_texture.json"),
        "w",
        encoding="utf-8",
    ) as f:
        json.dump(
            {
                "resource_pack_name": "shycubes",
                "texture_name": "atlas.terrain",
                "padding": 8,
                "num_mip_levels": 4,
                "texture_data": terrain_texture_data,
            },
            f,
            indent=2,
            sort_keys=True,
        )
        f.write("\n")

    # 6. Write Geyser custom mappings JSON if custom items exist (else clean up)
    mappings_out_file = os.path.join(
        dist_root, "custom_mappings", "shycubes_mappings.json"
    )
    if geyser_items:
        with open(mappings_out_file, "w", encoding="utf-8") as f:
            json.dump(
                {"format_version": 2, "items": geyser_items},
                f,
                indent=2,
                sort_keys=True,
            )
            f.write("\n")
    elif os.path.exists(mappings_out_file):
        os.remove(mappings_out_file)

    # 7. Bundle deterministic archives and compute SHA hashes
    java_zip = os.path.join(dist_root, "shycubes-java.zip")
    bedrock_mcpack = os.path.join(dist_root, "shycubes-bedrock.mcpack")
    write_deterministic_zip(java_dir, java_zip)
    write_deterministic_zip(bedrock_dir, bedrock_mcpack)

    with open(java_zip, "rb") as f:
        java_sha1 = hashlib.sha1(f.read()).hexdigest()
    with open(java_zip + ".sha1", "w", encoding="utf-8") as f:
        f.write(java_sha1 + "\n")

    with open(bedrock_mcpack, "rb") as f:
        bedrock_sha256 = hashlib.sha256(f.read()).hexdigest()
    with open(bedrock_mcpack + ".sha256", "w", encoding="utf-8") as f:
        f.write(bedrock_sha256 + "\n")
PYEOF
}

update_server_properties_resource_pack() {
  local props_file="${CONFIGS_ROOT}/server.properties"
  if [[ ! -f "${props_file}" ]]; then
    return 0
  fi

  local java_sha1
  java_sha1="$(tr -d ' \n\r' < "${JAVA_SHA1_FILE}")"
  local pack_uuid
  pack_uuid="$(jq -r '.java_pack_uuid // "a8c9d4e2-71b3-4f5a-9c21-6e3f8b4d2101"' "${TEXTURES_ROOT}/pack.json")"

  # Escape colons for Minecraft server.properties format (https\://...)
  local escaped_url="${PACK_URL//:/\\:}"

  python3 - "${props_file}" "${escaped_url}" "${pack_uuid}" "${java_sha1}" <<'PYEOF'
import sys

props_file, escaped_url, pack_uuid, java_sha1 = sys.argv[1:5]
with open(props_file, "r", encoding="utf-8") as f:
    lines = f.read().splitlines()

out = []
for line in lines:
    if line.startswith("resource-pack="):
        out.append(f"resource-pack={escaped_url}")
    elif line.startswith("resource-pack-id="):
        out.append(f"resource-pack-id={pack_uuid}")
    elif line.startswith("resource-pack-sha1="):
        out.append(f"resource-pack-sha1={java_sha1}")
    else:
        out.append(line)

with open(props_file, "w", encoding="utf-8") as f:
    f.write("\n".join(out) + "\n")
PYEOF

  # Re-sanitize configs so server.properties.config.example stays in sync
  "${SCRIPT_DIR}/sanitize_configs.sh"
}

sync_resource_packs_to_server() {
  local remote_geyser_packs="${REMOTE_DIR_TARGET}/Geyser-Spigot/packs"
  local remote_geyser_mappings="${REMOTE_DIR_TARGET}/Geyser-Spigot/custom_mappings"
  local remote_java_packs="${SERVER_ROOT_TARGET}/resourcepacks"

  if [[ "${DEPLOY_LOCAL}" == "true" ]]; then
    mkdir -p "${DEPLOY_REMOTE_DIR}/Geyser-Spigot/packs" \
             "${DEPLOY_REMOTE_DIR}/Geyser-Spigot/custom_mappings" \
             "${DEPLOY_SERVER_ROOT}/resourcepacks"
  else
    instrument DEBUG "resourcepack.mkdir_remote" run_remote_cmd \
      "mkdir -p '${DEPLOY_REMOTE_DIR}/Geyser-Spigot/packs' '${DEPLOY_REMOTE_DIR}/Geyser-Spigot/custom_mappings' '${DEPLOY_SERVER_ROOT}/resourcepacks'"
  fi

  # Push Bedrock .mcpack into plugins/Geyser-Spigot/packs/
  instrument INFO "resourcepack.rsync.bedrock" run_rsync -az \
    "${BEDROCK_MCPACK}" \
    "${remote_geyser_packs}/shycubes-bedrock.mcpack"

  # Push Geyser custom_mappings if present
  if [[ -f "${MAPPINGS_OUT_DIR}/shycubes_mappings.json" ]]; then
    instrument INFO "resourcepack.rsync.mappings" run_rsync -az \
      "${MAPPINGS_OUT_DIR}/shycubes_mappings.json" \
      "${remote_geyser_mappings}/shycubes_mappings.json"
  fi

  # Push Java .zip and .sha1 into /home/opc/mc/resourcepacks/
  instrument INFO "resourcepack.rsync.java" run_rsync -az \
    "${JAVA_ZIP}" "${JAVA_SHA1_FILE}" \
    "${remote_java_packs}/"
}

run_build_resource_pack() {
  instrument DEBUG "resourcepack.generate" generate_packs_from_textures
  instrument DEBUG "resourcepack.update_server_properties" update_server_properties_resource_pack

  local java_sha1 bedrock_sha256
  java_sha1="$(tr -d ' \n\r' < "${JAVA_SHA1_FILE}")"
  bedrock_sha256="$(tr -d ' \n\r' < "${BEDROCK_SHA256_FILE}")"

  emit_event INFO "resourcepack.built" \
    java_sha1="${java_sha1}" \
    bedrock_sha256="${bedrock_sha256}" \
    pack_url="${PACK_URL}"

  if [[ "${1:-}" == "--sync" ]]; then
    sync_resource_packs_to_server
  fi
}

instrument INFO "resourcepack.run" run_build_resource_pack "${1:-}"
