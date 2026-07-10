package com.fabian.xchat.listeners;

import com.fabian.xchat.utils.DebugLogger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandSendEvent;

import java.util.Collection;

public class CommandHideListener implements Listener {

    // Only hide our own namespaced commands
    private static final String[] HIDDEN_PREFIXES = {
            "xchat:", "x-chat:"
    };

    @EventHandler
    public void onCommandSend(PlayerCommandSendEvent event) {
        Collection<String> commands = event.getCommands();
        int before = commands.size();

        commands.removeIf(command -> {
            String lower = command.toLowerCase();
            for (String prefix : HIDDEN_PREFIXES) {
                if (lower.startsWith(prefix)) return true;
            }
            return false;
        });

        if (before != commands.size()) {
            DebugLogger.debug("CommandHide", "Filtered " + (before - commands.size()) + " namespaced commands for " + event.getPlayer().getName());
        }
    }
}