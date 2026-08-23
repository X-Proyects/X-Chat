package com.fabian.xchat.storage;

import com.fabian.xchat.XChat;
import com.fabian.xchat.utils.DebugLogger;
import com.fabian.xchat.utils.SchedulerUtil;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis-backed storage provider with automatic failover.
 * Uses Redis hashes and sets for cross-server data sharing.
 *
 * Data layout in Redis:
 *   xchat:ignored:{uuid}       -> Set of ignored UUIDs
 *   xchat:spy:{uuid}           -> "1" or not set
 *   xchat:reply:{uuid}        -> target UUID string
 *
 * FAILOVER BEHAVIOR (same as MySQLStorageProvider):
 *   - The connection test runs in the BACKGROUND, so a down Redis server
 *     can never freeze server startup.
 *   - After N consecutive failures (cross-server.failover.max-failures) the
 *     provider enters local-only mode: methods return safe defaults instantly
 *     and the plugin keeps working — only cross-server data is paused.
 *   - A background task retries every cross-server.failover.retry-interval
 *     seconds and re-enables everything automatically when Redis comes back.
 */
public class RedisStorageProvider implements StorageProvider {

    private final XChat plugin;
    private final String host;
    private final int port;
    private final String password;
    private final int database;
    private JedisPool jedisPool;
    private final String prefix;

    // ── Failover / circuit breaker state ──
    private final int maxFailures;
    private final int retryIntervalSeconds;
    private final java.util.concurrent.atomic.AtomicInteger failureCount = new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile boolean degraded = false;
    private volatile boolean announcedDegraded = false;
    private SchedulerUtil.TaskWrapper reconnectTask;

    public RedisStorageProvider(XChat plugin, String host, int port, String password, int database) {
        this.plugin = plugin;
        this.host = host;
        this.port = port;
        this.password = password;
        this.database = database;
        this.prefix = "xchat:";

        // Failover settings (with sane defaults if the section is missing)
        this.maxFailures = plugin.getConfig().getInt("cross-server.failover.max-failures", 3);
        this.retryIntervalSeconds = plugin.getConfig().getInt("cross-server.failover.retry-interval", 15);
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
        } catch (Exception e) {
            plugin.logError("Failed to create Redis pool: " + e.getMessage());
            DebugLogger.debug("RedisStorage", "Redis pool creation failed", e);
            jedisPool = null;
            return;
        }

        // Connection test runs in the BACKGROUND — a down Redis server
        // can never freeze server startup.
        SchedulerUtil.runAsync(plugin, () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.ping();
                DebugLogger.debug("RedisStorage", "Connected to Redis successfully");
            } catch (Exception e) {
                registerFailure(e);
            }
        });
    }

    @Override
    public void shutdown() {
        if (reconnectTask != null) {
            reconnectTask.cancel();
            reconnectTask = null;
        }
        if (jedisPool != null) {
            jedisPool.close();
            DebugLogger.debug("RedisStorage", "Redis pool closed");
        }
    }

    public JedisPool getJedisPool() {
        return jedisPool;
    }

    // ── Failover internals ──

    private void registerFailure(Exception e) {
        if (degraded) return; // already local-only — the reconnect task owns recovery
        int failures = failureCount.incrementAndGet();
        DebugLogger.debug("RedisStorage", "Connection failure " + failures + "/" + maxFailures, e);
        if (maxFailures > 0 && failures >= maxFailures) {
            degraded = true;
            if (!announcedDegraded) {
                announcedDegraded = true;
                plugin.logWarning("Redis connection failed " + failures + " times in a row. "
                        + "X-Chat will keep working locally (chat, tags, formats, PMs), but cross-server "
                        + "data (spy/ignore/reply sync) is paused until Redis comes back. "
                        + "Retrying every " + retryIntervalSeconds + "s in the background.");
            }
            startReconnectTask();
        }
    }

    private void resetFailures() {
        failureCount.set(0);
        if (degraded) {
            degraded = false;
            announcedDegraded = false;
            plugin.logInfo("[X-Chat] Redis connection restored — cross-server features re-enabled.");
        }
    }

    private void startReconnectTask() {
        if (reconnectTask != null) return; // already scheduled
        long periodTicks = Math.max(1, retryIntervalSeconds) * 20L;
        reconnectTask = SchedulerUtil.runAsyncTimer(plugin, this::tryReconnect, periodTicks, periodTicks);
        DebugLogger.debug("RedisStorage", "Background reconnect task started (every " + retryIntervalSeconds + "s)");
    }

    private void tryReconnect() {
        if (!degraded || jedisPool == null) return;
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.ping();
            resetFailures();
            DebugLogger.debug("RedisStorage", "Reconnect successful — leaving local-only mode");
        } catch (Exception e) {
            // Still down — stay silent in console (debug only) to avoid spam
            DebugLogger.debug("RedisStorage", "Reconnect attempt failed (will keep retrying)", e);
        }
    }

    /** True when the provider is in local-only mode (Redis unreachable). */
    public boolean isDegraded() {
        return degraded;
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
        if (jedisPool == null || degraded) return Collections.emptySet();
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> members = jedis.smembers(key("ignored", player.toString()));
            Set<UUID> result = ConcurrentHashMap.newKeySet();
            for (String m : members) {
                try { result.add(UUID.fromString(m)); } catch (IllegalArgumentException ignored) {}
            }
            resetFailures();
            return result;
        } catch (Exception e) {
            registerFailure(e);
            return Collections.emptySet();
        }
    }

    @Override
    public boolean isIgnoring(UUID player, UUID target) {
        if (jedisPool == null || degraded) return false;
        try (Jedis jedis = jedisPool.getResource()) {
            boolean member = jedis.sismember(key("ignored", player.toString()), target.toString());
            resetFailures();
            return member;
        } catch (Exception e) {
            registerFailure(e);
            return false;
        }
    }

    @Override
    public boolean toggleIgnore(UUID player, UUID target) {
        if (jedisPool == null || degraded) return false;
        try (Jedis jedis = jedisPool.getResource()) {
            String k = key("ignored", player.toString());
            boolean wasMember = jedis.sismember(k, target.toString());
            if (wasMember) {
                jedis.srem(k, target.toString());
                resetFailures();
                return false;
            } else {
                jedis.sadd(k, target.toString());
                resetFailures();
                return true;
            }
        } catch (Exception e) {
            registerFailure(e);
            return false;
        }
    }

    // ── Social Spy ──

    @Override
    public boolean isSpyEnabled(UUID player) {
        if (jedisPool == null || degraded) return false;
        try (Jedis jedis = jedisPool.getResource()) {
            boolean exists = jedis.exists(key("spy", player.toString()));
            resetFailures();
            return exists;
        } catch (Exception e) {
            registerFailure(e);
            return false;
        }
    }

    @Override
    public void setSpyEnabled(UUID player, boolean enabled) {
        if (jedisPool == null || degraded) return;
        try (Jedis jedis = jedisPool.getResource()) {
            String k = key("spy", player.toString());
            if (enabled) {
                jedis.setex(k, 86400, "1"); // 24h TTL as safety net
            } else {
                jedis.del(k);
            }
            resetFailures();
        } catch (Exception e) {
            registerFailure(e);
        }
    }

    // ── Reply Target ──

    @Override
    public UUID getLastMessageTarget(UUID player) {
        if (jedisPool == null || degraded) return null;
        try (Jedis jedis = jedisPool.getResource()) {
            String val = jedis.get(key("reply", player.toString()));
            if (val != null) {
                try {
                    UUID target = UUID.fromString(val);
                    resetFailures();
                    return target;
                } catch (IllegalArgumentException ignored) {}
            }
            resetFailures();
            return null;
        } catch (Exception e) {
            registerFailure(e);
            return null;
        }
    }

    @Override
    public void setLastMessageTarget(UUID player, UUID target) {
        if (jedisPool == null || degraded) return;
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.setex(key("reply", player.toString()), 86400, target.toString()); // 24h TTL
            resetFailures();
        } catch (Exception e) {
            registerFailure(e);
        }
    }

    @Override
    public String getName() {
        return "Redis";
    }
}
