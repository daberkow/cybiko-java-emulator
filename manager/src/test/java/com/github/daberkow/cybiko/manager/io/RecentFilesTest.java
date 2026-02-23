package com.github.daberkow.cybiko.manager.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecentFilesTest {

    @TempDir
    Path tempDir;

    @Test
    void saveAndLoadRoundtrip() throws IOException {
        Path config = tempDir.resolve("recent.properties");

        List<Path> paths = List.of(
            Path.of("/home/user/test1.nvram"),
            Path.of("/home/user/test2.nvram")
        );

        RecentFiles.save(paths, config);
        List<Path> loaded = RecentFiles.load(config);

        assertEquals(2, loaded.size());
        assertEquals(Path.of("/home/user/test1.nvram"), loaded.get(0));
        assertEquals(Path.of("/home/user/test2.nvram"), loaded.get(1));
    }

    @Test
    void loadMissingConfigReturnsEmpty() {
        Path config = tempDir.resolve("nonexistent.properties");
        List<Path> loaded = RecentFiles.load(config);
        assertTrue(loaded.isEmpty());
    }

    @Test
    void loadEmptyList() throws IOException {
        Path config = tempDir.resolve("recent.properties");
        RecentFiles.save(List.of(), config);
        List<Path> loaded = RecentFiles.load(config);
        assertTrue(loaded.isEmpty());
    }

    @Test
    void addRecentPushesToFront() {
        List<Path> existing = new ArrayList<>(List.of(
            Path.of("/a.nvram"),
            Path.of("/b.nvram")
        ));

        List<Path> result = RecentFiles.addRecent(existing, Path.of("/c.nvram"));

        assertEquals(3, result.size());
        assertEquals(Path.of("/c.nvram"), result.get(0));
        assertEquals(Path.of("/a.nvram"), result.get(1));
        assertEquals(Path.of("/b.nvram"), result.get(2));
    }

    @Test
    void addRecentDeduplicates() {
        List<Path> existing = new ArrayList<>(List.of(
            Path.of("/a.nvram"),
            Path.of("/b.nvram"),
            Path.of("/c.nvram")
        ));

        List<Path> result = RecentFiles.addRecent(existing, Path.of("/b.nvram"));

        assertEquals(3, result.size());
        assertEquals(Path.of("/b.nvram"), result.get(0));
        assertEquals(Path.of("/a.nvram"), result.get(1));
        assertEquals(Path.of("/c.nvram"), result.get(2));
    }

    @Test
    void addRecentTrimsToFive() {
        List<Path> existing = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            existing.add(Path.of("/file" + i + ".nvram"));
        }

        List<Path> result = RecentFiles.addRecent(existing, Path.of("/new.nvram"));

        assertEquals(5, result.size());
        assertEquals(Path.of("/new.nvram"), result.get(0));
        assertEquals(Path.of("/file0.nvram"), result.get(1));
        assertEquals(Path.of("/file3.nvram"), result.get(4));
    }

    @Test
    void saveTrimsToFive() throws IOException {
        Path config = tempDir.resolve("recent.properties");

        List<Path> paths = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            paths.add(Path.of("/file" + i + ".nvram"));
        }

        RecentFiles.save(paths, config);
        List<Path> loaded = RecentFiles.load(config);

        assertEquals(5, loaded.size());
        assertEquals(Path.of("/file0.nvram"), loaded.get(0));
        assertEquals(Path.of("/file4.nvram"), loaded.get(4));
    }

    @Test
    void saveCreatesParentDirectories() throws IOException {
        Path config = tempDir.resolve("sub/dir/recent.properties");

        RecentFiles.save(List.of(Path.of("/test.nvram")), config);
        List<Path> loaded = RecentFiles.load(config);

        assertEquals(1, loaded.size());
        assertEquals(Path.of("/test.nvram"), loaded.get(0));
    }

    @Test
    void addRecentToEmptyList() {
        List<Path> result = RecentFiles.addRecent(List.of(), Path.of("/first.nvram"));

        assertEquals(1, result.size());
        assertEquals(Path.of("/first.nvram"), result.get(0));
    }
}
