package xyz.akjr.antiMotdScanner;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.injector.temporary.TemporaryPlayerFactory;
import com.comphenix.protocol.wrappers.EnumWrappers;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.server.ServerListPingEvent;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class Network implements Listener {

    private final Config configManager;
    private final AntiMotdScanner plugin;
    private final ProtocolManager protocolManager;
    private final Set<String> cachedIPs;
    private final File ipCacheFile;
    private volatile long lastCacheLoadTime = 0;

    public Network(Config configManager, AntiMotdScanner plugin) {
        this.configManager = configManager;
        this.plugin = plugin;
        this.ipCacheFile = new File(plugin.getDataFolder(), "ip-data/ipcache.txt");
        this.protocolManager = ProtocolLibrary.getProtocolManager();
        this.cachedIPs = ConcurrentHashMap.newKeySet();
        initialize();
    }

    private void initialize() {
        loadCachedIPs();
        blockStatusRequests();
        plugin.getLogger().info("ServerListPing protection initialized for Minecraft 26.3 - Only cached player IPs can ping server");
    }

    public synchronized void loadCachedIPs() {
        if (!ipCacheFile.exists()) {
            plugin.getLogger().info("IP cache file doesn't exist yet - will be created when players join");
            return;
        }

        try {
            List<String> lines = Files.readAllLines(ipCacheFile.toPath());
            cachedIPs.clear();
            for (String line : lines) {
                String ip = line.trim();
                if (!ip.isEmpty() && !ip.startsWith("#")) {
                    cachedIPs.add(ip);
                }
            }
            lastCacheLoadTime = ipCacheFile.lastModified();
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not load /ip-data/ipcache.txt", e);
        }
    }

    public void reloadCachedIPs() {
        if (ipCacheFile.exists() && ipCacheFile.lastModified() > lastCacheLoadTime) {
            loadCachedIPs();
        }
    }

    public synchronized void clearCache() {
        cachedIPs.clear();
        lastCacheLoadTime = System.currentTimeMillis();
    }

    public boolean isCachedIP(String ip) {
        return ip != null && cachedIPs.contains(ip);
    }

    private void blockStatusRequests() {
        // Intercept Handshake packet (PacketType.Handshake.Client.SET_PROTOCOL)
        protocolManager.addPacketListener(new PacketAdapter(plugin,
                ListenerPriority.HIGHEST,
                PacketType.Handshake.Client.SET_PROTOCOL) {

            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (event.isCancelled()) return;

                PacketContainer packet = event.getPacket();
                boolean isStatus = isStatusHandshake(packet);
                if (!isStatus) {
                    return;
                }

                String clientIP = getClientIP(event);

                boolean logMotds = configManager.getConfig().getBoolean("log-all-motd-pings", true);
                if (logMotds) {
                    logMotdPing(clientIP);
                }

                boolean smartDisable = configManager.getConfig().getBoolean("block-unknown-pings", true);
                if (!smartDisable) return;

                reloadCachedIPs();
                if (!isCachedIP(clientIP)) {
                    event.setCancelled(true);
                    logBlocked("Blocked ServerListPing handshake from " + clientIP);
                }
            }
        });

        // Block the status start packet (secondary protection)
        protocolManager.addPacketListener(new PacketAdapter(plugin,
                ListenerPriority.HIGHEST,
                PacketType.Status.Client.START) {

            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (event.isCancelled()) return;

                boolean smartDisable = configManager.getConfig().getBoolean("block-unknown-pings", true);
                if (!smartDisable) return;

                String clientIP = getClientIP(event);
                reloadCachedIPs();
                if (!isCachedIP(clientIP)) {
                    event.setCancelled(true);
                    logBlocked("Blocked StatusRequest from " + clientIP);
                }
            }
        });

        // Block ping packets (final protection)
        protocolManager.addPacketListener(new PacketAdapter(plugin,
                ListenerPriority.HIGHEST,
                PacketType.Status.Client.PING) {

            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (event.isCancelled()) return;

                boolean smartDisable = configManager.getConfig().getBoolean("block-unknown-pings", true);
                if (!smartDisable) return;

                String clientIP = getClientIP(event);
                reloadCachedIPs();
                if (!isCachedIP(clientIP)) {
                    event.setCancelled(true);
                    logBlocked("Blocked PingPacket from " + clientIP);
                }
            }
        });
    }

    private boolean isStatusHandshake(PacketContainer packet) {
        try {
            EnumWrappers.ClientIntent intent = packet.getClientIntents().readSafely(0);
            if (intent != null) {
                return intent == EnumWrappers.ClientIntent.STATUS;
            }
        } catch (Exception ignored) {}

        try {
            Integer nextState = packet.getIntegers().readSafely(1);
            if (nextState != null && nextState == 1) {
                return true;
            }
        } catch (Exception ignored) {}

        return false;
    }

    private String getClientIP(PacketEvent event) {
        try {
            if (event.getPlayer() != null) {
                try {
                    InetSocketAddress addr = event.getPlayer().getAddress();
                    if (addr != null && addr.getAddress() != null) {
                        return addr.getAddress().getHostAddress();
                    }
                } catch (Exception ignored) {}

                try {
                    var injector = TemporaryPlayerFactory.getInjectorFromPlayer(event.getPlayer());
                    if (injector != null && injector.getAddress() instanceof InetSocketAddress inet) {
                        if (inet.getAddress() != null) {
                            return inet.getAddress().getHostAddress();
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return "0.0.0.0";
    }

    private void logBlocked(String message) {
        boolean logBlocked = configManager.getConfig().getBoolean("log-blocked-motd-pings", true);
        if (!logBlocked) return;

        try {
            File logFile = new File(plugin.getDataFolder(), "ip-data/blocked-motd-logs.txt");
            if (!logFile.exists()) {
                logFile.createNewFile();
            }

            try (PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)))) {
                String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                out.println("[" + timestamp + "] - " + message);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Error writing to blocked-motd-logs.txt", e);
        }
    }

    private void logMotdPing(String ip) {
        try {
            File logFile = new File(plugin.getDataFolder(), "ip-data/motd-ping-logs.txt");
            if (!logFile.exists()) {
                logFile.createNewFile();
            }
            try (PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)))) {
                String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                out.println("[" + timestamp + "] IP: " + ip);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Error writing to motd-ping-logs.txt", e);
        }
    }

    // ################# BUKKIT FALLBACK #################
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onServerListPing(ServerListPingEvent event) {
        boolean smartDisable = configManager.getConfig().getBoolean("block-unknown-pings", true);
        if (!smartDisable) return;
        if (event.getAddress() == null) return;

        String clientIP = event.getAddress().getHostAddress();
        reloadCachedIPs();
        if (!isCachedIP(clientIP)) {
            event.setMotd("");
            event.setMaxPlayers(0);
        }
    }

    // ################# PLAYER #################
    @EventHandler
    public void onPlayerLogin(PlayerLoginEvent event) {
        boolean logIpsToFile = this.configManager.getConfig().getBoolean("log-join-ips", true);
        if (logIpsToFile && event.getAddress() != null) {
            Player player = event.getPlayer();
            try {
                File logFile = new File(plugin.getDataFolder(), "ip-data/ip-join-logs.txt");
                if (!logFile.exists()) {
                    logFile.createNewFile();
                }
                try (PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)))) {
                    String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                    out.println("[" + timestamp + "] - " + player.getName() + " - " + player.getUniqueId() + " - [" + event.getAddress().getHostAddress() + "]");
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Error writing to ip-join-logs.txt", e);
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        boolean cacheOn = this.configManager.getConfig().getBoolean("enable-ipcache", true);
        if (cacheOn && event.getPlayer().getAddress() != null) {
            String playerIP = event.getPlayer().getAddress().getAddress().getHostAddress();
            if (playerIP != null && !playerIP.equals("0.0.0.0") && !cachedIPs.contains(playerIP)) {
                cachedIPs.add(playerIP);
                try {
                    File file = new File(plugin.getDataFolder(), "ip-data/ipcache.txt");
                    if (!file.exists()) {
                        file.createNewFile();
                    }
                    try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, true))) {
                        writer.write(playerIP);
                        writer.newLine();
                    }
                    lastCacheLoadTime = file.lastModified();
                } catch (IOException e) {
                    plugin.getLogger().log(Level.WARNING, "Could not save IP to ipcache.txt: " + playerIP, e);
                }
            }
        }
    }

    public void cleanup() {
        if (protocolManager != null) {
            protocolManager.removePacketListeners(plugin);
        }
    }
}
