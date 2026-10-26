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

## Deployment & Server Sync (`deploy.sh`, `scripts/`, & `.github/workflows/`)
- **[deploy.sh](file:///Users/rmanion/personal/mc1/deploy.sh)**: Orchestrates structure pulls, resource pack builds & sync, config sync, and plugin JAR deployment via `rsync` (supports both remote SSH and local self-hosted runner mode via `DEPLOY_LOCAL=true`).
- **[scripts/build_resource_pack.sh](file:///Users/rmanion/personal/mc1/scripts/build_resource_pack.sh)**: Converts source textures in `textures/` into both a Java resource pack (`resourcepacks/dist/shycubes-java.zip` + `.sha1`) and a Bedrock resource pack (`resourcepacks/dist/shycubes-bedrock.mcpack` + `.sha256` + Geyser `custom_mappings/`), updates `resource-pack-sha1` in `server.properties`, and syncs packs to `/home/opc/mc/resourcepacks/` and `/home/opc/mc/plugins/Geyser-Spigot/packs/`.
- **[scripts/sync_structures.sh](file:///Users/rmanion/personal/mc1/scripts/sync_structures.sh)**: Pulls structure files (`world/generated/`) via `rsync` into `server_data/current/structures/` and versions the previous snapshot into `server_data/versions/<N>` (keeping the last 10 versions and every 10th version ending in `0`).
- **[scripts/sync_configs.sh](file:///Users/rmanion/personal/mc1/scripts/sync_configs.sh)**: Pulls (`--pull`) or pushes (`--push`) live server configs (`server.properties`, `bukkit.yml`, `spigot.yml`, `config/paper-*.yml`, `plugins/*/config.yml`) in `server_configs/` via `rsync`.
- **[scripts/sanitize_configs.sh](file:///Users/rmanion/personal/mc1/scripts/sanitize_configs.sh)**: Strips confidential data (IPs, secrets, tokens, UUIDs) from live configs in `server_configs/` to produce Git-tracked `*.config.example` files.
- **[scripts/commit_and_push_sync.sh](file:///Users/rmanion/personal/mc1/scripts/commit_and_push_sync.sh)**: Commits and pushes updated `server_data/` structure versions and sanitized `*.config.example` files back to Git (`[skip ci]`).
- **[scripts/nr_deployment_marker.sh](file:///Users/rmanion/personal/mc1/scripts/nr_deployment_marker.sh)**: Records a New Relic Deployment Marker via NerdGraph (`changeTrackingCreateDeployment`) for `shyCubesLogs` using `NEW_RELIC_API_KEY` and `NEW_RELIC_ACCOUNT_ID` (and optional `NEW_RELIC_ENTITY_GUID`).
- **[scripts/ci_trace.sh](file:///Users/rmanion/personal/mc1/scripts/ci_trace.sh)**: Creates a root W3C `TRACEPARENT` span across GitHub Actions steps so all CI stages report as a single distributed trace.
- **[.github/workflows/deploy.yml](file:///Users/rmanion/personal/mc1/.github/workflows/deploy.yml)**: Self-hosted GitHub Action that builds, deploys, versions/commits server structures & sanitized configs, and records a New Relic Deployment Marker.
- **[.github/workflows/sync-server-data.yml](file:///Users/rmanion/personal/mc1/.github/workflows/sync-server-data.yml)**: Self-hosted GitHub Action (`workflow_dispatch`) to pull, version, and commit server structures & sanitized configs without deploying a plugin JAR.
- **[server_configs/README.md](file:///Users/rmanion/personal/mc1/server_configs/README.md)**: Documents server configuration files and configuration change history.

# Instrumentation #
Use shyCubesLogs as the New Relic Log partition and appName for this project

