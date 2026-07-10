package com.fabian.xchat.managers;

import com.fabian.xchat.XChat;
import com.fabian.xchat.utils.ColorUtils;
import com.fabian.xchat.utils.DebugLogger;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class BroadcastManager {

    private final XChat plugin;
    private FileConfiguration config;
    private File configFile;
    private com.fabian.xchat.utils.SchedulerUtil.TaskWrapper task;
    private int currentIndex = 0;
    private final Random random = new Random();

    public BroadcastManager(XChat plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        DebugLogger.debug("BroadcastManager", "Loading announcements config...");
        configFile = new File(plugin.getDataFolder(), "announcements.yml");
        if (!configFile.exists()) {
            plugin.saveResource("announcements.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(configFile);
        
        startTask();
    }

    private void startTask() {
        if (task != null) {
            task.cancel();
        }

        if (!config.getBoolean("settings.enabled", true)) {
            DebugLogger.debug("BroadcastManager", "Announcements disabled in config");
            return;
        }

        int interval = config.getInt("settings.interval", 300);
        if (interval <= 0) return;

        DebugLogger.debug("BroadcastManager", "Starting broadcast timer with interval: " + interval + "s");

        task = com.fabian.xchat.utils.SchedulerUtil.runAsyncTimer(plugin, () -> {
            org.bukkit.Bukkit.getScheduler().runTask(plugin, this::broadcastNext);
        }, interval * 20L, interval * 20L);
    }

    private void broadcastNext() {
        if (Bukkit.getOnlinePlayers().isEmpty()) return; // No anunciar si el server está vacío
        
        org.bukkit.configuration.ConfigurationSection section = config.getConfigurationSection("announcements");
        if (section == null || section.getKeys(false).isEmpty()) return;

        List<String> keys = new ArrayList<>(section.getKeys(false));
        
        boolean randomOrder = config.getBoolean("settings.random-order", false);
        String selectedKey;

        if (randomOrder) {
            selectedKey = keys.get(random.nextInt(keys.size()));
        } else {
            if (currentIndex >= keys.size()) {
                currentIndex = 0;
            }
            selectedKey = keys.get(currentIndex++);
        }

        String path = "announcements." + selectedKey;
        List<String> lines = config.getStringList(path + ".lines");
        if (lines.isEmpty()) return;

        String soundName = config.getString(path + ".sound", "");
        boolean crossServer = config.getBoolean(path + ".cross-server", true);
        boolean showServerPrefix = config.getBoolean(path + ".show-server-prefix", true);

        for (Player p : Bukkit.getOnlinePlayers()) {
            for (String line : lines) {
                ColorUtils.sendComponent(p, ColorUtils.format(p, line));
            }
            if (!soundName.isEmpty()) {
                try {
                    Sound sound = Sound.valueOf(soundName.toUpperCase());
                    p.playSound(p.getLocation(), sound, 1.0f, 1.0f);
                } catch (IllegalArgumentException ignored) {
                    // Invalid sound
                }
            }
        }

        // Publish auto-announcement to other servers via Redis
        if (crossServer && plugin.getMessagingService() != null && plugin.getMessagingService().isEnabled()) {
            StringBuilder allLines = new StringBuilder();
            // Prefix with a flag so the receiver knows whether to show server prefix
            allLines.append(showServerPrefix ? "1" : "0").append("\n");
            for (String line : lines) {
                String legacyLine = ColorUtils.toLegacyString(ColorUtils.format(null, line));
                allLines.append(legacyLine).append("\n");
            }
            plugin.getMessagingService().publish("broadcast", "Console", "",
                    plugin.getServerName(), allLines.toString().trim());
        }
    }

    public void reload() {
        loadConfig();
    }

    public void stop() {
        DebugLogger.debug("BroadcastManager", "Stopping broadcast timer");
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
