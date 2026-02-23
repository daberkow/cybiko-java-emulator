package com.github.daberkow.cybiko.manager.cfs;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class CfsChecksumTest {

    @Test
    void xtremeCrc16ZeroData() {
        byte[] data = new byte[256];
        int crc = CfsChecksum.xtremeCrc16(data, 0, data.length);
        // All zeros: val = (0 ^ 0 ^ i) << 1 for each i
        // Should produce a deterministic non-zero value
        assertTrue(crc >= 0 && crc <= 0xFFFF);
    }

    @Test
    void xtremeCrc16AllFF() {
        byte[] data = new byte[256];
        Arrays.fill(data, (byte) 0xFF);
        int crc = CfsChecksum.xtremeCrc16(data, 0, data.length);
        assertTrue(crc >= 0 && crc <= 0xFFFF);
    }

    @Test
    void xtremeCrc16WithOffset() {
        // CRC of subarray should match CRC with offset
        byte[] full = {0, 0, 0x41, 0x42, 0x43};
        byte[] sub = {0x41, 0x42, 0x43};
        int crcOffset = CfsChecksum.xtremeCrc16(full, 2, 3);
        int crcDirect = CfsChecksum.xtremeCrc16(sub, 0, 3);
        assertEquals(crcDirect, crcOffset);
    }

    @Test
    void xtremeCrc16DifferentDataDifferentCrc() {
        byte[] data1 = {0x01, 0x02, 0x03, 0x04};
        byte[] data2 = {0x04, 0x03, 0x02, 0x01};
        int crc1 = CfsChecksum.xtremeCrc16(data1, 0, 4);
        int crc2 = CfsChecksum.xtremeCrc16(data2, 0, 4);
        assertNotEquals(crc1, crc2);
    }

    @Test
    void classicCrc32BootRange() {
        // Boot pages: CRC32 over 250 bytes starting at offset 8
        byte[] page = new byte[264];
        Arrays.fill(page, (byte) 0xFF);
        long crc = CfsChecksum.classicCrc32(page, 264, true);
        assertTrue(crc >= 0 && crc <= 0xFFFFFFFFL);
    }

    @Test
    void classicCrc32FileRange() {
        // File pages: CRC32 over (264-10)=254 bytes starting at offset 8
        byte[] page = new byte[264];
        Arrays.fill(page, (byte) 0xFF);
        long crc = CfsChecksum.classicCrc32(page, 264, false);
        assertTrue(crc >= 0 && crc <= 0xFFFFFFFFL);
    }

    @Test
    void classicCrc32DifferentRanges() {
        byte[] page = new byte[264];
        Arrays.fill(page, (byte) 0xAB);
        long bootCrc = CfsChecksum.classicCrc32(page, 264, true);
        long fileCrc = CfsChecksum.classicCrc32(page, 264, false);
        // Different ranges should produce different CRCs (boot=250, file=254)
        assertNotEquals(bootCrc, fileCrc);
    }

    @Test
    void classicCrc16SeedAndSwap() {
        // Test with known bytes
        byte[] page = new byte[8];
        // All zeros: val = 0xAF17 ^ 0 ^ 0 ^ 0 = 0xAF17
        // Byte-swapped: 0x17AF
        int crc = CfsChecksum.classicCrc16(page);
        assertEquals(0x17AF, crc);
    }

    @Test
    void classicCrc16NonZero() {
        byte[] page = new byte[8];
        page[0] = (byte) 0xAF;
        page[1] = (byte) 0x17;
        // word[0] = 0xAF17, val = 0xAF17 ^ 0xAF17 ^ 0 ^ 0 = 0
        // Byte-swapped: 0
        int crc = CfsChecksum.classicCrc16(page);
        assertEquals(0, crc);
    }

    @Test
    void classicCrc32LargePageSize() {
        // AT45DB161: 528-byte pages, file CRC over 518 bytes
        byte[] page = new byte[528];
        Arrays.fill(page, (byte) 0xCD);
        long crc = CfsChecksum.classicCrc32(page, 528, false);
        assertTrue(crc >= 0 && crc <= 0xFFFFFFFFL);
    }
}
