package com.shycubes;

import com.shycubes.command.LeaderboardCommand;
import com.shycubes.db.PlayerDatabase;
import com.shycubes.leaderboard.LeaderboardHologramManager;
import com.shycubes.listener.PlayerActivityListener;
import com.shycubes.model.PlayerEntry;
import com.shycubes.util.BedrockUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public class ShyCubesPlugin extends JavaPlugin {

    private PlayerDatabase playerDatabase;
    private LeaderboardHologramManager hologramManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        String dbFileName = getConfig().getString("database.file", "players.db");
        this.playerDatabase = new PlayerDatabase(getDataFolder(), dbFileName, getLogger());
        try {
            this.playerDatabase.initialize();
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to initialize SQLite player database! Disabling ShyCubes.", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // Load any already-online players (e.g., if plugin was loaded while server running)
        for (Player online : Bukkit.getOnlinePlayers()) {
            UUID uuid = online.getUniqueId();
            boolean isBedrock = BedrockUtil.isBedrockPlayer(uuid);
            String xuid = isBedrock ? BedrockUtil.getFloodgateXuid(uuid) : null;
            this.playerDatabase.recordPlayerJoin(uuid, online.getName(), isBedrock, xuid);
        }

        this.hologramManager = new LeaderboardHologramManager(this);

        // Register event listeners
        Bukkit.getPluginManager().registerEvents(new PlayerActivityListener(this), this);

        // Register commands
        PluginCommand lbCmd = getCommand("leaderboard");
        if (lbCmd != null) {
            LeaderboardCommand executor = new LeaderboardCommand(this);
            lbCmd.setExecutor(executor);
            lbCmd.setTabCompleter(executor);
        }

        // Initial hologram render once worlds are ready
        Bukkit.getScheduler().runTask(this, () ->
                this.hologramManager.updateHologram(this.playerDatabase.getCachedTopTen())
        );

        // Periodic async DB flush + main-thread hologram update
        long intervalSeconds = Math.max(1L, getConfig().getLong("leaderboard.update-interval-seconds", 2L));
        long intervalTicks = intervalSeconds * 20L;
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (this.playerDatabase.flushAndRefreshIfDirty(10)) {
                List<PlayerEntry> latestTop = this.playerDatabase.getCachedTopTen();
                Bukkit.getScheduler().runTask(this, () -> {
                    if (this.isEnabled() && this.hologramManager != null) {
                        this.hologramManager.updateHologram(latestTop);
                    }
                });
            }
        }, intervalTicks, intervalTicks);

        getLogger().info("ShyCubes enabled! Tracking player attributes & Top 10 Blocks Broken hologram.");
    }

    @Override
    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        if (this.playerDatabase != null) {
            this.playerDatabase.flushDirty();
            getLogger().info("Flushed all player database records to SQLite.");
        }
    }

    public PlayerDatabase getPlayerDatabase() {
        return playerDatabase;
    }

    public LeaderboardHologramManager getHologramManager() {
        return hologramManager;
    }
}

