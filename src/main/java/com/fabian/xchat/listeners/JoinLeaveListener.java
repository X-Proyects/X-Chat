package com.fabian.xchat.listeners;

import com.fabian.xchat.XChat;
import com.fabian.xchat.utils.ColorUtils;
import com.fabian.xchat.utils.DebugLogger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class JoinLeaveListener implements Listener {

    private final XChat plugin;

    public JoinLeaveListener(XChat plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        DebugLogger.debug("JoinLeave", "Processing join for " + player.getName() + " (first join: " + !player.hasPlayedBefore() + ")");

        // Cancel default join message
        if (plugin.getConfig().getBoolean("join-leave.join.cancel-default", true)) {
            event.setJoinMessage(null);
        }

        // First-join message
        if (!player.hasPlayedBefore() && plugin.getConfig().getBoolean("join-leave.first-join.enabled", true)) {
            String firstMsg = plugin.getConfig()
                    .getString("join-leave.first-join.message", "")
                    .replace("%player_name%", player.getName());
            if (!firstMsg.isEmpty()) {
                net.kyori.adventure.text.Component component = ColorUtils.format(player, firstMsg);
                String legacy = ColorUtils.toLegacyString(component);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (ColorUtils.isPaperAdventureAvailable()) {
                        try { p.sendMessage(component); } catch (NoSuchMethodError | NoClassDefFoundError e) { p.sendMessage(legacy); }
                    } else {
                        p.sendMessage(legacy);
                    }
                }
                Bukkit.getConsoleSender().sendMessage(legacy);

                // Publish first-join cross-server
                publishCrossServerJoin(legacy);
            }
            // Don't return — still need to do cross-server player list and state restore below
        } else {
            // Normal join message
            if (plugin.getConfig().getBoolean("join-leave.join.enabled", true)) {
                String msg = plugin.getConfig()
                        .getString("join-leave.join.message", "")
                        .replace("%player_name%", player.getName());
                if (!msg.isEmpty()) {
                    net.kyori.adventure.text.Component component = ColorUtils.format(player, msg);
                    String legacy = ColorUtils.toLegacyString(component);
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (ColorUtils.isPaperAdventureAvailable()) {
                            try { p.sendMessage(component); } catch (NoSuchMethodError | NoClassDefFoundError e) { p.sendMessage(legacy); }
                        } else {
                            p.sendMessage(legacy);
                        }
                    }
                    Bukkit.getConsoleSender().sendMessage(legacy);

                    // Publish join cross-server
                    publishCrossServerJoin(legacy);
                }
            }
        }

        // Publish player list update (add this player to cross-server cache)
        plugin.publishPlayerUpdate(player.getName(), true);

        if (plugin.getChatHistoryManager() != null) {
            plugin.getChatHistoryManager().loadPlayer(player.getUniqueId());
        }

        // Restore cross-server state from storage (spy, reply target)
        com.fabian.xchat.storage.StorageProvider sp = plugin.getStorageProvider();
        if (sp != null && !(sp instanceof com.fabian.xchat.storage.YamlStorageProvider)) {
            // Restore spy state
            if (sp.isSpyEnabled(player.getUniqueId())) {
                plugin.toggleSpy(player.getUniqueId()); // enables in-memory
                DebugLogger.debug("JoinLeave", "Restored spy state for " + player.getName() + " (enabled)");
            }
            // Restore reply target
            java.util.UUID replyTarget = sp.getLastMessageTarget(player.getUniqueId());
            if (replyTarget != null) {
                plugin.setLastMessageTarget(player.getUniqueId(), replyTarget);
                DebugLogger.debug("JoinLeave", "Restored reply target for " + player.getName());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        DebugLogger.debug("JoinLeave", "Processing leave for " + player.getName());

        // Cancel default quit message (use legacy API, not Paper's Component API)
        if (plugin.getConfig().getBoolean("join-leave.leave.cancel-default", true)) {
            event.setQuitMessage(null);
        }

        if (plugin.getConfig().getBoolean("join-leave.leave.enabled", true)) {
            String msg = plugin.getConfig()
                    .getString("join-leave.leave.message", "")
                    .replace("%player_name%", player.getName());
            if (!msg.isEmpty()) {
                net.kyori.adventure.text.Component component = ColorUtils.format(player, msg);
                String legacy = ColorUtils.toLegacyString(component);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (ColorUtils.isPaperAdventureAvailable()) {
                        try { p.sendMessage(component); } catch (NoSuchMethodError | NoClassDefFoundError e) { p.sendMessage(legacy); }
                    } else {
                        p.sendMessage(legacy);
                    }
                }
                Bukkit.getConsoleSender().sendMessage(legacy);

                // Publish quit cross-server
                publishCrossServerQuit(legacy);
            }
        }

        // Publish player list update (remove this player from cross-server cache)
        plugin.publishPlayerUpdate(player.getName(), false);

        // Clean up player data from memory
        plugin.cleanupPlayer(player.getUniqueId());
    }

    /**
     * Publishes a join message to other servers via Redis.
     */
    private void publishCrossServerJoin(String legacyMessage) {
        if (plugin.getMessagingService() != null && plugin.getMessagingService().isEnabled()) {
            // Send the legacy-formatted join message
            plugin.getMessagingService().publish("join", "", "",
                    plugin.getServerName(), legacyMessage);
        }
    }

    /**
     * Publishes a quit message to other servers via Redis.
     */
    private void publishCrossServerQuit(String legacyMessage) {
        if (plugin.getMessagingService() != null && plugin.getMessagingService().isEnabled()) {
            plugin.getMessagingService().publish("quit", "", "",
                    plugin.getServerName(), legacyMessage);
        }
    }
}