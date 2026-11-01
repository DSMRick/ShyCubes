package com.shycubes.db;

import com.shycubes.model.PlayerEntry;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite-backed player database with in-memory write-back caching and extensible key-value attributes.
 */
public class PlayerDatabase {

    private final File dbFile;
    private final Logger logger;
    private final String jdbcUrl;

    private final ConcurrentHashMap<UUID, CachedPlayer> cache = new ConcurrentHashMap<>();
    private final AtomicBoolean leaderboardDirty = new AtomicBoolean(true);
    private volatile List<PlayerEntry> cachedTopTen = Collections.emptyList();

    private static final class CachedPlayer {
        final UUID uuid;
        volatile String username;
        volatile boolean bedrock;
        volatile String xuid;
        final AtomicLong blocksBroken;
        final long firstJoin;
        final AtomicLong lastSeen;
        final ConcurrentHashMap<String, String> attributes = new ConcurrentHashMap<>();
        final AtomicBoolean dirty = new AtomicBoolean(false);

        CachedPlayer(UUID uuid, String username, boolean bedrock, String xuid, long blocksBroken, long firstJoin, long lastSeen) {
            this.uuid = uuid;
            this.username = username;
            this.bedrock = bedrock;
            this.xuid = xuid;
            this.blocksBroken = new AtomicLong(blocksBroken);
            this.firstJoin = firstJoin;
            this.lastSeen = new AtomicLong(lastSeen);
        }

        PlayerEntry toEntry() {
            return new PlayerEntry(
                    uuid,
                    username,
                    bedrock,
                    xuid,
                    blocksBroken.get(),
                    firstJoin,
                    lastSeen.get(),
                    new HashMap<>(attributes)
            );
        }
    }

    public PlayerDatabase(File dataFolder, String fileName, Logger logger) {
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            logger.warning("Could not create plugin data folder: " + dataFolder.getAbsolutePath());
        }
        this.dbFile = new File(dataFolder, fileName);
        this.logger = logger;
        this.jdbcUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
    }

    public synchronized void initialize() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
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
                    blocks_broken INTEGER NOT NULL DEFAULT 0,
                    first_join INTEGER NOT NULL,
                    last_seen INTEGER NOT NULL
                );
                """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_players_blocks_broken
                ON players(blocks_broken DESC, last_seen DESC);
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
        }

        refreshTopTenSnapshot(10);
        logger.info("Initialized ShyCubes SQLite player database at " + dbFile.getAbsolutePath());
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    /**
     * Loads or creates a player record on join. Safe to call asynchronously.
     */
    public synchronized PlayerEntry recordPlayerJoin(UUID uuid, String username, boolean isBedrock, String xuid) {
        long now = System.currentTimeMillis();
        CachedPlayer existing = cache.get(uuid);
        if (existing != null) {
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
            long blocksBroken = 0L;
            long firstJoin = now;
            String storedXuid = xuid;

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT blocks_broken, first_join, xuid FROM players WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        blocksBroken = rs.getLong("blocks_broken");
                        firstJoin = rs.getLong("first_join");
                        if (storedXuid == null) {
                            storedXuid = rs.getString("xuid");
                        }
                    }
                }
            }

            CachedPlayer loaded = new CachedPlayer(uuid, username, isBedrock, storedXuid, blocksBroken, firstJoin, now);

            try (PreparedStatement attrPs = conn.prepareStatement(
                    "SELECT attr_key, attr_value FROM player_attributes WHERE uuid = ?")) {
                attrPs.setString(1, uuid.toString());
                try (ResultSet rs = attrPs.executeQuery()) {
                    while (rs.next()) {
                        loaded.attributes.put(rs.getString("attr_key"), rs.getString("attr_value"));
                    }
                }
            }

            loaded.dirty.set(true);
            cache.put(uuid, loaded);
            leaderboardDirty.set(true);
            flushDirty();
            return loaded.toEntry();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to load player " + username + " (" + uuid + ") from SQLite", e);
            CachedPlayer fallback = new CachedPlayer(uuid, username, isBedrock, xuid, 0L, now, now);
            fallback.dirty.set(true);
            cache.put(uuid, fallback);
            return fallback.toEntry();
        }
    }

    /**
     * Increments blocks_broken in memory with zero main-thread blocking.
     */
    public long incrementBlocksBroken(UUID uuid, String username, boolean isBedrock, String xuid) {
        long now = System.currentTimeMillis();
        CachedPlayer player = cache.computeIfAbsent(uuid,
                id -> new CachedPlayer(id, username, isBedrock, xuid, 0L, now, now));
        player.username = username;
        player.bedrock = isBedrock;
        if (xuid != null) {
            player.xuid = xuid;
        }
        player.lastSeen.set(now);
        long newTotal = player.blocksBroken.incrementAndGet();
        player.dirty.set(true);
        leaderboardDirty.set(true);
        return newTotal;
    }

    /**
     * Sets a custom attribute for a player in memory and marks for SQLite persistence.
     */
    public void setAttribute(UUID uuid, String username, boolean isBedrock, String key, String value) {
        long now = System.currentTimeMillis();
        CachedPlayer player = cache.computeIfAbsent(uuid,
                id -> new CachedPlayer(id, username, isBedrock, null, 0L, now, now));
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

    public long getBlocksBroken(UUID uuid) {
        CachedPlayer player = cache.get(uuid);
        return player != null ? player.blocksBroken.get() : 0L;
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

    public boolean isLeaderboardDirty() {
        return leaderboardDirty.get();
    }

    /**
     * Returns the latest cached Top 10 snapshot immediately (safe to call on the main server thread).
     */
    public List<PlayerEntry> getCachedTopTen() {
        return cachedTopTen;
    }

    /**
     * Flushes all dirty players to SQLite and refreshes the cached Top 10 leaderboard snapshot if needed.
     */
    public synchronized boolean flushAndRefreshIfDirty(int limit) {
        boolean flushedAny = flushDirty();
        if (flushedAny || leaderboardDirty.getAndSet(false)) {
            refreshTopTenSnapshot(limit);
            return true;
        }
        return false;
    }

    /**
     * Flushes all dirty cached players to SQLite in a single transaction.
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

        String upsertPlayerSql = """
            INSERT INTO players (uuid, username, is_bedrock, xuid, blocks_broken, first_join, last_seen)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                username = excluded.username,
                is_bedrock = excluded.is_bedrock,
                xuid = COALESCE(excluded.xuid, players.xuid),
                blocks_broken = MAX(players.blocks_broken, excluded.blocks_broken),
                last_seen = excluded.last_seen;
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
                 PreparedStatement psAttr = conn.prepareStatement(upsertAttrSql)) {
                for (CachedPlayer cp : toFlush) {
                    psPlayer.setString(1, cp.uuid.toString());
                    psPlayer.setString(2, cp.username);
                    psPlayer.setInt(3, cp.bedrock ? 1 : 0);
                    psPlayer.setString(4, cp.xuid);
                    psPlayer.setLong(5, cp.blocksBroken.get());
                    psPlayer.setLong(6, cp.firstJoin);
                    psPlayer.setLong(7, cp.lastSeen.get());
                    psPlayer.addBatch();

                    for (Map.Entry<String, String> attr : cp.attributes.entrySet()) {
                        psAttr.setString(1, cp.uuid.toString());
                        psAttr.setString(2, attr.getKey());
                        psAttr.setString(3, attr.getValue());
                        psAttr.setLong(4, now);
                        psAttr.addBatch();
                    }
                }
                psPlayer.executeBatch();
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
            logger.log(Level.WARNING, "Failed to flush player stats to SQLite", e);
            return false;
        }
    }

    /**
     * Queries SQLite for the top N block breakers and updates the volatile snapshot.
     */
    public synchronized List<PlayerEntry> refreshTopTenSnapshot(int limit) {
        flushDirty();
        List<PlayerEntry> results = new ArrayList<>(limit);
        String sql = """
            SELECT uuid, username, is_bedrock, xuid, blocks_broken, first_join, last_seen
            FROM players
            ORDER BY blocks_broken DESC, last_seen DESC
            LIMIT ?;
            """;
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    CachedPlayer inMem = cache.get(uuid);
                    long blocks = rs.getLong("blocks_broken");
                    if (inMem != null) {
                        blocks = Math.max(blocks, inMem.blocksBroken.get());
                    }
                    results.add(new PlayerEntry(
                            uuid,
                            rs.getString("username"),
                            rs.getInt("is_bedrock") == 1,
                            rs.getString("xuid"),
                            blocks,
                            rs.getLong("first_join"),
                            rs.getLong("last_seen"),
                            inMem != null ? new HashMap<>(inMem.attributes) : Collections.emptyMap()
                    ));
                }
            }
            this.cachedTopTen = Collections.unmodifiableList(results);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to query top blocks broken from SQLite", e);
        }
        return this.cachedTopTen;
    }
}
