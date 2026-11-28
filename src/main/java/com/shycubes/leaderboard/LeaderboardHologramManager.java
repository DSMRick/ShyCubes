package com.shycubes.leaderboard;

import com.shycubes.ShyCubesPlugin;
import com.shycubes.model.BillboardConfig;
import com.shycubes.model.LeaderboardRow;
import com.shycubes.model.StatKeys;
import com.shycubes.model.StatOrder;
import com.shycubes.model.StatQuery;
import com.shycubes.telemetry.OtelTelemetry;
import com.shycubes.telemetry.OtelTelemetry.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Manages multiple in-world floating text billboards defined under billboards.<id> in config.yml,
 * using PDC-tagged invisible marker ArmorStands for flicker-free Java + Geyser/Bedrock rendering.
 */
public class LeaderboardHologramManager {

    private static final double DEFAULT_LINE_SPACING = 0.28D;

    private final ShyCubesPlugin plugin;
    private final OtelTelemetry telemetry;
    private final NamespacedKey hologramIdKey;
    private final NamespacedKey hologramLineKey;

    private final Map<String, BillboardConfig> billboards = new LinkedHashMap<>();
    private String defaultBillboardId = "top_blocks";
    private double lineSpacing = DEFAULT_LINE_SPACING;

    public LeaderboardHologramManager(ShyCubesPlugin plugin, OtelTelemetry telemetry) {
        this.plugin = plugin;
        this.telemetry = telemetry;
        this.hologramIdKey = new NamespacedKey(plugin, "hologram_id");
        this.hologramLineKey = new NamespacedKey(plugin, "hologram_line");
        loadFromConfig();
    }

    public NamespacedKey getHologramIdKey() {
        return hologramIdKey;
    }

    public String getDefaultBillboardId() {
        if (billboards.containsKey(defaultBillboardId)) {
            return defaultBillboardId;
        }
        if (!billboards.isEmpty()) {
            return billboards.keySet().iterator().next();
        }
        return defaultBillboardId;
    }

    public BillboardConfig getBillboard(String id) {
        if (id == null) {
            return null;
        }
        return billboards.get(id.toLowerCase(Locale.ROOT));
    }

    public Collection<BillboardConfig> getBillboards() {
        return Collections.unmodifiableCollection(billboards.values());
    }

    public Set<String> getBillboardIds() {
        return Collections.unmodifiableSet(billboards.keySet());
    }

    public Set<StatQuery> getActiveQueries() {
        Set<StatQuery> queries = new LinkedHashSet<>();
        for (BillboardConfig config : billboards.values()) {
            queries.add(config.toStatQuery());
        }
        return queries;
    }

    /**
     * Loads all billboard definitions from config.yml and sweeps orphaned hologram entities.
     */
    public void loadFromConfig() {
        try (OtelTelemetry.SpanScope span = telemetry.startSpan("billboard.config_reload", Level.DEBUG)) {
            FileConfiguration cfg = plugin.getConfig();
            this.defaultBillboardId = cfg.getString("leaderboard.default-billboard", "top_blocks")
                    .trim()
                    .toLowerCase(Locale.ROOT);
            this.lineSpacing = cfg.getDouble("leaderboard.line-spacing", DEFAULT_LINE_SPACING);

            billboards.clear();
            ConfigurationSection section = cfg.getConfigurationSection("billboards");
            boolean configUpdated = false;

            if (section != null) {
                for (String rawId : section.getKeys(false)) {
                    ConfigurationSection boardSec = section.getConfigurationSection(rawId);
                    if (boardSec == null) {
                        continue;
                    }
                    String id = rawId.trim().toLowerCase(Locale.ROOT);
                    boolean enabled = boardSec.getBoolean("enabled", true);
                    String stat = boardSec.getString("stat", "blocks_broken").trim();
                    StatOrder order = StatOrder.fromString(boardSec.getString("order", "DESC"));
                    int entries = Math.max(1, boardSec.getInt("entries", 10));
                    String unit = boardSec.getString("unit", "");
                    List<String> header = boardSec.getStringList("header");
                    if (header.isEmpty()) {
                        String singleHeader = boardSec.getString("header");
                        if (singleHeader != null && !singleHeader.isBlank()) {
                            header = List.of(singleHeader);
                        } else {
                            header = List.of("§6§l✦ §e§l" + id.toUpperCase(Locale.ROOT) + " §6§l✦");
                        }
                    }

                    boolean autoPlace = boardSec.getBoolean("auto-place-at-spawn", false);
                    double ox = boardSec.getDouble("spawn-offset.x", 0.5D);
                    double oy = boardSec.getDouble("spawn-offset.y", 2.8D);
                    double oz = boardSec.getDouble("spawn-offset.z", 3.5D);

                    String worldName = boardSec.getString("location.world", "");
                    Double x = boardSec.contains("location.x") ? boardSec.getDouble("location.x") : null;
                    Double y = boardSec.contains("location.y") ? boardSec.getDouble("location.y") : null;
                    Double z = boardSec.contains("location.z") ? boardSec.getDouble("location.z") : null;

                    BillboardConfig config = new BillboardConfig(
                            id, enabled, stat, order, entries, unit, header,
                            autoPlace, ox, oy, oz, worldName, x, y, z
                    );

                    if (enabled && !config.hasLocation() && autoPlace && !Bukkit.getWorlds().isEmpty()) {
                        World defaultWorld = Bukkit.getWorlds().getFirst();
                        Location spawn = defaultWorld.getSpawnLocation();
                        Location autoLoc = new Location(
                                defaultWorld,
                                spawn.getBlockX() + ox,
                                spawn.getBlockY() + oy,
                                spawn.getBlockZ() + oz
                        );
                        config = config.withLocation(autoLoc, true);
                        writeBillboardToConfig(cfg, config);
                        configUpdated = true;
                    }

                    billboards.put(id, config);
                }
            }

            if (configUpdated) {
                plugin.saveConfig();
            }

            span.setAttribute("billboard.count", billboards.size());
            sweepOrphanedEntities();
        }
    }

    public void reloadConfig() {
        plugin.reloadConfig();
        loadFromConfig();
    }

    /**
     * Moves and enables an existing billboard to a new top-line anchor location.
     */
    public BillboardConfig setBillboardLocation(String id, Location newTopAnchor, boolean saveConfig) {
        String key = id.toLowerCase(Locale.ROOT);
        BillboardConfig existing = billboards.get(key);
        if (existing == null) {
            return null;
        }
        removeEntitiesForBillboard(key);
        BillboardConfig updated = existing.withLocation(newTopAnchor, true);
        billboards.put(key, updated);

        if (saveConfig) {
            writeBillboardToConfig(plugin.getConfig(), updated);
            plugin.saveConfig();
        }

        telemetry.emitEvent("billboard.location.updated", Level.INFO, Map.of(
                "billboard.id", key,
                "billboard.world", updated.worldName(),
                "billboard.x", updated.x(),
                "billboard.y", updated.y(),
                "billboard.z", updated.z()
        ));

        updateBillboard(updated, plugin.getPlayerDatabase().getCachedLeaderboard(updated.toStatQuery()));
        return updated;
    }

    /**
     * Creates a new billboard at the given top-line anchor, persists it to config.yml, and renders it.
     */
    public BillboardConfig createBillboard(String id, String stat, int entries, String title, Location topAnchor) {
        String key = id.toLowerCase(Locale.ROOT);
        List<String> header = List.of(
                "§6§l✦ §e§l" + title + " §6§l✦",
                "§7ShyCubes Leaderboard"
        );
        BillboardConfig created = new BillboardConfig(
                key,
                true,
                stat,
                StatOrder.DESC,
                Math.max(1, entries),
                StatKeys.defaultUnitFor(stat),
                header,
                false,
                0.5D,
                2.8D,
                3.5D,
                topAnchor.getWorld().getName(),
                Math.round(topAnchor.getX() * 100.0D) / 100.0D,
                Math.round(topAnchor.getY() * 100.0D) / 100.0D,
                Math.round(topAnchor.getZ() * 100.0D) / 100.0D
        );
        billboards.put(key, created);
        writeBillboardToConfig(plugin.getConfig(), created);
        plugin.saveConfig();

        telemetry.emitEvent("billboard.entry.created", Level.INFO, Map.of(
                "billboard.id", key,
                "billboard.stat", stat,
                "billboard.entries", created.entries(),
                "billboard.world", created.worldName()
        ));

        updateBillboard(created, plugin.getPlayerDatabase().getCachedLeaderboard(created.toStatQuery()));
        return created;
    }

    /**
     * Disables a billboard and removes its ArmorStand entities while preserving its config entry.
     */
    public boolean disableBillboard(String id) {
        String key = id.toLowerCase(Locale.ROOT);
        BillboardConfig existing = billboards.get(key);
        if (existing == null) {
            return false;
        }
        removeEntitiesForBillboard(key);
        BillboardConfig updated = existing.withEnabled(false);
        billboards.put(key, updated);
        writeBillboardToConfig(plugin.getConfig(), updated);
        plugin.saveConfig();

        telemetry.emitEvent("billboard.entry.disabled", Level.INFO, Map.of("billboard.id", key));
        return true;
    }

    /**
     * Removes a billboard's ArmorStand entities and deletes its entry from config.yml.
     */
    public boolean deleteBillboard(String id) {
        String key = id.toLowerCase(Locale.ROOT);
        if (!billboards.containsKey(key)) {
            return false;
        }
        removeEntitiesForBillboard(key);
        billboards.remove(key);
        plugin.getConfig().set("billboards." + key, null);
        plugin.saveConfig();

        telemetry.emitEvent("billboard.entry.deleted", Level.INFO, Map.of("billboard.id", key));
        return true;
    }

    private void writeBillboardToConfig(FileConfiguration cfg, BillboardConfig board) {
        String base = "billboards." + board.id();
        cfg.set(base + ".enabled", board.enabled());
        cfg.set(base + ".stat", board.stat());
        cfg.set(base + ".order", board.order().name());
        cfg.set(base + ".entries", board.entries());
        cfg.set(base + ".unit", board.unit());
        cfg.set(base + ".header", board.header());
        cfg.set(base + ".auto-place-at-spawn", board.autoPlaceAtSpawn());
        cfg.set(base + ".spawn-offset.x", board.spawnOffsetX());
        cfg.set(base + ".spawn-offset.y", board.spawnOffsetY());
        cfg.set(base + ".spawn-offset.z", board.spawnOffsetZ());
        cfg.set(base + ".location.world", board.worldName() != null ? board.worldName() : "");
        if (board.x() != null) {
            cfg.set(base + ".location.x", board.x());
        }
        if (board.y() != null) {
            cfg.set(base + ".location.y", board.y());
        }
        if (board.z() != null) {
            cfg.set(base + ".location.z", board.z());
        }
    }

    public void removeEntitiesForBillboard(String id) {
        String key = id.toLowerCase(Locale.ROOT);
        for (World world : Bukkit.getWorlds()) {
            for (ArmorStand stand : world.getEntitiesByClass(ArmorStand.class)) {
                if (key.equals(getEntityBillboardId(stand))) {
                    stand.remove();
                }
            }
        }
    }

    /**
     * Removes any ShyCubes hologram ArmorStand whose billboard ID no longer exists or is disabled.
     */
    public void sweepOrphanedEntities() {
        for (World world : Bukkit.getWorlds()) {
            for (ArmorStand stand : world.getEntitiesByClass(ArmorStand.class)) {
                String id = getEntityBillboardId(stand);
                if (id != null) {
                    BillboardConfig cfg = billboards.get(id);
                    if (cfg == null || !cfg.enabled()) {
                        stand.remove();
                    }
                }
            }
        }
    }

    public boolean isHologramEntity(Entity entity) {
        return getEntityBillboardId(entity) != null;
    }

    private String getEntityBillboardId(Entity entity) {
        if (!(entity instanceof ArmorStand)) {
            return null;
        }
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        String id = pdc.get(hologramIdKey, PersistentDataType.STRING);
        return (id != null && !id.isBlank()) ? id : null;
    }

    /**
     * Returns all enabled billboards anchored inside the specified chunk.
     */
    public List<BillboardConfig> getBillboardsInChunk(Chunk chunk) {
        List<BillboardConfig> matching = Collections.emptyList();
        for (BillboardConfig cfg : billboards.values()) {
            if (!cfg.enabled() || !cfg.hasLocation()) {
                continue;
            }
            Location loc = cfg.toLocation();
            if (loc == null || !loc.getWorld().equals(chunk.getWorld())) {
                continue;
            }
            int chunkX = loc.getBlockX() >> 4;
            int chunkZ = loc.getBlockZ() >> 4;
            if (chunk.getX() == chunkX && chunk.getZ() == chunkZ) {
                if (matching.isEmpty()) {
                    matching = new ArrayList<>();
                }
                matching.add(cfg);
            }
        }
        return matching;
    }

    /**
     * Builds the formatted leaderboard lines for any configured billboard.
     */
    public List<String> buildLeaderboardLines(BillboardConfig config, List<LeaderboardRow> rows) {
        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
        List<String> lines = new ArrayList<>(config.header().size() + config.entries());
        lines.addAll(config.header());

        String unitSuffix = config.unit().isBlank() ? "" : " §7" + config.unit();
        for (int i = 0; i < config.entries(); i++) {
            int rank = i + 1;
            String rankPrefix = switch (rank) {
                case 1 -> "§6§l#1";
                case 2 -> "§f§l#2";
                case 3 -> "§c§l#3";
                default -> "§7#" + rank;
            };

            if (i < rows.size()) {
                LeaderboardRow row = rows.get(i);
                String formattedValue = nf.format(row.value());
                lines.add(rankPrefix + " §f" + row.username() + " §8- §e" + formattedValue + unitSuffix);
            } else {
                lines.add(rankPrefix + " §8---");
            }
        }
        return lines;
    }

    /**
     * Updates all enabled billboards whose chunks are currently loaded.
     */
    public void updateAllBillboards(Function<StatQuery, List<LeaderboardRow>> dataProvider) {
        try (OtelTelemetry.SpanScope span = telemetry.startSpan(
                "billboard.update_all", Level.DEBUG, Map.of("billboard.count", billboards.size()))) {
            int updated = 0;
            for (BillboardConfig config : billboards.values()) {
                if (!config.enabled()) {
                    continue;
                }
                List<LeaderboardRow> rows = dataProvider.apply(config.toStatQuery());
                if (updateBillboard(config, rows)) {
                    updated++;
                }
            }
            span.setAttribute("billboard.updated", updated);
        }
    }

    /**
     * Updates a single billboard's ArmorStand stack in-place on the main thread if its chunk is loaded.
     */
    public boolean updateBillboard(BillboardConfig config, List<LeaderboardRow> rows) {
        if (config == null || !config.enabled() || !config.hasLocation()) {
            return false;
        }
        Location anchorLocation = config.toLocation();
        if (anchorLocation == null || anchorLocation.getWorld() == null) {
            return false;
        }
        World world = anchorLocation.getWorld();
        int chunkX = anchorLocation.getBlockX() >> 4;
        int chunkZ = anchorLocation.getBlockZ() >> 4;
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return false;
        }

        try (OtelTelemetry.SpanScope ignored = telemetry.startSpan(
                "billboard.render", Level.DEBUG, Map.of("billboard.id", config.id(), "billboard.stat", config.stat()))) {
            List<String> lines = buildLeaderboardLines(config, rows != null ? rows : Collections.emptyList());
            LegacyComponentSerializer serializer = LegacyComponentSerializer.legacySection();

            Map<Integer, ArmorStand> byLine = new HashMap<>();
            List<ArmorStand> duplicates = new ArrayList<>();

            for (Entity entity : world.getChunkAt(chunkX, chunkZ).getEntities()) {
                if (entity instanceof ArmorStand stand && config.id().equals(getEntityBillboardId(stand))) {
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
                Location lineLoc = anchorLocation.clone().subtract(0.0D, i * lineSpacing, 0.0D);
                Component textComponent = serializer.deserialize(lines.get(i));
                ArmorStand stand = byLine.get(i);

                try (OtelTelemetry.SpanScope entitySpan = telemetry.startSpan(
                        "billboard.entity_sync", Level.TRACE, Map.of("billboard.id", config.id(), "line", i))) {
                    if (stand == null || !stand.isValid()) {
                        final int lineIndex = i;
                        world.spawn(lineLoc, ArmorStand.class, as -> {
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
                            pdc.set(hologramIdKey, PersistentDataType.STRING, config.id());
                            pdc.set(hologramLineKey, PersistentDataType.INTEGER, lineIndex);
                        });
                        entitySpan.setAttribute("action", "spawn");
                    } else {
                        if (stand.getLocation().distanceSquared(lineLoc) > 0.001D) {
                            stand.teleport(lineLoc);
                        }
                        stand.customName(textComponent);
                        if (!stand.isCustomNameVisible()) {
                            stand.setCustomNameVisible(true);
                        }
                        entitySpan.setAttribute("action", "update");
                    }
                }
            }
            return true;
        }
    }
}
