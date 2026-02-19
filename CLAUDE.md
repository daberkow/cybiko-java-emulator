# Cybiko Xtreme Emulator

## Project Overview
Java emulator for the Cybiko Xtreme handheld computer. Learning project intended
for eventual port to C.

## Build & Run
```bash
./gradlew build
./gradlew run --args="path/to/cyrom150.bin path/to/cyos_v1508.bin"
```

### Options
| Flag | Description |
|------|-------------|
| `--headless` | Run without GUI window |
| `--trace` | Enable instruction tracing (slow, verbose) |
| `--mute` | Disable audio output |
| `--nvram <file>` | Load/save persistent NVRAM (CFS filesystem + CyOS state) |
| `--app <file.app>` | Add .app file to NVRAM before booting (multiple allowed) |
| `--list-apps` | List apps in NVRAM and exit |

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

ROM files: `src/main/resources/cybikoxt/cyrom150.bin` and `cyos_v1508.bin` (from MAME cybikoxt.zip).
App files: `../cybiko-archive/cybiko/cybiko/apps/` (e.g., `calc.app`, `dice/dice.app`).

## Hardware Reference

### CPU: Hitachi H8S/2323 @ 18.432 MHz
- H8S/2000 series (H8/300 base + H8/300H 32-bit + H8S extensions)
- 8x 32-bit general purpose registers (ER0-ER7)
  - Upper 16-bit halves: E0-E7
  - Lower 16-bit halves: R0-R7
  - Lower bytes split: R0H/R0L through R7H/R7L
- PC: 24-bit program counter
- CCR: Condition Code Register (I UI H U N Z V C)
- EXR: Extended control register (interrupt/trace)
- Big-endian byte order

### Memory Map
| Address Range     | Size  | Device                          |
|-------------------|-------|---------------------------------|
| 0x000000-0x007FFF | 32KB  | Boot ROM (mirrored to 0x03FFFF) |
| 0x100000-0x100001 | 2B    | LCD controller (HD66421)        |
| 0x200000-0x200003 | 4B    | USB controller (USBN9604)       |
| 0x400000-0x5FFFFF | 2MB   | External RAM                    |
| 0x600000-0x67FFFF | 512KB | Flash ROM (SST 39VF400A)        |
| 0xE00000-0xEFFFFF | 1MB   | Keyboard matrix                 |
| 0xFFDC00-0xFFFFFF | ~9KB  | On-chip RAM & I/O registers     |

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
- `CybikoXtreme` - Main emulator orchestrator with nanoTime-based frame timing (60fps)
- `AddressBus` - Memory-mapped I/O routing, DMA controller, keyboard matrix, speaker
- `H8SCpu` - CPU emulation core (~150 instructions implemented)
- `H8STimer8` - 8-bit timer with compare match and overflow interrupts
- `H8STimer16` - 16-bit timer (TPU) with per-channel clock source tables
- `Memory` - Simple byte-array backed memory
- `HD66421Lcd` - LCD controller emulation
- `PCF8593Rtc` - Real-time clock with I2C bit-bang protocol (matches MAME pcf8593.cpp)
- `CfsImage` - CFS (Cybiko File System) image builder/reader (matches MAME cybikoxt.cpp)
- `SpeakerOutput` - 1-bit speaker audio via javax.sound.sampled (44.1kHz, 8-bit mono)
- `FrameBufferRenderer` / `SwingRenderer` / `ConsoleRenderer` - Display
- `SwingRenderer` - Swing GUI with keyboard input (maps PC keys to Cybiko matrix)

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

## Boot Sequence
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

## Bugs Found & Fixed

### 1. LCD alternating black rows
- **Symptom**: LCD displayed alternating black/white rows
- **Root cause**: HD66421 register masking used `& 0x11` instead of proper per-register masks
- **Fix**: Corrected register index masking

### 2. Timer16 interrupt flooding
- **Symptom**: CPU overwhelmed by timer interrupts
- **Root cause**: Timer16 fired interrupts every tick instead of on flag transition 0->1
- **Fix**: Added flag-transition guard (only fire interrupt when status flag goes from 0 to 1)

### 3. CPU halted permanently with masked interrupts
- **Symptom**: CPU entered SLEEP and never woke up, even with pending interrupts
- **Root cause**: `processInterrupts()` returned false when I flag was set (CCR bit 7),
  so CPU stayed in halted state forever
- **Fix**: On real H8S, any interrupt request wakes CPU from sleep regardless of I flag.
  Added check in step() to wake CPU when halted and pending interrupts exist.

### 4. MOV.L @ERs+, ERd post-increment clobber when Rs==Rd
- **Symptom**: CyOS crashed after task stack switch. `MOV.L @ER7+, ER7` (restore SP)
  returned SP+4 instead of the loaded value.
- **Root cause**: `er[rl] = bus.read32(er[rh]); er[rh] += 4;` - when rh==rl, the
  post-increment overwrites the loaded value.
- **Fix**: Use temp variable: `val = read; increment; store val to dest`.
  Applied same pattern to all MOV pre-dec/post-inc variants (B, W, L sizes).

### 5. Timer16 per-channel clock source mapping
- **Symptom**: Timer delay loops ran at wrong speed
- **Root cause**: Used a single fixed prescaler table for all channels. In H8S/2319,
  TPSC values 4-7 map to different sources per channel (some are external clocks,
  some are prescalers like /256 or /1024).
- **Fix**: Implemented per-channel clock divisor tables matching MAME h8s2319.cpp.

### 6. Port A input register address mismatch
- **Symptom**: CyOS showed "Recharge the batteries" blinking on LCD despite
  Port A data register (0xFFFF69) returning 0xC0 (battery charged)
- **Root cause**: H8S has TWO register addresses per port:
  - 0xFFFF59: Port A **input data register** (`port_r()`) - reads actual pin state
  - 0xFFFF69: Port A **data register** (`dr_r()`) - reads output latch
  CyOS reads 0xFFFF59 for battery status, but we only handled 0xFFFF69.
  Reads from 0xFFFF59 fell through to uninitialized on-chip RAM (0x00),
  which has bit 6 = 0 → battery not charged.
- **Fix**: Added port input register handlers for 0xFFFF50-0xFFFF5E range.
  Port A input (0xFFFF59) returns 0xC0, Port F input (0xFFFF5E) returns RTC SDA.

### H8S Port Register Addresses
Two register ranges for port I/O (from MAME h8s2319.cpp):
| Register Type   | Range           | Example Port A | Function            |
|-----------------|-----------------|----------------|---------------------|
| DDR (direction) | 0xFFFEB0-0xFFFEBF | 0xFFFEB9      | Data Direction      |
| Input Data      | 0xFFFF50-0xFFFF5E | 0xFFFF59      | Actual pin state    |
| Data Register   | 0xFFFF60-0xFFFF6E | 0xFFFF69      | Output latch / pin  |
| PCR             | 0xFFFF70-0xFFFF77 | 0xFFFF70      | Port Control        |

### 7. DMA controller needed for keyboard input
- **Symptom**: CyOS displayed welcome screen but keyboard input had no effect
- **Root cause**: CyOS reads the keyboard matrix via DMA transfers, not direct CPU reads.
  The keyboard scan routine sets up DMA channel 0 to transfer from 0xE00000+ (keyboard
  matrix addresses) to 0xFFDC00 (on-chip RAM), then reads the results from on-chip RAM.
  Without DMA emulation, the on-chip RAM destination always contained zeros.
- **Fix**: Implemented DMA controller in AddressBus with channel registers at 0xFFFEE0-0xFFFEFF
  and control registers at 0xFFFF00-0xFFFF07. DMA transfers execute immediately when the
  enable bit (DMABCR bit 4/5/6/7 for channels 0/1/2/3) is written with DTE set.

### 8. RTC I2C protocol - first byte always zero
- **Symptom**: CyOS displayed time as "228.18.1900" (garbage date/time)
- **Root cause**: Old PCF8593Rtc.java used a custom shift-register I2C state machine.
  After `processReceivedByte()` switched to SEND mode and called `prepareNextByte()`
  (which loaded shiftReg with the first data byte), the code did `shiftReg = 0` which
  overwrote the loaded data. The first byte of every I2C read was always 0x00.
- **Fix**: Complete rewrite of PCF8593Rtc.java to match MAME pcf8593.cpp exactly.
  Uses `active` flag, `dataRecv[]` buffer, `bits` counter with `>8` for ACK handling,
  MSB-first bit operations matching MAME (`0x80 >> bits` for receive, `data[pos] >> (7-bits)`
  for send). Initializes with system time via `LocalDateTime.now()`.

### 9. RTC SDA idle state broke CyOS boot (CRITICAL)
- **Symptom**: CyOS got stuck at boot splash (PC=0x4A3C40, halted=true), never
  reached the Congratulations welcome screen. No I2C transactions attempted.
- **Root cause**: During the RTC rewrite (bug #8), the SDA output (`inp`) was
  initialized to 0 (SDA low). The old code had `sdaOut = true` (SDA high/released).
  In I2C, idle SDA must be HIGH (open-drain with pull-up). With `inp=0`, Port F
  reads (0xFFFF5E/0xFFFF6E) returned 0x00 instead of 0x40 for bit 6. CyOS reads
  Port F during early init and the wrong value sent it down a different code path
  where it never progressed past the boot splash.
- **Fix**: Three changes to PCF8593Rtc.java:
  1. Initialize `inp = 1` (SDA released/high when idle, matching I2C spec)
  2. Set `inp = 0` in RECV mode after processing byte (ACK = pull SDA low)
  3. Set `inp = 1` to release SDA: at start of each new RECV byte, on STOP
     condition, and on master NACK in SEND mode
- **Lesson**: Peripheral idle/default states matter! CyOS checks port pin states
  during early init, even for peripherals it hasn't communicated with yet. Always
  match the real hardware's electrical idle state (I2C: SDA and SCL both high).
  MAME itself has `m_inp = 0` with a FIXME comment saying "sda should default 1
  not 0" - our old working code got this right with `sdaOut = true`.

### 10. RTC register pointer masking clobbered time data
- **Symptom**: CyOS displayed garbled date/time values (e.g., "228.18.1900")
- **Root cause**: CyOS sends register address 0x80 for alarm/timer configuration.
  Our code masked with `& 0x0F`, mapping 0x80 → 0x00, so alarm writes went to the
  control register (stopping the clock with bit 7) and overwrote time data.
  MAME's pcf8593.cpp stores `m_pos = m_data_recv[1]` WITHOUT masking (m_pos is uint8_t).
- **Fix**: Store register pointer as full 8-bit value (`pos = dataRecv[1] & 0xFF`).
  Added bounds checking (`if (rtcPos < data.length)`) instead of masking. Writes to
  addresses >= 16 are safely ignored. Pos wraps at 0xFF matching MAME uint8_t behavior.

### 11. I2C SCL/SDA combined trigger prevented repeated START (CRITICAL)
- **Symptom**: CyOS never sent I2C read transactions (0xA3). Only write transactions
  (0xA2) were seen. Without reads, CyOS couldn't get time data from the RTC.
- **Root cause**: The combined `updateI2CPins()` handler processed both SCL and SDA
  from a single DDR or DR write, using hybrid pin ordering (SDA-first when idle,
  SCL-first when active). After an ACK (RTC holds SDA LOW via inp=0), the combined
  bus SDA check `(pinSda != 0) && (inp != 0)` was always false, making it impossible
  to detect the SDA transitions needed for repeated START conditions.

  **The real issue**: MAME's original Cybiko code (inside `#if 0` in cybiko_m.cpp)
  had SCL triggered from DR writes and SDA triggered from DDR writes as **separate**
  handlers. The new MAME port system combined them via the `(DR | ~DDR)` formula,
  which broke DDR-based open-drain I2C SDA control (DDR=0 gives output=1, which
  with SDA inversion means SDA LOW when it should be HIGH/released).

  CyOS I2C bit-bang (confirmed from ROM disassembly at 0x4A4566-0x4A4668):
  - SDA is controlled via **DDR writes** to 0xFFFEBE (bit 6, inverted)
  - SCL is controlled via **DR writes** using `BSET/BCLR #1, @0xFFFF6E` (8-byte
    memory bit operation instructions: `6A 38 00FF FF6E 70/72 10`)
  - These are from different register writes and must trigger independently

- **Fix**: Removed combined `updateI2CPins()`. DDR write (0xFFFEBE) triggers only
  `rtc.sda_w()`. DR write (0xFFFF6E) triggers only `rtc.scl_w()`. Also reverted
  `sda_w()` to simple `pinSda` transition check (matching MAME pcf8593.cpp).
- **Lesson**: The MAME port system's `(DR | ~DDR)` output formula is a regression
  for the Cybiko Xtreme's I2C wiring. The old disabled code shows the correct design.
  When MAME's active code doesn't work, check the `#if 0` blocks for the original intent.

### 12. Timer isRunning() snapshot broke Timer8_1 mid-frame start (CRITICAL)
- **Symptom**: CyOS stuck at logo screen (PC=0x4A3C40, halted=true). Timer8_0
  interrupts fire but CyOS never progresses past the boot splash.
- **Root cause**: Performance optimization snapshotted `timer8_1.isRunning()` once
  at the start of each frame (every 307,200 cycles). CyOS starts Timer8_1 mid-frame
  by writing TCR=0x03. The snapshot was `false` from frame start, so Timer8_1 wasn't
  ticked for the rest of that frame. Timer8_1's overflow flag (read from TCSR at
  0xFFFFB3 by CyOS scheduler at PC=0x49B920) never triggers at the expected time,
  causing CyOS to miss its boot timeout and never wake from the splash screen.
- **Fix**: Removed per-frame `isRunning()` snapshot. Tick all timers unconditionally
  every cycle. Stopped timers return early from `tick()` via `if (divisor == 0) return`
  which is fast enough (one branch per timer per cycle).
- **Lesson**: Timer start/stop can happen at any cycle, not just at frame boundaries.
  Caching running state per-frame creates a timing window where mid-frame timer starts
  are delayed, which can break timing-sensitive OS schedulers.

## Current Status
- CyOS fully boots to interactive "Congratulations!" welcome screen
- Keyboard input works (letters, Fn+letter combos for numbers, navigation keys)
- RTC with working I2C bit-bang protocol (reads and writes all registers correctly)
- Clock shows default date on fresh boot (Jan 1, 2000) - set time via CyOS Settings
- LCD renders full CyOS UI with menus and text input
- Timer8 and Timer16 interrupts drive the OS scheduler
- DMA controller handles keyboard matrix scans
- App loading via CFS filesystem (--app wraps .app files in proper CFS block format)
- Persistent NVRAM (--nvram saves/restores external RAM between sessions)
- Speaker audio output (1-bit, Port 1 bit 3 / TIOCB1)
- Performance: disabled timer skip, cached clock divisors, conditional debug instrumentation
- No unimplemented opcodes in the current execution path

## Keyboard Matrix
The Cybiko Xtreme keyboard is a 15-column matrix at 0xE00000-0xEFFFFF.
Column selection is **active-LOW** (confirmed from MAME `cybiko_m.cpp`):
`!BIT(offset, i)` means column i is selected when bit i of the word offset is 0.
CyOS uses a walking-zero scan pattern via DMA - each read address has all bits set
except one, isolating a single column. The SwingRenderer column indices (0-14) map
directly to MAME's `m_key[0]`-`m_key[14]` input ports.

### Column Addressing
| Column | Address    | Keys (bit positions in 16-bit read) |
|--------|------------|-------------------------------------|
| 0      | 0xE00100   | I(12), O(13), P(14)                |
| 1      | 0xE00200   | Y(5), U(6), J(7), H(8), N(9), B(10) |
| 2      | 0xE00400   | R(13), T(14), G(7), F(8), V(10), C(11) |
| 3      | 0xE00800   | Q(1), W(6), E(7), D(8), S(9), A(10) |
| 4      | 0xE01000   | Tab(0), Esc(15)                     |
| 5      | 0xE02000   | Del(8), Enter(9), Space(10)         |
| 6      | 0xE04000   | .(10), @(11)                        |
| 7      | 0xE08000   | Fn(15), Shift(0)                    |
| 8      | 0xE10000   | Right(0)                            |
| 9      | 0xE20000   | Down(0)                             |
| 10     | 0xE40000   | Left(0)                             |
| 11     | 0xE80000   | Up(0)                               |
| 12     | 0xF00000   | K(5), L(6), M(7), Z(8)             |
| 13     | 0xF80000   | Select(12), P-mapped(4)             |
| 14     | 0xFC0000   | Power(0)                            |

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

## PCF8593 RTC (I2C Protocol)
Real-time clock connected via I2C bit-bang on Port F.

### Port F Pin Wiring (CRITICAL - confirmed from ROM disassembly)
- **SCL**: Driven by Port F **DR** (0xFFFF6E) bit 1, direct polarity
  - CyOS uses `BSET #1, @0xFFFF6E` / `BCLR #1, @0xFFFF6E` (8-byte memory bit ops)
  - Trigger: DR write → `rtc.scl_w((portFDr & 0x02) != 0)`
- **SDA write**: Driven by Port F **DDR** (0xFFFEBE) bit 6, **inverted**
  - DDR=0 (input) → SDA HIGH (I2C pull-up), DDR=1 (output) → SDA LOW
  - CyOS writes 0x8B (bit 6=0, SDA HIGH) or 0xCB (bit 6=1, SDA LOW)
  - Trigger: DDR write → `rtc.sda_w((portFDdr & 0x40) == 0)`
- **SDA readback**: Port F input register (0xFFFF5E) bit 6, **NOT inverted**
  - CyOS reads via `MOV.B @0xFFFF5E:8, R2L` then `BLD #6, R2L` / `BTST #6, R2L`
- **IMPORTANT**: SCL and SDA are triggered from DIFFERENT registers (DR vs DDR).
  They must be handled as independent triggers, not combined.
- Note: MAME has `m_inp = 0` default with FIXME "sda should default 1 not 0".
  Our `inp` must be 1 (idle high) or CyOS fails to boot (see bug #9).

### I2C Protocol (matching MAME pcf8593.cpp)
- Address byte: 0xA2 (write) / 0xA3 (read)
- START: SDA goes high→low while SCL is high
- STOP: SDA goes low→high while SCL is high
- Data: MSB-first, sampled on SCL rising edge
- ACK: 9th bit, device pulls SDA low to acknowledge
- Repeated START: START condition without preceding STOP (used for read sequences)
- Register auto-increment after each byte, wraps at 0xFF (uint8_t)
- Register pointer stored as full 8-bit value; writes to addresses >= 16 are ignored

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

### CyOS Clock Protocol (from ROM disassembly at 0x4A46A4-0x4A47F0)
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
   - Write formula: `ADD.B #0x9C` is a mod-256 trick: (year + 156) mod 256 = year - 100

4. **Fresh boot default**: Year = 100 (2000), Jan 1, 00:00:00. Time set via CyOS Settings
   persists in NVRAM. The RTC maintains elapsed time while CyOS is running.

### CyOS I2C Bit-Bang Functions (in decompressed CyOS at 0x4A4558-0x4A4668)
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
1-bit output on Port 1 bit 3 (TIOCB1). AddressBus intercepts writes to Port 1 DR
(0xFFFF60) and tracks the speaker level. SpeakerOutput converts to 44.1kHz PCM audio.

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

## Development Workflow
1. Run emulator, find unimplemented opcode
2. Look up instruction in MAME h8.lst (mask/match pattern + microcode)
3. Implement in H8SCpu.java
4. Repeat until boot progresses further
5. When stuck in I/O polling loop, identify the register from MAME h8s2319.cpp and stub it
6. For CyOS-level debugging: dump RAM, disassemble with H8SDisasm, trace I2C/port access
