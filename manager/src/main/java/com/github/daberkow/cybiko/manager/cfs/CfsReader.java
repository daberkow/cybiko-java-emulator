package com.github.daberkow.cybiko.manager.cfs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads CFS images from files with auto-detection of geometry.
 */
public final class CfsReader {

    private CfsReader() {}

    /** Read a CFS image from a file path, auto-detecting geometry from file size. */
    public static CfsImage read(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        return new CfsImage(data);
    }

    /** Read a CFS image with explicit geometry. */
    public static CfsImage read(Path path, FlashGeometry geom) throws IOException {
        byte[] data = Files.readAllBytes(path);
        return new CfsImage(data, geom);
    }
}
