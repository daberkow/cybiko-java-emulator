package com.github.daberkow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Cybiko Xtreme CFS (Cybiko File System) image builder/reader.
 *
 * Matches the format in MAME's imgtool/modules/cybikoxt.cpp exactly.
 *
 * Page layout (258 bytes):
 *   [0-1]     CRC16 checksum (big-endian)
 *   [2-257]   Block data (256 bytes)
 *
 * Image layout:
 *   Pages 0-4:      Boot blocks (all 0xFF, CRC = 0xFFFF)
 *   Pages 5-2004:   File blocks
 *   + 7254 bytes padding (0xFF) to round out to ~512KB
 *
 * File block data format (256 bytes):
 *   [0]       Flags (bit 7 = BLOCK_USED)
 *   [1]       Data size in this block
 *   [2-3]     File ID (big-endian u16)
 *   [4-5]     Part ID (big-endian u16, 0 = first block)
 *   For part 0 (first block of file):
 *     [6]       0x20 (file flag)
 *     [7-73]    Filename (null-terminated, max 58 chars)
 *     [74-77]   Timestamp (big-endian u32, seconds since 1900/01/01)
 *     [78-255]  File data (178 bytes max)
 *   For part N > 0 (continuation blocks):
 *     [6-255]   File data (250 bytes max)
 */
public class CfsImage {
    public static final int PAGE_SIZE = 258;
    public static final int PAGE_COUNT = 2005;
    public static final int BOOT_BLOCKS = 5;
    public static final int FILE_BLOCKS = PAGE_COUNT - BOOT_BLOCKS; // 2000
    public static final int BLOCK_DATA_SIZE = PAGE_SIZE - 2; // 256
    public static final int FILE_HEADER_SIZE = 0x48; // 72
    public static final int PADDING_SIZE = 0x1B56; // 7254 bytes

    // Capacity per block
    public static final int FIRST_BLOCK_CAPACITY = BLOCK_DATA_SIZE - 6 - FILE_HEADER_SIZE; // 178
    public static final int CONT_BLOCK_CAPACITY = BLOCK_DATA_SIZE - 6; // 250

    public static final int TOTAL_IMAGE_SIZE = PAGE_SIZE * PAGE_COUNT + PADDING_SIZE; // 524544

    private final byte[] image;

    /** Create a fresh, empty CFS image. */
    public CfsImage() {
        image = new byte[TOTAL_IMAGE_SIZE];
        format();
    }

    /** Load a CFS image from existing data. */
    public CfsImage(byte[] data) {
        image = new byte[TOTAL_IMAGE_SIZE];
        System.arraycopy(data, 0, image, 0, Math.min(data.length, image.length));
    }

    /** Format the image: boot blocks + empty file blocks + padding (matches MAME cfs_format). */
    private void format() {
        // Boot blocks: all 0xFF content, CRC = 0xFFFF
        for (int b = 0; b < BOOT_BLOCKS; b++) {
            int pageOff = b * PAGE_SIZE;
            Arrays.fill(image, pageOff, pageOff + PAGE_SIZE, (byte) 0xFF);
            // CRC is already 0xFFFF from the fill
        }

        // File blocks: 0xFF content with byte[0] bit 7 cleared (marks as unused)
        // Matches MAME: memset(buffer, 0xFF, ...); buffer[0] &= ~0x80;
        byte[] emptyBlock = new byte[BLOCK_DATA_SIZE];
        Arrays.fill(emptyBlock, (byte) 0xFF);
        emptyBlock[0] = 0x7F; // 0xFF with bit 7 cleared = unused

        for (int b = 0; b < FILE_BLOCKS; b++) {
            writeBlock(BOOT_BLOCKS + b, emptyBlock);
        }

        // Padding: 0xFF bytes after all pages
        int padStart = PAGE_COUNT * PAGE_SIZE;
        Arrays.fill(image, padStart, padStart + PADDING_SIZE, (byte) 0xFF);
    }

    /** Add a file to the CFS image. Returns true on success. */
    public boolean addFile(String filename, byte[] fileData) {
        if (filename.length() > 58) {
            System.err.println("[CFS] Filename too long: " + filename);
            return false;
        }

        // Calculate blocks needed
        int blocksNeeded;
        if (fileData.length <= FIRST_BLOCK_CAPACITY) {
            blocksNeeded = 1;
        } else {
            blocksNeeded = 1 + (int) Math.ceil(
                (double) (fileData.length - FIRST_BLOCK_CAPACITY) / CONT_BLOCK_CAPACITY);
        }

        // Check free space
        int freeBlocks = countFreeBlocks();
        if (freeBlocks < blocksNeeded) {
            System.err.printf("[CFS] Not enough space: need %d blocks, have %d free%n",
                blocksNeeded, freeBlocks);
            return false;
        }

        // Delete existing file with same name
        int existingId = findFile(filename);
        if (existingId >= 0) {
            deleteFile(existingId);
        }

        // Write file blocks
        int fileId = -1;
        int partId = 0;
        int fileOffset = 0;
        int bytesLeft = fileData.length;

        for (int b = 0; b < FILE_BLOCKS && bytesLeft > 0; b++) {
            byte[] block = readBlock(BOOT_BLOCKS + b);
            if ((block[0] & 0x80) != 0) continue; // Block in use, skip

            if (partId == 0) fileId = b; // File ID = first block index

            // Initialize block with 0xFF (matches MAME)
            Arrays.fill(block, (byte) 0xFF);

            // Metadata
            block[0] = (byte) 0x80; // BLOCK_USED
            putU16BE(block, 2, fileId);
            putU16BE(block, 4, partId);

            int capacity;
            int dataStart;

            if (partId == 0) {
                // First block: header with filename and timestamp
                block[6] = 0x20;
                byte[] nameBytes = filename.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                int nameLen = Math.min(nameBytes.length, 58);
                System.arraycopy(nameBytes, 0, block, 7, nameLen);
                block[7 + nameLen] = 0; // null terminate

                // Timestamp: seconds since 1900/01/01
                long epochSeconds = System.currentTimeMillis() / 1000;
                long seconds1900 = epochSeconds + 2208988800L;
                putU32BE(block, 74, (int) seconds1900);

                capacity = FIRST_BLOCK_CAPACITY;
                dataStart = 6 + FILE_HEADER_SIZE; // 78
            } else {
                capacity = CONT_BLOCK_CAPACITY;
                dataStart = 6;
            }

            int bytesToCopy = Math.min(capacity, bytesLeft);
            block[1] = (byte) bytesToCopy;
            System.arraycopy(fileData, fileOffset, block, dataStart, bytesToCopy);
            fileOffset += bytesToCopy;
            bytesLeft -= bytesToCopy;

            writeBlock(BOOT_BLOCKS + b, block);
            partId++;
        }

        System.out.printf("[CFS] Added '%s' (%d bytes, %d blocks)%n",
            filename, fileData.length, partId);
        return true;
    }

    /** List files in the CFS image. */
    public List<String> listFiles() {
        List<String> files = new ArrayList<>();
        for (int b = 0; b < FILE_BLOCKS; b++) {
            byte[] block = readBlock(BOOT_BLOCKS + b);
            if ((block[0] & 0x80) != 0 && getU16BE(block, 4) == 0) {
                // Used block with part_id == 0 = first block of a file
                String name = readFilename(block);
                int fileId = getU16BE(block, 2);
                int size = calcFileSize(fileId);
                files.add(String.format("%s (%d bytes)", name, size));
            }
        }
        return files;
    }

    /** Delete a file by file ID. */
    public void deleteFile(int fileId) {
        for (int b = 0; b < FILE_BLOCKS; b++) {
            byte[] block = readBlock(BOOT_BLOCKS + b);
            if ((block[0] & 0x80) != 0 && getU16BE(block, 2) == fileId) {
                block[0] &= ~0x80; // Clear BLOCK_USED
                writeBlock(BOOT_BLOCKS + b, block);
            }
        }
    }

    /** Find a file by name, return its file ID or -1 if not found. */
    public int findFile(String filename) {
        for (int b = 0; b < FILE_BLOCKS; b++) {
            byte[] block = readBlock(BOOT_BLOCKS + b);
            if ((block[0] & 0x80) != 0 && getU16BE(block, 4) == 0) {
                if (filename.equals(readFilename(block))) {
                    return getU16BE(block, 2);
                }
            }
        }
        return -1;
    }

    /** Get the raw image bytes (for loading into emulator RAM). */
    public byte[] getImageData() {
        return image;
    }

    /** Get just the filesystem data without padding (PAGE_COUNT * PAGE_SIZE bytes). */
    public byte[] getFilesystemData() {
        return Arrays.copyOf(image, PAGE_COUNT * PAGE_SIZE);
    }

    /** Check if raw data looks like a CFS image (boot block signature). */
    public static boolean isCfsImage(byte[] data) {
        if (data.length < PAGE_SIZE * BOOT_BLOCKS) return false;
        // Check first boot block: CRC should be 0xFFFF, data should be all 0xFF
        if ((data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xFF) return false;
        for (int i = 2; i < PAGE_SIZE; i++) {
            if ((data[i] & 0xFF) != 0xFF) return false;
        }
        return true;
    }

    // --- Internal helpers ---

    private byte[] readBlock(int page) {
        byte[] block = new byte[BLOCK_DATA_SIZE];
        System.arraycopy(image, page * PAGE_SIZE + 2, block, 0, BLOCK_DATA_SIZE);
        return block;
    }

    private void writeBlock(int page, byte[] block) {
        // Copy block data into page (after CRC bytes)
        System.arraycopy(block, 0, image, page * PAGE_SIZE + 2, BLOCK_DATA_SIZE);
        // Calculate and write CRC
        int crc = calcChecksum(image, page * PAGE_SIZE + 2, BLOCK_DATA_SIZE);
        image[page * PAGE_SIZE] = (byte) ((crc >> 8) & 0xFF);
        image[page * PAGE_SIZE + 1] = (byte) (crc & 0xFF);
    }

    private int countFreeBlocks() {
        int free = 0;
        for (int b = 0; b < FILE_BLOCKS; b++) {
            int off = (BOOT_BLOCKS + b) * PAGE_SIZE + 2; // Skip CRC, read flags byte
            if ((image[off] & 0x80) == 0) free++;
        }
        return free;
    }

    private int calcFileSize(int fileId) {
        int size = 0;
        for (int b = 0; b < FILE_BLOCKS; b++) {
            byte[] block = readBlock(BOOT_BLOCKS + b);
            if ((block[0] & 0x80) != 0 && getU16BE(block, 2) == fileId) {
                size += block[1] & 0xFF;
            }
        }
        return size;
    }

    private static String readFilename(byte[] block) {
        int end = 7;
        while (end < 65 && block[end] != 0) end++;
        return new String(block, 7, end - 7, java.nio.charset.StandardCharsets.US_ASCII);
    }

    /**
     * CRC16 checksum matching MAME's page_buffer_calc_checksum().
     * XOR-rotate algorithm: val = (val ^ data[i] ^ i) << 1, with bit 16 fed back to bit 0.
     */
    static int calcChecksum(byte[] data, int offset, int size) {
        int val = 0;
        for (int i = 0; i < size; i++) {
            val = (val ^ (data[offset + i] & 0xFF) ^ i) << 1;
            val = val | ((val >>> 16) & 0x0001);
        }
        return val & 0xFFFF;
    }

    private static int getU16BE(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static void putU16BE(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >> 8) & 0xFF);
        data[offset + 1] = (byte) (value & 0xFF);
    }

    private static void putU32BE(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >> 24) & 0xFF);
        data[offset + 1] = (byte) ((value >> 16) & 0xFF);
        data[offset + 2] = (byte) ((value >> 8) & 0xFF);
        data[offset + 3] = (byte) (value & 0xFF);
    }
}
