package com.github.daberkow.cybiko.manager.cfs;

import com.github.daberkow.cybiko.manager.model.CfsFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CfsImageTest {

    @ParameterizedTest
    @EnumSource(FlashGeometry.class)
    void freshImageHasNoFiles(FlashGeometry geom) {
        CfsImage image = new CfsImage(geom);
        assertTrue(image.listFiles().isEmpty());
        assertEquals(geom.fileBlockCount(), image.freeBlockCount());
        assertEquals(0, image.usedBlockCount());
    }

    @ParameterizedTest
    @EnumSource(FlashGeometry.class)
    void freshImageVerifies(FlashGeometry geom) {
        CfsImage image = new CfsImage(geom);
        assertTrue(image.verify(), "Fresh image should verify");
    }

    @ParameterizedTest
    @EnumSource(FlashGeometry.class)
    void addSmallFile(FlashGeometry geom) {
        CfsImage image = new CfsImage(geom);
        byte[] data = "Hello, Cybiko!".getBytes();

        assertTrue(image.addFile("hello.txt", data));

        List<CfsFile> files = image.listFiles();
        assertEquals(1, files.size());
        assertEquals("hello.txt", files.get(0).name());
        assertArrayEquals(data, files.get(0).data());
        assertEquals(1, files.get(0).blockCount());
    }

    @ParameterizedTest
    @EnumSource(FlashGeometry.class)
    void addLargeMultiBlockFile(FlashGeometry geom) {
        CfsImage image = new CfsImage(geom);
        // Per-block data capped at 255 to fit in the 1-byte size field
        int firstCap = Math.min(geom.firstBlockCapacity(), 255);
        int contCap = Math.min(geom.contBlockCapacity(), 255);
        // Create data that spans 3 blocks
        int size = firstCap + contCap + 10;
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) data[i] = (byte) (i & 0xFF);

        assertTrue(image.addFile("big.dat", data));

        CfsFile file = image.readFile("big.dat");
        assertNotNull(file);
        assertArrayEquals(data, file.data());
        assertEquals(3, file.blockCount()); // first + 1 full cont + 1 partial cont
    }

    @ParameterizedTest
    @EnumSource(FlashGeometry.class)
    void deleteFile(FlashGeometry geom) {
        CfsImage image = new CfsImage(geom);
        image.addFile("delete.me", new byte[]{1, 2, 3});
        assertEquals(1, image.listFiles().size());

        assertTrue(image.deleteFile("delete.me"));
        assertTrue(image.listFiles().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(FlashGeometry.class)
    void deleteFreesBlocks(FlashGeometry geom) {
        CfsImage image = new CfsImage(geom);
        int initialFree = image.freeBlockCount();

        image.addFile("temp.bin", new byte[100]);
        assertTrue(image.freeBlockCount() < initialFree);

        image.deleteFile("temp.bin");
        assertEquals(initialFree, image.freeBlockCount());
    }

    @ParameterizedTest
    @EnumSource(FlashGeometry.class)
    void replaceFile(FlashGeometry geom) {
        CfsImage image = new CfsImage(geom);
        image.addFile("file.txt", "version 1".getBytes());
        image.addFile("file.txt", "version 2 updated".getBytes());

        List<CfsFile> files = image.listFiles();
        assertEquals(1, files.size());
        assertArrayEquals("version 2 updated".getBytes(), files.get(0).data());
    }

    @ParameterizedTest
    @EnumSource(FlashGeometry.class)
    void multipleFiles(FlashGeometry geom) {
        CfsImage image = new CfsImage(geom);
        image.addFile("a.txt", "aaa".getBytes());
        image.addFile("b.txt", "bbb".getBytes());
        image.addFile("c.txt", "ccc".getBytes());

        assertEquals(3, image.listFiles().size());
        assertNotNull(image.readFile("a.txt"));
        assertNotNull(image.readFile("b.txt"));
        assertNotNull(image.readFile("c.txt"));
    }

    @ParameterizedTest
    @EnumSource(FlashGeometry.class)
    void capacityTracking(FlashGeometry geom) {
        CfsImage image = new CfsImage(geom);
        long totalFree = image.freeSpace();
        assertTrue(totalFree > 0);

        image.addFile("data.bin", new byte[100]);
        long afterAdd = image.freeSpace();
        assertTrue(afterAdd < totalFree);
    }

    @ParameterizedTest
    @EnumSource(FlashGeometry.class)
    void roundtripThroughBytes(FlashGeometry geom) {
        CfsImage original = new CfsImage(geom);
        original.addFile("test.app", new byte[]{0x10, 0x20, 0x30});

        byte[] raw = original.toBytes();
        assertEquals(geom.totalImageSize(), raw.length);

        CfsImage loaded = new CfsImage(raw, geom);
        List<CfsFile> files = loaded.listFiles();
        assertEquals(1, files.size());
        assertEquals("test.app", files.get(0).name());
        assertArrayEquals(new byte[]{0x10, 0x20, 0x30}, files.get(0).data());
    }

    @ParameterizedTest
    @EnumSource(FlashGeometry.class)
    void roundtripVerifies(FlashGeometry geom) {
        CfsImage image = new CfsImage(geom);
        image.addFile("verify.txt", "check me".getBytes());

        byte[] raw = image.toBytes();
        CfsImage loaded = new CfsImage(raw, geom);
        assertTrue(loaded.verify(), "Roundtripped image should verify");
    }

    @Test
    void filenameTooLong() {
        CfsImage image = new CfsImage(FlashGeometry.XTREME);
        String longName = "a".repeat(59);
        assertFalse(image.addFile(longName, new byte[]{1}));
    }

    @Test
    void findFileIdNotFound() {
        CfsImage image = new CfsImage(FlashGeometry.XTREME);
        assertEquals(-1, image.findFileId("nonexistent"));
    }

    @Test
    void readFileNotFound() {
        CfsImage image = new CfsImage(FlashGeometry.XTREME);
        assertNull(image.readFile("nonexistent"));
    }

    @Test
    void deleteNonexistentFile() {
        CfsImage image = new CfsImage(FlashGeometry.XTREME);
        assertFalse(image.deleteFile("nonexistent"));
    }

    @Test
    void modifiedFlag() {
        CfsImage image = new CfsImage(FlashGeometry.XTREME);
        assertFalse(image.isModified());

        image.addFile("test", new byte[]{1});
        assertTrue(image.isModified());
    }

    @Test
    void clearModifiedResetsFlag() {
        CfsImage image = new CfsImage(FlashGeometry.XTREME);
        image.addFile("test", new byte[]{1});
        assertTrue(image.isModified());

        image.clearModified();
        assertFalse(image.isModified());
    }

    @Test
    void clearModifiedThenModifyAgain() {
        CfsImage image = new CfsImage(FlashGeometry.XTREME);
        image.addFile("a", new byte[]{1});
        image.clearModified();
        assertFalse(image.isModified());

        image.addFile("b", new byte[]{2});
        assertTrue(image.isModified());
    }

    @Test
    void clearModifiedAfterDelete() {
        CfsImage image = new CfsImage(FlashGeometry.XTREME);
        image.addFile("test", new byte[]{1});
        image.clearModified();

        image.deleteFile("test");
        assertTrue(image.isModified());

        image.clearModified();
        assertFalse(image.isModified());
    }

    @Test
    void detectGeometryFromBytes() {
        for (FlashGeometry geom : FlashGeometry.values()) {
            byte[] data = new byte[(int) geom.totalImageSize()];
            FlashGeometry detected = CfsImage.detectGeometry(data);
            assertEquals(geom, detected);
        }
    }

    @Test
    void detectGeometryFromNvramDump() {
        // Emulator saves full 2MB external RAM as NVRAM; should detect as XTREME
        byte[] data = new byte[2 * 1024 * 1024];
        assertEquals(FlashGeometry.XTREME, CfsImage.detectGeometry(data));
    }

    @Test
    void detectGeometryNvramDumpRoundtrip() {
        // Create a CFS image, embed it in a 2MB NVRAM dump, and verify files survive
        CfsImage original = new CfsImage(FlashGeometry.XTREME);
        original.addFile("hello.app", new byte[]{1, 2, 3, 4, 5});
        byte[] cfsBytes = original.toBytes();

        byte[] nvram = new byte[2 * 1024 * 1024];
        System.arraycopy(cfsBytes, 0, nvram, 0, cfsBytes.length);

        CfsImage loaded = new CfsImage(nvram);
        assertEquals(FlashGeometry.XTREME, loaded.geometry());
        CfsFile file = loaded.readFile("hello.app");
        assertNotNull(file);
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, file.data());
    }

    @Test
    void detectGeometryUnknownThrows() {
        byte[] data = new byte[12345];
        assertThrows(IllegalArgumentException.class, () -> CfsImage.detectGeometry(data));
    }

    @Test
    void addFileWithTimestamp() {
        CfsImage image = new CfsImage(FlashGeometry.XTREME);
        long ts = 3_958_444_800L;
        image.addFile("ts.dat", new byte[]{1, 2}, ts);

        CfsFile file = image.readFile("ts.dat");
        assertNotNull(file);
        assertEquals(ts, file.timestamp());
    }

    @Test
    void exactlyFirstBlockCapacity() {
        FlashGeometry geom = FlashGeometry.XTREME;
        CfsImage image = new CfsImage(geom);
        // XTREME firstBlockCapacity = 178, fits in 1-byte size field
        byte[] data = new byte[geom.firstBlockCapacity()];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;

        assertTrue(image.addFile("exact.bin", data));

        CfsFile file = image.readFile("exact.bin");
        assertNotNull(file);
        assertEquals(1, file.blockCount());
        assertArrayEquals(data, file.data());
    }

    @Test
    void oneByteOverFirstBlockCapacity() {
        FlashGeometry geom = FlashGeometry.XTREME;
        CfsImage image = new CfsImage(geom);
        // XTREME firstBlockCapacity = 178, fits in 1-byte size field
        byte[] data = new byte[geom.firstBlockCapacity() + 1];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;

        assertTrue(image.addFile("over.bin", data));

        CfsFile file = image.readFile("over.bin");
        assertNotNull(file);
        assertEquals(2, file.blockCount());
        assertArrayEquals(data, file.data());
    }

    @Test
    void emptyFileData() {
        CfsImage image = new CfsImage(FlashGeometry.XTREME);
        assertTrue(image.addFile("empty.txt", new byte[0]));

        CfsFile file = image.readFile("empty.txt");
        assertNotNull(file);
        assertEquals(0, file.size());
        assertEquals(1, file.blockCount());
    }
}
