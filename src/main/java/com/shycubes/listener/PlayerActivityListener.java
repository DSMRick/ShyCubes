package com.shycubes.listener;

import com.shycubes.ShyCubesPlugin;
import com.shycubes.model.BillboardConfig;
import com.shycubes.model.StatKeys;
import com.shycubes.model.StatQuery;
import com.shycubes.telemetry.OtelTelemetry;
import com.shycubes.telemetry.OtelTelemetry.Level;
import com.shycubes.telemetry.OtelTelemetry.SpanContext;
import com.shycubes.util.BedrockUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Ambient;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WaterMob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class PlayerActivityListener implements Listener {

    private static final Set<Material> AGEABLE_CROPS = EnumSet.of(
            Material.WHEAT,
            Material.CARROTS,
            Material.POTATOES,
            Material.BEETROOTS,
            Material.NETHER_WART,
            Material.COCOA,
            Material.PITCHER_CROP,
            Material.SWEET_BERRY_BUSH
    );

    private static final Set<Material> DIRECT_CROPS = EnumSet.of(
            Material.MELON,
            Material.PUMPKIN,
            Material.SUGAR_CANE,
            Material.BAMBOO,
            Material.CACTUS,
            Material.TORCHFLOWER
    );

    private final ShyCubesPlugin plugin;
    private final OtelTelemetry telemetry;

    public PlayerActivityListener(ShyCubesPlugin plugin, OtelTelemetry telemetry) {
        this.plugin = plugin;
        this.telemetry = telemetry;
    }

    private void incrementPlayerStat(Player player, String statKey) {
        UUID uuid = player.getUniqueId();
        boolean isBedrock = BedrockUtil.isBedrockPlayer(uuid);
        String xuid = isBedrock ? BedrockUtil.getFloodgateXuid(uuid) : null;
        plugin.getPlayerDatabase().incrementStat(uuid, player.getName(), isBedrock, xuid, statKey, 1L);
    }

    private boolean isHarvestableCrop(Block block) {
        Material type = block.getType();
        if (AGEABLE_CROPS.contains(type)) {
            return block.getBlockData() instanceof Ageable ageable
                    && ageable.getAge() >= ageable.getMaximumAge();
        }
        return DIRECT_CROPS.contains(type);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String username = player.getName();
        boolean isBedrock = BedrockUtil.isBedrockPlayer(uuid);
        String xuid = isBedrock ? BedrockUtil.getFloodgateXuid(uuid) : null;
        Set<StatQuery> queries = plugin.getHologramManager().getActiveQueries();

        try (OtelTelemetry.SpanScope span = telemetry.startSpan(
                "listener.player_join",
                Level.INFO,
                Map.of("player.uuid", uuid.toString(), "player.platform", isBedrock ? "Bedrock" : "Java"))) {
            SpanContext parentCtx = span.context();

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try (OtelTelemetry.SpanScope ignored = telemetry.startSpan(
                        "listener.player_join.sync_db", Level.DEBUG, parentCtx, Map.of("player.uuid", uuid.toString()))) {
                    plugin.getPlayerDatabase().recordPlayerJoin(uuid, username, isBedrock, xuid);
                    plugin.getPlayerDatabase().setAttribute(
                            uuid,
                            username,
                            isBedrock,
                            "platform",
                            isBedrock ? "Bedrock" : "Java"
                    );
                    plugin.getPlayerDatabase().refreshSnapshots(queries);
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (plugin.isEnabled()) {
                        plugin.getHologramManager().updateAllBillboards(plugin.getPlayerDatabase()::getCachedLeaderboard);
                    }
                });
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Set<StatQuery> queries = plugin.getHologramManager().getActiveQueries();
        plugin.getPlayerDatabase().markPlayerQuit(uuid);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (OtelTelemetry.SpanScope ignored = telemetry.startSpan(
                    "listener.player_quit.flush", Level.DEBUG, Map.of("player.uuid", uuid.toString()))) {
                plugin.getPlayerDatabase().flushAndRefreshIfDirty(queries);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        incrementPlayerStat(player, StatKeys.BLOCKS_BROKEN);

        if (isHarvestableCrop(event.getBlock())) {
            incrementPlayerStat(player, StatKeys.CROPS_HARVESTED);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        incrementPlayerStat(event.getPlayer(), StatKeys.BLOCKS_PLACED);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerHarvestBlock(PlayerHarvestBlockEvent event) {
        if (!event.getItemsHarvested().isEmpty()) {
            incrementPlayerStat(event.getPlayer(), StatKeys.CROPS_HARVESTED);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        incrementPlayerStat(victim, StatKeys.DEATHS);

        Player killer = victim.getKiller();
        if (killer != null && !killer.getUniqueId().equals(victim.getUniqueId())) {
            incrementPlayerStat(killer, StatKeys.PLAYER_KILLS);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof Player) {
            return;
        }
        Player killer = entity.getKiller();
        if (killer == null) {
            return;
        }

        if (entity instanceof Enemy) {
            incrementPlayerStat(killer, StatKeys.MOBS_KILLED);
        } else if (entity instanceof Animals || entity instanceof WaterMob || entity instanceof Ambient) {
            incrementPlayerStat(killer, StatKeys.ANIMALS_KILLED);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        List<BillboardConfig> billboards = plugin.getHologramManager().getBillboardsInChunk(event.getChunk());
        if (billboards.isEmpty()) {
            return;
        }
        try (OtelTelemetry.SpanScope ignored = telemetry.startSpan(
                "listener.chunk_load",
                Level.INFO,
                Map.of(
                        "world.name", event.getWorld().getName(),
                        "chunk.x", event.getChunk().getX(),
                        "chunk.z", event.getChunk().getZ(),
                        "billboard.count", billboards.size()
                ))) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!plugin.isEnabled()) {
                    return;
                }
                for (BillboardConfig cfg : billboards) {
                    plugin.getHologramManager().updateBillboard(
                            cfg,
                            plugin.getPlayerDatabase().getCachedLeaderboard(cfg.toStatQuery())
                    );
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHologramDamage(EntityDamageEvent event) {
        if (plugin.getHologramManager().isHologramEntity(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHologramManipulate(PlayerArmorStandManipulateEvent event) {
        if (plugin.getHologramManager().isHologramEntity(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }
}
