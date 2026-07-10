package com.fabian.xchat.managers;

import com.fabian.xchat.XChat;
import com.fabian.xchat.utils.DebugLogger;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.List;
import java.util.Map;

public class TagsManager {

    private final XChat plugin;
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

    public String processTags(Player player, String message) {
        DebugLogger.debug("TagsManager", "Processing tags for " + player.getName());
        message = processPingTag(player, message);
        message = processPosTag(player, message);
        message = processItemTag(player, message);
        return message;
    }

    private String processPingTag(Player player, String message) {
        if (!pingConfig.getBoolean("enabled", true)) return message;
        boolean hasMaster = player.hasPermission(plugin.getConfig().getString("general-permissions.all-tags", "xchat.tags.all"));
        if (!hasMaster && !player.hasPermission(pingConfig.getString("permission", "xchat.tags.use"))) return message;

        List<String> triggers = pingConfig.getStringList("triggers");
        String format = pingConfig.getString("format", "<aqua>📡 {ping}ms</aqua>");
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

    private String processItemTag(Player player, String message) {
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

        // Enchantments
        StringBuilder enchantsBuilder = new StringBuilder();
        if (meta != null && meta.hasEnchants()) {
            for (Map.Entry<Enchantment, Integer> entry : meta.getEnchants().entrySet()) {
                String enchName = entry.getKey().getKey().getKey();
                enchName = capitalize(enchName.replace("_", " "));
                enchantsBuilder.append("<gray>").append(enchName).append(" ").append(toRoman(entry.getValue())).append("</gray><newline>");
            }
        }

        // Lore
        StringBuilder loreBuilder = new StringBuilder();
        if (meta != null && meta.hasLore()) {
            for (String line : meta.getLore()) {
                loreBuilder.append(line.replace("§", "&")).append("<newline>");
            }
        }

        // Build Hover Tooltip
        List<String> hoverLines = itemConfig.getStringList("hover-format");
        StringBuilder hoverTooltip = new StringBuilder();
        for (String line : hoverLines) {
            String processedLine = line.replace("{item_name}", itemName)
                                       .replace("{item_amount}", String.valueOf(amount))
                                       .replace("{item_durability}", String.valueOf(durability))
                                       .replace("{item_max_durability}", String.valueOf(maxDurability));
            
            if (processedLine.contains("{item_enchantments}")) {
                if (enchantsBuilder.length() > 0) {
                    processedLine = processedLine.replace("{item_enchantments}", enchantsBuilder.toString().trim());
                } else {
                    processedLine = processedLine.replace("{item_enchantments}", "");
                }
            }
            
            if (processedLine.contains("{item_lore}")) {
                if (loreBuilder.length() > 0) {
                    processedLine = processedLine.replace("{item_lore}", loreBuilder.toString().trim());
                } else {
                    processedLine = processedLine.replace("{item_lore}", "");
                }
            }
            
            if (!processedLine.trim().isEmpty()) {
                hoverTooltip.append(processedLine).append("<newline>");
            }
        }

        // Build Display Format
        String display = itemConfig.getString("display-format", "<gold>[<yellow>{item_name}</yellow>]</gold>")
                .replace("{item_name}", itemName)
                .replace("{item_amount}", String.valueOf(amount))
                .replace("{item_hover}", hoverTooltip.toString().trim());

        // Replace all occurrences of triggers
        for (String trigger : triggers) {
            message = message.replaceAll("(?i)" + java.util.regex.Pattern.quote(trigger), java.util.regex.Matcher.quoteReplacement(display));
        }

        return message;
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
