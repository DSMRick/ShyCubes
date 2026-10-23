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

