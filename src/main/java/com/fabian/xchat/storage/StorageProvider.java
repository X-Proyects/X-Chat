package com.fabian.xchat.storage;

import java.util.Set;
import java.util.UUID;

/**
 * Abstraction for persistent storage of player data.
 * Implementations: YamlStorageProvider (default), RedisStorageProvider, MySQLStorageProvider.
 */
public interface StorageProvider {

    void init();

    void shutdown();

    // ── Ignore List ──

    Set<UUID> getIgnoredPlayers(UUID player);

    boolean isIgnoring(UUID player, UUID target);

    boolean toggleIgnore(UUID player, UUID target);

    // ── Social Spy State ──

    boolean isSpyEnabled(UUID player);

    void setSpyEnabled(UUID player, boolean enabled);

    // ── Reply Target (last PM) ──

    UUID getLastMessageTarget(UUID player);

    void setLastMessageTarget(UUID player, UUID target);

    String getName();

    // ── Chat Log (optional, default no-op) ──

    /**
     * Saves a chat message to the database for cross-server chat logging.
     * Default implementation does nothing (YAML/Redis don't support this).
     */
    default void saveChatLog(java.util.UUID playerUuid, String playerName, String serverName, String message) {
        // no-op by default
    }
}