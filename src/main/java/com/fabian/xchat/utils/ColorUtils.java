package com.fabian.xchat.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import com.fabian.xchat.utils.DebugLogger;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ColorUtils {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacySection();
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final Pattern UNRESOLVED_PAPI_PATTERN = Pattern.compile("%[a-zA-Z0-9_.]+%");

    // Built-in placeholders replaced by the plugin itself (never removed by unresolved filter)
    private static final Pattern BUILTIN_PLACEHOLDER_PATTERN = Pattern.compile("%player_name%");

    // Reflection-based PlaceholderAPI integration (safe when not installed)
    private static boolean papiAvailable = false;
    private static Method setPlaceholdersMethod;

    // Unresolved placeholders behavior: "remove" or "keep"
    private static String unresolvedBehavior = "remove";

    // Global plugin prefix — replaced automatically in every message
    private static String pluginPrefix = "";

    // Paper Adventure detection — lazy, cached after first check
    // Previously used Class.forName("io.papermc.paper.adventure.PaperAudiences") which
    // does NOT exist in Paper 1.16.5 and ran in a static block before Libby could load
    // Adventure, causing false negatives on real Paper servers.
    private static volatile Boolean paperAdventureAvailable = null;

    static {
        try {
            Class<?> papiClass = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            setPlaceholdersMethod = papiClass.getMethod("setPlaceholders", org.bukkit.OfflinePlayer.class, String.class);
            papiAvailable = true;
        } catch (Exception ignored) {
            papiAvailable = false;
        }
    }

    /**
     * Initializes ColorUtils with plugin config. Should be called once on enable.
     */
    public static void init(FileConfiguration config) {
        unresolvedBehavior = config.getString("settings.unresolved-placeholders", "remove");
        pluginPrefix = config.getString("settings.prefix", "");
    }

    /**
     * Refreshes the unresolved placeholder behavior from config (for /xchat reload).
     */
    public static void reload(FileConfiguration config) {
        init(config);
    }

    // ════════════════════════════════════════════════════════
    //  PUBLIC API - Called from ChatListener and other classes
    // ════════════════════════════════════════════════════════

    /**
     * Formats a string with PlaceholderAPI + color codes and returns a Component.
     * Used for general plugin messages (not chat).
     */
    public static Component format(Player player, String text) {
        if (text == null || text.isEmpty()) return Component.empty();
        DebugLogger.debug("ColorUtils", "Formatting text (" + text.length() + " chars)");
        text = text.replace("%prefix%", pluginPrefix);
        text = applyPapi(player, text);
        text = convertLegacyAndHex(text);
        return MINI_MESSAGE.deserialize(text);
    }

    /**
     * Converts legacy &-codes and hex formats to MiniMessage tags.
     * This is the single point of conversion for all text before MiniMessage deserialization.
     */
    public static String convertLegacyAndHex(String text) {
        if (text == null || text.isEmpty()) return text;

        // 1. Convert native Minecraft '§' to '&'
        text = text.replace('\u00a7', '&');

        // 2. Fix for users mixing MiniMessage with Legacy (e.g. <color:&#11D24C>)
        text = text.replaceAll("(?i)<color:&#([A-Fa-f0-9]{6})>", "<#$1>");

        // 3. Convert BungeeCord/Spigot Hex (&x&R&R&G&G&B&B) to MiniMessage (<#RRGGBB>)
        Matcher spigotMatcher = SPIGOT_HEX_PATTERN.matcher(text);
        StringBuilder spigotBuilder = new StringBuilder();
        while (spigotMatcher.find()) {
            String hex = spigotMatcher.group().replaceAll("[&xX]", "");
            spigotMatcher.appendReplacement(spigotBuilder, "<#" + hex + ">");
        }
        spigotMatcher.appendTail(spigotBuilder);
        text = spigotBuilder.toString();

        // 4. Convert legacy hex (&#FF0000) to MiniMessage (<#FF0000>)
        Matcher hexMatcher = HEX_PATTERN.matcher(text);
        StringBuilder builder = new StringBuilder();
        while (hexMatcher.find()) {
            hexMatcher.appendReplacement(builder, "<#" + hexMatcher.group(1) + ">");
        }
        hexMatcher.appendTail(builder);
        text = builder.toString();

        // 5. BBCode support ([BOLD], [COLOR=#hex], [italic], [underline], [strike], etc.)
        // Note: MiniMessage has no </color> closing tag, so [/color] becomes <reset>
        text = text.replaceAll("(?i)\\[color=([A-Za-z_]+|#[A-Fa-f0-9]{6})\\]", "<$1>")
                   .replaceAll("(?i)\\[/color\\]", "<reset>")
                   .replaceAll("(?i)\\[bold\\]", "<bold>")
                   .replaceAll("(?i)\\[/bold\\]", "<reset>")
                   .replaceAll("(?i)\\[italic\\]", "<italic>")
                   .replaceAll("(?i)\\[/italic\\]", "<reset>")
                   .replaceAll("(?i)\\[underline\\]", "<underlined>")
                   .replaceAll("(?i)\\[/underline\\]", "<reset>")
                   .replaceAll("(?i)\\[strike\\]", "<strikethrough>")
                   .replaceAll("(?i)\\[/strike\\]", "<reset>");

        // 6. Convert legacy '&' codes to MiniMessage tags
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

        // 7. Convert HTML-style shorthands to MiniMessage tags
        //   MiniMessage does NOT support <b>, <i>, <u>, <s> — it uses <bold>, <italic>, <underlined>, <strikethrough>
        //   Safe to use simple replace because <b> (3 chars) ≠ <bold> (5 chars)
        text = text.replace("<b>", "<bold>")
                   .replace("</b>", "</bold>")
                   .replace("<i>", "<italic>")
                   .replace("</i>", "</italic>")
                   .replace("<u>", "<underlined>")
                   .replace("</u>", "</underlined>")
                   .replace("<s>", "<strikethrough>")
                   .replace("</s>", "</strikethrough>");

        return text;
    }

    /**
     * Applies PlaceholderAPI if available, then handles unresolved %placeholder% patterns.
     * Built-in placeholders (%player_name%) are excluded from removal.
     * Called from ChatListener.buildFinalComponent().
     */
    public static String applyPapi(Player player, String text) {
        if (text == null || text.isEmpty()) return text;

        if (player != null && papiAvailable && setPlaceholdersMethod != null) {
            try {
                DebugLogger.debug("ColorUtils", "Applying PAPI for " + player.getName());
                text = (String) setPlaceholdersMethod.invoke(null, player, text);
            } catch (Exception ignored) {}
        }

        if ("remove".equalsIgnoreCase(unresolvedBehavior)) {
            text = UNRESOLVED_PAPI_PATTERN.matcher(text).replaceAll(match -> {
                if (BUILTIN_PLACEHOLDER_PATTERN.matcher(match.group()).matches()) {
                    return match.group(); // Keep built-in placeholders
                }
                return "";
            });
        }

        return text;
    }

    /**
     * Converts a Component to a legacy §-formatted string for Spigot compatibility.
     */
    public static String toLegacyString(Component component) {
        return LEGACY_SERIALIZER.serialize(component);
    }

    /**
     * Sends a Component to a CommandSender.
     * On Paper: uses native Adventure Component for full interactive support.
     * On Spigot: falls back to legacy string to avoid NoSuchMethodError.
     */
    public static void sendComponent(CommandSender sender, Component component) {
        try {
            sender.sendMessage(component);
            return;
        } catch (NoSuchMethodError | NoClassDefFoundError e) {
            // Fall back to legacy for Spigot
        }
        if (sender instanceof Player) {
            sender.sendMessage(toLegacyString(component));
        } else {
            sender.sendMessage(org.bukkit.ChatColor.stripColor(toLegacyString(component)));
        }
    }

    /**
     * Broadcasts a formatted message to all online players and console.
     * Uses Paper Adventure when available for interactive support.
     */
    public static void broadcastLegacy(Player context, String text) {
        Component component = format(context, text);
        String legacy = toLegacyString(component);
        for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            try {
                p.sendMessage(component);
            } catch (NoSuchMethodError | NoClassDefFoundError e) {
                p.sendMessage(legacy);
            }
        }
        org.bukkit.Bukkit.getConsoleSender().sendMessage(org.bukkit.ChatColor.stripColor(legacy));
    }

    // ════════════════════════════════════════════════════════
    //  GETTERS
    // ════════════════════════════════════════════════════════

    public static boolean isPAPIAvailable() {
        return papiAvailable;
    }

    public static String getUnresolvedBehavior() {
        return unresolvedBehavior;
    }

    public static boolean isPaperAdventureAvailable() {
        if (paperAdventureAvailable != null) return paperAdventureAvailable;
        synchronized (ColorUtils.class) {
            if (paperAdventureAvailable != null) return paperAdventureAvailable;
            // Detect by checking if Player class has the Adventure sendMessage(Component) method.
            // This is the actual method we need, and it exists on ALL Paper versions (1.16.5+)
            // but NOT on Spigot. This is more reliable than checking for internal Paper classes.
            try {
                org.bukkit.entity.Player.class.getMethod("sendMessage", net.kyori.adventure.text.Component.class);
                paperAdventureAvailable = true;
            } catch (NoSuchMethodException e) {
                paperAdventureAvailable = false;
            }
            return paperAdventureAvailable;
        }
    }

    // ════════════════════════════════════════════════════════
    //  INTERNAL
    // ════════════════════════════════════════════════════════

    private static final Pattern SPIGOT_HEX_PATTERN = Pattern.compile("(?i)&x(&[A-Fa-f0-9]){6}");
}
