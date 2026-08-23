package com.fabian.xchat.storage;

import com.fabian.xchat.XChat;
import com.fabian.xchat.utils.DebugLogger;
import com.fabian.xchat.utils.SchedulerUtil;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MySQL-backed storage provider with automatic failover.
 * Stores ignored players, spy state, and reply targets in MySQL tables
 * for cross-server persistence and sharing.
 *
 * FAILOVER BEHAVIOR:
 *   - The first connection + table creation runs in the BACKGROUND, so a down
 *     or unreachable MySQL server can never freeze server startup.
 *   - After N consecutive connection failures (cross-server.failover.max-failures,
 *     default 3) the provider enters "local-only" (degraded) mode: every method
 *     returns safe defaults instantly and the plugin keeps working normally
 *     (chat, tags, formats, PMs, mentions) — only cross-server persistence
 *     (spy/ignore/reply sync, chat log) is paused.
 *   - While degraded, a background task retries the connection every
 *     cross-server.failover.retry-interval seconds (default 15). When the
 *     database comes back, everything re-enables automatically.
 */
public class MySQLStorageProvider implements StorageProvider {

    private final XChat plugin;
    private HikariDataSource dataSource;

    // ── Failover / circuit breaker state ──
    private final int maxFailures;           // consecutive failures before local-only mode (0 = never)
    private final int retryIntervalSeconds;  // background reconnection interval
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private volatile boolean degraded = false;
    private volatile boolean announcedDegraded = false; // warn only once per outage
    private volatile boolean tablesCreated = false;
    private SchedulerUtil.TaskWrapper reconnectTask;

    public MySQLStorageProvider(XChat plugin, String host, int port, String database,
                               String username, String password) {
        this.plugin = plugin;
        HikariConfig config = new HikariConfig();
        // MySQL 8+ uses caching_sha2_password by default. When connecting without SSL,
        // the JDBC driver must retrieve the server's public key to encrypt the password.
        // allowPublicKeyRetrieval=true is required for this (otherwise: "Public Key Retrieval is not allowed").
        // Also added useUnicode+characterEncoding for proper UTF-8 (utf8mb4) support.
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&autoReconnect=true"
                + "&useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC");
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        // Increased from 5000ms to 30000ms — remote MySQL servers (especially over
        // network or with high latency) can take 5-10s to respond. The old 5s timeout
        // caused "No operations allowed after connection closed" because Hikari would
        // time out and close the connection mid-handshake, then fail when trying to
        // detect transaction isolation level.
        config.setConnectionTimeout(30000);
        // Max lifetime: 30 minutes (MySQL's default wait_timeout is 8 hours, but
        // network-level firewalls often kill idle connections at 30-60 min).
        config.setMaxLifetime(1800000);
        // Idle timeout: 10 minutes
        config.setIdleTimeout(600000);
        // Keepalive time: 5 minutes (Hikari 5.1+ sends a keepalive query to prevent
        // connections from being killed by firewalls/network devices).
        config.setKeepaliveTime(300000);
        // Connection test query — used as fallback if JDBC4 isValid() fails
        config.setConnectionTestQuery("SELECT 1");
        // Leak detection: logs a warning if a connection is held >60s
        config.setLeakDetectionThreshold(60000);
        // Don't fail fast at startup — let the pool initialize lazily so that
        // /xch reload doesn't throw if MySQL is momentarily unreachable.
        config.setInitializationFailTimeout(-1);
        config.setPoolName("XChat-MySQL");
        this.dataSource = new HikariDataSource(config);

        // Failover settings (with sane defaults if the section is missing)
        this.maxFailures = plugin.getConfig().getInt("cross-server.failover.max-failures", 3);
        this.retryIntervalSeconds = plugin.getConfig().getInt("cross-server.failover.retry-interval", 15);
    }

    @Override
    public void init() {
        DebugLogger.debug("MySQLStorage", "Initializing (background first connect)...");
        // The first connection attempt and table creation run in the BACKGROUND.
        // A down/unreachable MySQL server can never freeze server startup —
        // the plugin simply starts in local-only mode and retries later.
        SchedulerUtil.runAsync(plugin, () -> {
            try (Connection conn = getConnection()) {
                DebugLogger.debug("MySQLStorage", "Connected to MySQL and tables verified");
            } catch (SQLException e) {
                registerFailure(e);
            }
        });
    }

    private void createTables(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS xchat_ignored (" +
                "  player_uuid VARCHAR(36) NOT NULL," +
                "  ignored_uuid VARCHAR(36) NOT NULL," +
                "  PRIMARY KEY (player_uuid, ignored_uuid)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS xchat_spy (" +
                "  player_uuid VARCHAR(36) NOT NULL PRIMARY KEY," +
                "  enabled TINYINT(1) NOT NULL DEFAULT 1" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS xchat_reply (" +
                "  player_uuid VARCHAR(36) NOT NULL PRIMARY KEY," +
                "  target_uuid VARCHAR(36) NOT NULL," +
                "  updated_at BIGINT NOT NULL" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS xchat_chatlog (" +
                "  id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "  player_uuid VARCHAR(36) NOT NULL," +
                "  player_name VARCHAR(16) NOT NULL," +
                "  server_name VARCHAR(64) NOT NULL," +
                "  message TEXT NOT NULL," +
                "  timestamp BIGINT NOT NULL," +
                "  INDEX idx_player (player_uuid)," +
                "  INDEX idx_timestamp (timestamp)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );
        }
    }

    @Override
    public void shutdown() {
        // Stop the background reconnect task first
        if (reconnectTask != null) {
            reconnectTask.cancel();
            reconnectTask = null;
        }
        if (dataSource != null) {
            dataSource.close();
            DebugLogger.debug("MySQLStorage", "MySQL pool closed");
        }
    }

    // ── Failover internals ──

    /**
     * Returns a pooled connection, creating the tables on the first
     * successful connection (idempotent via CREATE TABLE IF NOT EXISTS).
     */
    private Connection getConnection() throws SQLException {
        Connection conn = dataSource.getConnection();
        if (!tablesCreated) ensureTables(conn);
        return conn;
    }

    private synchronized void ensureTables(Connection conn) throws SQLException {
        if (tablesCreated) return;
        createTables(conn);
        tablesCreated = true;
    }

    /** Counts a connection failure; switches to local-only mode after N in a row. */
    private void registerFailure(SQLException e) {
        if (degraded) return; // already local-only — the reconnect task owns recovery
        int failures = failureCount.incrementAndGet();
        DebugLogger.debug("MySQLStorage", "Connection failure " + failures + "/" + maxFailures, e);
        if (maxFailures > 0 && failures >= maxFailures) {
            degraded = true;
            if (!announcedDegraded) {
                announcedDegraded = true;
                plugin.logWarning("MySQL connection failed " + failures + " times in a row. "
                        + "X-Chat will keep working locally (chat, tags, formats, PMs), but cross-server "
                        + "data (spy/ignore/reply sync, chat log) is paused until the database comes back. "
                        + "Retrying every " + retryIntervalSeconds + "s in the background.");
            }
            startReconnectTask();
        }
    }

    /** Resets the failure counter on success; announces recovery if it was degraded. */
    private void resetFailures() {
        failureCount.set(0);
        if (degraded) {
            degraded = false;
            announcedDegraded = false;
            plugin.logInfo("[X-Chat] MySQL connection restored — cross-server features re-enabled.");
        }
    }

    /** Starts (once) the background task that retries the connection while degraded. */
    private void startReconnectTask() {
        if (reconnectTask != null) return; // already scheduled
        long periodTicks = Math.max(1, retryIntervalSeconds) * 20L;
        reconnectTask = SchedulerUtil.runAsyncTimer(plugin, this::tryReconnect, periodTicks, periodTicks);
        DebugLogger.debug("MySQLStorage", "Background reconnect task started (every " + retryIntervalSeconds + "s)");
    }

    private void tryReconnect() {
        if (!degraded || dataSource == null || dataSource.isClosed()) return;
        try (Connection conn = dataSource.getConnection()) {
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT 1")) {
                if (!rs.next()) return; // not a valid connection after all
            }
            if (!tablesCreated) ensureTables(conn);
            resetFailures();
            DebugLogger.debug("MySQLStorage", "Reconnect successful — leaving local-only mode");
        } catch (SQLException e) {
            // Still down — stay silent in console (debug only) to avoid spam
            DebugLogger.debug("MySQLStorage", "Reconnect attempt failed (will keep retrying)", e);
        }
    }

    /** True when the provider is in local-only mode (database unreachable). */
    public boolean isDegraded() {
        return degraded;
    }

    // ── Ignore List ──

    @Override
    public Set<UUID> getIgnoredPlayers(UUID player) {
        if (degraded) return ConcurrentHashMap.newKeySet();
        Set<UUID> result = ConcurrentHashMap.newKeySet();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT ignored_uuid FROM xchat_ignored WHERE player_uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try { result.add(UUID.fromString(rs.getString("ignored_uuid"))); }
                    catch (IllegalArgumentException ignored) {}
                }
            }
            resetFailures();
        } catch (SQLException e) {
            registerFailure(e);
            DebugLogger.debug("MySQLStorage", "Failed to load ignored players for " + player, e);
        }
        return result;
    }

    @Override
    public boolean isIgnoring(UUID player, UUID target) {
        if (degraded) return false;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM xchat_ignored WHERE player_uuid = ? AND ignored_uuid = ?")) {
            ps.setString(1, player.toString());
            ps.setString(2, target.toString());
            try (ResultSet rs = ps.executeQuery()) {
                boolean found = rs.next();
                resetFailures();
                return found;
            }
        } catch (SQLException e) {
            registerFailure(e);
            return false;
        }
    }

    @Override
    public boolean toggleIgnore(UUID player, UUID target) {
        if (degraded) return false;
        try (Connection conn = getConnection()) {
            // Check if exists
            boolean exists;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM xchat_ignored WHERE player_uuid = ? AND ignored_uuid = ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, target.toString());
                exists = ps.executeQuery().next();
            }

            if (exists) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM xchat_ignored WHERE player_uuid = ? AND ignored_uuid = ?")) {
                    ps.setString(1, player.toString());
                    ps.setString(2, target.toString());
                    ps.executeUpdate();
                }
                resetFailures();
                return false;
            } else {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO xchat_ignored (player_uuid, ignored_uuid) VALUES (?, ?)")) {
                    ps.setString(1, player.toString());
                    ps.setString(2, target.toString());
                    ps.executeUpdate();
                }
                resetFailures();
                return true;
            }
        } catch (SQLException e) {
            registerFailure(e);
            DebugLogger.debug("MySQLStorage", "Failed to toggle ignore", e);
            return false;
        }
    }

    // ── Social Spy ──

    @Override
    public boolean isSpyEnabled(UUID player) {
        if (degraded) return false;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT enabled FROM xchat_spy WHERE player_uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                boolean enabled = rs.next() && rs.getBoolean("enabled");
                resetFailures();
                return enabled;
            }
        } catch (SQLException e) {
            registerFailure(e);
            return false;
        }
    }

    @Override
    public void setSpyEnabled(UUID player, boolean enabled) {
        if (degraded) return;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO xchat_spy (player_uuid, enabled) VALUES (?, ?) " +
                     "ON DUPLICATE KEY UPDATE enabled = ?")) {
            ps.setString(1, player.toString());
            ps.setBoolean(2, enabled);
            ps.setBoolean(3, enabled);
            ps.executeUpdate();
            resetFailures();
        } catch (SQLException e) {
            registerFailure(e);
            DebugLogger.debug("MySQLStorage", "Failed to set spy state", e);
        }
    }

    // ── Reply Target ──

    @Override
    public UUID getLastMessageTarget(UUID player) {
        if (degraded) return null;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT target_uuid FROM xchat_reply WHERE player_uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    try {
                        UUID target = UUID.fromString(rs.getString("target_uuid"));
                        resetFailures();
                        return target;
                    }
                    catch (IllegalArgumentException ignored) {}
                }
            }
            resetFailures();
        } catch (SQLException e) {
            registerFailure(e);
        }
        return null;
    }

    @Override
    public void setLastMessageTarget(UUID player, UUID target) {
        if (degraded) return;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO xchat_reply (player_uuid, target_uuid, updated_at) VALUES (?, ?, ?) " +
                     "ON DUPLICATE KEY UPDATE target_uuid = ?, updated_at = ?")) {
            long now = System.currentTimeMillis();
            ps.setString(1, player.toString());
            ps.setString(2, target.toString());
            ps.setLong(3, now);
            ps.setString(4, target.toString());
            ps.setLong(5, now);
            ps.executeUpdate();
            resetFailures();
        } catch (SQLException e) {
            registerFailure(e);
            DebugLogger.debug("MySQLStorage", "Failed to set reply target", e);
        }
    }

    // ── Chat Log ──

    @Override
    public void saveChatLog(UUID playerUuid, String playerName, String serverName, String message) {
        if (degraded) return;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO xchat_chatlog (player_uuid, player_name, server_name, message, timestamp) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, playerName);
            ps.setString(3, serverName);
            ps.setString(4, message);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
            resetFailures();
        } catch (SQLException e) {
            registerFailure(e);
            DebugLogger.debug("MySQLStorage", "Failed to save chat log", e);
        }
    }

    @Override
    public String getName() {
        return "MySQL";
    }
}
