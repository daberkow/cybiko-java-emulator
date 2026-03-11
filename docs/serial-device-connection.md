# Cybiko Connectivity - Research & Design Plan

## Vision

Enable real Cybiko devices and emulated Cybikos to interact with each other and the
modern internet through three connectivity layers:

1. **Serial/PTY** — V1 emulator SCI2 piped to a PTY for CyberLoad protocol capture
2. **CyWIG replacement** — Modern TCP/IP gateway so Cybikos can browse the web and email
3. **SDR integration** — Real Cybiko devices talking to the emulator/CyWIG over actual RF

End state: A modern computer running our emulator + SDR + CyWIG serves as a wireless
internet gateway for real physical Cybiko devices, exactly like the original setup but
without needing a serial-connected Cybiko as the gateway.

---

## Part 1: Serial Port & CyberLoad Protocol

### Background

CyberLoad (Win95/98) is Cybiko's desktop companion for Classic V1/V2 over RS232.
EZ Loader handles the Xtreme over USB (USBN9604). We focus on Classic serial.

### Hardware Wiring (confirmed from MAME cybiko.cpp)

| Channel | V1 Classic | V2 | XT |
|---------|-----------|----|----|
| SCI0 | Radio (AVR co-proc) | Radio (AVR co-proc) | unused in MAME |
| SCI1 | SPI Flash (AT45DB041) | SPI Flash | unused |
| SCI2 | **RS232 serial port** | **RS232 serial port** | Radio (our emu) / RS232 (MAME) |

MAME doesn't emulate radio at all (we're the only ones who do). MAME wires SCI2 to
RS232 at **57600 baud, 8N1** on all variants. On V1/V2, SCI2 is clearly the serial port.

### CyberLoad Protocol (from string analysis of CyberLoad.exe)

**Commands found:**
- `tsync %u` — Timestamp synchronization
- `rootinfo "%s"` / `==RootInfo=` — Directory listing (query/response)
- `ack` / `nack` / `cancel` — Flow control
- `Upload CFT File: %s %d %d` — CFS file upload
- `CFT File Deleted from Cybiko: %s` — Remote file delete
- `Direct Fast Copy: file: %s` — Optimized file transfer
- `Loading CyOS %d of %d` — CyOS firmware upload
- `Sending files %d of %d (%d of %d)` — Multi-file batch transfer

**Storage areas:** `Device Flash`, `Card Flash`, `Card Flash or SMC`

**Win32 serial API surface:** `SetupComm`, `SetCommState`, `SetCommTimeouts`,
`ClearCommError`, `WaitCommEvent`, `GetOverlappedResult`, `ReadFile`, `WriteFile`

### Virtual Serial Port Approach (PTY)

Instead of needing real hardware, pipe the V1 emulator's SCI2 to a Linux PTY:

```
V1 Emulator (SCI2 TX/RX)
    ↕
PTY master (/dev/pts/X)
    ↕
socat bridge
    ↕
PTY slave (/dev/pts/Y)
    ↕
Wine symlink: ~/.wine/dosdevices/com1 → /dev/pts/Y
    ↕
CyberLoad.exe (Wine)
```

**Emulator changes needed:**
- Add `--serial-pty <path|auto>` CLI flag
- On SCI2 TDR write (0xFFFF8B): also write byte to PTY fd
- On SCI2 RDR read (0xFFFF8D): return byte from PTY rx queue
- Background thread reads PTY → queues bytes → sets RDRF → fires RXI2 interrupt
- Note: V1 uses `sci0Rdr` variable for SCI2 reads (naming bug) — needs separate `sci2Rdr`

### Boot File Format (.boot)

Header: `01 C0 FF AB` + 4B compressed size + 4B decompressed size + `FF 12 34 AB CD`

| File | Size | Description |
|------|------|-------------|
| comp114.boot | 83KB | CyOS v1.1.4 compressed |
| comp119.boot | 84KB | CyOS v1.1.9 compressed |
| UK114/UK119/US114/US119.boot | ~71KB | VCyWIG boot images (locale variants) |

### CyOS Serial API (from B2C source)

```c
com_open(COMM_DEV_DEFAULT, 1)     // Open serial port
com_close(serial)                 // Close port
com_read(serial, 1)               // Read 1 byte (-1 if none)
com_write(serial, value, 1)       // Write 1 byte
com_get_config(serial, &config)   // Get/set baud, databits, stopbits, parity, flow control
```

Baud rates: 110 to 115200. Flow control: None, XON/XOFF, RTS/CTS, DSR/DTR.

---

## Part 2: VCyWIG / Internet Gateway

### What VCyWIG Was

Virtual CyWIG (Cybiko Wireless Internet Gate) allowed Cybiko devices to access the
internet. The architecture was:

```
[Cybiko devices] ←— RF (900MHz, 150-300ft) —→ [Gateway Cybiko] ←— RS232 —→ [PC + VCyWIG.exe]
                                                                              ↕
                                                                         [Internet]
```

The gateway Cybiko ran special server firmware (UK/US .boot images). VCyWIG.exe on the
PC bridged between the serial port and the internet via Cybiko's servers at
`mail1.cybiko.com` (ports 15170-15171 server side, 4000-5000 local side).

### VCyWIG Protocol (from VCyWIG.exe string analysis)

**CyIP (Cybiko IP) packet layer:**
- `frominet %lx -> %lx %lx:%lx cyip %lx buf %lx datasize %d`
- `toinet %lx -> %lx %lx:%lx cyip %lx buf %lx datasize %d`
- `routeFromHitachi: bad size %lx` — serial-side packet routing
- `packet %lx->%lx %lx:%lx is delivering` — delivery tracking
- CRC validation on CyIP and Refresh packets
- Delivery receipts with confirmation

**Serial port layer:**
- `CyPort: constructing CyPort at %s` / `found cybiko on port COM%d`
- `WRONG packet from com port!!!!!!!!!!` — packet validation
- `send_cmd: failed to write port` / `request_send_start: failed to write port`

**Internet layer:**
- Connected to `mail1.cybiko.com` (CyCS = Cybiko Communication Server)
- UDP packets to/from remote server
- `POST server %s port %d proc %s request %s` — HTTP POST for some operations
- `http://www.cybiko.com/download/downloader1.asp?OS=WIN&param=trusttime`

### TCPKit — The Simpler Alternative

TCPKit is a **third-party** TCP/IP stack for Cybiko that's much simpler than VCyWIG:

```
[Cybiko app (tcphtml/telnet)] ←— RF —→ [tcpjunction.app on gateway Cybiko] ←— serial —→ [tcpgate.exe on PC]
                                                                                             ↕
                                                                                        [Real TCP sockets]
```

**tcpgate.exe protocol** (from string analysis — very well documented in debug strings):

```
>### = messages received from cybiko
<### = messages sent to cybiko
{### = messages to socket
}### = messages from socket
```

**Message types** (matching tcperror.h):
| Message | Direction | Purpose |
|---------|-----------|---------|
| TCPMSG_CONNECT_REQUEST | Cybiko→Gate | Open TCP socket to host:port |
| TCPMSG_CONNECT_RESPONSE | Gate→Cybiko | Connection result |
| TCPMSG_WRITE_REQUEST | Cybiko→Gate | Send data to socket |
| TCPMSG_WRITE_RESPONSE | Gate→Cybiko | Write acknowledgment |
| TCPMSG_READ_RESPONSE | Gate→Cybiko | Data received from socket |
| TCPMSG_CLOSE_REQUEST | Cybiko→Gate | Close socket |
| TCPMSG_CLOSE_RESPONSE | Gate→Cybiko | Socket closed confirmation |
| TCPMSG_FILTER_REQUEST | Cybiko→Gate | Apply content filter |
| TCPMSG_FILTER_RESPONSE | Gate→Cybiko | Filter applied |
| TCPMSG_ACKNOWLEDGE | Both | Flow control ACK |

**Filters:** TCP_FILTER_RAW, TCP_FILTER_WML (WAP), TCP_FILTER_HTML

**Max message size:** 1024 bytes. **Max concurrent sockets:** 64.

**Key**: tcpgate communicates over serial (COM1) with ack/nack flow control and retry
(`NACK Received... Resending....`, `sending retry=%d len=%d %s`).

**TCPKit has FULL SOURCE for the Cybiko side** (`tcphtml.c`, `telnet.c`, `tcplib.h`,
`tcperror.h`) and we have the compiled `tcpgate.exe` with very verbose debug strings.
This is much more tractable than reverse-engineering VCyWIG's proprietary CyIP protocol.

### Why TCPKit > VCyWIG for Our Purposes

| | VCyWIG | TCPKit |
|---|--------|--------|
| Protocol | Proprietary CyIP, complex | Simple message-based, source available |
| Server dependency | mail1.cybiko.com (dead) | Direct TCP sockets, no external server |
| Source code | Closed (only .exe) | Full C source for Cybiko side |
| Gateway | Needs special .boot firmware | Regular .app (tcpjunction.app) |
| Functionality | Email + WAP only | Raw TCP, HTTP, Telnet, extensible |

**Recommendation: Reimplement tcpgate in Java as our internet gateway.** We have
the full protocol definition from the source headers and debug strings.

---

## Part 3: SDR Integration

### Current State

The emulator already has `SdrTransport.java` — a TCP bridge client that connects to
a GNU Radio server. Wire format: `[2B length][1B channel][payload]`.

### End-State Architecture

```
[Real Cybiko] ←—— 900MHz RF ——→ [SDR Hardware (HackRF/USRP)]
                                       ↕
                                [GNU Radio flowgraph]
                                       ↕ TCP (localhost:19201)
                                [Our Emulator / CyWIG Gateway]
                                       ↕
                                [Internet / Modern Services]
```

With this setup:
- Real Cybiko devices discover the emulator as a peer (already works over UDP multicast)
- The CyWIG gateway intercepts TCP requests from radio frames
- Gateway makes real HTTP/SMTP/TCP connections on behalf of the Cybiko
- Responses flow back over SDR to the real device

---

## Multi-Phase Design Plan

### Phase 1: Virtual Serial Port (PTY Bridge)

**Goal:** Pipe V1 emulator SCI2 to a PTY so we can capture CyberLoad protocol traffic.

**Deliverables:**
1. `--serial-pty` CLI flag for the emulator
2. `VirtualSerialPort.java` — PTY I/O with background reader thread
3. SCI2 RX path for V1 (currently unimplemented — only TX capture exists)
4. Wine COM1 symlink auto-setup
5. Protocol capture log (hex dump of all SCI2 TX/RX)

**Dependencies:** `socat` (Linux), Wine for CyberLoad
**Code changes:** AddressBus.java (SCI2 RX), CybikoEmulator.java (CLI), new class

### Phase 2: CyberLoad Protocol Capture

**Goal:** Document the CyberLoad serial protocol by running CyberLoad.exe against
our V1 emulator via the PTY bridge.

**Approach:**
1. Boot V1 emulator with `--serial-pty auto`
2. Connect CyberLoad in Wine to the virtual COM port
3. Capture the handshake, device detection, directory listing, file upload flows
4. Document the full protocol in `docs/cyberload-protocol.md`

**Alternative:** If CyberLoad doesn't work via PTY (timing issues, flow control):
- Disassemble V1 boot ROM SCI2 handler (32KB, tractable)
- Disassemble CyOS serial handler from RAM dump
- Use `tools/H8SDisasm.java` for analysis

### Phase 3: TCPKit Gateway (tcpgate replacement)

**Goal:** Reimplement tcpgate.exe in Java so Cybiko apps can make real TCP connections.

**Deliverables:**
1. `TcpGateway.java` — Message dispatcher matching tcperror.h protocol
2. Socket pool (up to 64 concurrent) mapped to tcpqueue IDs
3. Serial framing layer (ack/nack, retransmit)
4. Content filters: RAW passthrough, HTML tag stripping, WML conversion
5. Integration with emulator radio (UDP multicast) and SDR transport

**Source material:**
- `tcplib.h` — Full API definition
- `tcperror.h` — Message types, error codes, filter types
- `tcphtml.c` — Working HTTP client (shows connect→filter→write→read flow)
- `telnet.c` — Working Telnet client
- `tcpgate.exe` debug strings — Message format details

**This is the most valuable phase** — it gives Cybiko apps real internet access,
working with both emulated and real (via SDR) Cybikos.

### Phase 4: CyberLoad Protocol Implementation

**Goal:** Manager can upload/download files to a real Cybiko Classic over serial.

**Deliverables:**
1. `CyberloadProtocol.java` — Protocol implementation from Phase 2 findings
2. `CybikoSerialConnection.java` — jSerialComm wrapper
3. Manager UI: "Connect Device..." in sidebar, port selection, progress indicators
4. `CfsStorage` interface for live device access

**Dependencies:** Phase 2 (protocol documentation), jSerialComm library, real hardware

### Phase 5: SDR + CyWIG Full Stack

**Goal:** Real Cybiko devices can use internet services through SDR + our gateway.

**Deliverables:**
1. GNU Radio flowgraph for Cybiko RF demod/mod (900MHz FSK)
2. SdrTransport enhanced for bidirectional frame relay
3. TCPKit gateway (Phase 3) accepting connections from SDR-bridged devices
4. VCyWIG-compatible beacon advertising (so Cybikos auto-detect the gateway)
5. End-to-end test: real Cybiko → SDR → gateway → internet → response

---

## Existing Assets

### In the Archive
| File | What | Use |
|------|------|-----|
| `CyberLoad.exe` (1.1MB) | CyberLoad for Classic | Protocol RE target |
| `VCyWIG.exe` (327KB) | VCyWIG gateway | Protocol reference |
| `VCyWIG.pdf` (22 pages) | VCyWIG user manual | Architecture docs |
| `tcpgate.exe` (176KB) | TCPKit gateway | Protocol RE (verbose debug strings) |
| `tcplib.h` / `tcperror.h` | TCPKit protocol headers | **Full protocol spec** |
| `tcphtml.c` / `telnet.c` | TCPKit app source | Working client examples |
| `tcpjunction.app` (1.9KB) | TCPKit radio relay app | Runs on gateway Cybiko |
| `serial.b2c` / `serialtest.b2c` | CyOS serial API | B2C serial examples |
| `UK114.boot` etc. | VCyWIG boot images | Gateway firmware |

### In the Emulator
| Component | What | Relevance |
|-----------|------|-----------|
| `SCI2 output capture` | Already logs SCI2 TX | Ready for PTY bridging |
| `CfsImage` / `CfsBlock` | CFS filesystem | File transfer format |
| `CybikoAppParser` | .app file parser | Upload format |
| `RadioCoProcessor` | AVR radio stub | Frame routing for gateway |
| `UdpMulticastTransport` | LAN radio | Emulator-to-emulator comms |
| `SdrTransport` | SDR TCP bridge | Real RF integration |
| `H8SDisasm` | Disassembler | Protocol RE from ROM |

### In MAME
- `cybiko.cpp` line 356-481: SCI2-to-RS232 wiring at 57600 baud (all variants)
- No radio emulation — we're the only ones who handle it

---

## References

- `../cybiko-archive/cybiko/cybiko/CyberLoad from install/` — CyberLoad + .boot files
- `../cybiko-archive/cybiko/cybiko/Virtual CyWIG from install/` — VCyWIG + boot images
- `../cybiko-archive/cybiko/cybiko/VCyWIG.pdf` — VCyWIG manual (22 pages)
- `../cybiko-archive/cybiko/cybiko/apps/tcpkit/` — TCPKit source + tcpgate.exe
- `../cybiko-archive/cybiko/cybiko/apps/serial/` — B2C serial API examples
- `../mame/mame-master/mame-master/src/mame/cybiko/cybiko.cpp` — MAME SCI2 wiring
- `manager/Cybiko_NVRAM_Manager_Design_Doc.md` section 8 — Serial stretch goal spec
