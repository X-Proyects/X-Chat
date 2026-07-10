package com.fabian.xchat.utils;

import com.fabian.xchat.XChat;
import com.fabian.xchat.managers.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

/**
 * DebugLogger - Static utility for debug logging with two modes:
 * <p>
 * - Config debug (debug: true in config.yml): messages go to CONSOLE only.
 * - Command debug (/xchat debug): messages go to the PLAYER who toggled it.
 * <p>
 * When both are active, only the player receives command-triggered debug messages.
 * Config debug always outputs to console independently.
 */
public final class DebugLogger {

    private static final String PLUGIN_NAME = "X-Chat";
    private static final String PREFIX = "&8[&bDEBUG&8] &f[" + PLUGIN_NAME + "&f]&r &7";

    private DebugLogger() {
        // Static utility class — no instances
    }

    /**
     * Checks if debug mode is active (either via config or via command).
     */
    private static boolean isDebugEnabled() {
        XChat plugin = XChat.getInstance();
        if (plugin == null) return false;
        ConfigManager configManager = plugin.getConfigManager();
        if (configManager == null) return false;
        try {
            boolean configDebug = plugin.getConfig().getBoolean("debug", false);
            return configDebug || configManager.debugPlayer != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Checks if config-based debug is active (console output).
     */
    private static boolean isConfigDebug() {
        XChat plugin = XChat.getInstance();
        if (plugin == null) return false;
        try {
            return plugin.getConfig().getBoolean("debug", false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Gets the player who enabled debug via command, or null.
     */
    private static Player getDebugPlayer() {
        XChat plugin = XChat.getInstance();
        if (plugin == null) return null;
        ConfigManager configManager = plugin.getConfigManager();
        if (configManager == null || configManager.debugPlayer == null) return null;
        return Bukkit.getPlayer(configManager.debugPlayer);
    }

    /**
     * Logs a debug message with the standard debug prefix.
     */
    public static void debug(String message) {
        if (!isDebugEnabled()) return;
        send(message);
    }

    /**
     * Logs a debug message with a category prefix.
     * Output format: [DEBUG] [Category] message
     */
    public static void debug(String category, String message) {
        if (!isDebugEnabled()) return;
        send("[" + category + "] " + message);
    }

    /**
     * Logs a debug message with a category prefix and includes a stack trace.
     */
    public static void debug(String category, String message, Throwable throwable) {
        if (!isDebugEnabled()) return;
        send("[" + category + "] " + message);
        if (throwable != null) {
            throwable.printStackTrace();
        }
    }

    /**
     * Routes the message to the appropriate recipient:
     * - If a player enabled debug via command -> send to that player
     * - If debug is enabled via config -> send to console
     * - If both -> player gets it (config debug still goes to console independently via isConfigDebug)
     */
    private static void send(String message) {
        XChat plugin = XChat.getInstance();
        if (plugin == null) return;
        ConfigManager configManager = plugin.getConfigManager();
        if (configManager == null) return;

        String formatted = ChatColor.translateAlternateColorCodes('&', PREFIX + message);

        // Player debug via command
        if (configManager.debugPlayer != null) {
            Player debugPlayer = Bukkit.getPlayer(configManager.debugPlayer);
            if (debugPlayer != null && debugPlayer.isOnline()) {
                debugPlayer.sendMessage(formatted);
                return;
            } else {
                // Player went offline, clean up
                configManager.debugPlayer = null;
            }
        }

        // Config debug -> console only
        if (isConfigDebug()) {
            Bukkit.getConsoleSender().sendMessage(formatted);
        }
    }
}