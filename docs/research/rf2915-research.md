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
| SCI0 | AVR radio (53.3 KBaud) [^3] | Not used for radio |
| SCI1 | AT45DB041 SPI flash [^9] | Not connected (memory-mapped flash) |
| SCI2 | Debug serial (57600) [^10] | AVR radio (polled I/O at boot, TXI2-driven for features) |

**Key discovery:** XT CyOS v1508 uses **SCI2** for radio communication, not SCI0. The
channel init command (`01 02 02`) is sent via SCI2 (0xFFFF88–0xFFFF8E) during boot
using polled I/O (SCR=0x70 = RIE+TE+RE). When a radio feature is opened (e.g., Chat),
CyOS switches to interrupt-driven mode with TIE enabled (SCR bit 7) and TXI2 (vector 90)
driving a state machine. The RXI2 handler (vector 89) processes response bytes and
advances through 8 protocol states. V1/V2 continue to use SCI0 with polled I/O.

**MAME note:** MAME wires SCI2 to a debug serial port (RS232). In reality, XT CyOS
uses SCI2 for the radio co-processor, not debug output. This is why MAME has no
radio emulation for the XT.

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

## XT SCI2 Interrupt-Driven Radio Protocol

### SCI2 Register Map (XT)

| Address | Register | Description |
|---------|----------|-------------|
| 0xFFFF88 | SMR2 | Serial Mode Register (async, 8N1) |
| 0xFFFF89 | BRR2 | Bit Rate Register |
| 0xFFFF8A | SCR2 | Serial Control Register |
| 0xFFFF8B | TDR2 | Transmit Data Register |
| 0xFFFF8C | SSR2 | Serial Status Register |
| 0xFFFF8D | RDR2 | Receive Data Register |
| 0xFFFF8E | SCMR2 | Smart Card Mode Register |

### SCI2 Interrupt Vectors (from MAME swx00.cpp)

| Vector | Name | Description |
|--------|------|-------------|
| 88 | ERI2 | SCI2 Receive Error |
| 89 | RXI2 | SCI2 Receive Data Register Full |
| 90 | TXI2 | SCI2 Transmit Data Register Empty |
| 91 | TEI2 | SCI2 Transmit End |

### Boot-Time Polled I/O Sequence

During boot (~frame 240), CyOS initializes SCI2 and sends the channel config command
using polled I/O (no interrupts):

```
W SMR=0x00   — async 8N1
W BRR=0x0F   — baud rate
W SCR=0x70   — RIE(6)+TE(5)+RE(4), no TIE(7)
W TDR=0x01   — command header byte
R SSR=0x44   — check TDRE (bit 7 clear = busy, RDRF set from response)
W SSR=0x44   — clear RDRF flag
W TDR=0x02   — command byte (set channel)
W TDR=0x02   — param byte (channel 2)
R SSR=0xC4   — RDRF=1, read final response
W SCR=0x00   — disable SCI2
```

### TDRE State Modeling

The emulator models SCI2 TDRE (Transmit Data Register Empty) to support both
polled and interrupt-driven modes:

1. **TDRE starts high** (TDR is empty and ready for data)
2. **TDR write clears TDRE** (byte is "transmitting")
3. **After ~32 CPU cycles, TDRE goes high** (byte transmitted)
4. **If TIE is set in SCR, TXI2 (vector 90) fires** when TDRE restores

This cycle allows CyOS's TXI2 interrupt handler to send one byte per invocation,
read the response from RDR, and advance the radio protocol state machine.

### Variable-Length Command Framing

Commands sent to the AVR co-processor have **variable length** determined by the first
byte. This was discovered through live SCI2-TX analysis with Chat open:

| First Byte | Length | Commands |
|------------|--------|----------|
| `0x01` | 3 bytes | Init, channel, config |
| `0x30` | 2 bytes | Poll for received data |
| `0xCF` | 2 bytes | Scan/beacon (peer discovery) |
| All others | 2 bytes | Default |

**Critical framing detail:** A fixed 3-byte assumption causes total framing corruption.
The sequence `01 02 04 30 00 CF 00 30 00` contains four commands (`01 02 04`, `30 00`,
`CF 00`, `30 00`), but a 3-byte parser reads it as `01 02 04`, `30 00 CF`, `00 30 00` —
every command after the first `30` is misaligned.

### Per-Byte Response Model (SCI0 / V1/V2)

On SCI0, which behaves like full-duplex SPI, every TDR write generates a synchronous
response byte:

| Byte position in 3-byte command | Response |
|---------------------------------|----------|
| Byte 1 (header) | 0xFF (idle) |
| Byte 2 (command) | 0xFF (idle) |
| Byte 3 (param) | Command-specific response |

3-byte command responses (on 3rd byte):
- `01 04 00` (init): 0x00 (ACK)
- `01 02 NN` (set channel): 0x00 (ACK)
- `01 03 00` (V1 init): 0x00 (ACK)

2-byte commands (`30 XX`, `CF XX`) do **not** queue immediate responses. Their response
comes asynchronously via the DTC completion path (see below).

### SCI2 Asynchronous Response Model (XT)

On SCI2, TX and RX are independent UART paths — **not** full-duplex SPI. Responses are
delivered asynchronously via RXI2 (vector 89) interrupts, not consumed synchronously
during TDR writes:

1. CyOS writes command bytes to TDR2 (0xFFFF8B)
2. `RadioCoProcessor.receive()` accumulates bytes into a command buffer
3. When a 3-byte command completes, the response is queued to `rxQueue`
4. `tickSci2()` detects queued data, loads it into RDR2, sets RDRF
5. If RIE is set in SCR2, RXI2 (vector 89) fires
6. CyOS RXI2 handler reads RDR2 and advances its state machine

For 2-byte commands (poll/scan), no immediate response is queued. Instead, CyOS follows
the 2-byte header with a **TX DTC bulk transfer** (51 or 201 bytes of packet data). The
response comes from the DTC completion path.

### DTC Bulk Transfer (DTCERF at 0xFFFF35)

CyOS uses DTC for bulk radio packet TX/RX on SCI2. **DTCERF bit mapping** (corrected
via CyOS disassembly — the H8S manual bit numbering differs from the physical wiring):

| Bit | Source | Description |
|-----|--------|-------------|
| 7 | RXI2 | SCI2 Receive Data Register Full |
| 6 | TXI2 | SCI2 Transmit Data Register Empty |
| 5 | — | (not used by CyOS radio) |
| 4 | TEI2 | SCI2 Transmit End |

**Critical correction:** Initial implementation had bit 6=RXI2, bit 5=TXI2 which caused
TX DTC to never fire. CyOS writes `DTCERF |= 0x40` to enable TXI2 DTC and `DTCERF |=
0x80` to enable RXI2 DTC. This was confirmed by disassembling the radio TX function at
0x49B9B4 in CyOS v1508.

DTC register info blocks in on-chip RAM:
- RX block at 0xFFFBE8: source=0xFFFF8D (SCI2 RDR), dest=RAM buffer
- TX block at 0xFFFBF4: source=RAM buffer, dest=0xFFFF8B (SCI2 TDR)

### TX DTC Protocol Flow

When CyOS sends a radio packet (poll, scan, or data transmission), the full sequence is:

```
1. CyOS sends 2-byte command header (e.g., 30 00 or CF 00) via TDR writes
2. CyOS sets up TX DTC: source=RAM buffer, dest=TDR2, count=51 or 201 bytes
3. CyOS enables TXI2 DTC (DTCERF |= 0x40) and TIE (SCR bit 7)
4. DTC autonomously transfers packet data bytes to TDR, one per TXI2 interrupt
5. When DTC count reaches 0, TX DTC completes
6. Emulator: executes entire DTC transfer immediately, stores indicator (0x32/0xC8)
7. TXI2 fires — CyOS TXI2 ISR: state 2→6, clears DTCERF bit 6 (BCLR #6)
8. DTCERF handler delivers deferred response: 0x03 (packet ACK) + indicator
9. RXI2 delivers 0x03 — state 6 completion handler → state 1
10. RXI2 delivers indicator (0x32/0xC8) — state 1 sets up RX DTC
```

**Critical: two-byte deferred delivery.** The AVR sends 0x03 (packet ACK) THEN the frame
size indicator (0x32 or 0xC8) after TX DTC completion. Both must be delivered after the
TXI2 ISR transitions CyOS from state 2→6. Without 0x03, the indicator is consumed by
state 6 instead of state 1, and CyOS never sets up the RX DTC for the received frame.

The 51-byte transfer corresponds to a poll command (0x30), and 201-byte transfer to a
scan/beacon command (0xCF). These are raw packet data, **not** radio commands — the DTC
bytes should NOT be fed through the command parser.

### CyOS RXI2 State Machine (XT, CyOS v1508)

The RXI2 interrupt handler at 0x49BBF8 implements an 8-state protocol state machine.
State is stored at `radioObj+0x335A`. The handler reads a byte from RDR2, then dispatches
based on state via a jump table at 0x4823FC:

| State | Jump Target | Expected Byte | Action |
|-------|------------|---------------|--------|
| 1 | 0x49BC7A | 0x32 or 0xC8 | Frame size indicator: 0x32=50B (poll beacon), 0xC8=200B (scan/chat or no data) |
| 2 | 0x49BC2E | 0x13 | TX DTC done ACK → state 3 |
| 3 | 0x49BC54 | 0x11 | Ready → state 2 (re-enable DTC) |
| 4 | 0x49BC92 | any | Handler with arg=0 |
| 5 | 0x49BC9A | any | Handler with arg=1 |
| 6 | 0x49BC70 | 0x03 | Packet received ACK → completion handler |
| 7 | 0x49BCA2 | 0x13 | TX DTC done → state 8 (alternate cycle) |
| 8 | 0x49BCBE | 0x11 | Ready → state 7 |

**TXI2 ISR behavior** (at ~0x49BD30): The TXI2 handler's action depends on current state:
- State 2 → transitions to state 6 (clears TIE, expects 0x03 next via RXI2)
- State 3 → clears TIE only
- State 7 → sends next byte to TDR from buffer

**Response code summary:**

| Code | Meaning | Used in States |
|------|---------|----------------|
| 0x03 | Packet received / TX complete ACK | 6 |
| 0x11 | Ready for next transfer | 3, 8 |
| 0x13 | TX DTC transfer done | 2, 7 |
| 0x32 | Data available (poll result) | 1 |
| 0xC8 | No data available (poll result) | 1 |

**Typical TX flow through states:**
```
State 2: Wait for 0x13 (TX DTC done)
  → TXI2 fires: state 2 → state 6 (TIE cleared)
  → RXI2 delivers 0x03: completion handler at 0x49C5F0
```

**Note:** The current emulator implementation queues 0x03 after TX DTC completion, which
is consumed by state 6. The 0x13/0x11 handshake in states 2/3 and 7/8 appears to be for
multi-packet transfers where DTC is re-enabled multiple times. The emulator currently
bypasses this by executing the entire DTC transfer atomically.

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

## CyOS Radio Frame Format

### TX DTC Buffer Layout

CyOS assembles outgoing radio packets in RAM and sends them via TX DTC on SCI2.
The TX DTC sends `frame_size + 1` bytes (51 for polls, 201 for scans). Captured
from SCI2-DTC hex dumps with two emulators on `--radio lan`:

#### 51-byte poll frame (0x30 command)

```
Offset  Example (hex)     Description
------  ----------------  ------------------------------------------
0-3     FF FF FF FF       RF preamble (clock sync for RF2915)
4-7     4C 80 51 A3       Sync word (constant, RF2915 config)
8       C2/C4             Channel byte (0xC0 | channel_number)
9       01                Frame type: beacon/presence
10-11   00 00 / 08 00     Flags (bit 3 set on some frames)
12-15   varies            Per-packet varying data (CRC/sequence?)
16      2A                Unknown (0x2A = 42 decimal, payload length?)
17-19   00 00 00          Padding
20+     "Dan\0own\0"      Username: overwrites fixed "unknown\0" buffer
                          (3-char name: "Dan\0" + tail "own\0")
                          (2-char name: "Sa\0" + tail "nown\0")
28      7F                Unknown
29      00                Unknown
30-49   varies            Random/encrypted data or CRC (changes every TX)
50      00                Trailing status byte (always 0x00)
```

#### 201-byte scan/chat frame (0xCF command)

```
Offset  Example (hex)     Description
------  ----------------  ------------------------------------------
0-7     FF FF FF FF       RF preamble + sync (same as poll)
        4C 80 51 A3
8       C4                Channel byte
9       20                Frame type: data/message (0x20)
10-11   00 00             Flags
12-15   varies            Per-packet varying data
16      18                Unknown
17-19   00 10 02          Unknown
20-21   00 00             Unknown
22-23   varies            Unknown
24      00                Unknown
25      25                Unknown (0x25 = '%' = 37 decimal)
26      0F                Length of message? (0x0F = 15 = len("Hi, everybody!"))
27+     "Hi, everybody!"  Chat message text (null-terminated)
27+N    00 00 ...         Zero padding to fill 200 bytes
200     00                Trailing status byte
```

### RF Header Stripping (TX side)

The first 8 bytes (preamble + sync word) are for RF2915 programming:
- **TX**: CyOS includes them so the AVR can configure the RF2915 transmitter
- **RX**: The RF2915 detects preamble/sync automatically and strips them

For emulator-to-emulator networking, we strip the 8-byte RF header and the
trailing status byte before forwarding over UDP. The UDP transport channel
is extracted from the frame content's channel byte (`payload[0] & 0x3F`),
not from `RadioCoProcessor.currentChannel`, because CyOS channel-hops and
may change channels between preparing the frame and firing the TX DTC.

### AVR RX Header (RX side)

On the receive path, the AVR prepends an 8-byte header before the RF payload
when forwarding data to the H8S via UART. This header occupies the first 8
bytes of the RX DTC buffer, with the RF payload starting at byte 8:

```
RX DTC buffer layout (50 or 200 bytes):
Offset  Size  Content
------  ----  ------------------------------------------
0-3     4     Destination peer ID or 0xFFFFFFFF (broadcast)
4-7     4     Sender's CyID (device identity from TX sync word)
8       1     Channel byte (0xC0 | channel)
9       1     Frame type byte
10+     N     Frame data (poll beacon or scan/chat payload)
```

The math confirms: TX sends 51/201 bytes (8 preamble+sync + 42/192 RF payload
+ 1 trailing). RX DTC receives 50/200 bytes. 50 - 42 = 8, 200 - 192 = 8 —
exactly the AVR header size.

CyOS's main-loop radio task (0x49ADFE) reads connObj->0x00 (bytes 0-3 of the
RX buffer) and compares against:
1. The device's own CyID at 0x4B4AC2 (loaded from flash 0x7FF818)
2. Broadcast (0xFFFFFFFF)
If neither matches, the frame is silently discarded. For peer discovery,
frames use broadcast destination (0xFFFFFFFF).

### Channel Encoding

Channel byte (offset 8 in TX buffer, offset 8 in RX DTC buffer): `0xC0 | channel`
- 0xC2 = channel 2 (CyOS default after `01 02 02`)
- 0xC4 = channel 4 (after `01 02 04`)
- CyOS alternates between channels during scanning

### Frame Types (offset 9 in TX/RX buffer)

| Byte & 0xE0 | Type | TX DTC Size | Command |
|-------------|------|-------------|---------|
| 0x00 (0x01) | Beacon/presence | 51 bytes | 0x30 (poll) |
| 0x20 | Data/message | 201 bytes | 0xCF (scan) |
| 0x40 | Connection match | varies | — |
| 0x60 | Control frame | varies | — |

### RX Delivery

CyOS uses DTC for both TX and RX on SCI2 (XT):
1. TX DTC completes → TXI2 ISR fires (vector 90, handler at 0x49BEE4)
2. TXI2 ISR: clears TIE, clears DTCERF bit 6 → deferred delivery of 0x03 + indicator
3. State 6 receives 0x03 (packet ACK) → completion handler → state 1
4. State 1 receives indicator (0x32 or 0xC8) → setup_rx_dtc
5. CyOS state 1 processes indicator: 0x32 → setup_rx_dtc(data), 0xC8 → setup_rx_dtc(null)
5. setup_rx_dtc: checks channel match (obj+0x335B vs obj+0x335C), sets up RX DTC
   - Channel match: state→4, MRA=0x20 (dest increment), count=50 (data) or 200 (null)
   - Channel mismatch: state→5, MRA=0x00 (dest fixed, discard)
6. DTCERF bit 7 set → RX DTC bulk-transfers count bytes from SCI2 RDR to RAM buffer
7. Completion RXI2 fires → state 4 handler calls frame_complete → delivers to app layer

Short TX DTC frames (e.g. 4-byte init command 01 03 00 00) are AVR commands,
not radio packets — they are not forwarded over the network.

### Username Field

The username at offset 20 (TX) / offset 20 (RX DTC buffer = 8 header + 12) uses an 8-byte fixed
buffer initialized to "unknown\0". The username overwrites from the left:
- "Dan" (3 chars): `44 61 6E 00 6F 77 6E 00` = "Dan\0own\0"
- "Sa" (2 chars): `53 61 00 6E 6F 77 6E 00` = "Sa\0nown\0"

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
(silence — zero SCI0 register accesses of any kind during 100+ seconds of emulation)
```

XT CyOS v1508 does **not** access any SCI0 register at any point during boot or
idle operation. Extended testing (6120 frames, ~100 seconds) with comprehensive
register logging (reads of SSR/RDR, writes to SMR/BRR/SCR/TDR) confirmed zero
SCI0 activity in both states:
- Fresh boot → "Congratulations" welcome screen (VRAM hash FAEDD600)
- Completed setup → Home screen with clock (VRAM hashes 6C55765A / B5EB2FA8)

**Root cause analysis (from CyOS decompression and string analysis):**

CyOS v1508 **does** contain radio driver code. Evidence from the decompressed CyOS
image at 0x4A3BF8+:
- String `"rfdriver"` at 0x483146 — radio driver service name
- String `"rcvr enable"` at 0x4823C5 — receiver enable function
- String `"Multi Channel Protocol..."` at 0x4831AC — protocol layer
- String `"My CyID is %ld 0x%08lX @%s"` at 0x4808C2 — device ID display
- String `"current channel %d"` at 0x480913 — channel management
- SCI0 TDR write (`@0xFFFF7B`) and RDR read (`@0xFFFF7D`) present in CyOS I/O
  dispatch tables at 0x4B4CF4 and 0x4B4A7A respectively

However, radio is gated behind a **parental permission** system:
- `"Communications are OFF on your Cybiko computer. You are not able to use Chat,
  Friend Finder, E-mail and play multi-player games."` at 0x4FFC5F
- `"If you want to be able to communicate wirelessly, call your parent."` at 0x4FFB55
- `"Message for parent. Your permission to use RF communication is required (now
  the communication is disabled)."` at 0x4FFC92

The `settings.dat` file in CFS (file ID 2) stores ~14KB of settings data. Three
bytes differ between fresh boot and completed setup:
- Byte 12: 0x00 → 0x01 (first-boot wizard completed flag)
- Byte 13: 0x01 → 0x00 (inverse of byte 12)
- Byte 41: 0x00 → 0x01 (possibly wireless permission, but setting it to 1 via
  the Congratulations flow does not trigger SCI0 activity)

**Conclusion:** XT CyOS v1508 defers all SCI0/radio initialization until the user
actively triggers a radio feature (Chat, Friend Finder, E-mail, multiplayer games)
through the UI. Idle operation — including the home screen — never touches SCI0.
This is a significant behavioral difference from V1/V2, which init SCI0 during
early boot. To capture XT SCI0 traffic, the emulator must run with a GUI and the
user must navigate to a radio feature.

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
   **Resolved** — init command sequences captured and response format reverse-engineered
   via CyOS disassembly. Full 8-state RXI2 state machine documented above.

2. **AVR Firmware Availability** — The AT90S2313 firmware is **not** in any available
   ROM dumps. The dump files in `roms/cybikov2/` are unrelated IE15 firmware [^6].
   The AVR lock bits were likely set to prevent readout.

3. ~~**V2 vs XT Boot Dependency** — V2 CyOS blocks on RF init; XT boots without it.~~
   **Resolved** — XT CyOS v1508 does not access SCI0 at all during boot or idle
   operation. V1 CyOS v1246 writes init commands but does not block on responses.
   V2 CyOS (all versions) writes init commands and blocks waiting for responses.

4. **Channel Selection Algorithm** — How CyOS picks channels (fixed assignment?
   frequency hopping?) is unknown [^4].

5. ~~**Packet Format Details** — Beyond 50/200 byte frames with Manchester encoding
   and sync bits, the header/payload/checksum structure is unknown [^4].~~
   **Partially resolved** — TX DTC hex dumps reveal the frame structure. See
   "CyOS Radio Frame Format" section below.

6. **V2 CyOS Version Differences** — v1355 sends more SCI0 traffic (periodic `30 00`
   polling) than v1357 or v1358. Different versions may need different stub responses.

7. ~~**Response Format** — What bytes should the RadioCoProcessor return on SCI0 RDR
   after receiving each init command? Requires disassembly of the radio driver.~~
   **Resolved** — Full response code set documented: 0x00 (ACK for 3-byte commands),
   0x03 (packet TX complete), 0x13 (DTC done), 0x11 (ready), 0x32 (50-byte frame
   available), 0xC8 (200-byte frame available or no data). 2-byte commands produce
   no immediate response; ACK comes via DTC completion path.

8. ~~**XT Radio Trigger** — XT v1508 defers radio init until a user-facing radio
   feature is opened (Chat, Friend Finder, E-mail, multiplayer).~~
   **Resolved** — XT uses SCI2 (not SCI0) for radio. Boot-time init is polled I/O
   (channel command `01 02 02`). Feature-time radio uses interrupt-driven TXI2/RXI2
   with DTC bulk transfers. Opening Chat triggers the full radio state machine with
   periodic `30 00` polls and `CF 00` scan/beacon commands.

9. ~~**RX DTC delivery** — When a remote packet arrives over the network, how does CyOS
   expect to receive it?~~
   **Resolved** — CyOS uses DTC for both TX and RX. After TX DTC completes, a frame
   size indicator (0x32=50 bytes for poll beacons, 0xC8=200 bytes for scan/chat frames
   or no data) is delivered via deferred RXI2. CyOS sets up RX DTC (DTCERF bit 7) to
   bulk-transfer the indicated number of bytes from SCI2 RDR to a RAM buffer. CyOS
   distinguishes "large frame" from "no data" by content (real data starts with channel
   byte 0xC0+, null reads are all 0xFF). Channel mismatch uses MRA=0x00 (dest fixed,
   state 5) to discard without corrupting memory.

10. **Multi-packet DTC cycles** — States 2/3 and 7/8 implement a 0x13/0x11 handshake
    that suggests DTC can be re-enabled multiple times within a single transaction.
    The emulator currently executes entire DTC transfers atomically and skips this
    handshake, which works for single-packet operations but may break for longer
    transfers.

11. **UDP multicast firewall** — Linux firewalld blocks UDP multicast delivery even
    when packets are visible in Wireshark (captured on the raw socket before
    firewall filtering). Users must open the port: `sudo firewall-cmd
    --add-port=19200/udp`. Same-host multicast also requires explicit loopback
    enable (`setLoopbackMode(false)`) and joining on all network interfaces.

## What Would Unlock Progress

- ~~**Logic analyzer** on SCI0 lines of a real Cybiko during boot/messaging~~ —
  emulator SCI0/SCI2 logging captures both directions; CyOS disassembly has revealed
  the full protocol state machine
- **SDR capture** of RF traffic between two Cybikos — reveals air interface protocol,
  modulation parameters, channel usage patterns
- ~~**AT90S2313 firmware dump**~~ — confirmed not available in any ROM files
- **V2 CyOS radio driver disassembly** — use H8SDisasm on the decompressed CyOS
  image around the SCI0 access patterns (addresses near 0x107xxx in V2 RAM)
- ~~**Different V2 ROM versions** (v1355, v1357, v1358) — some may be less aggressive
  about blocking boot on RF readiness~~ — **tested**: all three block, v1355 most
  verbose (periodic polling), v1357 most aggressive (stalls on first command)
- ~~**GUI-based XT SCI0 capture**~~ — **Resolved**: XT uses SCI2, not SCI0. Full
  protocol captured and reverse-engineered. CyOS v1508 radio code disassembled at
  0x49B9B4 (TX function), 0x49BBF8 (RXI2 handler), 0x49BD30 (TXI2 handler).
- **RX packet delivery** — implement the RX DTC path so that packets received from
  the network transport can be delivered to CyOS. Need to trace state 1 (poll result
  0x32) to understand how CyOS sets up RX DTC and expects frame data.
- **Chat protocol analysis** — capture and analyze Chat application protocol once
  packet delivery works. May need CyID assignment, peer list management, and message
  routing to be functional.

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
