# Emulator Extensions Design

Date: 2026-03-01

## Overview

Three major features for the Cybiko Java emulator:
- **Feature A**: File menu and emulator lifecycle management
- **Feature B**: Remote display/keyboard via ESP32 T-Deck Plus over WiFi TCP
- **Feature C**: RF2915 radio emulation with LAN networking and future SDR support

Implementation order: A → B → C (C starts with research phase).

## Feature A: File Menu + Emulator Lifecycle

### Problem
`CybikoEmulator.start()` blocks the calling thread forever. No way to stop, restart,
or manage NVRAM from the GUI. No menu bar exists.

### Design

**Thread the emulator**: `start()` launches a daemon thread running `run()` and returns
immediately. The existing `stop()` method (sets `running = false`) remains. Add
`isRunning()` and a completion/state-change callback for the UI.

**Menu bar** added to SwingRenderer's JFrame:
- **File → Open NVRAM...** — JFileChooser with `.nvram`/`.bin` filter. Disabled while running.
- **File → Save NVRAM As...** — Export current RAM state to a new file. Enabled while running.
- **File → Quit** (Ctrl+Q)
- **Emulator → Start** / **Stop** toggle

**Auto-save**: NVRAM auto-saves every 5 minutes while running (matching C emulator
behavior). Uses FNV-1a hash sampling to skip writes when RAM hasn't changed.

**State machine**: `STOPPED` ↔ `RUNNING`. Menu items enable/disable based on state.
Opening NVRAM loads it into external RAM. Starting boots with current ROM + RAM state.

**Scope**: ~200 lines across `CybikoEmulator.java` and `SwingRenderer.java`.

## Feature B: Remote Display/Keyboard (ESP32 T-Deck Plus)

### Decision: WiFi TCP over Bluetooth
- TCP gives guaranteed delivery, ordering, and 1-5ms LAN latency
- 4000 bytes/frame at 60fps = 240KB/s — trivial for WiFi, tight for BLE
- ESP32 has excellent WiFi TCP support; simpler than BLE GATT services
- WiFi also supports cross-subnet/VPN scenarios later

### Decision: Emulator is TCP server
- ESP32 connects to emulator at configured IP:port
- Simpler for desktop (just pick a port)
- ESP32 discovers via manual IP entry or mDNS (future)

### Decision: Full frames, no delta compression
- 160x100 at 2-bit grayscale = 4000 bytes raw
- At 60fps = 240KB/s — trivial bandwidth for WiFi
- Simple protocol, no state synchronization needed
- Can skip frames if ESP32 falls behind

### Java Server Side

New class: `RemoteDisplayServer`
- TCP server on configurable port, enabled via `--remote-display <port>` flag
- Implements `FrameBufferRenderer` — runs alongside SwingRenderer (both get frames)
- Accepts one client connection at a time
- Separate thread for connection handling

**Binary protocol** (little-endian for ESP32 compatibility):

| Direction | Message    | Format                                    |
|-----------|------------|-------------------------------------------|
| S→C       | Frame      | `0x01` + 4000 bytes raw VRAM              |
| S→C       | Audio      | `0x02` + uint16 length + PCM samples      |
| C→S       | Key Down   | `0x10` + uint8 column + uint16 bitmask    |
| C→S       | Key Up     | `0x11` + uint8 column + uint16 bitmask    |
| S→C       | Ping       | `0x03`                                    |
| C→S       | Pong       | `0x13`                                    |

Audio streaming is phase 2 — the T-Deck Plus has I2S audio hardware ready.

### ESP32 Client Side

Source: Copy `cybiko-lillygo/` from `../cybiko-c-emulator` into `esp32-remote/`.

**Strip**: All emulator core code (CPU, bus, timers, LCD controller, etc.)

**Keep**:
- TFT display init and DMA push code (ST7789 driver, 2x upscaling, grayscale LUT)
- I2C keyboard scanning (address 0x55, ASCII-to-matrix mapping)
- Trackball GPIO reading (up/down/left/right/click → column/bitmask)
- I2S audio output (48kHz, 16-bit mono)
- SD card init (for config file with server IP)
- PlatformIO project structure and pin definitions

**Add**:
- WiFi connection (credentials from `platformio.ini` or SD card config)
- TCP client connecting to emulator server
- Frame receive → VRAM → grayscale → RGB565 → TFT DMA push
- Keyboard/trackball → key event messages → TCP send
- Audio receive → I2S playback (phase 2)
- Reconnection logic on disconnect

**Key insight**: The C port already maps T-Deck keyboard ASCII codes to Cybiko
column/bitmask pairs. We send those pairs over TCP instead of calling
`keyboard_set_key()` locally. Most of the input/display code stays as-is.

## Feature C: RF2915 Radio Emulation

### Background
The Cybiko uses an RFMD RF2915 900MHz ISM transceiver. CyOS communicates with it via
SPI. Currently, the emulator has no RF emulation — the V2 CyOS stalls at boot because
RF hardware init never completes.

### Architecture (3 layers)

```
CyOS radio code
    ↓ SPI register reads/writes
[RF2915 Register Emulation]  ← RF2915Radio.java
    ↓ packet formed/received
[Packet Transport Layer]     ← RadioTransport interface
    ↓                           ↓
[UdpMulticastTransport]    [SdrTransport] (future)
    ↓                           ↓
  LAN UDP                  GNU Radio / HackRF
```

**Layer 1 — RF2915 Register Emulation** (`RF2915Radio.java`):
- Emulates RF2915 SPI register interface (24-bit command words)
- Key registers: frequency, modulation, power, TX/RX enable, status
- When CyOS writes a TX packet: extract and pass to transport layer
- When transport receives a packet: make available via RX register/interrupt
- Requires MAME research + RF2915 datasheet analysis

**Layer 2 — Transport Interface** (`RadioTransport.java`):
```java
interface RadioTransport {
    void sendPacket(byte[] data, int channel);
    void setPacketListener(PacketListener listener);
    void setChannel(int channel);
}
```

**Layer 3 — UDP Multicast Transport** (`UdpMulticastTransport.java`):
- Multicast group (e.g., `239.0.0.42:19200`)
- All emulators on LAN join the group
- Packets include: source device ID, channel, payload
- Channel filtering: only deliver packets on matching frequency
- Enabled via `--radio lan` flag

### Phased Implementation
1. **Research**: Analyze MAME source, RF2915 datasheet, disassemble CyOS radio code
2. **Register emulation**: RF2915 register set, pass init sequence, basic status
3. **Networking**: UDP multicast transport, packet TX/RX between emulators
4. **Future**: SDR transport backend for real Cybiko communication

### Side Benefit
Proper RF2915 init response should unblock V2 CyOS boot past the Cybiko logo screen.

## Context: Prior ESP32 Attempt

The emulator was previously ported to run directly on ESP32 (in `../cybiko-c-emulator`
`cybiko-lillygo/` directory). It achieved ~40-50% realtime speed — too slow for usable
emulation. This remote display approach offloads all emulation to the desktop while
the ESP32 acts as a thin display/input/audio client.
