package com.shycubes.leaderboard;

import com.shycubes.ShyCubesPlugin;
import com.shycubes.model.PlayerEntry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Manages the in-world floating text hologram leaderboard using stacked invisible marker ArmorStands
 * for seamless Java + Geyser/Bedrock cross-play rendering without flicker.
 */
public class LeaderboardHologramManager {

    public static final String HOLOGRAM_ID = "top_blocks";
    private static final double LINE_SPACING = 0.28D;

    private final ShyCubesPlugin plugin;
    private final NamespacedKey hologramIdKey;
    private final NamespacedKey hologramLineKey;
    private Location anchorLocation;

    public LeaderboardHologramManager(ShyCubesPlugin plugin) {
        this.plugin = plugin;
        this.hologramIdKey = new NamespacedKey(plugin, "hologram_id");
        this.hologramLineKey = new NamespacedKey(plugin, "hologram_line");
        loadConfiguredLocation();
    }

    public NamespacedKey getHologramIdKey() {
        return hologramIdKey;
    }

    public Location getAnchorLocation() {
        return anchorLocation != null ? anchorLocation.clone() : null;
    }

    public void loadConfiguredLocation() {
        FileConfiguration cfg = plugin.getConfig();
        if (cfg.getBoolean("leaderboard.location.enabled", false)) {
            String worldName = cfg.getString("leaderboard.location.world", "world");
            World world = Bukkit.getWorld(worldName);
            if (world == null && !Bukkit.getWorlds().isEmpty()) {
                world = Bukkit.getWorlds().getFirst();
            }
            if (world != null) {
                double x = cfg.getDouble("leaderboard.location.x");
                double y = cfg.getDouble("leaderboard.location.y");
                double z = cfg.getDouble("leaderboard.location.z");
                this.anchorLocation = new Location(world, x, y, z);
                return;
            }
        }

        if (cfg.getBoolean("leaderboard.auto-place-at-spawn", true) && !Bukkit.getWorlds().isEmpty()) {
            World world = Bukkit.getWorlds().getFirst();
            Location spawn = world.getSpawnLocation();
            double ox = cfg.getDouble("leaderboard.spawn-offset.x", 0.5D);
            double oy = cfg.getDouble("leaderboard.spawn-offset.y", 2.8D);
            double oz = cfg.getDouble("leaderboard.spawn-offset.z", 3.5D);
            Location defaultAnchor = new Location(
                    world,
                    spawn.getBlockX() + ox,
                    spawn.getBlockY() + oy,
                    spawn.getBlockZ() + oz
            );
            setHologramLocation(defaultAnchor, true);
        }
    }

    /**
     * Moves the hologram to a new anchor location (top line) and persists to config.yml.
     */
    public void setHologramLocation(Location newTopAnchor, boolean saveConfig) {
        removeExistingHologramEntities();
        this.anchorLocation = newTopAnchor.clone();

        if (saveConfig) {
            FileConfiguration cfg = plugin.getConfig();
            cfg.set("leaderboard.location.enabled", true);
            cfg.set("leaderboard.location.world", newTopAnchor.getWorld().getName());
            cfg.set("leaderboard.location.x", Math.round(newTopAnchor.getX() * 100.0D) / 100.0D);
            cfg.set("leaderboard.location.y", Math.round(newTopAnchor.getY() * 100.0D) / 100.0D);
            cfg.set("leaderboard.location.z", Math.round(newTopAnchor.getZ() * 100.0D) / 100.0D);
            plugin.saveConfig();
        }

        updateHologram(plugin.getPlayerDatabase().getCachedTopTen());
    }

    /**
     * Removes the hologram from the world and disables it in config.yml.
     */
    public void disableAndRemoveHologram() {
        removeExistingHologramEntities();
        this.anchorLocation = null;
        FileConfiguration cfg = plugin.getConfig();
        cfg.set("leaderboard.location.enabled", false);
        cfg.set("leaderboard.auto-place-at-spawn", false);
        plugin.saveConfig();
    }

    /**
     * Removes all tracked hologram ArmorStand entities across loaded worlds.
     */
    public void removeExistingHologramEntities() {
        for (World world : Bukkit.getWorlds()) {
            for (ArmorStand stand : world.getEntitiesByClass(ArmorStand.class)) {
                if (isHologramEntity(stand)) {
                    stand.remove();
                }
            }
        }
    }

    public boolean isHologramEntity(Entity entity) {
        if (!(entity instanceof ArmorStand)) {
            return false;
        }
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        String id = pdc.get(hologramIdKey, PersistentDataType.STRING);
        return HOLOGRAM_ID.equals(id);
    }

    public boolean isHologramChunk(Chunk chunk) {
        if (anchorLocation == null || anchorLocation.getWorld() == null) {
            return false;
        }
        if (!anchorLocation.getWorld().equals(chunk.getWorld())) {
            return false;
        }
        int anchorChunkX = anchorLocation.getBlockX() >> 4;
        int anchorChunkZ = anchorLocation.getBlockZ() >> 4;
        return chunk.getX() == anchorChunkX && chunk.getZ() == anchorChunkZ;
    }

    /**
     * Builds the formatted leaderboard lines for the Top 10 Blocks Broken display.
     */
    public List<String> buildLeaderboardLines(List<PlayerEntry> topPlayers) {
        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
        List<String> lines = new ArrayList<>(13);
        lines.add("§6§l✦ §e§lTOP 10 BLOCK BREAKERS §6§l✦");
        lines.add("§7ShyCubes Leaderboard");

        for (int i = 0; i < 10; i++) {
            int rank = i + 1;
            String rankPrefix = switch (rank) {
                case 1 -> "§6§l#1";
                case 2 -> "§f§l#2";
                case 3 -> "§c§l#3";
                default -> "§7#" + rank;
            };

            if (i < topPlayers.size()) {
                PlayerEntry entry = topPlayers.get(i);
                String formattedBlocks = nf.format(entry.blocksBroken());
                lines.add(rankPrefix + " §f" + entry.username() + " §8- §e" + formattedBlocks + " §7blocks");
            } else {
                lines.add(rankPrefix + " §8---");
            }
        }
        return lines;
    }

    /**
     * Updates the floating hologram lines in-place on the main server thread if the target chunk is loaded.
     */
    public void updateHologram(List<PlayerEntry> topPlayers) {
        if (anchorLocation == null || anchorLocation.getWorld() == null) {
            return;
        }
        World world = anchorLocation.getWorld();
        int chunkX = anchorLocation.getBlockX() >> 4;
        int chunkZ = anchorLocation.getBlockZ() >> 4;
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return;
        }

        List<String> lines = buildLeaderboardLines(topPlayers);
        LegacyComponentSerializer serializer = LegacyComponentSerializer.legacySection();

        // Discover existing hologram ArmorStands in the chunk by line index
        Map<Integer, ArmorStand> byLine = new HashMap<>();
        List<ArmorStand> duplicates = new ArrayList<>();

        for (Entity entity : world.getChunkAt(chunkX, chunkZ).getEntities()) {
            if (entity instanceof ArmorStand stand && isHologramEntity(stand)) {
                Integer lineIdx = stand.getPersistentDataContainer().get(hologramLineKey, PersistentDataType.INTEGER);
                if (lineIdx == null || lineIdx < 0 || lineIdx >= lines.size() || byLine.containsKey(lineIdx)) {
                    duplicates.add(stand);
                } else {
                    byLine.put(lineIdx, stand);
                }
            }
        }

        for (ArmorStand dup : duplicates) {
            dup.remove();
        }

        for (int i = 0; i < lines.size(); i++) {
            Location lineLoc = anchorLocation.clone().subtract(0.0D, i * LINE_SPACING, 0.0D);
            Component textComponent = serializer.deserialize(lines.get(i));
            ArmorStand stand = byLine.get(i);

            if (stand == null || !stand.isValid()) {
                final int lineIndex = i;
                stand = world.spawn(lineLoc, ArmorStand.class, as -> {
                    as.setVisible(false);
                    as.setInvisible(true);
                    as.setGravity(false);
                    as.setMarker(true);
                    as.setSmall(true);
                    as.setBasePlate(false);
                    as.setArms(false);
                    as.setInvulnerable(true);
                    as.setPersistent(true);
                    as.setRemoveWhenFarAway(false);
                    as.customName(textComponent);
                    as.setCustomNameVisible(true);
                    PersistentDataContainer pdc = as.getPersistentDataContainer();
                    pdc.set(hologramIdKey, PersistentDataType.STRING, HOLOGRAM_ID);
                    pdc.set(hologramLineKey, PersistentDataType.INTEGER, lineIndex);
                });
            } else {
                if (stand.getLocation().distanceSquared(lineLoc) > 0.001D) {
                    stand.teleport(lineLoc);
                }
                stand.customName(textComponent);
                if (!stand.isCustomNameVisible()) {
                    stand.setCustomNameVisible(true);
                }
            }
        }
    }
}

