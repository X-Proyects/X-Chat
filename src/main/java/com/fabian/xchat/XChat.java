package com.fabian.xchat;

import com.fabian.xchat.commands.CustomCommand;
import com.fabian.xchat.commands.XChatCommand;
import com.fabian.xchat.listeners.ChatListener;
import com.fabian.xchat.managers.*;
import com.fabian.xchat.messaging.MessagingService;
import com.fabian.xchat.messaging.RedisMessagingService;
import com.fabian.xchat.storage.*;
import com.fabian.xchat.utils.ColorUtils;
import com.fabian.xchat.utils.DebugLogger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.command.CommandMap;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.Field;
import java.util.*;
import java.util.regex.Pattern;

public class XChat extends JavaPlugin {

    private static XChat instance;

    public static XChat getInstance() {
        return instance;
    }

    public void logInfo(String message) {
        getLogger().info(message);
    }

    public void logError(String message) {
        getLogger().severe(message);
    }

    public void logWarning(String message) {
        getLogger().warning(message);
    }

    private LanguageManager languageManager;
    private ConfigManager configManager;
    private ChatHistoryManager chatHistoryManager;
    private BroadcastManager broadcastManager;
    private DataManager dataManager;
    private TagsManager tagsManager;
    private StorageProvider storageProvider;
    private RedisStorageProvider redisStorage; // kept reference for MessagingService
    private MySQLStorageProvider mysqlStorage; // kept reference for 'both' mode shutdown
    private MessagingService messagingService;
    private com.fabian.xchat.utils.UpdateChecker updateChecker;
    private DependencyManager dependencyManager;
    private FileConfiguration deathsConfig;
    private File deathsFile;
    private FileConfiguration formatsConfig;
    private File formatsFile;
    private final Map<UUID, UUID> lastMessageTarget = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<UUID> spyEnabled = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<UUID, Set<UUID>> ignoredPlayers = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<Command> registeredCommands = new ArrayList<>();

    // Cross-server player cache: name -> last heartbeat timestamp
    private final Map<String, Long> crossServerPlayers = new java.util.concurrent.ConcurrentHashMap<>();
    // Cross-server reply name cache: player UUID -> target name (for cross-server /r)
    private final Map<UUID, String> crossServerReplyNames = new java.util.concurrent.ConcurrentHashMap<>();
    // Task ID for player list heartbeat
    private com.fabian.xchat.utils.SchedulerUtil.TaskWrapper playerListHeartbeatTask = null;

    @Override
    public void onEnable() {
        instance = this;

        try {
            // Initialize config managers first
            this.configManager = new ConfigManager(this);
            loadDeathsConfig();
            loadFormatsConfig();
            this.languageManager = new LanguageManager(this);
            DebugLogger.debug("Config", "ConfigManager and LanguageManager initialized");
        } catch (Exception e) {
            DebugLogger.debug("Config", "Failed to initialize config managers", e);
            e.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // Load libraries before anything else
        DebugLogger.debug("Dependency", "Initializing DependencyManager...");
        this.dependencyManager = new DependencyManager(this);
        this.dependencyManager.loadDependencies();

        // Initialize remaining managers
        try {
            this.chatHistoryManager = new ChatHistoryManager(this);
            DebugLogger.debug("Init", "ChatHistoryManager initialized");
            this.broadcastManager = new BroadcastManager(this);
            DebugLogger.debug("Init", "BroadcastManager initialized");
            this.dataManager = new DataManager(this);
            this.dataManager.loadIgnoredPlayers(this.ignoredPlayers);
            this.tagsManager = new TagsManager(this);
            DebugLogger.debug("Init", "TagsManager initialized");

            // Initialize cross-server storage
            initCrossServerStorage();

            // Initialize ColorUtils with config (PAPI behavior, etc.)
            com.fabian.xchat.utils.ColorUtils.init(getConfig());

            // Start cross-server player list sync
            if (messagingService != null && messagingService.isEnabled()) {
                startPlayerListHeartbeat();
                requestCrossServerPlayerList();
            }

            // Register base command /xchat
            XChatCommand commandExecutor = new XChatCommand(this);
            getCommand("xchat").setExecutor(commandExecutor);
            getCommand("xchat").setTabCompleter(commandExecutor);
            DebugLogger.debug("Command", "Base /xchat command registered");

            // Register dynamic commands from config.yml
            registerDynamicCommands();
            DebugLogger.debug("Command", "Dynamic commands registered");

            // Register listeners
            getServer().getPluginManager().registerEvents(new com.fabian.xchat.listeners.ChatListener(this), this);
            getServer().getPluginManager().registerEvents(new com.fabian.xchat.listeners.JoinLeaveListener(this), this);
            getServer().getPluginManager().registerEvents(new com.fabian.xchat.listeners.DeathListener(this), this);
            getServer().getPluginManager().registerEvents(new com.fabian.xchat.listeners.UnknownCommandListener(this), this);

            // Hide own namespaced commands from tab-completion (1.13+)
            try {
                Class.forName("org.bukkit.event.player.PlayerCommandSendEvent");
                getServer().getPluginManager().registerEvents(new com.fabian.xchat.listeners.CommandHideListener(), this);
                DebugLogger.debug("Init", "CommandHideListener registered");
            } catch (ClassNotFoundException ignored) {}

            DebugLogger.debug("Init", "All listeners registered");

            // PlaceholderAPI Integration
            if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
                DebugLogger.debug("PAPI", "PlaceholderAPI found, registering expansion");
                new com.fabian.xchat.hooks.XChatExpansion(this).register();
            } else {
                DebugLogger.debug("PAPI", "PlaceholderAPI not found, skipping expansion");
            }

        } catch (Exception e) {
            DebugLogger.debug("Init", "Failed to initialize managers", e);
            e.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // Check for updates
        if (getConfig().getBoolean("updates.check", true)) {
            DebugLogger.debug("Update", "Update checker enabled, scheduling check");
            this.updateChecker = new com.fabian.xchat.utils.UpdateChecker(this);
            com.fabian.xchat.utils.SchedulerUtil.runAsyncDelayed(this, () -> updateChecker.checkForUpdates(), 100L);
        }

        // Register update notification listener
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @EventHandler
            public void onPlayerJoin(org.bukkit.event.player.PlayerJoinEvent event) {
                org.bukkit.entity.Player player = event.getPlayer();
                if (!player.isOp() && !player.hasPermission("xchat.admin")) return;
                if (!getConfig().getBoolean("updates.notify-on-join", true)) return;
                if (updateChecker == null) return;
                if (updateChecker.isUpdateAvailable()) {
                    DebugLogger.debug("UpdateListener", "Notifying admin " + player.getName() + " about update");
                    String current = getDescription().getVersion();
                    String latest = updateChecker.getLatestVersion();
                    player.sendMessage(net.md_5.bungee.api.ChatColor.translateAlternateColorCodes('&',
                            "&8[&bX-Chat&8] &eA new version is available: &a" + latest + " &e(current: &c" + current + "&e)"));
                    player.sendMessage(net.md_5.bungee.api.ChatColor.translateAlternateColorCodes('&',
                            "&8[&bX-Chat&8] &7Download it at: &f" + updateChecker.getDownloadUrl()));
                }
            }
        }, this);

        // Initialize bStats Metrics
        setupMetrics();

        Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&8[&bX-Chat&8] &7----------------------------------------------"));
        Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&8[&bX-Chat&8]   &aEnabled v" + getDescription().getVersion() + "! Chat is now managed."));
        Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&8[&bX-Chat&8]   &fFormat: &eMiniMessage"));
        Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&8[&bX-Chat&8] &7----------------------------------------------"));
    }

    private void loadDeathsConfig() {
        deathsFile = new File(getDataFolder(), "deaths.yml");
        if (!deathsFile.exists()) {
            saveResource("deaths.yml", false);
        }
        deathsConfig = YamlConfiguration.loadConfiguration(deathsFile);
    }

    public void reloadDeathsConfig() {
        if (deathsFile == null) {
            deathsFile = new File(getDataFolder(), "deaths.yml");
        }
        deathsConfig = YamlConfiguration.loadConfiguration(deathsFile);
    }

    private void loadFormatsConfig() {
        formatsFile = new File(getDataFolder(), "formats.yml");
        if (!formatsFile.exists()) {
            saveResource("formats.yml", false);
        }
        formatsConfig = YamlConfiguration.loadConfiguration(formatsFile);
    }

    public void reloadFormatsConfig() {
        if (formatsFile == null) {
            formatsFile = new File(getDataFolder(), "formats.yml");
        }
        formatsConfig = YamlConfiguration.loadConfiguration(formatsFile);
    }

    public FileConfiguration getFormatsConfig() {
        return formatsConfig;
    }

    public void registerDynamicCommands() {
        // Desregistrar comandos previamente creados (para reloads)
        unregisterDynamicCommands();

        try {
            Field commandMapField = Bukkit.getServer().getClass().getDeclaredField("commandMap");
            commandMapField.setAccessible(true);
            CommandMap commandMap = (CommandMap) commandMapField.get(Bukkit.getServer());

            registerSingleDynamicCommand(commandMap, "clear-chat", "clear");
            registerSingleDynamicCommand(commandMap, "msg", "msg");
            registerSingleDynamicCommand(commandMap, "reply", "reply");
            registerSingleDynamicCommand(commandMap, "social-spy", "spy");
            registerSingleDynamicCommand(commandMap, "ignore", "ignore");
            registerSingleDynamicCommand(commandMap, "sudo", "sudo");
            registerSingleDynamicCommand(commandMap, "chatlog", "chatlog");
            registerSingleDynamicCommand(commandMap, "broadcast", "broadcast");

            // Sincronizar comandos con todos los clientes (jugadores online)
            for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
                player.updateCommands();
            }

        } catch (Exception e) {
            logError("Error registering dynamic commands via reflection: " + e.getMessage());
        }
    }

    private void registerSingleDynamicCommand(CommandMap commandMap, String configKey, String type) {
        String name = getConfig().getString("commands." + configKey + ".name");
        if (name == null || name.trim().isEmpty()) return;

        List<String> aliases = getConfig().getStringList("commands." + configKey + ".aliases");
        String permission = getConfig().getString("commands." + configKey + ".permission");

        CustomCommand customCmd = new CustomCommand(this, name, type, aliases, permission);
        commandMap.register("xchat", customCmd);
        registeredCommands.add(customCmd);
    }

    @SuppressWarnings("unchecked")
    public void unregisterDynamicCommands() {
        try {
            Field commandMapField = Bukkit.getServer().getClass().getDeclaredField("commandMap");
            commandMapField.setAccessible(true);
            CommandMap commandMap = (CommandMap) commandMapField.get(Bukkit.getServer());

            if (commandMap instanceof SimpleCommandMap) {
                SimpleCommandMap simpleCommandMap = (SimpleCommandMap) commandMap;
                Field knownCommandsField = SimpleCommandMap.class.getDeclaredField("knownCommands");
                knownCommandsField.setAccessible(true);
                Map<String, Command> knownCommands = (Map<String, Command>) knownCommandsField.get(simpleCommandMap);

                for (Command cmd : registeredCommands) {
                    knownCommands.remove(cmd.getName());
                    knownCommands.remove("xchat:" + cmd.getName());
                    for (String alias : cmd.getAliases()) {
                        knownCommands.remove(alias);
                        knownCommands.remove("xchat:" + alias);
                    }
                }
                registeredCommands.clear();
            }
        } catch (Exception e) {
            logWarning("Error unregistering dynamic commands: " + e.getMessage());
        }
    }

    // ─── Reply / SocialSpy / Ignore ───────────────────────────

    public UUID getLastMessageTarget(UUID player) {
        return lastMessageTarget.get(player);
    }

    public Map<UUID, UUID> getLastMessageTargetMap() {
        return lastMessageTarget;
    }

    public void setLastMessageTarget(UUID player, UUID target) {
        if (player != null && target != null) {
            lastMessageTarget.put(player, target);
        }
    }

    public boolean isSpyEnabled(UUID player) {
        return spyEnabled.contains(player);
    }

    public boolean toggleSpy(UUID player) {
        if (spyEnabled.contains(player)) {
            spyEnabled.remove(player);
            return false;
        } else {
            spyEnabled.add(player);
            return true;
        }
    }

    public boolean toggleIgnore(UUID player, UUID target) {
        Set<UUID> ignored = ignoredPlayers.computeIfAbsent(player, k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
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

    public void saveIgnoredPlayers() {
        if (dataManager != null) {
            dataManager.saveIgnoredPlayers(ignoredPlayers);
        }
    }

    public boolean isIgnoring(UUID player, UUID target) {
        Set<UUID> ignored = ignoredPlayers.get(player);
        return ignored != null && ignored.contains(target);
    }

    public Set<UUID> getIgnoredPlayersFor(Player player) {
        return ignoredPlayers.get(player.getUniqueId());
    }

    public void cleanupPlayer(UUID player) {
        // Clear reply target
        lastMessageTarget.remove(player);
        spyEnabled.remove(player);
        crossServerReplyNames.remove(player);
        if (chatHistoryManager != null) {
            chatHistoryManager.unloadPlayer(player);
        }
        // NO removemos ignoredPlayers para que persistan en memoria y se guarden en data.yml
    }

    // ─── Cross-Server Player List ────────────────────────────

    /**
     * Returns the cross-server player cache (name -> timestamp).
     * Used by tab-completer to show players from other servers.
     */
    public Map<String, Long> getCrossServerPlayers() {
        return crossServerPlayers;
    }

    public void setCrossServerReplyName(UUID player, String targetName) {
        if (targetName != null) {
            crossServerReplyNames.put(player, targetName);
        } else {
            crossServerReplyNames.remove(player);
        }
    }

    public String getCrossServerReplyName(UUID player) {
        return crossServerReplyNames.get(player);
    }

    /**
     * Publishes a player list update (join/leave/request) to other servers.
     */
    public void publishPlayerUpdate(String name, boolean join) {
        if (messagingService != null && messagingService.isEnabled()) {
            String action = join ? "add" : "remove";
            messagingService.publish("playerlist", name, "", getServerName(), action);
        }
    }

    /**
     * Requests other servers to send their player lists.
     */
    public void requestCrossServerPlayerList() {
        if (messagingService != null && messagingService.isEnabled()) {
            messagingService.publish("playerlist", "", "", getServerName(), "request");
        }
    }

    /**
     * Starts a heartbeat that cleans stale player entries and re-publishes local players.
     */
    private void startPlayerListHeartbeat() {
        // Cancel previous task if any
        if (playerListHeartbeatTask != null) {
            playerListHeartbeatTask.cancel();
        }
        playerListHeartbeatTask = com.fabian.xchat.utils.SchedulerUtil.runAsyncTimer(this, () -> {
            long now = System.currentTimeMillis();
            // Remove entries older than 90 seconds (no heartbeat)
            crossServerPlayers.entrySet().removeIf(entry -> now - entry.getValue() > 90000);

            // Re-publish local players as heartbeat
            for (Player p : Bukkit.getOnlinePlayers()) {
                publishPlayerUpdate(p.getName(), true);
            }
        }, 6000L, 6000L); // Every 5 minutes
    }

    // ─── Cross-Server Storage Init ───────────────────────────

    /**
     * Checks if MySQL connection settings are still at their default values.
     * Default: host=localhost, port=3306, database=xchat, username=root, password=""
     */
    private boolean isMySQLDefault() {
        String host = getConfig().getString("cross-server.mysql.host", "localhost");
        int port = getConfig().getInt("cross-server.mysql.port", 3306);
        String db = getConfig().getString("cross-server.mysql.database", "xchat");
        String user = getConfig().getString("cross-server.mysql.username", "root");
        String pass = getConfig().getString("cross-server.mysql.password", "");
        return "localhost".equalsIgnoreCase(host)
                && port == 3306
                && "xchat".equalsIgnoreCase(db)
                && "root".equalsIgnoreCase(user)
                && (pass == null || pass.isEmpty());
    }

    /**
     * Checks if Redis connection settings are still at their default values.
     * Default: host=localhost, port=6379, password="", database=0
     */
    private boolean isRedisDefault() {
        String host = getConfig().getString("cross-server.redis.host", "localhost");
        int port = getConfig().getInt("cross-server.redis.port", 6379);
        String pass = getConfig().getString("cross-server.redis.password", "");
        int db = getConfig().getInt("cross-server.redis.database", 0);
        return "localhost".equalsIgnoreCase(host)
                && port == 6379
                && (pass == null || pass.isEmpty())
                && db == 0;
    }

    private void initCrossServerStorage() {
        String storageType = getConfig().getString("cross-server.storage", "yaml");
        String serverName = getConfig().getString("cross-server.server-name", "server1");

        // ── MySQL (persistent storage, used for 'mysql' and 'both') ──
        if ("mysql".equalsIgnoreCase(storageType) || "both".equalsIgnoreCase(storageType)) {
            if (isMySQLDefault()) {
                logWarning("Cross-server storage is set to '" + storageType + "' but MySQL connection settings are still at default values (localhost/root/empty password). Falling back to YAML. Please configure your MySQL settings in config.yml.");
                storageType = ("both".equalsIgnoreCase(getConfig().getString("cross-server.storage", "yaml"))) ? "redis" : "yaml";
            } else {
                String host = getConfig().getString("cross-server.mysql.host", "localhost");
                int port = getConfig().getInt("cross-server.mysql.port", 3306);
                String db = getConfig().getString("cross-server.mysql.database", "xchat");
                String user = getConfig().getString("cross-server.mysql.username", "root");
                String pass = getConfig().getString("cross-server.mysql.password", "");

                mysqlStorage = new MySQLStorageProvider(this, host, port, db, user, pass);
                mysqlStorage.init();
                DebugLogger.debug("Init", "MySQL storage initialized");
            }
        }

        // ── Redis (storage and/or pub/sub messaging) ──
        if ("redis".equalsIgnoreCase(storageType) || "both".equalsIgnoreCase(storageType)) {
            if (isRedisDefault()) {
                logWarning("Cross-server storage is set to '" + storageType + "' but Redis connection settings are still at default values (localhost/empty password). Falling back to YAML. Please configure your Redis settings in config.yml.");
                storageType = "yaml";
            } else {
                String host = getConfig().getString("cross-server.redis.host", "localhost");
                int port = getConfig().getInt("cross-server.redis.port", 6379);
                String pass = getConfig().getString("cross-server.redis.password", "");
                int db = getConfig().getInt("cross-server.redis.database", 0);

                redisStorage = new RedisStorageProvider(this, host, port, pass, db);
                redisStorage.init();
                DebugLogger.debug("Init", "Redis storage initialized");
            }
        }

        // ── Determine primary storage provider ──
        if ("mysql".equalsIgnoreCase(storageType)) {
            storageProvider = mysqlStorage;
            DebugLogger.debug("Init", "Storage provider: MySQL");
        } else if ("redis".equalsIgnoreCase(storageType)) {
            storageProvider = redisStorage;
            DebugLogger.debug("Init", "Storage provider: Redis");
        } else if ("both".equalsIgnoreCase(storageType)) {
            // MySQL for persistent storage, Redis only for pub/sub
            storageProvider = mysqlStorage;
            DebugLogger.debug("Init", "Storage provider: MySQL (Redis for messaging)");
        } else {
            // yaml (default)
            storageProvider = new YamlStorageProvider(this);
            storageProvider.init();
            if (storageProvider instanceof YamlStorageProvider) {
                ((YamlStorageProvider) storageProvider).loadIgnoredPlayersInto(ignoredPlayers);
            }
            DebugLogger.debug("Init", "Storage provider: YAML (default)");
        }

        // ── Cross-server messaging (Redis pub/sub) ──
        if (redisStorage != null && getConfig().getBoolean("cross-server.messaging.enabled", true)) {
            messagingService = new RedisMessagingService(this, redisStorage, serverName);
            messagingService.init();

            // Register handler for incoming cross-server messages
            messagingService.onMessage((channel, senderName, senderUUID, originServer, message) -> {
                handleIncomingCrossServerMessage(channel, senderName, senderUUID, originServer, message);
            });

            DebugLogger.debug("Init", "Messaging service: Redis pub/sub (server=" + serverName + ")");
        } else {
            DebugLogger.debug("Init", "Messaging service: disabled (local-only)");
        }
    }

    /**
     * Reinitializes cross-server storage and messaging (for /xchat reload).
     */
    public void reinitCrossServer() {
        DebugLogger.debug("Init", "Reinitializing cross-server storage and messaging...");

        // Shutdown existing messaging
        if (messagingService != null) {
            messagingService.shutdown();
            messagingService = null;
        }
        // Shutdown existing storage
        if (storageProvider != null) {
            storageProvider.shutdown();
            storageProvider = null;
        }
        if (mysqlStorage != null) {
            mysqlStorage.shutdown();
            mysqlStorage = null;
        }
        if (redisStorage != null) {
            redisStorage.shutdown();
            redisStorage = null;
        }

        // Reinitialize
        initCrossServerStorage();

        // Restart player list
        crossServerPlayers.clear();
        if (messagingService != null && messagingService.isEnabled()) {
            startPlayerListHeartbeat();
            requestCrossServerPlayerList();
        }

        // Re-init ColorUtils
        com.fabian.xchat.utils.ColorUtils.init(getConfig());

        DebugLogger.debug("Init", "Cross-server reinitialized");
    }

    // ─── Getters ─────────────────────────────────────────────

    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public ChatHistoryManager getChatHistoryManager() {
        return chatHistoryManager;
    }

    public BroadcastManager getBroadcastManager() {
        return broadcastManager;
    }

    public com.fabian.xchat.utils.UpdateChecker getUpdateChecker() {
        return updateChecker;
    }

    public DataManager getDataManager() {
        return dataManager;
    }

    public StorageProvider getStorageProvider() {
        return storageProvider;
    }

    public String getServerName() {
        return getConfig().getString("cross-server.server-name", "server1");
    }

    // ─── Cross-Server Message Handler ────────────────────────

    /**
     * Handles incoming cross-server messages from Redis pub/sub.
     * Called on the main server thread via the timer in RedisMessagingService.
     */
    private void handleIncomingCrossServerMessage(String channel, String senderName, String senderUUID, String originServer, String message) {
        DebugLogger.debug("CrossServer", "Received [" + channel + "] from " + originServer + ": " + senderName + " -> " + message.substring(0, Math.min(message.length(), 80)));

        // Server prefix configuration
        boolean showPrefix = getConfig().getBoolean("cross-server.server-prefix.enabled", true);
        String serverPrefixFormat = getConfig().getString("cross-server.server-prefix.format", "&8[&e{server}&8] ");
        String serverPrefix = showPrefix ? ChatColor.translateAlternateColorCodes('&',
                serverPrefixFormat.replace("{server}", originServer)) : "";

        switch (channel) {
            case "chat": {
                // Cross-server chat message
                // Payload format (4 lines, newline-delimited):
                //   Line 1: legacy formatted message (§-coded, Spigot fallback)
                //   Line 2: MiniMessage format string (with <message> placeholder) or empty
                //   Line 3: MiniMessage raw message text (mentions/links/tags) or empty
                //   Line 4: pre-mention plain text (for cross-server mention sound detection)
                String[] parts = message.split("\n", 4);
                String legacyMessage = parts[0];
                String mmFormat = (parts.length > 1 && !parts[1].isEmpty()) ? parts[1] : null;
                String mmRaw = (parts.length > 2 && !parts[2].isEmpty()) ? parts[2] : null;
                String preMentionRaw = (parts.length > 3 && !parts[3].isEmpty()) ? parts[3] : null;

                // Process mentions for local players (sound only) using pre-mention plain text
                if (preMentionRaw != null && getConfig().getBoolean("chat-settings.mentions.enabled", true)) {
                    processCrossServerMentions(preMentionRaw);
                }

                UUID senderUuid = null;
                try { senderUuid = UUID.fromString(senderUUID); } catch (IllegalArgumentException ignored) {}

                // Try to build Component from MiniMessage strings (preserves ALL formatting)
                boolean displayed = false;
                if (mmFormat != null && mmRaw != null && ColorUtils.isPaperAdventureAvailable()) {
                    try {
                        MiniMessage mm = MiniMessage.miniMessage();
                        Component msgComp = mm.deserialize(mmRaw);
                        Component fullComp = mm.deserialize(mmFormat,
                                Placeholder.component("message", msgComp));
                        Component prefixComp = LegacyComponentSerializer.legacyAmpersand().deserialize(serverPrefix);
                        Component finalComp = Component.text().append(prefixComp).append(fullComp).build();

                        for (Player p : Bukkit.getOnlinePlayers()) {
                            if (senderUuid != null && isIgnoring(p.getUniqueId(), senderUuid)) continue;
                            if (storageProvider != null && !(storageProvider instanceof YamlStorageProvider) && senderUuid != null) {
                                try { if (storageProvider.isIgnoring(p.getUniqueId(), senderUuid)) continue; } catch (Exception ignored) {}
                            }
                            p.sendMessage(finalComp);
                        }
                        displayed = true;
                    } catch (Throwable t) {
                        logWarning("[X-Chat] Failed to parse cross-server MiniMessage, falling back to legacy. Error: " + t.getMessage());
                        DebugLogger.debug("CrossServer", "Failed to parse cross-server MiniMessage", t);
                    }
                }

                // Fallback: legacy message (Spigot or MiniMessage parse failed)
                if (!displayed) {
                    String displayMessage = legacyMessage;
                    // For Spigot, re-process auto-links on pre-mention raw text for basic formatting
                    if (preMentionRaw != null && !ColorUtils.isPaperAdventureAvailable()) {
                        String processedRaw = reprocessCrossServerLinks(preMentionRaw);
                        if (!processedRaw.equals(preMentionRaw)) {
                            String simpleFormat = "<gray>" + senderName + " <dark_gray>» " + processedRaw;
                            displayMessage = ColorUtils.toLegacyString(MiniMessage.miniMessage().deserialize(simpleFormat));
                        }
                    }

                    String finalDisplay = serverPrefix + displayMessage;
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (senderUuid != null && isIgnoring(p.getUniqueId(), senderUuid)) continue;
                        if (storageProvider != null && !(storageProvider instanceof YamlStorageProvider) && senderUuid != null) {
                            try { if (storageProvider.isIgnoring(p.getUniqueId(), senderUuid)) continue; } catch (Exception ignored) {}
                        }
                        p.sendMessage(finalDisplay);
                    }
                    Bukkit.getConsoleSender().sendMessage(finalDisplay);

                    // Save to chat history for cross-server messages
                    if (chatHistoryManager != null && senderUuid != null) {
                        chatHistoryManager.addMessage(senderUuid, finalDisplay);
                    }
                } else {
                    // Console: use legacy fallback when MiniMessage display succeeded
                    Bukkit.getConsoleSender().sendMessage(serverPrefix + legacyMessage);
                    // Save to chat history
                    if (chatHistoryManager != null && senderUuid != null) {
                        chatHistoryManager.addMessage(senderUuid, serverPrefix + legacyMessage);
                    }
                }

                // Save to chat log DB if available
                if (storageProvider != null && senderUuid != null) {
                    storageProvider.saveChatLog(senderUuid, senderName, originServer, legacyMessage);
                }
                break;
            }
            case "pm": {
                // Cross-server PM: message format = targetName|senderName|messageContent
                String[] pmParts = message.split("\\|", 3);
                if (pmParts.length == 3) {
                    String targetName = pmParts[0];
                    String pmSenderName = pmParts[1];
                    String pmContent = pmParts[2];

                    // If the target player is on THIS server, deliver the message
                    Player target = Bukkit.getPlayer(targetName);
                    if (target != null && target.isOnline()) {
                        // Check if target is ignoring the sender (cross-server)
                        UUID senderUuid = null;
                        try { senderUuid = UUID.fromString(senderUUID); } catch (IllegalArgumentException ignored) {}

                        if (senderUuid != null) {
                            if (isIgnoring(target.getUniqueId(), senderUuid)) return;
                            if (storageProvider != null && !(storageProvider instanceof YamlStorageProvider)) {
                                try {
                                    if (storageProvider.isIgnoring(target.getUniqueId(), senderUuid)) return;
                                } catch (Exception ignored) {}
                            }
                        }

                        String receiverMsg = getLanguageManager().getMessage("msg-format-receiver")
                                .replace("%sender%", pmSenderName)
                                .replace("%message%", pmContent);

                        // Apply interactive name for cross-server sender (hover with server info + click to reply)
                        if (getConfig().getBoolean("chat-settings.interactive-name.enabled")) {
                            List<String> hoverLines = getConfig().getStringList("chat-settings.interactive-name.hover-text");
                            String clickValue = getConfig().getString("chat-settings.interactive-name.click-value", "/msg %player_name% ");
                            String clickAction = getConfig().getString("chat-settings.interactive-name.click-action", "SUGGEST_COMMAND");

                            StringBuilder hoverBuilder = new StringBuilder();
                            for (String line : hoverLines) {
                                String resolved = line.replace("%player_name%", pmSenderName);
                                resolved = ColorUtils.applyPapi(target, resolved);
                                if (hoverBuilder.length() > 0) hoverBuilder.append("<newline>");
                                hoverBuilder.append(resolved);
                            }
                            // Append cross-server origin info
                            hoverBuilder.append("<newline><gray>Server: <white>").append(originServer).append("</white>");
                            String hoverText = hoverBuilder.toString();
                            String resolvedClickValue = clickValue.replace("%player_name%", pmSenderName);
                            String wrappedName = "<hover:show_text:'" + hoverText + "'><click:" + clickAction.toLowerCase() + ":'" + resolvedClickValue + "'>" + pmSenderName + "</click></hover>";
                            receiverMsg = receiverMsg.replace(pmSenderName, wrappedName);
                        }

                        ColorUtils.sendComponent(target, ColorUtils.format(target, receiverMsg));

                        // Set reply target for the RECEIVER so /r works cross-server
                        setCrossServerReplyName(target.getUniqueId(), pmSenderName);
                        getLastMessageTargetMap().remove(target.getUniqueId());

                        // Send to local spies on this server only
                        // (sender's server already published spy to Redis for all other servers)
                        String spyMsg = getLanguageManager().getMessage("social-spy-format")
                                .replace("%sender%", pmSenderName)
                                .replace("%target%", targetName)
                                .replace("%message%", pmContent);
                        String spyLegacy = ColorUtils.toLegacyString(ColorUtils.format(null, spyMsg));
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            if (p.hasPermission("xchat.command.spy") && isSpyEnabled(p.getUniqueId())
                                    && !p.getUniqueId().equals(target.getUniqueId())) {
                                p.sendMessage(spyLegacy);
                            }
                        }
                    }
                }
                break;
            }
            case "spy": {
                // Cross-server social spy message — send to all local spies
                // No server prefix for spy messages (they already include sender/target info)
                String spyMsg = ChatColor.translateAlternateColorCodes('&', message);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.hasPermission("xchat.command.spy") && isSpyEnabled(p.getUniqueId())) {
                        p.sendMessage(spyMsg);
                    }
                }
                break;
            }
            case "broadcast": {
                // Cross-server broadcast — message may contain multiple lines
                // First line may be "0" or "1" flag for show-server-prefix (from auto-announcements)
                String[] bLines = message.split("\n");
                boolean usePrefix = true;
                int startIdx = 0;
                if (bLines.length > 0 && (bLines[0].equals("0") || bLines[0].equals("1"))) {
                    usePrefix = bLines[0].equals("1");
                    startIdx = 1;
                }
                String bcPrefix = usePrefix ? serverPrefix : "";
                for (int i = startIdx; i < bLines.length; i++) {
                    String line = bLines[i];
                    if (line.isEmpty()) continue;
                    String bcMsg = ChatColor.translateAlternateColorCodes('&', bcPrefix + line);
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        p.sendMessage(bcMsg);
                    }
                    Bukkit.getConsoleSender().sendMessage(bcMsg);
                }
                break;
            }
            case "join": {
                // Cross-server join message — no server prefix (the message itself is self-explanatory)
                if (!getConfig().getBoolean("cross-server.join-quit.enabled", true)) break;
                String joinMsg = ChatColor.translateAlternateColorCodes('&', message);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.sendMessage(joinMsg);
                }
                Bukkit.getConsoleSender().sendMessage(joinMsg);
                break;
            }
            case "quit": {
                // Cross-server quit message — no server prefix
                if (!getConfig().getBoolean("cross-server.join-quit.enabled", true)) break;
                String quitMsg = ChatColor.translateAlternateColorCodes('&', message);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.sendMessage(quitMsg);
                }
                Bukkit.getConsoleSender().sendMessage(quitMsg);
                break;
            }
            case "playerlist": {
                // Cross-server player list sync
                handlePlayerListMessage(senderName, message, originServer);
                break;
            }
            default:
                DebugLogger.debug("CrossServer", "Unknown channel: " + channel);
                break;
        }
    }

    /**
     * Handles cross-server player list messages.
     * Actions: "add", "remove", "request", "response"
     */
    private void handlePlayerListMessage(String playerName, String action, String originServer) {
        switch (action) {
            case "add":
                if (!playerName.isEmpty()) {
                    crossServerPlayers.put(playerName, System.currentTimeMillis());
                    DebugLogger.debug("CrossServer", "Player list: added " + playerName + " from " + originServer);
                }
                break;
            case "remove":
                if (!playerName.isEmpty()) {
                    crossServerPlayers.remove(playerName);
                    DebugLogger.debug("CrossServer", "Player list: removed " + playerName);
                }
                break;
            case "request":
                // Another server is requesting our player list — send all online players
                for (Player p : Bukkit.getOnlinePlayers()) {
                    publishPlayerUpdate(p.getName(), true);
                }
                DebugLogger.debug("CrossServer", "Player list: responded to request from " + originServer);
                break;
            case "response":
                // A server sent us a player name as a response
                if (!playerName.isEmpty()) {
                    crossServerPlayers.put(playerName, System.currentTimeMillis());
                }
                break;
            default:
                DebugLogger.debug("CrossServer", "Unknown playerlist action: " + action);
                break;
        }
    }

    /**
     * Processes mentions in a raw cross-server message for local players.
     * Plays sound and returns true if any mentions were found.
     * Does NOT modify the message — on Paper, the JSON component preserves formatting;
     * on Spigot, mention highlighting in the message body is not supported cross-server
     * (would require rebuilding the entire formatted message which loses hover/click/links).
     */
    private boolean processCrossServerMentions(String rawMessage) {
        boolean requireSymbol = getConfig().getBoolean("chat-settings.mentions.require-symbol", false);
        String symbol = getConfig().getString("chat-settings.mentions.symbol", "@");
        boolean found = false;

        List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
        onlinePlayers.sort((p1, p2) -> Integer.compare(p2.getName().length(), p1.getName().length()));

        for (Player target : onlinePlayers) {
            String name = target.getName();
            String trigger = requireSymbol ? Pattern.quote(symbol + name) : "\\b" + Pattern.quote(name) + "\\b";
            Pattern pattern = Pattern.compile(trigger, Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher matcher = pattern.matcher(rawMessage);
            if (matcher.find()) {
                found = true;
                try {
                    org.bukkit.Sound sound = org.bukkit.Sound.valueOf(
                            getConfig().getString("chat-settings.mentions.sound", "ENTITY_EXPERIENCE_ORB_PICKUP"));
                    float vol = (float) getConfig().getDouble("chat-settings.mentions.volume", 1.0);
                    float pitch = (float) getConfig().getDouble("chat-settings.mentions.pitch", 1.5);
                    target.playSound(target.getLocation(), sound, vol, pitch);
                } catch (Exception ignored) {}
            }
        }
        return found;
    }

    /**
     * Re-processes auto-links in a raw cross-server message for Spigot servers.
     * On Spigot, the JSON component is not available, so we re-apply link formatting
     * to the raw message text (underlined + blue, since hover/click require Paper).
     */
    private String reprocessCrossServerLinks(String rawMessage) {
        java.util.regex.Pattern URL_PATTERN = java.util.regex.Pattern.compile("(?i)\\b(https?://|www\\.)\\S+\\b");
        java.util.regex.Matcher urlMatcher = URL_PATTERN.matcher(rawMessage);
        StringBuffer sb = new StringBuffer();
        while (urlMatcher.find()) {
            String url = urlMatcher.group();
            String replacement = "<underlined><blue>" + url + "</blue></underlined>";
            urlMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        urlMatcher.appendTail(sb);
        return sb.toString();
    }

    public MessagingService getMessagingService() {
        return messagingService;
    }

    public TagsManager getTagsManager() {
        return tagsManager;
    }

    public FileConfiguration getDeathsConfig() {
        return deathsConfig;
    }

    @Override
    public void onDisable() {
        DebugLogger.debug("Init", "Plugin disabling...");

        // Publish quit for all online players to clear cross-server player list
        // Use publishSync to avoid IllegalPluginAccessException (scheduler is shutting down)
        if (messagingService != null && messagingService.isEnabled()) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (messagingService instanceof RedisMessagingService) {
                    ((RedisMessagingService) messagingService).publishSync("playerlist", p.getName(), "", getServerName(), "remove");
                }
            }
        }

        // Cancel player list heartbeat
        if (playerListHeartbeatTask != null) {
            playerListHeartbeatTask.cancel();
        }

        if (messagingService != null) {
            messagingService.shutdown();
        }
        if (storageProvider != null) {
            storageProvider.shutdown();
        }
        // Close mysqlStorage if 'both' mode (storageProvider is MySQL, but redisStorage also open)
        if (mysqlStorage != null && storageProvider != mysqlStorage) {
            mysqlStorage.shutdown();
        }
        if (redisStorage != null && storageProvider != redisStorage) {
            redisStorage.shutdown();
        }
        if (chatHistoryManager != null) {
            chatHistoryManager.saveAllToDisk();
        }
        if (broadcastManager != null) {
            broadcastManager.stop();
        }
        if (dataManager != null) {
            dataManager.saveIgnoredPlayers(ignoredPlayers);
        }
        unregisterDynamicCommands();

        Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&8[&bX-Chat&8] &7----------------------------------------------"));
        Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&8[&bX-Chat&8]   &cDisabled v" + getDescription().getVersion() + "! Out."));
        Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&8[&bX-Chat&8] &7----------------------------------------------"));
    }

    private void setupMetrics() {
        if (getConfig().getBoolean("settings.metrics", true)) {
            try {
                new org.bstats.bukkit.Metrics(this, 31664);
            } catch (Exception e) {
                logWarning("Could not start bStats Metrics: " + e.getMessage());
            }
        }
    }
}