package com.fabian.xchat.managers;

import com.fabian.xchat.XChat;
import com.fabian.xchat.utils.DebugLogger;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ChatHistoryManager {

    private final XChat plugin;
    private final Map<UUID, Queue<String>> historyMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Object> fileLocks = new ConcurrentHashMap<>();
    private final DateTimeFormatter formatter;
    private final int maxMessages;
    private final boolean enabled;

    public ChatHistoryManager(XChat plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("chat-history.enabled", true);
        this.maxMessages = plugin.getConfig().getInt("chat-history.max-messages", 100);
        String formatString = plugin.getConfig().getString("chat-history.timestamp-format", "yyyy-MM-dd HH:mm:ss");
        
        DateTimeFormatter tempFormatter;
        try {
            tempFormatter = DateTimeFormatter.ofPattern(formatString);
        } catch (IllegalArgumentException e) {
            plugin.logWarning("Invalid date format in config.yml: " + formatString + ". Using the default value.");
            tempFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        }
        this.formatter = tempFormatter;

        if (enabled) {
            int interval = plugin.getConfig().getInt("chat-history.save-interval", 300);
            if (interval > 0) {
                com.fabian.xchat.utils.SchedulerUtil.runAsyncTimer(plugin, this::saveAllToDisk, interval * 20L, interval * 20L);
            }
        }
    }

    public void addMessage(UUID player, String message) {
        if (!enabled) return;
        DebugLogger.debug("ChatHistory", "Adding message for " + player);

        Queue<String> queue = historyMap.computeIfAbsent(player, k -> new ConcurrentLinkedQueue<>());
        
        String timestampedMessage = "[" + LocalDateTime.now().format(formatter) + "] " + message;
        queue.add(timestampedMessage);

        while (queue.size() > maxMessages) {
            queue.poll();
        }

        // Si el guardado automático en intervalo está en 0, guardamos inmediatamente en hilo asíncrono
        if (plugin.getConfig().getInt("chat-history.save-interval", 300) == 0) {
            com.fabian.xchat.utils.SchedulerUtil.runAsync(plugin, () -> saveToDisk(player));
        }
    }

    public List<String> getHistory(UUID player) {
        Queue<String> queue = historyMap.get(player);
        return queue != null ? new ArrayList<>(queue) : new ArrayList<>();
    }

    public int getHistorySize(UUID player) {
        Queue<String> queue = historyMap.get(player);
        return queue != null ? queue.size() : 0;
    }

    public void unloadPlayer(UUID player) {
        if (!enabled) return;
        DebugLogger.debug("ChatHistory", "Unloading player history: " + player);
        saveToDisk(player);
        historyMap.remove(player);
    }

    public void saveAllToDisk() {
        if (!enabled) return;
        DebugLogger.debug("ChatHistory", "Saving all history to disk (" + historyMap.size() + " players)");
        for (UUID uuid : historyMap.keySet()) {
            saveToDisk(uuid);
        }
    }

    public void loadPlayer(UUID player) {
        if (!enabled) return;
        DebugLogger.debug("ChatHistory", "Loading player history: " + player);
        com.fabian.xchat.utils.SchedulerUtil.runAsync(plugin, () -> {
            File file = new File(new File(plugin.getDataFolder(), "players/history"), player.toString() + ".yml");
            if (file.exists()) {
                YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
                List<String> history = config.getStringList("history");
                historyMap.put(player, new ConcurrentLinkedQueue<>(history));
            }
        });
    }

    private void saveToDisk(UUID player) {
        Queue<String> queue = historyMap.get(player);
        if (queue == null) return;

        DebugLogger.debug("ChatHistory", "Saving history for " + player + " (" + queue.size() + " messages)");

        String folderPath = "players/history";
        File dataFolder = new File(plugin.getDataFolder(), folderPath);
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        File file = new File(dataFolder, player.toString() + ".yml");
        YamlConfiguration config = new YamlConfiguration();

        List<String> currentHistory = new ArrayList<>(queue);
        config.set("history", currentHistory);
        config.set("last-updated", LocalDateTime.now().format(formatter));

        synchronized (fileLocks.computeIfAbsent(player, k -> new Object())) {
            try {
                config.save(file);
            } catch (IOException e) {
                plugin.logWarning("Could not save history for " + player.toString() + ": " + e.getMessage());
            }
        }
    }
}
