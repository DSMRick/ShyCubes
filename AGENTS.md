# Project Overview: ShyCubes (`shycubes`)

**ShyCubes** (`shycubes`) is a Minecraft server plugin targeting **Paper** with **Geyser-Spigot** and **Floodgate** integration (supporting both Java Edition and Bedrock Edition players).

- **Repository**: https://github.com/DSMRick/ShyCubes
- **Local / Private Instructions**: See `private_agents.md` (git-ignored) for local environment and identity settings.

## Tech Stack & Environment
- **Server Platform**: Paper (Spigot/Paper API)
- **Cross-Play Integration**:
  - **Geyser-Spigot**: Bridges Bedrock Edition clients to Java Edition servers.
  - **Floodgate**: Allows Bedrock Edition players to join without a Java Edition account (`FloodgateApi` for Bedrock player detection, XUIDs, forms/Cumulus UI, etc.).
- **Build System**: Gradle (Kotlin or Groovy DSL) or Maven *(to be configured)*
- **Language**: Java

## Key Considerations for Paper + Geyser/Floodgate
- **Player Detection**: Use `FloodgateApi.getInstance().isFloodgatePlayer(uuid)` to check if a player is on Bedrock Edition.
- **UUIDs**: Floodgate players use a Floodgate-generated UUID (version 0, starting with `00000000-0000-0000-...`).
- **UI / Menus**: Java chest GUIs work via Geyser, or native Bedrock Forms (SimpleForm, ModalForm, CustomForm via Cumulus) can be sent to Floodgate players for a better UX.
- **plugin.yml / paper-plugin.yml**: Declare `floodgate` and/or `Geyser-Spigot` as `softdepend` (or `depend` if strictly required).

## Deployment & Server Sync (`deploy.sh` & `scripts/`)
- **[deploy.sh](file:///Users/rmanion/personal/mc1/deploy.sh)**: Orchestrates structure pulls, config sync, and plugin JAR deployment via `rsync`.
- **[scripts/sync_structures.sh](file:///Users/rmanion/personal/mc1/scripts/sync_structures.sh)**: Pulls structure files (`world/generated/`) via `rsync` into `server_data/current/structures/` and versions the previous snapshot into `server_data/versions/<N>` (keeping the last 10 versions and every 10th version ending in `0`).
- **[scripts/sync_configs.sh](file:///Users/rmanion/personal/mc1/scripts/sync_configs.sh)**: Pulls (`--pull`) or pushes (`--push`) live server configs (`server.properties`, `bukkit.yml`, `spigot.yml`, `config/paper-*.yml`, `plugins/*/config.yml`) in `server_configs/` via `rsync`.
- **[scripts/sanitize_configs.sh](file:///Users/rmanion/personal/mc1/scripts/sanitize_configs.sh)**: Strips confidential data (IPs, secrets, tokens, UUIDs) from live configs in `server_configs/` to produce Git-tracked `*.config.example` files.
- **[server_configs/README.md](file:///Users/rmanion/personal/mc1/server_configs/README.md)**: Documents server configuration files and configuration change history.

# Instrumentation #
Use shyCubesLogs as the New Relic Log partition and appName for this project

