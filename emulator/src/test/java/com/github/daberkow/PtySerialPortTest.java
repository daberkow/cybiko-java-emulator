package com.github.daberkow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.LINUX)
class PtySerialPortTest {

    @Test
    void autoModeCreatesPtyPair() throws Exception {
        try {
            Process p = new ProcessBuilder("which", "socat").start();
            if (p.waitFor() != 0) return;
        } catch (Exception e) { return; }

        PtySerialPort port = PtySerialPort.createAuto();
        try {
            assertNotNull(port.getPath());
            assertTrue(port.getPath().startsWith("/dev/pts/"));
            assertFalse(port.hasData());
            assertEquals(-1, port.read());
        } finally {
            port.close();
        }
    }

    @Test
    void writeAndReadThroughPtyPair() throws Exception {
        try {
            Process p = new ProcessBuilder("which", "socat").start();
            if (p.waitFor() != 0) return;
        } catch (Exception e) { return; }

        PtySerialPort port = PtySerialPort.createAuto();
        try {
            port.write(0x41); // 'A'
            assertFalse(port.hasData()); // No loopback — data went to slave
        } finally {
            port.close();
        }
    }

    @Test
    void explicitModeOpensPath() throws Exception {
        try {
            Process p = new ProcessBuilder("which", "socat").start();
            if (p.waitFor() != 0) return;
        } catch (Exception e) { return; }

        // Create a socat pair manually, use one end for explicit mode
        ProcessBuilder pb = new ProcessBuilder("socat", "-d", "-d",
                "pty,raw,echo=0", "pty,raw,echo=0");
        pb.redirectErrorStream(true);
        Process socat = pb.start();

        java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(socat.getInputStream()));
        String path1 = null, path2 = null;
        long deadline = System.currentTimeMillis() + 3000;
        while ((path1 == null || path2 == null) && System.currentTimeMillis() < deadline) {
            String line = reader.readLine();
            if (line != null && line.contains("PTY is")) {
                String path = line.substring(line.indexOf("/dev/"));
                if (path1 == null) path1 = path.trim();
                else path2 = path.trim();
            }
        }
        assertNotNull(path1, "socat didn't create first PTY");
        assertNotNull(path2, "socat didn't create second PTY");

        try {
            PtySerialPort port = PtySerialPort.createExplicit(path1);
            try {
                assertEquals(path1, port.getPath());
                port.write(0x42); // 'B'
                java.io.FileInputStream fis = new java.io.FileInputStream(path2);
                Thread.sleep(50);
                assertTrue(fis.available() > 0);
                assertEquals(0x42, fis.read());
                fis.close();
            } finally {
                port.close();
            }
        } finally {
            socat.destroyForcibly();
        }
    }
}
