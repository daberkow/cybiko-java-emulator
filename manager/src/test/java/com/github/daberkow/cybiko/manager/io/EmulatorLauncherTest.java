package com.github.daberkow.cybiko.manager.io;

import com.github.daberkow.cybiko.manager.cfs.FlashGeometry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EmulatorLauncherTest {

    @TempDir
    Path tempDir;

    @Test
    void buildCommandLineXtMinimal() {
        EmulatorConfig cfg = new EmulatorConfig();
        Path bootRom = tempDir.resolve("cyrom150.bin");
        Path flashRom = tempDir.resolve("cyos_v1508.bin");
        Path nvram = tempDir.resolve("test.nvram");

        List<String> cmd = EmulatorLauncher.buildCommandLine(
                bootRom, flashRom, nvram, FlashGeometry.XTREME, cfg);

        assertTrue(cmd.contains("--machine"));
        assertEquals("xt", cmd.get(cmd.indexOf("--machine") + 1));
        assertTrue(cmd.contains("--nvram"));
        assertEquals(nvram.toString(), cmd.get(cmd.indexOf("--nvram") + 1));
        assertTrue(cmd.contains(bootRom.toString()));
        assertTrue(cmd.contains(flashRom.toString()));
        assertFalse(cmd.contains("--radio"));
    }

    @Test
    void buildCommandLineV1WithRadio() {
        EmulatorConfig cfg = new EmulatorConfig();
        cfg.setRadioMode("lan");
        cfg.setRadioId("7");
        cfg.setMute(true);
        cfg.setLogging("radio,status");

        Path bootRom = tempDir.resolve("cyrom112.bin");
        Path flashRom = tempDir.resolve("flash_v1246.bin");
        Path nvram = tempDir.resolve("v1.nvram");

        List<String> cmd = EmulatorLauncher.buildCommandLine(
                bootRom, flashRom, nvram, FlashGeometry.AT45DB041, cfg);

        assertEquals("v1", cmd.get(cmd.indexOf("--machine") + 1));
        assertTrue(cmd.contains("--radio"));
        assertEquals("lan", cmd.get(cmd.indexOf("--radio") + 1));
        assertTrue(cmd.contains("--radio-id"));
        assertEquals("7", cmd.get(cmd.indexOf("--radio-id") + 1));
        assertTrue(cmd.contains("--mute"));
        assertTrue(cmd.contains("--logging"));
        assertEquals("radio,status", cmd.get(cmd.indexOf("--logging") + 1));
    }

    @Test
    void buildCommandLineSdrIncludesHostAndPort() {
        EmulatorConfig cfg = new EmulatorConfig();
        cfg.setRadioMode("sdr");
        cfg.setSdrHost("192.168.1.50");
        cfg.setSdrPort("19202");

        Path bootRom = tempDir.resolve("boot.bin");
        Path flashRom = tempDir.resolve("flash.bin");
        Path nvram = tempDir.resolve("test.nvram");

        List<String> cmd = EmulatorLauncher.buildCommandLine(
                bootRom, flashRom, nvram, FlashGeometry.XTREME, cfg);

        assertTrue(cmd.contains("--sdr-host"));
        assertEquals("192.168.1.50", cmd.get(cmd.indexOf("--sdr-host") + 1));
        assertTrue(cmd.contains("--sdr-port"));
        assertEquals("19202", cmd.get(cmd.indexOf("--sdr-port") + 1));
    }

    @Test
    void machineTypeFromGeometry() {
        assertEquals("xt", EmulatorLauncher.machineType(FlashGeometry.XTREME));
        assertEquals("v1", EmulatorLauncher.machineType(FlashGeometry.AT45DB041));
        assertEquals("v2", EmulatorLauncher.machineType(FlashGeometry.AT45DB081));
        assertEquals("v1", EmulatorLauncher.machineType(FlashGeometry.AT45DB161));
    }

    @Test
    void searchRomInDirectory() throws IOException {
        Path romsDir = tempDir.resolve("roms");
        Files.createDirectories(romsDir);
        Path rom = Files.createFile(romsDir.resolve("cyrom150.bin"));

        Path found = EmulatorLauncher.searchRom("cyrom150.bin", List.of(tempDir, romsDir));
        assertNotNull(found);
        assertEquals(rom, found);
    }

    @Test
    void searchRomReturnsNullWhenNotFound() {
        Path found = EmulatorLauncher.searchRom("nonexistent.bin", List.of(tempDir));
        assertNull(found);
    }

    @Test
    void knownRomFilenames() {
        assertEquals("cyrom150.bin", EmulatorLauncher.defaultBootRomName("xt"));
        assertEquals("cyos_v1508.bin", EmulatorLauncher.defaultFlashRomName("xt"));
        assertEquals("cyrom112.bin", EmulatorLauncher.defaultBootRomName("v1"));
        assertEquals("flash_v1246.bin", EmulatorLauncher.defaultFlashRomName("v1"));
    }
}
