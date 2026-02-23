package com.github.daberkow.cybiko.manager.cfs;

import java.util.zip.CRC32;

/**
 * CFS checksum algorithms matching MAME's cybiko.cpp and cybikoxt.cpp.
 */
public final class CfsChecksum {

    private CfsChecksum() {}

    /**
     * Xtreme CRC16: XOR-rotate algorithm.
     * From MAME cybikoxt.cpp page_buffer_calc_checksum().
     * {@code val = (val ^ data[i] ^ i) << 1; val |= (val >> 16) & 1}
     */
    public static int xtremeCrc16(byte[] data, int offset, int length) {
        int val = 0;
        for (int i = 0; i < length; i++) {
            val = (val ^ (data[offset + i] & 0xFF) ^ i) << 1;
            val = val | ((val >>> 16) & 0x0001);
        }
        return val & 0xFFFF;
    }

    /**
     * Classic CRC32: standard zlib CRC32 over page data after the 8-byte header.
     * From MAME cybiko.cpp page_buffer_calc_checksum_1().
     * Boot pages: CRC32 over 250 bytes starting at offset 8.
     * File pages: CRC32 over (pageSize - 10) bytes starting at offset 8.
     */
    public static long classicCrc32(byte[] page, int pageSize, boolean isBoot) {
        CRC32 crc = new CRC32();
        int len = isBoot ? 250 : pageSize - 10;
        crc.update(page, 8, len);
        return crc.getValue();
    }

    /**
     * Classic CRC16: XOR seed with first 3 big-endian words, then byte-swap.
     * From MAME cybiko.cpp page_buffer_calc_checksum_2().
     * {@code val = 0xAF17 ^ word[0] ^ word[2] ^ word[4]; return swapBytes(val)}
     */
    public static int classicCrc16(byte[] page) {
        int val = 0xAF17;
        val ^= getU16BE(page, 0);
        val ^= getU16BE(page, 2);
        val ^= getU16BE(page, 4);
        // Byte-swap the 16-bit value
        return ((val & 0xFF) << 8) | ((val >> 8) & 0xFF);
    }

    private static int getU16BE(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }
}
