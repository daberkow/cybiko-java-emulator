package com.github.daberkow.cybiko.manager.io;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CybikoAppParserTest {

    private byte[] loadCalcApp() throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/calc.app")) {
            assertNotNull(is, "calc.app test resource not found");
            return is.readAllBytes();
        }
    }

    @Test
    void parsesCalcAppFileList() throws IOException {
        CybikoAppParser parser = new CybikoAppParser(loadCalcApp());
        List<String> files = parser.listFiles();
        assertEquals(9, files.size());
        assertTrue(files.contains("root.ico"));
        assertTrue(files.contains("main.e"));
    }

    @Test
    void extractsRootIco() throws IOException {
        CybikoAppParser parser = new CybikoAppParser(loadCalcApp());
        byte[] ico = parser.extractFile("root.ico");
        assertNotNull(ico);
        assertEquals(572, ico.length);
        // Cybiko .ico format: byte 0 = type, byte 1 = bpp, byte 2 = width, byte 3 = height
        int width = ico[2] & 0xFF;
        int height = ico[3] & 0xFF;
        assertEquals(48, width);
        assertEquals(47, height);
    }

    @Test
    void extractMissingFileReturnsNull() throws IOException {
        CybikoAppParser parser = new CybikoAppParser(loadCalcApp());
        assertNull(parser.extractFile("nonexistent.file"));
    }

    @Test
    void invalidMagicThrows() {
        byte[] bad = {0x00, 0x00, 0x00, 0x01, 0x00, 0x0A};
        assertThrows(IllegalArgumentException.class, () -> new CybikoAppParser(bad));
    }
}
