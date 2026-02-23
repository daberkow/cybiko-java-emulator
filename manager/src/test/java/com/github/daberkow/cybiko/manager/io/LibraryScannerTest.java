package com.github.daberkow.cybiko.manager.io;

import com.github.daberkow.cybiko.manager.model.AppEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LibraryScannerTest {

    @TempDir
    Path tempDir;

    @Test
    void scanFindsAppFiles() throws IOException {
        Files.write(tempDir.resolve("calc.app"), new byte[]{1, 2, 3});
        Files.write(tempDir.resolve("dice.app"), new byte[]{4, 5});

        List<AppEntry> entries = LibraryScanner.scan(tempDir);

        assertEquals(2, entries.size());
        // Sorted alphabetically
        assertEquals("calc.app", entries.get(0).name());
        assertEquals("dice.app", entries.get(1).name());
        assertEquals(3, entries.get(0).sizeBytes());
        assertEquals(2, entries.get(1).sizeBytes());
    }

    @Test
    void scanCaseInsensitiveExtension() throws IOException {
        Files.write(tempDir.resolve("game.APP"), new byte[]{1});
        Files.write(tempDir.resolve("tool.App"), new byte[]{2});

        List<AppEntry> entries = LibraryScanner.scan(tempDir);
        assertEquals(2, entries.size());
    }

    @Test
    void scanIgnoresNonAppFiles() throws IOException {
        Files.write(tempDir.resolve("calc.app"), new byte[]{1});
        Files.write(tempDir.resolve("readme.txt"), new byte[]{2});
        Files.write(tempDir.resolve("data.bin"), new byte[]{3});

        List<AppEntry> entries = LibraryScanner.scan(tempDir);
        assertEquals(1, entries.size());
        assertEquals("calc.app", entries.get(0).name());
    }

    @Test
    void scanEmptyDirectory() {
        List<AppEntry> entries = LibraryScanner.scan(tempDir);
        assertTrue(entries.isEmpty());
    }

    @Test
    void scanNonexistentDirectory() {
        List<AppEntry> entries = LibraryScanner.scan(tempDir.resolve("nonexistent"));
        assertTrue(entries.isEmpty());
    }

    @Test
    void scanSetsCorrectPath() throws IOException {
        Path appFile = Files.write(tempDir.resolve("test.app"), new byte[]{1, 2, 3});

        List<AppEntry> entries = LibraryScanner.scan(tempDir);
        assertEquals(1, entries.size());
        assertEquals(appFile, entries.get(0).path());
    }

    @Test
    void scanSetsLastModified() throws IOException {
        Files.write(tempDir.resolve("test.app"), new byte[]{1});

        List<AppEntry> entries = LibraryScanner.scan(tempDir);
        assertEquals(1, entries.size());
        assertNotNull(entries.get(0).lastModified());
    }
}
