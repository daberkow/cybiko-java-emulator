package com.github.daberkow.cybiko.manager.cfs;

/**
 * Flash geometry for different Cybiko hardware variants.
 * Sizes derived from MAME imgtool modules (cybiko.cpp, cybikoxt.cpp).
 */
public enum FlashGeometry {

    /** AT45DB041 - Classic V1 (2048 pages x 264 bytes = 540,672 bytes) */
    AT45DB041(2048, 264, CfsFormat.CLASSIC),

    /** AT45DB081 - Classic V2 (4096 pages x 264 bytes = 1,081,344 bytes) */
    AT45DB081(4096, 264, CfsFormat.CLASSIC),

    /** AT45DB161 - Classic large (4096 pages x 528 bytes = 2,162,688 bytes) */
    AT45DB161(4096, 528, CfsFormat.CLASSIC),

    /** Xtreme (2005 pages x 258 bytes + 7254 padding = 524,544 bytes) */
    XTREME(2005, 258, CfsFormat.XTREME);

    public enum CfsFormat { CLASSIC, XTREME }

    public static final int BOOT_BLOCKS = 5;
    public static final int FILE_HEADER_SIZE = 0x48; // 72 bytes
    public static final int XTREME_PADDING = 0x1B56; // 7254 bytes

    private final int pageCount;
    private final int pageSize;
    private final CfsFormat format;

    FlashGeometry(int pageCount, int pageSize, CfsFormat format) {
        this.pageCount = pageCount;
        this.pageSize = pageSize;
        this.format = format;
    }

    public int pageCount() { return pageCount; }
    public int pageSize() { return pageSize; }
    public CfsFormat format() { return format; }

    /** Page header: 8 bytes for Classic (CRC32+writeCount+CRC16), 2 bytes for Xtreme (CRC16). */
    public int pageHeaderSize() {
        return format == CfsFormat.CLASSIC ? 8 : 2;
    }

    /** Page trailer: 2 bytes for Classic (0xFFFF), 0 for Xtreme. */
    public int pageTrailerSize() {
        return format == CfsFormat.CLASSIC ? 2 : 0;
    }

    /** Block data size = pageSize - header - trailer. */
    public int blockDataSize() {
        return pageSize - pageHeaderSize() - pageTrailerSize();
    }

    /** Number of file blocks (total pages minus boot blocks). */
    public int fileBlockCount() {
        return pageCount - BOOT_BLOCKS;
    }

    /** Max file data bytes in the first block of a file. */
    public int firstBlockCapacity() {
        return contBlockCapacity() - FILE_HEADER_SIZE;
    }

    /** Max file data bytes in a continuation block. */
    public int contBlockCapacity() {
        return blockDataSize() - 6;
    }

    /** Total image size in bytes (pages * pageSize + padding for Xtreme). */
    public long totalImageSize() {
        long size = (long) pageCount * pageSize;
        if (this == XTREME) size += XTREME_PADDING;
        return size;
    }

    /** Total usable capacity for file data across all file blocks. */
    public long totalCapacity() {
        int blocks = fileBlockCount();
        if (blocks <= 0) return 0;
        return firstBlockCapacity() + (long) (blocks - 1) * contBlockCapacity();
    }

    /**
     * Auto-detect geometry from file size.
     * @return matching geometry, or null if unrecognized
     */
    public static FlashGeometry fromFileSize(long size) {
        for (FlashGeometry g : values()) {
            if (g.totalImageSize() == size) return g;
        }
        return null;
    }
}
