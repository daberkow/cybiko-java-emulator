package org.example.cybiko;

/**
 * Hitachi HD66421 LCD controller emulation.
 *
 * 160x100 pixel display, 2-bit grayscale (4 shades).
 * VRAM is 4000 bytes (4 pixels per byte, 2 bits each).
 *
 * Memory-mapped at 0x100000 (register index) and 0x100001 (register data).
 *
 * Registers:
 *   0x00 R0  - Control 1 (display on/off, reverse, standby, etc.)
 *   0x01 R1  - Control 2 (bias, duty, auto-increment direction, blink)
 *   0x02 R2  - X address (0-39, since 160/4=40 bytes per line)
 *   0x03 R3  - Y address (0-99)
 *   0x04 R4  - RAM data read/write
 *   0x05 R5  - Display start line
 *   0x06 R6  - Blink start line
 *   0x07 R7  - Blink end line
 *   0x08 R8  - Blink register 1
 *   0x09 R9  - Blink register 2
 *   0x0A R10 - Blink register 3
 *   0x0B R11 - Partial display block
 *   0x0C R12 - Grayscale palette 1 (for pixel value 0)
 *   0x0D R13 - Grayscale palette 2 (for pixel value 1)
 *   0x0E R14 - Grayscale palette 3 (for pixel value 2)
 *   0x0F R15 - Grayscale palette 4 (for pixel value 3)
 *   0x10 R16 - Contrast control
 *   0x11 R17 - Plane selection
 */
public class HD66421Lcd {
    public static final int WIDTH = 160;
    public static final int HEIGHT = 100;
    public static final int VRAM_SIZE = WIDTH * HEIGHT / 4; // 4000 bytes

    // Register indices
    public static final int REG_CONTROL1 = 0x00;
    public static final int REG_CONTROL2 = 0x01;
    public static final int REG_X_ADDR = 0x02;
    public static final int REG_Y_ADDR = 0x03;
    public static final int REG_RAM = 0x04;
    public static final int REG_START_LINE = 0x05;
    public static final int REG_PALETTE1 = 0x0C;
    public static final int REG_PALETTE2 = 0x0D;
    public static final int REG_PALETTE3 = 0x0E;
    public static final int REG_PALETTE4 = 0x0F;
    public static final int REG_CONTRAST = 0x10;

    // Control register 1 bits
    public static final int R0_ADC  = 0x01;
    public static final int R0_HOLT = 0x02;
    public static final int R0_REV  = 0x04;
    public static final int R0_AMP  = 0x08;
    public static final int R0_PWR  = 0x10;
    public static final int R0_STBY = 0x20;
    public static final int R0_DISP = 0x40;
    public static final int R0_RMW  = 0x80;

    // Control register 2 bits
    public static final int R1_BLK  = 0x01;
    public static final int R1_INC  = 0x02; // 0=Y increment, 1=X increment

    private final int[] regs = new int[32]; // registers (indexed 0-31 by regIndex)
    private final byte[] vram = new byte[VRAM_SIZE];
    private final int[] frameBuffer = new int[WIDTH * HEIGHT]; // reused every frame
    private int regIndex;      // currently selected register
    private int xAddr, yAddr;  // current X/Y position

    public HD66421Lcd() {
        // Default palette: white, light gray, dark gray, black
        regs[REG_PALETTE1] = 0;
        regs[REG_PALETTE2] = 10;
        regs[REG_PALETTE3] = 20;
        regs[REG_PALETTE4] = 31;
        regs[REG_CONTRAST] = 0;
    }

    /** Read from LCD registers. offset=0 reads reg index, offset=1 reads reg data. */
    public int read8(int offset) {
        if (offset == 0) {
            return regIndex;
        } else {
            if (regIndex == REG_RAM) {
                int addr = yAddr * (WIDTH / 4) + xAddr;
                if (addr >= 0 && addr < VRAM_SIZE) {
                    return vram[addr] & 0xFF;
                }
                return 0;
            }
            return regs[regIndex];
        }
    }

    /** Write to LCD registers. offset=0 sets reg index, offset=1 writes reg data. */
    public void write8(int offset, int value) {
        value &= 0xFF;
        if (offset == 0) {
            regIndex = value & 0x1F;
        } else {
            // Store register value (matching MAME: m_reg[m_cmd] = data)
            regs[regIndex] = value;

            switch (regIndex) {
                case REG_X_ADDR -> xAddr = value;
                case REG_Y_ADDR -> yAddr = value;
                case REG_RAM -> {
                    int addr = yAddr * (WIDTH / 4) + xAddr;
                    if (addr >= 0 && addr < VRAM_SIZE) {
                        vram[addr] = (byte) value;
                    }
                    // Auto-increment (matching MAME reg_dat_w)
                    if ((regs[REG_CONTROL2] & R1_INC) != 0) {
                        xAddr++;
                    } else {
                        yAddr++;
                    }
                    // X overflow wraps to next Y (always checked, per MAME)
                    if (xAddr >= WIDTH / 4) {
                        xAddr = 0;
                        yAddr++;
                    }
                    // Y overflow wraps to 0 (always checked, per MAME)
                    if (yAddr >= HEIGHT) {
                        yAddr = 0;
                    }
                }
            }
        }
    }

    /** Returns true if the display is turned on. */
    public boolean isDisplayOn() {
        return (regs[REG_CONTROL1] & R0_DISP) != 0;
    }

    /**
     * Extract the frame buffer as a 160x100 int array of grayscale values (0-255).
     * Returns a reused internal array - caller must not hold a reference across frames.
     * Drawing order matches MAME: bottom-to-top, left-to-right.
     * VRAM byte 0 maps to screen row 99 (bottom), last byte to row 0 (top).
     */
    public int[] getFrameBuffer() {
        if (!isDisplayOn()) {
            java.util.Arrays.fill(frameBuffer, 255);
            return frameBuffer;
        }

        // Compute palette: 4 grayscale levels mapped to 0-255
        int contrast = regs[REG_CONTRAST];
        int p0 = Math.max(0, Math.min(31, 31 - (regs[REG_PALETTE1] - contrast + 3))) * 255 / 31;
        int p1 = Math.max(0, Math.min(31, 31 - (regs[REG_PALETTE2] - contrast + 3))) * 255 / 31;
        int p2 = Math.max(0, Math.min(31, 31 - (regs[REG_PALETTE3] - contrast + 3))) * 255 / 31;
        int p3 = Math.max(0, Math.min(31, 31 - (regs[REG_PALETTE4] - contrast + 3))) * 255 / 31;

        // Render VRAM bottom-to-top (matching MAME update_screen)
        int x = 0;
        int y = HEIGHT - 1;
        for (int i = 0; i < VRAM_SIZE; i++) {
            int data = vram[i] & 0xFF;
            int base = y * WIDTH + x;
            // 4 pixels per byte: bits 7-6, 5-4, 3-2, 1-0
            // Inlined palette lookup to avoid array access
            frameBuffer[base]     = switch ((data >> 6) & 3) { case 0 -> p0; case 1 -> p1; case 2 -> p2; default -> p3; };
            frameBuffer[base + 1] = switch ((data >> 4) & 3) { case 0 -> p0; case 1 -> p1; case 2 -> p2; default -> p3; };
            frameBuffer[base + 2] = switch ((data >> 2) & 3) { case 0 -> p0; case 1 -> p1; case 2 -> p2; default -> p3; };
            frameBuffer[base + 3] = switch ((data >> 0) & 3) { case 0 -> p0; case 1 -> p1; case 2 -> p2; default -> p3; };
            x += 4;
            if (x >= WIDTH) {
                x = 0;
                y--;
            }
        }

        return frameBuffer;
    }

    /** Get raw VRAM for debugging. */
    public byte[] getVram() { return vram; }

    /** Get registers for debugging. */
    public int[] getRegs() { return regs; }
}
