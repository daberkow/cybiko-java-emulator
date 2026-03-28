# Serial PTY Bridge Implementation Plan — DONE

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Connect V1/V2 emulator SCI2 to a Linux PTY for bidirectional serial I/O with external tools (minicom, CyberLoad via Wine).

**Architecture:** New `SerialPort` interface with `PtySerialPort` implementation. AddressBus gets serial-aware SCI2 register handling for V1/V2 (guarded by `serialPort != null && radioSciChannel != 2`). New `tickSerial()` method delivers RX bytes with RXI2 interrupts. CLI flag `--serial auto|<path>` wires it up.

**Tech Stack:** Java 21, JUnit 5, Linux PTY via `socat` subprocess or direct file I/O.

---

### Task 1: SerialPort Interface

**Files:**
- Create: `emulator/src/main/java/com/github/daberkow/SerialPort.java`

**Step 1: Create the interface**

```java
package com.github.daberkow;

import java.io.IOException;

/**
 * Abstraction for serial port I/O. Implemented by PtySerialPort (Phase 1)
 * and potentially jSerialComm wrapper (Phase 4) for real hardware.
 */
public interface SerialPort {
    /** Send one byte to the external tool. */
    void write(int b) throws IOException;

    /** Check if received data is available. */
    boolean hasData();

    /** Read one byte from the receive queue. Returns -1 if empty. Non-blocking. */
    int read();

    /** Close the port and release resources. */
    void close();

    /** Return the path external tools should connect to (e.g. /dev/pts/X). */
    String getPath();
}
```

**Step 2: Commit**

```bash
git add emulator/src/main/java/com/github/daberkow/SerialPort.java
git commit -m "feat: add SerialPort interface for PTY bridge"
```

---

### Task 2: PtySerialPort Implementation

**Files:**
- Create: `emulator/src/main/java/com/github/daberkow/PtySerialPort.java`
- Create: `emulator/src/test/java/com/github/daberkow/PtySerialPortTest.java`

**Step 1: Write failing tests**

```java
package com.github.daberkow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.LINUX)
class PtySerialPortTest {

    @Test
    void autoModeCreatesPtyPair() throws Exception {
        // Skip if socat not installed
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
            // Write to the port (goes to slave side)
            // Read from slave side and write back
            // This tests the internal wiring — full loopback needs the slave end
            // Just verify write doesn't throw and hasData/read work
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

        // Parse PTY paths from socat output
        java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(socat.getInputStream()));
        String path1 = null, path2 = null;
        long deadline = System.currentTimeMillis() + 3000;
        while ((path1 == null || path2 == null) && System.currentTimeMillis() < deadline) {
            String line = reader.readLine();
            if (line != null && line.contains("PTY is")) {
                String path = line.substring(line.indexOf("/dev/"));
                if (path1 == null) path1 = path;
                else path2 = path;
            }
        }
        assertNotNull(path1, "socat didn't create first PTY");
        assertNotNull(path2, "socat didn't create second PTY");

        try {
            PtySerialPort port = PtySerialPort.createExplicit(path1);
            try {
                assertEquals(path1, port.getPath());
                // Write through our port, read from the other end
                port.write(0x42); // 'B'
                // Read from path2 to verify
                java.io.FileInputStream fis = new java.io.FileInputStream(path2);
                // Give it a moment
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
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew :emulator:test --tests "com.github.daberkow.PtySerialPortTest" -i`
Expected: FAIL — PtySerialPort class doesn't exist

**Step 3: Implement PtySerialPort**

```java
package com.github.daberkow;

import java.io.*;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * PTY-based serial port for Linux. Connects SCI2 to a pseudo-terminal
 * so external tools (minicom, CyberLoad via Wine) can communicate.
 */
public class PtySerialPort implements SerialPort {
    private final FileInputStream inputStream;
    private final FileOutputStream outputStream;
    private final String slavePath;
    private final Process socatProcess; // null in explicit mode
    private final LinkedBlockingQueue<Integer> rxQueue = new LinkedBlockingQueue<>(256);
    private final Thread readerThread;
    private volatile boolean closed = false;

    private PtySerialPort(FileInputStream in, FileOutputStream out,
                          String slavePath, Process socatProcess) {
        this.inputStream = in;
        this.outputStream = out;
        this.slavePath = slavePath;
        this.socatProcess = socatProcess;

        this.readerThread = new Thread(this::readLoop, "serial-pty-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
    }

    /**
     * Auto mode: spawn socat to create a PTY pair.
     * We open the first PTY (master side). The second is for external tools.
     */
    public static PtySerialPort createAuto() throws IOException {
        ProcessBuilder pb = new ProcessBuilder("socat", "-d", "-d",
                "pty,raw,echo=0", "pty,raw,echo=0");
        pb.redirectErrorStream(true);
        Process socat = pb.start();

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(socat.getInputStream()));

        String masterPath = null, slavePath = null;
        long deadline = System.currentTimeMillis() + 5000;
        while ((masterPath == null || slavePath == null)
                && System.currentTimeMillis() < deadline) {
            String line = reader.readLine();
            if (line == null) break;
            if (line.contains("PTY is")) {
                String path = line.substring(line.indexOf("/dev/"));
                if (masterPath == null) masterPath = path.trim();
                else slavePath = path.trim();
            }
        }

        if (masterPath == null || slavePath == null) {
            socat.destroyForcibly();
            throw new IOException("Failed to create PTY pair via socat. Is socat installed?");
        }

        FileInputStream in = new FileInputStream(masterPath);
        FileOutputStream out = new FileOutputStream(masterPath);
        return new PtySerialPort(in, out, slavePath, socat);
    }

    /**
     * Explicit mode: open a user-provided PTY/device path directly.
     */
    public static PtySerialPort createExplicit(String path) throws IOException {
        FileInputStream in = new FileInputStream(path);
        FileOutputStream out = new FileOutputStream(path);
        return new PtySerialPort(in, out, path, null);
    }

    @Override
    public void write(int b) throws IOException {
        outputStream.write(b);
        outputStream.flush();
    }

    @Override
    public boolean hasData() {
        return !rxQueue.isEmpty();
    }

    @Override
    public int read() {
        Integer b = rxQueue.poll();
        return (b != null) ? b : -1;
    }

    @Override
    public void close() {
        closed = true;
        readerThread.interrupt();
        try { inputStream.close(); } catch (IOException ignored) {}
        try { outputStream.close(); } catch (IOException ignored) {}
        if (socatProcess != null) {
            socatProcess.destroyForcibly();
        }
    }

    @Override
    public String getPath() {
        return slavePath;
    }

    private void readLoop() {
        byte[] buf = new byte[256];
        try {
            while (!closed) {
                int n = inputStream.read(buf);
                if (n <= 0) break;
                for (int i = 0; i < n; i++) {
                    if (!rxQueue.offer(buf[i] & 0xFF)) {
                        rxQueue.poll(); // drop oldest on overflow
                        rxQueue.offer(buf[i] & 0xFF);
                    }
                }
            }
        } catch (IOException e) {
            if (!closed) {
                Log.log(Log.Category.SERIAL, "[SERIAL] Read error: %s", e.getMessage());
            }
        }
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew :emulator:test --tests "com.github.daberkow.PtySerialPortTest" -i`
Expected: PASS (or skip on non-Linux / no socat)

**Step 5: Commit**

```bash
git add emulator/src/main/java/com/github/daberkow/PtySerialPort.java \
        emulator/src/test/java/com/github/daberkow/PtySerialPortTest.java
git commit -m "feat: add PtySerialPort with auto and explicit PTY modes"
```

---

### Task 3: Add SERIAL Log Category

**Files:**
- Modify: `emulator/src/main/java/com/github/daberkow/Log.java:16-46`

**Step 1: Add SERIAL to the Category enum**

Add `SERIAL` after `SPEAKER` in the enum at line 25:
```java
        SPEAKER,  // Audio initialization
        SERIAL;   // Serial port PTY bridge I/O
```

Also add it to the `parseCategories` method's `"all"` case (find the line that returns `EnumSet.allOf`).

**Step 2: Run existing tests to make sure nothing breaks**

Run: `./gradlew :emulator:test -i`
Expected: All existing tests PASS

**Step 3: Commit**

```bash
git add emulator/src/main/java/com/github/daberkow/Log.java
git commit -m "feat: add SERIAL log category for PTY bridge I/O"
```

---

### Task 4: AddressBus Serial Wiring

**Files:**
- Modify: `emulator/src/main/java/com/github/daberkow/AddressBus.java`

This is the core task. We need:
1. New fields: `serialPort`, `sci2Rdr`, `sci2Rdrf`
2. `setSerialPort()` method
3. Serial-aware SCI2 TDR write (line ~1114)
4. Serial-aware SCI2 SSR read (line ~949)
5. Serial-aware SCI2 RDR read (line ~941)
6. New `tickSerial()` method
7. Serial TX/RX hex logging

**Step 1: Add new fields and setter**

After the existing `sci2Scr` field declarations (~line 107-110), add:

```java
    private SerialPort serialPort;       // PTY serial bridge (V1/V2 only, null if not enabled)
    private int sci2Rdr = 0;             // SCI2 receive data register (from serial port)
    private boolean sci2Rdrf = false;    // SCI2 receive data register full (from serial port)
    private final java.util.List<Integer> serialTxLog = new java.util.ArrayList<>();
    private final java.util.List<Integer> serialRxLog = new java.util.ArrayList<>();
```

Add setter method near `setRadio()` (~line 200):

```java
    public void setSerialPort(SerialPort port) {
        this.serialPort = port;
    }
```

**Step 2: Modify SCI2 TDR write handler (line ~1114)**

After the existing `sciOutput[channel]` append and `sci2TdrLog` add (lines 1114-1122),
add serial port write. Also add TDRE modeling for serial mode:

```java
            if (reg == 3) { // TDR - Transmit Data Register
                char c = (char)(value & 0x7F);
                sciOutput[channel].append(c >= 0x20 ? c : (c == '\n' ? '\n' : '.'));
                if (channel == 0) {
                    sci0TdrLog.add(value & 0xFF);
                }
                if (channel == 2) {
                    sci2TdrLog.add(value & 0xFF);
                }
                // Serial port bridge (V1/V2): forward SCI2 TDR to PTY
                if (channel == 2 && serialPort != null && radioSciChannel != 2) {
                    try {
                        serialPort.write(value & 0xFF);
                        serialTxLog.add(value & 0xFF);
                    } catch (java.io.IOException e) {
                        Log.log(Log.Category.SERIAL, "[SERIAL] TX error: %s", e.getMessage());
                    }
                    // Model TDRE for serial (same timing as radio)
                    sci2Tdre = false;
                    sci2TxiDelay = SCI2_TXI_DELAY;
                }
                if (channel == radioSciChannel && radio != null) {
                    // ... existing radio handling unchanged ...
```

**Step 3: Modify SCI2 SSR read handler (line ~949)**

Replace the existing SSR handler to include serial RDRF:

```java
        if (address == 0xFFFF8C) { // SCI2 SSR
            int ssr = SSR_TEND;
            if (sci2Tdre) ssr |= SSR_TDRE;
            // RDRF: from radio (XT) or serial port (V1/V2)
            if (radio != null && radioSciChannel == 2 && sci0Rdrf) ssr |= SSR_RDRF;
            if (serialPort != null && radioSciChannel != 2 && sci2Rdrf) ssr |= SSR_RDRF;
            if (sci2RegLog.size() < 500) {
                int pc = (cpu != null) ? cpu.getLastStartPC() : -1;
                if (pc != sci2SsrLastLogPc) {
                    sci2RegLog.add(String.format("R SSR=0x%02X PC=0x%06X", ssr, pc));
                    sci2SsrLastLogPc = pc;
                }
            }
            return ssr;
        }
```

**Step 4: Modify SCI2 RDR read handler (line ~941)**

Add serial port RDR read before the existing radio handler:

```java
        // SCI2 RDR — serial port (V1/V2)
        if (address == 0xFFFF8D && serialPort != null && radioSciChannel != 2 && sci2Rdrf) {
            if (sci2RegLog.size() < 500) {
                int pc = (cpu != null) ? cpu.getLastStartPC() : -1;
                sci2RegLog.add(String.format("R RDR=0x%02X PC=0x%06X", sci2Rdr, pc));
            }
            sci2Rdrf = false;
            return sci2Rdr;
        }
        // SCI2 RDR — radio (XT)
        if (address == 0xFFFF8D && radio != null && radioSciChannel == 2) {
            // ... existing radio RDR handling ...
```

**Step 5: Add tickSerial() method**

Add after `tickSci2()` (line ~616):

```java
    /**
     * Tick the serial port bridge. Checks for incoming bytes from the PTY
     * and delivers them to SCI2 RDR with RXI2 interrupt.
     * Only active on V1/V2 (radioSciChannel != 2).
     */
    public void tickSerial() {
        if (serialPort == null || radioSciChannel == 2) return;

        // TXI2 countdown (same logic as tickSci2 for radio)
        if (sci2TxiDelay > 0) {
            sci2TxiDelay--;
            if (sci2TxiDelay == 0) {
                sci2Tdre = true;
                if ((sci2Scr & 0x80) != 0 && cpu != null) {
                    cpu.requestInterrupt(90); // TXI2
                }
            }
        }

        // RX: deliver bytes from serial port to SCI2 RDR
        if (!sci2Rdrf && serialPort.hasData()) {
            sci2Rdr = serialPort.read();
            sci2Rdrf = true;
            serialRxLog.add(sci2Rdr);
            if ((sci2Scr & 0x40) != 0 && cpu != null) {
                cpu.requestInterrupt(89); // RXI2
            }
        }
    }

    public java.util.List<Integer> getSerialTxLog() { return serialTxLog; }
    public java.util.List<Integer> getSerialRxLog() { return serialRxLog; }
```

**Step 6: Verify build compiles**

Run: `./gradlew :emulator:compileJava`
Expected: BUILD SUCCESSFUL

**Step 7: Commit**

```bash
git add emulator/src/main/java/com/github/daberkow/AddressBus.java
git commit -m "feat: wire SCI2 to serial port for V1/V2 PTY bridge"
```

---

### Task 5: CybikoEmulator CLI and Wiring

**Files:**
- Modify: `emulator/src/main/java/com/github/daberkow/CybikoEmulator.java`

**Step 1: Add --serial CLI parsing**

In the argument parsing switch (~line 538), add:

```java
                case "--serial" -> {
                    if (i + 1 < args.length) serialArg = args[++i];
                }
```

Declare the variable near the other arg vars:
```java
            String serialArg = null;
```

**Step 2: Add serial port creation and wiring**

After the emulator and AddressBus are created but before `emu.start()`, add serial
setup. This goes near the radio setup block (~line 670):

```java
            // Serial port bridge (V1/V2 only)
            if (serialArg != null) {
                if (config.type == MachineConfig.MachineType.XT) {
                    System.err.println("ERROR: --serial not supported on XT (SCI2 used for radio)");
                    System.exit(1);
                }
                try {
                    PtySerialPort serialPort;
                    if ("auto".equalsIgnoreCase(serialArg)) {
                        serialPort = PtySerialPort.createAuto();
                    } else {
                        serialPort = PtySerialPort.createExplicit(serialArg);
                    }
                    emu.getBus().setSerialPort(serialPort);
                    Runtime.getRuntime().addShutdownHook(new Thread(serialPort::close));

                    Log.log(Log.Category.SERIAL, "[SERIAL] PTY bridge active");
                    Log.log(Log.Category.SERIAL, "[SERIAL] Slave PTY: %s", serialPort.getPath());
                    Log.log(Log.Category.SERIAL, "[SERIAL] To connect Wine: ln -s %s ~/.wine/dosdevices/com1",
                            serialPort.getPath());
                    Log.log(Log.Category.SERIAL, "[SERIAL] To connect minicom: minicom -D %s -b 57600",
                            serialPort.getPath());
                    // Also print to stderr so it's visible even without --logging serial
                    System.err.println("[SERIAL] Slave PTY: " + serialPort.getPath());
                } catch (java.io.IOException e) {
                    System.err.println("ERROR: Failed to create serial port: " + e.getMessage());
                    System.exit(1);
                }
            }
```

Note: the above block needs to be added in BOTH the GUI and headless code paths (there
are two `emu.start()` call sites in the main method). Search for both and add before each.
Alternatively, add it once before the GUI/headless branch point.

**Step 3: Add tickSerial() to the main loop**

Find where `bus.tickSci2()` is called (~line 341) and add `tickSerial()` right after:

```java
                bus.tickSci2();
                bus.tickSerial();
```

**Step 4: Add serial hex dump logging**

Find where SCI2 TDR/reg logs are printed (~line 428-438) and add serial logging:

```java
                // Serial port hex dump
                if (!bus.getSerialTxLog().isEmpty() && Log.isEnabled(Log.Category.SERIAL)) {
                    StringBuilder hex = new StringBuilder("[SERIAL-TX] ");
                    for (int b : bus.getSerialTxLog()) hex.append(String.format("%02X ", b));
                    Log.log(Log.Category.SERIAL, hex.toString());
                    bus.getSerialTxLog().clear();
                }
                if (!bus.getSerialRxLog().isEmpty() && Log.isEnabled(Log.Category.SERIAL)) {
                    StringBuilder hex = new StringBuilder("[SERIAL-RX] ");
                    for (int b : bus.getSerialRxLog()) hex.append(String.format("%02X ", b));
                    Log.log(Log.Category.SERIAL, hex.toString());
                    bus.getSerialRxLog().clear();
                }
```

**Step 5: Add getBus() accessor if not present**

Check if `CybikoEmulator` has `getBus()`. If not, add:
```java
    public AddressBus getBus() { return bus; }
```

**Step 6: Verify build compiles**

Run: `./gradlew :emulator:compileJava`
Expected: BUILD SUCCESSFUL

**Step 7: Commit**

```bash
git add emulator/src/main/java/com/github/daberkow/CybikoEmulator.java
git commit -m "feat: add --serial CLI flag for PTY bridge on V1/V2"
```

---

### Task 6: Integration Test

**Files:**
- Create: `emulator/src/test/java/com/github/daberkow/SerialBridgeTest.java`

**Step 1: Write integration test**

This tests the full path: write to SCI2 TDR → serial port TX, and serial port RX → SCI2 RDR.

```java
package com.github.daberkow;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SerialBridgeTest {

    /** Mock serial port for testing without PTY. */
    static class MockSerialPort implements SerialPort {
        final java.util.List<Integer> written = new java.util.ArrayList<>();
        final java.util.Queue<Integer> rxQueue = new java.util.LinkedList<>();

        @Override public void write(int b) { written.add(b); }
        @Override public boolean hasData() { return !rxQueue.isEmpty(); }
        @Override public int read() { Integer b = rxQueue.poll(); return b != null ? b : -1; }
        @Override public void close() {}
        @Override public String getPath() { return "/dev/null"; }

        void queueRx(int... bytes) {
            for (int b : bytes) rxQueue.add(b);
        }
    }

    @Test
    void sci2TdrWriteForwardsToSerialPort() {
        MachineConfig config = MachineConfig.V1;
        Memory rom = new Memory(0x8000);
        Memory flash = new Memory(config.flashRomSize);
        Memory extRam = new Memory(config.extRamSize);
        AddressBus bus = new AddressBus(config, rom, flash, extRam);
        MockSerialPort serial = new MockSerialPort();
        bus.setSerialPort(serial);

        // Write 'H' to SCI2 TDR (0xFFFF8B)
        bus.write8(0xFFFF8B, 0x48);

        assertEquals(1, serial.written.size());
        assertEquals(0x48, serial.written.get(0));
    }

    @Test
    void sci2SsrReflectsSerialRdrf() {
        MachineConfig config = MachineConfig.V1;
        Memory rom = new Memory(0x8000);
        Memory flash = new Memory(config.flashRomSize);
        Memory extRam = new Memory(config.extRamSize);
        AddressBus bus = new AddressBus(config, rom, flash, extRam);
        MockSerialPort serial = new MockSerialPort();
        bus.setSerialPort(serial);

        // SSR should not have RDRF when no data
        int ssr = bus.read8(0xFFFF8C);
        assertEquals(0, ssr & 0x40, "RDRF should be clear with no data");

        // Queue a byte and tick
        serial.queueRx(0x41);
        bus.tickSerial();

        // SSR should now have RDRF
        ssr = bus.read8(0xFFFF8C);
        assertNotEquals(0, ssr & 0x40, "RDRF should be set after tickSerial with data");
    }

    @Test
    void sci2RdrReturnsSerialByte() {
        MachineConfig config = MachineConfig.V1;
        Memory rom = new Memory(0x8000);
        Memory flash = new Memory(config.flashRomSize);
        Memory extRam = new Memory(config.extRamSize);
        AddressBus bus = new AddressBus(config, rom, flash, extRam);
        MockSerialPort serial = new MockSerialPort();
        bus.setSerialPort(serial);

        serial.queueRx(0x55);
        bus.tickSerial();

        // Read RDR should return the queued byte
        int rdr = bus.read8(0xFFFF8D);
        assertEquals(0x55, rdr);

        // RDRF should be cleared after read
        int ssr = bus.read8(0xFFFF8C);
        assertEquals(0, ssr & 0x40, "RDRF should clear after RDR read");
    }

    @Test
    void serialNotActiveOnXt() {
        MachineConfig config = MachineConfig.XT;
        Memory rom = new Memory(0x8000);
        Memory flash = new Memory(config.flashRomSize);
        Memory extRam = new Memory(config.extRamSize);
        AddressBus bus = new AddressBus(config, rom, flash, extRam);
        // Set radio so radioSciChannel = 2 (XT)
        RadioCoProcessor radio = new RadioCoProcessor();
        bus.setRadio(radio);

        MockSerialPort serial = new MockSerialPort();
        bus.setSerialPort(serial);

        // Write to SCI2 TDR — should NOT go to serial (XT uses SCI2 for radio)
        bus.write8(0xFFFF8B, 0x48);
        assertTrue(serial.written.isEmpty(), "Serial should be inactive on XT");
    }

    @Test
    void tdreRestoredAfterDelay() {
        MachineConfig config = MachineConfig.V1;
        Memory rom = new Memory(0x8000);
        Memory flash = new Memory(config.flashRomSize);
        Memory extRam = new Memory(config.extRamSize);
        AddressBus bus = new AddressBus(config, rom, flash, extRam);
        MockSerialPort serial = new MockSerialPort();
        bus.setSerialPort(serial);

        // Write to TDR — TDRE should clear
        bus.write8(0xFFFF8B, 0x41);
        int ssr = bus.read8(0xFFFF8C);
        assertEquals(0, ssr & 0x80, "TDRE should be clear right after TDR write");

        // Tick enough times for TDRE to restore (SCI2_TXI_DELAY = 32)
        for (int i = 0; i < 33; i++) bus.tickSerial();

        ssr = bus.read8(0xFFFF8C);
        assertNotEquals(0, ssr & 0x80, "TDRE should be set after delay ticks");
    }
}
```

**Step 2: Run tests**

Run: `./gradlew :emulator:test --tests "com.github.daberkow.SerialBridgeTest" -i`
Expected: PASS

Note: Some tests may need adjustment depending on exact AddressBus constructor signature
and MachineConfig access patterns. The implementing engineer should check the existing
test files (e.g. `H8SCpuTest.java`) for how AddressBus is instantiated in tests and
adapt accordingly.

**Step 3: Commit**

```bash
git add emulator/src/test/java/com/github/daberkow/SerialBridgeTest.java
git commit -m "test: add serial bridge integration tests"
```

---

### Task 7: Manual Smoke Test

**Step 1: Build and run with minicom**

```bash
./gradlew :emulator:run --args="--machine v1 src/main/resources/cybikov1/cyrom112.bin src/main/resources/cybikov1/flash_v1246.bin --serial auto --logging boot,serial"
```

Expected output includes:
```
[SERIAL] Slave PTY: /dev/pts/X
[SERIAL] To connect minicom: minicom -D /dev/pts/X -b 57600
```

**Step 2: Connect minicom in another terminal**

```bash
minicom -D /dev/pts/X -b 57600
```

Expected: Boot ROM messages visible in minicom:
```
Starting CyOS boot loader v1.1.2
Detecting DRAM interface settings...
```

**Step 3: Type in minicom**

Type characters — they should appear in [SERIAL-RX] log output from the emulator.

**Step 4: Document results and commit any fixes**

```bash
git commit -m "chore: serial PTY bridge Phase 1 complete"
```
