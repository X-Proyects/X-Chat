package com.fabian.xchat.listeners;

import com.fabian.xchat.XChat;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.TabCompleteEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Adds cross-server player names to vanilla chat Tab-completion.
 *
 * Normally, when a player presses Tab in chat (without typing a command),
 * Minecraft only suggests the names of players on the SAME server.
 * This listener intercepts the TabCompleteEvent and augments the completions
 * with cross-server players from the cache, so that names from other servers
 * appear in the vanilla Tab-complete popup.
 */
public class ChatTabCompleteListener implements Listener {

    private final XChat plugin;

    public ChatTabCompleteListener(XChat plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onTabComplete(TabCompleteEvent event) {
        String buffer = event.getBuffer();

        // Only intercept vanilla chat (no slash) — command tab-completion is handled
        // by each command's own TabCompleter (CustomCommand, XChatCommand, etc.)
        if (buffer.startsWith("/")) return;

        // Get the last word being typed (the prefix to match against)
        int lastSpace = buffer.lastIndexOf(' ');
        String prefix = lastSpace >= 0 ? buffer.substring(lastSpace + 1) : buffer;

        // If prefix is empty, don't add all players (would flood the popup)
        if (prefix.isEmpty()) return;

        String lowerPrefix = prefix.toLowerCase();

        @SuppressWarnings("unchecked")
        List<String> completions = (List<String>) event.getCompletions();

        // Add cross-server players whose name starts with the prefix
        for (String name : plugin.getCrossServerPlayers().keySet()) {
            // Skip if already in the list (local players are already there)
            if (completions.contains(name)) continue;

            // Match case-insensitively
            if (name.toLowerCase().startsWith(lowerPrefix)) {
                completions.add(name);
            }
        }

        // Note: we don't sort here because Bukkit/Minecraft sorts the popup itself.
        // But if completions was an immutable list, we need to replace it with a mutable one.
        // TabCompleteEvent.getCompletions() returns a mutable List by default on modern Bukkit,
        // but just to be safe:
        event.setCompletions(completions);
    }
}
