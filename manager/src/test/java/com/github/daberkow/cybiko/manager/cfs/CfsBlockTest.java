package com.github.daberkow.cybiko.manager.cfs;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class CfsBlockTest {

    @Test
    void parseUnusedBlock() {
        byte[] block = new byte[256];
        Arrays.fill(block, (byte) 0xFF);
        block[0] = 0x7F; // bit 7 clear = unused

        CfsBlock parsed = CfsBlock.parse(block);
        assertFalse(parsed.used());
        assertEquals(0, parsed.fileData().length);
    }

    @Test
    void parseUsedFirstBlock() {
        byte[] block = new byte[256];
        Arrays.fill(block, (byte) 0xFF);
        block[0] = (byte) 0x80; // BLOCK_USED
        block[1] = 10; // 10 data bytes
        block[2] = 0; block[3] = 5; // fileId = 5
        block[4] = 0; block[5] = 0; // partId = 0
        block[6] = 0x20; // first block marker
        // filename at offset 7
        byte[] name = "test.app".getBytes();
        System.arraycopy(name, 0, block, 7, name.length);
        block[7 + name.length] = 0;
        // timestamp at offset 74
        block[74] = 0x12; block[75] = 0x34; block[76] = 0x56; block[77] = 0x78;
        // data at offset 78
        for (int i = 0; i < 10; i++) block[78 + i] = (byte) (i + 1);

        CfsBlock parsed = CfsBlock.parse(block);
        assertTrue(parsed.used());
        assertEquals(10, parsed.dataSize());
        assertEquals(5, parsed.fileId());
        assertEquals(0, parsed.partId());
        assertEquals("test.app", parsed.filename());
        assertEquals(0x12345678L, parsed.timestamp());
        assertEquals(10, parsed.fileData().length);
        assertEquals(1, parsed.fileData()[0]);
    }

    @Test
    void parseUsedContinuationBlock() {
        byte[] block = new byte[256];
        Arrays.fill(block, (byte) 0xFF);
        block[0] = (byte) 0x80;
        block[1] = 20;
        block[2] = 0; block[3] = 5; // fileId = 5
        block[4] = 0; block[5] = 1; // partId = 1
        block[6] = 0x00;
        for (int i = 0; i < 20; i++) block[6 + i] = (byte) (i + 0x40);

        CfsBlock parsed = CfsBlock.parse(block);
        assertTrue(parsed.used());
        assertEquals(1, parsed.partId());
        assertNull(parsed.filename());
        assertEquals(20, parsed.fileData().length);
        assertEquals(0x40, parsed.fileData()[0]);
    }

    @Test
    void firstPartFactory() {
        byte[] data = {1, 2, 3, 4, 5};
        CfsBlock block = CfsBlock.firstPart(42, "hello.txt", 0xABCDEF00L, data);

        assertTrue(block.used());
        assertEquals(5, block.dataSize());
        assertEquals(42, block.fileId());
        assertEquals(0, block.partId());
        assertEquals(0x20, block.typeMarker());
        assertEquals("hello.txt", block.filename());
        assertEquals(0xABCDEF00L, block.timestamp());
        assertArrayEquals(data, block.fileData());
    }

    @Test
    void continuationFactory() {
        byte[] data = {10, 20, 30};
        CfsBlock block = CfsBlock.continuation(42, 3, data);

        assertTrue(block.used());
        assertEquals(3, block.dataSize());
        assertEquals(42, block.fileId());
        assertEquals(3, block.partId());
        assertNull(block.filename());
    }

    @Test
    void unusedFactory() {
        CfsBlock block = CfsBlock.unused(256);
        assertFalse(block.used());
    }

    @Test
    void toBytesFirstBlock() {
        byte[] data = {0x41, 0x42};
        CfsBlock block = CfsBlock.firstPart(7, "abc.app", 0x11223344L, data);

        byte[] raw = block.toBytes(256);
        assertEquals(256, raw.length);
        assertEquals((byte) 0x80, raw[0]); // BLOCK_USED
        assertEquals(2, raw[1]); // dataSize
        assertEquals(0, raw[2]); assertEquals(7, raw[3]); // fileId
        assertEquals(0, raw[4]); assertEquals(0, raw[5]); // partId
        assertEquals(0x20, raw[6]); // type marker
        assertEquals((byte) 'a', raw[7]);
        assertEquals((byte) 'b', raw[8]);
        assertEquals((byte) 'c', raw[9]);
        assertEquals(0, raw[7 + 7]); // null terminator after "abc.app"
        assertEquals((byte) 0x11, raw[74]);
        assertEquals((byte) 0x44, raw[77]);
        assertEquals((byte) 0x41, raw[78]);
        assertEquals((byte) 0x42, raw[79]);
    }

    @Test
    void toBytesUnused() {
        CfsBlock block = CfsBlock.unused(256);
        byte[] raw = block.toBytes(256);
        assertEquals(0x7F, raw[0] & 0xFF); // bit 7 clear
        for (int i = 1; i < raw.length; i++) {
            assertEquals((byte) 0xFF, raw[i], "Expected 0xFF at index " + i);
        }
    }

    @Test
    void parseRoundtrip() {
        byte[] data = new byte[100];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;

        CfsBlock original = CfsBlock.firstPart(15, "roundtrip.dat", 0x55667788L, data);
        byte[] raw = original.toBytes(256);
        CfsBlock parsed = CfsBlock.parse(raw);

        assertEquals(original.fileId(), parsed.fileId());
        assertEquals(original.partId(), parsed.partId());
        assertEquals(original.filename(), parsed.filename());
        assertEquals(original.timestamp(), parsed.timestamp());
        assertArrayEquals(original.fileData(), parsed.fileData());
    }

    @Test
    void filenameExtraction58Chars() {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 58; i++) name.append((char) ('a' + (i % 26)));
        String longName = name.toString();

        CfsBlock block = CfsBlock.firstPart(0, longName, 0, new byte[0]);
        byte[] raw = block.toBytes(256);
        CfsBlock parsed = CfsBlock.parse(raw);
        assertEquals(longName, parsed.filename());
    }

    @Test
    void timestampRoundtrip() {
        long ts = 3_958_444_800L; // ~2025-06-01 in CFS time
        CfsBlock block = CfsBlock.firstPart(0, "test", ts, new byte[]{1});
        byte[] raw = block.toBytes(256);
        CfsBlock parsed = CfsBlock.parse(raw);
        assertEquals(ts, parsed.timestamp());
    }
}
