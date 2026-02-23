package com.github.daberkow.cybiko.manager.cfs;

import java.util.Arrays;

/**
 * Format-aware page encoding/decoding for CFS images.
 * Handles the different page layouts between Classic (264/528-byte) and Xtreme (258-byte).
 */
public final class CfsPage {

    private CfsPage() {}

    /**
     * Extract block data from a raw page buffer.
     *
     * @param page raw page bytes (pageSize length)
     * @param geom flash geometry
     * @return block data bytes (blockDataSize length)
     */
    public static byte[] readBlockData(byte[] page, FlashGeometry geom) {
        int headerSize = geom.pageHeaderSize();
        int dataSize = geom.blockDataSize();
        byte[] block = new byte[dataSize];
        System.arraycopy(page, headerSize, block, 0, dataSize);
        return block;
    }

    /**
     * Build a complete page buffer from block data, with proper checksums.
     *
     * @param blockData block data bytes (blockDataSize length)
     * @param geom flash geometry
     * @param isBoot true for boot blocks, false for file blocks
     * @param writeCount write counter value (Classic only, ignored for Xtreme)
     * @return complete page buffer (pageSize length)
     */
    public static byte[] writePageBuffer(byte[] blockData, FlashGeometry geom, boolean isBoot, int writeCount) {
        byte[] page = new byte[geom.pageSize()];

        if (geom.format() == FlashGeometry.CfsFormat.XTREME) {
            // Xtreme: [CRC16:2][blockData:256]
            System.arraycopy(blockData, 0, page, 2, geom.blockDataSize());

            int crc;
            if (isBoot) {
                crc = 0xFFFF;
            } else {
                crc = CfsChecksum.xtremeCrc16(page, 2, geom.blockDataSize());
            }
            page[0] = (byte) ((crc >> 8) & 0xFF);
            page[1] = (byte) (crc & 0xFF);
        } else {
            // Classic: [CRC32:4][writeCount:2][CRC16:2][blockData:N][trailer:2]
            System.arraycopy(blockData, 0, page, 8, geom.blockDataSize());

            // Trailer = 0xFFFF
            int trailerOff = geom.pageSize() - 2;
            page[trailerOff] = (byte) 0xFF;
            page[trailerOff + 1] = (byte) 0xFF;

            // CRC32 at offset 0-3
            long crc32 = CfsChecksum.classicCrc32(page, geom.pageSize(), isBoot);
            putU32BE(page, 0, crc32);

            // Write count at offset 4-5
            page[4] = (byte) ((writeCount >> 8) & 0xFF);
            page[5] = (byte) (writeCount & 0xFF);

            // CRC16 at offset 6-7 (computed from bytes 0-5)
            int crc16 = CfsChecksum.classicCrc16(page);
            page[6] = (byte) ((crc16 >> 8) & 0xFF);
            page[7] = (byte) (crc16 & 0xFF);
        }

        return page;
    }

    /**
     * Verify page checksums.
     *
     * @param page raw page bytes
     * @param geom flash geometry
     * @param isBoot true for boot blocks
     * @return true if checksums are valid
     */
    public static boolean verify(byte[] page, FlashGeometry geom, boolean isBoot) {
        if (geom.format() == FlashGeometry.CfsFormat.XTREME) {
            // Xtreme boot blocks skip verification (MAME: only verify FILE blocks)
            if (isBoot) return true;

            int storedCrc = ((page[0] & 0xFF) << 8) | (page[1] & 0xFF);
            int calcCrc = CfsChecksum.xtremeCrc16(page, 2, geom.blockDataSize());
            return storedCrc == calcCrc;
        } else {
            // Classic: verify CRC32 then CRC16
            long storedCrc32 = getU32BE(page, 0);
            long calcCrc32 = CfsChecksum.classicCrc32(page, geom.pageSize(), isBoot);
            if (storedCrc32 != calcCrc32) return false;

            int storedCrc16 = ((page[6] & 0xFF) << 8) | (page[7] & 0xFF);
            int calcCrc16 = CfsChecksum.classicCrc16(page);
            return storedCrc16 == calcCrc16;
        }
    }

    private static void putU32BE(byte[] data, int offset, long value) {
        data[offset] = (byte) ((value >> 24) & 0xFF);
        data[offset + 1] = (byte) ((value >> 16) & 0xFF);
        data[offset + 2] = (byte) ((value >> 8) & 0xFF);
        data[offset + 3] = (byte) (value & 0xFF);
    }

    private static long getU32BE(byte[] data, int offset) {
        return ((long)(data[offset] & 0xFF) << 24) |
               ((long)(data[offset + 1] & 0xFF) << 16) |
               ((long)(data[offset + 2] & 0xFF) << 8) |
               (data[offset + 3] & 0xFF);
    }
}
