package com.fabian.xchat.listeners;

import com.fabian.xchat.XChat;
import com.fabian.xchat.utils.ColorUtils;
import com.fabian.xchat.utils.DebugLogger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@SuppressWarnings("deprecation")
public class ChatListener implements Listener {

    private static final Pattern URL_PATTERN = Pattern.compile("(?i)\\b(https?://|www\\.)\\S+\\b");
    private static final Pattern LEGACY_COLOR_PATTERN = Pattern.compile("&[0-9a-fk-orA-FK-OR]");
    private static final Pattern HEX_COLOR_PATTERN_1 = Pattern.compile("&#[A-Fa-f0-9]{6}");
    private static final Pattern HEX_COLOR_PATTERN_2 = Pattern.compile("<#[A-Fa-f0-9]{6}>");
    private static final Pattern MINIMESSAGE_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern CLICK_TAG_PATTERN = Pattern.compile("<[/]?(?i)(click)[^>]*>");
    private static final Pattern HOVER_TAG_PATTERN = Pattern.compile("<[/]?(?i)(hover)[^>]*>");
    private static final Pattern INSERT_TAG_PATTERN = Pattern.compile("<[/]?(?i)(insert)[^>]*>");
    private static final Pattern FONT_TAG_PATTERN = Pattern.compile("<[/]?(?i)(font)[^>]*>");
    private static final Pattern DANGEROUS_TAG_PATTERN = Pattern.compile("<[/]?(?i)(click|hover|insert|font)[^>]*>");

    private final XChat plugin;
    private final Map<UUID, Long> lastMessageTime = new ConcurrentHashMap<>();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public ChatListener(XChat plugin) {
        this.plugin = plugin;
    }

    // ──────────────────────────────────────────────────────
    //  MAIN CHAT EVENT
    // ──────────────────────────────────────────────────────
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String rawMessage = event.getMessage();
        DebugLogger.debug("ChatListener", "Processing chat event from " + player.getName() + ": " + rawMessage);

        // ── 1. Anti-Spam ──
        if (plugin.getConfig().getBoolean("filters.anti-spam.enabled") && !player.hasPermission(plugin.getConfig().getString("filters.anti-spam.bypass-permission", "xchat.bypass.spam"))) {
            long delay = plugin.getConfig().getInt("filters.anti-spam.delay-seconds") * 1000L;
            if (lastMessageTime.containsKey(player.getUniqueId())) {
                long timeLeft = (lastMessageTime.get(player.getUniqueId()) + delay) - System.currentTimeMillis();
                if (timeLeft > 0) {
                    String msg = plugin.getConfig().getString("filters.anti-spam.message", "");
                    if (!msg.isEmpty()) {
                        ColorUtils.sendComponent(player, ColorUtils.format(null, msg.replace("%time%", String.valueOf((timeLeft / 1000) + 1))));
                    }
                    event.setCancelled(true);
                    DebugLogger.debug("ChatListener", "Anti-Spam: blocked message from " + player.getName() + " (" + (timeLeft / 1000 + 1) + "s remaining)");
                    return;
                }
            }
            lastMessageTime.put(player.getUniqueId(), System.currentTimeMillis());
        }

        // ── 2. Anti-Caps ──
        if (plugin.getConfig().getBoolean("filters.anti-caps.enabled") && !player.hasPermission(plugin.getConfig().getString("filters.anti-caps.bypass-permission", "xchat.bypass.caps"))) {
            int minLength = plugin.getConfig().getInt("filters.anti-caps.min-length");
            if (rawMessage.length() >= minLength) {
                long capsCount = rawMessage.chars().filter(Character::isUpperCase).count();
                double percent = (double) capsCount / rawMessage.length() * 100;
                if (percent >= plugin.getConfig().getDouble("filters.anti-caps.max-percent")) {
                    String action = plugin.getConfig().getString("filters.anti-caps.action");
                    if ("block".equalsIgnoreCase(action)) {
                        String msg = plugin.getConfig().getString("filters.anti-caps.message", "");
                        if (!msg.isEmpty()) ColorUtils.sendComponent(player, ColorUtils.format(null, msg));
                        event.setCancelled(true);
                        DebugLogger.debug("ChatListener", "Anti-Caps: blocked message from " + player.getName() + " (" + percent + "% caps)");
                        return;
                    } else {
                        rawMessage = rawMessage.toLowerCase();
                        DebugLogger.debug("ChatListener", "Anti-Caps: lowercased message from " + player.getName() + " (" + percent + "% caps)");
                    }
                }
            }
        }

        // ── 3. Smart Censor Filter ──
        if (plugin.getConfig().getBoolean("filters.blocked-words.enabled")) {
            List<String> words = plugin.getConfig().getStringList("filters.blocked-words.words");
            String mode = plugin.getConfig().getString("filters.blocked-words.mode", "partial");
            boolean bypass = player.hasPermission(plugin.getConfig().getString("filters.blocked-words.bypass-permission", "xchat.bypass.blocked-words"));

            if (!bypass) {
                boolean containsBadWord = false;
                for (String word : words) {
                    String lowerMsg = rawMessage.toLowerCase();
                    String lowerWord = word.toLowerCase();
                    int idx = lowerMsg.indexOf(lowerWord);
                    while (idx != -1) {
                        containsBadWord = true;
                        if ("full".equalsIgnoreCase(mode)) {
                            StringBuilder sb = new StringBuilder(rawMessage);
                            sb.replace(idx, idx + word.length(), "*".repeat(word.length()));
                            rawMessage = sb.toString();
                        } else {
                            String original = rawMessage.substring(idx, idx + word.length());
                            StringBuilder sb = new StringBuilder(rawMessage);
                            sb.replace(idx, idx + word.length(), censorPartial(original));
                            rawMessage = sb.toString();
                        }
                        lowerMsg = rawMessage.toLowerCase();
                        idx = lowerMsg.indexOf(lowerWord, idx + 1);
                    }
                }
                if (containsBadWord) {
                    String msg = plugin.getConfig().getString("filters.blocked-words.message", "");
                    if (!msg.isEmpty()) {
                        String modeKey = "full".equalsIgnoreCase(mode) ? "censor-mode-full" : "censor-mode-partial";
                        String modeMsg = plugin.getLanguageManager().getMessage(modeKey);
                        ColorUtils.sendComponent(player, ColorUtils.format(null, msg.replace("%mode%", modeMsg != null ? modeMsg : mode)));
                    }
                }
            }
        }

        // ── 4. Anti-Advertising ──
        if (plugin.getConfig().getBoolean("filters.anti-advertising.enabled") && !player.hasPermission(plugin.getConfig().getString("filters.anti-advertising.bypass-permission", "xchat.bypass.advertising"))) {
            List<String> patterns = plugin.getConfig().getStringList("filters.anti-advertising.patterns");
            boolean match = false;
            for (String p : patterns) {
                try {
                    if (Pattern.compile(p).matcher(rawMessage).find()) {
                        match = true;
                        break;
                    }
                } catch (Exception ignored) {}
            }
            if (match) {
                DebugLogger.debug("ChatListener", "Anti-Advertising: matched pattern for " + player.getName());
                String action = plugin.getConfig().getString("filters.anti-advertising.action", "block");
                String msg = plugin.getConfig().getString("filters.anti-advertising.message", "");
                if (!msg.isEmpty()) ColorUtils.sendComponent(player, ColorUtils.format(null, msg));
                if ("block".equalsIgnoreCase(action)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }

        // ── 5. Auto-Grammar ──
        if (plugin.getConfig().getBoolean("chat-settings.auto-grammar.enabled") && !player.hasPermission(plugin.getConfig().getString("chat-settings.auto-grammar.bypass-permission", "xchat.bypass.grammar"))) {
            if (plugin.getConfig().getBoolean("chat-settings.auto-grammar.capitalize-first", true) && !rawMessage.isEmpty()) {
                rawMessage = Character.toUpperCase(rawMessage.charAt(0)) + rawMessage.substring(1);
            }
            if (plugin.getConfig().getBoolean("chat-settings.auto-grammar.add-period", true) && !rawMessage.isEmpty()) {
                char last = rawMessage.charAt(rawMessage.length() - 1);
                if (last != '.' && last != '!' && last != '?' && last != ':' && last != ')') {
                    rawMessage += ".";
                }
            }
        }

        // ══════════════════════════════════════════════════
        //  FORMAT SYSTEM - Core
        // ══════════════════════════════════════════════════

        // Step 1: Determine which format applies to this player
        String formatName = resolveFormat(player);
        DebugLogger.debug("ChatListener", "Resolved format for " + player.getName() + ": " + formatName);

        // Step 2: Read color permissions from the format
        boolean allowLegacy = plugin.getFormatsConfig().getBoolean("formats." + formatName + ".allow-colors.legacy", false);
        boolean allowHex = plugin.getFormatsConfig().getBoolean("formats." + formatName + ".allow-colors.hex", false);
        boolean allowMini = plugin.getFormatsConfig().getBoolean("formats." + formatName + ".allow-colors.minimessage", false);

        // Master color permission overrides format-level settings
        if (player.hasPermission(plugin.getConfig().getString("general-permissions.all-colors", "xchat.colors"))) {
            allowLegacy = allowHex = allowMini = true;
        }

        // Step 3: Get the raw format string from formats.yml
        String formatString = plugin.getFormatsConfig().getString("formats." + formatName + ".format",
                "<gray>%player_name% <dark_gray>» <white>{message}");

        // Step 4: Filter colors in the PLAYER'S MESSAGE based on permissions
        rawMessage = filterMessageColors(rawMessage, allowLegacy, allowHex, allowMini, player);

        // Step 4.5: Apply PlaceholderAPI in the player's message (if enabled)
        if (plugin.getConfig().getBoolean("chat-settings.placeholders-in-message.enabled", false)
                && player.hasPermission(plugin.getConfig().getString("chat-settings.placeholders-in-message.permission", "xchat.placeholders"))) {
            rawMessage = ColorUtils.applyPapi(player, rawMessage);
        }

        // Step 5: Apply emojis to the message
        if (plugin.getConfig().getBoolean("chat-settings.emojis.enabled", false)) {
            boolean requirePerm = plugin.getConfig().getBoolean("chat-settings.emojis.require-permission", true);
            if (!requirePerm || player.hasPermission(plugin.getConfig().getString("chat-settings.emojis.permission", "xchat.emojis"))) {
                ConfigurationSection emojiSection = plugin.getConfig().getConfigurationSection("chat-settings.emojis.list");
                if (emojiSection != null) {
                    for (String key : emojiSection.getKeys(false)) {
                        String emojiValue = emojiSection.getString(key);
                        if (emojiValue != null) rawMessage = rawMessage.replace(key, emojiValue);
                    }
                }
            }
        }

        // Step 6: Process dynamic tags [item], [pos], [ping]
        if (plugin.getConfig().getBoolean("chat-settings.custom-tags.enabled", true)) {
            rawMessage = plugin.getTagsManager().processTags(player, rawMessage);
        }

        // Save pre-mention raw text for cross-server mention sound detection (plain player names)
        String preMentionRaw = rawMessage;

        // Step 7: Process mentions
        if (plugin.getConfig().getBoolean("chat-settings.mentions.enabled")) {
            rawMessage = processMentions(player, rawMessage);
        }

        // Step 8: Auto-Links
        if (plugin.getConfig().getBoolean("chat-settings.auto-links.enabled", true)) {
            rawMessage = processAutoLinks(rawMessage);
        }

        // Step 9: Apply interactive name hover/click to format string
        if (plugin.getConfig().getBoolean("chat-settings.interactive-name.enabled")) {
            formatString = applyInteractiveName(formatString, player);
        }

        // Step 10: Build final Component via MiniMessage deserialization
        Component finalComponent = buildFinalComponent(player, formatString, rawMessage, allowMini);

        // ── Cross-Server MiniMessage Strings ──
        // Build MiniMessage source strings for cross-server transfer.
        // This preserves ALL rich formatting (colors, hover, click, gradients) unlike JSON serialization.
        String mmFormat = null;
        String mmRaw = null;
        if (allowMini) {
            // Build MiniMessage format string (mirrors buildFinalComponent logic)
            String mmFormatStr = formatString.replace("%player_name%", player.getName());
            mmFormatStr = ColorUtils.applyPapi(player, mmFormatStr);
            mmFormatStr = ColorUtils.convertLegacyAndHex(mmFormatStr);
            mmFormatStr = mmFormatStr.replace("{message}", "<message>");
            mmFormat = mmFormatStr;

            // Build MiniMessage raw message string (with mentions, auto-links, tags)
            String protectedMsg = protectAmpersandsInAttributes(rawMessage);
            mmRaw = ColorUtils.convertLegacyAndHex(protectedMsg).replace('\u0000', '&');
        }

        // ══════════════════════════════════════════════════
        //  OUTPUT
        // ══════════════════════════════════════════════════

        // Save to chat history
        String finalStringForHistory = PlainTextComponentSerializer.plainText().serialize(finalComponent);
        plugin.getChatHistoryManager().addMessage(player.getUniqueId(), finalStringForHistory);

        // Remove ignored players from recipients
        event.getRecipients().removeIf(recipient ->
                recipient instanceof Player && plugin.isIgnoring(((Player) recipient).getUniqueId(), player.getUniqueId()));

        // Cancel default Bukkit message
        event.setCancelled(true);

        // Broadcast to all recipients: try Component first (Paper), fallback to legacy (Spigot)
        String legacyOutput = ColorUtils.toLegacyString(finalComponent);
        for (Player recipient : event.getRecipients()) {
            try {
                recipient.sendMessage(finalComponent);
            } catch (NoSuchMethodError | NoClassDefFoundError e) {
                recipient.sendMessage(legacyOutput);
            }
        }
        // Console: always use legacy string (no hover/click support)
        Bukkit.getConsoleSender().sendMessage(legacyOutput);

        // Save to DB chatlog if storage supports it
        if (plugin.getStorageProvider() != null) {
            plugin.getStorageProvider().saveChatLog(player.getUniqueId(), player.getName(),
                    plugin.getServerName(), finalStringForHistory);
        }

        // Publish to cross-server (Redis pub/sub)
        if (plugin.getMessagingService() != null && plugin.getMessagingService().isEnabled()) {
            // Cross-server payload format (4 lines, newline-delimited):
            //   Line 1: legacy formatted message (§-coded, Spigot fallback)
            //   Line 2: MiniMessage format string (with <message> placeholder) or empty
            //   Line 3: MiniMessage raw message text (with mentions/links/tags) or empty
            //   Line 4: pre-mention plain text (for cross-server mention sound detection)
            String crossServerPayload = legacyOutput
                    + "\n" + (mmFormat != null ? mmFormat : "")
                    + "\n" + (mmRaw != null ? mmRaw : "")
                    + "\n" + preMentionRaw;

            plugin.getMessagingService().publish("chat", player.getName(),
                    player.getUniqueId().toString(),
                    plugin.getServerName(), crossServerPayload);
        }
    }

    // ──────────────────────────────────────────────────────
    //  FORMAT RESOLUTION
    // ──────────────────────────────────────────────────────
    /**
     * Resolves which format to use for the given player.
     * Formats are sorted by priority (configurable: higher-is-better or lower-is-better).
     * The first format whose permission the player has is returned.
     * If none match, falls back to "default".
     */
    private String resolveFormat(Player player) {
        ConfigurationSection formatsSection = plugin.getFormatsConfig().getConfigurationSection("formats");
        if (formatsSection == null) {
            plugin.logWarning("[X-Chat] No 'formats' section found in formats.yml! Falling back to default.");
            return "default";
        }

        // Collect all formats with their priority weight
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>();
        for (String key : formatsSection.getKeys(false)) {
            int priority = plugin.getFormatsConfig().getInt("formats." + key + ".priority", 0);
            sorted.add(new AbstractMap.SimpleEntry<>(key, priority));
        }

        // Sort: default is higher number = more priority
        boolean higherIsBetter = plugin.getConfig().getBoolean("format-priority.higher-is-better", true);
        sorted.sort((a, b) -> higherIsBetter
                ? Integer.compare(b.getValue(), a.getValue())   // descending: 100, 50, 1
                : Integer.compare(a.getValue(), b.getValue())); // ascending: 1, 50, 100

        // Return first format where the player has the specific permission
        for (Map.Entry<String, Integer> entry : sorted) {
            String key = entry.getKey();
            String perm = plugin.getFormatsConfig().getString("formats." + key + ".permission");
            if (perm != null && player.hasPermission(perm)) {
                return key;
            }
        }

        return "default";
    }

    // ──────────────────────────────────────────────────────
    //  COLOR FILTERING
    // ──────────────────────────────────────────────────────
    /**
     * Filters color codes in the player's message based on their format permissions.
     */
    private String filterMessageColors(String message, boolean allowLegacy, boolean allowHex, boolean allowMini, Player player) {
        if (!allowLegacy) {
            message = LEGACY_COLOR_PATTERN.matcher(message).replaceAll("");
        }
        if (!allowHex) {
            message = HEX_COLOR_PATTERN_1.matcher(message).replaceAll("");
            message = HEX_COLOR_PATTERN_2.matcher(message).replaceAll("");
        }
        if (!allowMini) {
            if (allowHex) {
                // Hex allowed but not full MiniMessage: strip only dangerous interactive tags
                message = DANGEROUS_TAG_PATTERN.matcher(message).replaceAll("");
            } else {
                // Neither hex nor MiniMessage: strip ALL MiniMessage tags
                message = MINIMESSAGE_TAG_PATTERN.matcher(message).replaceAll("");
            }
        } else {
            // MiniMessage allowed: strip only dangerous tags the player doesn't have permission for
            if (!canUseTag(player, "click")) message = CLICK_TAG_PATTERN.matcher(message).replaceAll("");
            if (!canUseTag(player, "hover")) message = HOVER_TAG_PATTERN.matcher(message).replaceAll("");
            if (!canUseTag(player, "insert")) message = INSERT_TAG_PATTERN.matcher(message).replaceAll("");
            if (!canUseTag(player, "font"))  message = FONT_TAG_PATTERN.matcher(message).replaceAll("");
        }
        return message;
    }

    // ──────────────────────────────────────────────────────
    //  MENTIONS
    // ──────────────────────────────────────────────────────
    private String processMentions(Player player, String message) {
        boolean requireSymbol = plugin.getConfig().getBoolean("chat-settings.mentions.require-symbol", false);
        String symbol = plugin.getConfig().getString("chat-settings.mentions.symbol", "@");
        String mentionFormat = plugin.getConfig().getString("chat-settings.mentions.format", "<yellow>%player%</yellow>");

        List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
        // Sort by display name length (descending) so longer matches take priority
        // Display name may include prefix (e.g., "[Admin] Fabian")
        onlinePlayers.sort((p1, p2) -> Integer.compare(
                org.bukkit.ChatColor.stripColor(p2.getDisplayName()).length(),
                org.bukkit.ChatColor.stripColor(p1.getDisplayName()).length()));

        for (Player target : onlinePlayers) {
            // Skip the sender: you cannot tag yourself
            if (target.equals(player)) continue;

            String name = target.getName();
            // Also try the display name (stripped of color codes), which may include prefix
            String strippedDisplay = org.bukkit.ChatColor.stripColor(target.getDisplayName());

            // Try the LONGER match first (display name with prefix)
            String matchText = strippedDisplay.length() > name.length() ? strippedDisplay : name;

            String trigger = requireSymbol ? Pattern.quote(symbol + matchText) : "\\b" + Pattern.quote(matchText) + "\\b";
            Pattern pattern = Pattern.compile(trigger, Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher matcher = pattern.matcher(message);
            if (matcher.find()) {
                message = matcher.replaceAll(java.util.regex.Matcher.quoteReplacement(mentionFormat.replace("%player%", matchText)));
                try {
                    org.bukkit.Sound sound = org.bukkit.Sound.valueOf(plugin.getConfig().getString("chat-settings.mentions.sound", "ENTITY_EXPERIENCE_ORB_PICKUP"));
                    float vol = (float) plugin.getConfig().getDouble("chat-settings.mentions.volume", 1.0);
                    float pitch = (float) plugin.getConfig().getDouble("chat-settings.mentions.pitch", 1.5);
                    target.playSound(target.getLocation(), sound, vol, pitch);
                } catch (Exception ignored) {}
                continue; // Already matched, skip the shorter name check
            }

            // Also try just the player name (without prefix)
            if (!matchText.equals(name)) {
                String nameTrigger = requireSymbol ? Pattern.quote(symbol + name) : "\\b" + Pattern.quote(name) + "\\b";
                Pattern namePattern = Pattern.compile(nameTrigger, Pattern.CASE_INSENSITIVE);
                java.util.regex.Matcher nameMatcher = namePattern.matcher(message);
                if (nameMatcher.find()) {
                    message = nameMatcher.replaceAll(java.util.regex.Matcher.quoteReplacement(mentionFormat.replace("%player%", name)));
                    try {
                        org.bukkit.Sound sound = org.bukkit.Sound.valueOf(plugin.getConfig().getString("chat-settings.mentions.sound", "ENTITY_EXPERIENCE_ORB_PICKUP"));
                        float vol = (float) plugin.getConfig().getDouble("chat-settings.mentions.volume", 1.0);
                        float pitch = (float) plugin.getConfig().getDouble("chat-settings.mentions.pitch", 1.5);
                        target.playSound(target.getLocation(), sound, vol, pitch);
                    } catch (Exception ignored) {}
                }
            }
        }
        return message;
    }

    // ──────────────────────────────────────────────────────
    //  AUTO-LINKS
    // ──────────────────────────────────────────────────────
    private String processAutoLinks(String message) {
        java.util.regex.Matcher urlMatcher = URL_PATTERN.matcher(message);
        StringBuffer sb = new StringBuffer();
        // Only add hover/click on Paper servers — on Spigot they show as raw tags
        boolean isPaper = ColorUtils.isPaperAdventureAvailable();
        String linkHover = plugin.getConfig().getString("chat-settings.auto-links.hover-text", "<yellow>Click to visit the link!");
        while (urlMatcher.find()) {
            String url = urlMatcher.group();
            String clickUrl = url.startsWith("http") ? url : "http://" + url;
            String replacement;
            if (isPaper) {
                replacement = "<click:open_url:'" + clickUrl + "'><hover:show_text:'" + linkHover + "'><underlined>" + url + "</underlined></hover></click>";
            } else {
                replacement = "<underlined><blue>" + url + "</blue></underlined>";
            }
            urlMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        urlMatcher.appendTail(sb);
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────
    //  INTERACTIVE NAME (hover/click on player name)
    // ──────────────────────────────────────────────────────
    private String applyInteractiveName(String format, Player player) {
        List<String> hoverLines = plugin.getConfig().getStringList("chat-settings.interactive-name.hover-text");
        // Resolve placeholders in hover lines (so %vault_prefix%, %player_ping% etc. work)
        String playerName = player.getName();
        String clickValue = plugin.getConfig().getString("chat-settings.interactive-name.click-value", "/msg %player_name% ");

        StringBuilder hoverBuilder = new StringBuilder();
        for (String line : hoverLines) {
            // Replace %player_name% in hover/click text with the actual name
            String resolved = line.replace("%player_name%", playerName);
            resolved = ColorUtils.applyPapi(player, resolved);
            if (hoverBuilder.length() > 0) hoverBuilder.append("<newline>");
            hoverBuilder.append(resolved);
        }
        String hoverText = hoverBuilder.toString();

        // Resolve %player_name% in click-value too
        String resolvedClickValue = clickValue.replace("%player_name%", playerName);

        String clickAction = plugin.getConfig().getString("chat-settings.interactive-name.click-action", "SUGGEST_COMMAND");
        // Use a unique placeholder that won't conflict with the normal %player_name% replacement.
        // buildFinalComponent() will NOT replace this — we use the actual name directly.
        String wrappedName = "<hover:show_text:'" + hoverText + "'><click:" + clickAction.toLowerCase() + ":'" + resolvedClickValue + "'>" + playerName + "</click></hover>";
        return format.replace("%player_name%", wrappedName);
    }

    // ──────────────────────────────────────────────────────
    //  FINAL COMPONENT BUILDER
    // ──────────────────────────────────────────────────────
    /**
     * Builds the final chat Component by:
     *   1. Replacing %player_name% in the format string
     *   2. Applying PlaceholderAPI for any %placeholder% patterns
     *   3. Converting any legacy/hex codes to MiniMessage format
     *   4. Injecting the message as a safe Component (plain or MiniMessage-parsed)
     *   5. Deserializing via MiniMessage to produce the final Component
     */
    private Component buildFinalComponent(Player player, String format, String message, boolean parseMiniInMessage) {
        // Replace built-in placeholders
        String playerName = player != null ? player.getName() : "Console";
        format = format.replace("%player_name%", playerName);

        // Apply PlaceholderAPI (if available) for remaining %placeholder% patterns
        format = ColorUtils.applyPapi(player, format);

        // Convert any legacy &-codes and hex to MiniMessage tags
        format = ColorUtils.convertLegacyAndHex(format);

        // Replace {message} with MiniMessage placeholder
        format = format.replace("{message}", "<message>");

        // Inject message: either as plain text (safe) or parsed MiniMessage
        if (parseMiniInMessage) {
            // Protect & inside MiniMessage tag attributes (e.g. URLs in <click:open_url:'...'>)
            // from being converted to color codes by convertLegacyAndHex.
            // We use a null char placeholder since it never appears in chat messages.
            String protectedMsg = protectAmpersandsInAttributes(message);
            String parsedMsg = ColorUtils.convertLegacyAndHex(protectedMsg);
            parsedMsg = parsedMsg.replace('\u0000', '&');
            Component messageComponent = miniMessage.deserialize(parsedMsg);
            return miniMessage.deserialize(format, Placeholder.component("message", messageComponent));
        } else {
            return miniMessage.deserialize(format, Placeholder.component("message", Component.text(message)));
        }
    }

    /**
     * Replaces '&' with '\u0000' inside MiniMessage tag attribute values
     * (between single quotes) so convertLegacyAndHex doesn't corrupt URLs.
     * Example: <click:open_url:'http://example.com?a=1&b=2'>
     *          becomes: <click:open_url:'http://example.com?a=1\u0000b=2'>
     */
    private String protectAmpersandsInAttributes(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        boolean inAttribute = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'' && i > 0) {
                // Check if we're right after a ':' (attribute value start)
                // Look backwards for a colon that's part of a tag
                boolean afterColon = false;
                for (int j = i - 1; j >= 0; j--) {
                    char prev = text.charAt(j);
                    if (prev == ':') { afterColon = true; break; }
                    if (prev == '<' || prev == ' ') break; // start of tag or non-attribute
                }
                if (afterColon && !inAttribute) {
                    inAttribute = true;
                    sb.append(c);
                    continue;
                }
                if (inAttribute) {
                    inAttribute = false;
                    sb.append(c);
                    continue;
                }
            }
            if (inAttribute && c == '&') {
                sb.append('\u0000'); // protect from convertLegacyAndHex
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────
    //  HELPERS
    // ──────────────────────────────────────────────────────
    private String censorPartial(String word) {
        if (word.length() <= 2) return "*".repeat(word.length());
        StringBuilder sb = new StringBuilder();
        sb.append(word.charAt(0));
        for (int i = 1; i < word.length() - 1; i++) {
            sb.append('*');
        }
        sb.append(word.charAt(word.length() - 1));
        return sb.toString();
    }

    private boolean canUseTag(Player player, String tagType) {
        if (player.hasPermission(plugin.getConfig().getString("general-permissions.all-tags", "xchat.tags.all"))) {
            return true;
        }
        String basePath = "chat-settings.interactive-tags." + tagType + ".";
        boolean enabled = plugin.getConfig().getBoolean(basePath + "enabled", false);
        if (!enabled) return false;
        String perm = plugin.getConfig().getString(basePath + "permission", "");
        return perm.isEmpty() || player.hasPermission(perm);
    }
}
