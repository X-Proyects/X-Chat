package com.fabian.xchat.hooks;

import com.fabian.xchat.XChat;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.UUID;

public class XChatExpansion extends PlaceholderExpansion {

    private final XChat plugin;

    public XChatExpansion(XChat plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "xchat";
    }

    @Override
    public @NotNull String getAuthor() {
        return "fabianfamr";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null || !player.isOnline()) return "";

        Player p = player.getPlayer();
        switch (params.toLowerCase()) {
            case "ignored_count": {
                Set<UUID> ignored = plugin.getIgnoredPlayersFor(p);
                return String.valueOf(ignored != null ? ignored.size() : 0);
            }
            case "is_spying":
                return plugin.isSpyEnabled(p.getUniqueId()) ? "true" : "false";
            case "chat_history_size":
                return String.valueOf(plugin.getChatHistoryManager().getHistorySize(p.getUniqueId()));
            default:
                return null;
        }
    }
}