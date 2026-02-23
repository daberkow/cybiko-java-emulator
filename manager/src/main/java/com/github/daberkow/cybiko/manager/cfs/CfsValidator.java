package com.github.daberkow.cybiko.manager.cfs;

import java.util.*;

/**
 * Comprehensive CFS image validator. Checks everything CyOS cares about:
 * page checksums, boot block integrity, block flags, file structure consistency,
 * part sequencing, orphaned blocks, and data size fields.
 */
public final class CfsValidator {

    /** Severity level for a validation issue. */
    public enum Severity {
        /** Will cause CyOS flash repair or data loss. */
        ERROR,
        /** Suspicious but may not trigger repair. */
        WARNING
    }

    /** A single validation issue found in the image. */
    public record Issue(Severity severity, String category, int blockOrPage, String message) {
        @Override
        public String toString() {
            String loc = blockOrPage >= 0 ? " [block " + blockOrPage + "]" : "";
            return severity + loc + ": " + message;
        }
    }

    /** Full validation result. */
    public record Result(List<Issue> issues) {
        public boolean isValid() {
            return issues.stream().noneMatch(i -> i.severity() == Severity.ERROR);
        }

        public long errorCount() {
            return issues.stream().filter(i -> i.severity() == Severity.ERROR).count();
        }

        public long warningCount() {
            return issues.stream().filter(i -> i.severity() == Severity.WARNING).count();
        }
    }

    private CfsValidator() {}

    /** Run all validation checks on the image. */
    public static Result validate(CfsImage image) {
        List<Issue> issues = new ArrayList<>();
        FlashGeometry geom = image.geometry();

        checkPageChecksums(image, geom, issues);
        checkBootBlocks(image, geom, issues);
        checkBlockFlags(image, geom, issues);
        checkFileStructure(image, geom, issues);
        checkDataSizes(image, geom, issues);

        return new Result(List.copyOf(issues));
    }

    /** 1. Page checksum verification (CRC16 for Xtreme, CRC32+CRC16 for Classic). */
    private static void checkPageChecksums(CfsImage image, FlashGeometry geom, List<Issue> issues) {
        for (int p = 0; p < geom.pageCount(); p++) {
            byte[] page = image.readPagePublic(p);
            boolean isBoot = p < FlashGeometry.BOOT_BLOCKS;
            if (!CfsPage.verify(page, geom, isBoot)) {
                int block = isBoot ? -1 : p - FlashGeometry.BOOT_BLOCKS;
                issues.add(new Issue(Severity.ERROR, "Checksum",
                    block,
                    "Page " + p + " checksum mismatch — CyOS will trigger flash repair"));
            }
        }
    }

    /** 2. Boot blocks should be all 0xFF data (empty). */
    private static void checkBootBlocks(CfsImage image, FlashGeometry geom, List<Issue> issues) {
        for (int p = 0; p < FlashGeometry.BOOT_BLOCKS; p++) {
            byte[] page = image.readPagePublic(p);
            byte[] blockData = CfsPage.readBlockData(page, geom);
            for (int i = 0; i < blockData.length; i++) {
                if (blockData[i] != (byte) 0xFF) {
                    issues.add(new Issue(Severity.ERROR, "Boot block",
                        -1,
                        "Boot block " + p + " contains non-0xFF data at offset " + i
                            + " — CyOS expects empty boot blocks"));
                    break;
                }
            }
        }
    }

    /** 3. Block flags consistency: used blocks have 0x80, unused have 0x7F prefix. */
    private static void checkBlockFlags(CfsImage image, FlashGeometry geom, List<Issue> issues) {
        for (int b = 0; b < geom.fileBlockCount(); b++) {
            byte[] raw = image.readFileBlockRawPublic(b);
            int flags = raw[0] & 0xFF;
            boolean used = (flags & 0x80) != 0;

            if (!used) {
                // Unused block: byte 0 should be 0x7F (0xFF with bit 7 cleared)
                if (flags != 0x7F) {
                    issues.add(new Issue(Severity.WARNING, "Block flags",
                        b,
                        "Unused block has flags=0x" + String.format("%02X", flags)
                            + " (expected 0x7F) — may confuse CyOS block scanner"));
                }
            } else {
                // Used block: flags byte should be exactly 0x80
                if (flags != 0x80) {
                    issues.add(new Issue(Severity.WARNING, "Block flags",
                        b,
                        "Used block has flags=0x" + String.format("%02X", flags)
                            + " (expected 0x80) — extra flag bits set"));
                }
            }
        }
    }

    /** 4. File structure: part sequencing, orphans, duplicates, filenames. */
    private static void checkFileStructure(CfsImage image, FlashGeometry geom, List<Issue> issues) {
        // Collect all used blocks grouped by fileId
        Map<Integer, List<BlockInfo>> fileBlocks = new LinkedHashMap<>();

        for (int b = 0; b < geom.fileBlockCount(); b++) {
            CfsBlock block = CfsBlock.parse(image.readFileBlockRawPublic(b));
            if (!block.used()) continue;

            fileBlocks.computeIfAbsent(block.fileId(), k -> new ArrayList<>())
                .add(new BlockInfo(b, block));
        }

        // Check each file
        Set<String> seenNames = new HashSet<>();

        for (var entry : fileBlocks.entrySet()) {
            int fileId = entry.getKey();
            List<BlockInfo> blocks = entry.getValue();

            // Find part 0
            BlockInfo part0 = null;
            for (BlockInfo bi : blocks) {
                if (bi.block.partId() == 0) {
                    part0 = bi;
                    break;
                }
            }

            if (part0 == null) {
                // Orphaned continuation blocks — no header
                for (BlockInfo bi : blocks) {
                    issues.add(new Issue(Severity.ERROR, "Orphaned block",
                        bi.index,
                        "Continuation block for fileId=" + fileId
                            + " part=" + bi.block.partId()
                            + " has no header (part 0) — CyOS will ignore or reclaim"));
                }
                continue;
            }

            // Check part 0
            String filename = part0.block.filename();
            if (filename == null || filename.isEmpty()) {
                issues.add(new Issue(Severity.ERROR, "Filename",
                    part0.index,
                    "File header (fileId=" + fileId + ") has empty filename"));
            } else {
                // Check for non-ASCII / control chars in filename
                for (int i = 0; i < filename.length(); i++) {
                    char c = filename.charAt(i);
                    if (c < 0x20 || c > 0x7E) {
                        issues.add(new Issue(Severity.WARNING, "Filename",
                            part0.index,
                            "Filename \"" + filename + "\" contains non-printable char at pos " + i));
                        break;
                    }
                }
            }

            if (part0.block.typeMarker() != 0x20) {
                issues.add(new Issue(Severity.ERROR, "Type marker",
                    part0.index,
                    "Part 0 type marker=0x" + String.format("%02X", part0.block.typeMarker())
                        + " (expected 0x20) — CyOS won't recognize as file header"));
            }

            // Duplicate filename check
            if (filename != null && !filename.isEmpty()) {
                if (!seenNames.add(filename)) {
                    issues.add(new Issue(Severity.ERROR, "Duplicate",
                        part0.index,
                        "Duplicate filename \"" + filename
                            + "\" — CyOS will only see one copy, other wastes space"));
                }
            }

            // FileId should match block index of part 0
            if (fileId != part0.index) {
                issues.add(new Issue(Severity.WARNING, "File ID",
                    part0.index,
                    "FileId=" + fileId + " doesn't match part 0 block index=" + part0.index
                        + " — unusual but may work"));
            }

            // Check continuation block sequencing
            Set<Integer> partIds = new TreeSet<>();
            for (BlockInfo bi : blocks) {
                if (!partIds.add(bi.block.partId())) {
                    issues.add(new Issue(Severity.ERROR, "Duplicate part",
                        bi.index,
                        "Duplicate partId=" + bi.block.partId() + " for fileId=" + fileId
                            + " — CyOS will assemble corrupted data"));
                }
            }

            // Check for gaps in part sequence
            int maxPart = partIds.stream().mapToInt(Integer::intValue).max().orElse(0);
            for (int p = 0; p <= maxPart; p++) {
                if (!partIds.contains(p)) {
                    issues.add(new Issue(Severity.ERROR, "Missing part",
                        part0.index,
                        "File \"" + filename + "\" missing part " + p + " of " + maxPart
                            + " — CyOS will read truncated/corrupt data"));
                }
            }

            // Check continuation block type markers
            for (BlockInfo bi : blocks) {
                if (bi.block.partId() > 0 && bi.block.typeMarker() != 0x00) {
                    issues.add(new Issue(Severity.WARNING, "Type marker",
                        bi.index,
                        "Continuation block (part " + bi.block.partId()
                            + ") type marker=0x" + String.format("%02X", bi.block.typeMarker())
                            + " (expected 0x00)"));
                }
            }

            // Timestamp sanity for part 0
            long ts = part0.block.timestamp();
            // 0 is suspicious, 0xFFFFFFFF is uninitialized
            if (ts == 0) {
                issues.add(new Issue(Severity.WARNING, "Timestamp",
                    part0.index,
                    "File \"" + filename + "\" has zero timestamp"));
            } else if (ts == 0xFFFFFFFFL) {
                issues.add(new Issue(Severity.WARNING, "Timestamp",
                    part0.index,
                    "File \"" + filename + "\" has uninitialized timestamp (0xFFFFFFFF)"));
            }
        }
    }

    /** 5. Data size fields: must not exceed block capacity, must be consistent. */
    private static void checkDataSizes(CfsImage image, FlashGeometry geom, List<Issue> issues) {
        int maxFirstCap = Math.min(geom.firstBlockCapacity(), 255);
        int maxContCap = Math.min(geom.contBlockCapacity(), 255);

        for (int b = 0; b < geom.fileBlockCount(); b++) {
            CfsBlock block = CfsBlock.parse(image.readFileBlockRawPublic(b));
            if (!block.used()) continue;

            int dataSize = block.dataSize();

            if (block.partId() == 0) {
                if (dataSize > maxFirstCap) {
                    issues.add(new Issue(Severity.ERROR, "Data size",
                        b,
                        "Part 0 data size=" + dataSize + " exceeds first block capacity="
                            + maxFirstCap + " — CyOS will read past block boundary"));
                }
            } else {
                if (dataSize > maxContCap) {
                    issues.add(new Issue(Severity.ERROR, "Data size",
                        b,
                        "Continuation data size=" + dataSize + " exceeds block capacity="
                            + maxContCap + " — CyOS will read past block boundary"));
                }
            }
        }
    }

    // ---- Repair ----

    /** Result of a repair operation. */
    public record RepairResult(
        List<String> preserved,
        List<String> removed,
        List<String> actions
    ) {}

    /**
     * Repair the image by extracting all recoverable files, reformatting,
     * and re-adding them with correct checksums and block structure.
     * This is the same strategy CyOS flash repair uses.
     *
     * @return report of what was preserved, removed, and fixed
     */
    public static RepairResult repair(CfsImage image) {
        FlashGeometry geom = image.geometry();
        List<String> preserved = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> actions = new ArrayList<>();

        // 1. Scan all blocks and identify recoverable files
        Map<Integer, List<BlockInfo>> fileBlocks = new LinkedHashMap<>();
        for (int b = 0; b < geom.fileBlockCount(); b++) {
            CfsBlock block = CfsBlock.parse(image.readFileBlockRawPublic(b));
            if (!block.used()) continue;
            fileBlocks.computeIfAbsent(block.fileId(), k -> new ArrayList<>())
                .add(new BlockInfo(b, block));
        }

        // 2. Determine which files are recoverable
        record RecoverableFile(String name, byte[] data, long timestamp) {}
        List<RecoverableFile> toKeep = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();

        for (var entry : fileBlocks.entrySet()) {
            int fileId = entry.getKey();
            List<BlockInfo> blocks = entry.getValue();

            // Find part 0
            BlockInfo part0 = null;
            for (BlockInfo bi : blocks) {
                if (bi.block.partId() == 0) {
                    part0 = bi;
                    break;
                }
            }

            if (part0 == null) {
                // Orphaned continuation blocks
                removed.add("fileId=" + fileId + " (orphaned — no header block)");
                actions.add("Reclaimed " + blocks.size()
                    + " orphaned block(s) for fileId=" + fileId);
                continue;
            }

            String filename = part0.block.filename();
            if (filename == null || filename.isEmpty()) {
                removed.add("fileId=" + fileId + " (empty filename)");
                actions.add("Removed file with empty filename (fileId=" + fileId + ")");
                continue;
            }

            // Duplicate name check — keep first occurrence only
            if (!seenNames.add(filename)) {
                removed.add("\"" + filename + "\" (duplicate, kept earlier copy)");
                actions.add("Removed duplicate \"" + filename + "\"");
                continue;
            }

            // Check part sequence completeness
            Set<Integer> partIds = new TreeSet<>();
            for (BlockInfo bi : blocks) {
                partIds.add(bi.block.partId());
            }
            int maxPart = partIds.stream().mapToInt(Integer::intValue).max().orElse(0);
            boolean complete = true;
            for (int p = 0; p <= maxPart; p++) {
                if (!partIds.contains(p)) {
                    complete = false;
                    break;
                }
            }

            if (!complete) {
                removed.add("\"" + filename + "\" (missing parts in sequence)");
                actions.add("Removed \"" + filename
                    + "\" — incomplete part sequence, data unrecoverable");
                continue;
            }

            // Check for duplicate partIds
            Map<Integer, Integer> partIdCounts = new HashMap<>();
            for (BlockInfo bi : blocks) {
                partIdCounts.merge(bi.block.partId(), 1, Integer::sum);
            }
            boolean hasDupParts = partIdCounts.values().stream().anyMatch(c -> c > 1);
            if (hasDupParts) {
                removed.add("\"" + filename + "\" (duplicate part IDs)");
                actions.add("Removed \"" + filename
                    + "\" — duplicate block parts, data ambiguous");
                continue;
            }

            // Assemble the file data from parts in order
            int maxFirstCap = Math.min(geom.firstBlockCapacity(), 255);
            int maxContCap = Math.min(geom.contBlockCapacity(), 255);

            // Sort blocks by partId
            blocks.sort(Comparator.comparingInt(bi -> bi.block.partId()));

            int totalSize = 0;
            boolean sizeOk = true;
            for (BlockInfo bi : blocks) {
                int dataSize = bi.block.dataSize();
                int cap = bi.block.partId() == 0 ? maxFirstCap : maxContCap;
                if (dataSize > cap) {
                    // Clamp to capacity rather than discarding
                    dataSize = cap;
                }
                totalSize += dataSize;
            }

            byte[] assembled = new byte[totalSize];
            int offset = 0;
            for (BlockInfo bi : blocks) {
                byte[] fd = bi.block.fileData();
                int cap = bi.block.partId() == 0 ? maxFirstCap : maxContCap;
                int len = Math.min(fd.length, cap);
                if (len > 0) {
                    System.arraycopy(fd, 0, assembled, offset, len);
                }
                offset += len;
            }
            // Trim if we accumulated less than totalSize
            if (offset < assembled.length) {
                assembled = java.util.Arrays.copyOf(assembled, offset);
            }

            long timestamp = part0.block.timestamp();
            // Fix zero/uninitialized timestamps
            if (timestamp == 0 || timestamp == 0xFFFFFFFFL) {
                timestamp = com.github.daberkow.cybiko.manager.model.CfsFile.nowTimestamp();
                actions.add("Fixed invalid timestamp for \"" + filename + "\"");
            }

            toKeep.add(new RecoverableFile(filename, assembled, timestamp));
            preserved.add("\"" + filename + "\" (" + assembled.length + " bytes)");
        }

        // 3. Reformat the image (wipes everything clean)
        image.reformatInPlace();

        // 4. Re-add all recoverable files
        for (RecoverableFile rf : toKeep) {
            if (!image.addFile(rf.name, rf.data, rf.timestamp)) {
                // Shouldn't happen unless we overflowed capacity
                preserved.remove("\"" + rf.name + "\" (" + rf.data.length + " bytes)");
                removed.add("\"" + rf.name + "\" (no space after rebuild)");
                actions.add("Could not re-add \"" + rf.name + "\" — no space remaining");
            }
        }

        // 5. Summary actions
        actions.add(0, "Reformatted image — all checksums recalculated");
        actions.add(1, "Boot blocks reset to clean state");
        actions.add(2, "All block flags normalized");

        return new RepairResult(
            List.copyOf(preserved),
            List.copyOf(removed),
            List.copyOf(actions)
        );
    }

    private record BlockInfo(int index, CfsBlock block) {}
}
