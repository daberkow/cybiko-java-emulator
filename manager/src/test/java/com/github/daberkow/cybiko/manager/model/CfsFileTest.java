package com.github.daberkow.cybiko.manager.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class CfsFileTest {

    @Test
    void size() {
        CfsFile file = new CfsFile(0, "test.txt", new byte[]{1, 2, 3, 4, 5}, 0, 1);
        assertEquals(5, file.size());
    }

    @Test
    void extensionNormal() {
        CfsFile file = new CfsFile(0, "calc.app", new byte[0], 0, 1);
        assertEquals("app", file.extension());
    }

    @Test
    void extensionUpperCase() {
        CfsFile file = new CfsFile(0, "README.TXT", new byte[0], 0, 1);
        assertEquals("txt", file.extension());
    }

    @Test
    void extensionNone() {
        CfsFile file = new CfsFile(0, "Makefile", new byte[0], 0, 1);
        assertEquals("", file.extension());
    }

    @Test
    void extensionEndsWithDot() {
        CfsFile file = new CfsFile(0, "weird.", new byte[0], 0, 1);
        assertEquals("", file.extension());
    }

    @Test
    void extensionMultipleDots() {
        CfsFile file = new CfsFile(0, "archive.tar.gz", new byte[0], 0, 1);
        assertEquals("gz", file.extension());
    }

    @Test
    void timestampConversionRoundtrip() {
        LocalDateTime now = LocalDateTime.of(2025, 6, 15, 12, 30, 0);
        long ts = CfsFile.toTimestamp(now);

        CfsFile file = new CfsFile(0, "test", new byte[0], ts, 1);
        LocalDateTime converted = file.lastModified();

        assertEquals(now.getYear(), converted.getYear());
        assertEquals(now.getMonth(), converted.getMonth());
        assertEquals(now.getDayOfMonth(), converted.getDayOfMonth());
        assertEquals(now.getHour(), converted.getHour());
        assertEquals(now.getMinute(), converted.getMinute());
    }

    @Test
    void epochOffset() {
        // 2208988800 seconds from 1900 to 1970
        assertEquals(2_208_988_800L, CfsFile.EPOCH_OFFSET);
    }

    @Test
    void nowTimestampReasonable() {
        long ts = CfsFile.nowTimestamp();
        // Should be > 2020-01-01 in CFS time
        // 2020 = 120 years from 1900 = ~3,786,912,000
        assertTrue(ts > 3_786_912_000L);
    }
}
