package com.shycubes.command;

import com.shycubes.ShyCubesPlugin;
import com.shycubes.model.PlayerEntry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class LeaderboardCommand implements CommandExecutor, TabCompleter {

    private final ShyCubesPlugin plugin;

    public LeaderboardCommand(ShyCubesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {
        if (args.length > 0) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "here", "sethere", "move" -> {
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage("§cOnly in-game players can set the hologram location.");
                        return true;
                    }
                    if (!sender.hasPermission("shycubes.leaderboard.admin") && !sender.isOp()) {
                        sender.sendMessage("§cYou do not have permission to move the leaderboard hologram.");
                        return true;
                    }
                    Location topAnchor = player.getLocation().getBlock().getLocation().add(0.5D, 2.8D, 0.5D);
                    plugin.getHologramManager().setHologramLocation(topAnchor, true);
                    sender.sendMessage(String.format(
                            Locale.US,
                            "§a[ShyCubes] §7Moved the Top 10 Blocks Broken floating hologram to §e%.1f, %.1f, %.1f §7(%s).",
                            topAnchor.getX(), topAnchor.getY(), topAnchor.getZ(), topAnchor.getWorld().getName()
                    ));
                    return true;
                }
                case "refresh" -> {
                    if (!sender.hasPermission("shycubes.leaderboard.admin") && !sender.isOp()) {
                        sender.sendMessage("§cYou do not have permission to refresh the leaderboard.");
                        return true;
                    }
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        List<PlayerEntry> top = plugin.getPlayerDatabase().refreshTopTenSnapshot(10);
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            plugin.getHologramManager().updateHologram(top);
                            sender.sendMessage("§a[ShyCubes] §7Leaderboard database flushed and floating hologram refreshed.");
                        });
                    });
                    return true;
                }
                case "remove", "off" -> {
                    if (!sender.hasPermission("shycubes.leaderboard.admin") && !sender.isOp()) {
                        sender.sendMessage("§cYou do not have permission to remove the leaderboard hologram.");
                        return true;
                    }
                    plugin.getHologramManager().disableAndRemoveHologram();
                    sender.sendMessage("§a[ShyCubes] §7Removed the in-world leaderboard hologram. Use §e/" + label + " here §7to place it again.");
                    return true;
                }
                case "stats", "info" -> {
                    Player target = (args.length >= 2) ? Bukkit.getPlayerExact(args[1]) : (sender instanceof Player p ? p : null);
                    if (target == null) {
                        sender.sendMessage("§cSpecify an online player: /" + label + " stats <player>");
                        return true;
                    }
                    PlayerEntry entry = plugin.getPlayerDatabase().getCachedPlayer(target.getUniqueId());
                    NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
                    sender.sendMessage("§6§l✦ §e§lPlayer Database Record: §f" + target.getName() + " §6§l✦");
                    if (entry != null) {
                        sender.sendMessage("§7UUID: §f" + entry.uuid());
                        sender.sendMessage("§7Platform: §b" + (entry.bedrock() ? "Bedrock (XUID: " + entry.xuid() + ")" : "Java"));
                        sender.sendMessage("§7Blocks Broken: §e" + nf.format(entry.blocksBroken()));
                        if (!entry.attributes().isEmpty()) {
                            sender.sendMessage("§7Attributes:");
                            for (Map.Entry<String, String> attr : entry.attributes().entrySet()) {
                                sender.sendMessage("  §8- §7" + attr.getKey() + ": §f" + attr.getValue());
                            }
                        }
                    } else {
                        sender.sendMessage("§7Blocks Broken: §e" + nf.format(plugin.getPlayerDatabase().getBlocksBroken(target.getUniqueId())));
                    }
                    return true;
                }
                default -> {
                    // Fall through to showing leaderboard
                }
            }
        }

        List<PlayerEntry> topTen = plugin.getPlayerDatabase().getCachedTopTen();
        List<String> lines = plugin.getHologramManager().buildLeaderboardLines(topTen);
        for (String line : lines) {
            sender.sendMessage(line);
        }

        if (sender instanceof Player player) {
            NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
            long myBlocks = plugin.getPlayerDatabase().getBlocksBroken(player.getUniqueId());
            sender.sendMessage("§7Your blocks broken: §e" + nf.format(myBlocks));
            if (player.isOp() || player.hasPermission("shycubes.leaderboard.admin")) {
                sender.sendMessage("§8Tip: Use §7/" + label + " here §8to move the 3D floating hologram to your position.");
            }
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String alias,
                                                @NotNull String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            options.add("stats");
            if (sender.isOp() || sender.hasPermission("shycubes.leaderboard.admin")) {
                options.add("here");
                options.add("refresh");
                options.add("remove");
            }
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return options.stream().filter(o -> o.startsWith(prefix)).toList();
        }
        return Collections.emptyList();
    }
}
