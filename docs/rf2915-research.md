# The Cybiko Radio System

A comprehensive reference for how the Cybiko handheld's wireless communication
works, from silicon to protocol, and what it means for emulation.

## Overview

The Cybiko's wireless system is a three-chip radio subsystem built around the
RFMD RF2915 900 MHz ISM transceiver [^1]. Critically, the main H8S CPU never talks
to the RF2915 directly. Instead, all radio communication is mediated by an Atmel
AT90S2313 AVR microcontroller acting as a co-processor [^2][^3]. The H8S sends
commands and data frames to the AVR over a UART link, and the AVR handles all
low-level RF operations: PLL tuning, TX/RX switching, Manchester encoding, and
packet framing [^4].

```
H8S CPU <--UART/SCI0--> AT90S2313 AVR <--3-wire serial--> LMX2315 PLL
                              |                                |
                              |                           VCO feedback
                              |                                |
                              +------data out/in---------> RF2915
                                                               |
                                                            antenna
```

## The Three Radio Chips

### RF2915 — Analog Transceiver

The RF2915 is manufactured by RF Micro Devices (RFMD, now Qorvo) [^1]. It is a
**purely analog** transceiver — it has no digital SPI register interface, no
configuration registers, and no microcontroller of its own. All "configuration"
is done via analog voltage levels and external components [^1].

| Parameter | Value |
|-----------|-------|
| Frequency range | 300 MHz to 1000 MHz [^1] |
| Cybiko US band | 902–928 MHz, 30 channels [^4] |
| Cybiko EU band | 868–870 MHz [^4] |
| Modulation | FSK (narrow/wideband), ASK, OOK [^1] |
| Data rate | 19,200 bps [^4] |
| Output power | 10 mW with power control [^1] |
| Supply voltage | 2.4V to 5.0V [^1] |
| Package | 32-lead plastic TQFP [^1] |
| Noise figure | 10 dB cascaded [^1] |
| RSSI range | 0.5V to 2.5V DC [^1] |

**Receiver path:**
Low Noise Amplifier (LNA, open-collector output) → RF Mixer → IF Amplifiers
(60 dB limiting) → FM Demodulator (quadrature discriminator) → Data Amplifier →
DATA OUT pin [^1].

**Transmitter path:**
Data input → Variable Gain Amplifier → Power Amplifier → TX OUT pin [^1].

**Control pins:**
- TX ENABLE: voltage > 2.0V powers up transmitter, < 1.0V disables [^1]
- RX ENABLE: voltage > 2.0V powers up receiver, < 1.0V disables [^1]
- VCO can be enabled independently for PLL lock before TX/RX [^1]
- Both disabled = full power-down mode [^1]

**Key takeaway for emulation:** The RF2915 itself does not need to be emulated.
It is invisible to the H8S CPU. We only need to emulate the AVR's behavior as
seen over the UART.

### LMX2315 — PLL Frequency Synthesizer

The LMX2315 is a National Semiconductor (now TI) 1.2 GHz PLL frequency
synthesizer [^5]. It generates the precise carrier frequencies for each of the
Cybiko's 30 radio channels. The AVR programs it via a 3-wire serial interface
(DATA, CLOCK, LE) — this is **not** standard SPI [^5].

| Parameter | Value |
|-----------|-------|
| Max frequency | 1.2 GHz [^5] |
| Serial interface | 19-bit shift register, MSB first [^5] |
| Clock | Rising edge triggered [^5] |
| Lock detect output | Pin 8 (LD) [^5] |

**Register structure** — the LMX2315 has a single 19-bit data register. The LSB
selects which internal counter gets loaded [^5]:

When control bit = 1 (R Counter):

| Bits | Field | Description |
|------|-------|-------------|
| 18–5 | S1–S14 | R counter divide ratio (3–16,383) [^5] |
| 4 | S15 | Prescaler select: 64/65 or 128/129 [^5] |
| 3–1 | — | Reserved [^5] |
| 0 | Control | 1 = load R counter [^5] |

When control bit = 0 (N Counter):

| Bits | Field | Description |
|------|-------|-------------|
| 18–8 | B10–B0 | Programmable (B) counter (3–2,047) [^5] |
| 7–1 | A6–A0 | Swallow (A) counter (0–127) [^5] |
| 0 | Control | 0 = load N counter [^5] |

**Frequency formula:**
```
f_VCO = [(P × B) + A] × f_OSC / R
```
Where P = prescaler (64 or 128), B = programmable counter, A = swallow counter,
f_OSC = reference oscillator, R = reference divider [^5].

**Channel spacing:** The 902–928 MHz band divided into 30 channels gives ~867 kHz
per channel [^4]. The AVR selects channels by reprogramming the LMX2315's N counter.

### AT90S2313 — AVR Radio Co-Processor

The AT90S2313 is the bridge between the H8S CPU and the analog radio hardware. It
appears on the PCB of both the Classic V2 and Xtreme [^2][^3].

| Parameter | Value |
|-----------|-------|
| Architecture | 8-bit AVR RISC [^6] |
| Clock | 4 MHz [^6] |
| Flash | 2 KB [^6] |
| SRAM | 128 bytes [^6] |
| EEPROM | 128 bytes [^6] |
| UART | 1 channel [^6] |
| I/O pins | 15 [^6] |
| Timers | 2 (8-bit + 16-bit) [^6] |

**Pin connections** (inferred from circuit function [^3][^4]):

| AVR Pin | Connected To | Function |
|---------|-------------|----------|
| RXD (PD0) | H8S TXD0 | Receive commands/frames from CPU |
| TXD (PD1) | H8S RXD0 | Send received frames to CPU |
| PBx | LMX2315 DATA | PLL serial data |
| PBx | LMX2315 CLOCK | PLL serial clock |
| PBx | LMX2315 LE | PLL load enable |
| PDx | RF2915 TX ENABLE | Transmitter enable (GPIO) |
| PDx | RF2915 RX ENABLE | Receiver enable (GPIO) |
| PDx | RF2915 DATA OUT | Received demodulated data (via LPF) |
| RST | H8S TMO0 (P2.6) | Reset / in-system programming [^3] |

**In-system programming:** The AVR can be reprogrammed by the H8S without physical
removal. Pulling the AVR's RST line low (via H8S Timer Output TMO0 on Port 2 bit 6)
enables the SPI programming interface. MOSI/MISO/SCK lines are shared between the
H8S and AVR, with SCK pulled high via 5.1K resistor during normal operation [^3].

## H8S ↔ AVR Communication (SCI0)

The H8S communicates with the AVR over **SCI channel 0** (SCI0) at **53,333 baud** [^3].

### SCI0 Register Map

| Address | Register | Description |
|---------|----------|-------------|
| 0xFFFF78 | SMR0 | Serial Mode Register (async, 8N1) [^7] |
| 0xFFFF79 | BRR0 | Bit Rate Register [^7] |
| 0xFFFF7A | SCR0 | Serial Control Register [^7] |
| 0xFFFF7B | TDR0 | Transmit Data Register [^7] |
| 0xFFFF7C | SSR0 | Serial Status Register [^7] |
| 0xFFFF7D | RDR0 | Receive Data Register [^7] |
| 0xFFFF7E | SCMR0 | Smart Card Mode Register [^7] |

### SSR Status Bits [^8]

| Bit | Name | Description |
|-----|------|-------------|
| 7 | TDRE | Transmit Data Register Empty |
| 6 | RDRF | Receive Data Register Full |
| 5 | ORER | Overrun Error |
| 4 | FER | Framing Error |
| 3 | PER | Parity Error |
| 2 | TEND | Transmit End |
| 1 | MPB | Multi-Processor Bit |
| 0 | MPBT | Multi-Processor Bit Transfer |

### SCI Channel Assignments

| SCI Channel | V1/V2 Connection | XT Connection |
|-------------|-------------------|---------------|
| SCI0 | AVR radio (53.3 KBaud) [^3] | AVR radio (53.3 KBaud) [^3] |
| SCI1 | AT45DB041 SPI flash [^9] | Not connected (memory-mapped flash) |
| SCI2 | Debug serial (57600) [^10] | Debug serial (57600) [^10] |

### MAME Has No SCI0 Wiring

In MAME, **SCI0 is completely unwired** on all three machine variants [^9][^10].
Only SCI1 and SCI2 have connections:

```cpp
// SCI1 → SPI flash (V1/V2 only) — cybiko.cpp line 424–425
m_maincpu->write_sci_tx<1>().set("flash1", FUNC(at45db041_device::si_w));
m_maincpu->write_sci_clk<1>().set("flash1", FUNC(at45db041_device::sck_w));

// SCI2 → debug serial (all variants) — cybiko.cpp line 366–367, 480–481
m_debug_serial->rxd_handler().set(m_maincpu, FUNC(h8_device::sci_rx_w<2>));
m_maincpu->write_sci_tx<2>().set(m_debug_serial, FUNC(rs232_port_device::write_txd));
```

This means **MAME has zero radio emulation**. CyOS writes to SCI0 TDR are silently
dropped. CyOS reads from SCI0 RDR return nothing. This directly causes V2 CyOS to
stall at boot [^11].

## CyOS Radio Protocol (CYRF / CyDP)

### Protocol Stack

The Cybiko uses a proprietary protocol called **CyDP** (Cybiko RF Digital Protocol),
with versions like "CyDPTM x.30" in the Xtreme product specs. A higher-level
protocol called **CYRF** handles ad-hoc mesh networking [^12].

| Layer | Component | Responsibility |
|-------|-----------|----------------|
| Physical | RF2915 + LMX2315 | FSK modulation, 902–928 MHz, 19.2 kbps [^4] |
| Data Link | AT90S2313 AVR | Manchester encoding, sync bits, 50/200 byte frames [^4] |
| Network | CYRF (CyOS) | Device discovery, channel allocation, mesh routing [^12] |
| Application | CyOS messaging | CyID addressing, process IDs, message passing [^12] |

### Air Interface

From the FCC technical description [^4]:
- H8S packs messages into frames of **50 or 200 bytes**
- Frames are sent to the AVR over UART (SCI0)
- AVR adds synchronization bits
- AVR applies Manchester encoding (doubles bit rate on air)
- Encoded data transmitted via RF2915 at 19,200 bps
- Half-duplex operation (TX and RX not simultaneous)

### Mesh Networking Features

From Cybiko patent US20020122410A1 [^12]:
- Up to **3,000 devices** in a single ad-hoc mesh network
- Each device has a unique **CyID** assigned at manufacture
- One RF channel dedicated to coordination/system info
- Remaining channels carry application data
- **Retransmitter** devices extend range by relaying packets
- Packet confirmation and retransmission for reliability
- Messages carry: receiver CyID, receiver process ID, sender CyID
- Message format: identifier + parameter header + optional data buffer

### Channel Allocation

30 digital channels across 902–928 MHz (~867 kHz spacing) [^4]. The AVR selects
channels by reprogramming the LMX2315 PLL N counter. The channel selection
algorithm used by CyOS (e.g., whether it hops or uses fixed channels for
conversations) is unknown.

### Range

| Environment | Range |
|-------------|-------|
| Indoor | Up to 150 ft (46 m) [^4] |
| Outdoor | Up to 500 ft (152 m) [^4] |
| With retransmitters | Extended via mesh relay [^12] |

## V2 CyOS Boot Stall — Why Radio Matters

### The Problem

V2 CyOS boots to the animated Cybiko logo but never reaches the desktop [^11].
The root cause:

1. CyOS starts 6 RTOS tasks including RF-related services [^11]
2. RF driver sends init commands to AVR over SCI0 [^11]
3. SCI0 writes go nowhere — no AVR emulation exists [^9]
4. AVR never responds, RF init never completes [^11]
5. RF task object at 0x202CB2 (CyOS v1358) stays in "not ready" state [^11]
6. Desktop app depends on RF service readiness [^11]
7. `entry[0x91]` in the display handler stays 0, all rendering skipped [^11]

### Current Workaround

Our emulator intercepts `set_task_state` calls at 0x1073AC and auto-resolves
non-RF service objects [^13]. The RF object (0x202CB2) is **blacklisted** — resolving
it too early triggers hardware init that fails permanently [^13]. This gets XT
and V1 booting but V2 remains stuck.

### What Would Fix V2

Emulate the AVR side of SCI0: respond to H8S UART writes with appropriate bytes
so the RF driver believes hardware is present. The minimum viable stub only needs
to handle the init handshake — full radio functionality is not required for boot.

## Board Component Inventory

### Cybiko Classic V2 (CY6411) [^2]

| Component | Part Number | Function |
|-----------|-------------|----------|
| CPU | H8S/2241 (6432241M04FA) | Main processor |
| AVR | AT90S2313-4SC (ATMEL 0027) | Radio co-processor |
| Transceiver | RF2915 (RFMD0028, 0F540BT) | 900 MHz RF |
| PLL | LMX2315 (MP02AB, TMD) | Frequency synthesis |
| SPI Flash | AT45DB041A | CyOS storage |
| Parallel Flash | SST 39VF020 | Additional storage |
| SRAM | EliteMT LP62S2048X-70LLT | External RAM |

### Cybiko Xtreme (CY44802) [^2]

| Component | Part Number | Function |
|-----------|-------------|----------|
| CPU | H8S/2323 (HD6432323G03F) | Main processor |
| AVR | AT90S2313-4SC (ATMEL 0033) | Radio co-processor |
| USB | USBN9604-28M (NSC00A1) | PC connectivity |
| Flash | SST 39VF400A | CyOS + data |
| DRAM | Samsung K4F171612D-TL60 | External RAM |

Note: The MAME board listing for the Xtreme does not explicitly list the RF2915
or LMX2315, but FCC filing OU2CY44801 and product specs confirm RF2915-based
radio capability [^4]. The radio components may be on a daughter module or the
MAME listing is simply incomplete.

## USBN9604 USB Controller (Xtreme Only)

The Xtreme has a USBN9604 USB Full Speed Node Controller at 0x200000–0x200003.
MAME documents byte-pair writes during boot [^14]:

```
// From cybiko_m.cpp lines 398–401:
// 00/01, 00/C0, 0F/32, 0D/03, 0B/03, 09/50, 07/D6, 05/00, 04/00,
// 20/00, 23/08, 27/01, 2F/08, 2C/02, 2B/08, 28/01, 04/80, 05/02, ...
```

The USBN9604 is for USB PC connectivity, **not** radio. The Xtreme's radio still
uses AT90S2313 + RF2915 + LMX2315 via SCI0.

## Implications for Emulation

### What We Need to Emulate

We do **not** need to emulate the RF2915 or LMX2315 — they are invisible to the
H8S CPU. We need to emulate the **AVR's UART behavior** as seen on SCI0 [^3].

### Three Possible Approaches

**Approach 1: AVR-Level Emulation (most accurate)**
- Full AT90S2313 CPU emulation running actual firmware
- Pros: CyOS radio driver works unmodified
- Cons: Need AVR firmware dump, full AVR CPU emulation is complex
- The AVR firmware is only 2 KB — very feasible to disassemble [^6]

**Approach 2: SCI0 Protocol Stub (pragmatic)**
- Reverse-engineer the UART command/response protocol
- Intercept SCI0 writes, generate appropriate responses
- Pros: Simpler, no AVR CPU needed
- Cons: Must discover protocol, fragile across CyOS versions

**Approach 3: CyOS API-Level Interception (highest level)**
- Hook CyOS send_packet/recv_packet functions in memory
- Route packet payloads between emulator instances
- Pros: Simplest networking code
- Cons: CyOS-version-specific, may miss edge cases

### Recommended Phased Plan

**Phase 1** — SCI0 init stub: fake RF init success over SCI0 to unblock V2 boot.
Discover the minimum handshake by disassembling V2 CyOS radio driver code.

**Phase 2** — Emulator-to-emulator networking: route frame data between instances
over TCP/UDP. Two emulators can "see" each other on the virtual radio network.

**Phase 3** — SDR bridge (future): route frames to/from a real RF2915 via GNU Radio
and a HackRF or similar SDR, allowing an emulated Cybiko to talk to a real one.

### Networking Architecture

```
Emulator A                              Emulator B
+-----------+                           +-----------+
| CyOS      |                           | CyOS      |
|   |       |                           |   |       |
| SCI0 TDR  |                           | SCI0 TDR  |
|   |       |                           |   |       |
| AVR Stub  |                           | AVR Stub  |
|   |       |                           |   |       |
| Network   |<-- TCP/UDP/Multicast -->  | Network   |
| Bridge    |                           | Bridge    |
+-----------+                           +-----------+
```

## SCI0 Protocol Analysis (Captured from Emulator)

### Methodology

Captured SCI0 TDR hex output from headless emulator runs using the `[SCI0-TX]` log
added in the emulator's `AddressBus.java`. Each byte written to SCI0 TDR (0xFFFF7B)
is logged. The emulator currently provides no SCI0 RDR response (SSR always returns
`TDRE|TEND = 0x84`, never `RDRF`), so CyOS sends commands but never receives replies.

### Captured Traffic by Machine and CyOS Version

#### XT (CyOS v1508) — No SCI0 Traffic

```
(silence — zero bytes written to SCI0 TDR during 30+ seconds of headless boot)
```

XT CyOS v1508 does **not** write to SCI0 at any point during boot, even after
reaching the "Congratulations" welcome screen or the desktop home screen (with
NVRAM). The XT's radio driver either:
- Uses a completely different communication mechanism, or
- Does not attempt radio init until explicitly triggered by user action, or
- Has been removed/disabled in v1508 (a late Xtreme-specific firmware)

This explains why XT boots successfully without any radio emulation [^11].

#### V1 Classic (CyOS v1246) — Init Commands Only

```
Frame ~60-120:  01 02 02 01 03 00    (6 bytes, one burst)
```

V1 sends two 3-byte commands during early CyOS init (after SPI flash loading, before
the animated Cybiko logo). After sending these commands, V1 CyOS does **not** wait
for a response and proceeds to boot fully. V1 does not gate boot on RF readiness.

#### V2 (CyOS v1358) — Two Init Commands, Then Stops

```
Frame ~0-60:    01 04 00             (3 bytes)
Frame ~1080:    01 02 02             (3 bytes)
(no further SCI0 traffic, even after 60 seconds)
```

V2 v1358 sends the first command (`01 04 00`) during very early boot (before frame 60,
during SPI flash loading). The second command (`01 02 02`) comes much later at frame
~1080, after the system transitions from SPI loading to CyOS init (vram hash changes
from `4ACC524E` to `C0DBEF72` = Cybiko logo). After these two commands, v1358 gives up
and enters the main loop where the service stub keeps it running but stuck at the logo.

#### V2 (CyOS v1357) — Single Command, Stalls Early

```
Frame ~0-60:    01 04 00             (3 bytes only)
(no further SCI0 traffic)
```

V2 v1357 sends only the first init command and stalls earlier than v1358. The second
command (`01 02 02`) never appears, suggesting v1357 blocks waiting for a response to
`01 04 00` before proceeding.

#### V2 (CyOS v1355) — Init + Periodic Polling

```
Frame ~0-60:    01 04 00             (3 bytes)
Frame ~120-180: 01 02 02             (3 bytes)
Frame ~420:     30 00                (2 bytes — repeats every ~300 frames / 5 seconds)
Frame ~780:     30 00
Frame ~1140:    30 00
...             30 00                (continues indefinitely)
```

V2 v1355 shows the most complete picture. After the two init commands, it enters a
**periodic polling loop** sending `30 00` every ~5 seconds (correlated with timer8_1
interrupts, intervals of ~300 frames). This `30 00` pattern likely represents a
"status query" or "are you there?" heartbeat to the AVR co-processor.

### Command Format Analysis

All observed SCI0 transmissions follow a pattern:

```
Byte 0: Command type / packet header
Byte 1: Sub-command or parameter
Byte 2: Data / parameter (when present)
```

| Hex Bytes | Seen In | Interpretation |
|-----------|---------|----------------|
| `01 04 00` | V2 (all versions) | Init command #1 — possibly "reset" or "query firmware version" |
| `01 02 02` | V1 v1246, V2 v1358, V2 v1355 | Init command #2 — possibly "configure" or "set mode" |
| `01 03 00` | V1 v1246 only | Init command #3 — V1-specific, possibly "set channel" or "enable RX" |
| `30 00` | V2 v1355 only | Periodic poll — `0x30` = ASCII '0', possibly status query |

**Observations:**
- The `01` prefix byte appears in all init commands — likely a command/packet marker
- V1 sends `01 02 02` before `01 03 00`; V2 sends `01 04 00` before `01 02 02`
- The different ordering suggests V1 and V2 have slightly different radio drivers
- `01 02 02` is shared between V1 and V2, suggesting a common "configure" command
- `01 04 00` is V2-only and comes first — may be a V2-specific init step
- `30 00` polling only appears in the oldest V2 CyOS (v1355) — newer versions
  may have removed the retry loop or changed the timeout behavior
- XT v1508 has no radio init at all — may have moved to a different architecture

### Current SCI0 Handling in Emulator

```
SCI0 SSR (0xFFFF7C) read:  always returns 0x84 (TDRE=1, TEND=1, RDRF=0)
SCI0 RDR (0xFFFF7D) read:  returns 0x00 (default on-chip RAM value)
SCI0 TDR (0xFFFF7B) write: logged to sci0TdrLog, appended to sciOutput[0]
```

CyOS writes commands to TDR but never sees RDRF set, so it never reads a response
from RDR. This is why V2 RF init stalls — the driver sends `01 04 00` and waits
for a reply that never comes.

### Dump Files in ROM Directory (Not Cybiko-Related)

The files `dump1.bin` (4096 bytes) and `dump5.bin` (4096 bytes) found in the
`roms/cybikov2/` directory are **NOT** Cybiko AVR firmware. They are firmware dumps
for the Soviet IE15 terminal, an entirely unrelated MAME-emulated machine:

```
dump1.bin: SHA1 = 5ac4159fbb1c3b81445605e26cd97a713ae12b5f  → IE15 5-chip firmware
dump5.bin: SHA1 = 2b72dc0594e38a528400cd25aed0c47e0c432895  → IE15 6-chip firmware
```

These match `src/devices/machine/ie15.cpp` in MAME source (line 689–691). They ended
up in the Cybiko ROM directory because the ROM collection was extracted from a combined
MAME ROM pack and the filenames collided. Similarly, `chargen-15ie.bin` (2048 bytes)
is the IE15 character generator ROM (SHA1 matches ie15.cpp line 696).

**No AT90S2313 AVR firmware dump exists in any available ROM files.** The AVR firmware
was never dumped by the MAME community. This is expected — the AT90S2313's lock bits
may have been set to prevent readout.

### Recommendations for RadioCoProcessor Implementation

Based on the captured protocol data:

1. **Minimum viable stub**: Respond to `01 04 00` with an ACK byte on SCI0 RDR
   (set RDRF in SSR). This should unblock V2 v1357 which stalls on the first command.

2. **Init sequence**: Handle both `01 04 00` and `01 02 02` with appropriate responses.
   The response format is unknown but likely mirrors the command format (3-byte
   packets with a status/ACK header).

3. **Polling response**: For v1355's `30 00` heartbeat, respond with a status byte
   indicating "radio present, no data pending."

4. **XT compatibility**: XT v1508 doesn't use SCI0 at all, so the RadioCoProcessor
   can be safely wired without affecting XT boot.

5. **Protocol discovery**: To determine the correct response bytes, disassemble the
   V2 CyOS radio driver code around the SCI0 access patterns. The driver code
   should contain the expected response values.

## Open Questions

1. ~~**SCI0 UART Protocol** — The exact byte-level protocol between H8S and AVR is
   completely undocumented. This is the single biggest blocker [^3].~~
   **Partially resolved** — init command sequences captured (see above). Response
   format still unknown; requires CyOS radio driver disassembly.

2. **AVR Firmware Availability** — The AT90S2313 firmware is **not** in any available
   ROM dumps. The dump files in `roms/cybikov2/` are unrelated IE15 firmware [^6].
   The AVR lock bits were likely set to prevent readout.

3. ~~**V2 vs XT Boot Dependency** — V2 CyOS blocks on RF init; XT boots without it.~~
   **Resolved** — XT CyOS v1508 does not write to SCI0 at all during boot. V1 CyOS
   v1246 writes init commands but does not block on responses. V2 CyOS (all versions)
   writes init commands and blocks waiting for responses.

4. **Channel Selection Algorithm** — How CyOS picks channels (fixed assignment?
   frequency hopping?) is unknown [^4].

5. **Packet Format Details** — Beyond 50/200 byte frames with Manchester encoding
   and sync bits, the header/payload/checksum structure is unknown [^4].

6. **V2 CyOS Version Differences** — v1355 sends more SCI0 traffic (periodic `30 00`
   polling) than v1357 or v1358. Different versions may need different stub responses.

7. **Response Format** — What bytes should the RadioCoProcessor return on SCI0 RDR
   after receiving each init command? Requires disassembly of the radio driver.

## What Would Unlock Progress

- ~~**Logic analyzer** on SCI0 lines of a real Cybiko during boot/messaging~~ —
  emulator SCI0 logging now captures the H8S→AVR direction; AVR→H8S direction
  requires stub implementation with trial-and-error or CyOS disassembly
- **SDR capture** of RF traffic between two Cybikos — reveals air interface protocol,
  modulation parameters, channel usage patterns
- ~~**AT90S2313 firmware dump**~~ — confirmed not available in any ROM files
- **V2 CyOS radio driver disassembly** — use H8SDisasm on the decompressed CyOS
  image around the SCI0 access patterns (addresses near 0x107xxx in V2 RAM)
- ~~**Different V2 ROM versions** (v1355, v1357, v1358) — some may be less aggressive
  about blocking boot on RF readiness~~ — **tested**: all three block, v1355 most
  verbose (periodic polling), v1357 most aggressive (stalls on first command)

---

## Footnotes

[^1]: RF2915 datasheet, RFMD (now Qorvo). 18-page PDF, 386 KB. Available at
[AllDatasheet](https://www.alldatasheet.com/datasheet-pdf/pdf/35809/RFMD/RF2915.html).
Covers pinout, electrical specs, block diagram, application circuit.

[^2]: MAME source `src/mame/cybiko/cybiko.cpp` lines 27–47. Board component
listings for Cybiko Classic V2 (CY6411) and Cybiko Xtreme (CY44802) from PCB
inspection comments by MAME developers. AT90S2313 listed at line 32 (V2) and
line 44 (XT). RF2915 at line 34, LMX2315 at line 36.

[^3]: DBZoo Cybiko Firmware page, [dbzoo.com/cybiko/firmware](http://www.dbzoo.com/cybiko/firmware).
Documents AVR-to-H8S UART connection on SCI0, 53,333 baud rate, AVR clock speed
(4 MHz), in-system programming via RST/MOSI/MISO, SCK pull-up resistor value.
Primary community source for radio co-processor architecture.

[^4]: FCC filing OU2CY44801 (Cybiko Xtreme). Technical description document
169162, available at [fcc.report](https://fcc.report/FCC-ID/OU2CY44801/169162.pdf).
Contains RF block diagram showing three-chip architecture, operating frequencies
(902–928 MHz US, 868–870 MHz EU), 30 channel allocation, 19,200 bps data rate,
50/200 byte frame sizes, Manchester encoding, indoor/outdoor range specs.
See also Classic filing [OU2CY6411](https://fccid.io/OU2CY6411).

[^5]: LMX2315 datasheet/handbook, National Semiconductor (now TI). Available at
[Archive.org](https://archive.org/stream/manuallib-id-2668612/2668612_djvu.txt).
Covers 19-bit register structure, R counter, N counter, prescaler, frequency
formula, 3-wire serial interface timing, control pins.

[^6]: AT90S2313 datasheet, Atmel (now Microchip). Standard AVR data: 2 KB flash,
128 bytes SRAM, 128 bytes EEPROM, 1 UART, 15 I/O pins, 2 timers. Widely
available. The small flash size (2 KB = ~1000 instructions) makes firmware
disassembly very tractable.

[^7]: MAME source `src/devices/cpu/h8/h8s2319.cpp` lines 174–180. SCI0 register
address mapping for the H8S/2319 base class (parent of H8S/2241 and H8S/2323).
Defines SMR0 through SCMR0 at 0xFFFF78–0xFFFF7E.

[^8]: MAME source `src/devices/cpu/h8/h8_sci.h` lines 81–106. SSR bit mask
definitions (TDRE=0x80, RDRF=0x40, ORER=0x20, FER=0x10, PER=0x08, TEND=0x04,
MPB=0x02, MPBT=0x01). Also defines SMR and SCR bit masks.

[^9]: MAME source `src/mame/cybiko/cybiko.cpp` lines 424–425 (V1 SCI1→flash),
447–448 (V2 SCI1→flash). SCI0 is notably absent from all three machine config
functions: `cybikov1()` (lines 416–430), `cybikov2()` (lines 432–460),
`cybikoxt()` (lines 462–485). The `h8s2319.cpp` line 303 defines SCI0 in the
CPU but the Cybiko driver never wires it.

[^10]: MAME source `src/mame/cybiko/cybiko.cpp` lines 366–367
(`cybikov1_debug_serial`), 480–481 (`cybikoxt`). SCI2 wired to RS232 debug port
on all variants.

[^11]: This project's `docs/v2-investigation.md`. Root cause analysis of V2 boot
stall. RF task object at 0x202CB2 (CyOS v1358), `entry[0x91]` display flag,
service dependency chain. Also `CybikoEmulator.java` lines 43–56 for the V2
service stub constants.

[^12]: Cybiko patent US20020122410A1, "Method and apparatus for creating ad-hoc
network." Available at [Google Patents](https://patents.google.com/patent/US20020122410A1/en).
Describes CYRF mesh protocol: CyID addressing, channel allocation, retransmitter
relay, packet confirmation, message format (identifier + parameter header + data).
Claims up to 3,000 devices per network.

[^13]: This project's `emulator/src/main/java/com/github/daberkow/CybikoEmulator.java`
lines 43–56 (V2 stub constants) and lines 227–241 (set_task_state intercept
logic). RF object 0x202CB2 blacklisted to prevent premature resolution.

[^14]: MAME source `src/mame/cybiko/cybiko_m.cpp` lines 398–401. Comment block
documenting USBN9604 register write sequence observed during Xtreme boot.
Also `src/mame/cybiko/cybiko.cpp` line 46 listing USBN9604-28M on XT board.
USBN9604 datasheet available from [TI](https://www.ti.com/lit/ds/symlink/usbn9604.pdf).

[^15]: DBZoo Xtreme Hardware page, [dbzoo.com/cybiko/extremehardware](http://www.dbzoo.com/cybiko/extremehardware).
Component identification and datasheet links for the Xtreme board.

[^16]: SCI0 protocol analysis performed 2026-03-01 using emulator's `AddressBus.java`
SCI0 TDR hex logging. Captured from headless runs of V1 (cyrom112+flash_v1246),
V2 (cyrom117+cyos_v1358/v1357/v1355+flash), and XT (cyrom150+cyos_v1508).
IE15 ROM identification via SHA1 comparison with MAME `src/devices/machine/ie15.cpp`.
