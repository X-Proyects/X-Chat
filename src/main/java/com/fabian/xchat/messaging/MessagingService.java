package com.fabian.xchat.messaging;

/**
 * Abstraction for cross-server messaging.
 * Implementations: RedisMessagingService (pub/sub) or no-op for local-only.
 */
public interface MessagingService {

    void init();

    void shutdown();

    /**
     * Publish a cross-server chat message.
     * @param channel e.g. "chat", "pm", "spy", "broadcast"
     * @param senderName display name of the sender
     * @param senderUUID UUID of the sender
     * @param serverName the server that originated the message
     * @param message the formatted legacy message string
     */
    void publish(String channel, String senderName, String senderUUID, String serverName, String message);

    /**
     * Publish a message synchronously (no scheduler).
     * Safe to call during plugin disable.
     */
    default void publishSync(String channel, String senderName, String senderUUID, String serverName, String message) {
        // Default: fall back to async publish. Implementations can override for true sync.
        publish(channel, senderName, senderUUID, serverName, message);
    }

    /**
     * Register a handler for incoming cross-server messages.
     */
    void onMessage(MessageHandler handler);

    boolean isEnabled();

    @FunctionalInterface
    interface MessageHandler {
        void onMessage(String channel, String senderName, String senderUUID, String serverName, String message);
    }
}