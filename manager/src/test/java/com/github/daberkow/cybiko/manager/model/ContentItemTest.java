package com.github.daberkow.cybiko.manager.model;

import com.github.daberkow.cybiko.manager.model.ContentItem.LibraryItem;
import com.github.daberkow.cybiko.manager.model.ContentItem.NvramItem;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ContentItemTest {

    @Test
    void nvramItemDelegatesToCfsFile() {
        CfsFile file = new CfsFile(5, "hello.app", new byte[]{1, 2, 3}, CfsFile.nowTimestamp(), 1);
        NvramItem item = new NvramItem(file);

        assertEquals("hello.app", item.name());
        assertEquals("app", item.extension());
        assertEquals(3, item.sizeBytes());
        assertTrue(item.inNvram());
    }

    @Test
    void nvramItemAlwaysInNvram() {
        CfsFile file = new CfsFile(0, "test.bin", new byte[0], 0, 1);
        NvramItem item = new NvramItem(file);
        assertTrue(item.inNvram());
    }

    @Test
    void libraryItemDelegatesToAppEntry() {
        AppEntry entry = new AppEntry(Path.of("/apps/calc.app"), "calc.app", 2048, Instant.now());
        LibraryItem item = new LibraryItem(entry, "");

        assertEquals("calc.app", item.name());
        assertEquals("app", item.extension());
        assertEquals(2048, item.sizeBytes());
        assertFalse(item.inNvram());
    }

    @Test
    void libraryItemInNvramWhenFlagged() {
        AppEntry entry = new AppEntry(Path.of("/apps/dice.app"), "dice.app", 1024, Instant.now());
        LibraryItem inNvram = new LibraryItem(entry, "cybiko.nvram");
        LibraryItem notInNvram = new LibraryItem(entry, "");

        assertTrue(inNvram.inNvram());
        assertFalse(notInNvram.inNvram());
    }

    @Test
    void libraryItemNvramStatusShowsImageNames() {
        AppEntry entry = new AppEntry(Path.of("/apps/dice.app"), "dice.app", 1024, Instant.now());
        LibraryItem single = new LibraryItem(entry, "cybiko.nvram");
        LibraryItem multi = new LibraryItem(entry, "cybiko.nvram, test.nvram");

        assertEquals("cybiko.nvram", single.nvramStatus());
        assertEquals("cybiko.nvram, test.nvram", multi.nvramStatus());
    }

    @Test
    void libraryItemLastModifiedMatchesEntry() {
        Instant now = Instant.now();
        AppEntry entry = new AppEntry(Path.of("/apps/game.app"), "game.app", 512, now);
        LibraryItem item = new LibraryItem(entry, "");

        assertEquals(entry.lastModifiedDateTime(), item.lastModified());
    }

    @Test
    void nvramItemLastModifiedMatchesCfsFile() {
        long ts = CfsFile.nowTimestamp();
        CfsFile file = new CfsFile(1, "data.dat", new byte[10], ts, 1);
        NvramItem item = new NvramItem(file);

        assertEquals(file.lastModified(), item.lastModified());
    }
}
