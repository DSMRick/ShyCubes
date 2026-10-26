# ShyCubes Resource Pack & Textures (`textures/`)

This directory holds the single-source-of-truth textures and metadata for **ShyCubes**.
During deployment (`./deploy.sh` or `./scripts/build_resource_pack.sh`), this folder is compiled into:

1. **Java Edition Resource Pack**: `resourcepacks/dist/shycubes-java.zip` (+ `.sha1`)
   - Automatically updates `resource-pack`, `resource-pack-id`, and `resource-pack-sha1` in `server_configs/server.properties`.
   - Synced to `/home/opc/mc/resourcepacks/shycubes-java.zip` on the server and tracked in `resourcepacks/dist/` so GitHub Raw (`https://raw.githubusercontent.com/DSMRick/ShyCubes/main/resourcepacks/dist/shycubes-java.zip`) can serve it directly to Java clients.
2. **Bedrock Edition Resource Pack (`.mcpack`)**: `resourcepacks/dist/shycubes-bedrock.mcpack` (+ `.sha256`)
   - Converts Java texture paths into Bedrock paths (`textures/items/...` and `textures/blocks/...`).
   - Generates Bedrock's `manifest.json`, `textures/item_texture.json`, and `textures/terrain_texture.json`.
   - Deployed via `rsync` to `/home/opc/mc/plugins/Geyser-Spigot/packs/shycubes-bedrock.mcpack` so Geyser automatically pushes it to connecting Bedrock players.
3. **Geyser Custom Mappings**: `resourcepacks/dist/custom_mappings/shycubes_mappings.json`
   - Generates Geyser v2 custom item/block mappings from `textures/mappings.json` and syncs them to `/home/opc/mc/plugins/Geyser-Spigot/custom_mappings/`.

## Folder Structure

```text
textures/
├── pack.json          # Pack metadata (name, description, pack_format, deterministic UUIDs)
├── pack.png           # Pack icon (used for Java pack.png and Bedrock pack_icon.png)
├── mappings.json      # Optional custom item/block definitions (CustomModelData + Geyser mappings)
├── items/             # Put item PNGs here -> Java assets/minecraft/textures/item/ & Bedrock textures/items/
├── blocks/            # Put block PNGs here -> Java assets/minecraft/textures/block/ & Bedrock textures/blocks/
├── entity/            # Optional entity textures -> Java assets/minecraft/textures/entity/ & Bedrock textures/entity/
└── gui/               # Optional GUI textures -> Java assets/minecraft/textures/gui/ & Bedrock textures/ui/
```

## Adding a Texture

### 1. Vanilla Texture Overrides
Drop any `.png` file into `textures/items/<name>.png` or `textures/blocks/<name>.png`.
- It will automatically be placed in `assets/minecraft/textures/item/<name>.png` (or `block/`) in the Java `.zip`.
- It will automatically be placed in `textures/items/<name>.png` (or `textures/blocks/<name>.png`) and registered in `textures/item_texture.json` / `textures/terrain_texture.json` in the Bedrock `.mcpack`.

### 2. Custom Items (CustomModelData + Geyser Mapping)
1. Place your PNG in `textures/items/<custom_name>.png`.
2. Add an entry to `textures/mappings.json` under `"items"`:
   ```json
   {
     "items": [
       {
         "name": "ruby_sword",
         "base_item": "minecraft:diamond_sword",
         "custom_model_data": 1001,
         "display_name": "Ruby Sword"
       }
     ]
   }
   ```
3. Next time `./deploy.sh` (or `./scripts/build_resource_pack.sh`) runs, it will automatically:
   - Generate the Java item model (`assets/minecraft/models/item/ruby_sword.json`), base item override (`assets/minecraft/models/item/diamond_sword.json`), and 1.21.4+ item definition (`assets/minecraft/items/diamond_sword.json`).
   - Register `ruby_sword` in Bedrock's `textures/item_texture.json`.
   - Generate `plugins/Geyser-Spigot/custom_mappings/shycubes_mappings.json` so Bedrock players see the custom item natively.

