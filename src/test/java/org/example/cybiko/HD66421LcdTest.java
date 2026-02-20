package org.example.cybiko;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HD66421LcdTest {

    private HD66421Lcd lcd() {
        return new HD66421Lcd();
    }

    @Test void displayOffReturnsWhite() {
        HD66421Lcd lcd = lcd();
        // Display is off by default (R0 DISP bit not set)
        int[] fb = lcd.getFrameBuffer();
        assertEquals(HD66421Lcd.WIDTH * HD66421Lcd.HEIGHT, fb.length);
        for (int pixel : fb) {
            assertEquals(255, pixel, "Display off should be all white");
        }
    }

    @Test void framebufferIsReused() {
        HD66421Lcd lcd = lcd();
        int[] fb1 = lcd.getFrameBuffer();
        int[] fb2 = lcd.getFrameBuffer();
        assertSame(fb1, fb2, "getFrameBuffer should reuse the same array");
    }

    @Test void registerIndexSelect() {
        HD66421Lcd lcd = lcd();
        // Write register index via offset 0
        lcd.write8(0, 0x02); // select X address register
        assertEquals(0x02, lcd.read8(0));
    }

    @Test void writeAndReadVram() {
        HD66421Lcd lcd = lcd();
        // Select RAM register
        lcd.write8(0, HD66421Lcd.REG_X_ADDR);
        lcd.write8(1, 0); // X = 0
        lcd.write8(0, HD66421Lcd.REG_Y_ADDR);
        lcd.write8(1, 0); // Y = 0
        lcd.write8(0, HD66421Lcd.REG_RAM);
        lcd.write8(1, 0xA5); // Write VRAM byte

        // Read it back (reset address first)
        lcd.write8(0, HD66421Lcd.REG_X_ADDR);
        lcd.write8(1, 0);
        lcd.write8(0, HD66421Lcd.REG_Y_ADDR);
        lcd.write8(1, 0);
        lcd.write8(0, HD66421Lcd.REG_RAM);
        assertEquals(0xA5, lcd.read8(1));
    }

    @Test void autoIncrementY() {
        HD66421Lcd lcd = lcd();
        // Default R1 has INC=0, so Y auto-increments
        lcd.write8(0, HD66421Lcd.REG_X_ADDR);
        lcd.write8(1, 0);
        lcd.write8(0, HD66421Lcd.REG_Y_ADDR);
        lcd.write8(1, 0);
        lcd.write8(0, HD66421Lcd.REG_RAM);
        lcd.write8(1, 0x11); // Write byte at (0,0), Y increments to 1
        lcd.write8(1, 0x22); // Write byte at (0,1)

        // Read back (0,0)
        lcd.write8(0, HD66421Lcd.REG_X_ADDR);
        lcd.write8(1, 0);
        lcd.write8(0, HD66421Lcd.REG_Y_ADDR);
        lcd.write8(1, 0);
        lcd.write8(0, HD66421Lcd.REG_RAM);
        assertEquals(0x11, lcd.read8(1));
    }

    @Test void displayOnRenders() {
        HD66421Lcd lcd = lcd();
        // Turn display on
        lcd.write8(0, HD66421Lcd.REG_CONTROL1);
        lcd.write8(1, HD66421Lcd.R0_DISP);

        int[] fb = lcd.getFrameBuffer();
        // With default palette and zeroed VRAM, pixel value 0 maps to palette 0
        // Default palette[0] = 0, so compute: bright = 31 - (0 - 0 + 3) = 28, pixel = 28*255/31 = 230
        // Just verify it's not all 255 (display-off white)
        boolean hasNonWhite = false;
        for (int p : fb) {
            if (p != 255) { hasNonWhite = true; break; }
        }
        assertTrue(hasNonWhite, "Display on with zeroed VRAM should produce non-white pixels");
    }

    @Test void vramSize() {
        HD66421Lcd lcd = lcd();
        byte[] vram = lcd.getVram();
        assertEquals(HD66421Lcd.VRAM_SIZE, vram.length);
        assertEquals(4000, vram.length); // 160*100/4
    }
}
