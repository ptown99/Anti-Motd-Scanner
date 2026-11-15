package xyz.akjr.antiMotdScanner;

import com.comphenix.protocol.wrappers.EnumWrappers;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.server.ServerListPingEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NetworkTest {

    @TempDir
    Path tempDir;

    private AntiMotdScanner plugin;
    private Config config;
    private YamlConfiguration yamlConfig;

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(AntiMotdScanner.class);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("AntiMotdScannerTest"));

        config = mock(Config.class);
        yamlConfig = new YamlConfiguration();
        yamlConfig.set("block-unknown-pings", true);
        yamlConfig.set("enable-ipcache", true);
        yamlConfig.set("log-all-motd-pings", true);
        yamlConfig.set("log-blocked-motd-pings", true);
        yamlConfig.set("log-join-ips", true);
        when(config.getConfig()).thenReturn(yamlConfig);

        Files.createDirectories(tempDir.resolve("ip-data"));
    }

    @Test
    void testCacheLoadingAndPurge() throws Exception {
        Path ipCache = tempDir.resolve("ip-data/ipcache.txt");
        Files.write(ipCache, List.of("# Whitelisted IPs", "192.168.1.100", "10.0.0.1", "   ", ""));

        Network network = new Network(config, plugin, null);

        assertTrue(network.isCachedIP("192.168.1.100"));
        assertTrue(network.isCachedIP("10.0.0.1"));
        assertFalse(network.isCachedIP("# Whitelisted IPs"));
        assertFalse(network.isCachedIP("1.2.3.4"));

        network.clearCache();
        assertFalse(network.isCachedIP("192.168.1.100"));
    }

    @Test
    void testPlayerJoinCachesNewIP() throws Exception {
        Network network = new Network(config, plugin, null);

        assertFalse(network.isCachedIP("198.51.100.42"));

        Player player = mock(Player.class);
        InetSocketAddress address = new InetSocketAddress(InetAddress.getByName("198.51.100.42"), 25565);
        when(player.getAddress()).thenReturn(address);

        PlayerJoinEvent joinEvent = new PlayerJoinEvent(player, "joined");
        network.onPlayerJoin(joinEvent);

        assertTrue(network.isCachedIP("198.51.100.42"));

        Path ipCache = tempDir.resolve("ip-data/ipcache.txt");
        assertTrue(Files.exists(ipCache));
        List<String> lines = Files.readAllLines(ipCache);
        assertTrue(lines.contains("198.51.100.42"));
    }

    @Test
    void testServerListPingBlocksUnknownIP() throws Exception {
        Path ipDataDir = tempDir.resolve("ip-data");
        Files.write(ipDataDir.resolve("ipcache.txt"), List.of("192.168.1.50"));

        Network network = new Network(config, plugin, null);

        // Ping from unknown scanner IP
        ServerListPingEvent scannerPing = mock(ServerListPingEvent.class);
        when(scannerPing.getAddress()).thenReturn(InetAddress.getByName("203.0.113.1"));
        network.onServerListPing(scannerPing);

        verify(scannerPing).setMotd("");
        verify(scannerPing).setMaxPlayers(0);

        // Ping from cached known player IP
        ServerListPingEvent playerPing = mock(ServerListPingEvent.class);
        when(playerPing.getAddress()).thenReturn(InetAddress.getByName("192.168.1.50"));
        network.onServerListPing(playerPing);

        verify(playerPing, never()).setMotd(anyString());
        verify(playerPing, never()).setMaxPlayers(anyInt());
    }

    @Test
    void testStatusIntentDetection() {
        Network network = new Network(config, plugin, null);

        // Modern ClientIntent.STATUS
        assertTrue(network.isStatusIntent(EnumWrappers.ClientIntent.STATUS, null));

        // Modern ClientIntent.LOGIN
        assertFalse(network.isStatusIntent(EnumWrappers.ClientIntent.LOGIN, null));

        // Modern ClientIntent.TRANSFER
        assertFalse(network.isStatusIntent(EnumWrappers.ClientIntent.TRANSFER, null));

        // Legacy integer fallback: 1 = STATUS, 2 = LOGIN
        assertTrue(network.isStatusIntent(null, 1));
        assertFalse(network.isStatusIntent(null, 2));
        assertFalse(network.isStatusIntent(null, null));
    }

    @Test
    void testPlayerLoginLogging() throws Exception {
        Network network = new Network(config, plugin, null);

        Player player = mock(Player.class);
        when(player.getName()).thenReturn("TestHero");
        when(player.getUniqueId()).thenReturn(UUID.fromString("12345678-1234-1234-1234-123456789abc"));

        PlayerLoginEvent loginEvent = new PlayerLoginEvent(player, "localhost", InetAddress.getByName("192.168.1.77"));
        network.onPlayerLogin(loginEvent);

        Path logFile = tempDir.resolve("ip-data/ip-join-logs.txt");
        assertTrue(Files.exists(logFile));
        String content = Files.readString(logFile);
        assertTrue(content.contains("TestHero"));
        assertTrue(content.contains("12345678-1234-1234-1234-123456789abc"));
        assertTrue(content.contains("192.168.1.77"));
    }
}
