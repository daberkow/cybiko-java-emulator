# Bugs Found & Fixed

Detailed debugging notes for each bug discovered during emulator development.
See CLAUDE.md for the condensed lessons-learned summary.

## 1. LCD alternating black rows
- **Symptom**: LCD displayed alternating black/white rows
- **Root cause**: HD66421 register masking used `& 0x11` instead of proper per-register masks
- **Fix**: Corrected register index masking

## 2. Timer16 interrupt flooding
- **Symptom**: CPU overwhelmed by timer interrupts
- **Root cause**: Timer16 fired interrupts every tick instead of on flag transition 0->1
- **Fix**: Added flag-transition guard (only fire interrupt when status flag goes from 0 to 1)

## 3. CPU halted permanently with masked interrupts
- **Symptom**: CPU entered SLEEP and never woke up, even with pending interrupts
- **Root cause**: `processInterrupts()` returned false when I flag was set (CCR bit 7),
  so CPU stayed in halted state forever
- **Fix**: On real H8S, any interrupt request wakes CPU from sleep regardless of I flag.
  Added check in step() to wake CPU when halted and pending interrupts exist.

## 4. MOV.L @ERs+, ERd post-increment clobber when Rs==Rd
- **Symptom**: CyOS crashed after task stack switch. `MOV.L @ER7+, ER7` (restore SP)
  returned SP+4 instead of the loaded value.
- **Root cause**: `er[rl] = bus.read32(er[rh]); er[rh] += 4;` - when rh==rl, the
  post-increment overwrites the loaded value.
- **Fix**: Use temp variable: `val = read; increment; store val to dest`.
  Applied same pattern to all MOV pre-dec/post-inc variants (B, W, L sizes).

## 5. Timer16 per-channel clock source mapping
- **Symptom**: Timer delay loops ran at wrong speed
- **Root cause**: Used a single fixed prescaler table for all channels. In H8S/2319,
  TPSC values 4-7 map to different sources per channel (some are external clocks,
  some are prescalers like /256 or /1024).
- **Fix**: Implemented per-channel clock divisor tables matching MAME h8s2319.cpp.

## 6. Port A input register address mismatch
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

## 7. DMA controller needed for keyboard input
- **Symptom**: CyOS displayed welcome screen but keyboard input had no effect
- **Root cause**: CyOS reads the keyboard matrix via DMA transfers, not direct CPU reads.
  The keyboard scan routine sets up DMA channel 0 to transfer from 0xE00000+ (keyboard
  matrix addresses) to 0xFFDC00 (on-chip RAM), then reads the results from on-chip RAM.
  Without DMA emulation, the on-chip RAM destination always contained zeros.
- **Fix**: Implemented DMA controller in AddressBus with channel registers at 0xFFFEE0-0xFFFEFF
  and control registers at 0xFFFF00-0xFFFF07. DMA transfers execute immediately when the
  enable bit (DMABCR bit 4/5/6/7 for channels 0/1/2/3) is written with DTE set.

## 8. RTC I2C protocol - first byte always zero
- **Symptom**: CyOS displayed time as "228.18.1900" (garbage date/time)
- **Root cause**: Old PCF8593Rtc.java used a custom shift-register I2C state machine.
  After `processReceivedByte()` switched to SEND mode and called `prepareNextByte()`
  (which loaded shiftReg with the first data byte), the code did `shiftReg = 0` which
  overwrote the loaded data. The first byte of every I2C read was always 0x00.
- **Fix**: Complete rewrite of PCF8593Rtc.java to match MAME pcf8593.cpp exactly.
  Uses `active` flag, `dataRecv[]` buffer, `bits` counter with `>8` for ACK handling,
  MSB-first bit operations matching MAME (`0x80 >> bits` for receive, `data[pos] >> (7-bits)`
  for send). Initializes with system time via `LocalDateTime.now()`.

## 9. RTC SDA idle state broke CyOS boot (CRITICAL)
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

## 10. I2C SDA routing differs between XT and V1/V2 (FIXED)
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

## 11. Timer isRunning() snapshot broke Timer8_1 mid-frame start
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

## 12. Port F input pins missing pull-ups broke V1 SPI flash access
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

## 13. V1 CyOS stuck waiting for DTC-driven SPI flash transfer
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
