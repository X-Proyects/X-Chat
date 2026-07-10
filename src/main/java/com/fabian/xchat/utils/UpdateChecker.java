package com.fabian.xchat.utils;

import com.fabian.xchat.XChat;
import com.fabian.xchat.managers.LanguageManager;
import com.fabian.xchat.utils.DebugLogger;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class UpdateChecker {

    private final XChat plugin;
    private final int resourceId;
    private String latestVersion;
    private boolean updateAvailable;

    public UpdateChecker(XChat plugin) {
        this.plugin = plugin;
        this.resourceId = 135770;
        this.updateAvailable = false;
    }

    public void checkForUpdates() {
        checkForUpdates(null);
    }

    public void checkForUpdates(CommandSender sender) {
        DebugLogger.debug("UpdateChecker", "Starting update check (sender: " + (sender != null ? sender.getName() : "console") + ")");
        com.fabian.xchat.utils.SchedulerUtil.runAsync(plugin, () -> {
            try {
                String currentVersion = plugin.getDescription().getVersion();
                
                // Spigot API for resource versions
                URL url = new URL("https://api.spigotmc.org/legacy/update.php?resource=" + resourceId);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", "Fabian/X-Chat/" + currentVersion);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);

                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String version = reader.readLine();
                reader.close();

                this.latestVersion = version;
                LanguageManager lang = plugin.getLanguageManager();

                if (latestVersion != null && isNewer(currentVersion, latestVersion)) {
                    this.updateAvailable = true;
                    DebugLogger.debug("UpdateChecker", "Update available: " + currentVersion + " -> " + latestVersion);

                    if (sender != null) {
                        ColorUtils.sendComponent(sender, ColorUtils.format(null, lang.getMessage("update-available").replace("%current%", currentVersion).replace("%latest%", latestVersion)));
                        ColorUtils.sendComponent(sender, ColorUtils.format(null, lang.getMessage("update-download").replace("%url%", getDownloadUrl())));
                    } else {
                        ColorUtils.sendComponent(Bukkit.getConsoleSender(), ColorUtils.format(null, lang.getMessage("update-available").replace("%current%", currentVersion).replace("%latest%", latestVersion)));
                        ColorUtils.sendComponent(Bukkit.getConsoleSender(), ColorUtils.format(null, lang.getMessage("update-download").replace("%url%", getDownloadUrl())));
                    }
                } else {
                    if (sender != null) {
                        ColorUtils.sendComponent(sender, ColorUtils.format(null, lang.getMessage("update-current")));
                    } else {
                        ColorUtils.sendComponent(Bukkit.getConsoleSender(), ColorUtils.format(null, lang.getMessage("update-current")));
                    }
                }

            } catch (Exception e) {
                DebugLogger.debug("UpdateChecker", "Update check failed", e);
                if (sender != null) {
                    ColorUtils.sendComponent(sender, ColorUtils.format(null, plugin.getLanguageManager().getMessage("update-error")));
                } else {
                    ColorUtils.sendComponent(Bukkit.getConsoleSender(), ColorUtils.format(null, plugin.getLanguageManager().getMessage("update-error")));
                }
            }
        });
    }

    public boolean isUpdateAvailable() {
        return updateAvailable;
    }

    public String getLatestVersion() {
        return latestVersion;
    }

    public String getDownloadUrl() {
        return "https://www.spigotmc.org/resources/" + resourceId + "/";
    }

    private boolean isNewer(String current, String latest) {
        try {
            String[] currentParts = current.replace("v", "").split("[\\.-]");
            String[] latestParts = latest.replace("v", "").split("[\\.-]");
            int length = Math.max(currentParts.length, latestParts.length);
            for (int i = 0; i < length; i++) {
                int currentPart = i < currentParts.length ? parseVersionPart(currentParts[i]) : 0;
                int latestPart = i < latestParts.length ? parseVersionPart(latestParts[i]) : 0;
                if (latestPart > currentPart)
                    return true;
                if (latestPart < currentPart)
                    return false;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private int parseVersionPart(String part) {
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
