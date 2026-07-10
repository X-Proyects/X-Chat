package com.fabian.xchat.managers;

import com.fabian.xchat.XChat;
import com.fabian.xchat.utils.DebugLogger;
import net.byteflux.libby.BukkitLibraryManager;
import net.byteflux.libby.Library;
import net.byteflux.libby.LibraryManager;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class DependencyManager {

    private final XChat plugin;
    private final BukkitLibraryManager libraryManager;

    public DependencyManager(XChat plugin) {
        this.plugin = plugin;
        this.libraryManager = new BukkitLibraryManager(plugin);
        setSharedLibraryPath();
        this.libraryManager.addMavenCentral();
        this.libraryManager.addSonatype();
        this.libraryManager.addRepository("https://repo.papermc.io/repository/maven-public/");
    }

    /**
     * Redirects libby's save directory to plugins/X-API/ so all plugins
     * share a single dependency folder instead of each having their own libs/.
     */
    private void setSharedLibraryPath() {
        try {
            Path sharedPath = Paths.get(plugin.getDataFolder().getParent(), "X-API");
            Files.createDirectories(sharedPath);
            Field field = LibraryManager.class.getDeclaredField("saveDirectory");
            field.setAccessible(true);
            field.set(libraryManager, sharedPath);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not set shared library path (plugins/X-API/): " + e.getMessage());
        }
    }

    public void loadDependencies() {
        DebugLogger.debug("DependencyManager", "Starting dependency load via X-API...");
        try {
            plugin.getLogger().info("Loading runtime dependencies via X-API...");
            loadAdventureDependencies();

            // Always load Redis/MySQL libs (they are small and enable /xchat reload to switch storage)
            loadRedisDependencies();
            loadMySQLDependencies();

            DebugLogger.debug("DependencyManager", "All dependencies loaded successfully");
            plugin.getLogger().info("All dependencies loaded successfully!");
        } catch (Exception e) {
            DebugLogger.debug("DependencyManager", "Failed to load runtime libraries!", e);
            plugin.getLogger().severe("Failed to load runtime libraries! " + e.getMessage());
        }
    }

    private void loadAdventureDependencies() {
        // Paper 1.16.5 does NOT bundle any Adventure libraries.
        // All Adventure modules must be loaded via Libby at runtime.
        // Use 4.10.0 — the earliest version where adventure-text-minimessage exists as a separate artifact.

        Library adventureApi = Library.builder()
                .groupId("net.kyori")
                .artifactId("adventure-api")
                .version("4.10.0")
                .build();

        Library key = Library.builder()
                .groupId("net.kyori")
                .artifactId("adventure-key")
                .version("4.10.0")
                .build();

        Library legacySerializer = Library.builder()
                .groupId("net.kyori")
                .artifactId("adventure-text-serializer-legacy")
                .version("4.10.0")
                .build();

        Library plainSerializer = Library.builder()
                .groupId("net.kyori")
                .artifactId("adventure-text-serializer-plain")
                .version("4.10.0")
                .build();

        Library miniMessage = Library.builder()
                .groupId("net.kyori")
                .artifactId("adventure-text-minimessage")
                .version("4.10.0")
                .build();

        Library examinationApi = Library.builder()
                .groupId("net.kyori")
                .artifactId("examination-api")
                .version("1.1.0")
                .build();

        Library examinationString = Library.builder()
                .groupId("net.kyori")
                .artifactId("examination-string")
                .version("1.1.0")
                .build();

        Library gsonSerializer = Library.builder()
                .groupId("net.kyori")
                .artifactId("adventure-text-serializer-gson")
                .version("4.10.0")
                .build();

        // Gson is required by adventure-text-serializer-gson.
        // Most servers bundle it, but load it explicitly for safety.
        Library gson = Library.builder()
                .groupId("com.google.code.gson")
                .artifactId("gson")
                .version("2.10.1")
                .build();

        libraryManager.loadLibrary(adventureApi);
        libraryManager.loadLibrary(key);
        libraryManager.loadLibrary(legacySerializer);
        libraryManager.loadLibrary(plainSerializer);
        libraryManager.loadLibrary(miniMessage);
        libraryManager.loadLibrary(gsonSerializer);
        libraryManager.loadLibrary(examinationApi);
        libraryManager.loadLibrary(examinationString);
        libraryManager.loadLibrary(gson);
    }

    private void loadRedisDependencies() {
        Library jedis = Library.builder()
                .groupId("redis.clients")
                .artifactId("jedis")
                .version("5.1.0")
                .build();
        libraryManager.loadLibrary(jedis);
        DebugLogger.debug("DependencyManager", "Jedis loaded for Redis support");
    }

    private void loadMySQLDependencies() {
        Library commonsPool = Library.builder()
                .groupId("org.apache.commons")
                .artifactId("commons-pool2")
                .version("2.12.0")
                .build();
        libraryManager.loadLibrary(commonsPool);
        DebugLogger.debug("DependencyManager", "Commons Pool2 loaded (required by HikariCP)");

        Library hikari = Library.builder()
                .groupId("com.zaxxer")
                .artifactId("HikariCP")
                .version("5.1.0")
                .build();
        libraryManager.loadLibrary(hikari);
        DebugLogger.debug("DependencyManager", "HikariCP loaded for MySQL support");

        Library mysql = Library.builder()
                .groupId("com.mysql")
                .artifactId("mysql-connector-j")
                .version("8.4.0")
                .build();
        libraryManager.loadLibrary(mysql);
        DebugLogger.debug("DependencyManager", "MySQL Connector loaded");
    }
}