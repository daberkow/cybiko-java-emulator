# Cybiko Emulator

## Project Overview
Java emulator for the Cybiko handheld computer family (Classic V1, V2, and Xtreme).
Learning project intended for eventual port to C.

## Build & Run
```bash
./gradlew build
./gradlew run --args="path/to/bootrom.bin path/to/flash.bin"
# Xtreme (default):
./gradlew run --args="cyrom150.bin cyos_v1508.bin"
# Classic V1:
./gradlew run --args="--machine v1 cyrom112.bin flash_v1246.bin"
# V2:
./gradlew run --args="--machine v2 cyrom117.bin cyos_v1357.bin flash_v1357.bin"
```

### Options
| Flag | Description |
|------|-------------|
| `--machine v1\|v2\|xt` | Select machine type (default: `xt`) |
| `--headless` | Run without GUI window |
| `--trace` | Enable instruction tracing (slow, verbose) |
| `--logging <cats>` | Log categories: cpu,radio,rtc,dma,io,status,boot,cfs,speaker,all,none (default: boot,status) |
| `--mute` | Disable audio output |
| `--nvram <file>` | Load/save persistent NVRAM (CFS filesystem + CyOS state) |
| `--app <file.app>` | Add .app file to NVRAM before booting (multiple allowed) |
| `--list-apps` | List apps in NVRAM and exit |
| `--radio <lan\|sdr>` | Enable radio (lan=UDP multicast, sdr=TCP bridge) |
| `--radio-id <n>` | Set radio device ID (default: random) |
| `--sdr-host <ip>` | SDR bridge host (default: localhost) |
| `--sdr-port <port>` | SDR bridge port (default: 19201) |

### NVRAM & App Loading
```bash
# First run: create NVRAM and load an app
./gradlew run --args="cyrom150.bin cyos_v1508.bin --nvram cybiko.nvram --app calc.app"

# Later runs: apps, clock, and CyOS settings persist automatically
./gradlew run --args="cyrom150.bin cyos_v1508.bin --nvram cybiko.nvram"

# Add more apps to existing NVRAM
./gradlew run --args="cyrom150.bin cyos_v1508.bin --nvram cybiko.nvram --app dice.app --app Calendar.app"

# List what's in the NVRAM
./gradlew run --args="cyrom150.bin cyos_v1508.bin --nvram cybiko.nvram --list-apps"

# One-off without persistence (temporary CFS image in RAM)
./gradlew run --args="cyrom150.bin cyos_v1508.bin --app calc.app"
```

The `--nvram` flag saves the entire 2MB external RAM on exit (via JVM shutdown hook),
preserving CyOS state including loaded apps, clock/date, and user settings between
sessions. If the file doesn't exist, a fresh CFS-formatted image is created.

The `--app` flag wraps raw `.app` files in proper CFS (Cybiko File System) block
format before loading into RAM. Without `--nvram`, apps are loaded into a temporary
CFS image that is lost on exit.

ROM files:
- Xtreme: `src/main/resources/cybikoxt/cyrom150.bin` + `cyos_v1508.bin` (from MAME cybikoxt.zip)
- Classic V1: `cyrom112.bin` + `flash_v1246.bin` (from MAME cybiko.zip)
- V2: `cyrom117.bin` + `cyos_v1357.bin` + `flash_v1357.bin` (from MAME cybikov2.zip)
App files: `../cybiko-archive/cybiko/cybiko/apps/` (e.g., `calc.app`, `dice/dice.app`).

### Radio Networking
```bash
# Two emulators on LAN
./gradlew run --args="cyrom150.bin cyos_v1508.bin --radio lan --radio-id 1"
./gradlew run --args="cyrom150.bin cyos_v1508.bin --radio lan --radio-id 2"

# SDR bridge (requires GNU Radio server)
./gradlew run --args="cyrom150.bin cyos_v1508.bin --radio sdr --sdr-host 192.168.1.50"

# V2 with radio (may help boot further)
./gradlew run --args="--machine v2 cyrom117.bin cyos_v1357.bin flash_v1357.bin --radio lan"
```

### Debug Features
```bash
# RAM dump: writes entire external RAM at frame 300 (after CyOS decompression)
JAVA_TOOL_OPTIONS="-Dcybiko.ramdump=/tmp/cyos_ram.bin" ./gradlew :emulator:run --args="..."

# SCI register logging: prints [SCI0-REG] and [SCI2-REG] lines to stderr showing
# all SCI0/SCI2 register reads/writes with PC addresses (radio protocol analysis)
# Also prints [SCI0-TX] and [SCI2-TX] hex dump of TDR bytes every ~2 seconds
# Both are automatic — no flags needed
```

## Hardware Reference

### Supported Machines
| Feature | V1 (Classic) | V2 | XT (Xtreme) |
|---------|-------------|----|-------------|
| CPU | H8S/2241 @ 11.06 MHz | H8S/2246 @ 11.06 MHz | H8S/2323 @ 18.43 MHz |
| On-chip RAM | 4KB @ 0xFFEC00 | 8KB @ 0xFFDC00 | 8KB @ 0xFFDC00 |
| External RAM | 512KB @ 0x200000 | 256KB @ 0x200000 | 2MB @ 0x400000 |
| LCD address | 0x600000 | 0x600000 | 0x100000 |
| Flash | SPI (AT45DB041, 528KB) | 256KB @ 0x100000 | 512KB @ 0x600000 |
| Timer16 channels | 3 | 3 | 6 |
| DMA | No | No | 4 channels |
| Boot ROM mirror | 32KB only | 32KB only | Mirrored to 0x3FFFF |
| Keyboard | 9 columns, 8-bit | 9 columns, 8-bit | 15 columns, 16-bit |
| RTC SDA pin | Port F bit 0 | Port F bit 0 | Port F bit 6 |
| Power status | Port 1: 0x08 | Port 1: 0x08 | Port A: 0xC0 |

### CPU: Hitachi H8S/2000 Series
- H8S/2000 series (H8/300 base + H8/300H 32-bit + H8S extensions)
- 8x 32-bit general purpose registers (ER0-ER7)
  - Upper 16-bit halves: E0-E7
  - Lower 16-bit halves: R0-R7
  - Lower bytes split: R0H/R0L through R7H/R7L
- PC: 24-bit program counter
- CCR: Condition Code Register (I UI H U N Z V C)
- EXR: Extended control register (interrupt/trace)
- Big-endian byte order

### Memory Map (Xtreme)
| Address Range     | Size  | Device                          |
|-------------------|-------|---------------------------------|
| 0x000000-0x007FFF | 32KB  | Boot ROM (mirrored to 0x03FFFF) |
| 0x100000-0x100001 | 2B    | LCD controller (HD66421)        |
| 0x200000-0x200003 | 4B    | USB controller (USBN9604)       |
| 0x400000-0x5FFFFF | 2MB   | External RAM                    |
| 0x600000-0x67FFFF | 512KB | Flash ROM (SST 39VF400A)        |
| 0xE00000-0xEFFFFF | 1MB   | Keyboard matrix                 |
| 0xFFDC00-0xFFFFFF | ~9KB  | On-chip RAM & I/O registers     |

### Memory Map (V1 Classic)
| Address Range     | Size  | Device                          |
|-------------------|-------|---------------------------------|
| 0x000000-0x007FFF | 32KB  | Boot ROM (no mirroring)         |
| 0x200000-0x27FFFF | 512KB | External RAM                    |
| 0x600000-0x600001 | 2B    | LCD controller (HD66421)        |
| 0xE00000-0xEFFFFF | 1MB   | Keyboard matrix                 |
| 0xFFEC00-0xFFFFFF | ~5KB  | On-chip RAM & I/O registers     |
Flash ROM accessed via SPI (AT45DB041), not memory-mapped.

### LCD: Hitachi HD66421
- 160x100 pixels, 2-bit grayscale (4 shades)
- VRAM: 4000 bytes (4 pixels/byte, 2 bits each)
- 18 registers (0x00-0x11)
- Interface: register index port + data port at 0x100000-0x100001
- Drawing order: bottom-to-top, left-to-right

### CCR Flag Layout (bits 7-0): I UI H U N Z V C
- I: Interrupt mask
- UI: User interrupt mask
- H: Half-carry
- U: User bit
- N: Negative
- Z: Zero
- V: Overflow
- C: Carry

### Timer16 (TPU) Per-Channel Clock Sources
Clock source mapping varies per channel (from MAME h8s2319.cpp):
| TPSC | Ch0   | Ch1   | Ch2   | Ch3    | Ch4    | Ch5   |
|------|-------|-------|-------|--------|--------|-------|
| 0    | /1    | /1    | /1    | /1     | /1     | /1    |
| 1    | /4    | /4    | /4    | /4     | /4     | /4    |
| 2    | /16   | /16   | /16   | /16    | /16    | /16   |
| 3    | /64   | /64   | /64   | /64    | /64    | /64   |
| 4    | extA  | extA  | extA  | extA   | extA   | extA  |
| 5    | extB  | extB  | extB  | /1024  | extC   | extC  |
| 6    | extC  | /256  | extC  | /256   | /1024  | /256  |
| 7    | extD  | chain | /1024 | /4096  | chain  | extD  |

## Architecture
- `CybikoEmulator` - Main emulator orchestrator with nanoTime-based frame timing (60fps).
  Takes `MachineConfig` to configure for V1/V2/XT. Inner loop: cycles/frame from config.
  Skips disabled timer ticks via per-frame isRunning() cache.
- `MachineConfig` - Machine configuration enum/data class for V1/V2/XT hardware variants.
  Contains all machine-specific parameters: clock speed, memory sizes/addresses, timer
  channels, DMA, SPI flash, RTC pin wiring, power status, keyboard columns.
- `AddressBus` - Memory-mapped I/O routing, DMA controller (XT only), DTC simulation
  (V1 SPI flash), keyboard matrix, speaker. Parameterized by MachineConfig for address
  decoding and peripheral wiring.
- `AT45DB041Flash` - SPI DataFlash emulation for V1 (2048 pages x 264 bytes = 528KB).
  State machine: receives commands via SCI1, outputs page data. Commands: status read
  (0x57), page read (0x52), program through buffer (0x82), buffer compare (0x60).
- `H8SCpu` - CPU emulation core (~150 instructions). Pending interrupts use sorted int[]
  (not TreeSet) for low overhead. Debug profiling gated behind `debug` flag.
- `H8STimer8` - 8-bit timer with compare match and overflow interrupts. Lean tick() path.
- `H8STimer16` - 16-bit timer (TPU) with per-channel clock source tables and TIOR
  output compare pin emulation (initial level, set/clear/toggle on match, PWM reset)
- `Memory` - Simple byte-array backed memory
- `HD66421Lcd` - LCD controller emulation. Reuses framebuffer array (no per-frame allocation).
- `PCF8593Rtc` - Real-time clock with I2C bit-bang protocol (matches MAME pcf8593.cpp)
- `CfsImage` - CFS (Cybiko File System) image builder/reader (matches MAME cybikoxt.cpp)
- `SpeakerOutput` - 1-bit speaker audio via javax.sound.sampled (48kHz, 8-bit mono).
  Transition-based waveform reconstruction: records level changes with cycle timestamps
  during each frame, then resamples to audio rate for accurate PWM reproduction.
  Driven by Timer16 ch1 TIOCB1 output compare callback (not Port 1 DR polling).
- `FrameBufferRenderer` / `SwingRenderer` / `ConsoleRenderer` - Display
- `SwingRenderer` - Swing GUI with keyboard input. Bulk setRGB for rendering. Takes
  MachineConfig to select XT (15-col, Fn+letter numbers) or V1 (9-col, dedicated numbers)
  keyboard layout. Three input mechanisms: (1) **Fn combo queue** — packs col:bit,
  processes one combo at a time with Fn press→delay→letter→hold→release cycle;
  parameterized fnCol/fnBit per machine (XT: col 7/0x8000, V1: col 1/0x80).
  (2) **Lazy Shift** — PC Shift sets pcShiftHeld flag but doesn't enter Cybiko matrix
  until paired with a letter key; prevents Shift+1→^ when user wants !.
  (3) **Shift combo queue** — for keys needing Cybiko Shift (^, ~, |, \); presses
  Shift first with 4-frame delay then key. Minimum key hold (3 frames) for all keys.
  Keyboard probe mode (F12) for finding unknown matrix positions.
- `RadioCoProcessor` - AVR radio co-processor stub (AT90S2313). Emulates SCI UART
  protocol with the H8S CPU. Variable-length command protocol: 0x01=3 bytes (init,
  channel, config), 0x30/0xCF=2 bytes (poll, scan), all others=2 bytes. Two call paths:
  `transfer()` for SCI0 full-duplex (V1/V2), `receive()` for SCI2 async UART (XT).
  3-byte commands queue ACK (0x00) responses; 2-byte commands produce no immediate
  response (ACK comes via DTC completion path). Two frame delivery paths:
  **(1) Synchronous (TX DTC-driven):** `completeTxDtc()` returns frame size
  indicator: 0x32 (50) for small frames, 0xC8 (200) for large frames or no data.
  Waits up to 15ms for UDP frames. Poll (0x30) delivers small beacons only;
  scan (0xCF) delivers any size. Short TX DTC (≤9 bytes) skips completeTxDtc()
  to avoid resetting rxFrameReady and blocking. After TX DTC, AddressBus defers
  delivery of TWO bytes via DTCERF bit 6 clear: 0x03 (packet ACK for state
  6→completion→state 1) then the indicator (for state 1→RX DTC setup).
  **(2) Async (autonomous):** `tryAsyncDelivery()` injects indicator into rxQueue
  when CyOS radio state == 1 (idle) and frames are pending. Checked every 512
  CPU cycles in tickSci2(). Uses `asyncRxPending` flag (separate from rxFrameReady)
  so concurrent completeTxDtc() resets don't lose the delivery. Delivers both
  small (0x32) and large (0xC8) frames. Frame data delivered via RX DTC:
  CyOS sets DTCERF bit 7 after receiving indicator, and AddressBus bulk-transfers
  50/200 bytes to a RAM buffer via `prepareRxFrame()`, which prepends an 8-byte
  AVR header (4B broadcast 0xFFFFFFFF + 4B sender CyID) before the RF payload
  so CyOS frame processing finds the destination ID at offset 0 and sender
  identity at offset 4. TX DTC data has 8-byte RF header (4B preamble + 4B CyID)
  stripped before forwarding to peer emulators (RF2915 strips these on receive).
  The CyID at TX bytes 4-7 is the sender's device identity; it equals the
  transport device ID because `patchCyId()` overwrites flash CyID with radio-id
  at startup. Received frame queue cap: 4 (handles CyOS 3x retransmit bursts).
  Connected via SCI0 (V1/V2) or SCI2 (XT)
  with TXI2/RXI2 interrupt support. Both TX DTC (DTCERF bit 6) and RX DTC (bit 7)
  are handled by `executeSci2Dtc()`.
- `RadioTransport` - Interface for radio network layer. Implementations:
  `UdpMulticastTransport` (LAN via multicast 239.0.0.42:19200),
  `SdrTransport` (TCP bridge to GNU Radio on localhost:19201).
- `UdpMulticastTransport` - UDP multicast for LAN-based radio. Wire format:
  [4B device ID][1B channel][payload]. TTL=1, daemon listener thread, self-filtering.
- `SdrTransport` - TCP bridge client for GNU Radio SDR integration. Wire format:
  [2B length][1B channel][payload]. Connects to configurable host:port.

## MAME Reference
Hardware details derived from MAME source at `../mame/`.

### Key MAME Source Files
- `src/devices/cpu/h8/h8.lst` - Instruction definitions with mask/match patterns and microcode
- `src/devices/cpu/h8/h8s2319.cpp` - H8S/2319-2323 peripheral register map (I/O addresses)
- `src/devices/video/hd66421.cpp` / `.h` - HD66421 LCD controller reference
- `src/mame/cybiko/cybiko.cpp` - Cybiko machine driver (memory map, peripherals, keyboard matrix)
- `src/devices/cpu/h8/h8_sci.h` - Serial Communication Interface register definitions
- `src/devices/cpu/h8/h8_timer16.cpp` / `.h` - Timer16 (TPU) implementation
- `src/devices/machine/pcf8593.cpp` / `.h` - PCF8593 RTC with I2C protocol

### H8S Instruction Encoding Learnings (from MAME h8.lst)
The instruction set uses a mask/match system. Key patterns:

**Register field conventions (CRITICAL):**
- Byte registers (4-bit): 0-7 = R0H-R7H, 8-F = R0L-R7L
- Word registers (4-bit): 0-7 = R0-R7 (lower halves), 8-F = E0-E7 (upper halves)
- Long registers (3-bit): 0-7 = ER0-ER7
- In MAME notation: `r32h` = bits 6-4, `r32l` = bits 2-0, `r8h` = bits 6-4, `r8l` = bits 2-0

**MOV indirect/displacement/pre-dec/post-inc encoding:**
- Bits 6-4 ALWAYS = address register (the one holding the memory address)
- Bits 2-0 ALWAYS = data register (the one holding the value)
- Bit 7 = direction (0 = read from memory, 1 = write to memory)
- This applies to ALL MOV modes: @ER, @ER+, @-ER, @(disp,ER), and their 0x0100 32-bit prefixed variants

**MOV post-increment/pre-decrement aliasing (CRITICAL BUG FIX):**
- When Rs == Rd (e.g., `MOV.L @ER7+, ER7`), must use temp variable
- Post-increment: read value, increment address reg, THEN store to dest
- Pre-decrement: snapshot source value, decrement address reg, THEN write
- Without temps, the post-increment clobbers the loaded value when Rs==Rd

**Shift/rotate group (0x10-0x17):**
- 0x10: SHLL/SHAL (shift left logical/arithmetic)
- 0x11: SHLR/SHAR (shift right logical/arithmetic)
- 0x12: ROTXL/ROTL (rotate left through/without carry)
- 0x13: ROTXR/ROTR (rotate right through/without carry)
- 0x14: OR.B register, 0x15: XOR.B register, 0x16: AND.B register
- 0x17: NOT/EXTU/NEG/EXTS
- Within shift groups: subop 0=byte#1, 1=word#1, 3=long#1, 4=byte#2, 5=word#2, 7=long#2, 8+=second op set

**Prefix instructions:**
- 0x0100: MOV.L 32-bit prefix (0x69/0x6B/0x6D/0x6F become 32-bit)
- 0x0110/0x0120/0x0130: LDM.L/STM.L (load/store multiple, 2/3/4 registers)
- 0x0140/0x0141: LDC/STC for CCR/EXR with memory operands
- 0x01C0/0x01D0: MULXS (signed multiply) prefix
- 0x01F0: 32-bit logic prefix (OR.L/XOR.L/AND.L register-to-register: 01F0 64sd/65sd/66sd)
- 0x78r0: MOV.B/W with 32-bit displacement (78r0 6Axx/6Bxx disp32)

### On-Chip I/O Registers (H8S/2323)
Key registers the boot code accesses:
- 0xFFFF7C: SCI0 SSR (Serial Status Register, channel 0)
- 0xFFFF80-0xFFFF86: SCI1 registers (SMR, BRR, SCR, TDR, SSR, RDR, SCMR)
- 0xFFFF84: SCI1 SSR - boot code polls bit 7 (TDRE) waiting for transmit ready
- SSR bits: TDRE(7), RDRF(6), ORER(5), FER(4), PER(3), TEND(2), MPB(1), MPBT(0)
- We return TDRE|TEND (0x84) as default to prevent infinite polling

## Boot Sequence (Xtreme)
1. Reset vector at 0x000000 points to ~0x004F96
2. SP initialized to 0x00FFFADC (top of on-chip RAM area)
3. Boot code zeros registers, copies data from ROM to on-chip RAM
4. Initializes serial ports (writes to SCI registers, polls SSR for TDRE)
5. Tests external RAM (writes 0x1234 pattern to 0x400000 area)
6. Initializes LCD controller, draws boot splash screen (Cybiko logo + text)
7. Enters main event loop with timer-driven callbacks
8. Boot loader prints serial messages:
   - "Starting CyOS boot loader v1.5.0."
   - "Detecting DRAM interface settings... 10-bit shift."
   - "Detecting DRAM size... 2048K."
   - "Scanning flash: plain image found."
   - "Preparing to load CyOS."
9. Decompresses CyOS from flash (LZSS compressed, 118KB -> 216KB)
10. Jumps to CyOS entry point at 0x4A3BF8
11. CyOS initializes task stack, installs timer callbacks, enters main loop

## Boot Phases (VRAM CRC32 Hashes)
The STATUS log includes a `vram=` field with CRC32 of the 4000-byte LCD VRAM.
Use these hashes to identify boot progress in headless testing.

### Xtreme Boot Phases
| Phase | Frames | VRAM Hash  | Description | PC |
|-------|--------|------------|-------------|-----|
| 1 | ~1-60 | 65232E60 | Boot ROM: "Cybiko" logo top third | 0x0076B2 (boot ROM) |
| 2 | ~60-240 | AA3312CC | "Cybiko" + "Loading CyOS 1.5.08" | 0x4A3C40 (halted) |
| 3 | ~240-300 | C0DBEF72 | Animated Cybiko logo (CyOS init) | 0x488900 / 0x003AD8 |
| 4a | ~360+ | FAEDD600 | "Congratulations" welcome (no NVRAM) | 0x4A3C40 (halted) |
| 4b | ~360+ | 6C55765A | Home screen with clock (NVRAM, colon shown) | 0x4A3C40 (halted) |
| 4b | ~360+ | B5EB2FA8 | Home screen with clock (NVRAM, colon hidden/blink) | 0x4A3C40 (halted) |

Phase 4 hash differs based on NVRAM state. The home screen (4b) alternates between
two hashes as the clock colon blinks. All hashes confirmed by user on GUI display.

### Classic V1 Boot Phases
| Phase | Frames | VRAM Hash  | Description | PC |
|-------|--------|------------|-------------|-----|
| 1 | ~1-60 | CC3A6F3D | Boot ROM init (all black) | 0x002D34 (boot ROM) |
| 2 | ~60-120 | 4ACC524E | SPI flash loading (dot pattern) | 0x20B4B8 (CyOS) |
| 3 | ~240-300 | 5580FBFF | CyOS decompression / early init | 0x205A76 |
| 4 | ~360 | C0DBEF72 | Animated Cybiko logo (same as XT) | 0x219C8E (halted) |
| 5 | ~480 | varies | CyOS service init + battery check | 0x206BFC |
| 6 | ~540+ | 65D3B6FC | CyOS UI (interactive) | 0x219C8E (halted) |

V1 boots to interactive UI without a service stub. The key fix was ADC channel
values: ch1=0x0300, ch2=0x0100 (differential > 15 passes CyOS battery check).
No service stub needed — unlike V2, V1 CyOS handles blocked services gracefully.

### V2 Boot Phases
| Phase | Frames | VRAM Hash  | Description | PC |
|-------|--------|------------|-------------|-----|
| 1 | ~1-60 | 4ACC524E | SPI flash loading (dot pattern) | 0x108720 |
| 2 | ~1080 | A6642987 | Transitional (CyOS init) | 0x108D2A |
| 3 | ~1140+ | C0DBEF72 | Animated Cybiko logo (same as XT/V1) | 0x11ED98 (halted) |

V2 currently stalls at phase 3 (Cybiko logo). The desktop app never starts because
RF hardware init never completes. Service stub resolves non-RF set_task_state calls.

## Bugs Found & Fixed
Full details in [docs/bugs-fixed.md](docs/bugs-fixed.md). Key lessons for future development:

### H8S Port Register Addresses
Two register ranges for port I/O (from MAME h8s2319.cpp):
| Register Type   | Range           | Example Port A | Function            |
|-----------------|-----------------|----------------|---------------------|
| DDR (direction) | 0xFFFEB0-0xFFFEBF | 0xFFFEB9      | Data Direction      |
| Input Data      | 0xFFFF50-0xFFFF5E | 0xFFFF59      | Actual pin state    |
| Data Register   | 0xFFFF60-0xFFFF6E | 0xFFFF69      | Output latch / pin  |
| PCR             | 0xFFFF70-0xFFFF77 | 0xFFFF70      | Port Control        |

### Patterns to Watch For
- **MOV post-inc/pre-dec aliasing**: When Rs==Rd, must use temp variable (bug #4)
- **Interrupt flag transitions**: Only fire on 0→1 transition, not every tick (bug #2)
- **SLEEP wake**: Any interrupt request wakes CPU from sleep regardless of I flag (bug #3)
- **Input port defaults**: Unhandled input pins should return 0xFF (pull-ups), not 0x00 (bug #12)
- **Peripheral idle states**: I2C SDA must idle HIGH; CyOS checks pins during early init (bug #9)
- **I2C routing per machine**: V1/V2 use DDR-based SDA, XT uses DR-based SDA (bug #10)
- **Timer caching**: Per-frame isRunning() caching is safe; per-cycle is too aggressive (bug #11)
- **DTC for V1 SPI**: CyOS uses DTC for bulk SPI reads; detect SCR writes + DTCER to trigger (bug #13)
- **V1 ADC battery check**: CyOS has TWO battery check paths using ch1/ch2 differential.
  Returning uniform max values (0xFFC0) fails because diff=0. Need ch1 > ch2+15 (bug #14)
- **V1 service stub harmful**: Unlike V2, V1 CyOS boots without a service stub. Adding one
  causes battery dialog timer waits to complete instantly, freezing the UI (bug #15)
- **MULXU.B register addressing**: MULXU.B Rs, Rd multiplies byte Rs × low byte of WORD
  register Rd. The Rd operand is a 4-bit word register index, not a byte register index.
  Must use `getR(rd) & 0xFF` (low byte of word reg), not `getRegB(rd)` (which maps 0→R0H
  instead of R0L). Caused BCD-to-binary conversion to lose tens digit (bug #16)

## Known Issues
- **XT keyboard fully mapped**: All physical keys found via probe mode. Previously
  missing keys: `!` (col 9, 0x0004), `,` (col 0, 0x0400), `(` (col 2, 0x0400),
  `)` (col 0, 0x0200). Full Fn+letter symbol layer and Shift+key combos mapped.
  Only unmapped: backtick (no Cybiko equivalent), Cybiko ☆ symbol (Fn+C, no PC key).
  See [docs/xt-keyboard.md](docs/xt-keyboard.md) for complete matrix and PC mapping.
- **V1 Fn+' for " not working**: Shift+' on PC should produce " via Fn+' on V1 Cybiko.
  The Fn combo queue (same as XT) doesn't produce results. V1 CyOS may use a different
  mechanism for the Fn layer, or different timing. Needs investigation (low priority).
- **V2 CyOS stuck at Cybiko logo**: V2 boots through SPI flash loading, reaches the
  animated Cybiko logo (VRAM hash C0DBEF72, same as V1/XT) but never progresses to
  desktop. I2C RTC communication now works (bug #10 fix), but the desktop app never
  starts because RF hardware init never completes (entry[0x91] stays 0, all display
  rendering skipped). A service stub auto-resolves non-RF set_task_state calls to
  bypass startup waits. The RF object (0x202CB2) must never be resolved via stub —
  causes failed HW init. See V2 Investigation below. MAME also cannot fully boot V2
  CyOS with these ROMs.
- **XT radio peer discovery and chat working between emulators**: CyOS v1508
  uses SCI2 for radio with hardware DTC for both TX and RX. TX DTC sends outgoing
  frames (51/201 bytes). RX DTC bulk-transfers 50/200 bytes from SCI2 RDR to a RAM
  buffer (DTCERF bit 7). After TX DTC completion, AddressBus defers delivery of
  TWO bytes when TXI2 ISR clears DTCERF bit 6: 0x03 (packet ACK, consumed by
  state 6 → completion → state 1) then the frame indicator (0x32/0xC8, consumed
  by state 1 → RX DTC setup). The 0x03 is critical — without it, the indicator
  gets consumed by state 6 instead of state 1, and CyOS never sets up RX DTC.
  Frame size indicator matches frame type: 0x32 (50 bytes) for poll beacons
  (≤50 bytes), 0xC8 (200 bytes) for scan/chat frames (>50 bytes) or no data.
  CyOS distinguishes "large frame" from "no data" by content (real data vs 0xFF).
  Short TX DTC frames (≤9 bytes, e.g. 4-byte AVR commands like `01 03 00 00`)
  skip completeTxDtc() entirely — they're command frames that don't need frame
  exchange, and calling completeTxDtc() would reset rxFrameReady (losing a
  pending frame) and block 15ms. handleTransmit() also filters these (no network
  send). handleTransmit() extracts the channel from the frame content's channel
  byte (`payload[0] & 0x3F`) instead of using `currentChannel`, because CyOS
  channel-hops (ch=2↔ch=4) and may switch channels between preparing the frame
  and firing the TX DTC. MRA-based address mode in RX DTC: 0x20=dest increment
  (real data), 0x00=dest fixed (discard on channel mismatch). RX DTC frames
  include an 8-byte AVR header prepended before the RF payload: bytes 0-3 =
  destination peer ID (0xFFFFFFFF for broadcast), bytes 4-7 = sender's CyID
  (device identity). CyOS checks connObj->0x00 against the local CyID or
  broadcast. The CyID at flash offset 0x7F818 is patched with radio-id via
  `patchCyId()` so each emulator has unique identity (CRC32 checksum at 0x7FFFC
  recalculated). Poll commands (0x30) deliver small beacons (≤50 bytes) but
  suppress large frames (>50 bytes) — matches real hardware where poll RX window
  is 50 bytes. Scan commands (0xCF) deliver any frame size. Async frame delivery
  via tickSci2() injects indicator bytes when CyOS radio state == 1 (idle),
  checked every 512 CPU cycles. This emulates the AVR's autonomous frame
  capture — frames received via UDP between TX DTC cycles are delivered without
  waiting for the next poll/scan. Nearby peer discovery and Chat messaging
  between emulators confirmed working.
  See [docs/rf2915-research.md](docs/rf2915-research.md) for decoded frame format.

## Current Status
- Multi-machine support: V1 (Classic), V2, and XT (Xtreme) selectable via --machine flag
- CyOS fully boots to interactive "Congratulations!" welcome screen (or desktop with NVRAM) on XT
- V1 CyOS fully boots from SPI flash (AT45DB041 + DTC bulk transfer) to interactive UI
- Keyboard fully mapped on XT: letters, arrows, F1-F7, all punctuation/symbols via
  lazy Shift + Fn combo queue + Shift combo queue. See docs/xt-keyboard.md.
- Keyboard works on V1: letters, arrows, numbers, most punctuation. Fn+' for " not working.
- Minimum key hold time (3 frames) prevents fast key presses from being missed
- RTC shows correct date/time on all variants (PCF8593 I2C, real-time advancement)
- Selectable log categories via --logging flag (cpu,radio,rtc,dma,io,status,boot,cfs,speaker)
- LCD renders full CyOS UI with menus and text input
- Timer8 and Timer16 interrupts drive the OS scheduler
- DMA controller handles keyboard matrix scans (XT only; V1/V2 use direct reads)
- App loading via CFS filesystem (--app wraps .app files in proper CFS block format)
- Persistent NVRAM (--nvram saves/restores external RAM between sessions)
- Speaker audio output (1-bit PWM via Timer16 ch1 TIOCB1 output compare, 48kHz)
- VRAM CRC32 hash and frame timing in STATUS log
- Frame time ~4ms (24% of 16.7ms budget), JIT-optimized after first second
- No unimplemented opcodes in the current execution path
- Radio co-processor stub handles SCI0 (V1/V2) and SCI2 (XT) UART protocol
- Variable-length command framing (0x01=3 bytes, 0x30/0xCF=2 bytes)
- SCI2 async UART: receive() for TX path, RXI2 (vector 89) for response delivery
- SCI2 TDRE state modeling with TXI2 (vector 90) interrupt generation
- SCI2 DTC bulk transfer: TX via DTCERF bit 6, RX via DTCERF bit 7
- TX DTC completion: 0x03 ACK + 0x32/0xC8 frame size indicator (50=small, 200=large/null)
- Short TX DTC (≤9 bytes) skips completeTxDtc() — avoids rxFrameReady reset and 15ms block
- RX DTC: 50/200-byte bulk transfer from SCI2 RDR to RAM buffer, completion ISR delivers frame
- RF header stripping: 8-byte preamble+sync removed from TX before network forwarding
- Poll (0x30) delivers small beacons (≤50 bytes), suppresses large frames (>50 bytes)
- Scan (0xCF) delivers any frame size
- Async frame delivery: tickSci2() injects indicator when CyOS state==1 (idle), every 512 cycles
- Received frame queue cap: 4 (handles CyOS 3x retransmit bursts)
- UDP wait in completeTxDtc(): 15ms (simulates RF round-trip for synchronous path)
- CyOS radio frame format partially decoded (see docs/rf2915-research.md)
- Nearby peer discovery works on home screen (beacons delivered during polls)
- Chat messaging between emulators confirmed working
- LAN radio networking via UDP multicast (--radio lan)
- SDR TCP bridge stub for GNU Radio integration (--radio sdr)
- V2 RF object blacklist conditionally relaxed when radio transport is connected

## Keyboard Matrix

### Xtreme (15-column, 16-bit)
The Cybiko Xtreme keyboard is a 15-column matrix at 0xE00000-0xEFFFFF.
Column selection is **active-LOW** (confirmed from MAME `cybiko_m.cpp`):
`!BIT(offset, i)` means column i is selected when bit i of the word offset is 0.
CyOS uses a walking-zero scan pattern via DMA - each read address has all bits set
except one, isolating a single column. The SwingRenderer column indices (0-14) map
directly to MAME's `m_key[0]`-`m_key[14]` input ports.

### Column Addressing
| Column | Address    | Keys (bit positions in 16-bit read) |
|--------|------------|-------------------------------------|
| 0      | 0xE00100   | F7(0), M(8), )(9), ,(10), K(11), I(12), O(13), L(14) |
| 1      | 0xE00200   | F6(0), G(1), B(2), N(3), H(4), Y(5), U(6), J(7) |
| 2      | 0xE00400   | F5(0), D(8), C(9), ((10), V(11), F(12), R(13), T(14) |
| 3      | 0xE00800   | F4(0), Q(1), A(2), Z(3), X(4), S(5), W(6), E(7) |
| 4      | 0xE01000   | F3(0), Enter(3), Select(4), CtxMenu(5), Space(6) |
| 5      | 0xE02000   | F2(0), Tab(7), Del(8), Ins(9), Esc(10)            |
| 6      | 0xE04000   | F1(0), Up(11), Right(12), Down(13), Left(14)      |
| 7      | 0xE08000   | Fn(15)                                             |
| 8      | 0xE10000   | Shift(15)                                          |
| 9      | 0xE20000   | Help(0), Period(1), !(2), Semicolon(3), P(4)       |
| 10     | 0xE40000   | (no keys found — fully probed)                     |
| 11     | 0xE80000   | (no keys found — fully probed)                     |
| 12     | 0xF00000   | (no keys found — fully probed)                     |
| 13     | 0xF80000   | (MAME: Help(0), Period(1), P(4) — see note)        |
| 14     | 0xFC0000   | Power(15)                                          |

### Number Keys (Fn + Letter combos)
The Cybiko Xtreme has no dedicated number keys. Numbers are entered via:
| Key | Combo | Column:Bit (letter) |
|-----|-------|---------------------|
| 1   | Fn+Q  | 3:0x0002            |
| 2   | Fn+W  | 3:0x0040            |
| 3   | Fn+E  | 3:0x0080            |
| 4   | Fn+R  | 2:0x2000            |
| 5   | Fn+T  | 2:0x4000            |
| 6   | Fn+Y  | 1:0x0020            |
| 7   | Fn+U  | 1:0x0040            |
| 8   | Fn+I  | 0:0x1000            |
| 9   | Fn+O  | 0:0x2000            |
| 0   | Fn+P  | 13:0x0010           |

Fn key is always column 7, bit 0x8000.

### Classic V1 (9-column, 8-bit)
The V1 has a 9-column keyboard matrix with 8-bit reads and dedicated number keys.
Column mapping from MAME INPUT_PORTS (A.0-A.8):

| Column | Keys (bit positions in 8-bit read) |
|--------|-------------------------------------|
| 0 | F7(0), Esc(1), Del(2), Left(3), Q(4), A(5), Grave(6), Shift(7) |
| 1 | F6(0), Up(1), Insert(2), 2(3), W(4), S(5), Z(6), Fn(7) |
| 2 | F5(0), F3(1), Space(2), 3(3), E(4), D(5), X(6), Help(7) |
| 3 | F4(0), 1(1), Tab(2), 4(3), R(4), F(5), C(6), [(7) |
| 4 | Right(0), Down(1), Select(2), 5(3), T(4), G(5), V(6), ](7) |
| 5 | F2(0), ;(1), Enter(2), 6(3), Y(4), H(5), B(6), \\(7) |
| 6 | F1(0), /(1), BkSp(2), 7(3), U(4), J(5), N(6) |
| 7 | -(0), .(1), 0(2), 8(3), I(4), K(5), M(6) |
| 8 | '(0), =(1), 9(2), P(3), O(4), L(5), ,(6) |

### AT45DB041 SPI Flash (V1 only)
The V1 uses an AT45DB041 serial flash for CyOS storage instead of memory-mapped flash.
- 2048 pages x 264 bytes = 540,672 bytes
- Connected via SCI1 (SPI mode): TDR write sends byte, RDR read receives response
- CS controlled by Port 3 DR bit 4 (active low)
- Commands: status read (0x57), page read (0x52), program via buffer1 (0x82), compare (0x60)
- Page address: `((cmd[1] & 0x0F) << 7) | ((cmd[2] & 0xFE) >> 1)`
- Byte offset: `((cmd[2] & 0x01) << 8) | cmd[3]`

## DMA Controller
Implemented in AddressBus. The H8S/2323 has a 4-channel DMA controller (DMAC).

### Registers
| Address Range       | Description                        |
|---------------------|------------------------------------|
| 0xFFFEE0-0xFFFEEF  | DMA channel 0 (MAR, IOAR, ETCR)   |
| 0xFFFEF0-0xFFFEFF  | DMA channel 1                      |
| 0xFFFF00-0xFFFF07  | DMA control (DMAWER, DMATCR, DMACR, DMABCR) |

### Key Register: DMABCR (0xFFFF06-0xFFFF07, 16-bit)
- Bits 4-7: DTE0-DTE3 (Data Transfer Enable for each channel)
- Writing DTE with channel configured triggers immediate transfer

### CyOS Keyboard Scan Path
1. Sets DMA source = 0xE00100 (keyboard column address)
2. Sets DMA dest = 0xFFDC00 (on-chip RAM)
3. Sets transfer count (15 columns × 2 bytes)
4. Enables DTE bit → DMA executes immediately
5. CyOS reads keyboard state from on-chip RAM at 0xFFDC00+

## DTC (Data Transfer Controller) - V1 Only
The H8S/2241 has a DTC that handles interrupt-triggered data transfers using register
info blocks in on-chip RAM. V1 CyOS uses DTC for bulk SPI flash reads via SCI1.

### How CyOS Uses DTC for SPI Flash
1. Sets up DTC register block at 0xFFFBD0 (10 bytes):
   - Byte 0: MRA (0x20=receive from RDR to RAM, 0x80=transmit from RAM to TDR)
   - Bytes 1-3: Source address low 3 bytes
   - Bytes 4-7: Dest address (byte 4 forced to 0x00)
   - Bytes 8-9: Transfer count (16-bit)
2. Enables DTC via DTCER (0xFFFF34) bit 1
3. Sets SCI1 SCR=0x50 (RE + RIE) for receive, or 0xA0 (TE + TIE) for transmit
4. In real hardware: SCI auto-generates clock, each byte triggers RXI/TXI → DTC handles it
5. When count reaches 0, DTC stops and normal interrupt handler runs (sets completion flag)

### Emulation Approach
Instead of full DTC emulation, AddressBus detects SCR writes with DTC enabled and
executes the entire transfer immediately. This avoids modeling autonomous SCI clock
generation and per-byte DTC dispatch.

## PCF8593 RTC (I2C Protocol)
Real-time clock connected via I2C bit-bang on Port F.

### Port F Pin Wiring
**Xtreme**: SCL = bit 1 (0x02), SDA write = bit 6 (0x40, inverted), SDA read = bit 6
**V1/V2**: SCL = bit 1 (0x02), SDA write = bit 0 (0x01, inverted), SDA read = bit 0
- **SCL**: Port F DR (0xFFFF6E), direct polarity (all variants)
- **SDA write (XT)**: Port F DR (0xFFFF6E), **inverted** (bit set = SDA low)
- **SDA write (V1/V2)**: Port F DDR (0xFFFEBE), open-drain model:
  DDR bit = output (1) drives SDA low via DR=0; DDR bit = input (0) releases SDA high
- **SDA readback**: Port F input register (0xFFFF5E), NOT inverted
- Note: MAME has `m_inp = 0` default with FIXME "sda should default 1 not 0".
  Our `inp` must be 1 (idle high) or CyOS fails to boot (see bug #9).

### I2C Protocol (matching MAME pcf8593.cpp)
- Address byte: 0xA2 (write) / 0xA3 (read)
- START: SDA goes high→low while SCL is high
- STOP: SDA goes low→high while SCL is high
- Data: MSB-first, sampled on SCL rising edge
- ACK: 9th bit, device pulls SDA low to acknowledge
- Repeated START: START condition without preceding STOP (used for read sequences)
- Register auto-increment after each byte, wraps at 0x0F (16 registers)
- Register pointer masked to 4 bits (`& 0x0F`)

### PCF8593 Register Map
| Reg | Contents              | Format      |
|-----|-----------------------|-------------|
| 0   | Control/status        | Flags (bit 7=stop, bit 2=function mode) |
| 1   | Hundredths of second  | BCD         |
| 2   | Seconds               | BCD (0-59)  |
| 3   | Minutes               | BCD (0-59)  |
| 4   | Hours                 | BCD (0-23, bits 7-6 masked by CyOS) |
| 5   | Year(bits 7-6) + Day(bits 5-0) | BCD day, 2-bit year-in-cycle |
| 6   | Weekday(bits 7-5) + Month(bits 4-0) | BCD month (5 bits) |
| 7   | Timer / **Year counter** (CyOS-specific) | BCD, see below |
| 8-15 | Alarm registers       | CyOS writes defaults on boot |

### CyOS Clock Protocol
From ROM disassembly at 0x4A46A4-0x4A47F0. This documents how CyOS uses the RTC.
The emulator reloads system time into RTC registers when CyOS writes 0x04 to
the control register (end of boot init), so the Set Date screen shows correct time.

CyOS does NOT use the RTC as an autonomous clock. Instead:

1. **Boot init**: Reads all 16 registers, then writes defaults:
   - Control = 0x84 (stop counting), time = Jan 1 00:00:00
   - Reg 7 = 0x00 (year counter), Reg 5 year-in-cycle = 0
   - Then writes Control = 0x04 (start counting, function mode 01)

2. **Periodic reads**: Reads registers 1-7, converts BCD→binary into time struct:
   - `struct[6]` = reg 1 (hundredths)
   - `struct[5]` = reg 2 (seconds)
   - `struct[4]` = reg 3 (minutes)
   - `struct[3]` = reg 4 & 0x3F (hours)
   - `struct[2]` = reg 5 & 0x3F (day)
   - `struct[1]` = reg 6 & 0x1F (month)
   - `struct[0]` = reg 7 (year counter, see below)

3. **Year handling**: Register 7 (normally timer/alarm) stores the year:
   - Read: BCD→binary, if `value != 99` then `value += 100`
   - So reg7=0x00 → year 100, reg7=0x26 → year 126 (from 1900 = 2026)
   - reg7=0x99 → year 99 (special case for 1999)
   - Checks 2-bit year-in-cycle from reg 5 bits 7-6 vs `year % 4`
   - If mismatch: increments year, writes back via `(year + 0x9C) & 0xFF` → BCD → reg 7

4. **Fresh boot default**: Year = 100 (2000), Jan 1, 00:00:00. Time set via CyOS Settings
   persists in NVRAM.

### CyOS I2C Bit-Bang Functions
Disassembled from decompressed CyOS at 0x4A4558-0x4A4668:
| Address | Function | Description |
|---------|----------|-------------|
| 0x4A4558 | delay | 6 NOPs + RTS (~6 CPU cycles delay for I2C timing) |
| 0x4A4566 | i2c_start | SDA LOW (DDR=0xCB), delay, SCL LOW (BCLR #1) |
| 0x4A457A | i2c_repeat_start | SDA HIGH (DDR=0x8B), SCL HIGH (BSET #1), then calls i2c_start |
| 0x4A458C | i2c_stop | SDA LOW, delay, SCL HIGH, delay, SDA HIGH |
| 0x4A45A6 | i2c_send_byte | R0=byte, sends MSB-first, 8 bits + ACK, returns ACK in R0L |
| 0x4A4608 | i2c_recv_byte | R0L=NACK flag, reads 8 bits from SDA, sends ACK/NACK |
| 0x4A466E | bcd_to_binary | R0L=BCD → R0=binary (high_nib * 10 + low_nib) |
| 0x4A4686 | binary_to_bcd | R0L=binary → R0=BCD (DIVXU by 10) |
| 0x4A46A4 | rtc_read_time | Reads regs 1-7 into time struct, handles year correction |
| 0x4A47F2 | rtc_read_alarm | Reads regs 9-15 |

## CFS (Cybiko File System)
CyOS stores apps and data in a block-based filesystem (CFS) in external RAM at 0x400000.
Format matches MAME's `src/tools/imgtool/modules/cybikoxt.cpp`.

### Image Layout
| Section | Pages | Description |
|---------|-------|-------------|
| Boot blocks | 0-4 (5 pages) | All 0xFF, CRC = 0xFFFF |
| File blocks | 5-2004 (2000 pages) | App/data storage |
| Padding | - | 7254 bytes of 0xFF |

Page size: 258 bytes (2-byte CRC + 256-byte block data). Total: ~512KB.

### File Block Format (256 bytes of block data)
| Offset | Size | Description |
|--------|------|-------------|
| 0 | 1 | Flags (bit 7 = BLOCK_USED) |
| 1 | 1 | Data size in this block |
| 2-3 | 2 | File ID (big-endian) |
| 4-5 | 2 | Part ID (big-endian, 0 = first block) |
| 6 | 1 | 0x20 for part 0, 0x00 for continuation |
| 7+ | - | Filename (part 0) or file data (continuation) |
| 74-77 | 4 | Timestamp, part 0 only (seconds since 1900/01/01) |
| 78+ | - | File data start for part 0 |

Data capacity: 178 bytes/first block, 250 bytes/continuation block.
Unused blocks: 0xFF with bit 7 of byte[0] cleared (0x7F).

### CRC16 Algorithm
```
val = 0; for each byte i: val = (val ^ data[i] ^ i) << 1; val |= (val >> 16) & 1
```

### Speaker
1-bit PWM output driven by Timer16 channel 1 TIOCB1 output compare pin. CyOS configures
TIOR to toggle the TIOCB1 pin on TGRB compare match, with counter clearing on TGRA match,
creating a PWM waveform. H8STimer16 emulates the TIOR output compare behavior (initial
level, set/clear/toggle on match, PWM reset on counter clear) and fires a callback to
SpeakerOutput on each transition. SpeakerOutput records transitions with cycle timestamps
per frame, then resamples to 48kHz PCM for javax.sound.sampled playback. Silence (no
transitions) outputs center value (128) to avoid DC offset clicks.

## Tools

### H8S Disassembler (`tools/H8SDisasm.java`)
Standalone H8S/2000 disassembler for analyzing CyOS code from RAM dumps.
```bash
# Compile
javac tools/H8SDisasm.java

# Usage: H8SDisasm <binary> <hex_offset> <hex_length> [hex_base_addr]
# Dump CyOS decompressed from external RAM (address = offset + 0x400000)
java -cp tools H8SDisasm /tmp/cyos_ram.bin A4550 200 4A4550
```

To get a RAM dump, add `-Dcybiko.ramdump=/tmp/cyos_ram.bin` to the Java command line.
The emulator dumps all 2MB of external RAM at frame 300 and stops. CyOS code starts at
offset 0xA3BF8 in the dump (address 0x4A3BF8). Boot ROM code is NOT in this dump (it's
in bootrom, not external RAM).

Supports most H8S instructions used in CyOS: MOV (all addressing modes), ADD/SUB/CMP,
AND/OR/XOR, shifts/rotates, branches, JSR/BSR/RTS, bit operations (BSET/BCLR/BTST/BLD/BST
including memory-addressed variants like `BSET #n, @aa:32`), STM/LDM, EXTU/EXTS, MULXU/DIVXU.

## V2 Status
Full investigation in [docs/v2-investigation.md](docs/v2-investigation.md).

V2 boots to animated Cybiko logo but stalls — desktop app never starts because RF
hardware init (RF2915) never completes. The service stub auto-resolves non-RF
set_task_state calls; RF object (0x202CB2) is blacklisted during boot. MAME also
cannot fully boot V2 CyOS with these ROMs.

### V2 CFS Format (differs from XT)
V1/V2 use 264-byte pages (vs XT's 258-byte):
| Offset | Size | Content |
|--------|------|---------|
| 0-3 | 4 | CRC32 checksum |
| 4-5 | 2 | Write count |
| 6-7 | 2 | CRC16 verification |
| 8-263 | 256 | Block data (same format as XT) |

## NVRAM Manager (`manager/` subproject)

Standalone JavaFX desktop application for managing Cybiko NVRAM/flash images without
running the emulator. Separate Gradle subproject with its own `build.gradle` and
`module-info.java`.

### Build & Run
```bash
./gradlew :manager:build    # compile + run tests
./gradlew :manager:test     # run tests only (~186 tests)
./gradlew :manager:run      # launch the GUI
```

### Features
- **Open/save/create** NVRAM images (.nvram, .bin, .nv) for all hardware variants
- **Library folders** — configure directories of .app files, browse and add to NVRAM
- **File management** — add files to NVRAM, remove files, view file details
- **Search/filter** — real-time substring search across file lists
- **Multi-select** — batch operations on multiple files
- **Drag-and-drop** — drag library items onto NVRAM entries in sidebar to add files
  directly (multi-select supported, visual drop target feedback)
- **Smart lists** — "Recently Added" (top 20 by date) and "Not in Any NVRAM" virtual
  folders that auto-refresh when NVRAMs or library folders change
- **Hex viewer** — virtualized ListView (handles 2MB+ images), multi-select + copy to clipboard
- **Integrity validation** — CyOS-level checks: checksums, boot blocks, block flags,
  file structure (orphans, duplicates, missing parts), data sizes
- **Flash repair** — extracts recoverable files, reformats image, re-adds valid files
- **NVRAM properties** — flash type, page/block counts, usage stats, checksum status
- **CSV export** — export file listing as CSV
- **Unsaved changes tracking** — title shows "*", confirms on close
- **In-window dialogs** — all modal dialogs render as overlays inside the main window
  using `Platform.enterNestedEventLoop()`, avoiding Wayland compositor tiling issues
- **Dark theme** — GitHub-style dark mode CSS

### Architecture

#### Packages
| Package | Description |
|---------|-------------|
| `cfs` | CFS filesystem core: geometry, checksums, pages, blocks, image, reader/writer, validator |
| `model` | Data records: CfsFile, AppEntry, LibraryFolder, ContentItem (sealed interface) |
| `io` | Library folder persistence (properties file) and directory scanner |
| `ui` | JavaFX UI components: sidebar, content list, detail pane, dialogs |
| (root) | App entry point, MainWindow controller |

#### CFS Core Classes (`cfs` package)
- `FlashGeometry` — Enum for AT45DB041 (V1/V2 4Mbit), AT45DB081 (8Mbit), AT45DB161 (16Mbit),
  XTREME (SST 39VF400A). Defines page size, block data size, page count, boot blocks,
  capacities, CRC algorithm per variant.
- `CfsChecksum` — CRC16 (Xtreme) and CRC32 (AT45DB) checksum algorithms matching MAME.
- `CfsPage` — Page-level I/O: read block data from page buffer, write page buffer with CRC,
  verify page checksum. Handles different page header layouts per geometry.
- `CfsBlock` — Parses/serializes 256-byte block data. Records: flags, dataSize, fileId,
  partId, type marker, filename (part 0), timestamp (part 0), file data.
- `CfsImage` — Central read/write API. Create fresh images, load from bytes, auto-detect
  geometry from file size. Query: listFiles, readFile, findFileId. Mutate: addFile,
  deleteFile. Capacity: freeBlockCount, freeSpace, usedSpace. Serialization: toBytes, verify.
  Package-visible accessors for validator/repair.
- `CfsReader` / `CfsWriter` — File-based convenience wrappers for CfsImage.
- `CfsValidator` — Comprehensive validation matching CyOS flash checks:
  - **Checksums**: verifies CRC on every page (CRC16 or CRC32 per geometry)
  - **Boot blocks**: verifies boot pages contain all 0xFF data
  - **Block flags**: checks unused blocks are properly marked (byte[0] & 0x80 == 0)
  - **File structure**: detects orphaned continuation blocks (no part 0), duplicate
    filenames, duplicate partIds, missing parts in sequences, wrong type markers
    (0x20 for part 0, 0x00 for continuations), invalid filenames (non-printable chars)
  - **Data sizes**: validates size field fits block capacity, warns on zero-size blocks
  - Returns `Result` record with `List<Issue>`, `isValid()`, `errorCount()`, `warningCount()`
  - `repair()` method: extracts all recoverable files, calls `reformatInPlace()`,
    re-adds valid files. Returns `RepairResult` with preserved/removed/actions lists.

#### Model Classes (`model` package)
- `CfsFile` — Record: fileId, name, data bytes, timestamp, blockCount. Methods: size(),
  extension(), formattedDate(), nowTimestamp().
- `AppEntry` — Record: path, name, sizeBytes, lastModified. For library .app files.
- `LibraryFolder` — Record: path, label, category. User-configured folder.
- `ContentItem` — Sealed interface with `NvramItem(CfsFile)` and `LibraryItem(AppEntry, boolean inNvram)`.
  Unifies NVRAM and library items for the table view.

#### I/O Classes (`io` package)
- `LibraryConfig` — Persists `List<LibraryFolder>` to `~/.cybiko-manager/library.properties`.
- `LibraryScanner` — Scans directories for `.app` files (case-insensitive), returns `List<AppEntry>`.

#### UI Classes (`ui` package)
- `App` — JavaFX Application entry point. Sets dark theme, handles close-with-unsaved-changes.
- `MainWindow` — Primary controller (extends StackPane for overlay dialogs). Menu bar
  (File, Library, NVRAM, Help), wires all components. Manages NVRAM images, library
  folders, view mode switching (NVRAM/LIBRARY/SMART_LIST), drag-and-drop wiring,
  CSV export, validation, repair, unsaved changes. All dialogs rendered as in-window
  overlays via `showDialog()` using `Platform.enterNestedEventLoop()` to avoid
  Wayland compositor tiling issues with secondary OS windows.
- `SidebarPane` — Left panel with three sections: NVRAM images list, Smart Lists
  (Recently Added / Not in Any NVRAM), and Library folders list. Three-way mutual
  exclusion selection. Drop target on NVRAM cells for drag-and-drop from library.
  "+" button for adding library folders. Context menu for removing folders.
- `ContentListPane` — Center table view with search field. Columns: Name, Extension, Size,
  Date, Status ("In NVRAM" badge). FilteredList chain for real-time search. Multi-select.
  Drag source for LibraryItems (static field pattern for same-JVM transfer).
- `DetailPane` — Right panel showing file details and action buttons (Add to NVRAM,
  Remove from NVRAM, View Hex).
- `CapacityBar` — Progress bar showing used/free space with percentage label.
- `HexViewerDialog` — Non-modal Stage with virtualized ListView (16 bytes/row, offset + hex + ASCII).
  Multi-select with Copy button and Ctrl+C shortcut.
- `NvramPropertiesDialog` — In-window overlay dialog: flash geometry, block stats, checksum status.
- `LibraryFolderDialog` — In-window overlay dialog: DirectoryChooser + label/category fields.
- `AboutDialog` — Version, Java/JavaFX/OS info.

### CFS Validation Checks
The validator performs the same checks CyOS does during flash integrity verification:

| Category | Severity | What it checks |
|----------|----------|----------------|
| Checksum | ERROR | CRC mismatch on any page (CRC16 for Xtreme, CRC32 for AT45DB) |
| Boot block | WARNING | Non-0xFF data in boot pages 0-4 |
| Block flags | WARNING | Used block with flag byte inconsistency |
| File structure | ERROR | Orphaned continuation blocks (no matching part 0) |
| File structure | ERROR | Duplicate filenames across different fileIds |
| File structure | WARNING | Duplicate partIds within same fileId |
| File structure | WARNING | Missing parts in sequence (e.g., has part 0 and 2 but not 1) |
| File structure | WARNING | Wrong type marker (should be 0x20 for part 0, 0x00 for cont.) |
| File structure | WARNING | Invalid filename (non-printable characters) |
| Data size | ERROR | Size field exceeds block capacity |
| Data size | WARNING | Zero-size used block |

### Flash Repair Strategy
1. Scan all blocks, collect files that have valid part 0 with complete part sequences
2. Extract file data (name, bytes, timestamp) for each recoverable file
3. Call `CfsImage.reformatInPlace()` — wipes image and writes fresh boot blocks + empty file blocks
4. Re-add each recovered file via `CfsImage.addFile(name, data, timestamp)`
5. Report what was preserved and what was removed (orphans, incomplete files, duplicates)

### Test Coverage (~186 tests)
| Test File | Coverage |
|-----------|----------|
| `CfsChecksumTest` | CRC16/CRC32 algorithms, known vectors |
| `CfsPageTest` | Page read/write, CRC verification, all geometries |
| `CfsBlockTest` | Block parse/serialize, first/continuation parts |
| `CfsImageTest` | Add/delete files, capacity, multi-block files, roundtrip, clearModified |
| `CfsReaderWriterTest` | File I/O roundtrip |
| `CfsValidatorTest` | All 5 check categories, fresh/corrupted images, repair (checksum fix, boot block fix, orphan removal, multi-block preserve, clean no-op, modified flag) |
| `FlashGeometryTest` | Geometry detection, capacities |
| `CfsFileTest` | Record fields, timestamp conversion |
| `AppEntryTest` | Extension parsing, date conversion |
| `ContentItemTest` | Sealed interface delegation, inNvram flag |
| `LibraryConfigTest` | Save/load roundtrip, empty/missing config |
| `LibraryScannerTest` | Directory scanning, filtering, empty dirs |

## Development Workflow
1. Run emulator, find unimplemented opcode
2. Look up instruction in MAME h8.lst (mask/match pattern + microcode)
3. Implement in H8SCpu.java
4. Repeat until boot progresses further
5. When stuck in I/O polling loop, identify the register from MAME h8s2319.cpp and stub it
6. For CyOS-level debugging: dump RAM, disassemble with H8SDisasm, trace I2C/port access
