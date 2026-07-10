package com.fabian.xchat.commands;

import com.fabian.xchat.XChat;
import com.fabian.xchat.storage.StorageProvider;
import com.fabian.xchat.utils.ColorUtils;
import com.fabian.xchat.utils.DebugLogger;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class CustomCommand extends Command {

    private final XChat plugin;
    private final String type; // "clear", "msg", "reply", "spy", "ignore", "sudo", "chatlog", "broadcast"

    public CustomCommand(XChat plugin, String name, String type, List<String> aliases, String permission) {
        super(name);
        this.plugin = plugin;
        this.type = type;
        this.setAliases(aliases);
        this.setPermission(permission);
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
        DebugLogger.debug("CustomCommand", "Dynamic command executed: /" + label + " (type: " + type + ") by " + sender.getName());
        String perm = getPermission();
        // Only check permission if it is defined and not empty.
        // Empty permission = everyone can use the command.
        if (perm != null && !perm.isEmpty() && !sender.hasPermission(perm)) {
            ColorUtils.sendComponent(sender, ColorUtils.format(null, plugin.getLanguageManager().getMessage("no-permission")));
            return true;
        }

        switch (type) {
            case "clear":
                handleClear(sender);
                break;
            case "msg":
                handleMsg(sender, args);
                break;
            case "reply":
                handleReply(sender, args);
                break;
            case "spy":
                handleSpy(sender);
                break;
            case "ignore":
                handleIgnore(sender, args);
                break;
            case "sudo":
                handleSudo(sender, args);
                break;
            case "chatlog":
                handleChatLog(sender, args);
                break;
            case "broadcast":
                handleBroadcast(sender, args);
                break;
        }
        return true;
    }

    private void handleClear(CommandSender sender) {
        String blanks = String.join("\n", java.util.Collections.nCopies(100, ""));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(blanks);
        }
        String msg = plugin.getLanguageManager().getMessage("chat-cleared").replace("%player%", sender.getName());
        ColorUtils.broadcastLegacy(null, msg);
    }

    private void handleMsg(CommandSender sender, String[] args) {
        if (args.length < 2) {
            ColorUtils.sendComponent(sender, ColorUtils.format(sender instanceof Player ? (Player) sender : null, plugin.getLanguageManager().getMessage("define-msg-target").replace("%command%", getName())));
            return;
        }

        CommandSender target;
        UUID targetUUID = null;
        boolean isLocal = true;

        if (args[0].equalsIgnoreCase("console")) {
            target = Bukkit.getConsoleSender();
            targetUUID = new UUID(0, 0);
        } else {
            // First try local lookup
            target = Bukkit.getPlayer(args[0]);
            if (target == null || (target instanceof Player && !((Player) target).isOnline())) {
                // Check storage for UUID (the player might be on another server)
                StorageProvider sp = plugin.getStorageProvider();
                if (sp != null && !(sp instanceof com.fabian.xchat.storage.YamlStorageProvider)) {
                    sendCrossServerPM(sender, args[0], args);
                    return;
                }
                ColorUtils.sendComponent(sender, ColorUtils.format(sender instanceof Player ? (Player) sender : null, plugin.getLanguageManager().getMessage("player-not-found")));
                return;
            }
            if (target instanceof Player) {
                targetUUID = ((Player) target).getUniqueId();
            }
        }

        if (sender instanceof Player) {
            Player pSender = (Player) sender;
            if (target.equals(pSender)) {
                ColorUtils.sendComponent(sender, ColorUtils.format(pSender, plugin.getLanguageManager().getMessage("player-cannot-msg-self")));
                return;
            }

            if (target instanceof Player && plugin.isIgnoring(((Player)target).getUniqueId(), pSender.getUniqueId())) {
                ColorUtils.sendComponent(sender, ColorUtils.format(pSender, plugin.getLanguageManager().getMessage("player-ignoring-you")));
                return;
            }

            if (target instanceof Player && plugin.isIgnoring(pSender.getUniqueId(), ((Player)target).getUniqueId())) {
                ColorUtils.sendComponent(sender, ColorUtils.format(pSender, plugin.getLanguageManager().getMessage("player-ignored").replace("%player%", target.getName())));
                return;
            }

            // Cross-server ignore check via storage
            if (targetUUID != null && plugin.getStorageProvider() != null
                    && !(plugin.getStorageProvider() instanceof com.fabian.xchat.storage.YamlStorageProvider)) {
                if (plugin.getStorageProvider().isIgnoring(targetUUID, pSender.getUniqueId())) {
                    ColorUtils.sendComponent(sender, ColorUtils.format(pSender, plugin.getLanguageManager().getMessage("player-ignoring-you")));
                    return;
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            sb.append(args[i]).append(" ");
        }
        String message = sb.toString().trim();

        sendPrivateMessage(sender, target, targetUUID, message, isLocal);
    }

    private void handleReply(CommandSender sender, String[] args) {
        if (args.length < 1) {
            ColorUtils.sendComponent(sender, ColorUtils.format(sender instanceof Player ? (Player) sender : null, plugin.getLanguageManager().getMessage("define-message").replace("%command%", getName())));
            return;
        }

        UUID senderUUID = sender instanceof Player ? ((Player) sender).getUniqueId() : new UUID(0, 0);
        UUID targetUUID = plugin.getLastMessageTarget(senderUUID);

        // Check cross-server storage for reply target
        if (targetUUID == null && plugin.getStorageProvider() != null
                && !(plugin.getStorageProvider() instanceof com.fabian.xchat.storage.YamlStorageProvider)) {
            targetUUID = plugin.getStorageProvider().getLastMessageTarget(senderUUID);
        }

        // Check cross-server reply name cache
        String crossServerTargetName = null;
        if (targetUUID == null) {
            crossServerTargetName = plugin.getCrossServerReplyName(senderUUID);
        }

        if (targetUUID == null && crossServerTargetName == null) {
            ColorUtils.sendComponent(sender, ColorUtils.format(sender instanceof Player ? (Player) sender : null, plugin.getLanguageManager().getMessage("no-reply-target")));
            return;
        }

        // If we only have a name (cross-server reply), send via Redis
        if (targetUUID == null && crossServerTargetName != null) {
            // Verify the target is not on this server
            Player localTarget = Bukkit.getPlayer(crossServerTargetName);
            if (localTarget != null && localTarget.isOnline()) {
                // Target is actually local now, deliver directly
                StringBuilder sb = new StringBuilder();
                for (String arg : args) { sb.append(arg).append(" "); }
                String message = sb.toString().trim();
                sendPrivateMessage(sender, localTarget, localTarget.getUniqueId(), message, true);
                return;
            }

            // Target is on another server — send via Redis
            if (plugin.getMessagingService() != null && plugin.getMessagingService().isEnabled()) {
                StringBuilder sb = new StringBuilder();
                for (String arg : args) { sb.append(arg).append(" "); }
                String message = sb.toString().trim();
                String senderName = sender instanceof Player ? sender.getName() : plugin.getLanguageManager().getMessage("console-name");
                String senderUuid = sender instanceof Player ? ((Player) sender).getUniqueId().toString() : "00000000-0000-0000-0000-000000000000";

                // Payload: targetName|senderName|messageContent
                String payload = crossServerTargetName + "|" + senderName + "|" + message;
                plugin.getMessagingService().publish("pm", senderName, senderUuid,
                        plugin.getServerName(), payload);

                // Show sender's message locally
                String senderMsg = plugin.getLanguageManager().getMessage("msg-format-sender")
                        .replace("%target%", crossServerTargetName)
                        .replace("%message%", message);
                ColorUtils.sendComponent(sender, ColorUtils.format(sender instanceof Player ? (Player) sender : null, senderMsg));

                // Update reply target name for sender
                if (sender instanceof Player) {
                    plugin.setCrossServerReplyName(((Player) sender).getUniqueId(), crossServerTargetName);
                }

                // Social Spy (local)
                String spyMsg = plugin.getLanguageManager().getMessage("social-spy-format")
                        .replace("%sender%", senderName)
                        .replace("%target%", crossServerTargetName)
                        .replace("%message%", message);
                String spyLegacy = ColorUtils.toLegacyString(ColorUtils.format(null, spyMsg));
                for (Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
                    if (online.hasPermission("xchat.command.spy") && plugin.isSpyEnabled(online.getUniqueId()) && !online.equals(sender)) {
                        online.sendMessage(spyLegacy);
                    }
                }
                // Publish spy to other servers (receiver's server handles its own local spies)
                plugin.getMessagingService().publish("spy", senderName, senderUuid,
                        plugin.getServerName(), spyLegacy);
                return;
            }
            ColorUtils.sendComponent(sender, ColorUtils.format(sender instanceof Player ? (Player) sender : null, plugin.getLanguageManager().getMessage("player-not-found")));
            return;
        }

        CommandSender target;
        boolean isLocal = true;
        if (targetUUID.equals(new UUID(0, 0))) {
            target = Bukkit.getConsoleSender();
        } else {
            target = Bukkit.getPlayer(targetUUID);
            if (target == null || (target instanceof Player && !((Player) target).isOnline())) {
                // Target is on another server - send via Redis
                if (plugin.getMessagingService() != null && plugin.getMessagingService().isEnabled()) {
                    StringBuilder sb = new StringBuilder();
                    for (String arg : args) { sb.append(arg).append(" "); }
                    String message = sb.toString().trim();
                    String senderName = sender instanceof Player ? sender.getName() : plugin.getLanguageManager().getMessage("console-name");
                    String senderUuid = sender instanceof Player ? ((Player) sender).getUniqueId().toString() : "00000000-0000-0000-0000-000000000000";
                    // Resolve target name for cross-server delivery
                    String targetName = Bukkit.getOfflinePlayer(targetUUID).getName();
                    if (targetName == null) targetName = targetUUID.toString();
                    // Payload: targetName|senderName|messageContent
                    String payload = targetName + "|" + senderName + "|" + message;
                    plugin.getMessagingService().publish("pm", senderName, senderUuid,
                            plugin.getServerName(), payload);
                    // Show sender's message locally
                    String senderMsg = plugin.getLanguageManager().getMessage("msg-format-sender")
                            .replace("%target%", targetName)
                            .replace("%message%", message);
                    ColorUtils.sendComponent(sender, ColorUtils.format(sender instanceof Player ? (Player) sender : null, senderMsg));

                    // Update reply target for sender
                    if (sender instanceof Player) {
                        plugin.getLastMessageTargetMap().remove(((Player) sender).getUniqueId());
                        plugin.setCrossServerReplyName(((Player) sender).getUniqueId(), targetName);
                    }

                    // Social Spy (local)
                    String spyMsg = plugin.getLanguageManager().getMessage("social-spy-format")
                            .replace("%sender%", senderName)
                            .replace("%target%", targetName)
                            .replace("%message%", message);
                    String spyLegacy = ColorUtils.toLegacyString(ColorUtils.format(null, spyMsg));
                    for (Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
                        if (online.hasPermission("xchat.command.spy") && plugin.isSpyEnabled(online.getUniqueId()) && !online.equals(sender)) {
                            online.sendMessage(spyLegacy);
                        }
                    }
                    plugin.getMessagingService().publish("spy", senderName, senderUuid,
                            plugin.getServerName(), spyLegacy);
                    return;
                }
                ColorUtils.sendComponent(sender, ColorUtils.format(sender instanceof Player ? (Player) sender : null, plugin.getLanguageManager().getMessage("player-not-found")));
                return;
            }
        }

        if (sender instanceof Player) {
            Player pSender = (Player) sender;
            if (target instanceof Player && plugin.isIgnoring(((Player)target).getUniqueId(), pSender.getUniqueId())) {
                ColorUtils.sendComponent(sender, ColorUtils.format(pSender, plugin.getLanguageManager().getMessage("player-ignoring-you")));
                return;
            }

            if (target instanceof Player && plugin.isIgnoring(pSender.getUniqueId(), ((Player) target).getUniqueId())) {
                ColorUtils.sendComponent(sender, ColorUtils.format(pSender, plugin.getLanguageManager().getMessage("player-ignored").replace("%player%", target.getName())));
                return;
            }
        }

        StringBuilder sb = new StringBuilder();
        for (String arg : args) {
            sb.append(arg).append(" ");
        }
        String message = sb.toString().trim();

        sendPrivateMessage(sender, target, targetUUID, message, isLocal);
    }

    private void sendPrivateMessage(CommandSender sender, CommandSender target, UUID targetUUID, String message, boolean isLocal) {
        Player pSender = sender instanceof Player ? (Player) sender : null;
        String senderName = sender instanceof Player ? sender.getName() : plugin.getLanguageManager().getMessage("console-name");
        String targetName = target instanceof Player ? target.getName() : plugin.getLanguageManager().getMessage("console-name");

        String senderMsg = plugin.getLanguageManager().getMessage("msg-format-sender")
                .replace("%target%", targetName)
                .replace("%message%", message);
        ColorUtils.sendComponent(sender, ColorUtils.format(pSender, senderMsg));

        String receiverMsg = plugin.getLanguageManager().getMessage("msg-format-receiver")
                .replace("%sender%", senderName)
                .replace("%message%", message);
        ColorUtils.sendComponent(target, ColorUtils.format(target instanceof Player ? (Player) target : null, receiverMsg));

        UUID senderUUID = sender instanceof Player ? ((Player) sender).getUniqueId() : new UUID(0, 0);
        UUID effectiveTargetUUID = targetUUID != null ? targetUUID : (target instanceof Player ? ((Player) target).getUniqueId() : new UUID(0, 0));

        // Save reply targets locally and in storage
        plugin.setLastMessageTarget(senderUUID, effectiveTargetUUID);
        plugin.setLastMessageTarget(effectiveTargetUUID, senderUUID);
        if (plugin.getStorageProvider() != null && !(plugin.getStorageProvider() instanceof com.fabian.xchat.storage.YamlStorageProvider)) {
            plugin.getStorageProvider().setLastMessageTarget(senderUUID, effectiveTargetUUID);
            plugin.getStorageProvider().setLastMessageTarget(effectiveTargetUUID, senderUUID);
        }

        // Social Spy (local) — send as legacy string for consistency
        String spyMsg = plugin.getLanguageManager().getMessage("social-spy-format")
                .replace("%sender%", senderName)
                .replace("%target%", target.getName())
                .replace("%message%", message);
        String spyLegacy = ColorUtils.toLegacyString(ColorUtils.format(null, spyMsg));

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("xchat.command.spy") && plugin.isSpyEnabled(online.getUniqueId()) && !online.equals(sender) && !online.equals(target)) {
                online.sendMessage(spyLegacy);
            }
        }

        // Publish spy message to Redis for cross-server spy (as legacy string)
        if (plugin.getMessagingService() != null && plugin.getMessagingService().isEnabled()) {
            String senderUuid = sender instanceof Player ? ((Player) sender).getUniqueId().toString() : "00000000-0000-0000-0000-000000000000";
            plugin.getMessagingService().publish("spy", senderName, senderUuid,
                    plugin.getServerName(), spyLegacy);
        }
    }

    private void sendCrossServerPM(CommandSender sender, String targetName, String[] args) {
        if (plugin.getMessagingService() == null || !plugin.getMessagingService().isEnabled()) {
            ColorUtils.sendComponent(sender, ColorUtils.format(sender instanceof Player ? (Player) sender : null,
                    plugin.getLanguageManager().getMessage("player-not-found")));
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < args.length; i++) { sb.append(args[i]).append(" "); }
        String message = sb.toString().trim();
        String senderName = sender instanceof Player ? sender.getName() : plugin.getLanguageManager().getMessage("console-name");
        String senderUUID = sender instanceof Player ? ((Player) sender).getUniqueId().toString() : "00000000-0000-0000-0000-000000000000";

        // Payload: targetName|senderName|messageContent
        String payload = targetName + "|" + senderName + "|" + message;
        plugin.getMessagingService().publish("pm", senderName, senderUUID,
                plugin.getServerName(), payload);

        // Show sender's message locally
        String senderMsg = plugin.getLanguageManager().getMessage("msg-format-sender")
                .replace("%target%", targetName)
                .replace("%message%", message);
        ColorUtils.sendComponent(sender, ColorUtils.format(sender instanceof Player ? (Player) sender : null, senderMsg));

        // Set reply target for sender using NAME (not fake UUID) so /r works cross-server
        if (sender instanceof Player) {
            UUID senderUuid = ((Player) sender).getUniqueId();
            plugin.getLastMessageTargetMap().remove(senderUuid); // clear local UUID target
            plugin.setCrossServerReplyName(senderUuid, targetName); // store name for /r
        }

        // Social Spy (local) — send to spies on THIS server
        String spyMsg = plugin.getLanguageManager().getMessage("social-spy-format")
                .replace("%sender%", senderName)
                .replace("%target%", targetName)
                .replace("%message%", message);
        String spyLegacy = ColorUtils.toLegacyString(ColorUtils.format(null, spyMsg));
        for (Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("xchat.command.spy") && plugin.isSpyEnabled(online.getUniqueId()) && !online.equals(sender)) {
                online.sendMessage(spyLegacy);
            }
        }

        // Publish spy message to other servers (receiver's server handles its own local spies)
        plugin.getMessagingService().publish("spy", senderName, senderUUID,
                plugin.getServerName(), spyLegacy);
    }

    private void handleSpy(CommandSender sender) {
        if (!(sender instanceof Player)) {
            ColorUtils.sendComponent(sender, ColorUtils.format(null, plugin.getLanguageManager().getMessage("only-players-spy")));
            return;
        }
        Player player = (Player) sender;
        UUID uuid = player.getUniqueId();

        boolean isSpy = plugin.toggleSpy(uuid);
        // Persist to storage if available
        if (plugin.getStorageProvider() != null && !(plugin.getStorageProvider() instanceof com.fabian.xchat.storage.YamlStorageProvider)) {
            plugin.getStorageProvider().setSpyEnabled(uuid, isSpy);
        }
        String msgKey = isSpy ? "social-spy-toggled-on" : "social-spy-toggled-off";
        ColorUtils.sendComponent(sender, ColorUtils.format(player, plugin.getLanguageManager().getMessage(msgKey)));
    }

    private void handleIgnore(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            ColorUtils.sendComponent(sender, ColorUtils.format(null, plugin.getLanguageManager().getMessage("only-players-ignore")));
            return;
        }
        Player pSender = (Player) sender;

        if (args.length < 1) {
            ColorUtils.sendComponent(sender, ColorUtils.format(pSender, plugin.getLanguageManager().getMessage("define-player").replace("%command%", getName())));
            return;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null || !target.isOnline()) {
            ColorUtils.sendComponent(sender, ColorUtils.format(pSender, plugin.getLanguageManager().getMessage("player-not-found")));
            return;
        }

        if (target.equals(pSender)) {
            ColorUtils.sendComponent(sender, ColorUtils.format(pSender, plugin.getLanguageManager().getMessage("cannot-ignore-self")));
            return;
        }

        // Toggle in memory (this also saves to YAML data.yml via saveIgnoredPlayers)
        boolean ignored = plugin.toggleIgnore(pSender.getUniqueId(), target.getUniqueId());

        // Sync to non-YAML storage providers (use the SAME result, don't toggle again)
        if (plugin.getStorageProvider() != null && !(plugin.getStorageProvider() instanceof com.fabian.xchat.storage.YamlStorageProvider)) {
            // plugin.toggleIgnore returned the NEW state (true = now ignoring)
            // storageProvider needs to reflect the same state
            boolean currentlyInStorage = plugin.getStorageProvider().isIgnoring(pSender.getUniqueId(), target.getUniqueId());
            if (ignored != currentlyInStorage) {
                plugin.getStorageProvider().toggleIgnore(pSender.getUniqueId(), target.getUniqueId());
            }
        }

        String msgKey = ignored ? "player-ignored" : "player-unignored";
        ColorUtils.sendComponent(sender, ColorUtils.format(pSender, plugin.getLanguageManager().getMessage(msgKey).replace("%player%", target.getName())));
    }

    private void handleSudo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            ColorUtils.sendComponent(sender, ColorUtils.format(sender instanceof Player ? (Player) sender : null, plugin.getLanguageManager().getMessage("define-player-action").replace("%command%", getName())));
            return;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null || !target.isOnline()) {
            ColorUtils.sendComponent(sender, ColorUtils.format(null, plugin.getLanguageManager().getMessage("player-not-found")));
            return;
        }

        if (target.hasPermission("xchat.exempt.sudo") && !sender.hasPermission("xchat.bypass.exempt")) {
            ColorUtils.sendComponent(sender, ColorUtils.format(sender instanceof Player ? (Player) sender : null, plugin.getLanguageManager().getMessage("sudo-exempt")));
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            sb.append(args[i]).append(" ");
        }
        String action = sb.toString().trim();

        if (action.startsWith("/")) {
            target.performCommand(action.substring(1));
        } else {
            target.chat(action);
        }
        
        ColorUtils.sendComponent(sender, ColorUtils.format(sender instanceof Player ? (Player) sender : null, plugin.getLanguageManager().getMessage("sudo-success").replace("%player%", target.getName()).replace("%action%", action)));
    }

    private void handleChatLog(CommandSender sender, String[] args) {
        if (args.length < 1) {
            ColorUtils.sendComponent(sender, ColorUtils.format(sender instanceof Player ? (Player) sender : null, plugin.getLanguageManager().getMessage("define-player-page").replace("%command%", getName())));
            return;
        }

        // Resolve the target player (works for local, offline, and cross-server players)
        org.bukkit.OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        java.util.UUID targetUUID = target != null ? target.getUniqueId() : null;

        if (targetUUID == null) {
            ColorUtils.sendComponent(sender, ColorUtils.format(sender instanceof Player ? (Player) sender : null, plugin.getLanguageManager().getMessage("player-not-found")));
            return;
        }

        // Try to load from disk if not already in memory (handles cross-server players after restart)
        if (plugin.getChatHistoryManager() != null && plugin.getChatHistoryManager().getHistory(targetUUID).isEmpty()) {
            plugin.getChatHistoryManager().loadPlayer(targetUUID);
        }

        List<String> history = plugin.getChatHistoryManager().getHistory(targetUUID);

        if (history.isEmpty()) {
            String displayName = target.getName() != null ? target.getName() : plugin.getLanguageManager().getMessage("console-name");
            ColorUtils.sendComponent(sender, ColorUtils.format(sender instanceof Player ? (Player) sender : null, plugin.getLanguageManager().getMessage("no-recent-history").replace("%player%", displayName)));
            return;
        }

        int page = 1;
        if (args.length > 1) {
            try { page = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
        }
        
        int perPage = 15;
        int maxPages = (int) Math.ceil(history.size() / (double) perPage);
        if (page < 1) page = 1;
        if (page > maxPages) page = maxPages;

        ColorUtils.sendComponent(sender, ColorUtils.format(null, plugin.getLanguageManager().getMessage("chatlog-header").replace("%player%", target.getName()).replace("%page%", String.valueOf(page)).replace("%max%", String.valueOf(maxPages))));
        
        int start = (page - 1) * perPage;
        int end = Math.min(start + perPage, history.size());

        for (int i = start; i < end; i++) {
            ColorUtils.sendComponent(sender, ColorUtils.format(null, history.get(i)));
        }
    }

    private void handleBroadcast(CommandSender sender, String[] args) {
        if (args.length == 0) {
            ColorUtils.sendComponent(sender, ColorUtils.format(null, plugin.getLanguageManager().getMessage("broadcast-usage").replace("%command%", this.getName())));
            return;
        }
        
        String message = String.join(" ", args);
        StringBuilder allLines = new StringBuilder();
        
        Object formatObj = plugin.getConfig().get("broadcast-settings.format");
        if (formatObj instanceof java.util.List) {
            java.util.List<?> list = (java.util.List<?>) formatObj;
            for (Object line : list) {
                if (line != null) {
                    String formattedLine = line.toString().replace("{message}", message);
                    ColorUtils.broadcastLegacy(null, formattedLine);
                    // Use toLegacyString for cross-server (converts to § codes, universally understood)
                    String legacyLine = ColorUtils.toLegacyString(ColorUtils.format(null, formattedLine));
                    allLines.append(legacyLine);
                    allLines.append("\n");
                }
            }
        } else if (formatObj != null) {
            String formattedLine = formatObj.toString().replace("{message}", message);
            ColorUtils.broadcastLegacy(null, formattedLine);
            String legacyLine = ColorUtils.toLegacyString(ColorUtils.format(null, formattedLine));
            allLines.append(legacyLine);
        } else {
            String fallback = plugin.getLanguageManager().getMessage("broadcast-fallback").replace("{message}", message);
            ColorUtils.broadcastLegacy(null, fallback);
            String legacyLine = ColorUtils.toLegacyString(ColorUtils.format(null, fallback));
            allLines.append(legacyLine);
        }

        // Publish broadcast to other servers (as legacy string with § codes)
        boolean crossServer = plugin.getConfig().getBoolean("broadcast-settings.cross-server", true);
        boolean showServerPrefix = plugin.getConfig().getBoolean("broadcast-settings.show-server-prefix", true);
        if (crossServer && plugin.getMessagingService() != null && plugin.getMessagingService().isEnabled()) {
            String senderName = sender instanceof Player ? sender.getName() : "Console";
            String senderUUID = sender instanceof Player ? ((Player) sender).getUniqueId().toString() : "00000000-0000-0000-0000-000000000000";
            // Prefix with a flag so the receiver knows whether to show server prefix
            String payload = (showServerPrefix ? "1" : "0") + "\n" + allLines.toString().trim();
            plugin.getMessagingService().publish("broadcast", senderName, senderUUID,
                    plugin.getServerName(), payload);
        }
    }


    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) throws IllegalArgumentException {
        List<String> completions = new ArrayList<>();
        if ("msg".equalsIgnoreCase(type) || "ignore".equalsIgnoreCase(type) || "sudo".equalsIgnoreCase(type) || "chatlog".equalsIgnoreCase(type)) {
            if (args.length == 1) {
                String search = args[0].toLowerCase();
                // Include local players
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!p.equals(sender) && p.getName().toLowerCase().startsWith(search)) {
                        completions.add(p.getName());
                    }
                }
                // Include cross-server players from cache
                for (Map.Entry<String, Long> entry : plugin.getCrossServerPlayers().entrySet()) {
                    String name = entry.getKey();
                    if (!sender.getName().equalsIgnoreCase(name) && name.toLowerCase().startsWith(search) && !completions.contains(name)) {
                        completions.add(name);
                    }
                }
            }
        }
        return completions;
    }
}