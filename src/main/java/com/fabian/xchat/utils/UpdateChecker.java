package com.fabian.xchat.utils;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.function.Function;

/**
 * X-Proyects Unified Update Checker (canonical implementation).
 * <p>
 * Identical across every X-Proyects plugin — only the package and the
 * plugin-specific message resolver change.
 * <p>
 * Message resolver: maps a message key to its localized text.
 * Required keys: {@code update-available}, {@code update-download},
 * {@code update-current}, {@code update-error}.
 * Supported placeholders: {@code %current%}, {@code %latest%}, {@code %url%}.
 */
public class UpdateChecker {

    private final Plugin plugin;
    private final int resourceId;
    private final Function<String, String> messageResolver;

    private volatile String latestVersion;
    private volatile boolean updateAvailable;

    public UpdateChecker(Plugin plugin, int resourceId, Function<String, String> messageResolver) {
        this.plugin = plugin;
        this.resourceId = resourceId;
        this.messageResolver = messageResolver;
        this.updateAvailable = false;
    }

    public void checkForUpdates() {
        checkForUpdates(null);
    }

    @SuppressWarnings("deprecation")
    public void checkForUpdates(CommandSender sender) {
        SchedulerUtil.runTaskAsynchronously(plugin, () -> {
            HttpURLConnection connection = null;
            BufferedReader reader = null;
            try {
                String currentVersion = plugin.getDescription().getVersion();

                // Spigot API for resource versions
                URL url = new URL("https://api.spigotmc.org/legacy/update.php?resource=" + resourceId);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", "Fabian/X-Proyects/" + currentVersion);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);

                reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String version = reader.readLine();

                this.latestVersion = version;

                if (latestVersion != null && isNewerVersion(currentVersion, latestVersion)) {
                    this.updateAvailable = true;
                    notify(sender, "update-available",
                            "%current%", currentVersion,
                            "%latest%", latestVersion);
                    notify(sender, "update-download",
                            "%url%", getDownloadUrl());
                } else {
                    notify(sender, "update-current");
                }

            } catch (Exception e) {
                notify(sender, "update-error");
            } finally {
                if (reader != null) {
                    try { reader.close(); } catch (Exception ignored) {}
                }
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    /**
     * Sends a resolved message to the given sender (or console if null).
     */
    private void notify(CommandSender sender, String key, String... replacements) {
        String message = resolve(key, replacements);
        CommandSender target = sender != null ? sender : Bukkit.getConsoleSender();
        if (message != null && !message.isEmpty()) {
            target.sendMessage(message);
        }
    }

    /**
     * Resolves a message key with placeholder substitution.
     */
    private String resolve(String key, String... replacements) {
        String message;
        try {
            message = messageResolver != null ? messageResolver.apply(key) : null;
        } catch (Exception e) {
            message = null;
        }
        if (message == null || message.isEmpty()) {
            // Canonical English fallback so the checker never goes silent
            switch (key) {
                case "update-available": return "&eA new version is available! &7(&a" + safe(replacements, 1) + "&7)";
                case "update-download": return "&eDownload: &b" + getDownloadUrl();
                case "update-current": return "&aYou are running the latest version.";
                default: return "&cUpdate check failed.";
            }
        }
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            message = message.replace(replacements[i], replacements[i + 1]);
        }
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', message);
    }

    private static String safe(String[] arr, int idx) {
        return (arr != null && idx < arr.length && arr[idx] != null) ? arr[idx] : "?";
    }

    /**
     * Compares semantic versions ("1.8" vs "1.9", "2.4" vs "2.10", "1.3-Beta" etc.).
     */
    private boolean isNewerVersion(String current, String remote) {
        if (remote == null || remote.isEmpty())
            return false;

        // Remove 'v' or 'V' if present
        String v1 = current.toLowerCase().replace("v", "");
        String v2 = remote.toLowerCase().replace("v", "");

        String[] currentParts = v1.split("\\.");
        String[] remoteParts = v2.split("\\.");

        int length = Math.max(currentParts.length, remoteParts.length);
        for (int i = 0; i < length; i++) {
            int currentPart = 0;
            int remotePart = 0;
            if (i < currentParts.length) {
                String cleaned = currentParts[i].replaceAll("[^0-9]", "");
                if (!cleaned.isEmpty()) {
                    try { currentPart = Integer.parseInt(cleaned); } catch (NumberFormatException e) { currentPart = 0; }
                }
            }
            if (i < remoteParts.length) {
                String cleaned = remoteParts[i].replaceAll("[^0-9]", "");
                if (!cleaned.isEmpty()) {
                    try { remotePart = Integer.parseInt(cleaned); } catch (NumberFormatException e) { remotePart = 0; }
                }
            }

            if (remotePart > currentPart)
                return true;
            if (remotePart < currentPart)
                return false;
        }

        return false;
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
}
