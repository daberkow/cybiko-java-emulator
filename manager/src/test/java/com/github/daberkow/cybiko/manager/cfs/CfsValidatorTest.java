package com.github.daberkow.cybiko.manager.cfs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class CfsValidatorTest {

    @ParameterizedTest
    @EnumSource(FlashGeometry.class)
    void freshImagePassesAllChecks(FlashGeometry geom) {
        CfsImage image = new CfsImage(geom);
        CfsValidator.Result result = CfsValidator.validate(image);
        assertTrue(result.isValid(), "Fresh image should have no errors: " + result.issues());
        assertEquals(0, result.errorCount());
        assertEquals(0, result.warningCount());
    }

    @ParameterizedTest
    @EnumSource(FlashGeometry.class)
    void imageWithFilesPassesAllChecks(FlashGeometry geom) {
        CfsImage image = new CfsImage(geom);
        image.addFile("hello.app", new byte[]{1, 2, 3, 4, 5});
        image.addFile("world.app", new byte[100]);

        CfsValidator.Result result = CfsValidator.validate(image);
        assertTrue(result.isValid(), "Image with valid files should pass: " + result.issues());
        assertEquals(0, result.errorCount());
    }

    @ParameterizedTest
    @EnumSource(FlashGeometry.class)
    void multiBlockFilePassesAllChecks(FlashGeometry geom) {
        CfsImage image = new CfsImage(geom);
        int firstCap = Math.min(geom.firstBlockCapacity(), 255);
        int contCap = Math.min(geom.contBlockCapacity(), 255);
        // Spans 3 blocks
        byte[] data = new byte[firstCap + contCap + 10];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i & 0xFF);

        image.addFile("big.dat", data);

        CfsValidator.Result result = CfsValidator.validate(image);
        assertTrue(result.isValid(), "Multi-block file should pass: " + result.issues());
        assertEquals(0, result.errorCount());
    }

    @Test
    void corruptedChecksumDetected() {
        CfsImage image = new CfsImage(FlashGeometry.XTREME);
        image.addFile("test.app", new byte[]{1, 2, 3});

        // Corrupt a file page checksum by flipping a byte in the raw image
        byte[] raw = image.toBytes();
        // Page 5 (first file block) starts at offset 5*258 = 1290
        // CRC is at bytes 0-1, block data starts at byte 2
        // Corrupt a data byte
        raw[1290 + 10] ^= 0xFF;
        CfsImage corrupted = new CfsImage(raw, FlashGeometry.XTREME);

        CfsValidator.Result result = CfsValidator.validate(corrupted);
        assertFalse(result.isValid());
        assertTrue(result.errorCount() > 0);
        assertTrue(result.issues().stream()
            .anyMatch(i -> i.category().equals("Checksum")));
    }

    @Test
    void corruptedBootBlockDetected() {
        CfsImage image = new CfsImage(FlashGeometry.XTREME);
        byte[] raw = image.toBytes();
        // Boot block 0 starts at offset 0, block data at offset 2
        // Write non-0xFF into boot block data area
        raw[2 + 50] = 0x42;
        // Fix checksum so only boot content check triggers, not checksum check
        // For Xtreme boot blocks, CRC is 0xFFFF and verification is skipped
        CfsImage corrupted = new CfsImage(raw, FlashGeometry.XTREME);

        CfsValidator.Result result = CfsValidator.validate(corrupted);
        assertTrue(result.issues().stream()
            .anyMatch(i -> i.category().equals("Boot block")),
            "Should detect non-0xFF boot block: " + result.issues());
    }

    @Test
    void deleteAndReaddLeavesCleanImage() {
        CfsImage image = new CfsImage(FlashGeometry.XTREME);
        image.addFile("test.app", new byte[]{1, 2, 3});
        image.deleteFile("test.app");
        image.addFile("other.app", new byte[]{4, 5, 6});

        CfsValidator.Result result = CfsValidator.validate(image);
        assertTrue(result.isValid(), "Image after delete+add should be clean: " + result.issues());
    }

    @Test
    void emptyFilePassesValidation() {
        CfsImage image = new CfsImage(FlashGeometry.XTREME);
        image.addFile("empty.txt", new byte[0]);

        CfsValidator.Result result = CfsValidator.validate(image);
        assertTrue(result.isValid(), "Empty file should pass: " + result.issues());
    }

    @Test
    void resultCountsMatchIssueList() {
        CfsImage image = new CfsImage(FlashGeometry.XTREME);
        CfsValidator.Result result = CfsValidator.validate(image);
        long errors = result.issues().stream()
            .filter(i -> i.severity() == CfsValidator.Severity.ERROR).count();
        long warnings = result.issues().stream()
            .filter(i -> i.severity() == CfsValidator.Severity.WARNING).count();
        assertEquals(errors, result.errorCount());
        assertEquals(warnings, result.warningCount());
    }

    @Test
    void roundtrippedImageStillValid() {
        CfsImage image = new CfsImage(FlashGeometry.XTREME);
        image.addFile("a.app", new byte[50]);
        image.addFile("b.app", new byte[200]);

        byte[] raw = image.toBytes();
        CfsImage loaded = new CfsImage(raw, FlashGeometry.XTREME);

        CfsValidator.Result result = CfsValidator.validate(loaded);
        assertTrue(result.isValid(), "Roundtripped image should pass: " + result.issues());
    }

    // ---- Repair tests ----

    @Test
    void repairCorruptedChecksumPreservesFiles() {
        CfsImage image = new CfsImage(FlashGeometry.XTREME);
        image.addFile("keep.app", new byte[]{10, 20, 30});

        // Corrupt a checksum
        byte[] raw = image.toBytes();
        raw[5 * 258 + 10] ^= 0xFF;  // flip byte in first file block page
        CfsImage corrupted = new CfsImage(raw, FlashGeometry.XTREME);

        // Verify it's broken
        assertFalse(CfsValidator.validate(corrupted).isValid());

        // Repair
        CfsValidator.RepairResult repairResult = CfsValidator.repair(corrupted);

        // Should pass validation now
        CfsValidator.Result postCheck = CfsValidator.validate(corrupted);
        assertTrue(postCheck.isValid(),
            "Repaired image should pass validation: " + postCheck.issues());

        // File should be preserved (data was intact, only checksum was wrong)
        assertFalse(repairResult.preserved().isEmpty(),
            "Should preserve files: " + repairResult);
    }

    @Test
    void repairCorruptedBootBlockFixesIt() {
        CfsImage image = new CfsImage(FlashGeometry.XTREME);
        byte[] raw = image.toBytes();
        raw[2 + 50] = 0x42;  // corrupt boot block data
        CfsImage corrupted = new CfsImage(raw, FlashGeometry.XTREME);

        CfsValidator.repair(corrupted);

        CfsValidator.Result postCheck = CfsValidator.validate(corrupted);
        assertTrue(postCheck.isValid(),
            "Boot block should be fixed: " + postCheck.issues());
    }

    @ParameterizedTest
    @EnumSource(FlashGeometry.class)
    void repairCleanImageIsNoOp(FlashGeometry geom) {
        CfsImage image = new CfsImage(geom);
        image.addFile("a.app", new byte[]{1, 2, 3});
        image.addFile("b.app", new byte[]{4, 5, 6});

        CfsValidator.RepairResult repairResult = CfsValidator.repair(image);

        assertEquals(2, repairResult.preserved().size());
        assertTrue(repairResult.removed().isEmpty());

        // Files still readable
        assertNotNull(image.readFile("a.app"));
        assertNotNull(image.readFile("b.app"));
        assertArrayEquals(new byte[]{1, 2, 3}, image.readFile("a.app").data());
        assertArrayEquals(new byte[]{4, 5, 6}, image.readFile("b.app").data());

        // Passes validation
        assertTrue(CfsValidator.validate(image).isValid());
    }

    @Test
    void repairPreservesMultiBlockFiles() {
        FlashGeometry geom = FlashGeometry.XTREME;
        CfsImage image = new CfsImage(geom);
        int firstCap = Math.min(geom.firstBlockCapacity(), 255);
        int contCap = Math.min(geom.contBlockCapacity(), 255);
        byte[] bigData = new byte[firstCap + contCap + 10];
        for (int i = 0; i < bigData.length; i++) bigData[i] = (byte) (i & 0xFF);

        image.addFile("big.dat", bigData);

        CfsValidator.RepairResult repairResult = CfsValidator.repair(image);

        assertEquals(1, repairResult.preserved().size());
        assertArrayEquals(bigData, image.readFile("big.dat").data());
        assertTrue(CfsValidator.validate(image).isValid());
    }

    @Test
    void repairRemovesOrphanedBlocks() {
        CfsImage image = new CfsImage(FlashGeometry.XTREME);
        image.addFile("good.app", new byte[]{1, 2, 3});

        // Manually write an orphaned continuation block (part 1, no part 0)
        // fileId=9999 doesn't have a part 0
        byte[] orphanData = new byte[image.geometry().blockDataSize()];
        java.util.Arrays.fill(orphanData, (byte) 0xFF);
        orphanData[0] = (byte) 0x80; // BLOCK_USED
        orphanData[1] = 5; // dataSize
        orphanData[2] = (byte) ((9999 >> 8) & 0xFF); // fileId high
        orphanData[3] = (byte) (9999 & 0xFF);         // fileId low
        orphanData[4] = 0; // partId high
        orphanData[5] = 1; // partId=1 (continuation, no part 0)
        orphanData[6] = 0; // type marker for continuation

        // Write to block 5 (after block 0 used by good.app)
        byte[] page = CfsPage.writePageBuffer(orphanData, image.geometry(), false, 99);
        System.arraycopy(page, 0, image.toBytes(), 0, 0); // need direct access
        // Use the package-visible write path via raw bytes manipulation
        byte[] raw = image.toBytes();
        int pageOffset = (FlashGeometry.BOOT_BLOCKS + 5) * image.geometry().pageSize();
        System.arraycopy(page, 0, raw, pageOffset, page.length);
        // Reload the image from corrupted bytes
        CfsImage corrupted = new CfsImage(raw, FlashGeometry.XTREME);

        // Should have errors
        CfsValidator.Result preCheck = CfsValidator.validate(corrupted);
        assertTrue(preCheck.issues().stream()
            .anyMatch(i -> i.category().equals("Orphaned block")),
            "Should detect orphan: " + preCheck.issues());

        // Repair
        CfsValidator.RepairResult repairResult = CfsValidator.repair(corrupted);

        // Orphan removed, good file preserved
        assertTrue(repairResult.removed().stream()
            .anyMatch(s -> s.contains("orphaned")));
        assertTrue(repairResult.preserved().stream()
            .anyMatch(s -> s.contains("good.app")));

        assertTrue(CfsValidator.validate(corrupted).isValid());
        assertNotNull(corrupted.readFile("good.app"));
    }

    @Test
    void repairSetsModifiedFlag() {
        CfsImage image = new CfsImage(FlashGeometry.XTREME);
        image.addFile("test.app", new byte[]{1});
        image.clearModified();
        assertFalse(image.isModified());

        CfsValidator.repair(image);
        assertTrue(image.isModified());
    }

    @Test
    void repairEmptyImageStaysClean() {
        CfsImage image = new CfsImage(FlashGeometry.XTREME);

        CfsValidator.RepairResult repairResult = CfsValidator.repair(image);

        assertTrue(repairResult.preserved().isEmpty());
        assertTrue(repairResult.removed().isEmpty());
        assertTrue(CfsValidator.validate(image).isValid());
    }
}
