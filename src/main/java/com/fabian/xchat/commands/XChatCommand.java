package com.fabian.xchat.commands;

import com.fabian.xchat.XChat;
import com.fabian.xchat.utils.ColorUtils;
import com.fabian.xchat.utils.DebugLogger;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class XChatCommand implements CommandExecutor, TabCompleter {

    private final XChat plugin;

    public XChatCommand(XChat plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        DebugLogger.debug("XChatCommand", "Command executed by " + sender.getName() + ": /xchat " + (args.length > 0 ? args[0] : "(help)"));
        if (!sender.hasPermission("xchat.admin")) {
            com.fabian.xchat.utils.ColorUtils.sendComponent(sender, com.fabian.xchat.utils.ColorUtils.format(null, plugin.getLanguageManager().getMessage("no-permission")));
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                DebugLogger.debug("XChatCommand", "Reload initiated by " + sender.getName());
                plugin.saveIgnoredPlayers();
                plugin.getConfigManager().reloadConfiguration();
                plugin.reloadDeathsConfig();
                plugin.reloadFormatsConfig();
                plugin.getBroadcastManager().reload();
                plugin.getLanguageManager().reload();
                plugin.getTagsManager().loadConfigs();
                plugin.registerDynamicCommands();
                // Reinitialize cross-server storage and messaging (picks up config changes)
                plugin.reinitCrossServer();
                com.fabian.xchat.utils.ColorUtils.sendComponent(sender, com.fabian.xchat.utils.ColorUtils.format(null, plugin.getLanguageManager().getMessage("reloaded")));
                break;

            case "version":
                com.fabian.xchat.utils.ColorUtils.sendComponent(sender, com.fabian.xchat.utils.ColorUtils.format(null, plugin.getLanguageManager().getMessage("plugin-version").replace("%version%", plugin.getDescription().getVersion())));
                break;

            case "update":
                if (plugin.getUpdateChecker() != null) {
                    plugin.getUpdateChecker().checkForUpdates(sender);
                } else {
                    com.fabian.xchat.utils.ColorUtils.sendComponent(sender, com.fabian.xchat.utils.ColorUtils.format(null, plugin.getLanguageManager().getMessage("update-checker-disabled")));
                }
                break;

            case "locate":
                if (args.length < 2) {
                    String current = plugin.getLanguageManager().getCurrentLanguage();
                    List<String> available = plugin.getLanguageManager().getAvailableLanguages();
                    com.fabian.xchat.utils.ColorUtils.sendComponent(sender, com.fabian.xchat.utils.ColorUtils.format(null, plugin.getLanguageManager().getMessage("locate-usage")));
                    com.fabian.xchat.utils.ColorUtils.sendComponent(sender, com.fabian.xchat.utils.ColorUtils.format(null, plugin.getLanguageManager().getMessage("locate-current").replace("%current%", current)));
                    com.fabian.xchat.utils.ColorUtils.sendComponent(sender, com.fabian.xchat.utils.ColorUtils.format(null, plugin.getLanguageManager().getMessage("locate-available").replace("%available%", String.join(", ", available))));
                } else {
                    String newLang = args[1].toLowerCase();
                    boolean success = plugin.getLanguageManager().setLanguage(newLang);
                    if (success) {
                        com.fabian.xchat.utils.ColorUtils.sendComponent(sender, com.fabian.xchat.utils.ColorUtils.format(null, plugin.getLanguageManager().getMessage("locate-success").replace("%lang%", newLang)));
                    } else {
                        List<String> available = plugin.getLanguageManager().getAvailableLanguages();
                        com.fabian.xchat.utils.ColorUtils.sendComponent(sender, com.fabian.xchat.utils.ColorUtils.format(null, plugin.getLanguageManager().getMessage("locate-not-found").replace("%lang%", newLang)));
                        com.fabian.xchat.utils.ColorUtils.sendComponent(sender, com.fabian.xchat.utils.ColorUtils.format(null, plugin.getLanguageManager().getMessage("locate-available").replace("%available%", String.join(", ", available))));
                    }
                }
                break;

            case "help":
                sendHelp(sender);
                break;

            case "forcemessages":
                if (!sender.hasPermission("xchat.admin.forcemessages")) {
                    com.fabian.xchat.utils.ColorUtils.sendComponent(sender, com.fabian.xchat.utils.ColorUtils.format(null, plugin.getLanguageManager().getMessage("no-permission")));
                    break;
                }
                handleForceMessages(sender, args);
                break;

            case "debug":
                if (sender instanceof Player) {
                    Player player = (Player) sender;
                    if (plugin.getConfigManager().debugPlayer != null && plugin.getConfigManager().debugPlayer.equals(player.getUniqueId())) {
                        plugin.getConfigManager().debugPlayer = null;
                        com.fabian.xchat.utils.ColorUtils.sendComponent(player,
                                com.fabian.xchat.utils.ColorUtils.format(player,
                                plugin.getConfig().getString("prefix", "&8[&bX-Chat&8]&r ") + "&7Debug mode: &cdisabled"));
                    } else {
                        plugin.getConfigManager().debugPlayer = player.getUniqueId();
                        com.fabian.xchat.utils.ColorUtils.sendComponent(player,
                                com.fabian.xchat.utils.ColorUtils.format(player,
                                plugin.getConfig().getString("prefix", "&8[&bX-Chat&8]&r ") + "&7Debug mode: &aenabled &7(messages sent to you)"));
                    }
                } else {
                    boolean dbg = plugin.getConfig().getBoolean("debug", false);
                    plugin.getConfig().set("debug", !dbg);
                    plugin.saveConfig();
                    com.fabian.xchat.utils.ColorUtils.sendComponent(sender,
                            com.fabian.xchat.utils.ColorUtils.format(null,
                            plugin.getConfig().getString("prefix", "&8[&bX-Chat&8]&r ") + "&7Debug mode: " + (!dbg ? "&aenabled &7(console)" : "&cdisabled")));
                }
                break;

            default:
                sendHelp(sender);
                break;
        }

        return true;
    }

    private void handleForceMessages(CommandSender sender, String[] args) {
        // args[0] = "forcemessages", real args start at args[1]
        if (args.length < 3) {
            String current = plugin.getLanguageManager().getCurrentLanguage();
            com.fabian.xchat.utils.ColorUtils.sendComponent(sender, com.fabian.xchat.utils.ColorUtils.format(null,
                    plugin.getLanguageManager().getMessage("force-messages-usage")));
            com.fabian.xchat.utils.ColorUtils.sendComponent(sender, com.fabian.xchat.utils.ColorUtils.format(null,
                    plugin.getLanguageManager().getMessage("force-messages-current").replace("%current%", current)));
            return;
        }

        String mode = args[1].toLowerCase();
        String target = args[2].toLowerCase();

        if (!mode.equals("new") && !mode.equals("keep")) {
            com.fabian.xchat.utils.ColorUtils.sendComponent(sender, com.fabian.xchat.utils.ColorUtils.format(null,
                    plugin.getLanguageManager().getMessage("force-messages-invalid-mode")));
            return;
        }

        String currentLang = plugin.getLanguageManager().getCurrentLanguage();

        if (mode.equals("keep")) {
            if (target.equals("all")) {
                int count = plugin.getLanguageManager().forceReloadAllMessages();
                com.fabian.xchat.utils.ColorUtils.sendComponent(sender, com.fabian.xchat.utils.ColorUtils.format(null,
                        plugin.getLanguageManager().getMessage("force-messages-all").replace("%count%", String.valueOf(count))));
            } else {
                List<String> available = plugin.getLanguageManager().getAvailableLanguages();
                if (!available.contains(target)) {
                    com.fabian.xchat.utils.ColorUtils.sendComponent(sender, com.fabian.xchat.utils.ColorUtils.format(null,
                            plugin.getLanguageManager().getMessage("language-not-found").replace("%available%", String.join(", ", available))));
                    return;
                }
                boolean success = plugin.getLanguageManager().forceReloadMessages(target);
                if (success) {
                    if (target.equals(currentLang)) {
                        com.fabian.xchat.utils.ColorUtils.sendComponent(sender, com.fabian.xchat.utils.ColorUtils.format(null,
                                plugin.getLanguageManager().getMessage("force-messages-success").replace("%lang%", target)));
                    } else {
                        com.fabian.xchat.utils.ColorUtils.sendComponent(sender, com.fabian.xchat.utils.ColorUtils.format(null,
                                plugin.getLanguageManager().getMessage("force-messages-no-changes").replace("%lang%", target)));
                    }
                }
            }
        } else { // mode == "new"
            if (target.equals("all")) {
                int count = plugin.getLanguageManager().forceResetAllMessages();
                com.fabian.xchat.utils.ColorUtils.sendComponent(sender, com.fabian.xchat.utils.ColorUtils.format(null,
                        plugin.getLanguageManager().getMessage("force-messages-reset-all").replace("%count%", String.valueOf(count))));
            } else {
                boolean success = plugin.getLanguageManager().forceResetMessages(target);
                if (!success) {
                    List<String> available = plugin.getLanguageManager().getAvailableLanguages();
                    com.fabian.xchat.utils.ColorUtils.sendComponent(sender, com.fabian.xchat.utils.ColorUtils.format(null,
                            plugin.getLanguageManager().getMessage("language-not-found").replace("%available%", String.join(", ", available))));
                    return;
                }
                if (target.equals(currentLang)) {
                    com.fabian.xchat.utils.ColorUtils.sendComponent(sender, com.fabian.xchat.utils.ColorUtils.format(null,
                            plugin.getLanguageManager().getMessage("force-messages-reset-success").replace("%lang%", target)));
                } else {
                    com.fabian.xchat.utils.ColorUtils.sendComponent(sender, com.fabian.xchat.utils.ColorUtils.format(null,
                            plugin.getLanguageManager().getMessage("force-messages-reset-no-active").replace("%lang%", target)));
                }
            }
        }
    }

    private void sendHelp(CommandSender sender) {
        List<String> help = plugin.getLanguageManager().getMessageList("command-help");
        for (String s : help) {
            com.fabian.xchat.utils.ColorUtils.sendComponent(sender, com.fabian.xchat.utils.ColorUtils.format(null, s));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1 && sender.hasPermission("xchat.admin")) {
            String input = args[0].toLowerCase();
            List<String> subcommands = List.of("reload", "version", "update", "locate", "help", "debug", "forcemessages");
            for (String sub : subcommands) {
                if (sub.startsWith(input)) {
                    completions.add(sub);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("forcemessages") && sender.hasPermission("xchat.admin.forcemessages")) {
            String input = args[1].toLowerCase();
            for (String mode : List.of("new", "keep")) {
                if (mode.startsWith(input)) {
                    completions.add(mode);
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("forcemessages") && sender.hasPermission("xchat.admin.forcemessages")) {
            String input = args[2].toLowerCase();
            List<String> languages = plugin.getLanguageManager().getAvailableLanguages();
            if ("all".startsWith(input)) {
                completions.add("all");
            }
            for (String lang : languages) {
                if (lang.toLowerCase().startsWith(input)) {
                    completions.add(lang);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("locate") && sender.hasPermission("xchat.admin")) {
            List<String> available = plugin.getLanguageManager().getAvailableLanguages();
            for (String lang : available) {
                if (lang.toLowerCase().startsWith(args[1].toLowerCase())) {
                    completions.add(lang);
                }
            }
        }
        return completions;
    }
}
