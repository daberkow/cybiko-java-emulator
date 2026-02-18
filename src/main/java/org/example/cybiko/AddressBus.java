package org.example.cybiko;

/**
 * Memory-mapped I/O bus for the Cybiko Xtreme.
 * Routes reads/writes to the correct device based on address.
 *
 * Memory map:
 *   0x000000-0x03FFFF  Boot ROM (32KB mirrored)
 *   0x100000-0x100001  LCD controller (HD66421)
 *   0x200000-0x200003  USB controller (stub)
 *   0x400000-0x5FFFFF  External RAM (2MB)
 *   0x600000-0x7FFFFF  Flash ROM (512KB mirrored)
 *   0xE00000-0xEFFFFF  Keyboard matrix
 *   0xFFDC00-0xFFFFFF  On-chip RAM & I/O registers
 *
 * On-chip I/O registers (from MAME h8s2319.cpp):
 *   0xFFFE80-0xFFFE8F  Timer16 Channel 3
 *   0xFFFE90-0xFFFE9B  Timer16 Channel 4
 *   0xFFFEA0-0xFFFEAB  Timer16 Channel 5
 *   0xFFFEB0-0xFFFEBF  Port DDR registers
 *   0xFFFEC4-0xFFFECE  IPR (Interrupt Priority)
 *   0xFFFF2C-0xFFFF2D  ISCR (Interrupt Sense Control)
 *   0xFFFF2E           IER (Interrupt Enable)
 *   0xFFFF2F           ISR (Interrupt Status)
 *   0xFFFF38-0xFFFF39  SBYCR/SYSCR (System Control)
 *   0xFFFF50-0xFFFF5F  Port input registers
 *   0xFFFF60-0xFFFF6F  Port output registers
 *   0xFFFF78-0xFFFF7E  SCI0
 *   0xFFFF80-0xFFFF86  SCI1
 *   0xFFFF90-0xFFFF99  ADC
 *   0xFFFFB0-0xFFFFB9  Timer8 Channel 0/1
 *   0xFFFFBC-0xFFFFBF  Watchdog
 *   0xFFFFC0           TSTR (Timer16 Start)
 *   0xFFFFC1           TSYR (Timer16 Sync)
 *   0xFFFFD0-0xFFFFDF  Timer16 Channel 0
 *   0xFFFFE0-0xFFFFEB  Timer16 Channel 1
 *   0xFFFFF0-0xFFFFFB  Timer16 Channel 2
 */
public class AddressBus {
    private Memory bootRom;       // 32KB, mirrored across 0x000000-0x03FFFF
    private Memory externalRam;   // 2MB
    private Memory flashRom;      // 512KB, mirrored across 0x600000-0x7FFFFF
    private Memory onChipRam;     // ~9KB (0xFFDC00-0xFFFFFF)
    private HD66421Lcd lcd;

    // Timer peripherals
    private H8STimer8 timer8_0;
    private H8STimer16 timer16_0;
    private H8STimer16 timer16_1;
    private H8STimer16 timer16_2;
    private H8STimer16 timer16_3;
    private H8STimer16 timer16_4;
    private H8STimer16 timer16_5;

    // RTC (PCF8593) connected via I2C on Port F
    private PCF8593Rtc rtc = new PCF8593Rtc();

    // I/O registers
    private int tstr = 0;    // Timer Start Register (0xFFFFC0)
    private int ier = 0;     // Interrupt Enable Register (0xFFFF2E)
    private int isr = 0;     // Interrupt Status Register (0xFFFF2F)

    // Serial output buffers (one per SCI channel)
    private final StringBuilder[] sciOutput = {new StringBuilder(), new StringBuilder(), new StringBuilder()};

    // ADC state
    private int adcsr = 0;   // ADC Control/Status Register (bit 7=ADF, bit 5=ADST)
    private int adcr = 0x7E; // ADC Control Register (default from manual)

    // DMA Controller state
    // Channel 0: 0xFFFEE0-0xFFFEEF, Channel 1: 0xFFFEF0-0xFFFEFF
    // Control: 0xFFFF00-0xFFFF07
    private final int[] dmaRegs = new int[32]; // 0xFFFEE0-0xFFFFFF (channel regs)
    private final int[] dmaCtrl = new int[8];  // 0xFFFF00-0xFFFF07 (control regs)

    public void setBootRom(Memory bootRom) { this.bootRom = bootRom; }
    public void setExternalRam(Memory externalRam) { this.externalRam = externalRam; }
    public void setFlashRom(Memory flashRom) { this.flashRom = flashRom; }
    public void setOnChipRam(Memory onChipRam) { this.onChipRam = onChipRam; }
    public void setLcd(HD66421Lcd lcd) { this.lcd = lcd; }
    private H8STimer8 timer8_1;
    public void setTimer8_0(H8STimer8 timer) { this.timer8_0 = timer; }
    public void setTimer8_1(H8STimer8 timer) { this.timer8_1 = timer; }
    public void setTimer16_0(H8STimer16 timer) { this.timer16_0 = timer; }
    public void setTimer16_1(H8STimer16 timer) { this.timer16_1 = timer; }
    public void setTimer16_2(H8STimer16 timer) { this.timer16_2 = timer; }
    public void setTimer16_3(H8STimer16 timer) { this.timer16_3 = timer; }
    public void setTimer16_4(H8STimer16 timer) { this.timer16_4 = timer; }
    public void setTimer16_5(H8STimer16 timer) { this.timer16_5 = timer; }

    /** Tick the RTC. Call once per frame to advance real-time clock. */
    public void tickRtc() { rtc.tick(); }

    public int read8(int address) {
        address &= 0xFFFFFF; // 24-bit address space
        return switch (decodeRegion(address)) {
            case BOOT_ROM -> bootRom.read8(address & 0x7FFF);
            case LCD -> lcd.read8(address & 1);
            case USB -> 0; // stub
            case EXT_RAM -> externalRam.read8(address - 0x400000);
            case FLASH -> flashRom.read8((address - 0x600000) & 0x7FFFF);
            case KEYBOARD -> {
                // 16-bit keyboard value; for 8-bit reads, return the appropriate byte
                int kbVal = readKeyboard(address & ~1); // align to word
                yield (address & 1) == 0 ? (kbVal >> 8) & 0xFF : kbVal & 0xFF;
            }
            case ON_CHIP -> readOnChip8(address);
            case UNMAPPED -> { logUnmapped("read8", address); yield 0xFF; }
        };
    }

    public int read16(int address) {
        address &= 0xFFFFFF;
        return switch (decodeRegion(address)) {
            case BOOT_ROM -> bootRom.read16(address & 0x7FFF);
            case LCD -> (lcd.read8(0) << 8) | lcd.read8(1);
            case USB -> 0;
            case EXT_RAM -> externalRam.read16(address - 0x400000);
            case FLASH -> flashRom.read16((address - 0x600000) & 0x7FFFF);
            case KEYBOARD -> readKeyboard(address);
            case ON_CHIP -> readOnChip16(address);
            case UNMAPPED -> { logUnmapped("read16", address); yield 0xFFFF; }
        };
    }

    public int read32(int address) {
        return (read16(address) << 16) | read16(address + 2);
    }

    public void write8(int address, int value) {
        address &= 0xFFFFFF;
        switch (decodeRegion(address)) {
            case BOOT_ROM -> {} // ignore writes to ROM
            case LCD -> lcd.write8(address & 1, value);
            case USB -> {} // stub
            case EXT_RAM -> externalRam.write8(address - 0x400000, value);
            case FLASH -> flashRom.write8((address - 0x600000) & 0x7FFFF, value);
            case KEYBOARD -> {} // read-only
            case ON_CHIP -> writeOnChip8(address, value);
            case UNMAPPED -> logUnmapped("write8", address);
        }
    }

    public void write16(int address, int value) {
        address &= 0xFFFFFF;
        switch (decodeRegion(address)) {
            case BOOT_ROM -> {}
            case LCD -> { lcd.write8(0, value >> 8); lcd.write8(1, value & 0xFF); }
            case USB -> {}
            case EXT_RAM -> externalRam.write16(address - 0x400000, value);
            case FLASH -> flashRom.write16((address - 0x600000) & 0x7FFFF, value);
            case KEYBOARD -> {}
            case ON_CHIP -> writeOnChip16(address, value);
            case UNMAPPED -> logUnmapped("write16", address);
        }
    }

    public void write32(int address, int value) {
        write16(address, value >>> 16);
        write16(address + 2, value & 0xFFFF);
    }

    // --- DMA Controller ---
    // Executes a DMA transfer for the given channel (0 or 1).
    // Channel registers: base + 0x00=MARAH, +0x02=MARAL, +0x04=IOARA, +0x06=ETCRA,
    //                    +0x08=MARBH, +0x0A=MARBL, +0x0C=IOARB, +0x0E=ETCRB
    // DMACR1 (0xFFFF04-05): bit 15 = 16-bit mode

    private void executeDmaTransfer(int channel) {
        int base = channel * 16; // Channel 0: offset 0, Channel 1: offset 16
        // Read source address (MAR_A: 32-bit from MARAH:MARAL)
        int srcAddr = (dmaRegs[base] << 24) | (dmaRegs[base + 1] << 16)
                    | (dmaRegs[base + 2] << 8) | dmaRegs[base + 3];
        // Read destination address (MAR_B: 32-bit from MARBH:MARBL)
        int dstAddr = (dmaRegs[base + 8] << 24) | (dmaRegs[base + 9] << 16)
                    | (dmaRegs[base + 10] << 8) | dmaRegs[base + 11];
        // Read transfer count (ETCRA: 16-bit)
        int count = (dmaRegs[base + 6] << 8) | dmaRegs[base + 7];

        // DMACR for this channel: channel 0 = 0xFFFF02-03, channel 1 = 0xFFFF04-05
        int dmacrH = dmaCtrl[2 + channel * 2];
        int dmacrL = dmaCtrl[3 + channel * 2];
        boolean mode16 = (dmacrH & 0x80) != 0; // Bit 15 of DMACR = 16-bit transfer

        srcAddr &= 0xFFFFFF;
        dstAddr &= 0xFFFFFF;
        if (count == 0) count = 0x10000; // 0 means 65536

        // Perform the transfer
        if (mode16) {
            for (int i = 0; i < count; i++) {
                int val = read16(srcAddr);
                write16(dstAddr, val);
                srcAddr = (srcAddr + 2) & 0xFFFFFF;
                dstAddr = (dstAddr + 2) & 0xFFFFFF;
            }
        } else {
            for (int i = 0; i < count; i++) {
                int val = read8(srcAddr);
                write8(dstAddr, val);
                srcAddr = (srcAddr + 1) & 0xFFFFFF;
                dstAddr = (dstAddr + 1) & 0xFFFFFF;
            }
        }

        // Clear the enable/busy bit after transfer completes
        // DMABCR (0xFFFF07): clear the channel's enable bit
        if (channel == 1) {
            dmaCtrl[7] &= ~0x40; // Clear bit 6 (DEA1/DTE1)
        } else {
            dmaCtrl[7] &= ~0x04; // Clear bit 2 (DEA0/DTE0)
        }

        // Clear transfer count
        dmaRegs[base + 6] = 0;
        dmaRegs[base + 7] = 0;
    }

    // --- Keyboard matrix ---
    // The Cybiko Xtreme keyboard is a 15-column matrix read from 0xE00000-0xEFFFFF.
    // Bits 0-14 of the address offset select columns (active-low: bit=0 means scan that column).
    // Return value: active-LOW (0xFFFF = no keys pressed, bit=0 means key pressed).
    // Each column has up to 16 key bits (16-bit return value).
    private final int[] keyColumns = new int[15]; // Active-high key state per column

    /** Set a key as pressed (active-high: bit=1 means pressed) in the given column. */
    public void setKeyState(int column, int bitmask, boolean pressed) {
        if (column < 0 || column >= 15) return;
        if (pressed) keyColumns[column] |= bitmask;
        else keyColumns[column] &= ~bitmask;
    }

    private int readKeyboard(int address) {
        // MAME's keyboard handler uses word offsets: offset = (address - base) / 2
        // Column i is selected when bit i of the word offset is 0
        int wordOffset = ((address - 0xE00000) & 0xFFFFF) >> 1;
        int data = 0xFFFF; // Start with all keys released
        for (int i = 0; i < 15; i++) {
            if ((wordOffset & (1 << i)) == 0) { // Column selected (active-low)
                data &= ~keyColumns[i]; // Clear bits for pressed keys
            }
        }
        return data;
    }

    // --- On-chip memory and I/O routing ---

    // SCI Status Register bits
    private static final int SSR_TDRE = 0x80;
    private static final int SSR_TEND = 0x04;

    /**
     * Route a Timer16 channel read by checking the address against the channel's base/end.
     * Returns -1 if no channel matched.
     */
    private int routeTimer16Read8(int address) {
        if (address >= 0xFFFEA0 && address <= 0xFFFEAB && timer16_5 != null)
            return timer16_5.read8(address - 0xFFFEA0);
        if (address >= 0xFFFE90 && address <= 0xFFFE9B && timer16_4 != null)
            return timer16_4.read8(address - 0xFFFE90);
        if (address >= 0xFFFE80 && address <= 0xFFFE8F && timer16_3 != null)
            return timer16_3.read8(address - 0xFFFE80);
        if (address >= 0xFFFFD0 && address <= 0xFFFFDF && timer16_0 != null)
            return timer16_0.read8(address - 0xFFFFD0);
        if (address >= 0xFFFFE0 && address <= 0xFFFFEB && timer16_1 != null)
            return timer16_1.read8(address - 0xFFFFE0);
        if (address >= 0xFFFFF0 && address <= 0xFFFFFB && timer16_2 != null)
            return timer16_2.read8(address - 0xFFFFF0);
        return -1;
    }

    private int routeTimer16Read16(int address) {
        if (address >= 0xFFFEA0 && address <= 0xFFFEAB && timer16_5 != null)
            return timer16_5.read16(address - 0xFFFEA0);
        if (address >= 0xFFFE90 && address <= 0xFFFE9B && timer16_4 != null)
            return timer16_4.read16(address - 0xFFFE90);
        if (address >= 0xFFFE80 && address <= 0xFFFE8F && timer16_3 != null)
            return timer16_3.read16(address - 0xFFFE80);
        if (address >= 0xFFFFD0 && address <= 0xFFFFDF && timer16_0 != null)
            return timer16_0.read16(address - 0xFFFFD0);
        if (address >= 0xFFFFE0 && address <= 0xFFFFEB && timer16_1 != null)
            return timer16_1.read16(address - 0xFFFFE0);
        if (address >= 0xFFFFF0 && address <= 0xFFFFFB && timer16_2 != null)
            return timer16_2.read16(address - 0xFFFFF0);
        return -1;
    }

    /** Returns true if address was handled by a Timer16 channel. */
    private boolean routeTimer16Write8(int address, int value) {
        if (address >= 0xFFFEA0 && address <= 0xFFFEAB && timer16_5 != null) {
            timer16_5.write8(address - 0xFFFEA0, value); return true;
        }
        if (address >= 0xFFFE90 && address <= 0xFFFE9B && timer16_4 != null) {
            timer16_4.write8(address - 0xFFFE90, value); return true;
        }
        if (address >= 0xFFFE80 && address <= 0xFFFE8F && timer16_3 != null) {
            timer16_3.write8(address - 0xFFFE80, value); return true;
        }
        if (address >= 0xFFFFD0 && address <= 0xFFFFDF && timer16_0 != null) {
            timer16_0.write8(address - 0xFFFFD0, value); return true;
        }
        if (address >= 0xFFFFE0 && address <= 0xFFFFEB && timer16_1 != null) {
            timer16_1.write8(address - 0xFFFFE0, value); return true;
        }
        if (address >= 0xFFFFF0 && address <= 0xFFFFFB && timer16_2 != null) {
            timer16_2.write8(address - 0xFFFFF0, value); return true;
        }
        return false;
    }

    private boolean routeTimer16Write16(int address, int value) {
        if (address >= 0xFFFEA0 && address <= 0xFFFEAB && timer16_5 != null) {
            timer16_5.write16(address - 0xFFFEA0, value); return true;
        }
        if (address >= 0xFFFE90 && address <= 0xFFFE9B && timer16_4 != null) {
            timer16_4.write16(address - 0xFFFE90, value); return true;
        }
        if (address >= 0xFFFE80 && address <= 0xFFFE8F && timer16_3 != null) {
            timer16_3.write16(address - 0xFFFE80, value); return true;
        }
        if (address >= 0xFFFFD0 && address <= 0xFFFFDF && timer16_0 != null) {
            timer16_0.write16(address - 0xFFFFD0, value); return true;
        }
        if (address >= 0xFFFFE0 && address <= 0xFFFFEB && timer16_1 != null) {
            timer16_1.write16(address - 0xFFFFE0, value); return true;
        }
        if (address >= 0xFFFFF0 && address <= 0xFFFFFB && timer16_2 != null) {
            timer16_2.write16(address - 0xFFFFF0, value); return true;
        }
        return false;
    }

    // --- Timer8 routing (two channels, interleaved registers) ---
    // Bus offsets: TCR(0,1), TCSR(2,3), TCORA(4)/TCORB(5)=ch0, TCORA(6)/TCORB(7)=ch1, TCNT(8,9)
    // Internal H8STimer8 offsets: TCR=0, TCSR=2, TCORA=4, TCORB=6, TCNT=8

    private int readTimer8(int busOff) {
        if (busOff <= 3) {
            // TCR (0,1) and TCSR (2,3): even=ch0, odd=ch1
            H8STimer8 ch = (busOff % 2 == 0) ? timer8_0 : timer8_1;
            int regOff = busOff & ~1; // 0→0(TCR), 2→2(TCSR)
            return ch != null ? ch.read(regOff) : 0;
        } else if (busOff <= 7) {
            // TCOR: 4,5=ch0(TCORA,TCORB), 6,7=ch1(TCORA,TCORB)
            H8STimer8 ch = (busOff < 6) ? timer8_0 : timer8_1;
            int regOff = (busOff % 2 == 0) ? 4 : 6; // TCORA or TCORB
            return ch != null ? ch.read(regOff) : 0;
        } else {
            // TCNT: 8=ch0, 9=ch1
            H8STimer8 ch = (busOff == 8) ? timer8_0 : timer8_1;
            return ch != null ? ch.read(8) : 0;
        }
    }

    private void writeTimer8(int busOff, int value) {
        if (busOff <= 3) {
            H8STimer8 ch = (busOff % 2 == 0) ? timer8_0 : timer8_1;
            int regOff = busOff & ~1;
            if (ch != null) ch.write(regOff, value);
        } else if (busOff <= 7) {
            H8STimer8 ch = (busOff < 6) ? timer8_0 : timer8_1;
            int regOff = (busOff % 2 == 0) ? 4 : 6;
            if (ch != null) ch.write(regOff, value);
        } else {
            H8STimer8 ch = (busOff == 8) ? timer8_0 : timer8_1;
            if (ch != null) ch.write(8, value);
        }
    }

    private int readOnChip8(int address) {
        // Timer16 channels (check all 6)
        int t16 = routeTimer16Read8(address);
        if (t16 >= 0) return t16;

        // Timer8 Channel 0/1: 0xFFFFB0-0xFFFFB9 (interleaved registers)
        // TCR: 0=ch0, 1=ch1; TCSR: 2=ch0, 3=ch1; TCOR: 4-5=ch0, 6-7=ch1; TCNT: 8=ch0, 9=ch1
        if (address >= 0xFFFFB0 && address <= 0xFFFFB9) {
            return readTimer8(address - 0xFFFFB0);
        }

        // TSTR - Timer Start Register (0xFFFFC0)
        if (address == 0xFFFFC0) return tstr;

        // TSYR - Timer Sync Register (0xFFFFC1) - stub
        if (address == 0xFFFFC1) return 0;

        // IER - Interrupt Enable Register (0xFFFF2E)
        if (address == 0xFFFF2E) return ier;

        // ISR - Interrupt Status Register (0xFFFF2F)
        if (address == 0xFFFF2F) return isr;

        // SCI SSR registers: report transmitter ready
        // SCI0 SSR=0xFFFF7C, SCI1 SSR=0xFFFF84
        if (address == 0xFFFF7C || address == 0xFFFF84) {
            return SSR_TDRE | SSR_TEND;
        }
        // SCI2 SSR=0xFFFF8C: report transmitter ready (same as SCI0/SCI1)
        if (address == 0xFFFF8C) {
            return SSR_TDRE | SSR_TEND;
        }

        // DMA channel registers (0xFFFEE0-0xFFFEFF)
        if (address >= 0xFFFEE0 && address <= 0xFFFEFF) {
            return dmaRegs[address - 0xFFFEE0];
        }

        // DMA control registers (0xFFFF00-0xFFFF07)
        if (address >= 0xFFFF00 && address <= 0xFFFF07) {
            return dmaCtrl[address - 0xFFFF00];
        }

        // System control registers - stub
        if (address == 0xFFFF38) return 0; // SBYCR
        if (address == 0xFFFF39) return 0; // SYSCR
        if (address == 0xFFFF3B) return 0; // MDCR

        // Port Input Data Registers (0xFFFF50-0xFFFF5E)
        // These read the actual pin states (for input pins, from external devices)
        // Port A input (0xFFFF59): battery/power status (from MAME xtpower_r)
        // Port F input (0xFFFF5E): I2C RTC SDA line
        if (address >= 0xFFFF50 && address <= 0xFFFF5E) {
            if (address == 0xFFFF59) {
                return 0xC0; // Port A: power on + battery charged
            }
            if (address == 0xFFFF5E) {
                // Port F: I2C RTC - bit 6 = SDA from RTC
                return rtc.sda_r() ? 0x40 : 0x00;
            }
            return onChipRam.read8(address - 0xFFDC00);
        }

        // Port Data Registers (0xFFFF60-0xFFFF6F)
        // Port A (0xFFFF69): battery/power status (from MAME xtpower_r)
        //   Bit 7 = On/Off button (1=on), Bit 6 = Battery charged (1=charged)
        if (address >= 0xFFFF60 && address <= 0xFFFF6F) {
            if (address == 0xFFFF69) {
                return 0xC0; // Port A: power on + battery charged
            }
            if (address == 0xFFFF6E) {
                // Port F: I2C RTC - bit 6 = SDA from RTC
                return rtc.sda_r() ? 0x40 : 0x00;
            }
            return onChipRam.read8(address - 0xFFDC00);
        }

        // Watchdog - stub (return 0 for TCSR, counter stopped)
        if (address >= 0xFFFFBC && address <= 0xFFFFBF) return 0;

        // ADC registers (0xFFFF90-0xFFFF99)
        // ADDRA-ADDRD at 0xFFFF90-97 (10-bit result, left-aligned in 16 bits)
        // ADCSR at 0xFFFF98 (bit 7=ADF, bit 5=ADST, bits 2-0=channel select)
        // ADCR at 0xFFFF99
        if (address >= 0xFFFF90 && address <= 0xFFFF99) {
            if (address <= 0xFFFF97) return (address % 2 == 0) ? 0xCC : 0x00;
            if (address == 0xFFFF98) return adcsr;
            return adcr;
        }

        // Default: on-chip RAM backing
        return onChipRam.read8(address - 0xFFDC00);
    }

    private int readOnChip16(int address) {
        // Timer16 channels
        int t16 = routeTimer16Read16(address);
        if (t16 >= 0) return t16;

        // For I/O registers (0xFFFE00+), compose from two 8-bit reads
        if (address >= 0xFFFE00) {
            return (readOnChip8(address) << 8) | readOnChip8(address + 1);
        }
        return onChipRam.read16(address - 0xFFDC00);
    }

    private void writeOnChip8(int address, int value) {
        // Timer16 channels
        if (routeTimer16Write8(address, value)) return;

        // Timer8 Channel 0/1: 0xFFFFB0-0xFFFFB9 (interleaved registers)
        if (address >= 0xFFFFB0 && address <= 0xFFFFB9) {
            writeTimer8(address - 0xFFFFB0, value);
            return;
        }

        // TSTR - Timer Start Register (0xFFFFC0)
        if (address == 0xFFFFC0) {
            tstr = value & 0xFF;
            // Each bit enables a timer16 channel (bit 0=ch0, bit 5=ch5)
            if (timer16_0 != null) timer16_0.setEnabled((tstr & 0x01) != 0);
            if (timer16_1 != null) timer16_1.setEnabled((tstr & 0x02) != 0);
            if (timer16_2 != null) timer16_2.setEnabled((tstr & 0x04) != 0);
            if (timer16_3 != null) timer16_3.setEnabled((tstr & 0x08) != 0);
            if (timer16_4 != null) timer16_4.setEnabled((tstr & 0x10) != 0);
            if (timer16_5 != null) timer16_5.setEnabled((tstr & 0x20) != 0);
            return;
        }

        // IER - Interrupt Enable Register (0xFFFF2E)
        if (address == 0xFFFF2E) {
            ier = value & 0xFF;
            // System.err.printf("[IO] IER write: 0x%02X%n", ier);
            return;
        }

        // ISR - Interrupt Status Register (0xFFFF2F)
        // Writing 0 clears bits, writing 1 has no effect
        if (address == 0xFFFF2F) {
            isr &= value;
            return;
        }

        // SCI registers - capture serial output (SCI0, SCI1, SCI2)
        // SCI0: 0xFFFF78-0xFFFF7E, SCI1: 0xFFFF80-0xFFFF86, SCI2: 0xFFFF88-0xFFFF8E
        if (address >= 0xFFFF78 && address <= 0xFFFF8E) {
            int channel, reg;
            if (address >= 0xFFFF88) {
                channel = 2; reg = address - 0xFFFF88;
            } else if (address >= 0xFFFF80) {
                channel = 1; reg = address - 0xFFFF80;
            } else {
                channel = 0; reg = address - 0xFFFF78;
            }
            if (reg == 3) { // TDR - Transmit Data Register
                char c = (char)(value & 0x7F);
                sciOutput[channel].append(c >= 0x20 ? c : (c == '\n' ? '\n' : '.'));
            }
            onChipRam.write8(address - 0xFFDC00, value);
            return;
        }

        // Port DDR registers (0xFFFEB0-0xFFFEBF) - store in RAM
        // Port F DDR at 0xFFFEBE - I2C RTC SDA is driven via DDR bit 6
        // On H8S, DDR=1 means output (drives pin to DR value), DDR=0 means input (pin released/HIGH)
        // The old MAME code (cybiko_m.cpp) had SDA connected to PFDDR, not PFDR.
        // CyOS controls SDA by toggling DDR: DDR bit6=1 → output LOW (SDA asserted),
        // DDR bit6=0 → input/released (SDA HIGH via pull-up)
        if (address >= 0xFFFEB0 && address <= 0xFFFEBF) {
            if (address == 0xFFFEBE) {
                // Port F DDR write - SDA is controlled via DDR bit 6
                // DDR bit6=1 (output mode) → SDA driven LOW (inverted: output=1 means assert/LOW)
                // DDR bit6=0 (input mode) → SDA released HIGH
                rtc.sda_w((value & 0x40) == 0); // same inversion as DR: bit6=0 → SDA HIGH
            }
            onChipRam.write8(address - 0xFFDC00, value);
            return;
        }

        // Port F write (0xFFFF6E) - I2C RTC bit-banging
        // SCL is driven by Port F DR bit 1 (per MAME: PFDR → scl_w)
        // SDA is driven by Port F DDR bit 6 (per MAME: PFDDR → sda_w) — handled above
        if (address == 0xFFFF6E) {
            rtc.scl_w((value & 0x02) != 0);
            // Do NOT call sda_w here — SDA is controlled via DDR (0xFFFEBE), not DR
            onChipRam.write8(address - 0xFFDC00, value);
            return;
        }

        // Port data register writes (0xFFFF60-0xFFFF6F) - store in RAM
        if (address >= 0xFFFF60 && address <= 0xFFFF6F) {
            onChipRam.write8(address - 0xFFDC00, value);
            return;
        }

        // DMA channel registers (0xFFFEE0-0xFFFEFF) - store for DMA transfers
        if (address >= 0xFFFEE0 && address <= 0xFFFEFF) {
            dmaRegs[address - 0xFFFEE0] = value & 0xFF;
            return;
        }

        // DMA control registers (0xFFFF00-0xFFFF07)
        if (address >= 0xFFFF00 && address <= 0xFFFF07) {
            int idx = address - 0xFFFF00;
            int oldVal = dmaCtrl[idx];
            dmaCtrl[idx] = value & 0xFF;
            // Check if DMA transfer should be triggered
            // DMABCR low byte (0xFFFF07): bit 6 set = start transfer for channel 1
            if (idx == 7 && (value & 0x40) != 0 && (oldVal & 0x40) == 0) {
                executeDmaTransfer(1);
            }
            // DMABCR low byte: bit 2 = DEA0 for channel 0
            if (idx == 7 && (value & 0x04) != 0 && (oldVal & 0x04) == 0) {
                executeDmaTransfer(0);
            }
            return;
        }

        // IPR - Interrupt Priority Registers (0xFFFEC4-0xFFFECE)
        if (address >= 0xFFFEC4 && address <= 0xFFFECE) {
            onChipRam.write8(address - 0xFFDC00, value);
            return;
        }

        // System control, watchdog - ignore writes (stub)
        if (address == 0xFFFF38 || address == 0xFFFF39) return; // SBYCR/SYSCR
        if (address >= 0xFFFFBC && address <= 0xFFFFBF) return; // Watchdog

        // ADC registers
        if (address >= 0xFFFF90 && address <= 0xFFFF99) {
            if (address == 0xFFFF98) {
                // ADCSR write: bit 5 (ADST) starts conversion, bit 7 (ADF) cleared by writing 0
                if ((value & 0x20) != 0) {
                    // ADST set - start conversion, immediately complete it
                    adcsr = (value & 0x7F) | 0x80; // set ADF, clear ADST
                } else {
                    adcsr = value & 0x7F; // ADF can only be cleared by writing 0
                }
            } else if (address == 0xFFFF99) {
                adcr = value & 0xFF;
            }
            return;
        }

        // All other I/O writes go to on-chip RAM backing
        onChipRam.write8(address - 0xFFDC00, value);
    }

    private void writeOnChip16(int address, int value) {
        // Timer16 channels
        if (routeTimer16Write16(address, value)) return;

        // For other I/O registers, split into two 8-bit writes
        if (address >= 0xFFFE00) {
            writeOnChip8(address, (value >> 8) & 0xFF);
            writeOnChip8(address + 1, value & 0xFF);
            return;
        }
        onChipRam.write16(address - 0xFFDC00, value);
    }

    // --- Address decoding ---

    private enum Region {
        BOOT_ROM, LCD, USB, EXT_RAM, FLASH, KEYBOARD, ON_CHIP, UNMAPPED
    }

    private Region decodeRegion(int address) {
        if (address <= 0x03FFFF) return Region.BOOT_ROM;
        if (address >= 0x100000 && address <= 0x100001) return Region.LCD;
        if (address >= 0x200000 && address <= 0x200003) return Region.USB;
        if (address >= 0x400000 && address <= 0x5FFFFF) return Region.EXT_RAM;
        if (address >= 0x600000 && address <= 0x7FFFFF) return Region.FLASH;
        if (address >= 0xE00000 && address <= 0xEFFFFF) return Region.KEYBOARD;
        if (address >= 0xFFDC00 && address <= 0xFFFFFF) return Region.ON_CHIP;
        return Region.UNMAPPED;
    }

    /** Drain and return buffered serial output for the given SCI channel. */
    public String drainSerialOutput(int channel) {
        if (channel < 0 || channel > 2) return "";
        String s = sciOutput[channel].toString();
        sciOutput[channel].setLength(0);
        return s;
    }

    private void logUnmapped(String op, int address) {
        System.err.printf("Bus: unmapped %s at 0x%06X%n", op, address);
    }
}
