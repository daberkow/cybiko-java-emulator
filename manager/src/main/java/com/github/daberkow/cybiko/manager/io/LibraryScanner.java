package com.github.daberkow.cybiko.manager.io;

import com.github.daberkow.cybiko.manager.model.AppEntry;
import com.github.daberkow.cybiko.manager.model.LibraryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Scans directories for .app files and returns AppEntry lists.
 */
public final class LibraryScanner {

    private LibraryScanner() {}

    /** Scan a library folder for .app files. */
    public static List<AppEntry> scan(LibraryFolder folder) {
        return scan(folder.path());
    }

    /** Scan a directory for .app files (case-insensitive extension match). */
    public static List<AppEntry> scan(Path directory) {
        if (!Files.isDirectory(directory)) return Collections.emptyList();

        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(p -> {
                    String name = p.getFileName().toString();
                    return name.toLowerCase().endsWith(".app");
                })
                .map(p -> {
                    try {
                        return new AppEntry(
                            p,
                            p.getFileName().toString(),
                            Files.size(p),
                            Files.getLastModifiedTime(p).toInstant()
                        );
                    } catch (IOException e) {
                        return null;
                    }
                })
                .filter(e -> e != null)
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .toList();
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }
}
