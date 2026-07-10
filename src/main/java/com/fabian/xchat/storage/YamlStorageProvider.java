package com.fabian.xchat.storage;

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

/**
 * Default YAML-based storage provider.
 * Uses data.yml for ignored players. Spy and reply targets are in-memory only.
 */
public class YamlStorageProvider implements StorageProvider {

    private final XChat plugin;
    private File dataFile;
    private FileConfiguration dataConfig;
    private final Map<UUID, Set<UUID>> ignoredPlayers = new ConcurrentHashMap<>();

    public YamlStorageProvider(XChat plugin) {
        this.plugin = plugin;
    }

    @Override
    public void init() {
        DebugLogger.debug("YamlStorage", "Initializing YAML storage...");
        loadData();
        DebugLogger.debug("YamlStorage", "YAML storage initialized (" + ignoredPlayers.size() + " players with ignore lists)");
    }

    @Override
    public void shutdown() {
        saveIgnoredPlayers();
        DebugLogger.debug("YamlStorage", "YAML storage saved and shut down");
    }

    private void loadData() {
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

    private void saveData() {
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            plugin.logError("Could not save data.yml!");
            e.printStackTrace();
        }
    }

    public void loadIgnoredPlayersInto(Map<UUID, Set<UUID>> target) {
        target.clear();
        ConfigurationSection section = dataConfig.getConfigurationSection("ignored-players");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    UUID player = UUID.fromString(key);
                    Set<UUID> ignored = ConcurrentHashMap.newKeySet();
                    for (String ignoredKey : section.getStringList(key)) {
                        ignored.add(UUID.fromString(ignoredKey));
                    }
                    target.put(player, ignored);
                    ignoredPlayers.put(player, ignored);
                } catch (IllegalArgumentException ignored) {}
            }
        }
    }

    public void saveIgnoredPlayers() {
        DebugLogger.debug("YamlStorage", "Saving ignored players (" + ignoredPlayers.size() + " players)");
        dataConfig.set("ignored-players", null);
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

    // ── Ignore List ──

    @Override
    public Set<UUID> getIgnoredPlayers(UUID player) {
        return ignoredPlayers.get(player);
    }

    @Override
    public boolean isIgnoring(UUID player, UUID target) {
        Set<UUID> ignored = ignoredPlayers.get(player);
        return ignored != null && ignored.contains(target);
    }

    @Override
    public boolean toggleIgnore(UUID player, UUID target) {
        Set<UUID> ignored = ignoredPlayers.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet());
        boolean result;
        if (ignored.contains(target)) {
            ignored.remove(target);
            result = false;
        } else {
            ignored.add(target);
            result = true;
        }
        saveIgnoredPlayers();
        return result;
    }

    // ── Social Spy (in-memory only for YAML) ──

    @Override
    public boolean isSpyEnabled(UUID player) {
        return false; // YAML provider doesn't persist spy state; handled in-memory by plugin
    }

    @Override
    public void setSpyEnabled(UUID player, boolean enabled) {
        // No-op for YAML provider
    }

    // ── Reply Target (in-memory only for YAML) ──

    @Override
    public UUID getLastMessageTarget(UUID player) {
        return null; // YAML provider doesn't persist reply targets; handled in-memory by plugin
    }

    @Override
    public void setLastMessageTarget(UUID player, UUID target) {
        // No-op for YAML provider
    }

    @Override
    public String getName() {
        return "YAML";
    }
}