package org.example.cybiko;

/**
 * AT45DB041 SPI DataFlash emulation (matching MAME at45dbxx.cpp).
 *
 * Used by Cybiko V1 to store CyOS firmware. The CPU accesses this chip
 * via SPI (SCI1) with CS on Port 3 bit 4.
 *
 * Specs: 2048 pages x 264 bytes = 540,672 bytes total.
 *
 * SPI commands:
 *   0x57 - Status register read (1 cmd byte, then outputs status)
 *   0x52 - Main memory page read (4 cmd + 4 don't-care, then outputs page data)
 *   0x60 - Buffer compare (4 cmd bytes, compares page to buffer1)
 *   0x82 - Page program through buffer1 (4 cmd bytes + data, commits on CS deassert)
 */
public class AT45DB041Flash {
    private static final int NUM_PAGES = 2048;
    private static final int PAGE_SIZE = 264;
    private static final int TOTAL_SIZE = NUM_PAGES * PAGE_SIZE; // 540672

    private static final int CMD_STATUS = 0x57;
    private static final int CMD_READ   = 0x52;
    private static final int CMD_COMPARE = 0x60;
    private static final int CMD_PROGRAM = 0x82;

    private static final int DEVICE_ID = 0x18; // AT45DB041

    private static final int MODE_SI = 1; // Serial input (receiving commands/data)
    private static final int MODE_SO = 2; // Serial output (sending data)

    private final byte[] data;        // Flash memory array
    private final byte[] buffer1;     // 264-byte write buffer

    // Command state
    private final int[] cmd = new int[8];
    private int cmdSize = 0;
    private int mode = MODE_SI;

    // I/O state for read/write operations
    private byte[] ioData;   // Pointer to data being read/written
    private int ioSize;      // Size of I/O region
    private int ioPos;       // Current position in I/O region

    // Status register
    private int status = 0x80; // Bit 7 = ready

    private boolean csActive = false;

    public AT45DB041Flash(byte[] flashData) {
        data = new byte[TOTAL_SIZE];
        buffer1 = new byte[PAGE_SIZE];
        if (flashData != null) {
            System.arraycopy(flashData, 0, data, 0, Math.min(flashData.length, TOTAL_SIZE));
        }
    }

    /** Set chip select state. Active LOW: selected=true means CS pin is LOW. */
    public void cs(boolean selected) {
        if (csActive == selected) return;

        if (!selected) {
            // CS going HIGH (deassert): complete pending operation
            if (cmdSize >= 4 && cmd[0] == CMD_PROGRAM) {
                // Copy buffer1 to flash page
                int page = getPageAddr();
                if (page >= 0 && page < NUM_PAGES) {
                    System.arraycopy(buffer1, 0, data, page * PAGE_SIZE, PAGE_SIZE);
                }
            }
            // Reset state
            cmdSize = 0;
            mode = MODE_SI;
            ioData = null;
            ioPos = 0;
            ioSize = 0;
        }

        csActive = selected;
    }

    /**
     * Full-duplex SPI byte transfer. Called for each byte clocked through SCI.
     * @param dataIn byte sent from CPU (MOSI)
     * @return byte sent to CPU (MISO), or 0 if in input mode
     */
    public int transfer(int dataIn) {
        if (!csActive) return 0;

        if (mode == MODE_SO) {
            // Output mode: return next byte from I/O buffer
            if (ioData != null && ioSize > 0) {
                int val = ioData[ioPos] & 0xFF;
                ioPos++;
                if (ioPos >= ioSize) ioPos = 0; // Wrap around
                return val;
            }
            return 0;
        }

        // Input mode (MODE_SI): process command bytes
        if (cmdSize < 8) {
            cmd[cmdSize++] = dataIn & 0xFF;

            int opcode = cmd[0];
            switch (opcode) {
                case CMD_STATUS:
                    if (cmdSize == 1) {
                        // Immediately switch to output mode with status byte
                        status = (status & 0xC7) | DEVICE_ID;
                        mode = MODE_SO;
                        ioData = new byte[] { (byte) status };
                        ioSize = 1;
                        ioPos = 0;
                        cmdSize = 8; // Mark complete
                    }
                    break;

                case CMD_READ:
                    if (cmdSize == 8) {
                        // 4 command bytes + 4 don't-care received; start outputting page data
                        int page = getPageAddr();
                        int byteAddr = getByteAddr();
                        int offset = page * PAGE_SIZE;
                        ioData = data;
                        ioSize = offset + PAGE_SIZE; // Read wraps within page conceptually
                        ioPos = offset + byteAddr;
                        // For simplicity, set up a page-sized view
                        byte[] pageData = new byte[PAGE_SIZE];
                        for (int i = 0; i < PAGE_SIZE; i++) {
                            int srcIdx = offset + ((byteAddr + i) % PAGE_SIZE);
                            if (srcIdx < data.length) {
                                pageData[i] = data[srcIdx];
                            }
                        }
                        ioData = pageData;
                        ioSize = PAGE_SIZE;
                        ioPos = 0;
                        mode = MODE_SO;
                    }
                    break;

                case CMD_COMPARE:
                    if (cmdSize == 4) {
                        // Compare page to buffer1
                        int page = getPageAddr();
                        int offset = page * PAGE_SIZE;
                        boolean match = true;
                        for (int i = 0; i < PAGE_SIZE; i++) {
                            if (offset + i < data.length && data[offset + i] != buffer1[i]) {
                                match = false;
                                break;
                            }
                        }
                        if (!match) status |= 0x40;
                        else status &= ~0x40;
                        cmdSize = 8; // Mark complete
                    }
                    break;

                case CMD_PROGRAM:
                    if (cmdSize == 4) {
                        // Set up buffer1 for writing at the specified byte address
                        int byteAddr = getByteAddr();
                        java.util.Arrays.fill(buffer1, (byte) 0xFF);
                        ioData = buffer1;
                        ioSize = PAGE_SIZE;
                        ioPos = byteAddr;
                        cmdSize = 8; // Prevent further cmd processing; stay in SI mode
                    }
                    break;
            }
        } else {
            // Past command phase: writing data (for 0x82 program command)
            if (ioData != null && ioSize > 0) {
                ioData[ioPos] = (byte) (dataIn & 0xFF);
                ioPos++;
                if (ioPos >= ioSize) ioPos = 0;
            }
        }

        return 0; // SI mode returns 0 on MISO
    }

    /** Extract page address from command bytes (AT45DB041: 11-bit page). */
    private int getPageAddr() {
        return ((cmd[1] & 0x0F) << 7) | ((cmd[2] & 0xFE) >> 1);
    }

    /** Extract byte address within page from command bytes (9-bit). */
    private int getByteAddr() {
        return ((cmd[2] & 0x01) << 8) | (cmd[3] & 0xFF);
    }

    /** Get raw flash data (for testing/debug). */
    public byte[] getData() {
        return data;
    }
}
