package com.fabian.xchat.managers;

import com.fabian.xchat.XChat;
import com.fabian.xchat.utils.ColorUtils;
import com.fabian.xchat.utils.DebugLogger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TagsManager {

    private final XChat plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private File tagsFolder;
    private FileConfiguration itemConfig;
    private FileConfiguration posConfig;
    private FileConfiguration pingConfig;

    public TagsManager(XChat plugin) {
        this.plugin = plugin;
        loadConfigs();
    }

    public void loadConfigs() {
        DebugLogger.debug("TagsManager", "Loading tag configs...");
        tagsFolder = new File(plugin.getDataFolder(), "tags");
        if (!tagsFolder.exists()) {
            tagsFolder.mkdirs();
        }

        File itemFile = new File(tagsFolder, "item.yml");
        if (!itemFile.exists()) plugin.saveResource("tags/item.yml", false);
        itemConfig = YamlConfiguration.loadConfiguration(itemFile);

        File posFile = new File(tagsFolder, "pos.yml");
        if (!posFile.exists()) plugin.saveResource("tags/pos.yml", false);
        posConfig = YamlConfiguration.loadConfiguration(posFile);

        File pingFile = new File(tagsFolder, "ping.yml");
        if (!pingFile.exists()) plugin.saveResource("tags/ping.yml", false);
        pingConfig = YamlConfiguration.loadConfiguration(pingFile);
    }

    /**
     * Processes all custom tags in the message.
     * Ping and pos tags remain string-based (simple color formatting).
     * Item tags build Components directly via Adventure API for reliable hover/click.
     *
     * @param player    the sender
     * @param message   the chat message (modified in place)
     * @param resolvers list to which item tag Component placeholders are added
     * @return the modified message with item tag triggers replaced by placeholders
     */
    public String processTags(Player player, String message, List<TagResolver> resolvers) {
        DebugLogger.debug("TagsManager", "Processing tags for " + player.getName());
        message = processPingTag(player, message);
        message = processPosTag(player, message);
        message = processItemTag(player, message, resolvers);
        return message;
    }

    /**
     * Legacy overload — used by code that doesn't need interactive resolvers.
     * Item tag hover will NOT work with this method.
     */
    public String processTags(Player player, String message) {
        return processTags(player, message, new java.util.ArrayList<>());
    }

    private String processPingTag(Player player, String message) {
        if (!pingConfig.getBoolean("enabled", true)) return message;
        boolean hasMaster = player.hasPermission(plugin.getConfig().getString("general-permissions.all-tags", "xchat.tags.all"));
        if (!hasMaster && !player.hasPermission(pingConfig.getString("permission", "xchat.tags.use"))) return message;

        List<String> triggers = pingConfig.getStringList("triggers");
        String format = pingConfig.getString("format", "<aqua>📡 {ping}ms</aqua>");
        format = ColorUtils.convertLegacyAndHex(format);
        String replacement = format.replace("{ping}", String.valueOf(player.getPing()));

        for (String trigger : triggers) {
            message = message.replaceAll("(?i)" + java.util.regex.Pattern.quote(trigger), java.util.regex.Matcher.quoteReplacement(replacement));
        }
        return message;
    }

    private String processPosTag(Player player, String message) {
        if (!posConfig.getBoolean("enabled", true)) return message;
        boolean hasMaster = player.hasPermission(plugin.getConfig().getString("general-permissions.all-tags", "xchat.tags.all"));
        if (!hasMaster && !player.hasPermission(posConfig.getString("permission", "xchat.tags.use"))) return message;

        List<String> triggers = posConfig.getStringList("triggers");
        String format = posConfig.getString("format", "<green>📍 {x}, {y}, {z}</green>");
        format = ColorUtils.convertLegacyAndHex(format);
        org.bukkit.Location loc = player.getLocation();
        
        String replacement = format.replace("{x}", String.valueOf(loc.getBlockX()))
                                   .replace("{y}", String.valueOf(loc.getBlockY()))
                                   .replace("{z}", String.valueOf(loc.getBlockZ()))
                                   .replace("{world}", loc.getWorld().getName());

        for (String trigger : triggers) {
            message = message.replaceAll("(?i)" + java.util.regex.Pattern.quote(trigger), java.util.regex.Matcher.quoteReplacement(replacement));
        }
        return message;
    }

    /**
     * Processes item tags in the message.
     * Builds the item display Component directly via Adventure API (with hover event)
     * and injects it as a MiniMessage placeholder. This ensures hover/click events
     * are reliably preserved, unlike string-based MiniMessage embedding.
     */
    private String processItemTag(Player player, String message, List<TagResolver> resolvers) {
        if (!itemConfig.getBoolean("enabled", true)) return message;
        boolean hasMaster = player.hasPermission(plugin.getConfig().getString("general-permissions.all-tags", "xchat.tags.all"));
        if (!hasMaster && !player.hasPermission(itemConfig.getString("permission", "xchat.item.show"))) return message;

        List<String> triggers = itemConfig.getStringList("triggers");
        boolean containsTrigger = false;
        
        for (String trigger : triggers) {
            if (message.toLowerCase().contains(trigger.toLowerCase())) {
                containsTrigger = true;
                break;
            }
        }

        if (!containsTrigger) return message;

        ItemStack item = player.getInventory().getItemInMainHand();
        
        if (item == null || item.getType() == Material.AIR) {
            String emptyHand = itemConfig.getString("empty-hand", "<gray>[Empty Hand]</gray>");
            emptyHand = ColorUtils.convertLegacyAndHex(emptyHand);
            for (String trigger : triggers) {
                message = message.replaceAll("(?i)" + java.util.regex.Pattern.quote(trigger), java.util.regex.Matcher.quoteReplacement(emptyHand));
            }
            return message;
        }

        // Get Item Info
        String itemName = getItemName(item);
        int amount = item.getAmount();
        
        int damage = 0;
        int maxDurability = item.getType().getMaxDurability();
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta instanceof Damageable && maxDurability > 0) {
            damage = ((Damageable) meta).getDamage();
        }
        int durability = maxDurability - damage;

        // Build Hover Component directly via Adventure API
        Component hoverComp = buildItemHoverComponent(item, meta, itemName, amount, durability, maxDurability);

        // Build Display Component
        String displayFormat = itemConfig.getString("display-format", "<gold>[<yellow>{item_name}</yellow>]</gold>");
        displayFormat = ColorUtils.convertLegacyAndHex(displayFormat);
        displayFormat = displayFormat.replace("{item_name}", itemName)
                .replace("{item_amount}", String.valueOf(amount));

        Component displayComp;
        try {
            displayComp = miniMessage.deserialize(displayFormat);
        } catch (Exception e) {
            DebugLogger.debug("TagsManager", "Failed to parse item display format, using fallback", e);
            displayComp = Component.text("[" + itemName + "]");
        }

        // Apply hover event if available
        if (hoverComp != null) {
            displayComp = displayComp.style(Style.style()
                    .hoverEvent(HoverEvent.showText(hoverComp)));
        }

        // Build click event (if configured)
        // Always build — the try/catch in sendMessage handles Spigot fallback.
        // The old isPaperAdventureAvailable() check was unreliable and caused click
        // events to be skipped on real Paper servers.
        String clickAction = itemConfig.getString("click-action", "");
        if (clickAction != null && !clickAction.isEmpty()) {
            try {
                net.kyori.adventure.text.event.ClickEvent clickEvent;
                switch (clickAction.toUpperCase()) {
                    case "SUGGEST_COMMAND":
                        String suggestCmd = itemConfig.getString("click-value", "/iteminfo {player_name}")
                                .replace("{player_name}", player.getName())
                                .replace("{item_name}", itemName);
                        clickEvent = net.kyori.adventure.text.event.ClickEvent.suggestCommand(suggestCmd);
                        displayComp = displayComp.style(displayComp.style().clickEvent(clickEvent));
                        break;
                    case "RUN_COMMAND":
                        String runCmd = itemConfig.getString("click-value", "")
                                .replace("{player_name}", player.getName())
                                .replace("{item_name}", itemName);
                        if (!runCmd.isEmpty()) {
                            clickEvent = net.kyori.adventure.text.event.ClickEvent.runCommand(runCmd);
                            displayComp = displayComp.style(displayComp.style().clickEvent(clickEvent));
                        }
                        break;
                    case "COPY_TO_CLIPBOARD":
                        String copyText = itemConfig.getString("click-value", itemName);
                        clickEvent = net.kyori.adventure.text.event.ClickEvent.copyToClipboard(copyText);
                        displayComp = displayComp.style(displayComp.style().clickEvent(clickEvent));
                        break;
                }
            } catch (Exception e) {
                DebugLogger.debug("TagsManager", "Failed to create item click event", e);
            }
        }

        // Replace all occurrences of triggers with a placeholder
        for (String trigger : triggers) {
            java.util.regex.Pattern trigPattern = java.util.regex.Pattern.compile(
                    "(?i)" + java.util.regex.Pattern.quote(trigger));
            java.util.regex.Matcher trigMatcher = trigPattern.matcher(message);
            if (trigMatcher.find()) {
                String placeholderName = "xchat_item";
                resolvers.add(Placeholder.component(placeholderName, displayComp));
                message = trigMatcher.replaceAll(java.util.regex.Matcher.quoteReplacement("<" + placeholderName + ">"));
                break; // Only replace first occurrence (one item per message)
            }
        }

        return message;
    }

    /**
     * Builds the item hover tooltip Component directly via Adventure API.
     * Each line is parsed from the config's hover-format list.
     */
    private Component buildItemHoverComponent(ItemStack item, ItemMeta meta,
                                               String itemName, int amount,
                                               int durability, int maxDurability) {
        List<String> hoverLines = itemConfig.getStringList("hover-format");
        if (hoverLines == null || hoverLines.isEmpty()) return null;

        List<Component> lineComponents = new ArrayList<>();

        for (String line : hoverLines) {
            line = ColorUtils.convertLegacyAndHex(line);
            String processedLine = line.replace("{item_name}", itemName)
                                       .replace("{item_amount}", String.valueOf(amount))
                                       .replace("{item_durability}", String.valueOf(durability))
                                       .replace("{item_max_durability}", String.valueOf(maxDurability));
            
            // Build enchantments text
            if (processedLine.contains("{item_enchantments}")) {
                StringBuilder enchantsBuilder = new StringBuilder();
                if (meta != null && meta.hasEnchants()) {
                    for (Map.Entry<Enchantment, Integer> entry : meta.getEnchants().entrySet()) {
                        String enchName = entry.getKey().getKey().getKey();
                        enchName = capitalize(enchName.replace("_", " "));
                        enchantsBuilder.append("<gray>").append(enchName).append(" ").append(toRoman(entry.getValue())).append("</gray><newline>");
                    }
                }
                processedLine = processedLine.replace("{item_enchantments}", enchantsBuilder.toString().trim());
            }
            
            // Build lore text
            if (processedLine.contains("{item_lore}")) {
                StringBuilder loreBuilder = new StringBuilder();
                if (meta != null && meta.hasLore()) {
                    for (String loreLine : meta.getLore()) {
                        loreBuilder.append(loreLine.replace("§", "&")).append("<newline>");
                    }
                }
                processedLine = processedLine.replace("{item_lore}", loreBuilder.toString().trim());
            }

            if (!processedLine.trim().isEmpty()) {
                try {
                    lineComponents.add(miniMessage.deserialize(processedLine));
                } catch (Exception e) {
                    lineComponents.add(Component.text(processedLine));
                }
            }
        }

        if (lineComponents.isEmpty()) return null;

        // Combine multiple lines with newline
        if (lineComponents.size() == 1) return lineComponents.get(0);

        net.kyori.adventure.text.TextComponent.Builder builder = Component.text();
        for (int i = 0; i < lineComponents.size(); i++) {
            if (i > 0) builder.append(Component.newline());
            builder.append(lineComponents.get(i));
        }
        return builder.asComponent();
    }

    private String getItemName(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return item.getItemMeta().getDisplayName().replace("§", "&");
        }
        return capitalize(item.getType().name().replace("_", " "));
    }

    private String capitalize(String str) {
        String[] words = str.toLowerCase().split(" ");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.length() > 0) {
                sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(" ");
            }
        }
        return sb.toString().trim();
    }

    private String toRoman(int number) {
        String[] roman = {"O", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        if (number > 0 && number <= 10) return roman[number];
        return String.valueOf(number);
    }
}