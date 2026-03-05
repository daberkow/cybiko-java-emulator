# Cybiko Wireless Protocol

How Cybiko emulators communicate over the network. This document covers the
emulator-to-emulator networking protocol — the wire format, frame lifecycle,
and machine-specific differences. For the underlying CyOS ↔ AVR radio interface
(SCI registers, DTC transfers, state machines), see [rf2915-research.md](rf2915-research.md).

## Architecture

```
Emulator A                                      Emulator B
┌──────────────────────┐                        ┌──────────────────────┐
│ CyOS radio task      │                        │ CyOS radio task      │
│   ↓                  │                        │   ↑                  │
│ SCI TX (TDR writes)  │                        │ SCI RX (RDR reads)   │
│   ↓                  │                        │   ↑                  │
│ RadioCoProcessor     │                        │ RadioCoProcessor     │
│   ↓ handleTransmit() │                        │   ↑ prepareRxFrame() │
│ RadioTransport       │── UDP multicast ──────→│ RadioTransport       │
│ (UdpMulticastTransport│  239.0.0.42:19200     │ (UdpMulticastTransport│
│  or SdrTransport)    │                        │  or SdrTransport)    │
└──────────────────────┘                        └──────────────────────┘
```

Two transport implementations:
- **UdpMulticastTransport** — LAN discovery via multicast group 239.0.0.42:19200, TTL=1
- **SdrTransport** — TCP bridge to GNU Radio SDR on configurable host:port (default localhost:19201)

## UDP Wire Format

```
Offset  Size  Content
------  ----  ----------------------------------------
0-3     4     Sender device ID (32-bit, big-endian)
4       1     Channel number (0-29, typically 2 or 4)
5+      N     Payload (42 bytes for beacons, 192 bytes for chat/data)
```

The sender device ID matches the `--radio-id` argument (or random UUID hash
if unspecified). Self-filtering: each transport ignores packets from its own ID.

## Frame Lifecycle

### Transmit Path

CyOS assembles a raw frame in RAM and sends it to the AVR co-processor via
SCI (UART). The emulator intercepts these bytes and forwards them over the network.

**XT (Xtreme) — DTC bulk transfer on SCI2:**
1. CyOS sends 2-byte command (0x30 poll or 0xCF scan) via SCI2
2. CyOS sets up TX DTC: source = RAM buffer, count = 51 or 201 bytes
3. AddressBus detects DTCERF bit 6 clear → reads TX buffer from RAM
4. `handleTransmit()` strips 8-byte RF header + 1 trailing byte → 42/192-byte payload
5. Payload sent via `RadioTransport.sendPacket(payload, channel)`

**V1 (Classic) — Interrupt-driven byte-by-byte on SCI0:**
1. Emulator's `tickV1Radio()` sets up radio state machine every 15 frames
2. TXI0 ISR sends command byte (state 7), param byte (state 8), then data (state 2)
3. Data bytes accumulated in `RadioCoProcessor.v1DataBuffer`
4. TXI0 state 9 clears TIE → `v1TxComplete()` called
5. `handleTransmit()` strips 8-byte RF header (no trailing byte for V1) → 42/192-byte payload
6. Payload sent via `RadioTransport.sendPacket(payload, channel)`

### Receive Path

Incoming UDP packets are queued and delivered to CyOS through the SCI receive path.

**XT — DTC bulk transfer:**
1. Frame arrives via UDP → queued in `RadioCoProcessor.receivedFrames`
2. After TX DTC completes, `completeTxDtc()` waits up to 15ms for frames
3. Returns indicator: 0x32 (50-byte small frame) or 0xC8 (200-byte large/null frame)
4. AddressBus defers delivery: first 0x03 (ACK), then the indicator byte
5. CyOS state 6 consumes 0x03 → completion → state 1
6. CyOS state 1 consumes indicator → sets up RX DTC (DTCERF bit 7)
7. `prepareRxFrame()` bulk-transfers 50/200 bytes to CyOS RAM buffer
8. RX DTC completion fires → CyOS processes the frame

**V1 — Byte-by-byte via RXI0:**
1. Frame arrives via UDP → queued in `RadioCoProcessor.receivedFrames`
2. After TXI0 completes, `v1TxComplete()` waits up to 15ms for frames
3. Queues response bytes: 0x03 (ACK) + indicator + frame data (50/200 bytes)
4. `tickSci0()` delivers bytes one at a time via RXI0 interrupts
5. RXI0 state 9 consumes 0x03 → completion handler → state 1
6. RXI0 state 1 consumes indicator → frame handler → state 5
7. RXI0 state 5 consumes frame data bytes → frame complete → state 1

**Async delivery (both XT and V1):**
Frames can also arrive between TX cycles. `tickSci2()` (XT) or `tickSci0()` (V1)
checks for pending frames every 512 CPU cycles when CyOS radio state == 1 (idle).
If a frame is pending, the indicator is injected directly, bypassing the TX→RX cycle.

## Frame Format

### Over-the-Air Frame (CyOS TX buffer)

The raw frame as CyOS prepares it in RAM, before the emulator strips headers:

```
Poll beacon (50 bytes on V1, 51 bytes on XT):
Offset  Size  Content
------  ----  ----------------------------------------
0-3     4     RF preamble: FF FF FF FF
4-7     4     CyID (sender's device identity)
8       1     Channel byte: 0xC0 | channel (e.g. 0xC2 = ch 2)
9       1     Frame type: 0x01 (beacon/presence)
10-11   2     Flags
12-15   4     Sequence/CRC (changes per packet)
16      1     0x2A (payload length marker, 42 decimal)
17-19   3     Padding (zeros)
20-27   8     Username (null-terminated, overwrites "unknown\0")
28      1     0x7F
29      1     0x00
30-49   20    CRC/sequence data (varies per TX)
50      1     Trailing status byte: 0x00 (XT only, not present on V1)

Scan/chat frame (200 bytes on V1, 201 bytes on XT):
Offset  Size  Content
------  ----  ----------------------------------------
0-7     8     RF preamble + CyID (same as poll)
8       1     Channel byte
9       1     Frame type: 0x20 (data/message)
10-15   6     Flags + sequence
16      1     Length marker
17-19   3     Unknown
20-25   6     Unknown
26      1     Message length
27+     N     Chat message text (null-terminated)
27+N    pad   Zero padding to fill frame
200     1     Trailing status byte: 0x00 (XT only)
```

### Network Payload (after header stripping)

What actually travels over UDP between emulators:

```
Poll beacon payload (42 bytes):
Offset  Size  Content
------  ----  ----------------------------------------
0       1     Channel byte: 0xC0 | channel
1       1     Frame type (0x01 = beacon, 0x20 = data)
2-3     2     Flags
4-7     4     Sequence/CRC
8       1     Payload length marker
9-11    3     Padding
12-19   8     Username (null-terminated)
20      1     0x7F
21      1     0x00
22-41   20    CRC/sequence data

Scan/chat payload (192 bytes):
Offset  Size  Content
------  ----  ----------------------------------------
0       1     Channel byte
1       1     Frame type: 0x20
2-7     6     Flags + sequence
8       1     Length marker
9-11    3     Unknown
12-17   6     Unknown
18      1     Message length
19+     N     Chat message text (null-terminated)
19+N    pad   Zero padding
```

### RX DTC Buffer (as delivered to CyOS)

On the receive side, `prepareRxFrame()` prepends an 8-byte AVR header before
the payload, so CyOS sees:

```
RX buffer (50 bytes for poll, 200 bytes for scan/chat):
Offset  Size  Content
------  ----  ----------------------------------------
0-3     4     Destination: 0xFFFFFFFF (broadcast) or specific CyID
4-7     4     Sender's CyID
8       1     Channel byte
9       1     Frame type
10+     N     Frame data (same layout as network payload offset 2+)
```

CyOS checks bytes 0-3 against the local CyID (at 0x4B4AC2 on XT, 0x21F9DC on V1)
and broadcast (0xFFFFFFFF). Non-matching frames are silently discarded.

## Device Identity (CyID)

Each Cybiko has a 32-bit device identity (CyID) stored in flash. Without unique
CyIDs, CyOS's self-filter rejects frames from devices with the same identity.

| Machine | CyID Location | Patching Method |
|---------|--------------|-----------------|
| XT | Flash ROM offset 0x7F818 | `patchCyId()` patches memory-mapped flash + CRC32 checksum at 0x7FFFC |
| V1 | Compressed in SPI flash, cached at RAM 0x21F9DC | Patched in RAM after CyOS decompresses; connObj beacon also patched per-TX |

The `--radio-id` argument sets both the CyID and the UDP transport sender ID,
so `senderId == CyID` for each emulator instance.

### V1 CyID Patching

V1's CyID is baked into the compressed CyOS firmware in SPI flash (AT45DB041).
`patchCyId()` is a no-op for V1 because `flashRom` is null. Instead, AddressBus
patches the CyID in two places after CyOS boots:

1. **RAM cache** at 0x21F9DC — so CyOS accepts incoming frames addressed to the patched ID
2. **connObj beacon frame** at offset 4-7 — so outgoing beacons carry the correct CyID

Both patches happen in `tickV1Radio()` after the radio bootstrap completes.

## Channel Management

CyOS uses two channels and hops between them during peer discovery:
- Channel 2 (0xC2) — default after init command `01 02 02`
- Channel 4 (0xC4) — alternate scan channel

The channel byte in each frame (`payload[0] & 0x3F`) determines which channel
the UDP packet is tagged with. The transport layer filters by channel on receive.

On the RX side, CyOS checks if the received frame's channel matches the expected
channel. Mismatched frames use MRA=0x00 (XT DTC) or are discarded (V1).

## Command Protocol (H8S → AVR)

Commands sent from CyOS to the AVR co-processor over SCI:

| Command | Length | Description |
|---------|--------|-------------|
| `01 04 00` | 3 bytes | Init/reset (V2 first boot) |
| `01 02 XX` | 3 bytes | Set channel (XX = channel number) |
| `01 03 00` | 3 bytes | V1 second init command |
| `30 XX` | 2 bytes | Poll: check for received frames (small beacons only, ≤50 bytes) |
| `CF XX` | 2 bytes | Scan: check for received frames (any size) |

3-byte commands (0x01 prefix) get an immediate 0x03 ACK response.
2-byte commands (0x30/0xCF) get no immediate response — the response comes after
the frame data transfer completes (via DTC on XT, TXI0 on V1).

### Poll vs Scan

| | Poll (0x30) | Scan (0xCF) |
|---|------------|-------------|
| TX frame size | 50 bytes (V1) / 51 bytes (XT) | 200 bytes (V1) / 201 bytes (XT) |
| RX frame size | 50 bytes | 200 bytes |
| Delivers | Small beacons only (≤50 bytes) | Any frame size |
| Purpose | Periodic presence announcement | Peer discovery, chat messages |

## Machine Differences

### XT (Xtreme)

- **SCI channel**: SCI2 (registers at 0xFFFF88-0xFFFF8D)
- **Transfer method**: Hardware DTC bulk transfer (TX via DTCERF bit 6, RX via bit 7)
- **Interrupt vectors**: RXI2=89, TXI2=90
- **Radio init**: Deferred until user opens a radio feature (Chat, Friend Finder)
- **Frame sizes**: 51 bytes (poll) / 201 bytes (scan) — includes trailing status byte
- **CyID source**: Flash ROM at offset 0x7F818
- **State field**: radioObj+0x335A

### V1 (Classic)

- **SCI channel**: SCI0 (registers at 0xFFFF78-0xFFFF7D)
- **Transfer method**: Interrupt-driven byte-by-byte (TXI0 vector 82, RXI0 vector 81)
- **Radio init**: During early boot (before animated logo)
- **Frame sizes**: 50 bytes (poll) / 200 bytes (scan) — no trailing status byte
- **CyID source**: Compressed in SPI flash, cached in RAM at 0x21F9DC
- **State field**: radioObj+0x3370
- **Emulator-driven**: CyOS never calls the tick function after init; the emulator's
  `tickV1Radio()` drives poll/scan cycles every 15 frames by directly manipulating
  the radio state machine fields

### V1 Radio State Machine

CyOS V1's radio uses an interrupt-driven state machine:

```
tickV1Radio() sets state=7
    ↓
State 7 (TXI0): Send command byte → state 8
State 8 (TXI0): Send param byte → state 2
State 2 (TXI0): Send data bytes from txPtr → when txPtr≥txEnd → state 9
State 9 (TXI0): Clear TIE → v1TxComplete() → queue 0x03 + indicator + frame data
    ↓
State 9 (RXI0): Receive 0x03 ACK → completion handler → state 1
State 1 (RXI0): Receive indicator (0x32/0xC8) → frame handler → state 5
State 5 (RXI0): Receive frame data bytes → frame complete → state 1
    ↓
(cycle repeats on next tickV1Radio)
```

### V1 Tick Function (0x214760)

The real CyOS tick function that `tickV1Radio()` replicates:

1. Check re-init flag (radioObj+0x3375) → call re-init handler if set
2. Check connObj pointer (radioObj+0x3344) → skip if NULL
3. Check connObj+0xD3:
   - Non-zero → direct 3-byte send (state=2, txEnd=connObj+3)
4. Check txPtr (radioObj+0x3350):
   - Non-zero → TX already in progress, skip to channel check
5. Set up TX buffer:
   - txPtr = connObj
   - connObj+0xD2 non-zero → poll: txEnd=connObj+50, cmd=0x30
   - connObj+0xD2 zero → scan: txEnd=connObj+200, cmd=0xCF
6. Set param byte from bit 3 of connObj+0x09
7. Check channel (connObj+0xD4 vs radioObj+0x3371), change if different
8. Set state=7, enable TIE (BSET #7 at 0xFFFF7A)

### V1 connObj Beacon Layout

The connObj doubles as the TX frame buffer. `tickV1Radio()` populates it:

| Offset | Size | Content | Source |
|--------|------|---------|--------|
| 0-3 | 4 | RF preamble FF FF FF FF | CyOS init |
| 4-7 | 4 | CyID | Patched by tickV1Radio from --radio-id |
| 8 | 1 | Channel byte (0xC0\|ch) | Set by tickV1Radio each cycle |
| 9 | 1 | Frame type 0x01 | Set by tickV1Radio at bootstrap |
| 16 | 1 | 0x2A (length marker) | Set by tickV1Radio at bootstrap |
| 20-27 | 8 | Username | Set by tickV1Radio ("Cybiko" default) |
| 0xD2 | 1 | Poll/scan flag | Read by tickV1Radio (0=scan, non-zero=poll) |
| 0xD4 | 1 | Channel | Read by tickV1Radio for channel selection |

## Frame Delivery Timing

### Synchronous Path
The primary delivery mechanism. After each TX cycle completes:
1. `completeTxDtc()` (XT) or `v1TxComplete()` (V1) waits up to **15ms** for UDP frames
2. Returns indicator based on what's available
3. Frame delivered through the normal state machine

### Async Path
For frames arriving between TX cycles:
- Checked every **512 CPU cycles** in `tickSci2()` (XT) or `tickSci0()` (V1)
- Only fires when CyOS radio state == 1 (idle)
- Injects indicator directly into the SCI receive path
- Uses separate `asyncRxPending` flag to avoid interference with synchronous path

### Queue Management
- **Received frame queue capacity**: 4 frames (handles CyOS 3x retransmit bursts)
- **ConcurrentLinkedQueue**: UDP listener thread adds frames, emulator thread reads them
- **Poll suppression**: Poll commands (0x30) only deliver small frames (≤50 bytes);
  large frames wait for scan commands (0xCF)

## Running Multi-Emulator Chat

```bash
# Terminal 1: XT emulator
./gradlew run --args="cyrom150.bin cyos_v1508.bin --radio lan --radio-id 1 --nvram xt.nvram"

# Terminal 2: V1 Classic emulator
./gradlew run --args="--machine v1 cyrom112.bin flash_v1246.bin --radio lan --radio-id 2"

# Terminal 3: Another V1
./gradlew run --args="--machine v1 cyrom112.bin flash_v1246.bin --radio lan --radio-id 3"
```

Each emulator needs a unique `--radio-id`. Peer discovery happens automatically
via poll beacons on the home screen (XT) or continuously (V1). Open Chat on any
device to see nearby peers and exchange messages.
