package com.github.daberkow.cybiko.manager.cfs;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A parsed CFS block. Block layout is the same across all geometries
 * (only blockDataSize varies).
 *
 * <pre>
 * Byte 0:     flags (bit 7 = BLOCK_USED)
 * Byte 1:     data byte count
 * Bytes 2-3:  fileId (big-endian)
 * Bytes 4-5:  partId (big-endian)
 * Byte 6:     type marker (0x20 for part 0, 0x00 for continuation)
 *
 * Part 0 (first block):
 *   Bytes 7-72:   filename (null-terminated)
 *   Bytes 74-77:  timestamp (big-endian u32, seconds since 1900/01/01)
 *   Bytes 78+:    file data
 *
 * Continuation (part > 0):
 *   Bytes 6+:     file data
 * </pre>
 */
public record CfsBlock(
    boolean used,
    int dataSize,
    int fileId,
    int partId,
    int typeMarker,
    String filename,
    long timestamp,
    byte[] fileData
) {

    public static final int FILE_HEADER_SIZE = FlashGeometry.FILE_HEADER_SIZE; // 72

    /** Parse a CfsBlock from raw block data bytes. */
    public static CfsBlock parse(byte[] block) {
        boolean used = (block[0] & 0x80) != 0;
        int dataSize = block[1] & 0xFF;
        int fileId = getU16BE(block, 2);
        int partId = getU16BE(block, 4);
        int typeMarker = block[6] & 0xFF;

        String filename = null;
        long timestamp = 0;
        byte[] fileData;

        if (used && partId == 0) {
            // First block: extract filename and timestamp
            filename = readNullTerminated(block, 7, 66);
            timestamp = getU32BE(block, 74);
            int dataStart = 6 + FILE_HEADER_SIZE; // 78
            int len = Math.min(dataSize, block.length - dataStart);
            fileData = new byte[Math.max(0, len)];
            if (len > 0) System.arraycopy(block, dataStart, fileData, 0, len);
        } else if (used) {
            // Continuation block
            int dataStart = 6;
            int len = Math.min(dataSize, block.length - dataStart);
            fileData = new byte[Math.max(0, len)];
            if (len > 0) System.arraycopy(block, dataStart, fileData, 0, len);
        } else {
            fileData = new byte[0];
        }

        return new CfsBlock(used, dataSize, fileId, partId, typeMarker, filename, timestamp, fileData);
    }

    /** Create an unused block of the given size. */
    public static CfsBlock unused(int blockDataSize) {
        return new CfsBlock(false, 0, 0xFFFF, 0xFFFF, 0xFF, null, 0, new byte[0]);
    }

    /** Create the first block (part 0) of a file. */
    public static CfsBlock firstPart(int fileId, String filename, long timestamp, byte[] data) {
        return new CfsBlock(true, data.length, fileId, 0, 0x20, filename, timestamp, data);
    }

    /** Create a continuation block (part > 0). */
    public static CfsBlock continuation(int fileId, int partId, byte[] data) {
        return new CfsBlock(true, data.length, fileId, partId, 0x00, null, 0, data);
    }

    /**
     * Serialize this block to raw bytes of the specified blockDataSize.
     */
    public byte[] toBytes(int blockDataSize) {
        byte[] block = new byte[blockDataSize];
        Arrays.fill(block, (byte) 0xFF);

        if (!used) {
            block[0] = 0x7F; // 0xFF with bit 7 cleared
            return block;
        }

        block[0] = (byte) 0x80; // BLOCK_USED
        block[1] = (byte) (dataSize & 0xFF);
        putU16BE(block, 2, fileId);
        putU16BE(block, 4, partId);

        if (partId == 0) {
            block[6] = 0x20;
            if (filename != null) {
                byte[] nameBytes = filename.getBytes(StandardCharsets.US_ASCII);
                int nameLen = Math.min(nameBytes.length, 58);
                System.arraycopy(nameBytes, 0, block, 7, nameLen);
                block[7 + nameLen] = 0; // null terminate
            }
            putU32BE(block, 74, timestamp);
            int dataStart = 6 + FILE_HEADER_SIZE;
            int len = Math.min(fileData.length, blockDataSize - dataStart);
            if (len > 0) System.arraycopy(fileData, 0, block, dataStart, len);
        } else {
            block[6] = 0x00;
            int len = Math.min(fileData.length, blockDataSize - 6);
            if (len > 0) System.arraycopy(fileData, 0, block, 6, len);
        }

        return block;
    }

    private static String readNullTerminated(byte[] data, int offset, int maxLen) {
        int end = offset;
        int limit = Math.min(offset + maxLen, data.length);
        while (end < limit && data[end] != 0) end++;
        return new String(data, offset, end - offset, StandardCharsets.US_ASCII);
    }

    private static int getU16BE(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static long getU32BE(byte[] data, int offset) {
        return ((long)(data[offset] & 0xFF) << 24) |
               ((long)(data[offset + 1] & 0xFF) << 16) |
               ((long)(data[offset + 2] & 0xFF) << 8) |
               (data[offset + 3] & 0xFF);
    }

    private static void putU16BE(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >> 8) & 0xFF);
        data[offset + 1] = (byte) (value & 0xFF);
    }

    private static void putU32BE(byte[] data, int offset, long value) {
        data[offset] = (byte) ((value >> 24) & 0xFF);
        data[offset + 1] = (byte) ((value >> 16) & 0xFF);
        data[offset + 2] = (byte) ((value >> 8) & 0xFF);
        data[offset + 3] = (byte) (value & 0xFF);
    }
}
