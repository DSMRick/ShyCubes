package com.shycubes;

import com.shycubes.command.LeaderboardCommand;
import com.shycubes.db.PlayerDatabase;
import com.shycubes.leaderboard.LeaderboardHologramManager;
import com.shycubes.listener.PlayerActivityListener;
import com.shycubes.model.StatQuery;
import com.shycubes.telemetry.OtelTelemetry;
import com.shycubes.telemetry.OtelTelemetry.Level;
import com.shycubes.telemetry.OtelTelemetry.SpanContext;
import com.shycubes.util.BedrockUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ShyCubesPlugin extends JavaPlugin {

    private OtelTelemetry telemetry;
    private PlayerDatabase playerDatabase;
    private LeaderboardHologramManager hologramManager;

    @Override
    public void onEnable() {
        this.telemetry = new OtelTelemetry();

        try (OtelTelemetry.SpanScope enableSpan = telemetry.startSpan("plugin.enable", Level.INFO)) {
            saveDefaultConfig();

            String dbFileName = getConfig().getString("database.file", "players.db");
            this.playerDatabase = new PlayerDatabase(getDataFolder(), dbFileName, telemetry);
            try {
                this.playerDatabase.initialize();
            } catch (SQLException e) {
                enableSpan.recordException(e);
                telemetry.emitEvent("plugin.db.init_failed", Level.FATAL, Map.of(
                        "db.file", dbFileName,
                        "error.message", e.getMessage() != null ? e.getMessage() : "SQLException"
                ));
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

            this.hologramManager = new LeaderboardHologramManager(this, telemetry);
            this.playerDatabase.refreshSnapshots(this.hologramManager.getActiveQueries());

            // Register event listeners
            Bukkit.getPluginManager().registerEvents(new PlayerActivityListener(this, telemetry), this);

            // Register commands
            PluginCommand lbCmd = getCommand("leaderboard");
            if (lbCmd != null) {
                LeaderboardCommand executor = new LeaderboardCommand(this, telemetry);
                lbCmd.setExecutor(executor);
                lbCmd.setTabCompleter(executor);
            }

            // Initial hologram render once worlds are ready
            Bukkit.getScheduler().runTask(this, () ->
                    this.hologramManager.updateAllBillboards(this.playerDatabase::getCachedLeaderboard)
            );

            // Periodic async DB flush + main-thread billboard updates
            long intervalSeconds = Math.max(1L, getConfig().getLong("leaderboard.update-interval-seconds", 2L));
            long intervalTicks = intervalSeconds * 20L;
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                Set<StatQuery> activeQueries = this.hologramManager.getActiveQueries();
                if (activeQueries.isEmpty()) {
                    this.playerDatabase.flushDirty();
                    return;
                }
                try (OtelTelemetry.SpanScope refreshSpan = telemetry.startSpan(
                        "scheduler.leaderboard_refresh", Level.DEBUG, Map.of("query.count", activeQueries.size()))) {
                    if (this.playerDatabase.flushAndRefreshIfDirty(activeQueries)) {
                        SpanContext ctx = refreshSpan.context();
                        Bukkit.getScheduler().runTask(this, () -> {
                            if (this.isEnabled() && this.hologramManager != null) {
                                try (OtelTelemetry.SpanScope ignored = telemetry.startSpan(
                                        "scheduler.leaderboard_render", Level.DEBUG, ctx, Map.of())) {
                                    this.hologramManager.updateAllBillboards(this.playerDatabase::getCachedLeaderboard);
                                }
                            }
                        });
                    }
                }
            }, intervalTicks, intervalTicks);

            telemetry.emitEvent("plugin.lifecycle.enabled", Level.INFO, Map.of(
                    "billboard.count", this.hologramManager.getBillboards().size(),
                    "update.interval_seconds", intervalSeconds
            ));
        }
    }

    @Override
    public void onDisable() {
        if (this.telemetry != null) {
            try (OtelTelemetry.SpanScope ignored = telemetry.startSpan("plugin.disable", Level.INFO)) {
                Bukkit.getScheduler().cancelTasks(this);
                if (this.playerDatabase != null) {
                    this.playerDatabase.flushDirty();
                }
                telemetry.emitEvent("plugin.lifecycle.disabled", Level.INFO);
            }
            this.telemetry.shutdown();
        } else {
            Bukkit.getScheduler().cancelTasks(this);
            if (this.playerDatabase != null) {
                this.playerDatabase.flushDirty();
            }
        }
    }

    public OtelTelemetry getTelemetry() {
        return telemetry;
    }

    public PlayerDatabase getPlayerDatabase() {
        return playerDatabase;
    }

    public LeaderboardHologramManager getHologramManager() {
        return hologramManager;
    }
}
