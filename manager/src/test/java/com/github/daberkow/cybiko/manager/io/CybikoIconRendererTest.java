package com.github.daberkow.cybiko.manager.io;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class CybikoIconRendererTest {

    private byte[] loadCalcApp() throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/calc.app")) {
            assertNotNull(is, "calc.app test resource not found");
            return is.readAllBytes();
        }
    }

    @Test
    void parsesIconHeader() {
        // Synthetic 572-byte .ico: width=48, height=47
        // Row bytes = ceil(48/4) = 12, pixel data = 12 * 47 = 564, total = 8 + 564 = 572
        byte[] ico = new byte[572];
        ico[0] = 0x02; // type = picture
        ico[1] = 0x01; // 1 image
        ico[2] = 48;   // width
        ico[3] = 47;   // height
        ico[4] = 0;    // left
        ico[5] = 0;    // top
        ico[6] = 48;   // display width
        ico[7] = 47;   // display height

        CybikoIconRenderer.IconInfo info = CybikoIconRenderer.parseHeader(ico);
        assertNotNull(info);
        assertEquals(48, info.width());
        assertEquals(47, info.height());
    }

    @Test
    void convertsPixelsToArgb() {
        // 4 pixels wide, 1 row: values 0, 1, 2, 3 packed into 1 byte
        // 2bpp MSB-first: 00 01 10 11 = 0x1B
        byte[] ico = new byte[9]; // 8-byte header + 1 byte pixel data
        ico[2] = 4;  // width
        ico[3] = 1;  // height
        ico[8] = 0x1B; // pixels: 0, 1, 2, 3

        int[] pixels = CybikoIconRenderer.toArgbPixels(ico);
        assertNotNull(pixels);
        assertEquals(4, pixels.length);
        assertEquals(0xFFFFFFFF, pixels[0]); // white
        assertEquals(0xFFAAAAAA, pixels[1]); // light gray
        assertEquals(0xFF555555, pixels[2]); // dark gray
        assertEquals(0xFF000000, pixels[3]); // black
    }

    @Test
    void realCalcIcon() throws IOException {
        CybikoAppParser parser = new CybikoAppParser(loadCalcApp());
        byte[] ico = parser.extractFile("root.ico");
        assertNotNull(ico, "root.ico should exist in calc.app");

        CybikoIconRenderer.IconInfo info = CybikoIconRenderer.parseHeader(ico);
        assertNotNull(info);
        assertEquals(48, info.width());
        assertEquals(47, info.height());

        int[] pixels = CybikoIconRenderer.toArgbPixels(ico);
        assertNotNull(pixels);
        assertEquals(48 * 47, pixels.length); // 2256 pixels

        // Every pixel should be one of the 4 palette values
        for (int pixel : pixels) {
            assertTrue(
                    pixel == 0xFFFFFFFF || pixel == 0xFFAAAAAA
                            || pixel == 0xFF555555 || pixel == 0xFF000000,
                    "Unexpected ARGB value: 0x" + Integer.toHexString(pixel));
        }
    }

    @Test
    void nullDataReturnsNull() {
        assertNull(CybikoIconRenderer.parseHeader(null));
        assertNull(CybikoIconRenderer.toArgbPixels(null));
    }

    @Test
    void tooShortDataReturnsNull() {
        assertNull(CybikoIconRenderer.parseHeader(new byte[7]));
        assertNull(CybikoIconRenderer.parseHeader(new byte[0]));
    }
}
