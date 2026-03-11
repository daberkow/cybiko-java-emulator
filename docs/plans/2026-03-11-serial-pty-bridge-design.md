# Phase 1: Virtual Serial Port (PTY Bridge) — Design

## Goal

Connect V1/V2 emulator SCI2 to a Linux PTY for bidirectional serial communication
with external tools (minicom, CyberLoad via Wine). Enables CyberLoad protocol capture
without real hardware.

## CLI Interface

```bash
# Auto mode: emulator creates PTY pair via socat, prints paths
./gradlew run --args="--machine v1 cyrom112.bin flash_v1246.bin --serial auto"

# Explicit mode: user provides a PTY/device path
./gradlew run --args="--machine v1 cyrom112.bin flash_v1246.bin --serial /dev/pts/5"
```

Blocked on XT with error message (SCI2 used for radio on XT).

## Architecture

```
CyOS SCI2 TDR write (0xFFFF8B)
    ↓
AddressBus → SerialPort.write(byte)
    ↓
FileOutputStream → PTY master fd
    ↓
External tool reads from PTY slave

External tool writes to PTY slave
    ↓
PTY master fd → FileInputStream (background thread)
    ↓
SerialPort.rxQueue (LinkedBlockingQueue)
    ↓
AddressBus tickSerial() → sets sci2Rdr + sci2Rdrf → fires RXI2
    ↓
CyOS reads SCI2 RDR (0xFFFF8D)
```

## New Code

### SerialPort interface

```java
public interface SerialPort {
    void write(int b) throws IOException;
    boolean hasData();
    int read();
    void close();
    String getPath();  // slave PTY path for user instructions
}
```

Designed so jSerialComm can implement it later (Phase 4) for real hardware.

### PtySerialPort implements SerialPort

**Auto mode** (`--serial auto`):
1. Spawn `socat -d -d pty,raw,echo=0 pty,raw,echo=0`
2. Parse two PTY paths from socat's stderr (format: `N PTY is /dev/pts/X`)
3. Open first path as master (FileInputStream + FileOutputStream)
4. Second path is the slave — print it to console with Wine instructions

**Explicit mode** (`--serial /dev/pts/X`):
1. Open given path directly as FileInputStream + FileOutputStream
2. Path is both master and slave (user manages the other end)

**Background reader thread** (daemon):
- Reads from PTY FileInputStream in a loop
- Queues bytes to `LinkedBlockingQueue<Integer>` (capacity 256)
- Overflow: drop oldest byte (offer semantics)

**Console output on startup:**
```
[SERIAL] PTY bridge active
[SERIAL] Slave PTY: /dev/pts/5
[SERIAL] To connect Wine: ln -s /dev/pts/5 ~/.wine/dosdevices/com1
[SERIAL] To connect minicom: minicom -D /dev/pts/5 -b 57600
```

## AddressBus Changes

### New fields
- `SerialPort serialPort` — set via `setSerialPort()`
- `int sci2Rdr` — separate from `sci0Rdr` (fixes shared variable bug)
- `boolean sci2Rdrf` — separate from `sci0Rdrf`

### SCI2 register handling (when serialPort != null && radioSciChannel != 2)

**TDR write (0xFFFF8B):**
- Existing: append to `sciOutput[2]`, log to `sci2TdrLog`
- New: also call `serialPort.write(value & 0xFF)`
- Set `sci2Tdre = false`, restore after SCI2_TXI_DELAY (32 cycles)
- Fire TXI2 (vector 90) when TDRE restores and TIE enabled

**SSR read (0xFFFF8C):**
- TEND (0x04): always set
- TDRE (0x80): from `sci2Tdre`
- RDRF (0x40): from `sci2Rdrf`

**RDR read (0xFFFF8D):**
- Return `sci2Rdr`
- Clear `sci2Rdrf`

**SCR write (0xFFFF8A):**
- Cache to `sci2Scr` (existing behavior)

### New tickSerial() method

Called from main emulator loop (alongside tickSci2, same frequency):
```
if serialPort != null && serialPort.hasData() && !sci2Rdrf:
    sci2Rdr = serialPort.read()
    sci2Rdrf = true
    if (sci2Scr & 0x40) != 0:  // RIE enabled
        cpu.requestInterrupt(89)  // RXI2
```

### Guard

All serial-specific SCI2 handling gated on:
```java
serialPort != null && radioSciChannel != 2
```
This ensures V1/V2 get serial, XT keeps radio on SCI2.

## CybikoEmulator Changes

### CLI parsing
- `--serial auto` → create PtySerialPort in auto mode
- `--serial <path>` → create PtySerialPort in explicit mode
- If machine is XT, print error and exit

### Wiring
- Call `bus.setSerialPort(ptySerialPort)` after AddressBus creation
- Add `bus.tickSerial()` call in main loop (every N cycles, same as tickSci2)

### Logging
- New `Log.Category.SERIAL` for serial-specific messages
- Hex dump of TX/RX bytes periodically (like existing SCI2-TX logging)

### Shutdown
- Close serial port in JVM shutdown hook (existing hook, add port.close())

## Not In Scope

- Wine auto-symlink creation (print instructions only)
- jSerialComm / real hardware (Phase 4 — SerialPort interface enables drop-in)
- Baud rate or flow control emulation (PTY ignores these)
- XT support (SCI2 conflict with radio)
- CyberLoad protocol implementation (Phase 2 uses this bridge to capture it)
