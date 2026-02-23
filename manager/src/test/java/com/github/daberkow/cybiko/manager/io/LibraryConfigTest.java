package com.github.daberkow.cybiko.manager.io;

import com.github.daberkow.cybiko.manager.model.LibraryFolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LibraryConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void saveAndLoadRoundtrip() throws IOException {
        Path config = tempDir.resolve("library.properties");

        List<LibraryFolder> folders = List.of(
            new LibraryFolder(Path.of("/home/user/games"), "Games", "games"),
            new LibraryFolder(Path.of("/home/user/tools"), "Tools", "tools")
        );

        LibraryConfig.save(folders, config);
        List<LibraryFolder> loaded = LibraryConfig.load(config);

        assertEquals(2, loaded.size());
        assertEquals("Games", loaded.get(0).label());
        assertEquals(Path.of("/home/user/games"), loaded.get(0).path());
        assertEquals("games", loaded.get(0).category());
        assertEquals("Tools", loaded.get(1).label());
        assertEquals(Path.of("/home/user/tools"), loaded.get(1).path());
        assertEquals("tools", loaded.get(1).category());
    }

    @Test
    void loadEmptyList() throws IOException {
        Path config = tempDir.resolve("library.properties");

        LibraryConfig.save(List.of(), config);
        List<LibraryFolder> loaded = LibraryConfig.load(config);

        assertTrue(loaded.isEmpty());
    }

    @Test
    void loadMissingConfigReturnsEmpty() {
        Path config = tempDir.resolve("nonexistent.properties");
        List<LibraryFolder> loaded = LibraryConfig.load(config);
        assertTrue(loaded.isEmpty());
    }

    @Test
    void saveCreatesParentDirectories() throws IOException {
        Path config = tempDir.resolve("sub/dir/library.properties");

        List<LibraryFolder> folders = List.of(
            new LibraryFolder(Path.of("/apps"), "Apps", "apps")
        );

        LibraryConfig.save(folders, config);
        List<LibraryFolder> loaded = LibraryConfig.load(config);

        assertEquals(1, loaded.size());
        assertEquals("Apps", loaded.get(0).label());
    }

    @Test
    void singleFolder() throws IOException {
        Path config = tempDir.resolve("library.properties");

        List<LibraryFolder> folders = List.of(
            new LibraryFolder(Path.of("/only/folder"), "Only", "only")
        );

        LibraryConfig.save(folders, config);
        List<LibraryFolder> loaded = LibraryConfig.load(config);

        assertEquals(1, loaded.size());
        assertEquals("Only", loaded.get(0).label());
        assertEquals(Path.of("/only/folder"), loaded.get(0).path());
    }
}
