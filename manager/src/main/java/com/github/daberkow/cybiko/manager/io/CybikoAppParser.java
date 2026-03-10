package com.github.daberkow.cybiko.manager.io;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parses Cybiko .app archive files.
 * <p>
 * Archive format (big-endian throughout):
 * <pre>
 * Offset 0-1:  "Cy" magic (0x43 0x79)
 * Offset 2-3:  File count N (16-bit BE)
 * Offset 4-5:  Header payload size (16-bit BE)
 * Offset 6:    File entry table (N entries, 10 bytes each):
 *   - bytes 0-1: name string offset (absolute, 16-bit BE)
 *   - bytes 2-5: data offset (absolute, 32-bit BE)
 *   - bytes 6-9: data size (32-bit BE)
 * After entries: Null-terminated filename strings
 * After names:   File data blobs
 * </pre>
 */
public final class CybikoAppParser {

    private static final byte[] MAGIC = {0x43, 0x79}; // "Cy"
    private static final int ENTRY_SIZE = 10;

    private final byte[] data;
    private final List<Entry> entries;

    /**
     * Parse a Cybiko .app archive.
     *
     * @param appData the raw .app file bytes
     * @throws IllegalArgumentException if the magic bytes are not "Cy"
     */
    public CybikoAppParser(byte[] appData) {
        if (appData.length < 6 || appData[0] != MAGIC[0] || appData[1] != MAGIC[1]) {
            throw new IllegalArgumentException("Invalid Cybiko .app magic: expected 'Cy'");
        }
        this.data = appData;
        int fileCount = readU16(appData, 2);
        this.entries = new ArrayList<>(fileCount);
        for (int i = 0; i < fileCount; i++) {
            int base = 6 + i * ENTRY_SIZE;
            int nameOffset = readU16(appData, base);
            int dataOffset = readU32(appData, base + 2);
            int dataSize = readU32(appData, base + 6);
            String name = readNullTerminated(appData, nameOffset);
            entries.add(new Entry(name, dataOffset, dataSize));
        }
    }

    /** Returns the list of embedded file names. */
    public List<String> listFiles() {
        return entries.stream().map(Entry::name).toList();
    }

    /**
     * Extracts and decompresses a file from the archive.
     *
     * @param name the file name to extract
     * @return decompressed file data, or null if not found
     */
    public byte[] extractFile(String name) {
        for (Entry entry : entries) {
            if (entry.name().equals(name)) {
                byte[] blob = Arrays.copyOfRange(data, entry.dataOffset(),
                        entry.dataOffset() + entry.dataSize());
                return CybikoDecompressor.decompress(blob);
            }
        }
        return null;
    }

    private static int readU16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static int readU32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static String readNullTerminated(byte[] b, int off) {
        int end = off;
        while (end < b.length && b[end] != 0) {
            end++;
        }
        return new String(b, off, end - off, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private record Entry(String name, int dataOffset, int dataSize) {}
}
