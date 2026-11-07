package com.fabian.xchat.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * X-Proyects Unified Color System (canonical implementation).
 * <p>
 * Identical across every X-Proyects plugin — only the package changes.
 * Supports: legacy & codes, &#RRGGBB hex, Spigot &x hex, MiniMessage tags,
 * PlaceholderAPI (via reflection), and Paper Adventure detection.
 * <p>
 * X-Chat appends its plugin-specific extensions (%prefix% token and
 * unresolved-placeholder filtering) at the bottom of this class.
 */
public final class ColorUtils {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacySection();
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final Pattern SPIGOT_HEX_PATTERN = Pattern.compile("(?i)&x(&[A-Fa-f0-9]){6}");

    // PlaceholderAPI reflection
    private static boolean papiAvailable = false;
    private static Method setPlaceholdersMethod;

    // Paper Adventure detection
    private static boolean paperAdventureAvailable = false;

    static {
        try {
            Class<?> papiClass = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            setPlaceholdersMethod = papiClass.getMethod("setPlaceholders", org.bukkit.OfflinePlayer.class, String.class);
            papiAvailable = true;
        } catch (Exception ignored) {
            papiAvailable = false;
        }
        try {
            Class.forName("io.papermc.paper.adventure.PaperAudiences");
            paperAdventureAvailable = true;
        } catch (Exception e) {
            paperAdventureAvailable = false;
        }
    }

    private ColorUtils() {
        // Static utility class — no instances
    }

    // ──────────────────────────────────────────────────────
    //  CORE TRANSLATION
    // ──────────────────────────────────────────────────────

    /**
     * Translates color codes in text: legacy & codes, hex and MiniMessage tags.
     * Returns a legacy §-formatted string for maximum compatibility.
     */
    public static String translateColors(String text) {
        if (text == null || text.isEmpty()) return text;
        text = convertLegacyAndHex(text);
        try {
            Component component = MINI_MESSAGE.deserialize(text);
            return LEGACY_SERIALIZER.serialize(component);
        } catch (Exception e) {
            // Fallback: plain legacy translation
            return ChatColor.translateAlternateColorCodes('&', text);
        }
    }

    /**
     * Alias of {@link #translateColors(String)} (kept for command-side call sites).
     */
    public static String translate(String input) {
        return translateColors(input);
    }

    /**
     * Translates every string in a list (lore, help lines, etc.).
     */
    public static List<String> translate(List<String> input) {
        if (input == null) return new ArrayList<>();
        List<String> out = new ArrayList<>(input.size());
        for (String s : input) out.add(translateColors(s));
        return out;
    }

    /**
     * Strips all § color codes from a string.
     */
    public static String stripColor(String input) {
        if (input == null) return null;
        return ChatColor.stripColor(input);
    }

    // ──────────────────────────────────────────────────────
    //  COMPONENT-BASED FORMATTING
    // ──────────────────────────────────────────────────────

    /**
     * Formats text with PAPI placeholders and MiniMessage colors, returns Component.
     */
    public static Component format(Player player, String text) {
        if (text == null || text.isEmpty()) return Component.empty();
        text = applyPluginPrefix(text); // X-Chat ext: %prefix% token support
        text = applyPapi(player, text);
        text = convertLegacyAndHex(text);
        try {
            return MINI_MESSAGE.deserialize(text);
        } catch (Exception e) {
            return Component.text(text);
        }
    }

    /**
     * Legacy-friendly variant of {@link #format(Player, String)} returning a §-string.
     */
    public static String formatLegacy(Player player, String text) {
        if (text == null || text.isEmpty()) return "";
        return translateColors(applyPapi(player, text));
    }

    /**
     * Parses a MiniMessage string into a Component (without PAPI application).
     */
    public static Component component(String input) {
        if (input == null || input.isEmpty()) return Component.empty();
        return MINI_MESSAGE.deserialize(convertLegacyAndHex(input));
    }

    /**
     * Parses a list of MiniMessage strings into Components.
     */
    public static List<Component> component(List<String> input) {
        if (input == null) return new ArrayList<>();
        List<Component> out = new ArrayList<>(input.size());
        for (String s : input) out.add(component(s));
        return out;
    }

    /**
     * Alias of {@link #component(String)}.
     */
    public static Component miniMessage(String input) {
        return component(input);
    }

    // ──────────────────────────────────────────────────────
    //  CONVERSION HELPERS
    // ──────────────────────────────────────────────────────

    /**
     * Converts legacy &-codes and hex formats to MiniMessage tags.
     */
    public static String convertLegacyAndHex(String text) {
        if (text == null || text.isEmpty()) return text;

        // 1. Convert § to &
        text = text.replace('\u00a7', '&');

        // 2. Fix MiniMessage mixed with legacy: <color:&#hex> → <#hex>
        text = text.replaceAll("(?i)<color:&#([A-Fa-f0-9]{6})>", "<#$1>");

        // 3. Convert Spigot Hex (&x&R&R&G&G&B&B) to MiniMessage
        Matcher spigotMatcher = SPIGOT_HEX_PATTERN.matcher(text);
        StringBuilder spigotBuilder = new StringBuilder();
        while (spigotMatcher.find()) {
            String hex = spigotMatcher.group().replaceAll("[&xX]", "");
            spigotMatcher.appendReplacement(spigotBuilder, "<#" + hex + ">");
        }
        spigotMatcher.appendTail(spigotBuilder);
        text = spigotBuilder.toString();

        // 4. Convert legacy hex (&#FF0000) to MiniMessage
        Matcher hexMatcher = HEX_PATTERN.matcher(text);
        StringBuilder builder = new StringBuilder();
        while (hexMatcher.find()) {
            hexMatcher.appendReplacement(builder, "<#" + hexMatcher.group(1) + ">");
        }
        hexMatcher.appendTail(builder);
        text = builder.toString();

        // 5. Convert legacy & codes to MiniMessage tags
        text = text.replace("&0", "<black>")
                   .replace("&1", "<dark_blue>")
                   .replace("&2", "<dark_green>")
                   .replace("&3", "<dark_aqua>")
                   .replace("&4", "<dark_red>")
                   .replace("&5", "<dark_purple>")
                   .replace("&6", "<gold>")
                   .replace("&7", "<gray>")
                   .replace("&8", "<dark_gray>")
                   .replace("&9", "<blue>")
                   .replace("&a", "<green>")
                   .replace("&b", "<aqua>")
                   .replace("&c", "<red>")
                   .replace("&d", "<light_purple>")
                   .replace("&e", "<yellow>")
                   .replace("&f", "<white>")
                   .replace("&l", "<bold>")
                   .replace("&m", "<strikethrough>")
                   .replace("&n", "<underlined>")
                   .replace("&o", "<italic>")
                   .replace("&r", "<reset>");

        // 6. HTML shorthands
        text = text.replace("<b>", "<bold>").replace("</b>", "</bold>")
                   .replace("<i>", "<italic>").replace("</i>", "</italic>")
                   .replace("<u>", "<underlined>").replace("</u>", "</underlined>")
                   .replace("<s>", "<strikethrough>").replace("</s>", "</strikethrough>");

        return text;
    }

    /**
     * Applies PlaceholderAPI if available.
     */
    public static String applyPapi(Player player, String text) {
        if (text == null || text.isEmpty()) return text;
        if (player != null && papiAvailable && setPlaceholdersMethod != null) {
            try {
                text = (String) setPlaceholdersMethod.invoke(null, player, text);
            } catch (Exception ignored) {}
        }
        return stripUnresolvedPlaceholders(text); // X-Chat ext: unresolved %placeholder% filter
    }

    /**
     * Converts a Component to a legacy §-formatted string.
     */
    public static String toLegacyString(Component component) {
        if (component == null) return "";
        return LEGACY_SERIALIZER.serialize(component);
    }

    // ──────────────────────────────────────────────────────
    //  SENDING
    // ──────────────────────────────────────────────────────

    /**
     * Sends a Component to a CommandSender.
     * On Paper: native Adventure. On Spigot: fallback to legacy string.
     */
    public static void sendComponent(CommandSender sender, Component component) {
        if (sender == null || component == null) return;
        if (paperAdventureAvailable) {
            try {
                java.lang.reflect.Method sendMessageMethod = sender.getClass().getMethod("sendMessage", Component.class);
                sendMessageMethod.invoke(sender, component);
                return;
            } catch (Exception ignored) {
                // Fall back to legacy for Spigot
            }
        }
        if (sender instanceof Player) {
            sender.sendMessage(toLegacyString(component));
        } else {
            sender.sendMessage(ChatColor.stripColor(toLegacyString(component)));
        }
    }

    /**
     * Overload accepting a pre-formatted legacy string.
     */
    public static void sendComponent(CommandSender sender, String message) {
        if (sender == null || message == null || message.isEmpty()) return;
        if (sender instanceof Player) {
            sender.sendMessage(message);
        } else {
            sender.sendMessage(ChatColor.stripColor(message));
        }
    }

    /**
     * Sends a pre-formatted string to a CommandSender.
     * Strips § color codes for non-Player senders (console) to avoid
     * garbled characters on Windows CP437/CP850.
     */
    public static void send(CommandSender sender, String message) {
        if (sender == null || message == null || message.isEmpty()) return;
        if (sender instanceof Player) {
            sender.sendMessage(message);
        } else {
            sender.sendMessage(ChatColor.stripColor(message));
        }
    }

    /**
     * Broadcasts a legacy-formatted message to all online players + console.
     * The context player (may be null) receives it as-is as well.
     */
    public static void broadcastLegacy(Player context, String text) {
        if (text == null || text.isEmpty()) return;
        String formatted = translateColors(text);
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            p.sendMessage(formatted);
        }
        org.bukkit.Bukkit.getConsoleSender().sendMessage(ChatColor.stripColor(formatted));
    }

    // ──────────────────────────────────────────────────────
    //  FEATURE DETECTION
    // ──────────────────────────────────────────────────────

    public static boolean isPAPIAvailable() { return papiAvailable; }
    public static boolean isPaperAdventureAvailable() { return paperAdventureAvailable; }

    // ── X-Chat extensions (plugin-specific) ───────────────
    //
    // X-Chat layers two config-driven behaviors on top of the canonical
    // color system (both read from config.yml `settings.*`):
    //   - settings.prefix                  → %prefix% token replacement in format()
    //   - settings.unresolved-placeholders → "remove"/"keep" filter in applyPapi()
    // init(FileConfiguration) is called from XChat.onEnable() / reinitCrossServer()
    // and reload(FileConfiguration) from ConfigManager.reloadConfiguration().

    private static final Pattern UNRESOLVED_PAPI_PATTERN = Pattern.compile("%[a-zA-Z0-9_.]+%");

    // Built-in placeholders replaced by the plugin itself (never removed by the unresolved filter)
    private static final Pattern BUILTIN_PLACEHOLDER_PATTERN = Pattern.compile("%player_name%");

    // Unresolved placeholders behavior: "remove" or "keep"
    private static String unresolvedBehavior = "remove";

    // Global plugin prefix — replaced automatically in every message via %prefix%
    private static String pluginPrefix = "";

    /**
     * Initializes the X-Chat color settings from plugin config.
     * Called once on enable (and again when the config is reloaded).
     */
    public static void init(FileConfiguration config) {
        unresolvedBehavior = config.getString("settings.unresolved-placeholders", "remove");
        pluginPrefix = config.getString("settings.prefix", "");
        if (pluginPrefix == null) pluginPrefix = "";
    }

    /**
     * Refreshes the X-Chat color settings from config (for /xchat reload).
     */
    public static void reload(FileConfiguration config) {
        init(config);
    }

    /**
     * Returns the current unresolved-placeholder behavior: "remove" or "keep".
     */
    public static String getUnresolvedBehavior() {
        return unresolvedBehavior;
    }

    /**
     * Replaces the %prefix% token with the configured plugin prefix.
     * Hook called from the canonical {@link #format(Player, String)}.
     */
    private static String applyPluginPrefix(String text) {
        if (text == null) return text;
        return text.replace("%prefix%", pluginPrefix);
    }

    /**
     * Removes unresolved %placeholder% tokens when the configured behavior is
     * "remove" (built-in placeholders such as %player_name% are preserved).
     * Hook called from the canonical {@link #applyPapi(Player, String)}.
     */
    private static String stripUnresolvedPlaceholders(String text) {
        if (text == null || text.isEmpty()) return text;
        if (!"remove".equalsIgnoreCase(unresolvedBehavior)) return text;
        return UNRESOLVED_PAPI_PATTERN.matcher(text).replaceAll(match ->
                BUILTIN_PLACEHOLDER_PATTERN.matcher(match.group()).matches() ? match.group() : "");
    }
}
