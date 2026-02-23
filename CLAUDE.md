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
```

### Options
| Flag | Description |
|------|-------------|
| `--machine v1\|v2\|xt` | Select machine type (default: `xt`) |
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

ROM files:
- Xtreme: `src/main/resources/cybikoxt/cyrom150.bin` + `cyos_v1508.bin` (from MAME cybikoxt.zip)
- Classic V1: `cyrom112.bin` + `flash_v1246.bin` (from MAME cybiko.zip)
App files: `../cybiko-archive/cybiko/cybiko/apps/` (e.g., `calc.app`, `dice/dice.app`).

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
- `H8STimer16` - 16-bit timer (TPU) with per-channel clock source tables
- `Memory` - Simple byte-array backed memory
- `HD66421Lcd` - LCD controller emulation. Reuses framebuffer array (no per-frame allocation).
- `PCF8593Rtc` - Real-time clock with I2C bit-bang protocol (matches MAME pcf8593.cpp)
- `CfsImage` - CFS (Cybiko File System) image builder/reader (matches MAME cybikoxt.cpp)
- `SpeakerOutput` - 1-bit speaker audio via javax.sound.sampled (44.1kHz, 8-bit mono).
  Takes clock rate from MachineConfig for correct sample timing.
- `FrameBufferRenderer` / `SwingRenderer` / `ConsoleRenderer` - Display
- `SwingRenderer` - Swing GUI with keyboard input. Bulk setRGB for rendering. Takes
  MachineConfig to select XT (15-col, Fn+letter numbers) or V1 (9-col, dedicated numbers)
  keyboard layout. Queue-based Fn+letter injection for XT number keys. Minimum key hold
  time (3 frames) for all keys.

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
| 2 | ~60-120 | 4ACC524E | SPI flash loading (dot pattern) | 0x20B756 (CyOS) |
| 3 | ~240-300 | 5580FBFF | CyOS decompression / early init | 0x205CC2 |
| 4 | ~360 | C0DBEF72 | Animated Cybiko logo (same as XT) | 0x219C8E (halted) |
| 5 | ~480-540 | F48DA453 | CyOS initialization | 0x219C8E (halted) |
| 6 | ~660+ | EFD624A8 | Final screen (CyOS UI) | 0x219C8E (halted) |

### V2 Boot Phases
| Phase | Frames | VRAM Hash  | Description | PC |
|-------|--------|------------|-------------|-----|
| 1 | ~1-60 | 4ACC524E | SPI flash loading (dot pattern) | 0x108720 |
| 2 | ~1080 | A6642987 | Transitional (CyOS init) | 0x108D2A |
| 3 | ~1140+ | C0DBEF72 | Animated Cybiko logo (same as XT/V1) | 0x11ED98 (halted) |

V2 currently stalls at phase 3 (Cybiko logo). The desktop app never starts because
RF hardware init never completes. Service stub resolves non-RF set_task_state calls.

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

### 10. I2C SDA routing differs between XT and V1/V2 (FIXED)
- **Symptom**: V2 produced zero I2C transactions during boot. XT worked fine.
- **Root cause**: V1/V2 CyOS uses the open-drain I2C model where SDA is controlled
  via Port F DDR (0xFFFEBE), not DR (0xFFFF6E). DDR bit 0 = output mode drives SDA
  low (via DR=0); DDR bit 0 = input mode releases SDA high (via pull-up). XT writes
  both SCL and SDA to DR in the same byte write. Our code only triggered the RTC
  from DR writes, so V1/V2 I2C was completely silent.
- **Fix**: Machine-specific I2C routing in AddressBus.java:
  - V1/V2: SDA triggered from Port F DDR (0xFFFEBE) writes; SCL from DR (0xFFFF6E)
  - XT: Both SCL and SDA triggered from Port F DR writes (0xFFFF6E)
- **Result**: V2 now has full I2C transactions matching the CyOS RTC protocol:
  boot init reads all 16 registers, writes defaults (control=0x84, time=Jan 1 00:00:00),
  then starts counting (control=0x04).

### 11. Timer isRunning() snapshot broke Timer8_1 mid-frame start
- **Symptom**: CyOS stuck at logo screen (boot phase 3). Timer8_0 interrupts fire
  but CyOS never progresses past the boot splash.
- **Root cause**: Performance optimization snapshotted `timer8_1.isRunning()` once
  at the start of each frame (every 307,200 cycles). CyOS starts Timer8_1 mid-frame
  by writing TCR=0x03. The snapshot was `false` from frame start, so Timer8_1 wasn't
  ticked for the rest of that frame.
- **Fix** (first attempt): Removed per-frame snapshot, ticked all timers unconditionally.
- **Current approach**: Re-introduced per-frame `isRunning()` caching (acceptable since
  worst case is 1 frame / ~16ms delay for a timer enabled mid-frame). This works because
  CyOS doesn't rely on Timer8_1 starting within the same frame it's configured - the
  original bug was from a more aggressive optimization that cached per-cycle, not per-frame.
- **Lesson**: Per-frame timer caching is safe; per-cycle would be too aggressive.

### 12. Port F input pins missing pull-ups broke V1 SPI flash access
- **Symptom**: V1 boot ROM stuck in tight loop at PC=0x002BC4, never accessing SPI flash.
- **Root cause**: V1 boot ROM polls Port F input register (0xFFFF5E) bit 2 waiting for
  AT45DB041 RDY/BUSY pin to go high (device ready). Our handler only returned the RTC
  SDA bit (bit 0 for V1, bit 6 for XT), leaving all other bits at 0. On real hardware,
  unconnected input pins with pull-up resistors read as 1.
- **Fix**: Changed Port F input (0xFFFF5E) and Port F DR (0xFFFF6E) reads to return 0xFF
  (all pull-ups high) with the RTC SDA bit conditionally cleared when SDA is low. This
  correctly models: bit 2 = AT45DB041 RDY (always ready), bit 0/6 = RTC SDA state.
- **Lesson**: Input port registers should default to pull-up state (0xFF) for unhandled
  bits, not 0x00. Real hardware has pull-ups on most port pins.

### 13. V1 CyOS stuck waiting for DTC-driven SPI flash transfer
- **Symptom**: V1 CyOS loaded from SPI flash, displayed dot pattern on LCD, but stuck
  at PC=0x206B82 polling a flag at 0x21F07A that never became non-zero.
- **Root cause**: V1 CyOS uses the H8S DTC (Data Transfer Controller) for bulk SPI flash
  reads. CyOS sets up a DTC register block at 0xFFFBD0 (source=SCI1 RDR, dest=RAM buffer,
  count=page size), enables DTC via DTCER (0xFFFF34), then sets SCI1 SCR=0x50 (RE+RIE).
  In real hardware, the SCI in clock-synchronous mode auto-generates clock when RE=1,
  receiving bytes that trigger RXI interrupts, which the DTC handles automatically. Our
  emulation had no DTC and no autonomous SCI receive, so no data was ever transferred.
- **Fix**: Implemented lightweight DTC simulation in AddressBus. When SCI1 SCR is written
  with RE+RIE (receive mode) or TE+TIE (transmit mode) and DTCER bit 1 is set:
  1. Reads DTC register block from on-chip RAM at 0xFFFBD0
  2. MRA=0x20 (receive): calls spiFlash.transfer(0xFF) for each byte, writes to dest
  3. MRA=0x80 (transmit): reads from source buffer, calls spiFlash.transfer() for each
  4. Clears DTC count and DTCER, fires SCI1 RXI interrupt (vector 85) for completion
  This bypasses the need for full DTC emulation or autonomous SCI clock generation.
- **Lesson**: The H8S DTC is simpler than DMA but still essential for V1 CyOS. It's
  interrupt-triggered, using register info blocks in on-chip RAM. CyOS's SPI flash driver
  uses polled SCI for command bytes, then switches to DTC for bulk data transfer.

## Known Issues
- **Fn+number keys intermittent**: Number keys (Fn+letter combos) work ~80-90% of the
  time but occasionally produce the letter instead. SwingRenderer uses queue-based
  delayed Fn+letter injection (Fn pressed first, letter delayed 3-4 frames) but fast
  typing can still race with CyOS's DMA keyboard scan. Root cause is thread
  synchronization: Swing EDT modifies key matrix while emulation thread reads it.
  Performance is not the bottleneck (~4ms/frame, 24% of budget).
- **V2 CyOS stuck at Cybiko logo**: V2 boots through SPI flash loading, reaches the
  animated Cybiko logo (VRAM hash C0DBEF72, same as V1/XT) but never progresses to
  desktop. I2C RTC communication now works (bug #10 fix), but the desktop app never
  starts because RF hardware init never completes (entry[0x91] stays 0, all display
  rendering skipped). A service stub auto-resolves non-RF set_task_state calls to
  bypass startup waits. The RF object (0x202CB2) must never be resolved via stub —
  causes failed HW init. See V2 Investigation below. MAME also cannot fully boot V2
  CyOS with these ROMs.

## Current Status
- Multi-machine support: V1 (Classic), V2, and XT (Xtreme) selectable via --machine flag
- CyOS fully boots to interactive "Congratulations!" welcome screen (or desktop with NVRAM) on XT
- V1 CyOS fully boots from SPI flash (AT45DB041 + DTC bulk transfer) to interactive UI
- Keyboard input works (letters, navigation keys, Fn+letter for numbers on XT, dedicated numbers on V1)
- Minimum key hold time (3 frames) prevents fast key presses from being missed
- RTC I2C protocol works on all variants (V1/V2 use DDR-based SDA, XT uses DR-based SDA)
- LCD renders full CyOS UI with menus and text input
- Timer8 and Timer16 interrupts drive the OS scheduler
- DMA controller handles keyboard matrix scans (XT only; V1/V2 use direct reads)
- App loading via CFS filesystem (--app wraps .app files in proper CFS block format)
- Persistent NVRAM (--nvram saves/restores external RAM between sessions)
- Speaker audio output (1-bit, Port 1 bit 3 / TIOCB1)
- VRAM CRC32 hash and frame timing in STATUS log
- Frame time ~4ms (24% of 16.7ms budget), JIT-optimized after first second
- No unimplemented opcodes in the current execution path

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

### CyOS Clock Protocol (EXPERIMENTAL - not yet working in emulator)
From ROM disassembly at 0x4A46A4-0x4A47F0. This documents how CyOS expects the
RTC to behave. Currently the I2C reads don't return correct values because the
d75fd54-style DR-based triggering doesn't properly handle all I2C sequences.
Fixing this requires getting the DDR-based SDA approach working (see bug #10).

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

### CyOS I2C Bit-Bang Functions (EXPERIMENTAL - for future RTC fix)
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

## V2 Investigation: LCD Never Updates After Boot

### Summary
V2 CyOS (cyrom117.bin + cyos_v1358.bin + flash_v1358.bin) boots past the boot ROM
but the LCD never updates after the initial test pattern. The root cause is that CyOS's
main UI task blocks reading "cyos.cfg" from the SPI flash (AT45DB041) when the file
data spans multiple CFS pages. MAME also cannot fully boot V2 CyOS with these ROMs.

### Boot Sequence (V2)
1. Boot ROM draws gradient test pattern to LCD (frame ~1-45)
2. Boot ROM loads CyOS from memory-mapped flash at 0x100000
3. CyOS decompresses, creates 6 tasks, starts RTOS scheduler (frame ~45-60)
4. DTC+SCI1 reads CFS directory from SPI flash (2,102 DTC transfers during boot)
5. At frame ~123, timer dispatches event handler task (0x20889E, pri=200) and
   animation task (0x208706, pri=50) simultaneously
6. Event handler opens "/default/System.a" (succeeds), then "cyos.cfg"
7. "cyos.cfg" first ~84 bytes read successfully from pre-loaded CFS page
8. Next read (4 bytes of second record) **blocks** - continuation page not fetched
9. Task suspended (pri=0, not re-queued), LCD rendering code at 0x10AEE4 never reached

### V2 CyOS Task Map
| Task Addr | Base Pri | Handler (vtable[4]) | Role |
|-----------|----------|---------------------|------|
| 0x2001C4 | 1 | (idle/SLEEP) | Dispatcher/idle loop |
| 0x208BA4 | 201 | 0x1256C6 | Semaphore event loop |
| 0x20889E | 200 | 0x10B1C4 | **Event dispatcher (UI/LCD)** |
| 0x208706 | 50 | 0x123AB8 | Scrolling/animation |
| 0x20775E | 5 | 0x119F62 | Periodic housekeeping |
| 0x20831E | 5 | 0x117EDE | I2C/RTC related |

### V2 RTOS Scheduler (disassembled)
Task Control Block (partial):
| Offset | Field | Description |
|--------|-------|-------------|
| 44 (0x2C) | state | 0=idle, 1=prepared/ready |
| 48 (0x30) | accum_time | CPU time consumed |
| 84 (0x54) | pri84 | Current/effective priority |
| 85 (0x55) | base_pri | Base priority (restored by prepare_task) |
| 88 (0x58) | next | Queue link pointer |
| 92 (0x5C) | saved_sp | Saved stack pointer |
| 100 (0x64) | queue_ptr | Current wait queue |

Key globals: `0x20022C`=current_task, `0x200230`=ready_queue, `0x200039`=sched_lock

Scheduler functions:
- `yield(task)` at 0x1067F0: sets pri84=0, calls reschedule
- `reschedule()` at 0x1068E4: if ready_head.pri >= current.pri → context switch.
  **Tasks with pri=0 are NOT re-queued** (0x106934: BEQ skips insert_into_queue)
- `prepare_task(task)` at 0x106874: copies base_pri→pri84, inserts into ready_queue
- `setup_dispatch(task)` at 0x106C1A: calls 0x107838 + prepare_task + optional reschedule

### The Blocking Chain
1. Handler 0x10B1C4 calls event_poll (0x10B05E) → blocks until event arrives
2. Timer dispatches task → event_poll returns 0x0007 (bits 0,1,2 set)
3. Handler calls 0x10BDE8 (process event, opens "cyos.cfg")
4. 0x10BDE8 reads file via producer-consumer queue model:
   - `file_read_bytes(handle, buf, N)` at 0x110248
   - Calls `semaphore_wait(handle+8, count)` at 0x10FC4E
   - If queue has data → returns immediately; if empty → blocks task
5. First CFS page data (pre-loaded during file_open) consumed after ~84 bytes
6. Next `semaphore_wait` finds empty queue → `set_task_queue` → yield → **suspended**
7. No mechanism fetches the continuation CFS page → task permanently blocked

### CyOS File I/O Architecture (V2)
```
file_open("cyos.cfg")
  → CFS directory lookup (cached in RAM at 0x20F21C from boot DTC reads)
  → DTC reads first data page from SPI flash (AT45DB041)
  → Pre-loads page data into file handle queue at handle+8
  → Returns handle with ~170 bytes of data ready

file_read_bytes(handle, buf, count)
  → semaphore_wait(handle+8, count) — blocks if queue empty
  → dequeue bytes from queue
  → update file offset at handle+68
  → if queue exhausted: BLOCKS (continuation page fetch broken)
```

File handle structure:
| Offset | Content |
|--------|---------|
| 0 | ptr → status struct (bit 0=READY/closed, bit 1=ERROR) |
| 8 | ptr → semaphore/data queue |
| 68 | file read position (32-bit) |

### Why Continuation Pages Aren't Fetched
The CyOS file I/O uses a queue-based producer-consumer model. When the file handle's
data queue is exhausted, the consumer task blocks on the semaphore waiting for more
data. A producer (likely a callback or background task) should:
1. Detect queue underflow
2. Initiate DTC+SCI1 read of the next CFS page from SPI flash
3. Enqueue the page data into the file handle's queue
4. Signal the semaphore to unblock the consumer

This continuation mechanism is not working. Possible causes:
- The queue underflow callback isn't being triggered
- The DTC setup for continuation reads uses a different code path than the initial read
- The background flash driver task (possibly 0x208BA4) is never dispatched to process
  the I/O request
- MAME's V2 emulation has the same limitation (hangs at "CyOS loading")

### V2 Timer Queue Architecture (disassembled)
The queue at 0x200234 is a lightweight TIMER QUEUE separate from the full RTOS task
scheduler. Timer entry structure (from disassembly of scheduler at 0x1076FA):
| Offset | Field | Description |
|--------|-------|-------------|
| 0x00 | deadline | 32-bit tick count to fire at (compared with 0x20004E) |
| 0x04 | interval | 32-bit repeat period (re-enqueued after each fire) |
| 0x08 | one_shot | Byte flag (1=one-shot, 0=repeating) |
| 0x0C | next | Pointer to next entry in queue |
| 0x10 | desc_ptr | Callback descriptor pointer → descriptor[0x08] = handler function |

Active timer entries at frame ~1200:
- `0x208706`: interval=250, handler=0x1239CC (display handler)
- `0x20775E`: interval=5000, handler=0x106AB2 (idle handler)

### V2 Display Handler Chain (0x1239CC)
The display handler runs every 250 ticks (~once per 16 frames) and gates rendering
through a chain of entry flags:
1. **entry[0x96]** (offset 150): If 0 → calls idle handler (0x106AB2), skips display
2. **entry[0x9A]** (offset 154): If non-zero → consumer loop (display_update 0x123752)
3. **entry[0x91]** (offset 145): **If 0 → skips ALL rendering at 0x123C8E**

The display handler also accumulates scroll velocity and increments a counter. After
256 handler calls, it calls task_resume to wake the display init task (0x123AB8) which
then enters another 256-call cycle. This takes ~4096 frames per cycle.

### V2 Display Rendering Blocked - Root Cause
After extensive debugging (V2Debug106-V2Debug118), the full chain was traced:
1. **entry[0x91] stays 0** — no CyOS code ever sets it because the desktop app never starts
2. **Desktop app depends on RF service readiness** — RF init never completes without hardware
3. **RF hardware not emulated** — RF2915 radio SPI commands get no response
4. **Even forcing entry[0x91]=1** doesn't help — there's no display content registered

The display system itself works correctly (display_update called 594+ times, handler
fires on schedule), but it has no content to render.

### V2 Code Stub Injection Technique
Debug scripts use H8S machine code injection to bypass the RF dependency:
```
Write stub to RAM after splash (not before — CyOS init overwrites early RAM):
  STUB_ADDR = 0x23FE00 (near end of external RAM)
  MOV.L #RF_OBJ, ER0     ; 7A 00 00 20 2C B2
  JSR @change_state_to_1  ; 5E 10 74 6E
  JMP @scheduler_return   ; 5A 10 98 DC
```
When the scheduler runs (0x1098DC), redirect to stub if RF has pending waiters.
This successfully wakes the RF task (rfState 0→1, rfWl cleared), but the desktop
app still doesn't start because it needs more than just RF state=1.

### V2 set_task_state Auto-Resolve Strategy
The emulator always-resolves non-RF set_task_state calls (not once-per-pair). Services
like 0x208B84 are polled ~2.3M times during normal boot. The RF object (0x202CB2) is
blacklisted during boot because resolving it early triggers RF hardware init that
fails and permanently breaks boot (confirmed by V2Debug114).

### Next Steps to Fix V2
1. **Implement RF chip SPI stub** — respond with "initialized OK" to RF2915 commands
2. **Find RF→desktop dependency** — identify which CyOS function starts the desktop app
   and what conditions it checks beyond RF state=1
3. **Try different ROMs** — a web-based V2 emulator exists that works with its own ROMs;
   different CyOS versions may handle RF dependency differently
4. **Compare with V1** — V1 CyOS file I/O works and has no RF dependency for boot
5. **Disassemble 0x10FC4E** (semaphore_wait) to understand queue refill for multi-page reads

### MAME V2 Key Source Files
- `src/mame/cybiko/cybiko.cpp` lines 86-100: V2 memory map
- `src/mame/cybiko/cybiko_m.cpp` lines 126-132: V2 keyboard quirk (ESC bit OR)
- `src/devices/machine/at45dbxx.cpp`: AT45DB041 SPI flash protocol (bit-level)
- `src/tools/imgtool/modules/cybiko.cpp`: V1/V2 CFS format (264-byte pages)

### V2 CFS Format (differs from XT)
V1/V2 use 264-byte pages (vs XT's 258-byte):
| Offset | Size | Content |
|--------|------|---------|
| 0-3 | 4 | CRC32 checksum |
| 4-5 | 2 | Write count |
| 6-7 | 2 | CRC16 verification |
| 8-263 | 256 | Block data (same format as XT) |

flash_v1358.bin: 2048 pages × 264 bytes = 540,672 bytes. "cyos.cfg" found at page 287.

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
