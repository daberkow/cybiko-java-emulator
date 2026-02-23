package com.github.daberkow.cybiko.manager.cfs;

import com.github.daberkow.cybiko.manager.model.CfsFile;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Central class for reading and manipulating CFS (Cybiko File System) images.
 * Supports all hardware variants via FlashGeometry.
 */
public class CfsImage {

    private final byte[] image;
    private final FlashGeometry geom;
    private int writeCount;
    private boolean modified;

    /** Create a fresh, empty CFS image with the given geometry. */
    public CfsImage(FlashGeometry geom) {
        this.geom = geom;
        this.image = new byte[(int) geom.totalImageSize()];
        this.writeCount = 0;
        this.modified = false;
        format();
    }

    /** Load a CFS image from raw bytes, auto-detecting geometry. */
    public CfsImage(byte[] data) {
        this(data, detectGeometry(data));
    }

    /** Load a CFS image from raw bytes with explicit geometry. */
    public CfsImage(byte[] data, FlashGeometry geom) {
        this.geom = geom;
        this.image = new byte[(int) geom.totalImageSize()];
        System.arraycopy(data, 0, image, 0, Math.min(data.length, image.length));
        this.writeCount = 0;
        this.modified = false;
    }

    public FlashGeometry geometry() { return geom; }
    public boolean isModified() { return modified; }
    public void clearModified() { modified = false; }

    // ---- Query API ----

    /** List all files in the image. */
    public List<CfsFile> listFiles() {
        List<CfsFile> files = new ArrayList<>();
        Set<Integer> seenIds = new HashSet<>();

        for (int b = 0; b < geom.fileBlockCount(); b++) {
            CfsBlock block = readFileBlock(b);
            if (block.used() && block.partId() == 0 && seenIds.add(block.fileId())) {
                CfsFile file = assembleFile(block.fileId(), block);
                if (file != null) files.add(file);
            }
        }
        return files;
    }

    /** Read a file by name. Returns null if not found. */
    public CfsFile readFile(String name) {
        int fileId = findFileId(name);
        if (fileId < 0) return null;
        return readFile(fileId);
    }

    /** Read a file by file ID. Returns null if not found. */
    public CfsFile readFile(int fileId) {
        // Find part 0
        for (int b = 0; b < geom.fileBlockCount(); b++) {
            CfsBlock block = readFileBlock(b);
            if (block.used() && block.fileId() == fileId && block.partId() == 0) {
                return assembleFile(fileId, block);
            }
        }
        return null;
    }

    /** Find the file ID for a given filename. Returns -1 if not found. */
    public int findFileId(String name) {
        for (int b = 0; b < geom.fileBlockCount(); b++) {
            CfsBlock block = readFileBlock(b);
            if (block.used() && block.partId() == 0 && name.equals(block.filename())) {
                return block.fileId();
            }
        }
        return -1;
    }

    // ---- Mutate API ----

    /**
     * Add a file to the image. Replaces any existing file with the same name.
     * @return true on success, false if not enough space or filename too long
     */
    public boolean addFile(String name, byte[] data) {
        return addFile(name, data, CfsFile.nowTimestamp());
    }

    /**
     * Add a file with a specific timestamp.
     * @return true on success
     */
    public boolean addFile(String name, byte[] data, long timestamp) {
        if (name.length() > 58) return false;

        int blocksNeeded = calcBlocksNeeded(data.length);

        // Account for blocks freed by deleting existing file
        int existingId = findFileId(name);
        int existingBlocks = 0;
        if (existingId >= 0) {
            existingBlocks = countFileBlocks(existingId);
        }

        if (freeBlockCount() + existingBlocks < blocksNeeded) return false;

        // Delete existing
        if (existingId >= 0) {
            deleteFile(existingId);
        }

        // Write blocks (always write at least one block for the header)
        int fileId = -1;
        int partId = 0;
        int offset = 0;
        int bytesLeft = data.length;
        // Cap per-block data at 255 bytes to fit in the 1-byte size field.
        // AT45DB161 has block capacities > 255; without capping, the size byte wraps.
        int maxFirstCap = Math.min(geom.firstBlockCapacity(), 255);
        int maxContCap = Math.min(geom.contBlockCapacity(), 255);

        for (int b = 0; b < geom.fileBlockCount(); b++) {
            if (partId > 0 && bytesLeft <= 0) break;

            CfsBlock existing = readFileBlock(b);
            if (existing.used()) continue;

            if (partId == 0) fileId = b;

            byte[] partData;
            if (partId == 0) {
                int toCopy = Math.min(maxFirstCap, bytesLeft);
                partData = new byte[toCopy];
                if (toCopy > 0) System.arraycopy(data, offset, partData, 0, toCopy);
                CfsBlock block = CfsBlock.firstPart(fileId, name, timestamp, partData);
                writeFileBlock(b, block);
            } else {
                int toCopy = Math.min(maxContCap, bytesLeft);
                partData = new byte[toCopy];
                System.arraycopy(data, offset, partData, 0, toCopy);
                CfsBlock block = CfsBlock.continuation(fileId, partId, partData);
                writeFileBlock(b, block);
            }

            offset += partData.length;
            bytesLeft -= partData.length;
            partId++;
        }

        modified = true;
        return true;
    }

    /** Delete a file by name. Returns true if found and deleted. */
    public boolean deleteFile(String name) {
        int fileId = findFileId(name);
        if (fileId < 0) return false;
        deleteFile(fileId);
        return true;
    }

    /** Delete a file by file ID. */
    public void deleteFile(int fileId) {
        for (int b = 0; b < geom.fileBlockCount(); b++) {
            byte[] blockData = readFileBlockRaw(b);
            if ((blockData[0] & 0x80) != 0 && getU16BE(blockData, 2) == fileId) {
                blockData[0] &= ~0x80;
                writeFileBlockRaw(b, blockData);
            }
        }
        modified = true;
    }

    // ---- Capacity API ----

    /** Number of free (unused) file blocks. */
    public int freeBlockCount() {
        int free = 0;
        for (int b = 0; b < geom.fileBlockCount(); b++) {
            if (!isBlockUsed(b)) free++;
        }
        return free;
    }

    /** Number of used file blocks. */
    public int usedBlockCount() {
        return geom.fileBlockCount() - freeBlockCount();
    }

    /** Free space in bytes (approximate, accounts for headers). */
    public long freeSpace() {
        int free = freeBlockCount();
        if (free <= 0) return 0;
        long space = (long) free * geom.contBlockCapacity();
        // If all blocks were continuation blocks, but one would be a first block
        // Only subtract header if there's at least one free block
        if (space > 0) space -= FlashGeometry.FILE_HEADER_SIZE;
        return Math.max(0, space);
    }

    /** Used space in bytes (sum of all file data sizes). */
    public long usedSpace() {
        long used = 0;
        for (CfsFile file : listFiles()) {
            used += file.size();
        }
        return used;
    }

    /** Total capacity for file data. */
    public long totalCapacity() {
        return geom.totalCapacity();
    }

    // ---- Serialization ----

    /** Get the raw image bytes. */
    public byte[] toBytes() {
        return Arrays.copyOf(image, image.length);
    }

    /** Verify all page checksums in the image. */
    public boolean verify() {
        for (int p = 0; p < geom.pageCount(); p++) {
            byte[] page = readPage(p);
            boolean isBoot = p < FlashGeometry.BOOT_BLOCKS;
            if (!CfsPage.verify(page, geom, isBoot)) return false;
        }
        return true;
    }

    /** Auto-detect geometry from raw image data. */
    public static FlashGeometry detectGeometry(byte[] data) {
        FlashGeometry geom = FlashGeometry.fromFileSize(data.length);
        if (geom != null) return geom;
        throw new IllegalArgumentException("Cannot detect CFS geometry from file size: " + data.length);
    }

    // ---- Package-visible accessors for CfsValidator ----

    /** Read a raw page buffer (package-visible for validation). */
    byte[] readPagePublic(int pageIndex) {
        return readPage(pageIndex);
    }

    /** Read raw block data bytes (package-visible for validation). */
    byte[] readFileBlockRawPublic(int blockIndex) {
        return readFileBlockRaw(blockIndex);
    }

    /** Reformat the image in-place (package-visible for repair). Wipes all data. */
    void reformatInPlace() {
        Arrays.fill(image, (byte) 0);
        writeCount = 0;
        modified = true;
        format();
    }

    // ---- Internal helpers ----

    private void format() {
        // Boot blocks: all 0xFF content
        byte[] bootData = new byte[geom.blockDataSize()];
        Arrays.fill(bootData, (byte) 0xFF);
        for (int b = 0; b < FlashGeometry.BOOT_BLOCKS; b++) {
            byte[] page = CfsPage.writePageBuffer(bootData, geom, true, nextWriteCount());
            writePage(b, page);
        }

        // File blocks: 0xFF with bit 7 cleared (unused)
        byte[] emptyData = new byte[geom.blockDataSize()];
        Arrays.fill(emptyData, (byte) 0xFF);
        emptyData[0] = 0x7F; // Clear BLOCK_USED
        for (int b = 0; b < geom.fileBlockCount(); b++) {
            byte[] page = CfsPage.writePageBuffer(emptyData, geom, false, nextWriteCount());
            writePage(FlashGeometry.BOOT_BLOCKS + b, page);
        }

        // Xtreme padding
        if (geom == FlashGeometry.XTREME) {
            int padStart = geom.pageCount() * geom.pageSize();
            Arrays.fill(image, padStart, padStart + FlashGeometry.XTREME_PADDING, (byte) 0xFF);
        }
    }

    private byte[] readPage(int pageIndex) {
        byte[] page = new byte[geom.pageSize()];
        int off = pageIndex * geom.pageSize();
        System.arraycopy(image, off, page, 0, geom.pageSize());
        return page;
    }

    private void writePage(int pageIndex, byte[] page) {
        int off = pageIndex * geom.pageSize();
        System.arraycopy(page, 0, image, off, geom.pageSize());
    }

    private byte[] readFileBlockRaw(int blockIndex) {
        byte[] page = readPage(FlashGeometry.BOOT_BLOCKS + blockIndex);
        return CfsPage.readBlockData(page, geom);
    }

    private CfsBlock readFileBlock(int blockIndex) {
        return CfsBlock.parse(readFileBlockRaw(blockIndex));
    }

    private void writeFileBlockRaw(int blockIndex, byte[] blockData) {
        byte[] page = CfsPage.writePageBuffer(blockData, geom, false, nextWriteCount());
        writePage(FlashGeometry.BOOT_BLOCKS + blockIndex, page);
    }

    private void writeFileBlock(int blockIndex, CfsBlock block) {
        byte[] blockData = block.toBytes(geom.blockDataSize());
        writeFileBlockRaw(blockIndex, blockData);
    }

    private boolean isBlockUsed(int blockIndex) {
        int pageIndex = FlashGeometry.BOOT_BLOCKS + blockIndex;
        int off = pageIndex * geom.pageSize() + geom.pageHeaderSize();
        return (image[off] & 0x80) != 0;
    }

    private CfsFile assembleFile(int fileId, CfsBlock firstBlock) {
        // Collect all parts in order
        Map<Integer, CfsBlock> parts = new TreeMap<>();
        parts.put(0, firstBlock);

        for (int b = 0; b < geom.fileBlockCount(); b++) {
            CfsBlock block = readFileBlock(b);
            if (block.used() && block.fileId() == fileId && block.partId() > 0) {
                parts.put(block.partId(), block);
            }
        }

        // Calculate total size
        int totalSize = 0;
        for (CfsBlock block : parts.values()) {
            totalSize += block.fileData().length;
        }

        // Assemble data
        byte[] data = new byte[totalSize];
        int offset = 0;
        for (CfsBlock block : parts.values()) {
            System.arraycopy(block.fileData(), 0, data, offset, block.fileData().length);
            offset += block.fileData().length;
        }

        return new CfsFile(fileId, firstBlock.filename(), data, firstBlock.timestamp(), parts.size());
    }

    private int countFileBlocks(int fileId) {
        int count = 0;
        for (int b = 0; b < geom.fileBlockCount(); b++) {
            byte[] blockData = readFileBlockRaw(b);
            if ((blockData[0] & 0x80) != 0 && getU16BE(blockData, 2) == fileId) {
                count++;
            }
        }
        return count;
    }

    private int calcBlocksNeeded(int dataLength) {
        int firstCap = Math.min(geom.firstBlockCapacity(), 255);
        int contCap = Math.min(geom.contBlockCapacity(), 255);
        if (dataLength <= firstCap) return 1;
        int remaining = dataLength - firstCap;
        return 1 + (remaining + contCap - 1) / contCap;
    }

    private int nextWriteCount() {
        return writeCount++;
    }

    private static int getU16BE(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }
}
