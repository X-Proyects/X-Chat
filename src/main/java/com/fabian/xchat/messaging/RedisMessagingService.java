package com.fabian.xchat.messaging;

import com.fabian.xchat.XChat;
import com.fabian.xchat.storage.RedisStorageProvider;
import com.fabian.xchat.utils.DebugLogger;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Redis pub/sub messaging service for cross-server communication.
 *
 * Channels used:
 *   xchat:chat       — public chat messages
 *   xchat:pm         — private messages
 *   xchat:spy        — social spy messages
 *   xchat:broadcast  — cross-server broadcasts
 *   xchat:join       — join messages
 *   xchat:quit       — quit messages
 *   xchat:playerlist — cross-server player list sync
 *
 * Message format (pipe-delimited):
 *   serverName|senderUUID|senderName|legacyFormattedMessage
 */
public class RedisMessagingService implements MessagingService {

    private final XChat plugin;
    private final RedisStorageProvider redisStorage;
    private final String serverName;
    private final String channelPrefix;

    private Thread subscriberThread;
    private volatile boolean running = false;
    private MessageHandler messageHandler;
    private final ConcurrentLinkedQueue<String[]> inboundQueue = new ConcurrentLinkedQueue<>();

    // Reference to the active JedisPubSub instance so we can call unsubscribe()
    // to cleanly break the blocking jedis.subscribe() call during shutdown.
    // Volatile because it's set by the subscriber thread and read by the shutdown thread.
    private volatile JedisPubSub activePubSub;

    // Reference to the Bukkit timer task that drains the inbound queue.
    // Must be cancelled on shutdown to prevent processing stale messages after reload.
    private volatile BukkitTask inboundProcessorTask;

    public RedisMessagingService(XChat plugin, RedisStorageProvider redisStorage, String serverName) {
        this.plugin = plugin;
        this.redisStorage = redisStorage;
        this.serverName = serverName;
        this.channelPrefix = "xchat:";
    }

    @Override
    public void init() {
        if (redisStorage.getJedisPool() == null) {
            DebugLogger.debug("RedisMessaging", "Redis pool is null, messaging disabled");
            return;
        }

        running = true;
        subscriberThread = new Thread(this::subscriberLoop, "XChat-Redis-Subscriber");
        subscriberThread.setDaemon(true);
        subscriberThread.start();

        // Process inbound messages on the main server thread
        inboundProcessorTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            String[] msg;
            while ((msg = inboundQueue.poll()) != null) {
                try {
                    if (messageHandler != null) {
                        messageHandler.onMessage(msg[0], msg[1], msg[2], msg[3], msg[4]);
                    }
                } catch (Throwable t) {
                    // Catch Throwable (not just Exception) to also catch NoClassDefFoundError etc.
                    DebugLogger.debug("RedisMessaging", "Error processing inbound message", t);
                }
            }
        }, 1L, 1L);

        DebugLogger.debug("RedisMessaging", "Redis pub/sub subscriber started (server=" + serverName + ")");
    }

    private void subscriberLoop() {
        while (running) {
            try (Jedis jedis = redisStorage.getJedisPool().getResource()) {
                JedisPubSub pubSub = new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        // channel: xchat:chat, xchat:pm, etc.
                        String type = channel.substring(channelPrefix.length());
                        // Payload: serverName|senderUUID|senderName|message
                        String[] parts = message.split("\\|", 4);
                        if (parts.length == 4) {
                            String originServer = parts[0];
                            // Ignore messages from this server
                            if (originServer.equals(serverName)) return;
                            inboundQueue.offer(new String[]{type, parts[2], parts[1], parts[0], parts[3]});
                            //                                          ^name  ^uuid  ^server ^message
                        }
                    }

                    @Override
                    public void onPong(String pattern) {
                        // Keepalive
                    }
                };
                activePubSub = pubSub;
                jedis.subscribe(pubSub, channelPrefix + "chat", channelPrefix + "pm", channelPrefix + "spy",
                     channelPrefix + "broadcast", channelPrefix + "join", channelPrefix + "quit",
                     channelPrefix + "playerlist");
            } catch (Exception e) {
                if (!running) break;
                DebugLogger.debug("RedisMessaging", "Subscriber disconnected, reconnecting in 5s...", e);
                try { Thread.sleep(5000); } catch (InterruptedException ignored) { break; }
            }
        }
        DebugLogger.debug("RedisMessaging", "Subscriber loop exited");
    }

    @Override
    public void shutdown() {
        running = false;

        // 1. Unsubscribe the JedisPubSub to break the blocking jedis.subscribe() call.
        //    This is the ONLY reliable way to unblock the subscriber thread —
        //    thread.interrupt() does NOT work on jedis.subscribe().
        JedisPubSub pubSub = activePubSub;
        if (pubSub != null) {
            try {
                pubSub.unsubscribe();
            } catch (Exception e) {
                DebugLogger.debug("RedisMessaging", "Error unsubscribing pub/sub", e);
            }
            activePubSub = null;
        }

        // 2. Wait for the subscriber thread to actually finish (max 5 seconds).
        //    This ensures no duplicate subscribers exist when a new one starts on reload.
        if (subscriberThread != null) {
            try {
                subscriberThread.join(5000);
                if (subscriberThread.isAlive()) {
                    DebugLogger.debug("RedisMessaging", "Subscriber thread did not exit within 5s");
                }
            } catch (InterruptedException ignored) {}
            subscriberThread = null;
        }

        // 3. Cancel the inbound queue processor task so stale messages aren't
        //    processed after this service is replaced by a new one on reload.
        if (inboundProcessorTask != null) {
            try {
                inboundProcessorTask.cancel();
            } catch (Exception ignored) {}
            inboundProcessorTask = null;
        }

        // 4. Clear any remaining messages in the inbound queue.
        inboundQueue.clear();

        DebugLogger.debug("RedisMessaging", "Redis pub/sub subscriber stopped");
    }

    @Override
    public void publish(String channel, String senderName, String senderUUID, String serverName, String message) {
        if (!running || redisStorage.getJedisPool() == null) return;
        // Use CompletableFuture instead of Bukkit scheduler to avoid IllegalPluginAccessException
        // when the plugin is disabling or the scheduler is shutting down.
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try (Jedis jedis = redisStorage.getJedisPool().getResource()) {
                // Payload format: serverName|senderUUID|senderName|message
                // (channel is the Redis pub/sub channel name, not in the payload)
                String payload = serverName + "|" + senderUUID + "|" + senderName + "|" + message;
                jedis.publish(channelPrefix + channel, payload);
            } catch (Exception e) {
                DebugLogger.debug("RedisMessaging", "Failed to publish to " + channel, e);
            }
        });
    }

    /**
     * Publishes a message synchronously on the calling thread.
     * Safe to call during plugin disable (no scheduler involved).
     */
    public void publishSync(String channel, String senderName, String senderUUID, String serverName, String message) {
        if (redisStorage.getJedisPool() == null) return;
        try (Jedis jedis = redisStorage.getJedisPool().getResource()) {
            String payload = serverName + "|" + senderUUID + "|" + senderName + "|" + message;
            jedis.publish(channelPrefix + channel, payload);
        } catch (Exception e) {
            DebugLogger.debug("RedisMessaging", "Failed to publish (sync) to " + channel, e);
        }
    }

    @Override
    public void onMessage(MessageHandler handler) {
        this.messageHandler = handler;
    }

    @Override
    public boolean isEnabled() {
        return running && redisStorage.getJedisPool() != null;
    }

    public String getServerName() {
        return serverName;
    }
}
