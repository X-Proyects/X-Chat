package com.fabian.xchat.storage;

import com.fabian.xchat.XChat;
import com.fabian.xchat.utils.DebugLogger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed storage provider.
 * Uses Redis hashes and sets for cross-server data sharing.
 *
 * Data layout in Redis:
 *   xchat:ignored:{uuid}       -> Set of ignored UUIDs
 *   xchat:spy:{uuid}           -> "1" or not set
 *   xchat:reply:{uuid}        -> target UUID string
 */
public class RedisStorageProvider implements StorageProvider {

    private final XChat plugin;
    private final String host;
    private final int port;
    private final String password;
    private final int database;
    private JedisPool jedisPool;
    private final String prefix;

    public RedisStorageProvider(XChat plugin, String host, int port, String password, int database) {
        this.plugin = plugin;
        this.host = host;
        this.port = port;
        this.password = password;
        this.database = database;
        this.prefix = "xchat:";
    }

    @Override
    public void init() {
        DebugLogger.debug("RedisStorage", "Connecting to Redis at " + host + ":" + port + " db=" + database);
        try {
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(8);
            poolConfig.setMaxIdle(4);
            poolConfig.setMinIdle(1);
            if (password != null && !password.isEmpty()) {
                jedisPool = new JedisPool(poolConfig, host, port, 5000, password, database);
            } else {
                jedisPool = new JedisPool(poolConfig, host, port, 5000, null, database);
            }

            // Test connection
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.ping();
            }

            DebugLogger.debug("RedisStorage", "Connected to Redis successfully");
        } catch (Exception e) {
            plugin.logError("Failed to connect to Redis: " + e.getMessage());
            DebugLogger.debug("RedisStorage", "Redis connection failed", e);
            jedisPool = null;
        }
    }

    @Override
    public void shutdown() {
        if (jedisPool != null) {
            jedisPool.close();
            DebugLogger.debug("RedisStorage", "Redis pool closed");
        }
    }

    public JedisPool getJedisPool() {
        return jedisPool;
    }

    private String key(String... parts) {
        StringBuilder sb = new StringBuilder(prefix);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(":");
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    // ── Ignore List ──

    @Override
    public Set<UUID> getIgnoredPlayers(UUID player) {
        if (jedisPool == null) return Collections.emptySet();
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> members = jedis.smembers(key("ignored", player.toString()));
            Set<UUID> result = ConcurrentHashMap.newKeySet();
            for (String m : members) {
                try { result.add(UUID.fromString(m)); } catch (IllegalArgumentException ignored) {}
            }
            return result;
        }
    }

    @Override
    public boolean isIgnoring(UUID player, UUID target) {
        if (jedisPool == null) return false;
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.sismember(key("ignored", player.toString()), target.toString());
        }
    }

    @Override
    public boolean toggleIgnore(UUID player, UUID target) {
        if (jedisPool == null) return false;
        try (Jedis jedis = jedisPool.getResource()) {
            String k = key("ignored", player.toString());
            boolean wasMember = jedis.sismember(k, target.toString());
            if (wasMember) {
                jedis.srem(k, target.toString());
                return false;
            } else {
                jedis.sadd(k, target.toString());
                return true;
            }
        }
    }

    // ── Social Spy ──

    @Override
    public boolean isSpyEnabled(UUID player) {
        if (jedisPool == null) return false;
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.exists(key("spy", player.toString()));
        }
    }

    @Override
    public void setSpyEnabled(UUID player, boolean enabled) {
        if (jedisPool == null) return;
        try (Jedis jedis = jedisPool.getResource()) {
            String k = key("spy", player.toString());
            if (enabled) {
                jedis.setex(k, 86400, "1"); // 24h TTL as safety net
            } else {
                jedis.del(k);
            }
        }
    }

    // ── Reply Target ──

    @Override
    public UUID getLastMessageTarget(UUID player) {
        if (jedisPool == null) return null;
        try (Jedis jedis = jedisPool.getResource()) {
            String val = jedis.get(key("reply", player.toString()));
            if (val != null) {
                try { return UUID.fromString(val); } catch (IllegalArgumentException ignored) {}
            }
            return null;
        }
    }

    @Override
    public void setLastMessageTarget(UUID player, UUID target) {
        if (jedisPool == null) return;
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.setex(key("reply", player.toString()), 86400, target.toString()); // 24h TTL
        }
    }

    @Override
    public String getName() {
        return "Redis";
    }
}