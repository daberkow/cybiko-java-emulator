package com.github.daberkow.cybiko.manager.io;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class CybikoDecompressorTest {

    @Test
    void uncompressedBlobReturnsRawBytesAfterTypeByte() {
        // Type 0x00 = uncompressed, followed by raw data
        byte[] blob = new byte[]{0x00, 0x41, 0x42, 0x43};
        byte[] result = CybikoDecompressor.decompress(blob);
        assertArrayEquals(new byte[]{0x41, 0x42, 0x43}, result);
    }

    @Test
    void unknownTypeBlobThrowsIllegalArgumentException() {
        byte[] blob = new byte[]{0x05, 0x00, 0x00};
        assertThrows(IllegalArgumentException.class, () -> CybikoDecompressor.decompress(blob));
    }

    @Test
    void compressedCalcAppRootIcoDecompressesCorrectly() throws IOException {
        // Load calc.app from test resources
        byte[] appData;
        try (InputStream is = getClass().getResourceAsStream("/calc.app")) {
            assertNotNull(is, "calc.app test fixture not found");
            appData = is.readAllBytes();
        }

        // root.ico is at data_off=419, size=277 bytes
        byte[] compressedBlob = new byte[277];
        System.arraycopy(appData, 419, compressedBlob, 0, 277);

        // Verify it's a compressed blob (type 0x02)
        assertEquals(0x02, compressedBlob[0] & 0xFF);

        byte[] decompressed = CybikoDecompressor.decompress(compressedBlob);

        // Should decompress to 572 bytes
        assertEquals(572, decompressed.length);

        // Header bytes: type=0x02, images=0x01, width=48, height=47
        assertEquals(0x02, decompressed[0] & 0xFF);
        assertEquals(0x01, decompressed[1] & 0xFF);
        assertEquals(48, decompressed[2] & 0xFF);
        assertEquals(47, decompressed[3] & 0xFF);
    }
}
