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

    /**
     * Compares two version strings and returns true only if `latest` is strictly
     * newer than `current`. Understands pre-release suffixes:
     *   1.3-Beta-10 &lt; 1.3  (a release is newer than any pre-release of the same base)
     *   1.3-Beta-9  &lt; 1.3-Beta-10
     *   1.3         &lt; 1.4
     */
    private boolean isNewer(String current, String latest) {
        try {
            if (current == null || latest == null) return false;

            // Normalize: trim and strip a leading "v"/"V"
            String c = current.trim().replaceFirst("(?i)^v", "");
            String l = latest.trim().replaceFirst("(?i)^v", "");
            if (c.equalsIgnoreCase(l)) return false;

            // Split base version from pre-release suffix: "1.3-Beta-10" -> "1.3" + pre-release
            String cBase = c.split("-")[0].trim();
            String lBase = l.split("-")[0].trim();
            boolean cPre = c.contains("-");
            boolean lPre = l.contains("-");

            // Compare numeric base versions first
            int cmp = compareNumericVersions(cBase, lBase);
            if (cmp != 0) return cmp < 0;

            // Same base version: a release (no suffix) is NEWER than any pre-release.
            // Fixes: "1.3" no longer reports an update when Spigot shows "1.3-Beta-10".
            if (cPre && !lPre) return true;   // current is pre-release, latest is release -> update
            if (!cPre && lPre) return false;  // current is release, latest is pre-release -> no update
            if (cPre && lPre) {
                // Both pre-releases: compare trailing build numbers (Beta-9 vs Beta-10)
                return extractTrailingNumber(l) > extractTrailingNumber(c);
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Compares dotted numeric versions ("1.3" vs "1.3.1").
     * Returns negative if a &lt; b, positive if a &gt; b, zero if equal.
     */
    private int compareNumericVersions(String a, String b) {
        String[] aParts = a.split("\\.");
        String[] bParts = b.split("\\.");
        int length = Math.max(aParts.length, bParts.length);
        for (int i = 0; i < length; i++) {
            int aPart = i < aParts.length ? parseVersionPart(aParts[i]) : 0;
            int bPart = i < bParts.length ? parseVersionPart(bParts[i]) : 0;
            if (aPart != bPart) return Integer.compare(aPart, bPart);
        }
        return 0;
    }

    /** Extracts the trailing number of a pre-release suffix ("Beta-10" -> 10). */
    private int extractTrailingNumber(String version) {
        java.util.regex.Matcher m = TRAILING_NUMBER_PATTERN.matcher(version);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private static final java.util.regex.Pattern TRAILING_NUMBER_PATTERN =
            java.util.regex.Pattern.compile("(\\d+)\\s*$");

    private int parseVersionPart(String part) {
        try {
            return Integer.parseInt(part.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
