package com.github.daberkow.cybiko.manager.cfs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlashGeometryTest {

    @Test
    void fromFileSizeAT45DB041() {
        FlashGeometry g = FlashGeometry.fromFileSize(540_672);
        assertEquals(FlashGeometry.AT45DB041, g);
    }

    @Test
    void fromFileSizeAT45DB081() {
        FlashGeometry g = FlashGeometry.fromFileSize(1_081_344);
        assertEquals(FlashGeometry.AT45DB081, g);
    }

    @Test
    void fromFileSizeAT45DB161() {
        FlashGeometry g = FlashGeometry.fromFileSize(2_162_688);
        assertEquals(FlashGeometry.AT45DB161, g);
    }

    @Test
    void fromFileSizeXtreme() {
        FlashGeometry g = FlashGeometry.fromFileSize(524_288);
        assertEquals(FlashGeometry.XTREME, g);
    }

    @Test
    void fromFileSizeUnknown() {
        assertNull(FlashGeometry.fromFileSize(12345));
    }

    @Test
    void at45db041Sizes() {
        FlashGeometry g = FlashGeometry.AT45DB041;
        assertEquals(2048, g.pageCount());
        assertEquals(264, g.pageSize());
        assertEquals(8, g.pageHeaderSize());
        assertEquals(2, g.pageTrailerSize());
        assertEquals(254, g.blockDataSize()); // 264 - 8 - 2
        assertEquals(2043, g.fileBlockCount()); // 2048 - 5
        assertEquals(248, g.contBlockCapacity()); // 254 - 6
        assertEquals(176, g.firstBlockCapacity()); // 248 - 72
        assertEquals(540_672, g.totalImageSize()); // 2048 * 264
    }

    @Test
    void at45db081Sizes() {
        FlashGeometry g = FlashGeometry.AT45DB081;
        assertEquals(4096, g.pageCount());
        assertEquals(264, g.pageSize());
        assertEquals(254, g.blockDataSize());
        assertEquals(4091, g.fileBlockCount());
        assertEquals(1_081_344, g.totalImageSize());
    }

    @Test
    void at45db161Sizes() {
        FlashGeometry g = FlashGeometry.AT45DB161;
        assertEquals(4096, g.pageCount());
        assertEquals(528, g.pageSize());
        assertEquals(518, g.blockDataSize()); // 528 - 8 - 2
        assertEquals(512, g.contBlockCapacity()); // 518 - 6
        assertEquals(440, g.firstBlockCapacity()); // 512 - 72
        assertEquals(2_162_688, g.totalImageSize());
    }

    @Test
    void xtremeSizes() {
        FlashGeometry g = FlashGeometry.XTREME;
        assertEquals(2005, g.pageCount());
        assertEquals(258, g.pageSize());
        assertEquals(2, g.pageHeaderSize());
        assertEquals(0, g.pageTrailerSize());
        assertEquals(256, g.blockDataSize()); // 258 - 2
        assertEquals(2000, g.fileBlockCount()); // 2005 - 5
        assertEquals(250, g.contBlockCapacity()); // 256 - 6
        assertEquals(178, g.firstBlockCapacity()); // 250 - 72
        assertEquals(524_288, g.totalImageSize()); // 2005 * 258 + 0x1B56(6998)
    }

    @Test
    void classicFormat() {
        assertEquals(FlashGeometry.CfsFormat.CLASSIC, FlashGeometry.AT45DB041.format());
        assertEquals(FlashGeometry.CfsFormat.CLASSIC, FlashGeometry.AT45DB081.format());
        assertEquals(FlashGeometry.CfsFormat.CLASSIC, FlashGeometry.AT45DB161.format());
    }

    @Test
    void xtremeFormat() {
        assertEquals(FlashGeometry.CfsFormat.XTREME, FlashGeometry.XTREME.format());
    }
}
