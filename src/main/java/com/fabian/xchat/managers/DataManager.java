package com.fabian.xchat.managers;

import com.fabian.xchat.XChat;
import com.fabian.xchat.utils.DebugLogger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DataManager {

    private final XChat plugin;
    private File dataFile;
    private FileConfiguration dataConfig;

    public DataManager(XChat plugin) {
        this.plugin = plugin;
        loadData();
    }

    private void loadData() {
        DebugLogger.debug("DataManager", "Loading data.yml...");
        dataFile = new File(plugin.getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                plugin.logError("Could not create data.yml!");
                e.printStackTrace();
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }

    public void saveData() {
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            plugin.logError("Could not save data.yml!");
            e.printStackTrace();
        }
    }

    public void loadIgnoredPlayers(Map<UUID, Set<UUID>> ignoredPlayers) {
        DebugLogger.debug("DataManager", "Loading ignored players from data.yml");
        ignoredPlayers.clear();
        ConfigurationSection section = dataConfig.getConfigurationSection("ignored-players");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    UUID player = UUID.fromString(key);
                    Set<UUID> ignored = ConcurrentHashMap.newKeySet();
                    for (String ignoredKey : section.getStringList(key)) {
                        ignored.add(UUID.fromString(ignoredKey));
                    }
                    ignoredPlayers.put(player, ignored);
                } catch (IllegalArgumentException ignored) {}
            }
        }
    }

    public void saveIgnoredPlayers(Map<UUID, Set<UUID>> ignoredPlayers) {
        DebugLogger.debug("DataManager", "Saving ignored players to data.yml (" + ignoredPlayers.size() + " players)");
        dataConfig.set("ignored-players", null); // Clear old data
        ConfigurationSection section = dataConfig.createSection("ignored-players");
        
        for (Map.Entry<UUID, Set<UUID>> entry : ignoredPlayers.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                java.util.List<String> list = new java.util.ArrayList<>();
                for (UUID uuid : entry.getValue()) {
                    list.add(uuid.toString());
                }
                section.set(entry.getKey().toString(), list);
            }
        }
        saveData();
    }
}
