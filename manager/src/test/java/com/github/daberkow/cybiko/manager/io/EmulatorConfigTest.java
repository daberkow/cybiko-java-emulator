package com.github.daberkow.cybiko.manager.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EmulatorConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void saveAndLoadRoundtrip() throws IOException {
        Path config = tempDir.resolve("emulator.properties");

        EmulatorConfig cfg = new EmulatorConfig();
        cfg.setEmulatorJarPath("/opt/cybiko/emulator.jar");
        cfg.setXtBootRom("/roms/cyrom150.bin");
        cfg.setXtFlashRom("/roms/cyos_v1508.bin");
        cfg.setV1BootRom("/roms/cyrom112.bin");
        cfg.setV1FlashRom("/roms/flash_v1246.bin");
        cfg.setRadioMode("lan");
        cfg.setRadioId("42");
        cfg.setMute(true);
        cfg.setHeadless(false);
        cfg.setTrace(false);
        cfg.setSdrHost("192.168.1.50");
        cfg.setSdrPort("19201");
        cfg.setLogging("radio,status");

        EmulatorConfig.save(cfg, config);
        EmulatorConfig loaded = EmulatorConfig.load(config);

        assertEquals("/opt/cybiko/emulator.jar", loaded.getEmulatorJarPath());
        assertEquals("/roms/cyrom150.bin", loaded.getXtBootRom());
        assertEquals("/roms/cyos_v1508.bin", loaded.getXtFlashRom());
        assertEquals("/roms/cyrom112.bin", loaded.getV1BootRom());
        assertEquals("/roms/flash_v1246.bin", loaded.getV1FlashRom());
        assertEquals("lan", loaded.getRadioMode());
        assertEquals("42", loaded.getRadioId());
        assertTrue(loaded.isMute());
        assertFalse(loaded.isHeadless());
        assertFalse(loaded.isTrace());
        assertEquals("192.168.1.50", loaded.getSdrHost());
        assertEquals("19201", loaded.getSdrPort());
        assertEquals("radio,status", loaded.getLogging());
    }

    @Test
    void loadMissingFileReturnsDefaults() {
        Path config = tempDir.resolve("nonexistent.properties");
        EmulatorConfig loaded = EmulatorConfig.load(config);

        assertEquals("", loaded.getEmulatorJarPath());
        assertEquals("off", loaded.getRadioMode());
        assertFalse(loaded.isMute());
        assertEquals("localhost", loaded.getSdrHost());
        assertEquals("19201", loaded.getSdrPort());
    }

    @Test
    void defaultRadioModeIsOff() {
        EmulatorConfig cfg = new EmulatorConfig();
        assertEquals("off", cfg.getRadioMode());
    }
}
