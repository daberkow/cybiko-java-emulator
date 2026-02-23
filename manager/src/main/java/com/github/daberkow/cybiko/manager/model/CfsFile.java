package com.github.daberkow.cybiko.manager.model;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * A file stored in a CFS image.
 *
 * @param fileId    block index of the first block
 * @param name      filename (max 58 chars)
 * @param data      raw file content bytes
 * @param timestamp seconds since 1900-01-01 00:00:00
 * @param blockCount number of blocks occupied
 */
public record CfsFile(
    int fileId,
    String name,
    byte[] data,
    long timestamp,
    int blockCount
) {

    /** Seconds between 1900-01-01 and 1970-01-01 (Unix epoch). */
    public static final long EPOCH_OFFSET = 2_208_988_800L;

    /** File size in bytes. */
    public int size() {
        return data.length;
    }

    /** File extension (lowercase), or empty string if none. */
    public String extension() {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase();
    }

    /** Convert CFS timestamp to LocalDateTime. */
    public LocalDateTime lastModified() {
        long epochSeconds = timestamp - EPOCH_OFFSET;
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneId.systemDefault());
    }

    /** Convert a LocalDateTime to CFS timestamp (seconds since 1900-01-01). */
    public static long toTimestamp(LocalDateTime dateTime) {
        long epochSeconds = dateTime.atZone(ZoneId.systemDefault()).toEpochSecond();
        return epochSeconds + EPOCH_OFFSET;
    }

    /** Convert current system time to CFS timestamp. */
    public static long nowTimestamp() {
        return System.currentTimeMillis() / 1000 + EPOCH_OFFSET;
    }
}
