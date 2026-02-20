package org.example.cybiko;

/**
 * Memory-mapped I/O bus for Cybiko emulators (V1, V2, XT).
 * Routes reads/writes to the correct device based on address and machine config.
 *
 * XT Memory map:
 *   0x000000-0x03FFFF  Boot ROM (32KB mirrored)
 *   0x100000-0x100001  LCD controller (HD66421)
 *   0x200000-0x200003  USB controller (stub)
 *   0x400000-0x5FFFFF  External RAM (2MB)
 *   0x600000-0x7FFFFF  Flash ROM (512KB mirrored)
 *   0xE00000-0xEFFFFF  Keyboard matrix
 *   0xFFDC00-0xFFFFFF  On-chip RAM & I/O registers
 *
 * V1/V2 Memory map:
 *   0x000000-0x007FFF  Boot ROM (32KB, no mirror)
 *   0x200000-0x27FFFF  External RAM (V1: 512KB, V2: 256KB)
 *   0x600000-0x600001  LCD controller
 *   0xE00000-0xEFFFFF  Keyboard matrix
 *   0xFFEC00-0xFFFFFF  On-chip RAM & I/O registers (V1: 4KB)
 *   V2 only: 0x100000  Memory-mapped flash (256KB)
 */
public class AddressBus {
    private final MachineConfig config;

    private Memory bootRom;
    private Memory externalRam;
    private Memory flashRom;       // null for V1
    private Memory onChipRam;
    private HD66421Lcd lcd;

    // Timer peripherals
    private H8STimer8 timer8_0;
    private H8STimer8 timer8_1;
    private final H8STimer16[] timer16 = new H8STimer16[6]; // slots 0-5, null if not present

    // RTC (PCF8593) connected via I2C on Port F
    private PCF8593Rtc rtc = new PCF8593Rtc();

    // I/O registers
    private int tstr = 0;    // Timer Start Register (0xFFFFC0)
    private int ier = 0;     // Interrupt Enable Register (0xFFFF2E)
    private int isr = 0;     // Interrupt Status Register (0xFFFF2F)

    // Serial output buffers (one per SCI channel)
    private final StringBuilder[] sciOutput = {new StringBuilder(), new StringBuilder(), new StringBuilder()};

    // Speaker state - Port 1 bit 3 (TIOCB1) drives the speaker
    private int speakerLevel = 0;
    private SpeakerOutput speakerOutput;
    public void setSpeakerOutput(SpeakerOutput speaker) { this.speakerOutput = speaker; }
    public int getSpeakerLevel() { return speakerLevel; }

    // ADC state
    private int adcsr = 0;
    private int adcr = 0x7E;

    // DMA Controller state (XT only)
    private final int[] dmaRegs = new int[32];
    private final int[] dmaCtrl = new int[8];

    // SPI flash (V1 only) - AT45DB041 connected via SCI1
    private AT45DB041Flash spiFlash;
    private int sci1Rdr = 0;       // SCI1 receive data register (byte from SPI flash)
    private boolean sci1Rdrf = false; // SCI1 receive data register full

    // CPU reference for debug logging
    private H8SCpu cpu;
    public void setCpu(H8SCpu cpu) { this.cpu = cpu; }

    public AddressBus(MachineConfig config) {
        this.config = config;
    }

    /** Backward-compatible constructor (defaults to XT). */
    public AddressBus() {
        this(MachineConfig.forType(MachineConfig.MachineType.XT));
    }

    public void setBootRom(Memory bootRom) { this.bootRom = bootRom; }
    public void setExternalRam(Memory externalRam) { this.externalRam = externalRam; }
    public void setFlashRom(Memory flashRom) { this.flashRom = flashRom; }
    public void setOnChipRam(Memory onChipRam) { this.onChipRam = onChipRam; }
    public void setLcd(HD66421Lcd lcd) { this.lcd = lcd; }
    public void setTimer8_0(H8STimer8 timer) { this.timer8_0 = timer; }
    public void setTimer8_1(H8STimer8 timer) { this.timer8_1 = timer; }
    public void setTimer16(int ch, H8STimer16 timer) { timer16[ch] = timer; }
    public void setSpiFlash(AT45DB041Flash flash) { this.spiFlash = flash; }

    // Legacy setters (delegate to indexed version)
    public void setTimer16_0(H8STimer16 t) { timer16[0] = t; }
    public void setTimer16_1(H8STimer16 t) { timer16[1] = t; }
    public void setTimer16_2(H8STimer16 t) { timer16[2] = t; }
    public void setTimer16_3(H8STimer16 t) { timer16[3] = t; }
    public void setTimer16_4(H8STimer16 t) { timer16[4] = t; }
    public void setTimer16_5(H8STimer16 t) { timer16[5] = t; }

    /** Tick the RTC. Call once per frame to advance real-time clock. */
    public void tickRtc() { rtc.tick(); }

    public int read8(int address) {
        address &= 0xFFFFFF;
        return switch (decodeRegion(address)) {
            case BOOT_ROM -> bootRom.read8(address & 0x7FFF);
            case LCD -> lcd.read8(address & 1);
            case USB -> 0;
            case EXT_RAM -> externalRam.read8(address - config.extRamBase);
            case FLASH -> flashRom.read8((address - config.flashBase) & (config.flashRomSize - 1));
            case KEYBOARD -> {
                int kbVal = readKeyboard(address & ~1);
                yield (address & 1) == 0 ? (kbVal >> 8) & 0xFF : kbVal & 0xFF;
            }
            case ON_CHIP -> readOnChip8(address);
            case UNMAPPED -> { logUnmapped("read8", address); yield 0; }
        };
    }

    public int read16(int address) {
        address &= 0xFFFFFF;
        return switch (decodeRegion(address)) {
            case BOOT_ROM -> bootRom.read16(address & 0x7FFF);
            case LCD -> (lcd.read8(0) << 8) | lcd.read8(1);
            case USB -> 0;
            case EXT_RAM -> externalRam.read16(address - config.extRamBase);
            case FLASH -> flashRom.read16((address - config.flashBase) & (config.flashRomSize - 1));
            case KEYBOARD -> readKeyboard(address);
            case ON_CHIP -> readOnChip16(address);
            case UNMAPPED -> { logUnmapped("read16", address); yield 0; }
        };
    }

    public int read32(int address) {
        return (read16(address) << 16) | read16(address + 2);
    }

    public void write8(int address, int value) {
        address &= 0xFFFFFF;
        switch (decodeRegion(address)) {
            case BOOT_ROM -> {}
            case LCD -> lcd.write8(address & 1, value);
            case USB -> {}
            case EXT_RAM -> externalRam.write8(address - config.extRamBase, value);
            case FLASH -> {
                if (flashRom != null)
                    flashRom.write8((address - config.flashBase) & (config.flashRomSize - 1), value);
            }
            case KEYBOARD -> {}
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
            case EXT_RAM -> externalRam.write16(address - config.extRamBase, value);
            case FLASH -> {
                if (flashRom != null)
                    flashRom.write16((address - config.flashBase) & (config.flashRomSize - 1), value);
            }
            case KEYBOARD -> {}
            case ON_CHIP -> writeOnChip16(address, value);
            case UNMAPPED -> logUnmapped("write16", address);
        }
    }

    public void write32(int address, int value) {
        write16(address, value >>> 16);
        write16(address + 2, value & 0xFFFF);
    }

    // --- DMA Controller (XT only) ---
    private void executeDmaTransfer(int channel) {
        if (!config.hasDma) return;
        int base = channel * 16;
        int srcAddr = (dmaRegs[base] << 24) | (dmaRegs[base + 1] << 16)
                    | (dmaRegs[base + 2] << 8) | dmaRegs[base + 3];
        int dstAddr = (dmaRegs[base + 8] << 24) | (dmaRegs[base + 9] << 16)
                    | (dmaRegs[base + 10] << 8) | dmaRegs[base + 11];
        int count = (dmaRegs[base + 6] << 8) | dmaRegs[base + 7];

        int dmacrH = dmaCtrl[2 + channel * 2];
        boolean mode16 = (dmacrH & 0x80) != 0;

        srcAddr &= 0xFFFFFF;
        dstAddr &= 0xFFFFFF;

        if (count == 0) {
            if (channel == 1) dmaCtrl[7] &= ~0xC0;
            else dmaCtrl[7] &= ~0x30;
            return;
        }
        if (dmaDebugLog < 50) {
            System.err.printf("[DMA] ch%d: src=0x%06X dst=0x%06X count=%d mode16=%b PC=0x%06X%n",
                channel, srcAddr, dstAddr, count, mode16, cpu != null ? cpu.getLastStartPC() : -1);
            dmaDebugLog++;
        }

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

        if (channel == 1) dmaCtrl[7] &= ~0xC0;
        else dmaCtrl[7] &= ~0x30;
        dmaRegs[base + 6] = 0;
        dmaRegs[base + 7] = 0;
    }

    // --- Keyboard matrix ---
    private final int[] keyColumns = new int[15];

    public void setKeyState(int column, int bitmask, boolean pressed) {
        if (column < 0 || column >= 15) return;
        if (pressed) keyColumns[column] |= bitmask;
        else keyColumns[column] &= ~bitmask;
    }

    private int readKeyboard(int address) {
        int wordOffset = ((address - 0xE00000) & 0xFFFFF) >> 1;
        int data = 0xFFFF;
        for (int i = 0; i < config.keyboardColumns; i++) {
            if ((wordOffset & (1 << i)) == 0) {
                data &= ~keyColumns[i];
            }
        }
        return data;
    }

    // --- On-chip memory and I/O routing ---

    private static final int SSR_TDRE = 0x80;
    private static final int SSR_RDRF = 0x40;
    private static final int SSR_TEND = 0x04;

    private int routeTimer16Read8(int address) {
        if (address >= 0xFFFEA0 && address <= 0xFFFEAB && timer16[5] != null)
            return timer16[5].read8(address - 0xFFFEA0);
        if (address >= 0xFFFE90 && address <= 0xFFFE9B && timer16[4] != null)
            return timer16[4].read8(address - 0xFFFE90);
        if (address >= 0xFFFE80 && address <= 0xFFFE8F && timer16[3] != null)
            return timer16[3].read8(address - 0xFFFE80);
        if (address >= 0xFFFFD0 && address <= 0xFFFFDF && timer16[0] != null)
            return timer16[0].read8(address - 0xFFFFD0);
        if (address >= 0xFFFFE0 && address <= 0xFFFFEB && timer16[1] != null)
            return timer16[1].read8(address - 0xFFFFE0);
        if (address >= 0xFFFFF0 && address <= 0xFFFFFB && timer16[2] != null)
            return timer16[2].read8(address - 0xFFFFF0);
        return -1;
    }

    private int routeTimer16Read16(int address) {
        if (address >= 0xFFFEA0 && address <= 0xFFFEAB && timer16[5] != null)
            return timer16[5].read16(address - 0xFFFEA0);
        if (address >= 0xFFFE90 && address <= 0xFFFE9B && timer16[4] != null)
            return timer16[4].read16(address - 0xFFFE90);
        if (address >= 0xFFFE80 && address <= 0xFFFE8F && timer16[3] != null)
            return timer16[3].read16(address - 0xFFFE80);
        if (address >= 0xFFFFD0 && address <= 0xFFFFDF && timer16[0] != null)
            return timer16[0].read16(address - 0xFFFFD0);
        if (address >= 0xFFFFE0 && address <= 0xFFFFEB && timer16[1] != null)
            return timer16[1].read16(address - 0xFFFFE0);
        if (address >= 0xFFFFF0 && address <= 0xFFFFFB && timer16[2] != null)
            return timer16[2].read16(address - 0xFFFFF0);
        return -1;
    }

    private boolean routeTimer16Write8(int address, int value) {
        if (address >= 0xFFFEA0 && address <= 0xFFFEAB && timer16[5] != null) {
            timer16[5].write8(address - 0xFFFEA0, value); return true;
        }
        if (address >= 0xFFFE90 && address <= 0xFFFE9B && timer16[4] != null) {
            timer16[4].write8(address - 0xFFFE90, value); return true;
        }
        if (address >= 0xFFFE80 && address <= 0xFFFE8F && timer16[3] != null) {
            timer16[3].write8(address - 0xFFFE80, value); return true;
        }
        if (address >= 0xFFFFD0 && address <= 0xFFFFDF && timer16[0] != null) {
            timer16[0].write8(address - 0xFFFFD0, value); return true;
        }
        if (address >= 0xFFFFE0 && address <= 0xFFFFEB && timer16[1] != null) {
            timer16[1].write8(address - 0xFFFFE0, value); return true;
        }
        if (address >= 0xFFFFF0 && address <= 0xFFFFFB && timer16[2] != null) {
            timer16[2].write8(address - 0xFFFFF0, value); return true;
        }
        return false;
    }

    private boolean routeTimer16Write16(int address, int value) {
        if (address >= 0xFFFEA0 && address <= 0xFFFEAB && timer16[5] != null) {
            timer16[5].write16(address - 0xFFFEA0, value); return true;
        }
        if (address >= 0xFFFE90 && address <= 0xFFFE9B && timer16[4] != null) {
            timer16[4].write16(address - 0xFFFE90, value); return true;
        }
        if (address >= 0xFFFE80 && address <= 0xFFFE8F && timer16[3] != null) {
            timer16[3].write16(address - 0xFFFE80, value); return true;
        }
        if (address >= 0xFFFFD0 && address <= 0xFFFFDF && timer16[0] != null) {
            timer16[0].write16(address - 0xFFFFD0, value); return true;
        }
        if (address >= 0xFFFFE0 && address <= 0xFFFFEB && timer16[1] != null) {
            timer16[1].write16(address - 0xFFFFE0, value); return true;
        }
        if (address >= 0xFFFFF0 && address <= 0xFFFFFB && timer16[2] != null) {
            timer16[2].write16(address - 0xFFFFF0, value); return true;
        }
        return false;
    }

    // --- Timer8 routing ---
    private int readTimer8(int busOff) {
        H8STimer8 ch = (busOff % 2 == 0) ? timer8_0 : timer8_1;
        int regOff = (busOff & ~1);
        return ch != null ? ch.read(regOff) : 0;
    }

    private void writeTimer8(int busOff, int value) {
        H8STimer8 ch = (busOff % 2 == 0) ? timer8_0 : timer8_1;
        int regOff = (busOff & ~1);
        if (ch != null) ch.write(regOff, value);
    }

    private int onChipOffset(int address) {
        return address - config.onChipRamBase;
    }

    private int readOnChip8(int address) {
        // Timer16 channels
        int t16 = routeTimer16Read8(address);
        if (t16 >= 0) return t16;

        // Timer8 Channel 0/1: 0xFFFFB0-0xFFFFB9
        if (address >= 0xFFFFB0 && address <= 0xFFFFB9) {
            return readTimer8(address - 0xFFFFB0);
        }

        if (address == 0xFFFFC0) return tstr;
        if (address == 0xFFFFC1) return 0; // TSYR stub

        if (address == 0xFFFF2E) return ier;
        if (address == 0xFFFF2F) return isr;

        // SCI SSR registers
        if (address == 0xFFFF7C) return SSR_TDRE | SSR_TEND; // SCI0 SSR
        if (address == 0xFFFF84) {
            // SCI1 SSR - V1 needs RDRF when SPI flash has data
            if (spiFlash != null) {
                return SSR_TDRE | SSR_TEND | (sci1Rdrf ? SSR_RDRF : 0);
            }
            return SSR_TDRE | SSR_TEND;
        }
        if (address == 0xFFFF85) {
            // SCI1 RDR - V1 reads SPI flash response
            if (spiFlash != null) {
                sci1Rdrf = false;
                return sci1Rdr;
            }
            return 0;
        }
        if (address == 0xFFFF8C) return SSR_TDRE | SSR_TEND; // SCI2 SSR

        // DMA registers (XT only)
        if (config.hasDma) {
            if (address >= 0xFFFEE0 && address <= 0xFFFEFF) {
                return dmaRegs[address - 0xFFFEE0];
            }
            if (address >= 0xFFFF00 && address <= 0xFFFF07) {
                return dmaCtrl[address - 0xFFFF00];
            }
        }

        // System control
        if (address == 0xFFFF38) return 0; // SBYCR
        if (address == 0xFFFF39) return 0; // SYSCR
        if (address == 0xFFFF3B) return 0; // MDCR

        // Port Input Data Registers (0xFFFF50-0xFFFF5E)
        if (address >= 0xFFFF50 && address <= 0xFFFF5E) {
            // Power/battery status
            if (address == config.powerPortAddr) {
                return config.powerPortValue;
            }
            // Port F input: default pull-up, with RTC SDA conditionally cleared
            // V1: bit 2 = AT45DB041 RDY/BUSY (always ready), bit 0 = RTC SDA
            // XT: bit 6 = RTC SDA
            if (address == 0xFFFF5E) {
                int val = 0xFF;
                if (!rtc.sda_r()) val &= ~config.rtcSdaReadBit;
                return val;
            }
            return onChipRam.read8(onChipOffset(address));
        }

        // Port Data Registers (0xFFFF60-0xFFFF6F)
        if (address >= 0xFFFF60 && address <= 0xFFFF6F) {
            // XT: Port A (0xFFFF69) returns power status
            if (config.type == MachineConfig.MachineType.XT && address == 0xFFFF69) {
                return 0xC0;
            }
            // Port F DR: default pull-up, with RTC SDA conditionally cleared
            if (address == 0xFFFF6E) {
                int val = 0xFF;
                if (!rtc.sda_r()) val &= ~config.rtcSdaReadBit;
                return val;
            }
            return onChipRam.read8(onChipOffset(address));
        }

        // Watchdog stub
        if (address >= 0xFFFFBC && address <= 0xFFFFBF) return 0;

        // ADC registers
        if (address >= 0xFFFF90 && address <= 0xFFFF99) {
            if (address <= 0xFFFF97) return (address % 2 == 0) ? 0xCC : 0x00;
            if (address == 0xFFFF98) return adcsr;
            return adcr;
        }

        // I/O register fallthrough logging
        if (address >= 0xFFFE00 && ioFallthroughLog < 200) {
            int pc = (cpu != null) ? cpu.getLastStartPC() : -1;
            if (pc >= 0x400000 || (config.type != MachineConfig.MachineType.XT && pc >= 0x200000)) {
                int val = onChipRam.read8(onChipOffset(address));
                System.err.printf("[IO-FALL] read8 0x%06X=0x%02X PC=0x%06X%n", address, val, pc);
                ioFallthroughLog++;
            }
        }
        return onChipRam.read8(onChipOffset(address));
    }

    private int readOnChip16(int address) {
        int t16 = routeTimer16Read16(address);
        if (t16 >= 0) return t16;

        if (address >= 0xFFFE00) {
            return (readOnChip8(address) << 8) | readOnChip8(address + 1);
        }
        return onChipRam.read16(onChipOffset(address));
    }

    private void writeOnChip8(int address, int value) {
        if (routeTimer16Write8(address, value)) return;

        // Timer8 Channel 0/1: 0xFFFFB0-0xFFFFB9
        if (address >= 0xFFFFB0 && address <= 0xFFFFB9) {
            writeTimer8(address - 0xFFFFB0, value);
            return;
        }

        // TSTR - Timer Start Register (0xFFFFC0)
        if (address == 0xFFFFC0) {
            tstr = value & 0xFF;
            if (timer16[0] != null) timer16[0].setEnabled((tstr & 0x01) != 0);
            if (timer16[1] != null) timer16[1].setEnabled((tstr & 0x02) != 0);
            if (timer16[2] != null) timer16[2].setEnabled((tstr & 0x04) != 0);
            if (timer16[3] != null) timer16[3].setEnabled((tstr & 0x08) != 0);
            if (timer16[4] != null) timer16[4].setEnabled((tstr & 0x10) != 0);
            if (timer16[5] != null) timer16[5].setEnabled((tstr & 0x20) != 0);
            return;
        }

        if (address == 0xFFFF2E) { ier = value & 0xFF; return; }
        if (address == 0xFFFF2F) { isr &= value; return; }

        // SCI registers
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
                // V1: SCI1 TDR writes go to SPI flash
                if (channel == 1 && spiFlash != null) {
                    sci1Rdr = spiFlash.transfer(value & 0xFF);
                    sci1Rdrf = true;
                }
            }
            if (reg == 2 && channel == 1 && spiFlash != null) { // SCR write for SCI1
                // Detect DTC-driven SPI flash transfer setup
                // CyOS uses DTC with SCI1 for bulk SPI flash reads/writes
                executeSci1Dtc(value);
            }
            onChipRam.write8(onChipOffset(address), value);
            return;
        }

        // Port DDR registers (0xFFFEB0-0xFFFEBF)
        if (address >= 0xFFFEB0 && address <= 0xFFFEBF) {
            onChipRam.write8(onChipOffset(address), value);
            return;
        }

        // Port 1 write (0xFFFF60) - bit 3 (TIOCB1) drives speaker
        if (address == 0xFFFF60) {
            int level = (value & 0x08) != 0 ? 1 : 0;
            if (level != speakerLevel) {
                speakerLevel = level;
                if (speakerOutput != null) speakerOutput.setLevel(level);
            }
            onChipRam.write8(onChipOffset(address), value);
            return;
        }

        // Port 3 write (0xFFFF62) - V1: bit 4 controls SPI flash CS
        if (address == 0xFFFF62) {
            if (spiFlash != null) {
                // CS is active-low: bit 4 = 0 means selected
                spiFlash.cs((value & 0x10) == 0);
            }
            onChipRam.write8(onChipOffset(address), value);
            return;
        }

        // Port F write (0xFFFF6E) - I2C RTC bit-banging
        if (address == 0xFFFF6E) {
            rtc.scl_w((value & config.rtcSclBit) != 0);
            // SDA write is inverted: writing the bit = pull SDA low
            rtc.sda_w((value & config.rtcSdaWriteBit) == 0);
            onChipRam.write8(onChipOffset(address), value);
            return;
        }

        // Port data register writes (0xFFFF60-0xFFFF6F)
        if (address >= 0xFFFF60 && address <= 0xFFFF6F) {
            onChipRam.write8(onChipOffset(address), value);
            return;
        }

        // DMA registers (XT only)
        if (config.hasDma) {
            if (address >= 0xFFFEE0 && address <= 0xFFFEFF) {
                dmaRegs[address - 0xFFFEE0] = value & 0xFF;
                return;
            }
            if (address >= 0xFFFF00 && address <= 0xFFFF07) {
                int idx = address - 0xFFFF00;
                int oldVal = dmaCtrl[idx];
                dmaCtrl[idx] = value & 0xFF;
                if (idx == 7) {
                    boolean ch0Triggered = false;
                    if ((value & 0x10) != 0 && (oldVal & 0x10) == 0) {
                        executeDmaTransfer(0); ch0Triggered = true;
                    }
                    if (!ch0Triggered && (value & 0x20) != 0 && (oldVal & 0x20) == 0) {
                        executeDmaTransfer(0);
                    }
                    boolean ch1Triggered = false;
                    if ((value & 0x40) != 0 && (oldVal & 0x40) == 0) {
                        executeDmaTransfer(1); ch1Triggered = true;
                    }
                    if (!ch1Triggered && (value & 0x80) != 0 && (oldVal & 0x80) == 0) {
                        executeDmaTransfer(1);
                    }
                }
                return;
            }
        }

        // System control, watchdog stubs
        if (address == 0xFFFF38 || address == 0xFFFF39) return;
        if (address >= 0xFFFFBC && address <= 0xFFFFBF) return;

        // ADC registers
        if (address >= 0xFFFF90 && address <= 0xFFFF99) {
            if (address == 0xFFFF98) {
                if ((value & 0x20) != 0) {
                    adcsr = (value & 0x7F) | 0x80;
                } else {
                    adcsr = value & 0x7F;
                }
            } else if (address == 0xFFFF99) {
                adcr = value & 0xFF;
            }
            return;
        }

        onChipRam.write8(onChipOffset(address), value);
    }

    /**
     * Simulate DTC (Data Transfer Controller) for SCI1 SPI flash transfers.
     * V1 CyOS uses DTC to bulk-read/write SPI flash pages via SCI1.
     * DTC register info block at 0xFFFBD0 (set up by CyOS before SCR write):
     *   Byte 0: MRA (0x20=receive from RDR, 0x80=transmit to TDR)
     *   Bytes 1-3: SAR low 3 bytes (source address)
     *   Byte 4: forced to 0x00 by CyOS
     *   Bytes 5-7: DAR low 3 bytes (dest address)
     *   Bytes 8-9: CRA (transfer count, 16-bit)
     */
    private void executeSci1Dtc(int scrValue) {
        // Only trigger when RE (bit 4) and RIE (bit 6) are set, or TE (bit 5) and TIE (bit 7)
        boolean receiveMode = (scrValue & 0x50) == 0x50; // RE + RIE
        boolean transmitMode = (scrValue & 0xA0) == 0xA0; // TE + TIE
        if (!receiveMode && !transmitMode) return;

        // Check DTCER at 0xFFFF34 - bit 1 must be set for SCI1 DTC
        int dtcer = onChipRam.read8(onChipOffset(0xFFFF34));
        if ((dtcer & 0x02) == 0) return;

        // Read DTC register info block at 0xFFFBD0
        int dtcBase = onChipOffset(0xFFFBD0);
        int mra = onChipRam.read8(dtcBase);
        int darHi = onChipRam.read8(dtcBase + 4);
        int darLo = (onChipRam.read8(dtcBase + 5) << 16)
                  | (onChipRam.read8(dtcBase + 6) << 8)
                  | onChipRam.read8(dtcBase + 7);
        int dar = (darHi << 24) | darLo;
        int sarLo = (onChipRam.read8(dtcBase + 1) << 16)
                  | (onChipRam.read8(dtcBase + 2) << 8)
                  | onChipRam.read8(dtcBase + 3);
        int count = (onChipRam.read8(dtcBase + 8) << 8)
                  | onChipRam.read8(dtcBase + 9);

        if (count == 0) return;

        if (mra == 0x20 && receiveMode) {
            // Receive mode: read from SPI flash into destination buffer
            for (int i = 0; i < count; i++) {
                int b = spiFlash.transfer(0xFF);
                write8(dar + i, b);
            }
        } else if (mra == 0x80 && transmitMode) {
            // Transmit mode: send from source buffer to SPI flash
            for (int i = 0; i < count; i++) {
                int b = read8(sarLo + i);
                sci1Rdr = spiFlash.transfer(b);
            }
            sci1Rdrf = true;
        } else {
            return; // Unknown mode, don't handle
        }

        // Clear DTC count
        onChipRam.write8(dtcBase + 8, 0);
        onChipRam.write8(dtcBase + 9, 0);

        // Clear DTCER bit to disable DTC for this source
        onChipRam.write8(onChipOffset(0xFFFF34), dtcer & ~0x02);

        // Fire SCI1 RXI interrupt (vector 85) for completion handler
        if (cpu != null) {
            cpu.requestInterrupt(85);
        }
    }

    private void writeOnChip16(int address, int value) {
        if (routeTimer16Write16(address, value)) return;

        if (address >= 0xFFFE00) {
            writeOnChip8(address, (value >> 8) & 0xFF);
            writeOnChip8(address + 1, value & 0xFF);
            return;
        }
        onChipRam.write16(onChipOffset(address), value);
    }

    // --- Address decoding ---

    private enum Region {
        BOOT_ROM, LCD, USB, EXT_RAM, FLASH, KEYBOARD, ON_CHIP, UNMAPPED
    }

    private Region decodeRegion(int address) {
        // Boot ROM region
        if (address <= config.bootRomMirrorEnd) return Region.BOOT_ROM;

        // LCD
        if (address >= config.lcdBase && address <= config.lcdBase + 1) return Region.LCD;

        // USB (XT only)
        if (config.type == MachineConfig.MachineType.XT
                && address >= 0x200000 && address <= 0x200003) return Region.USB;

        // External RAM
        if (address >= config.extRamBase
                && address < config.extRamBase + config.externalRamSize) return Region.EXT_RAM;

        // Memory-mapped flash (V2 and XT)
        if (config.flashBase >= 0 && flashRom != null
                && address >= config.flashBase
                && address <= config.flashRegionEnd) return Region.FLASH;

        // Keyboard matrix
        if (address >= 0xE00000 && address <= 0xEFFFFF) return Region.KEYBOARD;

        // On-chip RAM & I/O registers
        if (address >= config.onChipRamBase && address <= 0xFFFFFF) return Region.ON_CHIP;

        return Region.UNMAPPED;
    }

    public String drainSerialOutput(int channel) {
        if (channel < 0 || channel > 2) return "";
        String s = sciOutput[channel].toString();
        sciOutput[channel].setLength(0);
        return s;
    }

    private int unmappedLogCount = 0;
    private int ioFallthroughLog = 0;
    private int dmaDebugLog = 0;
    private void logUnmapped(String op, int address) {
        if (unmappedLogCount < 200) {
            int pc = (cpu != null) ? cpu.getPC() : -1;
            System.err.printf("Bus: unmapped %s at 0x%06X (PC=0x%06X)%n", op, address, pc);
            unmappedLogCount++;
            if (unmappedLogCount == 200) {
                System.err.println("Bus: suppressing further unmapped warnings (200 reached)");
            }
        }
    }
}
