package com.fabian.xchat.utils;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * X-Proyects Unified Config Updater (canonical implementation).
 * <p>
 * Identical across every X-Proyects plugin — only the package changes.
 * <p>
 * Updates a local YAML file by adding missing keys from a bundled resource
 * while PRESERVING existing values and comments.
 * <p>
 * FIX vs. legacy versions: missing nested keys are now inserted INSIDE their
 * parent section (at the correct indentation point), instead of being blindly
 * appended to the end of the file — which used to attach them to whatever
 * section happened to be last, corrupting the YAML structure.
 */
public final class ConfigUpdater {

    private ConfigUpdater() {
        // Static utility class — no instances
    }

    /**
     * Updates a local YAML file by adding missing keys from a resource file.
     * Existing keys keep their on-disk values; comments are preserved.
     */
    public static void update(Plugin plugin, String resourcePath, File diskFile) {
        if (!diskFile.exists())
            return;

        try {
            YamlConfiguration resConfig = null;
            try (InputStream resourceStream = plugin.getResource(resourcePath)) {
                if (resourceStream == null)
                    return;

                resConfig = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(resourceStream, StandardCharsets.UTF_8));
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to update " + diskFile.getName() + ": " + e.getMessage());
                return;
            }

            if (resConfig == null) return;

            YamlConfiguration diskConfig = new YamlConfiguration();
            try (InputStreamReader reader = new InputStreamReader(new FileInputStream(diskFile),
                    StandardCharsets.UTF_8)) {
                diskConfig.load(reader);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to read disk config " + diskFile.getName() + ": " + e.getMessage()
                        + " — Skipping update to prevent data loss.");
                return;
            }

            boolean isFlat = true;
            for (String key : resConfig.getKeys(true)) {
                if (key.contains(".")) {
                    isFlat = false;
                    break;
                }
            }

            if (isFlat) {
                updateFlatFile(plugin, resourcePath, diskFile, diskConfig);
            } else {
                updateHierarchicalFile(plugin, resourcePath, diskFile, diskConfig, resConfig);
            }

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to update " + diskFile.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Flat files (no nested sections): rewrite using the resource as the template,
     * substituting on-disk values where keys exist.
     */
    private static void updateFlatFile(Plugin plugin, String resourcePath, File diskFile, YamlConfiguration diskConfig) throws IOException {
        List<String> resLines = new ArrayList<>();
        try (InputStream resStream = plugin.getResource(resourcePath)) {
            if (resStream == null) return;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    resLines.add(line);
                }
            }
        }

        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(diskFile), StandardCharsets.UTF_8))) {
            for (String line : resLines) {
                if (line.trim().startsWith("#") || line.trim().isEmpty()) {
                    writer.write(line);
                    writer.newLine();
                    continue;
                }

                int colonIndex = line.indexOf(':');
                if (colonIndex != -1) {
                    String key = line.substring(0, colonIndex).trim();
                    if (diskConfig.contains(key)) {
                        writer.write(key + ": " + formatValue(diskConfig.get(key)));
                    } else {
                        writer.write(line);
                    }
                } else {
                    writer.write(line);
                }
                writer.newLine();
            }
        }
    }

    /**
     * Hierarchical files: inserts each missing key INSIDE its parent section.
     * <p>
     * Algorithm:
     * <ol>
     *   <li>Read all on-disk lines and build a section map (dotted-path → line index).</li>
     *   <li>For each missing key, locate its parent section's block and compute
     *       the insertion point (after the last entry of that block).</li>
     *   <li>Insert the key with the parent's indentation + 2 spaces. If the parent
     *       itself is missing, create the intermediate sections first.</li>
     * </ol>
     */
    private static void updateHierarchicalFile(Plugin plugin, String resourcePath, File diskFile,
                                               YamlConfiguration diskConfig, YamlConfiguration resConfig) throws IOException {

        // Collect missing keys (dotted paths, parents first for stable insertion)
        Map<String, Object> missing = new LinkedHashMap<>();
        for (String key : resConfig.getKeys(true)) {
            if (!diskConfig.contains(key)) {
                missing.put(key, resConfig.get(key));
            }
        }

        if (missing.isEmpty())
            return;

        // Read the on-disk lines
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(diskFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }

        // Map: dotted section path → index of the first line of its block (the "key:" line itself)
        Map<String, Integer> sectionLineIndex = new LinkedHashMap<>();
        // Stack of (indent, dottedPath) for the current nesting
        List<int[]> indentStack = new ArrayList<>(); // indents
        List<String> pathStack = new ArrayList<>();  // paths

        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String trimmed = raw.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            int colonIndex = trimmed.indexOf(':');
            if (colonIndex == -1) continue;

            String node = trimmed.substring(0, colonIndex).trim();
            // Skip list items
            if (node.startsWith("-")) continue;

            int indent = raw.length() - raw.replaceFirst("^\\s+", "").length();

            // Pop stack entries whose indent is >= current (we are leaving their block)
            while (!indentStack.isEmpty() && indent <= indentStack.get(indentStack.size() - 1)[0]) {
                indentStack.remove(indentStack.size() - 1);
                pathStack.remove(pathStack.size() - 1);
            }

            // Build dotted path
            String dotted = pathStack.isEmpty() ? node : pathStack.get(pathStack.size() - 1) + "." + node;

            boolean isSection = trimmed.endsWith(":") || trimmed.substring(colonIndex + 1).trim().isEmpty();
            if (isSection) {
                sectionLineIndex.put(dotted, i);
                indentStack.add(new int[]{indent});
                pathStack.add(dotted);
            }
        }

        // Insert missing keys, deepest-first within each parent, parents before children.
        // We process the missing map in natural (parent-first) order and apply insertions
        // from the END of the file backwards so earlier indices stay valid.
        List<int[]> insertions = new ArrayList<>(); // {lineIndex, mode} mode 0=insert-after-block
        Map<Integer, List<String>> insertionsText = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : missing.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            String nodeName = key.contains(".") ? key.substring(key.lastIndexOf('.') + 1) : key;
            String parentPath = key.contains(".") ? key.substring(0, key.lastIndexOf('.')) : "";

            // If the key is a section that will be created, remember its expected indentation
            Integer parentIdx = parentPath.isEmpty() ? -1 : sectionLineIndex.get(parentPath);

            // Parent may itself be a missing section — create it as part of the same pass
            List<String> createdSections = new ArrayList<>();
            String walk = parentPath;
            while (!walk.isEmpty() && !sectionLineIndex.containsKey(walk) && !diskConfig.contains(walk)) {
                String walkNode = walk.contains(".") ? walk.substring(walk.lastIndexOf('.') + 1) : walk;
                String walkParent = walk.contains(".") ? walk.substring(0, walk.lastIndexOf('.')) : "";
                int depth = walk.split("\\.").length;
                createdSections.add(0, indentOf(depth) + walkNode + ":");
                // Register it so nested missing children resolve against it
                sectionLineIndex.put(walk, -1); // marker: appended later
                walk = walkParent;
            }

            // Determine insertion base index & indent
            int baseIndex;
            int indentDepth;
            if (parentIdx != null && parentIdx >= 0) {
                baseIndex = endOfBlock(lines, parentIdx);
                indentDepth = key.split("\\.").length; // depth of the key = parent depth + 1
            } else if (parentPath.isEmpty()) {
                baseIndex = lines.size();
                indentDepth = 1;
            } else {
                // Parent was just created (marker -1): append at end after created sections
                baseIndex = lines.size();
                indentDepth = key.split("\\.").length;
            }

            StringBuilder block = new StringBuilder();
            for (String s : createdSections) {
                block.append(s).append('\n');
            }
            if (!(value instanceof ConfigurationSection)) {
                block.append(indentOf(indentDepth)).append(nodeName).append(": ").append(formatValue(value));
            } else {
                block.append(indentOf(indentDepth)).append(nodeName).append(":");
            }

            List<String> texts = insertionsText.computeIfAbsent(baseIndex, k -> new ArrayList<>());
            texts.add(block.toString());
        }

        // Apply insertions from the last index to the first (stable indices)
        List<Integer> indices = new ArrayList<>(insertionsText.keySet());
        indices.sort((a, b) -> Integer.compare(b, a));
        for (Integer idx : indices) {
            int at = Math.min(idx, lines.size());
            List<String> blocks = insertionsText.get(idx);
            for (String block : blocks) {
                String[] blockLines = block.split("\n", -1);
                for (String bl : blockLines) {
                    if (!bl.isEmpty()) {
                        lines.add(at, bl);
                        at++;
                    }
                }
            }
        }

        // Write the file back
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(diskFile), StandardCharsets.UTF_8))) {
            for (String line : lines) {
                writer.write(line);
                writer.newLine();
            }
        }
    }

    /**
     * Finds the last line of the block that starts at sectionIdx (inclusive of nested children).
     */
    private static int endOfBlock(List<String> lines, int sectionIdx) {
        String sectionLine = lines.get(sectionIdx);
        int sectionIndent = sectionLine.length() - sectionLine.replaceFirst("^\\s+", "").length();

        int end = sectionIdx;
        for (int i = sectionIdx + 1; i < lines.size(); i++) {
            String raw = lines.get(i);
            String trimmed = raw.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                end = i; // trailing comment inside block — keep scanning
                continue;
            }
            int indent = raw.length() - raw.replaceFirst("^\\s+", "").length();
            if (indent > sectionIndent) {
                end = i;
            } else {
                break;
            }
        }
        return end + 1; // insertion point: right after the block
    }

    /**
     * Builds an indent string for a 1-based depth (2 spaces per level).
     */
    private static String indentOf(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < depth; i++) {
            sb.append("  ");
        }
        return sb.toString();
    }

    /**
     * Formats a YAML value for writing.
     */
    private static String formatValue(Object value) {
        if (value == null)
            return "''";
        if (value instanceof String) {
            String s = (String) value;
            if (s.contains("'") || s.contains("&") || s.contains("\"") || s.contains(":") || s.contains("#")) {
                return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            }
            return "\"" + s + "\"";
        }
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            if (list.isEmpty()) return "[]";
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                sb.append(formatValue(list.get(i)));
                if (i < list.size() - 1)
                    sb.append(", ");
            }
            sb.append("]");
            return sb.toString();
        }
        return value.toString();
    }
}
