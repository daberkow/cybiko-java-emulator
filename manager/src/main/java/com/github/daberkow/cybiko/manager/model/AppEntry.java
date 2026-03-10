package com.github.daberkow.cybiko.manager.model;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * A library app file record from the local filesystem.
 *
 * @param path         absolute path to the .app file
 * @param name         filename (e.g., "calc.app")
 * @param sizeBytes    file size in bytes
 * @param lastModified last modification time
 * @param iconData     raw root.ico bytes, or null if unavailable
 */
public record AppEntry(
    Path path,
    String name,
    long sizeBytes,
    Instant lastModified,
    byte[] iconData
) {

    /** File extension (lowercase), or empty string if none. */
    public String extension() {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase();
    }

    /** Convert last modified time to LocalDateTime. */
    public LocalDateTime lastModifiedDateTime() {
        return LocalDateTime.ofInstant(lastModified, ZoneId.systemDefault());
    }
}
