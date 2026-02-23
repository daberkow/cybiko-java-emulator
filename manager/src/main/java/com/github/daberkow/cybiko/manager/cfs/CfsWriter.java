package com.github.daberkow.cybiko.manager.cfs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes CFS images to files.
 */
public final class CfsWriter {

    private CfsWriter() {}

    /** Write a CFS image to a file path. */
    public static void write(CfsImage image, Path path) throws IOException {
        Files.write(path, image.toBytes());
    }
}
