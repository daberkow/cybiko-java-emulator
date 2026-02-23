package com.github.daberkow.cybiko.manager.cfs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class CfsPageTest {

    @ParameterizedTest
    @EnumSource(FlashGeometry.class)
    void readBlockDataExtractsCorrectRegion(FlashGeometry geom) {
        byte[] page = new byte[geom.pageSize()];
        // Fill header with 0xAA, block data with sequential bytes, trailer with 0xBB
        Arrays.fill(page, 0, geom.pageHeaderSize(), (byte) 0xAA);
        for (int i = 0; i < geom.blockDataSize(); i++) {
            page[geom.pageHeaderSize() + i] = (byte) (i & 0xFF);
        }
        if (geom.pageTrailerSize() > 0) {
            Arrays.fill(page, geom.pageSize() - geom.pageTrailerSize(), geom.pageSize(), (byte) 0xBB);
        }

        byte[] block = CfsPage.readBlockData(page, geom);
        assertEquals(geom.blockDataSize(), block.length);
        for (int i = 0; i < block.length; i++) {
            assertEquals((byte) (i & 0xFF), block[i], "Mismatch at index " + i);
        }
    }

    @ParameterizedTest
    @EnumSource(FlashGeometry.class)
    void writeAndVerifyRoundtrip(FlashGeometry geom) {
        byte[] blockData = new byte[geom.blockDataSize()];
        Arrays.fill(blockData, (byte) 0x42);
        blockData[0] = 0x7F; // unused block

        byte[] page = CfsPage.writePageBuffer(blockData, geom, false, 0);
        assertEquals(geom.pageSize(), page.length);
        assertTrue(CfsPage.verify(page, geom, false), "Page verification failed");
    }

    @ParameterizedTest
    @EnumSource(FlashGeometry.class)
    void bootBlockRoundtrip(FlashGeometry geom) {
        byte[] blockData = new byte[geom.blockDataSize()];
        Arrays.fill(blockData, (byte) 0xFF);

        byte[] page = CfsPage.writePageBuffer(blockData, geom, true, 0);
        assertEquals(geom.pageSize(), page.length);
        assertTrue(CfsPage.verify(page, geom, true), "Boot page verification failed");
    }

    @ParameterizedTest
    @EnumSource(FlashGeometry.class)
    void corruptionDetected(FlashGeometry geom) {
        byte[] blockData = new byte[geom.blockDataSize()];
        Arrays.fill(blockData, (byte) 0x42);

        byte[] page = CfsPage.writePageBuffer(blockData, geom, false, 0);

        // Corrupt a data byte
        int dataOff = geom.pageHeaderSize() + 10;
        page[dataOff] ^= 0xFF;

        // Xtreme boot blocks skip verification, so only test file blocks
        assertFalse(CfsPage.verify(page, geom, false), "Corrupted page should fail verification");
    }

    @Test
    void xtremeBootSkipsVerification() {
        FlashGeometry geom = FlashGeometry.XTREME;
        byte[] page = new byte[geom.pageSize()];
        // Garbage data should still pass boot verification
        Arrays.fill(page, (byte) 0x12);
        assertTrue(CfsPage.verify(page, geom, true));
    }

    @Test
    void classicPageHasTrailer() {
        FlashGeometry geom = FlashGeometry.AT45DB041;
        byte[] blockData = new byte[geom.blockDataSize()];
        Arrays.fill(blockData, (byte) 0x42);

        byte[] page = CfsPage.writePageBuffer(blockData, geom, false, 0);

        // Trailer should be 0xFFFF
        assertEquals((byte) 0xFF, page[geom.pageSize() - 2]);
        assertEquals((byte) 0xFF, page[geom.pageSize() - 1]);
    }

    @Test
    void classicWriteCountPlaced() {
        FlashGeometry geom = FlashGeometry.AT45DB041;
        byte[] blockData = new byte[geom.blockDataSize()];

        byte[] page = CfsPage.writePageBuffer(blockData, geom, false, 0x1234);

        // Write count at offset 4-5
        assertEquals((byte) 0x12, page[4]);
        assertEquals((byte) 0x34, page[5]);
    }

    @Test
    void readBlockDataRoundtrip() {
        FlashGeometry geom = FlashGeometry.XTREME;
        byte[] original = new byte[geom.blockDataSize()];
        for (int i = 0; i < original.length; i++) original[i] = (byte) (i * 3);

        byte[] page = CfsPage.writePageBuffer(original, geom, false, 0);
        byte[] extracted = CfsPage.readBlockData(page, geom);
        assertArrayEquals(original, extracted);
    }
}
