package com.github.daberkow.cybiko.manager.model;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class AppEntryTest {

    @Test
    void extensionFromAppFile() {
        AppEntry entry = new AppEntry(Path.of("/apps/calc.app"), "calc.app", 1024, Instant.now(), null);
        assertEquals("app", entry.extension());
    }

    @Test
    void extensionFromUpperCase() {
        AppEntry entry = new AppEntry(Path.of("/apps/Game.APP"), "Game.APP", 2048, Instant.now(), null);
        assertEquals("app", entry.extension());
    }

    @Test
    void extensionEmptyForNoExtension() {
        AppEntry entry = new AppEntry(Path.of("/apps/README"), "README", 100, Instant.now(), null);
        assertEquals("", entry.extension());
    }

    @Test
    void extensionEmptyForTrailingDot() {
        AppEntry entry = new AppEntry(Path.of("/apps/file."), "file.", 50, Instant.now(), null);
        assertEquals("", entry.extension());
    }

    @Test
    void lastModifiedDateTime() {
        Instant instant = Instant.parse("2025-06-15T12:30:00Z");
        AppEntry entry = new AppEntry(Path.of("/apps/test.app"), "test.app", 512, instant, null);
        LocalDateTime expected = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        assertEquals(expected, entry.lastModifiedDateTime());
    }
}
