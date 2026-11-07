package com.fabian.xchat.utils;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * X-Proyects Unified Debug System (canonical implementation).
 * <p>
 * Identical across every X-Proyects plugin — only the package changes.
 * <p>
 * Two modes:
 * <ul>
 *   <li>Config debug ({@code debug: true} in config.yml): messages go to CONSOLE only.</li>
 *   <li>Player debug (via {@link #setDebugPlayer(UUID)}): messages go to that PLAYER.</li>
 * </ul>
 * The owning plugin calls {@link #init(Plugin, String)} on enable and
 * {@link #setDebug(boolean)} whenever its config is (re)loaded.
 */
public final class DebugLogger {

    private static Plugin plugin;
    private static String pluginName = "X-Plugin";
    private static boolean configDebug = false;
    private static volatile UUID debugPlayerId = null;

    private DebugLogger() {
        // Static utility class — no instances
    }

    /**
     * Initializes the debug system. Call once from onEnable().
     *
     * @param owningPlugin the plugin instance
     * @param name         display name, e.g. "X-Chat"
     */
    public static void init(Plugin owningPlugin, String name) {
        plugin = owningPlugin;
        pluginName = name != null ? name : "X-Plugin";
    }

    /**
     * Sets config-based debug (console output). Call on enable and on every config reload.
     */
    public static void setDebug(boolean enabled) {
        configDebug = enabled;
    }

    /**
     * Sets or clears the player that receives debug output (command-toggled debug).
     */
    public static void setDebugPlayer(UUID playerId) {
        debugPlayerId = playerId;
    }

    public static UUID getDebugPlayer() {
        return debugPlayerId;
    }

    /**
     * Checks if debug mode is active (either via config or via command).
     */
    public static boolean isDebugEnabled() {
        return configDebug || debugPlayerId != null;
    }

    /**
     * Checks if config-based debug is active (console output).
     */
    public static boolean isConfigDebug() {
        return configDebug;
    }

    // ──────────────────────────────────────────────────────
    //  LOGGING API
    // ──────────────────────────────────────────────────────

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
            Logger logger = plugin != null ? plugin.getLogger() : Bukkit.getLogger();
            logger.warning("[" + pluginName + "] Debug exception: " + throwable);
            throwable.printStackTrace();
        }
    }

    // ──────────────────────────────────────────────────────
    //  ROUTING
    // ──────────────────────────────────────────────────────

    /**
     * Routes the message to the appropriate recipient:
     * <ul>
     *   <li>If a player enabled debug via command → that player (config debug still logs to console)</li>
     *   <li>If debug is enabled via config → console</li>
     * </ul>
     */
    private static void send(String message) {
        String prefix = "&8[&bDEBUG&8] &f[" + pluginName + "&f]&r &7";
        String formatted = ChatColor.translateAlternateColorCodes('&', prefix + message);

        // Player debug via command
        UUID id = debugPlayerId;
        if (id != null) {
            Player debugPlayer = Bukkit.getPlayer(id);
            if (debugPlayer != null && debugPlayer.isOnline()) {
                debugPlayer.sendMessage(formatted);
                // Config debug still goes to console independently
                if (configDebug) {
                    Bukkit.getConsoleSender().sendMessage(formatted);
                }
                return;
            } else {
                // Player went offline, clean up
                debugPlayerId = null;
            }
        }

        // Config debug → console only
        if (configDebug) {
            Bukkit.getConsoleSender().sendMessage(formatted);
        }
    }
}
