package com.fabian.xchat.listeners;

import com.fabian.xchat.XChat;
import com.fabian.xchat.utils.ColorUtils;
import com.fabian.xchat.utils.DebugLogger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
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
import java.util.regex.Matcher;
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

    // Prefix for placeholders injected by interactive features (links, mentions, item tags)
    // Using a unique prefix avoids conflicts with player text or other MiniMessage tags.
    private static final String IPC_PREFIX = "xchat_";

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

        // Step 4.6: Convert authorized legacy/hex codes to MiniMessage BEFORE system tags
        // (custom tags, mentions, auto-links) are added. This prevents convertLegacyAndHex
        // from corrupting system-generated MiniMessage tags or & inside URLs.
        if (allowLegacy || allowHex) {
            rawMessage = protectAmpersandsInUrls(rawMessage);
            rawMessage = ColorUtils.convertLegacyAndHex(rawMessage);
            rawMessage = rawMessage.replace('\u0000', '&');
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
        // TagsManager returns the modified message + Component placeholders for interactive tags (item hover)
        List<TagResolver> interactiveResolvers = new ArrayList<>();
        if (plugin.getConfig().getBoolean("chat-settings.custom-tags.enabled", true)) {
            rawMessage = plugin.getTagsManager().processTags(player, rawMessage, interactiveResolvers);
        }

        // Save pre-mention raw text for cross-server mention sound detection (plain player names)
        String preMentionRaw = rawMessage;

        // Step 7: Process mentions — build mention Components via Adventure API directly
        if (plugin.getConfig().getBoolean("chat-settings.mentions.enabled")) {
            rawMessage = processMentions(player, rawMessage, interactiveResolvers);
        }

        // Step 8: Auto-Links — build link Components via Adventure API directly
        if (plugin.getConfig().getBoolean("chat-settings.auto-links.enabled", true)) {
            rawMessage = processAutoLinks(rawMessage, interactiveResolvers);
        }

        // Step 9: Apply interactive name hover/click to format string
        if (plugin.getConfig().getBoolean("chat-settings.interactive-name.enabled")) {
            formatString = applyInteractiveName(formatString, player);
        }

        // Step 10: Build final Component via MiniMessage deserialization
        Component finalComponent = buildFinalComponent(player, formatString, rawMessage, allowMini, interactiveResolvers);

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

        // ── Cross-Server Payload ──
        // JSON Component (preserves ALL data: colors, hover, click, gradients) + legacy fallback + pre-mention text
        String jsonComponent = null;
        try {
            jsonComponent = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().serialize(finalComponent);
        } catch (Throwable t) {
            DebugLogger.debug("ChatListener", "Failed to serialize component to JSON for cross-server", t);
        }

        // Publish to cross-server (Redis pub/sub)
        if (plugin.getMessagingService() != null && plugin.getMessagingService().isEnabled()) {
            // Payload format (3 lines, newline-delimited):
            //   Line 1: legacy formatted message (§-coded, Spigot/ultimate fallback)
            //   Line 2: JSON serialized Component (full hover/click/format preservation) or empty
            //   Line 3: pre-mention plain text (for cross-server mention sound detection)
            String crossServerPayload = legacyOutput
                    + "\n" + (jsonComponent != null ? jsonComponent : "")
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
    //  MENTIONS (Component-based — bypasses MiniMessage string parsing)
    // ──────────────────────────────────────────────────────
    /**
     * Processes player mentions in the message.
     * Instead of embedding MiniMessage strings (which may fail to parse), builds mention
     * Components directly via Adventure API and injects them as MiniMessage placeholders.
     *
     * @param player    the sender
     * @param message   the chat message (may be modified — mentions replaced with placeholders)
     * @param resolvers list to which mention Component placeholders are added
     * @return the modified message with mention text replaced by placeholders
     */
    private String processMentions(Player player, String message, List<TagResolver> resolvers) {
        boolean requireSymbol = plugin.getConfig().getBoolean("chat-settings.mentions.require-symbol", false);
        String symbol = plugin.getConfig().getString("chat-settings.mentions.symbol", "@");
        String mentionFormat = plugin.getConfig().getString("chat-settings.mentions.format", "<yellow>%player%</yellow>");
        // Ensure mention format is MiniMessage (convert & codes if config uses them)
        mentionFormat = ColorUtils.convertLegacyAndHex(mentionFormat);

        List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
        // Sort by display name length (descending) so longer matches take priority
        // Display name may include prefix (e.g., "[Admin] Fabian")
        onlinePlayers.sort((p1, p2) -> Integer.compare(
                org.bukkit.ChatColor.stripColor(p2.getDisplayName()).length(),
                org.bukkit.ChatColor.stripColor(p1.getDisplayName()).length()));

        int mentionIndex = 0;
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
            Matcher matcher = pattern.matcher(message);
            if (matcher.find()) {
                // Build the mention Component directly via Adventure API
                Component mentionComp = buildMentionComponent(mentionFormat, matchText);
                String placeholderName = IPC_PREFIX + "m" + (mentionIndex++);
                resolvers.add(Placeholder.component(placeholderName, mentionComp));
                message = matcher.replaceAll(Matcher.quoteReplacement("<" + placeholderName + ">"));

                // Play mention sound for the target
                playMentionSound(target);
                continue;
            }

            // Also try just the player name (without prefix)
            if (!matchText.equals(name)) {
                String nameTrigger = requireSymbol ? Pattern.quote(symbol + name) : "\\b" + Pattern.quote(name) + "\\b";
                Pattern namePattern = Pattern.compile(nameTrigger, Pattern.CASE_INSENSITIVE);
                Matcher nameMatcher = namePattern.matcher(message);
                if (nameMatcher.find()) {
                    Component mentionComp = buildMentionComponent(mentionFormat, name);
                    String placeholderName = IPC_PREFIX + "m" + (mentionIndex++);
                    resolvers.add(Placeholder.component(placeholderName, mentionComp));
                    message = nameMatcher.replaceAll(Matcher.quoteReplacement("<" + placeholderName + ">"));

                    playMentionSound(target);
                }
            }
        }
        return message;
    }

    /**
     * Builds a mention Component by parsing the mention format with MiniMessage
     * and replacing %player% with the actual name. The format is a simple
     * color/style string (no complex nested tags), so MiniMessage parsing is safe here.
     */
    private Component buildMentionComponent(String mentionFormat, String playerName) {
        String resolved = mentionFormat.replace("%player%", playerName);
        try {
            return miniMessage.deserialize(resolved);
        } catch (Exception e) {
            // Fallback: plain colored name
            DebugLogger.debug("ChatListener", "Failed to parse mention format, using fallback: " + resolved, e);
            return Component.text(playerName, net.kyori.adventure.text.format.NamedTextColor.YELLOW);
        }
    }

    /**
     * Plays the mention sound for a target player.
     */
    private void playMentionSound(Player target) {
        try {
            org.bukkit.Sound sound = org.bukkit.Sound.valueOf(plugin.getConfig().getString("chat-settings.mentions.sound", "ENTITY_EXPERIENCE_ORB_PICKUP"));
            float vol = (float) plugin.getConfig().getDouble("chat-settings.mentions.volume", 1.0);
            float pitch = (float) plugin.getConfig().getDouble("chat-settings.mentions.pitch", 1.5);
            target.playSound(target.getLocation(), sound, vol, pitch);
        } catch (Exception ignored) {}
    }

    // ──────────────────────────────────────────────────────
    //  AUTO-LINKS (Component-based — bypasses MiniMessage string parsing)
    // ──────────────────────────────────────────────────────
    /**
     * Processes URLs in the message and makes them clickable.
     * Instead of embedding MiniMessage click/hover strings (which may fail to parse),
     * builds link Components directly via Adventure API and injects them as placeholders.
     *
     * @param message   the chat message (modified — URLs replaced with placeholders)
     * @param resolvers list to which link Component placeholders are added
     * @return the modified message with URLs replaced by placeholders
     */
    private String processAutoLinks(String message, List<TagResolver> resolvers) {
        Matcher urlMatcher = URL_PATTERN.matcher(message);
        StringBuffer sb = new StringBuffer();
        boolean isPaper = ColorUtils.isPaperAdventureAvailable();
        int linkIndex = 0;

        // Build hover Component from config (parse once, reuse for all links)
        Component hoverComp = buildLinkHoverComponent();

        while (urlMatcher.find()) {
            String url = urlMatcher.group();
            String clickUrl = url.startsWith("http") ? url : "http://" + url;

            // Build link Component directly via Adventure API
            Component linkComp;
            if (isPaper && hoverComp != null) {
                linkComp = Component.text(url)
                        .style(Style.style()
                                .decoration(TextDecoration.UNDERLINED, TextDecoration.State.TRUE)
                                .clickEvent(ClickEvent.openUrl(clickUrl))
                                .hoverEvent(HoverEvent.showText(hoverComp)));
            } else if (isPaper) {
                // Paper but no hover configured
                linkComp = Component.text(url)
                        .style(Style.style()
                                .decoration(TextDecoration.UNDERLINED, TextDecoration.State.TRUE)
                                .clickEvent(ClickEvent.openUrl(clickUrl)));
            } else {
                // Spigot: no click/hover support, just underline and color
                linkComp = Component.text(url)
                        .style(Style.style()
                                .decoration(TextDecoration.UNDERLINED, TextDecoration.State.TRUE)
                                .color(net.kyori.adventure.text.format.NamedTextColor.BLUE));
            }

            String placeholderName = IPC_PREFIX + "l" + (linkIndex++);
            resolvers.add(Placeholder.component(placeholderName, linkComp));
            urlMatcher.appendReplacement(sb, Matcher.quoteReplacement("<" + placeholderName + ">"));
        }
        urlMatcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Builds the hover Component for links from config.
     * Parsed once and reused for all links in the message.
     */
    private Component buildLinkHoverComponent() {
        String linkHoverStr = plugin.getConfig().getString("chat-settings.auto-links.hover-text", "");
        if (linkHoverStr == null || linkHoverStr.isEmpty()) return null;
        linkHoverStr = ColorUtils.convertLegacyAndHex(linkHoverStr);
        try {
            return miniMessage.deserialize(linkHoverStr);
        } catch (Exception e) {
            DebugLogger.debug("ChatListener", "Failed to parse link hover text, using plain text", e);
            return Component.text("Click to visit the link!");
        }
    }

    // ──────────────────────────────────────────────────────
    //  INTERACTIVE NAME (hover/click on player name)
    // ──────────────────────────────────────────────────────
    private String applyInteractiveName(String format, Player player) {
        List<String> hoverLines = plugin.getConfig().getStringList("chat-settings.interactive-name.hover-text");
        // Resolve placeholders in hover lines (so %vault_prefix%, %player_ping% etc. work)
        String playerName = player.getName();
        String clickValue = plugin.getConfig().getString("chat-settings.interactive-name.click-value", "/msg %player_name% ");

        // Build hover Component directly via Adventure API
        Component hoverComp = buildInteractiveNameHover(player, hoverLines);

        // Build click event
        String resolvedClickValue = clickValue.replace("%player_name%", playerName);
        String clickAction = plugin.getConfig().getString("chat-settings.interactive-name.click-action", "SUGGEST_COMMAND");

        ClickEvent clickEvent;
        switch (clickAction.toUpperCase()) {
            case "RUN_COMMAND":
                clickEvent = ClickEvent.runCommand(resolvedClickValue);
                break;
            case "OPEN_URL":
                clickEvent = ClickEvent.openUrl(resolvedClickValue);
                break;
            case "COPY_TO_CLIPBOARD":
                clickEvent = ClickEvent.copyToClipboard(resolvedClickValue);
                break;
            default: // SUGGEST_COMMAND
                clickEvent = ClickEvent.suggestCommand(resolvedClickValue);
                break;
        }

        // Build the name Component with hover and click
        Component nameComp = Component.text(playerName)
                .style(Style.style()
                        .hoverEvent(hoverComp != null ? HoverEvent.showText(hoverComp) : null)
                        .clickEvent(clickEvent));

        // Replace %player_name% in format with a MiniMessage placeholder
        // We use a unique placeholder name and pass the Component via TagResolver
        String placeholderName = IPC_PREFIX + "name";
        // Store the name component for later use in buildFinalComponent
        this._interactiveNameResolver = Placeholder.component(placeholderName, nameComp);
        return format.replace("%player_name%", "<" + placeholderName + ">");
    }

    // Transient field to pass the interactive name resolver to buildFinalComponent
    private TagResolver _interactiveNameResolver = null;

    /**
     * Builds the hover Component for interactive player names from config lines.
     */
    private Component buildInteractiveNameHover(Player player, List<String> hoverLines) {
        if (hoverLines == null || hoverLines.isEmpty()) return null;

        // Parse all hover lines as MiniMessage and combine with newlines
        List<Component> lines = new ArrayList<>();
        for (String line : hoverLines) {
            String resolved = line.replace("%player_name%", player.getName());
            resolved = ColorUtils.applyPapi(player, resolved);
            resolved = ColorUtils.convertLegacyAndHex(resolved);
            try {
                lines.add(miniMessage.deserialize(resolved));
            } catch (Exception e) {
                lines.add(Component.text(resolved));
            }
        }

        if (lines.size() == 1) return lines.get(0);

        // Combine multiple lines with newline
        net.kyori.adventure.text.TextComponent.Builder builder = Component.text();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) builder.append(Component.newline());
            builder.append(lines.get(i));
        }
        return builder.asComponent();
    }

    // ──────────────────────────────────────────────────────
    //  FINAL COMPONENT BUILDER
    // ──────────────────────────────────────────────────────
    /**
     * Builds the final chat Component by:
     *   1. Replacing %player_name% in the format string
     *   2. Applying PlaceholderAPI for any %placeholder% patterns
     *   3. Converting any legacy/hex codes to MiniMessage format
     *   4. Injecting the message and all interactive Component placeholders
     *   5. Deserializing via MiniMessage to produce the final Component
     *
     * @param extraResolvers additional TagResolvers for links, mentions, item tags, etc.
     */
    private Component buildFinalComponent(Player player, String format, String message,
                                          boolean parseMiniInMessage, List<TagResolver> extraResolvers) {
        // Replace built-in placeholders (only if not already replaced by interactive name)
        if (_interactiveNameResolver == null) {
            String playerName = player != null ? player.getName() : "Console";
            format = format.replace("%player_name%", playerName);
        }

        // Apply PlaceholderAPI (if available) for remaining %placeholder% patterns
        format = ColorUtils.applyPapi(player, format);

        // Convert any legacy &-codes and hex to MiniMessage tags in the FORMAT
        format = ColorUtils.convertLegacyAndHex(format);

        // Replace {message} with MiniMessage placeholder
        format = format.replace("{message}", "<message>");

        // The message now contains:
        // - Player text with legacy/hex already converted (step 4.6 in pipeline)
        // - System-generated MiniMessage placeholders for links, mentions, item tags
        // - Safe MiniMessage color/style tags
        // Parse directly — do NOT run convertLegacyAndHex here as it would
        // corrupt system tags and URL query parameters.
        Component messageComponent;
        try {
            messageComponent = miniMessage.deserialize(message);
        } catch (Exception e) {
            DebugLogger.debug("ChatListener", "Failed to parse message MiniMessage, using plain text", e);
            messageComponent = Component.text(message);
        }

        // Collect ALL TagResolvers: message placeholder + interactive elements + name
        List<TagResolver> allResolvers = new ArrayList<>();
        allResolvers.add(Placeholder.component("message", messageComponent));
        if (extraResolvers != null) {
            allResolvers.addAll(extraResolvers);
        }
        if (_interactiveNameResolver != null) {
            allResolvers.add(_interactiveNameResolver);
            _interactiveNameResolver = null; // Reset for next message
        }

        try {
            return miniMessage.deserialize(format, allResolvers.toArray(new TagResolver[0]));
        } catch (Exception e) {
            DebugLogger.debug("ChatListener", "Failed to parse format with resolvers, attempting fallback", e);
            // Fallback: return just the message component
            return messageComponent;
        }
    }

    /**
     * Protects & characters inside URL patterns from being converted to color codes
     * by convertLegacyAndHex. Uses \u0000 as a temporary placeholder that is
     * restored after conversion.
     * Example: https://example.com?a=1&b=2
     *          becomes: https://example.com?a=1\u0000b=2
     */
    private String protectAmpersandsInUrls(String text) {
        Matcher urlMatcher = URL_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (urlMatcher.find()) {
            String url = urlMatcher.group();
            String protectedUrl = url.replace('&', '\u0000');
            urlMatcher.appendReplacement(sb, Matcher.quoteReplacement(protectedUrl));
        }
        urlMatcher.appendTail(sb);
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