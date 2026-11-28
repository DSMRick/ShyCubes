package com.shycubes.command;

import com.shycubes.ShyCubesPlugin;
import com.shycubes.model.BillboardConfig;
import com.shycubes.model.LeaderboardRow;
import com.shycubes.model.PlayerEntry;
import com.shycubes.model.StatKeys;
import com.shycubes.model.StatQuery;
import com.shycubes.telemetry.OtelTelemetry;
import com.shycubes.telemetry.OtelTelemetry.Level;
import com.shycubes.telemetry.OtelTelemetry.SpanContext;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

public class LeaderboardCommand implements CommandExecutor, TabCompleter {

    private static final Pattern VALID_ID_PATTERN = Pattern.compile("^[a-z0-9_-]{1,32}$");
    private static final Set<String> RESERVED_SUBCOMMANDS = Set.of(
            "here", "sethere", "move", "create", "remove", "off", "delete",
            "list", "refresh", "reload", "stats", "info", "tp"
    );

    private final ShyCubesPlugin plugin;
    private final OtelTelemetry telemetry;

    public LeaderboardCommand(ShyCubesPlugin plugin, OtelTelemetry telemetry) {
        this.plugin = plugin;
        this.telemetry = telemetry;
    }

    @Override
    public boolean onCommand(CommandSender sender,
                             Command command,
                             String label,
                             String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "view";
        try (OtelTelemetry.SpanScope span = telemetry.startSpan(
                "command.leaderboard",
                Level.INFO,
                Map.of("command.sender", sender.getName(), "command.subcommand", sub))) {
            SpanContext parentCtx = span.context();

            if (args.length > 0) {
                switch (sub) {
                    case "here", "sethere", "move" -> {
                        return handleHere(sender, label, args, parentCtx);
                    }
                    case "create" -> {
                        return handleCreate(sender, label, args, parentCtx);
                    }
                    case "remove", "off" -> {
                        return handleRemove(sender, label, args);
                    }
                    case "delete" -> {
                        return handleDelete(sender, label, args);
                    }
                    case "list" -> {
                        return handleList(sender);
                    }
                    case "refresh" -> {
                        return handleRefresh(sender, args, parentCtx);
                    }
                    case "reload" -> {
                        return handleReload(sender, parentCtx);
                    }
                    case "tp" -> {
                        return handleTeleport(sender, label, args);
                    }
                    case "stats", "info" -> {
                        return handleStats(sender, label, args);
                    }
                    default -> {
                        // Treat arg[0] as a billboard ID to view in chat
                        return handleView(sender, label, sub);
                    }
                }
            }

            return handleView(sender, label, plugin.getHologramManager().getDefaultBillboardId());
        }
    }

    private boolean isAdmin(CommandSender sender) {
        return sender.isOp() || sender.hasPermission("shycubes.leaderboard.admin");
    }

    private boolean handleHere(CommandSender sender, String label, String[] args, SpanContext parentCtx) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly in-game players can set a billboard location.");
            return true;
        }
        if (!isAdmin(sender)) {
            sender.sendMessage("§cYou do not have permission to move billboards.");
            return true;
        }
        String id = args.length >= 2
                ? args[1].toLowerCase(Locale.ROOT)
                : plugin.getHologramManager().getDefaultBillboardId();
        BillboardConfig existing = plugin.getHologramManager().getBillboard(id);
        if (existing == null) {
            sender.sendMessage("§cUnknown billboard '§f" + id + "§c'. Use §e/" + label + " list §cor §e/" + label + " create <id> <stat>§c.");
            return true;
        }

        Location topAnchor = player.getLocation().getBlock().getLocation().add(0.5D, 2.8D, 0.5D);
        BillboardConfig moved = plugin.getHologramManager().setBillboardLocation(id, topAnchor, true);
        sender.sendMessage(String.format(
                Locale.US,
                "§a[ShyCubes] §7Moved billboard §f%s §7(stat: §e%s§7) to §e%.1f, %.1f, %.1f §7(%s).",
                moved.id(), moved.stat(), topAnchor.getX(), topAnchor.getY(), topAnchor.getZ(), topAnchor.getWorld().getName()
        ));

        refreshBillboardsAsync(List.of(moved), parentCtx, null);
        return true;
    }

    private boolean handleCreate(CommandSender sender, String label, String[] args, SpanContext parentCtx) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly in-game players can create billboards.");
            return true;
        }
        if (!isAdmin(sender)) {
            sender.sendMessage("§cYou do not have permission to create billboards.");
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /" + label + " create <id> <stat> [entries] [title...]");
            return true;
        }

        String id = args[1].toLowerCase(Locale.ROOT);
        if (!VALID_ID_PATTERN.matcher(id).matches() || RESERVED_SUBCOMMANDS.contains(id)) {
            sender.sendMessage("§cInvalid billboard ID '§f" + id + "§c'. Use 1-32 lowercase letters, numbers, '_' or '-' (not a command name).");
            return true;
        }
        if (plugin.getHologramManager().getBillboard(id) != null) {
            sender.sendMessage("§cBillboard '§f" + id + "§c' already exists. Use §e/" + label + " here " + id + " §cto move it.");
            return true;
        }

        String stat = args[2].trim();
        int entries = 10;
        int titleStartIdx = 3;
        if (args.length >= 4) {
            try {
                entries = Math.max(1, Math.min(50, Integer.parseInt(args[3])));
                titleStartIdx = 4;
            } catch (NumberFormatException ignored) {
                titleStartIdx = 3;
            }
        }

        String title;
        if (args.length > titleStartIdx) {
            title = String.join(" ", Arrays.copyOfRange(args, titleStartIdx, args.length));
        } else {
            title = "TOP " + entries + " " + stat.replace('_', ' ').toUpperCase(Locale.ROOT);
        }

        Location topAnchor = player.getLocation().getBlock().getLocation().add(0.5D, 2.8D, 0.5D);
        BillboardConfig created = plugin.getHologramManager().createBillboard(id, stat, entries, title, topAnchor);
        sender.sendMessage(String.format(
                Locale.US,
                "§a[ShyCubes] §7Created billboard §f%s §7(stat: §e%s§7, entries: §e%d§7) at §e%.1f, %.1f, %.1f §7(%s).",
                created.id(), created.stat(), created.entries(),
                topAnchor.getX(), topAnchor.getY(), topAnchor.getZ(), topAnchor.getWorld().getName()
        ));

        refreshBillboardsAsync(List.of(created), parentCtx, null);
        return true;
    }

    private boolean handleRemove(CommandSender sender, String label, String[] args) {
        if (!isAdmin(sender)) {
            sender.sendMessage("§cYou do not have permission to remove billboards.");
            return true;
        }
        String id = args.length >= 2
                ? args[1].toLowerCase(Locale.ROOT)
                : plugin.getHologramManager().getDefaultBillboardId();
        if (!plugin.getHologramManager().disableBillboard(id)) {
            sender.sendMessage("§cUnknown billboard '§f" + id + "§c'.");
            return true;
        }
        sender.sendMessage("§a[ShyCubes] §7Disabled and removed billboard §f" + id + "§7. Use §e/" + label + " here " + id + " §7to place it again.");
        return true;
    }

    private boolean handleDelete(CommandSender sender, String label, String[] args) {
        if (!isAdmin(sender)) {
            sender.sendMessage("§cYou do not have permission to delete billboards.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /" + label + " delete <id>");
            return true;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        if (!plugin.getHologramManager().deleteBillboard(id)) {
            sender.sendMessage("§cUnknown billboard '§f" + id + "§c'.");
            return true;
        }
        sender.sendMessage("§a[ShyCubes] §7Permanently deleted billboard §f" + id + " §7from config.yml.");
        return true;
    }

    private boolean handleList(CommandSender sender) {
        var boards = plugin.getHologramManager().getBillboards();
        sender.sendMessage("§6§l✦ §e§lShyCubes Billboards §7(" + boards.size() + ") §6§l✦");
        if (boards.isEmpty()) {
            sender.sendMessage("§7No billboards configured.");
            return true;
        }
        String defaultId = plugin.getHologramManager().getDefaultBillboardId();
        for (BillboardConfig cfg : boards) {
            String status = cfg.enabled() ? "§aON" : "§cOFF";
            String defTag = cfg.id().equals(defaultId) ? " §e(default)" : "";
            String locStr = cfg.hasLocation()
                    ? String.format(Locale.US, "%s @ %.1f, %.1f, %.1f", cfg.worldName(), cfg.x(), cfg.y(), cfg.z())
                    : "unset";
            sender.sendMessage("§8- §f" + cfg.id() + defTag + " §8[" + status + "§8] §7stat=§e" + cfg.stat()
                    + " §7top=§e" + cfg.entries() + " §8(" + locStr + ")");
        }
        return true;
    }

    private boolean handleRefresh(CommandSender sender, String[] args, SpanContext parentCtx) {
        if (!isAdmin(sender)) {
            sender.sendMessage("§cYou do not have permission to refresh billboards.");
            return true;
        }
        if (args.length >= 2) {
            String id = args[1].toLowerCase(Locale.ROOT);
            BillboardConfig cfg = plugin.getHologramManager().getBillboard(id);
            if (cfg == null) {
                sender.sendMessage("§cUnknown billboard '§f" + id + "§c'.");
                return true;
            }
            refreshBillboardsAsync(List.of(cfg), parentCtx,
                    () -> sender.sendMessage("§a[ShyCubes] §7Refreshed billboard §f" + cfg.id() + "§7."));
            return true;
        }

        List<BillboardConfig> all = new ArrayList<>(plugin.getHologramManager().getBillboards());
        refreshBillboardsAsync(all, parentCtx,
                () -> sender.sendMessage("§a[ShyCubes] §7Flushed database and refreshed all billboards."));
        return true;
    }

    private boolean handleReload(CommandSender sender, SpanContext parentCtx) {
        if (!isAdmin(sender)) {
            sender.sendMessage("§cYou do not have permission to reload billboards.");
            return true;
        }
        plugin.getHologramManager().reloadConfig();
        List<BillboardConfig> all = new ArrayList<>(plugin.getHologramManager().getBillboards());
        refreshBillboardsAsync(all, parentCtx,
                () -> sender.sendMessage("§a[ShyCubes] §7Reloaded config.yml and updated §e" + all.size() + " §7billboard(s)."));
        return true;
    }

    private boolean handleTeleport(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly in-game players can teleport to billboards.");
            return true;
        }
        if (!isAdmin(sender)) {
            sender.sendMessage("§cYou do not have permission to teleport to billboards.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /" + label + " tp <id>");
            return true;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        BillboardConfig cfg = plugin.getHologramManager().getBillboard(id);
        if (cfg == null) {
            sender.sendMessage("§cUnknown billboard '§f" + id + "§c'.");
            return true;
        }
        Location loc = cfg.toLocation();
        if (loc == null) {
            sender.sendMessage("§cBillboard '§f" + id + "§c' does not have a valid loaded world location.");
            return true;
        }
        Location dest = loc.clone().subtract(0.0D, 2.8D, 0.0D);
        player.teleport(dest);
        sender.sendMessage("§a[ShyCubes] §7Teleported to billboard §f" + id + "§7.");
        return true;
    }

    private boolean handleStats(CommandSender sender, String label, String[] args) {
        Player target = (args.length >= 2)
                ? Bukkit.getPlayerExact(args[1])
                : (sender instanceof Player p ? p : null);
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
            if (!entry.stats().isEmpty()) {
                sender.sendMessage("§7Stats:");
                for (Map.Entry<String, Long> stat : new TreeMap<>(entry.stats()).entrySet()) {
                    sender.sendMessage("  §8- §7" + stat.getKey() + ": §e" + nf.format(stat.getValue()));
                }
            } else {
                sender.sendMessage("§7Stats: §8(none recorded yet)");
            }
            if (!entry.attributes().isEmpty()) {
                sender.sendMessage("§7Attributes:");
                for (Map.Entry<String, String> attr : new TreeMap<>(entry.attributes()).entrySet()) {
                    sender.sendMessage("  §8- §7" + attr.getKey() + ": §f" + attr.getValue());
                }
            }
        } else {
            sender.sendMessage("§7No cached database record for §f" + target.getName() + "§7.");
        }
        return true;
    }

    private boolean handleView(CommandSender sender, String label, String id) {
        BillboardConfig cfg = plugin.getHologramManager().getBillboard(id);
        if (cfg == null) {
            sender.sendMessage("§cUnknown billboard '§f" + id + "§c'. Use §e/" + label + " list §cto see available billboards.");
            return true;
        }
        List<LeaderboardRow> rows = plugin.getPlayerDatabase().getCachedLeaderboard(cfg.toStatQuery());
        List<String> lines = plugin.getHologramManager().buildLeaderboardLines(cfg, rows);
        for (String line : lines) {
            sender.sendMessage(line);
        }

        if (sender instanceof Player player) {
            NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
            long myVal = plugin.getPlayerDatabase().getStat(player.getUniqueId(), cfg.stat());
            String unitSuffix = cfg.unit().isBlank() ? "" : " " + cfg.unit();
            sender.sendMessage("§7Your §f" + cfg.stat() + "§7: §e" + nf.format(myVal) + unitSuffix);
            if (isAdmin(player)) {
                sender.sendMessage("§8Tip: Use §7/" + label + " here " + cfg.id() + " §8to move this billboard to your position.");
            }
        }
        return true;
    }

    private void refreshBillboardsAsync(List<BillboardConfig> targets, SpanContext parentCtx, Runnable onComplete) {
        List<StatQuery> queries = targets.stream().map(BillboardConfig::toStatQuery).toList();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (OtelTelemetry.SpanScope ignored = telemetry.startSpan(
                    "command.leaderboard.refresh_async", Level.DEBUG, parentCtx, Map.of("query.count", queries.size()))) {
                plugin.getPlayerDatabase().refreshSnapshots(queries);
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!plugin.isEnabled()) {
                    return;
                }
                for (BillboardConfig cfg : targets) {
                    if (cfg.enabled()) {
                        plugin.getHologramManager().updateBillboard(
                                cfg,
                                plugin.getPlayerDatabase().getCachedLeaderboard(cfg.toStatQuery())
                        );
                    }
                }
                if (onComplete != null) {
                    onComplete.run();
                }
            });
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender,
                                      Command command,
                                      String alias,
                                      String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            options.add("list");
            options.add("stats");
            if (isAdmin(sender)) {
                options.add("here");
                options.add("create");
                options.add("remove");
                options.add("delete");
                options.add("refresh");
                options.add("reload");
                options.add("tp");
            }
            options.addAll(plugin.getHologramManager().getBillboardIds());
            return filterPrefix(options, args[0]);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (sub) {
                case "here", "sethere", "move", "remove", "off", "delete", "refresh", "tp" ->
                        isAdmin(sender)
                                ? filterPrefix(plugin.getHologramManager().getBillboardIds(), args[1])
                                : Collections.emptyList();
                case "stats", "info" ->
                        filterPrefix(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
                default -> Collections.emptyList();
            };
        }

        if (args.length == 3 && "create".equals(sub) && isAdmin(sender)) {
            Set<String> stats = new TreeSet<>(plugin.getPlayerDatabase().getKnownStatKeys());
            stats.addAll(StatKeys.ALL_BUILTINS);
            return filterPrefix(stats, args[2]);
        }

        if (args.length == 4 && "create".equals(sub) && isAdmin(sender)) {
            return filterPrefix(List.of("5", "10", "15"), args[3]);
        }

        return Collections.emptyList();
    }

    private static List<String> filterPrefix(Iterable<String> candidates, String rawPrefix) {
        String prefix = rawPrefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String c : candidates) {
            if (c.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                matches.add(c);
            }
        }
        return matches;
    }
}
