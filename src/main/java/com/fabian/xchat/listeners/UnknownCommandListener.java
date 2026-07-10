package com.fabian.xchat.listeners;

import com.fabian.xchat.XChat;
import com.fabian.xchat.utils.ColorUtils;
import com.fabian.xchat.utils.DebugLogger;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

public class UnknownCommandListener implements Listener {

    private final XChat plugin;

    public UnknownCommandListener(XChat plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onUnknownCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.getConfig().getBoolean("chat-settings.errors.friendly-errors", true)) {
            return;
        }

        String message = event.getMessage();
        if (message == null || message.length() <= 1) return;

        // Remove leading slash and extract command label
        String raw = message.substring(1).split(" ")[0].toLowerCase();

        // If the server knows this command, do nothing
        Command knownCommand = Bukkit.getCommandMap().getCommand(raw);
        if (knownCommand != null) return;

        // Also check aliases
        if (Bukkit.getCommandMap().getKnownCommands().containsKey(raw)) return;

        // Unknown command — cancel the ugly error and show friendly message
        event.setCancelled(true);
        DebugLogger.debug("UnknownCommand", "Unknown command: " + raw + " from " + event.getPlayer().getName());

        // Read message from config (errors section), fallback to language file
        String msg = plugin.getConfig().getString("chat-settings.errors.unknown-command", "");
        if (msg == null || msg.isEmpty()) {
            msg = plugin.getLanguageManager().getMessage("unknown-command");
        }
        msg = msg.replace("%command%", raw);
        ColorUtils.sendComponent(event.getPlayer(), ColorUtils.format(event.getPlayer(), msg));
    }
}
