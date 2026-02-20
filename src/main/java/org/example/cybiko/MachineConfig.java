package org.example.cybiko;

/**
 * Machine configuration for different Cybiko hardware variants.
 *
 * V1: Cybiko Classic (H8S/2241 @ 11.0592 MHz, 512KB RAM, SPI flash)
 * V2: Cybiko v2 (H8S/2246 @ 11.0592 MHz, 256KB RAM, memory-mapped flash)
 * XT: Cybiko Xtreme (H8S/2323 @ 18.432 MHz, 2MB RAM, memory-mapped flash)
 */
public class MachineConfig {

    public enum MachineType { V1, V2, XT }

    public final MachineType type;
    public final String name;
    public final long clockHz;
    public final int cyclesPerFrame;

    // Memory sizes
    public final int bootRomSize;
    public final int externalRamSize;
    public final int flashRomSize;     // Memory-mapped flash; 0 for V1 (uses SPI)
    public final int onChipRamSize;

    // Memory base addresses
    public final int extRamBase;       // 0x200000 for V1/V2, 0x400000 for XT
    public final int lcdBase;          // 0x600000 for V1/V2, 0x100000 for XT
    public final int flashBase;        // Memory-mapped flash base; -1 for V1 (SPI)
    public final int flashRegionEnd;   // Last address of flash memory region (inclusive)
    public final int onChipRamBase;    // 0xFFEC00 for V1, 0xFFDC00 for V2/XT

    // Boot ROM mirroring
    public final int bootRomMirrorEnd; // Last address that maps to boot ROM

    // Timer configuration
    public final int timer16Channels;  // 3 for V1/V2, 6 for XT

    // DMA
    public final boolean hasDma;

    // SPI flash (V1 only)
    public final boolean hasSpiFlash;
    public final int spiFlashSize;     // 540672 (2048 pages x 264 bytes)

    // RTC I2C pin wiring on Port F
    public final int rtcSclBit;        // Bit mask for SCL in Port F DR
    public final int rtcSdaWriteBit;   // Bit mask for SDA write in Port F DR
    public final int rtcSdaReadBit;    // Bit mask for SDA in Port F input register
    // SDA write polarity: for all variants, writing the bit = SDA LOW (inverted)

    // Power/battery status
    public final int powerPortAddr;    // Address that returns power status
    public final int powerPortValue;   // Value indicating power on + battery OK

    // Keyboard
    public final int keyboardColumns;  // Number of active columns

    private MachineConfig(MachineType type, String name, long clockHz,
                          int bootRomSize, int externalRamSize, int flashRomSize, int onChipRamSize,
                          int extRamBase, int lcdBase, int flashBase, int flashRegionEnd, int onChipRamBase,
                          int bootRomMirrorEnd, int timer16Channels, boolean hasDma,
                          boolean hasSpiFlash, int spiFlashSize,
                          int rtcSclBit, int rtcSdaWriteBit, int rtcSdaReadBit,
                          int powerPortAddr, int powerPortValue,
                          int keyboardColumns) {
        this.type = type;
        this.name = name;
        this.clockHz = clockHz;
        this.cyclesPerFrame = (int) (clockHz / 60);
        this.bootRomSize = bootRomSize;
        this.externalRamSize = externalRamSize;
        this.flashRomSize = flashRomSize;
        this.onChipRamSize = onChipRamSize;
        this.extRamBase = extRamBase;
        this.lcdBase = lcdBase;
        this.flashBase = flashBase;
        this.flashRegionEnd = flashRegionEnd;
        this.onChipRamBase = onChipRamBase;
        this.bootRomMirrorEnd = bootRomMirrorEnd;
        this.timer16Channels = timer16Channels;
        this.hasDma = hasDma;
        this.hasSpiFlash = hasSpiFlash;
        this.spiFlashSize = spiFlashSize;
        this.rtcSclBit = rtcSclBit;
        this.rtcSdaWriteBit = rtcSdaWriteBit;
        this.rtcSdaReadBit = rtcSdaReadBit;
        this.powerPortAddr = powerPortAddr;
        this.powerPortValue = powerPortValue;
        this.keyboardColumns = keyboardColumns;
    }

    public static MachineConfig forType(MachineType type) {
        return switch (type) {
            case V1 -> new MachineConfig(
                MachineType.V1, "Cybiko Classic",
                11_059_200L,               // 11.0592 MHz
                0x8000,                    // 32KB boot ROM
                0x80000,                   // 512KB external RAM
                0,                         // No memory-mapped flash (SPI)
                0x1400,                    // 4KB + 1KB on-chip RAM (0xFFEC00-0xFFFFFF)
                0x200000,                  // External RAM base
                0x600000,                  // LCD base
                -1,                        // No memory-mapped flash
                -1,                        // No flash region end
                0xFFEC00,                  // On-chip RAM base
                0x007FFF,                  // Boot ROM: no mirroring (32KB only)
                3,                         // 3 Timer16 channels
                false,                     // No DMA
                true,                      // Has SPI flash (AT45DB041)
                540672,                    // 2048 pages x 264 bytes
                0x02,                      // SCL = Port F bit 1
                0x01,                      // SDA write = Port F bit 0 (inverted)
                0x01,                      // SDA read = Port F bit 0
                0xFFFF50,                  // Port 1 input register
                0x08,                      // Power on = bit 3
                9                          // 9 keyboard columns
            );
            case V2 -> new MachineConfig(
                MachineType.V2, "Cybiko v2",
                11_059_200L,               // 11.0592 MHz
                0x8000,                    // 32KB boot ROM
                0x40000,                   // 256KB external RAM
                0x40000,                   // 256KB memory-mapped flash
                0x2400,                    // 8KB + 1KB on-chip RAM
                0x200000,                  // External RAM base
                0x600000,                  // LCD base
                0x100000,                  // Flash base
                0x13FFFF,                  // Flash region end (256KB, no mirror)
                0xFFDC00,                  // On-chip RAM base
                0x007FFF,                  // Boot ROM: no mirroring
                3,                         // 3 Timer16 channels
                false,                     // No DMA
                false,                     // No SPI flash
                0,
                0x02,                      // SCL = Port F bit 1
                0x01,                      // SDA write = Port F bit 0 (inverted)
                0x01,                      // SDA read = Port F bit 0
                0xFFFF50,                  // Port 1 input register
                0x08,                      // Power on = bit 3
                9                          // 9 keyboard columns (same as V1 + esc quirk)
            );
            case XT -> new MachineConfig(
                MachineType.XT, "Cybiko Xtreme",
                18_432_000L,               // 18.432 MHz
                0x8000,                    // 32KB boot ROM
                0x200000,                  // 2MB external RAM
                0x80000,                   // 512KB memory-mapped flash
                0x2400,                    // 8KB + 1KB on-chip RAM
                0x400000,                  // External RAM base
                0x100000,                  // LCD base
                0x600000,                  // Flash base
                0x7FFFFF,                  // Flash region end (mirrored across 2MB)
                0xFFDC00,                  // On-chip RAM base
                0x03FFFF,                  // Boot ROM: mirrored to 256KB
                6,                         // 6 Timer16 channels
                true,                      // Has DMA
                false,                     // No SPI flash
                0,
                0x02,                      // SCL = Port F bit 1
                0x40,                      // SDA write = Port F bit 6 (inverted)
                0x40,                      // SDA read = Port F bit 6
                0xFFFF59,                  // Port A input register
                0xC0,                      // Power on + battery charged
                15                         // 15 keyboard columns
            );
        };
    }
}
