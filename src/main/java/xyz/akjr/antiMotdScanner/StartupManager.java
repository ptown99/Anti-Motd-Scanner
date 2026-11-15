package xyz.akjr.antiMotdScanner;

import org.bukkit.scheduler.BukkitRunnable;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import java.util.Objects;

public class StartupManager {
    private final AntiMotdScanner plugin;
    private final Config config;
    private final Network network;

    public StartupManager(AntiMotdScanner plugin, Config config, Network network) {
        this.plugin = plugin;
        this.config = config;
        this.network = network;
    }

    public boolean initialize() {
        initializeConfig();
        if (!validateDependencies()) {
            return false;
        }
        registerCommands();
        printStartupMessages();
        return true;
    }

    private void registerCommands() {
        Commands commandExecutor = new Commands(plugin, config, network);
        Objects.requireNonNull(plugin.getCommand("ams")).setExecutor(commandExecutor);
        Objects.requireNonNull(plugin.getCommand("ams-reload")).setExecutor(commandExecutor);
        Objects.requireNonNull(plugin.getCommand("ams-purge-cache")).setExecutor(commandExecutor);
    }

    private void initializeConfig() {
        config.createConfig();
    }

    private boolean validateDependencies() {
        if (plugin.getServer().getPluginManager().getPlugin("ProtocolLib") == null) {
            plugin.getLogger().severe("ProtocolLib not found! Disabling plugin.");
            plugin.getServer().getPluginManager().disablePlugin(plugin);
            return false;
        }

        ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();
        if (protocolManager == null) {
            plugin.getLogger().severe("Failed to initialize ProtocolLib's ProtocolManager! Disabling plugin.");
            plugin.getServer().getPluginManager().disablePlugin(plugin);
            return false;
        }
        return true;
    }

    private void printStartupMessages() {
        new BukkitRunnable() {
            @Override
            public void run() {
                plugin.getLogger().info("Server protected by Anti-MOTD-Scanner (AMS).");
            }
        }.runTaskLater(plugin, 225L);
    }
}
