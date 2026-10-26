# Server Configurations (`server_configs/`)

This directory mirrors the configuration layout of the Paper + Geyser-Spigot + Floodgate server (`/home/opc/mc`).

## Workflow
- **Live files** (`*.properties`, `*.yml`) are **git-ignored** because they contain server-specific secrets and IP addresses.
- **Sanitized files** (`*.config.example`) are **git-tracked** with confidential values stripped (`<YOUR_SERVER_IP>`, `<REDACTED_SECRET>`, `<REDACTED_UUID>`) so configuration changes are documented and versioned in Git.
- **Pull from server**: `./scripts/sync_configs.sh --pull` (or `./deploy.sh --pull-only`)
- **Push local edits to server**: `./scripts/sync_configs.sh --push` (automatically run during `./deploy.sh`)
- **Regenerate `.config.example` files**: `./scripts/sanitize_configs.sh`

## Tracked Configuration Files
- `shycubes.service` — `systemd` unit file installed to `/etc/systemd/system/shycubes.service` (`Restart=always`, runs `ExecStartPre=-/home/opc/mc/scripts/pre_start_check.sh` to deploy & update Paper/plugins on every start/restart, then runs `run_server_with_otel.sh` wrapping `java -Xms10G -Xmx10G -jar /home/opc/mc/server.jar nogui`)
- `run_server_with_otel.sh` — Streams Paper JVM stdout/stderr lines in real time to New Relic via the local OTLP collector (`http://localhost:4318/v1/logs`, `shyCubesLogs` partition) with parsed severity levels and thread names
- `shycubes-update.timer` & `shycubes-update.service` — `systemd` timer that runs `scripts/update_server_and_plugins.sh --restart` every day at **4:00 AM America/Chicago (CT)**
- `plugins_manifest.json` — Declarative manifest of Paper and plugins (`Geyser-Spigot`, `floodgate-spigot`, plus any future Modrinth, Hangar, GitHub Release, or URL plugins) updated by `scripts/update_server_and_plugins.sh`
- `start.sh` — Delegates CLI restarts to `sudo systemctl restart shycubes.service` and exits immediately when called by Paper's in-game `/restart` hook so `systemd` restarts the server cleanly without hanging
- `server.properties.config.example` — Core Minecraft / Paper server properties
- `bukkit.yml.config.example` — Bukkit configuration
- `spigot.yml.config.example` — Spigot configuration
- `config/paper-global.yml.config.example` — Global Paper configuration
- `config/paper-world-defaults.yml.config.example` — Default per-world Paper configuration
- `plugins/Geyser-Spigot/config.yml.config.example` — Geyser-Spigot Bedrock bridge configuration
- `plugins/floodgate/config.yml.config.example` — Floodgate Bedrock authentication configuration
- `plugins/bStats/config.yml.config.example` — bStats metrics configuration

## Configuration Change Log
- **Configured Resource Pack (`server.properties`)**: Configured `resource-pack`, `resource-pack-id`, and `resource-pack-sha1` (automatically updated by [`scripts/build_resource_pack.sh`](file:///Users/rmanion/personal/mc1/scripts/build_resource_pack.sh) on every build/deploy) alongside Bedrock `.mcpack` delivery in `/home/opc/mc/plugins/Geyser-Spigot/packs/`.
- **Added `start.sh`**: Added executable startup script (`java -Xms10G -Xmx10G -jar server.jar nogui`) to `/home/opc/mc/start.sh` so Spigot/Paper's `/restart` (`restart-script: ./start.sh` in `spigot.yml`) works both from in-game `/restart` and interactive SSH sessions.
- **Initial baseline**: Pulled from remote Paper 26.3 server with Geyser-Spigot (`transport: nethernet`, `mode: builtin`) and Floodgate installed.

