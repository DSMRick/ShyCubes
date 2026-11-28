package com.shycubes.db;

import com.shycubes.model.LeaderboardRow;
import com.shycubes.model.PlayerEntry;
import com.shycubes.model.StatOrder;
import com.shycubes.model.StatQuery;
import com.shycubes.telemetry.OtelTelemetry;
import com.shycubes.telemetry.OtelTelemetry.Level;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SQLite-backed player database with lock-free in-memory write-back caching
 * for extensible numeric player_stats (long) and string player_attributes.
 */
public class PlayerDatabase {

    private final File dbFile;
    private final OtelTelemetry telemetry;
    private final String jdbcUrl;

    private final ConcurrentHashMap<UUID, CachedPlayer> cache = new ConcurrentHashMap<>();
    private final AtomicBoolean leaderboardDirty = new AtomicBoolean(true);
    private final ConcurrentHashMap<StatQuery, List<LeaderboardRow>> cachedSnapshots = new ConcurrentHashMap<>();
    private final Set<String> knownStatKeys = ConcurrentHashMap.newKeySet();

    private static final class CachedPlayer {
        final UUID uuid;
        volatile String username;
        volatile boolean bedrock;
        volatile String xuid;
        volatile long firstJoin;
        final AtomicLong lastSeen;
        final ConcurrentHashMap<String, AtomicLong> stats = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, String> attributes = new ConcurrentHashMap<>();
        final AtomicBoolean loadedFromDb = new AtomicBoolean(false);
        final AtomicBoolean dirty = new AtomicBoolean(false);

        CachedPlayer(UUID uuid, String username, boolean bedrock, String xuid, long firstJoin, long lastSeen) {
            this.uuid = uuid;
            this.username = username;
            this.bedrock = bedrock;
            this.xuid = xuid;
            this.firstJoin = firstJoin;
            this.lastSeen = new AtomicLong(lastSeen);
        }

        PlayerEntry toEntry() {
            Map<String, Long> statCopy = new HashMap<>(stats.size());
            for (Map.Entry<String, AtomicLong> entry : stats.entrySet()) {
                statCopy.put(entry.getKey(), entry.getValue().get());
            }
            return new PlayerEntry(
                    uuid,
                    username,
                    bedrock,
                    xuid,
                    firstJoin,
                    lastSeen.get(),
                    statCopy,
                    new HashMap<>(attributes)
            );
        }
    }

    public PlayerDatabase(File dataFolder, String fileName, OtelTelemetry telemetry) {
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            telemetry.emitEvent("db.folder.create_failed", Level.WARN, Map.of("path", dataFolder.getAbsolutePath()));
        }
        this.dbFile = new File(dataFolder, fileName);
        this.telemetry = telemetry;
        this.jdbcUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
    }

    public synchronized void initialize() throws SQLException {
        try (OtelTelemetry.SpanScope span = telemetry.startSpan(
                "db.initialize", Level.INFO, Map.of("db.system", "sqlite", "db.file", dbFile.getName()))) {
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                span.recordException(e);
                throw new SQLException("SQLite JDBC driver not found in server runtime", e);
            }

            try (Connection conn = openConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL;");
                stmt.execute("PRAGMA synchronous=NORMAL;");
                stmt.execute("PRAGMA foreign_keys=ON;");

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS players (
                        uuid TEXT PRIMARY KEY,
                        username TEXT NOT NULL,
                        is_bedrock INTEGER NOT NULL DEFAULT 0,
                        xuid TEXT,
                        first_join INTEGER NOT NULL,
                        last_seen INTEGER NOT NULL
                    );
                    """);

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS player_stats (
                        uuid TEXT NOT NULL,
                        stat_key TEXT NOT NULL,
                        stat_value INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY (uuid, stat_key),
                        FOREIGN KEY (uuid) REFERENCES players(uuid) ON DELETE CASCADE
                    );
                    """);

                stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_player_stats_rank_desc
                    ON player_stats(stat_key, stat_value DESC);
                    """);

                stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_player_stats_rank_asc
                    ON player_stats(stat_key, stat_value ASC);
                    """);

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS player_attributes (
                        uuid TEXT NOT NULL,
                        attr_key TEXT NOT NULL,
                        attr_value TEXT NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY (uuid, attr_key),
                        FOREIGN KEY (uuid) REFERENCES players(uuid) ON DELETE CASCADE
                    );
                    """);

                try (ResultSet rs = stmt.executeQuery("SELECT DISTINCT stat_key FROM player_stats;")) {
                    while (rs.next()) {
                        String key = rs.getString("stat_key");
                        if (key != null && !key.isBlank()) {
                            knownStatKeys.add(key);
                        }
                    }
                }
            } catch (SQLException e) {
                span.recordException(e);
                throw e;
            }
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    /**
     * Loads or creates a player record on join. Safe to call asynchronously.
     */
    public synchronized PlayerEntry recordPlayerJoin(UUID uuid, String username, boolean isBedrock, String xuid) {
        try (OtelTelemetry.SpanScope span = telemetry.startSpan(
                "db.load_player", Level.INFO, Map.of("db.system", "sqlite", "player.uuid", uuid.toString()))) {
            long now = System.currentTimeMillis();
            CachedPlayer existing = cache.get(uuid);
            if (existing != null && existing.loadedFromDb.get()) {
                existing.username = username;
                existing.bedrock = isBedrock;
                if (xuid != null) {
                    existing.xuid = xuid;
                }
                existing.lastSeen.set(now);
                existing.dirty.set(true);
                leaderboardDirty.set(true);
                flushDirty();
                return existing.toEntry();
            }

            try (Connection conn = openConnection()) {
                long firstJoin = now;
                String storedXuid = xuid;

                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT first_join, xuid FROM players WHERE uuid = ?")) {
                    ps.setString(1, uuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            firstJoin = rs.getLong("first_join");
                            if (storedXuid == null) {
                                storedXuid = rs.getString("xuid");
                            }
                        }
                    }
                }

                CachedPlayer target = existing != null
                        ? existing
                        : new CachedPlayer(uuid, username, isBedrock, storedXuid, firstJoin, now);
                target.username = username;
                target.bedrock = isBedrock;
                if (storedXuid != null) {
                    target.xuid = storedXuid;
                }
                target.firstJoin = Math.min(target.firstJoin, firstJoin);
                target.lastSeen.set(now);

                try (PreparedStatement statPs = conn.prepareStatement(
                        "SELECT stat_key, stat_value FROM player_stats WHERE uuid = ?")) {
                    statPs.setString(1, uuid.toString());
                    try (ResultSet rs = statPs.executeQuery()) {
                        while (rs.next()) {
                            String key = rs.getString("stat_key");
                            long val = rs.getLong("stat_value");
                            knownStatKeys.add(key);
                            target.stats.compute(key, (k, existingVal) -> {
                                if (existingVal == null) {
                                    return new AtomicLong(val);
                                }
                                existingVal.addAndGet(val);
                                return existingVal;
                            });
                        }
                    }
                }

                try (PreparedStatement attrPs = conn.prepareStatement(
                        "SELECT attr_key, attr_value FROM player_attributes WHERE uuid = ?")) {
                    attrPs.setString(1, uuid.toString());
                    try (ResultSet rs = attrPs.executeQuery()) {
                        while (rs.next()) {
                            target.attributes.putIfAbsent(rs.getString("attr_key"), rs.getString("attr_value"));
                        }
                    }
                }

                target.loadedFromDb.set(true);
                target.dirty.set(true);
                cache.put(uuid, target);
                leaderboardDirty.set(true);
                flushDirty();
                return target.toEntry();
            } catch (SQLException e) {
                span.recordException(e);
                telemetry.emitEvent("db.player.load_failed", Level.ERROR, Map.of(
                        "player.uuid", uuid.toString(),
                        "error.message", e.getMessage() != null ? e.getMessage() : "SQLException"
                ));
                CachedPlayer fallback = existing != null
                        ? existing
                        : new CachedPlayer(uuid, username, isBedrock, xuid, now, now);
                fallback.dirty.set(true);
                cache.put(uuid, fallback);
                return fallback.toEntry();
            }
        }
    }

    /**
     * Increments any numeric player stat in memory with zero main-thread blocking.
     */
    public long incrementStat(UUID uuid, String username, boolean isBedrock, String xuid, String statKey, long delta) {
        try (OtelTelemetry.SpanScope ignored = telemetry.startSpan(
                "player.stat_increment", Level.TRACE, Map.of("stat.key", statKey, "stat.delta", delta))) {
            long now = System.currentTimeMillis();
            CachedPlayer player = cache.computeIfAbsent(uuid,
                    id -> new CachedPlayer(id, username, isBedrock, xuid, now, now));
            player.username = username;
            player.bedrock = isBedrock;
            if (xuid != null) {
                player.xuid = xuid;
            }
            player.lastSeen.set(now);
            knownStatKeys.add(statKey);
            long newTotal = player.stats
                    .computeIfAbsent(statKey, k -> new AtomicLong(0L))
                    .addAndGet(delta);
            player.dirty.set(true);
            leaderboardDirty.set(true);
            return newTotal;
        }
    }

    /**
     * Sets a numeric player stat directly in memory and marks for SQLite persistence.
     */
    public void setStat(UUID uuid, String username, boolean isBedrock, String xuid, String statKey, long value) {
        long now = System.currentTimeMillis();
        CachedPlayer player = cache.computeIfAbsent(uuid,
                id -> new CachedPlayer(id, username, isBedrock, xuid, now, now));
        player.username = username;
        player.bedrock = isBedrock;
        if (xuid != null) {
            player.xuid = xuid;
        }
        player.lastSeen.set(now);
        knownStatKeys.add(statKey);
        player.stats.computeIfAbsent(statKey, k -> new AtomicLong(0L)).set(value);
        player.dirty.set(true);
        leaderboardDirty.set(true);
    }

    public long getStat(UUID uuid, String statKey) {
        CachedPlayer player = cache.get(uuid);
        if (player == null) {
            return 0L;
        }
        AtomicLong val = player.stats.get(statKey);
        return val != null ? val.get() : 0L;
    }

    public Set<String> getKnownStatKeys() {
        return Collections.unmodifiableSet(new TreeSet<>(knownStatKeys));
    }

    /**
     * Sets a custom string attribute for a player in memory and marks for SQLite persistence.
     */
    public void setAttribute(UUID uuid, String username, boolean isBedrock, String key, String value) {
        long now = System.currentTimeMillis();
        CachedPlayer player = cache.computeIfAbsent(uuid,
                id -> new CachedPlayer(id, username, isBedrock, null, now, now));
        player.attributes.put(key, value);
        player.lastSeen.set(now);
        player.dirty.set(true);
    }

    public String getAttribute(UUID uuid, String key) {
        CachedPlayer player = cache.get(uuid);
        if (player != null) {
            return player.attributes.get(key);
        }
        return null;
    }

    public PlayerEntry getCachedPlayer(UUID uuid) {
        CachedPlayer player = cache.get(uuid);
        return player != null ? player.toEntry() : null;
    }

    public void markPlayerQuit(UUID uuid) {
        CachedPlayer player = cache.get(uuid);
        if (player != null) {
            player.lastSeen.set(System.currentTimeMillis());
            player.dirty.set(true);
        }
    }

    /**
     * Returns the latest cached leaderboard snapshot for a StatQuery (safe on main server thread).
     */
    public List<LeaderboardRow> getCachedLeaderboard(StatQuery query) {
        return cachedSnapshots.getOrDefault(query, Collections.emptyList());
    }

    /**
     * Flushes dirty players to SQLite and refreshes all requested billboard snapshots if any data changed.
     */
    public synchronized boolean flushAndRefreshIfDirty(Collection<StatQuery> queries) {
        boolean flushedAny = flushDirty();
        if (flushedAny || leaderboardDirty.getAndSet(false)) {
            refreshSnapshots(queries);
            return true;
        }
        return false;
    }

    /**
     * Flushes all dirty cached players, stats, and attributes to SQLite in a single transaction.
     */
    public synchronized boolean flushDirty() {
        List<CachedPlayer> toFlush = new ArrayList<>();
        for (CachedPlayer cp : cache.values()) {
            if (cp.dirty.compareAndSet(true, false)) {
                toFlush.add(cp);
            }
        }
        if (toFlush.isEmpty()) {
            return false;
        }

        try (OtelTelemetry.SpanScope span = telemetry.startSpan(
                "db.flush_dirty", Level.INFO, Map.of("db.system", "sqlite", "db.batch_players", toFlush.size()))) {
            String upsertPlayerSql = """
                INSERT INTO players (uuid, username, is_bedrock, xuid, first_join, last_seen)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    username = excluded.username,
                    is_bedrock = excluded.is_bedrock,
                    xuid = COALESCE(excluded.xuid, players.xuid),
                    last_seen = excluded.last_seen;
                """;

            String upsertStatSql = """
                INSERT INTO player_stats (uuid, stat_key, stat_value, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(uuid, stat_key) DO UPDATE SET
                    stat_value = excluded.stat_value,
                    updated_at = excluded.updated_at;
                """;

            String upsertAttrSql = """
                INSERT INTO player_attributes (uuid, attr_key, attr_value, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(uuid, attr_key) DO UPDATE SET
                    attr_value = excluded.attr_value,
                    updated_at = excluded.updated_at;
                """;

            long now = System.currentTimeMillis();
            try (Connection conn = openConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement psPlayer = conn.prepareStatement(upsertPlayerSql);
                     PreparedStatement psStat = conn.prepareStatement(upsertStatSql);
                     PreparedStatement psAttr = conn.prepareStatement(upsertAttrSql)) {
                    int statCount = 0;
                    for (CachedPlayer cp : toFlush) {
                        psPlayer.setString(1, cp.uuid.toString());
                        psPlayer.setString(2, cp.username);
                        psPlayer.setInt(3, cp.bedrock ? 1 : 0);
                        psPlayer.setString(4, cp.xuid);
                        psPlayer.setLong(5, cp.firstJoin);
                        psPlayer.setLong(6, cp.lastSeen.get());
                        psPlayer.addBatch();

                        for (Map.Entry<String, AtomicLong> stat : cp.stats.entrySet()) {
                            psStat.setString(1, cp.uuid.toString());
                            psStat.setString(2, stat.getKey());
                            psStat.setLong(3, stat.getValue().get());
                            psStat.setLong(4, now);
                            psStat.addBatch();
                            statCount++;
                        }

                        for (Map.Entry<String, String> attr : cp.attributes.entrySet()) {
                            psAttr.setString(1, cp.uuid.toString());
                            psAttr.setString(2, attr.getKey());
                            psAttr.setString(3, attr.getValue());
                            psAttr.setLong(4, now);
                            psAttr.addBatch();
                        }
                    }
                    span.setAttribute("db.batch_stats", statCount);
                    psPlayer.executeBatch();
                    psStat.executeBatch();
                    psAttr.executeBatch();
                    conn.commit();
                    return true;
                } catch (SQLException e) {
                    conn.rollback();
                    for (CachedPlayer cp : toFlush) {
                        cp.dirty.set(true);
                    }
                    throw e;
                }
            } catch (SQLException e) {
                span.recordException(e);
                telemetry.emitEvent("db.flush.failed", Level.ERROR, Map.of(
                        "error.message", e.getMessage() != null ? e.getMessage() : "SQLException"
                ));
                return false;
            }
        }
    }

    /**
     * Queries SQLite for each unique StatQuery and updates the cached leaderboard snapshots.
     */
    public synchronized void refreshSnapshots(Collection<StatQuery> queries) {
        if (queries == null || queries.isEmpty()) {
            return;
        }
        flushDirty();
        Set<StatQuery> unique = new LinkedHashSet<>(queries);
        try (OtelTelemetry.SpanScope span = telemetry.startSpan(
                "db.query_top_stats", Level.INFO, Map.of("db.system", "sqlite", "query.count", unique.size()))) {
            String descSql = """
                SELECT p.uuid, p.username, s.stat_value
                FROM player_stats s
                JOIN players p ON p.uuid = s.uuid
                WHERE s.stat_key = ?
                ORDER BY s.stat_value DESC, p.last_seen DESC
                LIMIT ?;
                """;
            String ascSql = """
                SELECT p.uuid, p.username, s.stat_value
                FROM player_stats s
                JOIN players p ON p.uuid = s.uuid
                WHERE s.stat_key = ?
                ORDER BY s.stat_value ASC, p.last_seen DESC
                LIMIT ?;
                """;

            try (Connection conn = openConnection();
                 PreparedStatement psDesc = conn.prepareStatement(descSql);
                 PreparedStatement psAsc = conn.prepareStatement(ascSql)) {
                for (StatQuery query : unique) {
                    PreparedStatement ps = (query.order() == StatOrder.ASC) ? psAsc : psDesc;
                    ps.setString(1, query.statKey());
                    ps.setInt(2, query.limit());
                    List<LeaderboardRow> rows = new ArrayList<>(query.limit());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            UUID uuid = UUID.fromString(rs.getString("uuid"));
                            String username = rs.getString("username");
                            long value = rs.getLong("stat_value");
                            rows.add(new LeaderboardRow(uuid, username, value));
                        }
                    }
                    cachedSnapshots.put(query, Collections.unmodifiableList(rows));
                }
            } catch (SQLException e) {
                span.recordException(e);
                telemetry.emitEvent("db.leaderboard.query_failed", Level.ERROR, Map.of(
                        "error.message", e.getMessage() != null ? e.getMessage() : "SQLException"
                ));
            }
        }
    }
}
