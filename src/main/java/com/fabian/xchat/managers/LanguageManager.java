package com.fabian.xchat.managers;

import com.fabian.xchat.XChat;
import com.fabian.xchat.utils.DebugLogger;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LanguageManager {

    private final XChat plugin;
    private FileConfiguration langConfig;
    private File langFile;
    private String currentLanguage = "en";

    public LanguageManager(XChat plugin) {
        this.plugin = plugin;
        loadLanguage();
    }

    public void loadLanguage() {
        currentLanguage = plugin.getConfig().getString("settings.language", "en").toLowerCase();
        DebugLogger.debug("LanguageManager", "Loading language: " + currentLanguage);
        
        File langDir = new File(plugin.getDataFolder(), "messages");

        if (!langDir.exists()) {
            // First time: copy all default language files
            langDir.mkdirs();
            for (String lang : new String[]{"es", "en", "pt", "ja", "ru", "custom"}) {
                plugin.saveResource("messages/" + lang + ".yml", false);
            }
        }

        langFile = new File(langDir, currentLanguage + ".yml");
        if (!langFile.exists()) {
            langFile = new File(langDir, "en.yml"); // Fallback to English if chosen language doesn't exist
            currentLanguage = "en";
        }

        try {
            langConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(new FileInputStream(langFile), StandardCharsets.UTF_8));
        } catch (java.io.FileNotFoundException e) {
            langConfig = YamlConfiguration.loadConfiguration(langFile);
        }
    }

    public String getMessage(String path) {
        String msg = langConfig.getString(path, null);
        if (msg == null) {
            DebugLogger.debug("LanguageManager", "Missing language key: " + path);
            return "<red>Message not found: " + path;
        }
        return msg;
    }

    public List<String> getMessageList(String path) {
        if (!langConfig.isList(path)) return Collections.singletonList(getMessage(path));
        return langConfig.getStringList(path);
    }

    public List<String> getAvailableLanguages() {
        List<String> languages = new ArrayList<>();
        File langDir = new File(plugin.getDataFolder(), "messages");
        if (langDir.exists() && langDir.isDirectory()) {
            File[] files = langDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().endsWith(".yml")) {
                        languages.add(f.getName().replace(".yml", ""));
                    }
                }
            }
        }
        return languages;
    }

    public boolean setLanguage(String lang) {
        lang = lang.toLowerCase();
        File langDir = new File(plugin.getDataFolder(), "messages");
        File targetFile = new File(langDir, lang + ".yml");
        
        if (targetFile.exists()) {
            plugin.getConfig().set("settings.language", lang);
            plugin.saveConfig();
            this.currentLanguage = lang;
            this.langFile = targetFile;
            try {
                this.langConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(new FileInputStream(langFile), StandardCharsets.UTF_8));
            } catch (java.io.FileNotFoundException e) {
                this.langConfig = YamlConfiguration.loadConfiguration(langFile);
            }
            return true;
        }
        return false;
    }

    public String getCurrentLanguage() {
        return currentLanguage;
    }

    public void reload() {
        DebugLogger.debug("LanguageManager", "Reloading language files...");
        loadLanguage();
    }

    /**
     * "keep" mode: Adds missing keys from the JAR default to the on-disk file.
     * If the language is the active one, reloads it into memory.
     *
     * @return true if the file was updated (keys were added)
     */
    public boolean forceReloadMessages(String langCode) {
        langCode = langCode.toLowerCase();
        File langDir = new File(plugin.getDataFolder(), "messages");
        File targetFile = new File(langDir, langCode + ".yml");

        if (!targetFile.exists()) {
            DebugLogger.debug("LanguageManager", "forceReloadMessages: file not found for " + langCode);
            return false;
        }

        // Load JAR defaults
        String jarPath = "messages/" + langCode + ".yml";
        InputStream jarStream = plugin.getResource(jarPath);
        if (jarStream == null) {
            DebugLogger.debug("LanguageManager", "forceReloadMessages: no JAR default for " + langCode);
            return false;
        }

        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                new InputStreamReader(jarStream, StandardCharsets.UTF_8));
        YamlConfiguration existing;
        try {
            existing = YamlConfiguration.loadConfiguration(new InputStreamReader(new FileInputStream(targetFile), StandardCharsets.UTF_8));
        } catch (java.io.FileNotFoundException e) {
            existing = YamlConfiguration.loadConfiguration(targetFile);
        }

        boolean changed = false;
        for (String key : defaults.getKeys(true)) {
            if (!existing.contains(key)) {
                existing.set(key, defaults.get(key));
                changed = true;
            }
        }

        if (changed) {
            try {
                existing.save(targetFile);
                DebugLogger.debug("LanguageManager", "forceReloadMessages: added missing keys to " + langCode);
            } catch (Exception e) {
                DebugLogger.debug("LanguageManager", "forceReloadMessages: failed to save " + langCode + ": " + e.getMessage());
                return false;
            }
        }

        // Reload in memory if this is the active language
        if (langCode.equals(currentLanguage)) {
            try {
                this.langConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(new FileInputStream(targetFile), StandardCharsets.UTF_8));
            } catch (java.io.FileNotFoundException e) {
                this.langConfig = YamlConfiguration.loadConfiguration(targetFile);
            }
        }

        return true;
    }

    /**
     * "keep" mode for all available languages.
     * @return count of files updated
     */
    public int forceReloadAllMessages() {
        int count = 0;
        for (String lang : getAvailableLanguages()) {
            if (forceReloadMessages(lang)) {
                count++;
            }
        }
        return count;
    }

    /**
     * "new" mode: Deletes the on-disk file and extracts a fresh copy from the JAR.
     * If the language is the active one, reloads it into memory.
     *
     * @return true if the file was regenerated
     */
    public boolean forceResetMessages(String langCode) {
        langCode = langCode.toLowerCase();
        File langDir = new File(plugin.getDataFolder(), "messages");
        File targetFile = new File(langDir, langCode + ".yml");
        String jarPath = "messages/" + langCode + ".yml";

        // Check that a JAR default exists
        InputStream jarStream = plugin.getResource(jarPath);
        if (jarStream == null) {
            DebugLogger.debug("LanguageManager", "forceResetMessages: no JAR default for " + langCode);
            return false;
        }

        // Delete existing file
        if (targetFile.exists()) {
            targetFile.delete();
        }

        // Extract fresh copy from JAR (replace = true)
        plugin.saveResource(jarPath, true);

        DebugLogger.debug("LanguageManager", "forceResetMessages: regenerated " + langCode);

        // Reload in memory if this is the active language
        if (langCode.equals(currentLanguage)) {
            this.langFile = new File(langDir, langCode + ".yml");
            try {
                this.langConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(new FileInputStream(this.langFile), StandardCharsets.UTF_8));
            } catch (java.io.FileNotFoundException e) {
                this.langConfig = YamlConfiguration.loadConfiguration(this.langFile);
            }
        }

        return true;
    }

    /**
     * "new" mode for all default language files shipped in the JAR.
     * @return count of files regenerated
     */
    public int forceResetAllMessages() {
        int count = 0;
        for (String lang : new String[]{"es", "en", "pt", "ja", "ru", "custom"}) {
            if (forceResetMessages(lang)) {
                count++;
            }
        }
        return count;
    }
}
