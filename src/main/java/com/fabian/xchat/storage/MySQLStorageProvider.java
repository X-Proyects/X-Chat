package com.fabian.xchat.storage;

import com.fabian.xchat.XChat;
import com.fabian.xchat.utils.DebugLogger;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MySQL-backed storage provider.
 * Stores ignored players, spy state, and reply targets in MySQL tables
 * for cross-server persistence and sharing.
 */
public class MySQLStorageProvider implements StorageProvider {

    private final XChat plugin;
    private HikariDataSource dataSource;

    public MySQLStorageProvider(XChat plugin, String host, int port, String database,
                               String username, String password) {
        this.plugin = plugin;
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&autoReconnect=true&utf8mb4");
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5000);
        config.setPoolName("XChat-MySQL");
        this.dataSource = new HikariDataSource(config);
    }

    @Override
    public void init() {
        DebugLogger.debug("MySQLStorage", "Connecting to MySQL...");
        try (Connection conn = dataSource.getConnection()) {
            createTables(conn);
            DebugLogger.debug("MySQLStorage", "Connected to MySQL and tables verified");
        } catch (SQLException e) {
            plugin.logError("Failed to connect to MySQL: " + e.getMessage());
            DebugLogger.debug("MySQLStorage", "MySQL connection failed", e);
        }
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
        if (dataSource != null) {
            dataSource.close();
            DebugLogger.debug("MySQLStorage", "MySQL pool closed");
        }
    }

    // ── Ignore List ──

    @Override
    public Set<UUID> getIgnoredPlayers(UUID player) {
        Set<UUID> result = ConcurrentHashMap.newKeySet();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT ignored_uuid FROM xchat_ignored WHERE player_uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try { result.add(UUID.fromString(rs.getString("ignored_uuid"))); }
                    catch (IllegalArgumentException ignored) {}
                }
            }
        } catch (SQLException e) {
            DebugLogger.debug("MySQLStorage", "Failed to load ignored players for " + player, e);
        }
        return result;
    }

    @Override
    public boolean isIgnoring(UUID player, UUID target) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM xchat_ignored WHERE player_uuid = ? AND ignored_uuid = ?")) {
            ps.setString(1, player.toString());
            ps.setString(2, target.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public boolean toggleIgnore(UUID player, UUID target) {
        try (Connection conn = dataSource.getConnection()) {
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
                return false;
            } else {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO xchat_ignored (player_uuid, ignored_uuid) VALUES (?, ?)")) {
                    ps.setString(1, player.toString());
                    ps.setString(2, target.toString());
                    ps.executeUpdate();
                }
                return true;
            }
        } catch (SQLException e) {
            DebugLogger.debug("MySQLStorage", "Failed to toggle ignore", e);
            return false;
        }
    }

    // ── Social Spy ──

    @Override
    public boolean isSpyEnabled(UUID player) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT enabled FROM xchat_spy WHERE player_uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getBoolean("enabled");
            }
        } catch (SQLException ignored) {}
        return false;
    }

    @Override
    public void setSpyEnabled(UUID player, boolean enabled) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO xchat_spy (player_uuid, enabled) VALUES (?, ?) " +
                     "ON DUPLICATE KEY UPDATE enabled = ?")) {
            ps.setString(1, player.toString());
            ps.setBoolean(2, enabled);
            ps.setBoolean(3, enabled);
            ps.executeUpdate();
        } catch (SQLException e) {
            DebugLogger.debug("MySQLStorage", "Failed to set spy state", e);
        }
    }

    // ── Reply Target ──

    @Override
    public UUID getLastMessageTarget(UUID player) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT target_uuid FROM xchat_reply WHERE player_uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    try { return UUID.fromString(rs.getString("target_uuid")); }
                    catch (IllegalArgumentException ignored) {}
                }
            }
        } catch (SQLException ignored) {}
        return null;
    }

    @Override
    public void setLastMessageTarget(UUID player, UUID target) {
        try (Connection conn = dataSource.getConnection();
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
        } catch (SQLException e) {
            DebugLogger.debug("MySQLStorage", "Failed to set reply target", e);
        }
    }

    // ── Chat Log ──

    @Override
    public void saveChatLog(UUID playerUuid, String playerName, String serverName, String message) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO xchat_chatlog (player_uuid, player_name, server_name, message, timestamp) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, playerName);
            ps.setString(3, serverName);
            ps.setString(4, message);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            DebugLogger.debug("MySQLStorage", "Failed to save chat log", e);
        }
    }

    @Override
    public String getName() {
        return "MySQL";
    }
}