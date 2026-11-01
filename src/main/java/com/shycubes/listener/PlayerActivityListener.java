package com.shycubes.listener;

import com.shycubes.ShyCubesPlugin;
import com.shycubes.model.PlayerEntry;
import com.shycubes.util.BedrockUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;

import java.util.List;
import java.util.UUID;

public class PlayerActivityListener implements Listener {

    private final ShyCubesPlugin plugin;

    public PlayerActivityListener(ShyCubesPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String username = player.getName();
        boolean isBedrock = BedrockUtil.isBedrockPlayer(uuid);
        String xuid = isBedrock ? BedrockUtil.getFloodgateXuid(uuid) : null;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            plugin.getPlayerDatabase().recordPlayerJoin(uuid, username, isBedrock, xuid);
            plugin.getPlayerDatabase().setAttribute(
                    uuid,
                    username,
                    isBedrock,
                    "platform",
                    isBedrock ? "Bedrock" : "Java"
            );
            List<PlayerEntry> topTen = plugin.getPlayerDatabase().refreshTopTenSnapshot(10);
            Bukkit.getScheduler().runTask(plugin, () ->
                    plugin.getHologramManager().updateHologram(topTen));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        plugin.getPlayerDatabase().markPlayerQuit(uuid);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                plugin.getPlayerDatabase().flushAndRefreshIfDirty(10));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String username = player.getName();
        boolean isBedrock = BedrockUtil.isBedrockPlayer(uuid);
        String xuid = isBedrock ? BedrockUtil.getFloodgateXuid(uuid) : null;

        plugin.getPlayerDatabase().incrementBlocksBroken(uuid, username, isBedrock, xuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (plugin.getHologramManager().isHologramChunk(event.getChunk())) {
            Bukkit.getScheduler().runTask(plugin, () ->
                    plugin.getHologramManager().updateHologram(
                            plugin.getPlayerDatabase().getCachedTopTen()
                    ));
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
