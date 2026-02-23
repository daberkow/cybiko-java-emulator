package com.github.daberkow.cybiko.manager.cfs;

import com.github.daberkow.cybiko.manager.model.CfsFile;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CfsReaderWriterTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @EnumSource(FlashGeometry.class)
    void writeAndReadRoundtrip(FlashGeometry geom) throws IOException {
        CfsImage original = new CfsImage(geom);
        original.addFile("round.txt", "trip data here".getBytes());

        Path file = tempDir.resolve("test_" + geom.name() + ".bin");
        CfsWriter.write(original, file);

        CfsImage loaded = CfsReader.read(file, geom);
        List<CfsFile> files = loaded.listFiles();
        assertEquals(1, files.size());
        assertEquals("round.txt", files.get(0).name());
        assertArrayEquals("trip data here".getBytes(), files.get(0).data());
    }

    @ParameterizedTest
    @EnumSource(FlashGeometry.class)
    void autoDetectGeometryOnRead(FlashGeometry geom) throws IOException {
        CfsImage original = new CfsImage(geom);
        original.addFile("detect.txt", "auto".getBytes());

        Path file = tempDir.resolve("detect_" + geom.name() + ".bin");
        CfsWriter.write(original, file);

        // Read without specifying geometry
        CfsImage loaded = CfsReader.read(file);
        assertEquals(geom, loaded.geometry());

        List<CfsFile> files = loaded.listFiles();
        assertEquals(1, files.size());
        assertEquals("detect.txt", files.get(0).name());
    }

    @ParameterizedTest
    @EnumSource(FlashGeometry.class)
    void emptyImageRoundtrip(FlashGeometry geom) throws IOException {
        CfsImage original = new CfsImage(geom);

        Path file = tempDir.resolve("empty_" + geom.name() + ".bin");
        CfsWriter.write(original, file);

        CfsImage loaded = CfsReader.read(file, geom);
        assertTrue(loaded.listFiles().isEmpty());
        assertTrue(loaded.verify());
    }

    @ParameterizedTest
    @EnumSource(FlashGeometry.class)
    void multiFileRoundtrip(FlashGeometry geom) throws IOException {
        CfsImage original = new CfsImage(geom);
        original.addFile("file1.txt", "data one".getBytes());
        original.addFile("file2.app", "data two".getBytes());
        original.addFile("file3.dat", new byte[500]);

        Path file = tempDir.resolve("multi_" + geom.name() + ".bin");
        CfsWriter.write(original, file);

        CfsImage loaded = CfsReader.read(file, geom);
        assertEquals(3, loaded.listFiles().size());
        assertNotNull(loaded.readFile("file1.txt"));
        assertNotNull(loaded.readFile("file2.app"));
        assertNotNull(loaded.readFile("file3.dat"));
    }
}
