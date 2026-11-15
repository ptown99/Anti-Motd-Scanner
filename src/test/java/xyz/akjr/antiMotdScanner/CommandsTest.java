package xyz.akjr.antiMotdScanner;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommandsTest {

    @TempDir
    Path tempDir;

    private AntiMotdScanner plugin;
    private Config config;
    private Network network;
    private CommandSender sender;
    private Command command;

    @BeforeEach
    void setUp() {
        plugin = mock(AntiMotdScanner.class);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        PluginDescriptionFile desc = mock(PluginDescriptionFile.class);
        when(desc.getVersion()).thenReturn("0.2-26.3");
        when(plugin.getDescription()).thenReturn(desc);

        config = mock(Config.class);
        network = mock(Network.class);
        sender = mock(CommandSender.class);
        command = mock(Command.class);
    }

    @Test
    void testAmsAboutCommandForOp() {
        when(command.getName()).thenReturn("ams");
        when(sender.isOp()).thenReturn(true);

        Commands commands = new Commands(plugin, config, network);
        boolean result = commands.onCommand(sender, command, "ams", new String[0]);

        assertTrue(result);
        verify(sender, atLeastOnce()).sendMessage(contains("AntiMOTD Scanner"));
    }

    @Test
    void testAmsReloadCommand() {
        when(command.getName()).thenReturn("ams-reload");
        when(sender.isOp()).thenReturn(true);

        Commands commands = new Commands(plugin, config, network);
        boolean result = commands.onCommand(sender, command, "ams-reload", new String[0]);

        assertTrue(result);
        verify(config).reload();
        verify(network).reloadCachedIPs();
        verify(sender).sendMessage(contains("Reloaded"));
    }

    @Test
    void testAmsPurgeCacheCommand() throws Exception {
        Path ipDataDir = tempDir.resolve("ip-data");
        Files.createDirectories(ipDataDir);
        Path ipCache = ipDataDir.resolve("ipcache.txt");
        Files.write(ipCache, List.of("1.1.1.1", "2.2.2.2"));

        when(command.getName()).thenReturn("ams-purge-cache");
        when(sender.isOp()).thenReturn(true);

        Commands commands = new Commands(plugin, config, network);
        boolean result = commands.onCommand(sender, command, "ams-purge-cache", new String[0]);

        assertTrue(result);
        verify(network).clearCache();
        assertEquals(0, Files.size(ipCache));
        verify(sender).sendMessage(contains("Purged all IP Cache"));
    }

    @Test
    void testCommandDeniedForNonOp() {
        when(command.getName()).thenReturn("ams-reload");
        when(sender.isOp()).thenReturn(false);
        when(sender.hasPermission(anyString())).thenReturn(false);

        Commands commands = new Commands(plugin, config, network);
        boolean result = commands.onCommand(sender, command, "ams-reload", new String[0]);

        assertTrue(result);
        verify(config, never()).reload();
        verify(sender).sendMessage(contains("required"));
    }
}
