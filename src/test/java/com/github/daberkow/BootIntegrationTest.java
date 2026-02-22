package com.github.daberkow;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

/**
 * Integration test that boots CyOS and verifies boot phases via VRAM hashes.
 * Skipped automatically when ROM files are not present.
 */
class BootIntegrationTest {

    private static final Path BOOT_ROM = Path.of("src/main/resources/cybikoxt/cyrom150.bin");
    private static final Path FLASH_ROM = Path.of("src/main/resources/cybikoxt/cyos_v1508.bin");

    @Test void bootReachesCongratulationsScreen() throws Exception {
        assumeTrue(Files.exists(BOOT_ROM), "Boot ROM not found - skipping integration test");
        assumeTrue(Files.exists(FLASH_ROM), "Flash ROM not found - skipping integration test");

        CybikoEmulator emu = new CybikoEmulator();
        emu.loadBootRom(BOOT_ROM);
        emu.loadFlashRom(FLASH_ROM);
        emu.setHeadless(true);
        emu.setRenderer(new ConsoleRenderer() {
            @Override public void render(int[] pixels, int width, int height) {
                // Silent - don't print ASCII art
            }
        });

        H8SCpu cpu = emu.getCpu();
        HD66421Lcd lcd = emu.getLcd();
        H8STimer8 t8_0 = emu.getTimer8(0);
        H8STimer8 t8_1 = emu.getTimer8(1);
        H8STimer16[] t16 = new H8STimer16[6];
        for (int ch = 0; ch < 6; ch++) t16[ch] = emu.getTimer16(ch);

        // Run up to 900 frames (~15 seconds of emulated time)
        // Should reach Congratulations screen (FAEDD600) by ~frame 360
        cpu.reset();
        long vramHash = 0;
        boolean reachedCongrats = false;

        for (int frame = 0; frame < 900; frame++) {
            // Cache running state per frame (matches main emulator loop)
            boolean r8_0 = t8_0.isRunning(), r8_1 = t8_1.isRunning();
            boolean[] r16 = new boolean[6];
            for (int ch = 0; ch < 6; ch++) r16[ch] = t16[ch].isRunning();

            // Execute one frame worth of cycles with timer ticks
            for (int i = 0; i < 307200; i++) {
                if (r8_0) t8_0.tick();
                if (r8_1) t8_1.tick();
                for (int ch = 0; ch < 6; ch++) if (r16[ch]) t16[ch].tick();
                cpu.step();
            }

            CRC32 crc = new CRC32();
            crc.update(lcd.getVram());
            vramHash = crc.getValue();

            if (vramHash == 0xFAEDD600L) {
                reachedCongrats = true;
                System.out.printf("[BOOT TEST] Congratulations screen reached at frame %d%n", frame);
                break;
            }
        }

        assertTrue(reachedCongrats,
            String.format("CyOS should reach Congratulations screen (FAEDD600) but last hash was %08X", vramHash));
    }
}
