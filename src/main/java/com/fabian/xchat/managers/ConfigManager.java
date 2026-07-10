package com.fabian.xchat.managers;

import com.fabian.xchat.XChat;
import com.fabian.xchat.utils.DebugLogger;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.UUID;

public class ConfigManager {

    private final XChat plugin;
    private FileConfiguration config;
    public UUID debugPlayer; // player who enabled debug via command (null = console-only via config)

    public ConfigManager(XChat plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        DebugLogger.debug("ConfigManager", "Loading config.yml...");
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        boolean needFreshConfig = false;

        // Si no existe, crear desde el JAR
        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false);
        } else {
            // Verificar que el YAML existente no esté corrupto
            try {
                YamlConfiguration.loadConfiguration(configFile);
            } catch (Exception e) {
                plugin.logWarning("config.yml is corrupted or invalid: " + e.getMessage());
                plugin.logWarning("Creating a backup and regenerating from defaults...");
                backupConfig();
                try {
                    Files.copy(plugin.getResource("config.yml"), configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception ex) {
                    plugin.logError("Could not regenerate config.yml: " + ex.getMessage());
                }
                needFreshConfig = true;
            }
        }

        // Cargar la config (ya sea la existente válida, o la recién generada)
        plugin.reloadConfig();
        this.config = plugin.getConfig();

        // Si recién regeneramos la config corrupta, no hace falta merge
        if (needFreshConfig) return;

        // Check config-code para auto-actualizar
        int currentCode = config.getInt("config-code", 0);

        InputStream defaultStream = plugin.getResource("config.yml");
        if (defaultStream != null) {
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
            int newCode = defaultConfig.getInt("config-code", 0);

            if (currentCode < newCode) {
                plugin.logInfo("&eFound a newer configuration version! (&f" + currentCode + " &7-> &a" + newCode + "&e)");
                DebugLogger.debug("ConfigManager", "Config update detected: " + currentCode + " -> " + newCode);
                backupConfig();
                mergeConfig();
                plugin.reloadConfig();
                this.config = plugin.getConfig();
            }
        }
    }

    private void backupConfig() {
        try {
            File configFile = new File(plugin.getDataFolder(), "config.yml");
            File backupFile = new File(plugin.getDataFolder(), "config_old.yml");
            Files.copy(configFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            plugin.logInfo("&eA backup of your current config has been created: &f" + backupFile.getName());
        } catch (Exception e) {
            plugin.logWarning("Could not create config backup: " + e.getMessage());
        }
    }

    /**
     * Merges user values into the new default config template.
     * For each key in the new template, if the user had that key, the user's value is preserved.
     * New keys that didn't exist before keep their default values.
     * List values are also properly preserved using the Bukkit YamlConfiguration API.
     */
    private void mergeConfig() {
        try {
            // Load the new default config from the JAR
            InputStream is = plugin.getResource("config.yml");
            if (is == null) return;

            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(is, StandardCharsets.UTF_8));

            // Copy the new default config as base
            File configFile = new File(plugin.getDataFolder(), "config.yml");
            Files.copy(plugin.getResource("config.yml"), configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

            // Reload to get the fresh default as a FileConfiguration
            YamlConfiguration merged = YamlConfiguration.loadConfiguration(configFile);

            // Walk through all keys the user had in their old config
            Set<String> userKeys = this.config.getKeys(true);
            for (String key : userKeys) {
                if (key.equals("config-code")) continue; // Always use the new version

                // Only copy keys that also exist in the new default config
                if (merged.contains(key) && !merged.isConfigurationSection(key)
                        && !this.config.isConfigurationSection(key)) {
                    // Preserve the user's scalar value
                    merged.set(key, this.config.get(key));
                } else if (merged.isList(key) && this.config.isList(key)) {
                    // Preserve the user's list values
                    merged.set(key, this.config.getList(key));
                } else if (merged.isConfigurationSection(key) && this.config.isConfigurationSection(key)) {
                    // For sections, do nothing — the loop will handle child keys individually
                }
            }

            // Write the merged config back
            merged.save(configFile);

        } catch (Exception e) {
            plugin.logWarning("Could not merge config.yml: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void reloadConfiguration() {
        DebugLogger.debug("ConfigManager", "Reloading configuration...");
        loadConfig();
        // Refresh ColorUtils settings (PAPI behavior, etc.)
        com.fabian.xchat.utils.ColorUtils.reload(plugin.getConfig());
    }

    public FileConfiguration getConfig() {
        return config;
    }

    public void saveConfig() {
        DebugLogger.debug("ConfigManager", "Saving config.yml");
        plugin.saveConfig();
    }
}
