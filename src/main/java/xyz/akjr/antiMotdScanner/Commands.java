package xyz.akjr.antiMotdScanner;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.io.PrintWriter;

public class Commands implements CommandExecutor {
    private final AntiMotdScanner plugin;
    private final Config config;
    private final Network network;

    public Commands(AntiMotdScanner plugin, Config config, Network network) {
        this.plugin = plugin;
        this.config = config;
        this.network = network;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("ams-reload")) {
            if (sender.isOp() || sender.hasPermission("antimotdscanner.reload") || sender.hasPermission("antimotdscanner.admin")) {
                config.reload();
                if (network != null) {
                    network.reloadCachedIPs();
                }
                sender.sendMessage(ChatColor.GREEN + "Reloaded AntiMotdScanner config and IP cache.");
            } else {
                sender.sendMessage(ChatColor.RED + "Operator status or antimotdscanner.reload permission is required for this command.");
            }
            return true;
        } else if (command.getName().equalsIgnoreCase("ams-purge-cache")) {
            if (sender.isOp() || sender.hasPermission("antimotdscanner.purge") || sender.hasPermission("antimotdscanner.admin")) {
                File directory = new File(plugin.getDataFolder(), "ip-data");
                File file = new File(directory, "ipcache.txt");
                try (PrintWriter pw = new PrintWriter(file)) {
                    // empties the file
                } catch (Exception e) {
                    sender.sendMessage(ChatColor.RED + "Failed to purge ipcache.txt: " + e.getMessage());
                    return true;
                }
                if (network != null) {
                    network.clearCache();
                }
                sender.sendMessage(ChatColor.GREEN + "Purged all IP Cache.");
            } else {
                sender.sendMessage(ChatColor.RED + "Operator status or antimotdscanner.purge permission is required for this command.");
            }
            return true;
        } else if (command.getName().equalsIgnoreCase("ams")) {
            if (sender.isOp() || sender.hasPermission("antimotdscanner.about") || sender.hasPermission("antimotdscanner.admin")) {
                sender.sendMessage(ChatColor.GREEN + "AntiMOTD Scanner v" + plugin.getDescription().getVersion() + " (Minecraft 26.3 port)");
                sender.sendMessage(ChatColor.GRAY + "This plugin prevents ServerListPing events from unknown IP addresses that have never joined the server.");
            } else {
                sender.sendMessage(ChatColor.RED + "Operator status or antimotdscanner.about permission is required for this command.");
            }
            return true;
        }
        return false;
    }
}
