package com.github.daberkow;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

class CfsImageTest {

    @Test void freshImageIsValidCfs() {
        CfsImage cfs = new CfsImage();
        assertTrue(CfsImage.isCfsImage(cfs.getImageData()));
    }

    @Test void freshImageHasNoFiles() {
        CfsImage cfs = new CfsImage();
        assertTrue(cfs.listFiles().isEmpty());
    }

    @Test void imageSize() {
        CfsImage cfs = new CfsImage();
        assertEquals(CfsImage.TOTAL_IMAGE_SIZE, cfs.getImageData().length);
    }

    @Test void addAndListFile() {
        CfsImage cfs = new CfsImage();
        byte[] data = new byte[]{1, 2, 3, 4, 5};
        assertTrue(cfs.addFile("test.app", data));

        List<String> files = cfs.listFiles();
        assertEquals(1, files.size());
        assertTrue(files.get(0).contains("test.app"));
        assertTrue(files.get(0).contains("5 bytes"));
    }

    @Test void addLargeFile() {
        CfsImage cfs = new CfsImage();
        // File larger than one block (178 bytes first block capacity)
        byte[] data = new byte[500];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i & 0xFF);
        assertTrue(cfs.addFile("big.app", data));

        List<String> files = cfs.listFiles();
        assertEquals(1, files.size());
        assertTrue(files.get(0).contains("500 bytes"));
    }

    @Test void findFile() {
        CfsImage cfs = new CfsImage();
        cfs.addFile("hello.app", new byte[]{1, 2, 3});
        assertTrue(cfs.findFile("hello.app") >= 0);
        assertEquals(-1, cfs.findFile("nonexistent.app"));
    }

    @Test void deleteFile() {
        CfsImage cfs = new CfsImage();
        cfs.addFile("delete_me.app", new byte[]{1, 2, 3});
        assertEquals(1, cfs.listFiles().size());

        int fileId = cfs.findFile("delete_me.app");
        cfs.deleteFile(fileId);
        assertTrue(cfs.listFiles().isEmpty());
    }

    @Test void addFileReplacesDuplicate() {
        CfsImage cfs = new CfsImage();
        cfs.addFile("test.app", new byte[]{1, 2, 3});
        cfs.addFile("test.app", new byte[]{4, 5, 6, 7, 8});

        List<String> files = cfs.listFiles();
        assertEquals(1, files.size(), "Duplicate should replace existing");
        assertTrue(files.get(0).contains("5 bytes"));
    }

    @Test void multipleFiles() {
        CfsImage cfs = new CfsImage();
        cfs.addFile("app1.app", new byte[10]);
        cfs.addFile("app2.app", new byte[20]);
        cfs.addFile("app3.app", new byte[30]);

        assertEquals(3, cfs.listFiles().size());
    }

    @Test void roundTripThroughConstructor() {
        CfsImage original = new CfsImage();
        original.addFile("test.app", new byte[]{0x42, 0x43});

        // Load the image data into a new CfsImage
        CfsImage loaded = new CfsImage(original.getImageData());
        List<String> files = loaded.listFiles();
        assertEquals(1, files.size());
        assertTrue(files.get(0).contains("test.app"));
    }

    @Test void checksumAlgorithm() {
        // Empty block (all 0xFF with bit 7 cleared) should produce a valid CRC
        byte[] data = new byte[256];
        java.util.Arrays.fill(data, (byte) 0xFF);
        data[0] = 0x7F;
        int crc = CfsImage.calcChecksum(data, 0, data.length);
        assertTrue(crc >= 0 && crc <= 0xFFFF, "CRC should be 16-bit");
    }

    @Test void invalidDataNotCfs() {
        assertFalse(CfsImage.isCfsImage(new byte[10]));
        assertFalse(CfsImage.isCfsImage(new byte[]{0, 0, 0, 0}));
    }

    @Test void filenameTooLong() {
        CfsImage cfs = new CfsImage();
        String longName = "a".repeat(60) + ".app"; // 64 chars > 58 limit
        assertFalse(cfs.addFile(longName, new byte[]{1}));
    }
}
