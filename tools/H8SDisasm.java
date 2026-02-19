import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Standalone H8S/2000 disassembler for binary files.
 *
 * Usage: java H8SDisasm <input_file> <hex_offset> <hex_length> [hex_base_addr]
 *
 * If base_addr is omitted, defaults to (offset + 0x400000) which maps to
 * Cybiko Xtreme external RAM.
 *
 * Handles the most common H8S instructions found in I2C bit-bang code:
 * MOV (all modes), ADD, SUB, CMP, AND, OR, XOR, bit manipulation,
 * branches, shifts/rotates, JSR/BSR/RTS, EXTU/EXTS, INC/DEC, etc.
 */
public class H8SDisasm {

    private byte[] data;
    private int pos;       // current position in data[]
    private int baseAddr;  // display address of data[0]

    // --- Register name helpers ---

    static String regB(int r) {
        // 4-bit byte register: 0-7 = R0H-R7H, 8-F = R0L-R7L
        if (r < 8) return "R" + r + "H";
        return "R" + (r - 8) + "L";
    }

    static String regW(int r) {
        // 4-bit word register: 0-7 = R0-R7, 8-F = E0-E7
        if (r < 8) return "R" + r;
        return "E" + (r - 8);
    }

    static String regL(int r) {
        return "ER" + (r & 7);
    }

    static String condName(int cond) {
        return switch (cond & 0xF) {
            case 0x0 -> "BRA"; case 0x1 -> "BRN"; case 0x2 -> "BHI"; case 0x3 -> "BLS";
            case 0x4 -> "BCC"; case 0x5 -> "BCS"; case 0x6 -> "BNE"; case 0x7 -> "BEQ";
            case 0x8 -> "BVC"; case 0x9 -> "BVS"; case 0xA -> "BPL"; case 0xB -> "BMI";
            case 0xC -> "BGE"; case 0xD -> "BLT"; case 0xE -> "BGT"; case 0xF -> "BLE";
            default -> "B??";
        };
    }

    // --- Fetch helpers ---

    private int peek8() {
        if (pos >= data.length) return 0;
        return data[pos] & 0xFF;
    }

    private int fetch8() {
        if (pos >= data.length) return 0;
        return data[pos++] & 0xFF;
    }

    private int fetch16() {
        int hi = fetch8();
        int lo = fetch8();
        return (hi << 8) | lo;
    }

    private int fetch32() {
        int hi = fetch16();
        int lo = fetch16();
        return (hi << 16) | lo;
    }

    // --- Output helpers ---

    /** Format hex bytes from startPos to current pos. */
    private String hexBytes(int startPos) {
        StringBuilder sb = new StringBuilder();
        for (int i = startPos; i < pos; i++) {
            sb.append(String.format("%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }

    private void emit(int startPos, String mnemonic) {
        int addr = baseAddr + startPos;
        String hex = hexBytes(startPos);
        System.out.printf("%06X: %-12s %s%n", addr, hex, mnemonic);
    }

    // --- Bit operation decoder (used by 7C/7D/7E/7F and 6A compound) ---

    private String decodeBitOp(int bitOpWord, String target) {
        int opType = (bitOpWord >> 8) & 0xFF;
        int bitNum = (bitOpWord >> 4) & 0x7;
        return switch (opType) {
            case 0x60 -> String.format("BSET %s, %s", regB((bitOpWord >> 4) & 0xF), target);
            case 0x61 -> String.format("BNOT %s, %s", regB((bitOpWord >> 4) & 0xF), target);
            case 0x62 -> String.format("BCLR %s, %s", regB((bitOpWord >> 4) & 0xF), target);
            case 0x63 -> String.format("BTST %s, %s", regB((bitOpWord >> 4) & 0xF), target);
            case 0x67 -> (bitOpWord & 0x80) == 0
                    ? String.format("BST #%d, %s", bitNum, target)
                    : String.format("BIST #%d, %s", bitNum, target);
            case 0x70 -> String.format("BSET #%d, %s", bitNum, target);
            case 0x71 -> String.format("BNOT #%d, %s", bitNum, target);
            case 0x72 -> String.format("BCLR #%d, %s", bitNum, target);
            case 0x73 -> String.format("BTST #%d, %s", bitNum, target);
            case 0x74 -> (bitOpWord & 0x80) == 0
                    ? String.format("BOR #%d, %s", bitNum, target)
                    : String.format("BIOR #%d, %s", bitNum, target);
            case 0x75 -> (bitOpWord & 0x80) == 0
                    ? String.format("BXOR #%d, %s", bitNum, target)
                    : String.format("BIXOR #%d, %s", bitNum, target);
            case 0x76 -> (bitOpWord & 0x80) == 0
                    ? String.format("BAND #%d, %s", bitNum, target)
                    : String.format("BIAND #%d, %s", bitNum, target);
            case 0x77 -> (bitOpWord & 0x80) == 0
                    ? String.format("BLD #%d, %s", bitNum, target)
                    : String.format("BILD #%d, %s", bitNum, target);
            default -> String.format(".word 0x%04X  ; ??? bit op on %s", bitOpWord, target);
        };
    }

    // --- Main disassembly loop ---

    void disassemble() {
        while (pos < data.length) {
            int startPos = pos;
            int op = fetch16();
            int hi = (op >> 8) & 0xFF;
            int lo = op & 0xFF;

            switch (hi >> 4) {
                case 0x0 -> decode0(startPos, op, hi, lo);
                case 0x1 -> decode1(startPos, op, hi, lo);
                case 0x2 -> emit(startPos, String.format("MOV.B @0x%06X:8, %s",
                        0xFFFF00 | lo, regB(hi & 0xF)));
                case 0x3 -> emit(startPos, String.format("MOV.B %s, @0x%06X:8",
                        regB(hi & 0xF), 0xFFFF00 | lo));
                case 0x4 -> {
                    int disp = (byte) lo;
                    int target = baseAddr + pos + disp; // PC is already past the instruction
                    emit(startPos, String.format("%s 0x%06X", condName(hi & 0xF), target));
                }
                case 0x5 -> decode5(startPos, op, hi, lo);
                case 0x6 -> decode6(startPos, op, hi, lo);
                case 0x7 -> decode7(startPos, op, hi, lo);
                case 0x8 -> emit(startPos, String.format("ADD.B #0x%02X, %s",
                        lo, regB(hi & 0xF)));
                case 0x9 -> emit(startPos, String.format("ADDX #0x%02X, %s",
                        lo, regB(hi & 0xF)));
                case 0xA -> emit(startPos, String.format("CMP.B #0x%02X, %s",
                        lo, regB(hi & 0xF)));
                case 0xB -> emit(startPos, String.format("SUBX #0x%02X, %s",
                        lo, regB(hi & 0xF)));
                case 0xC -> emit(startPos, String.format("OR.B #0x%02X, %s",
                        lo, regB(hi & 0xF)));
                case 0xD -> emit(startPos, String.format("XOR.B #0x%02X, %s",
                        lo, regB(hi & 0xF)));
                case 0xE -> emit(startPos, String.format("AND.B #0x%02X, %s",
                        lo, regB(hi & 0xF)));
                case 0xF -> emit(startPos, String.format("MOV.B #0x%02X, %s",
                        lo, regB(hi & 0xF)));
                default -> emit(startPos, String.format(".word 0x%04X", op));
            }
        }
    }

    // --- Group 0x0xxx ---
    private void decode0(int startPos, int op, int hi, int lo) {
        switch (hi) {
            case 0x00 -> {
                if (lo == 0x00) emit(startPos, "NOP");
                else emit(startPos, String.format(".word 0x%04X", op));
            }
            case 0x01 -> decode01(startPos, lo);
            case 0x02 -> emit(startPos, String.format("STC CCR, %s", regB(lo & 0xF)));
            case 0x03 -> emit(startPos, String.format("LDC %s, CCR", regB(lo & 0xF)));
            case 0x04 -> emit(startPos, String.format("ORC #0x%02X, CCR", lo));
            case 0x05 -> emit(startPos, String.format("XORC #0x%02X, CCR", lo));
            case 0x06 -> emit(startPos, String.format("ANDC #0x%02X, CCR", lo));
            case 0x07 -> emit(startPos, String.format("LDC #0x%02X, CCR", lo));
            case 0x08 -> emit(startPos, String.format("ADD.B %s, %s",
                    regB((lo >> 4) & 0xF), regB(lo & 0xF)));
            case 0x09 -> emit(startPos, String.format("ADD.W %s, %s",
                    regW((lo >> 4) & 0xF), regW(lo & 0xF)));
            case 0x0A -> decode0A(startPos, op, lo);
            case 0x0B -> decode0B(startPos, op, lo);
            case 0x0C -> emit(startPos, String.format("MOV.B %s, %s",
                    regB((lo >> 4) & 0xF), regB(lo & 0xF)));
            case 0x0D -> emit(startPos, String.format("MOV.W %s, %s",
                    regW((lo >> 4) & 0xF), regW(lo & 0xF)));
            case 0x0E -> emit(startPos, String.format("ADDX %s, %s",
                    regB((lo >> 4) & 0xF), regB(lo & 0xF)));
            case 0x0F -> decode0F(startPos, op, lo);
            default -> emit(startPos, String.format(".word 0x%04X", op));
        }
    }

    // --- 0x01 prefix ---
    private void decode01(int startPos, int lo) {
        switch (lo) {
            case 0x00 -> {
                // 0x0100 prefix: MOV.L and other 32-bit ops
                int op2 = fetch16();
                int hi2 = (op2 >> 8) & 0xFF;
                int lo2 = op2 & 0xFF;
                switch (hi2) {
                    case 0x69 -> {
                        int rh = (lo2 >> 4) & 0x7;
                        int rl = lo2 & 0x7;
                        if ((lo2 & 0x80) == 0)
                            emit(startPos, String.format("MOV.L @%s, %s", regL(rh), regL(rl)));
                        else
                            emit(startPos, String.format("MOV.L %s, @%s", regL(rl), regL(rh)));
                    }
                    case 0x6B -> decode6B_32(startPos, op2);
                    case 0x6D -> {
                        int rh = (lo2 >> 4) & 0x7;
                        int rl = lo2 & 0x7;
                        if ((lo2 & 0x80) == 0)
                            emit(startPos, String.format("MOV.L @%s+, %s", regL(rh), regL(rl)));
                        else
                            emit(startPos, String.format("MOV.L %s, @-%s", regL(rl), regL(rh)));
                    }
                    case 0x6F -> {
                        int rh = (lo2 >> 4) & 0x7;
                        int rl = lo2 & 0x7;
                        int disp = (short) fetch16();
                        if ((lo2 & 0x80) == 0)
                            emit(startPos, String.format("MOV.L @(%d, %s), %s", disp, regL(rh), regL(rl)));
                        else
                            emit(startPos, String.format("MOV.L %s, @(%d, %s)", regL(rl), disp, regL(rh)));
                    }
                    case 0x78 -> {
                        // MOV.L @(d:24, ERs), ERd or store variant
                        int r1 = (lo2 >> 4) & 0x7;
                        int op3 = fetch16();
                        int disp = fetch32();
                        int r2 = op3 & 0x7;
                        if ((op3 & 0x0080) == 0)
                            emit(startPos, String.format("MOV.L @(%d, %s), %s", disp, regL(r1), regL(r2)));
                        else
                            emit(startPos, String.format("MOV.L %s, @(%d, %s)", regL(r1), disp, regL(r2)));
                    }
                    case 0x0F -> {
                        // MOV.L ERs, ERd (alternate encoding via 0100 0Fsd)
                        int rs = (lo2 >> 4) & 0x7;
                        int rd = lo2 & 0x7;
                        emit(startPos, String.format("MOV.L %s, %s", regL(rs), regL(rd)));
                    }
                    default -> emit(startPos, String.format(".word 0x0100  ; + 0x%04X", op2));
                }
            }
            case 0x10, 0x20, 0x30 -> {
                // STM/LDM
                int count = (lo >> 4);
                int op2 = fetch16();
                int rn = op2 & 0x7;
                if ((op2 & 0xFFF8) == 0x6D70) {
                    emit(startPos, String.format("LDM.L @SP+, %s-%s", regL(rn - count), regL(rn)));
                } else if ((op2 & 0xFFF8) == 0x6DF0) {
                    emit(startPos, String.format("STM.L %s-%s, @-SP", regL(rn), regL(rn + count)));
                } else {
                    emit(startPos, String.format(".word 0x01%02X  ; + 0x%04X", lo, op2));
                }
            }
            case 0x40 -> {
                // 0x0140 prefix: LDC/STC CCR with memory operands
                int op2 = fetch16();
                decode0140(startPos, op2);
            }
            case 0x41 -> {
                // 0x0141 prefix: EXR operations
                int op2 = fetch16();
                int hi2 = (op2 >> 8) & 0xFF;
                switch (hi2) {
                    case 0x04 -> emit(startPos, String.format("ORC #0x%02X, EXR", op2 & 0xFF));
                    case 0x05 -> emit(startPos, String.format("XORC #0x%02X, EXR", op2 & 0xFF));
                    case 0x06 -> emit(startPos, String.format("ANDC #0x%02X, EXR", op2 & 0xFF));
                    case 0x07 -> emit(startPos, String.format("LDC #0x%02X, EXR", op2 & 0xFF));
                    default -> emit(startPos, String.format(".word 0x0141  ; + 0x%04X", op2));
                }
            }
            case 0x80 -> emit(startPos, "SLEEP");
            case 0xC0, 0xD0, 0xF0 -> {
                // Extended prefixes: MULXS, DIVXS, OR.L/XOR.L/AND.L reg
                int op2 = fetch16();
                int hi2 = (op2 >> 8) & 0xFF;
                if (hi2 == 0x6B) {
                    decode6B_32(startPos, op2);
                } else if (hi2 == 0x50) {
                    int rs = (op2 >> 4) & 0xF;
                    int rd = op2 & 0xF;
                    emit(startPos, String.format("MULXS.B %s, %s", regB(rs), regW(rd)));
                } else if (hi2 == 0x52) {
                    int rs = (op2 >> 4) & 0xF;
                    int rd = op2 & 0x7;
                    emit(startPos, String.format("MULXS.W %s, %s", regW(rs), regL(rd)));
                } else if (hi2 == 0x51) {
                    int rs = (op2 >> 4) & 0xF;
                    int rd = op2 & 0x7;
                    emit(startPos, String.format("DIVXS.B %s, %s", regB(rs), regW(rd)));
                } else if (hi2 == 0x53) {
                    int rs = (op2 >> 4) & 0xF;
                    int rd = op2 & 0x7;
                    emit(startPos, String.format("DIVXS.W %s, %s", regW(rs), regL(rd)));
                } else if (lo == 0xF0 && hi2 == 0x64) {
                    int rs = (op2 >> 4) & 0x7;
                    int rd = op2 & 0x7;
                    emit(startPos, String.format("OR.L %s, %s", regL(rs), regL(rd)));
                } else if (lo == 0xF0 && hi2 == 0x65) {
                    int rs = (op2 >> 4) & 0x7;
                    int rd = op2 & 0x7;
                    emit(startPos, String.format("XOR.L %s, %s", regL(rs), regL(rd)));
                } else if (lo == 0xF0 && hi2 == 0x66) {
                    int rs = (op2 >> 4) & 0x7;
                    int rd = op2 & 0x7;
                    emit(startPos, String.format("AND.L %s, %s", regL(rs), regL(rd)));
                } else {
                    emit(startPos, String.format(".word 0x01%02X  ; + 0x%04X", lo, op2));
                }
            }
            default -> {
                // Unknown 01xx - may consume another word
                if (pos + 1 < data.length) {
                    int op2 = fetch16();
                    emit(startPos, String.format(".word 0x01%02X  ; + 0x%04X", lo, op2));
                } else {
                    emit(startPos, String.format(".word 0x01%02X", lo));
                }
            }
        }
    }

    // 0x0140 prefix: LDC/STC CCR with memory operands
    private void decode0140(int startPos, int op2) {
        int hi2 = (op2 >> 8) & 0xFF;
        int lo2 = op2 & 0xFF;
        switch (hi2) {
            case 0x69 -> {
                int r = (lo2 >> 4) & 0x7;
                if ((lo2 & 0x80) == 0)
                    emit(startPos, String.format("LDC.W @%s, CCR", regL(r)));
                else
                    emit(startPos, String.format("STC.W CCR, @%s", regL(r)));
            }
            case 0x6B -> {
                boolean write = (lo2 & 0x80) != 0;
                boolean addr24 = (lo2 & 0x20) != 0;
                int addr;
                if (addr24) {
                    addr = fetch32() & 0xFFFFFF;
                } else {
                    addr = (short) fetch16();
                    addr &= 0xFFFFFF;
                }
                if (!write)
                    emit(startPos, String.format("LDC.W @0x%06X, CCR", addr));
                else
                    emit(startPos, String.format("STC.W CCR, @0x%06X", addr));
            }
            case 0x6D -> {
                int r = (lo2 >> 4) & 0x7;
                if ((lo2 & 0x80) == 0)
                    emit(startPos, String.format("LDC.W @%s+, CCR", regL(r)));
                else
                    emit(startPos, String.format("STC.W CCR, @-%s", regL(r)));
            }
            case 0x6F -> {
                int r = (lo2 >> 4) & 0x7;
                int disp = (short) fetch16();
                if ((lo2 & 0x80) == 0)
                    emit(startPos, String.format("LDC.W @(%d, %s), CCR", disp, regL(r)));
                else
                    emit(startPos, String.format("STC.W CCR, @(%d, %s)", disp, regL(r)));
            }
            default -> emit(startPos, String.format(".word 0x0140  ; + 0x%04X", op2));
        }
    }

    // MOV.L with absolute addresses (after 0100 or 01C0/01D0/01F0 prefix)
    private void decode6B_32(int startPos, int op2) {
        int lo2 = op2 & 0xFF;
        boolean write = (lo2 & 0x80) != 0;
        boolean addr24 = (lo2 & 0x20) != 0;
        int rd = lo2 & 0x7;
        int addr;
        if (addr24) {
            addr = fetch32() & 0xFFFFFF;
        } else {
            addr = (short) fetch16();
            addr &= 0xFFFFFF;
        }
        if (!write)
            emit(startPos, String.format("MOV.L @0x%06X, %s", addr, regL(rd)));
        else
            emit(startPos, String.format("MOV.L %s, @0x%06X", regL(rd), addr));
    }

    // --- 0x0A: INC.B, ADD.L ---
    private void decode0A(int startPos, int op, int lo) {
        if ((lo & 0x80) != 0) {
            int rs = (lo >> 4) & 0x7;
            int rd = lo & 0x7;
            emit(startPos, String.format("ADD.L %s, %s", regL(rs), regL(rd)));
        } else {
            int subNib = (lo >> 4) & 0xF;
            int rd = lo & 0xF;
            switch (subNib) {
                case 0x0 -> emit(startPos, String.format("INC.B %s", regB(rd)));
                case 0x5 -> emit(startPos, String.format("INC.W #1, %s", regW(rd)));
                case 0x7 -> emit(startPos, String.format("INC.L #1, %s", regL(rd & 0x7)));
                default -> emit(startPos, String.format(".word 0x%04X", op));
            }
        }
    }

    // --- 0x0B: ADDS, INC.W #2, INC.L #2 ---
    private void decode0B(int startPos, int op, int lo) {
        int subNib = (lo >> 4) & 0xF;
        int rd = lo & 0x7;
        switch (subNib) {
            case 0x0 -> emit(startPos, String.format("ADDS #1, %s", regL(rd)));
            case 0x5 -> emit(startPos, String.format("INC.W #1, %s", regW(lo & 0xF)));
            case 0x7 -> emit(startPos, String.format("INC.L #1, %s", regL(rd)));
            case 0x8 -> emit(startPos, String.format("ADDS #2, %s", regL(rd)));
            case 0x9 -> emit(startPos, String.format("ADDS #4, %s", regL(rd)));
            case 0xD -> emit(startPos, String.format("INC.W #2, %s", regW(lo & 0xF)));
            case 0xF -> emit(startPos, String.format("INC.L #2, %s", regL(rd)));
            default -> emit(startPos, String.format(".word 0x%04X", op));
        }
    }

    // --- 0x0F: MOV.L reg, DAA ---
    private void decode0F(int startPos, int op, int lo) {
        if ((lo & 0x80) != 0) {
            int rs = (lo >> 4) & 0x7;
            int rd = lo & 0x7;
            emit(startPos, String.format("MOV.L %s, %s", regL(rs), regL(rd)));
        } else if ((lo & 0xF0) == 0x00) {
            emit(startPos, String.format("DAA %s", regB(lo & 0xF)));
        } else {
            emit(startPos, String.format(".word 0x%04X", op));
        }
    }

    // --- Group 0x1xxx ---
    private void decode1(int startPos, int op, int hi, int lo) {
        switch (hi) {
            case 0x10, 0x11, 0x12, 0x13 -> decodeShift(startPos, op, hi, lo);
            case 0x14 -> emit(startPos, String.format("OR.B %s, %s",
                    regB((lo >> 4) & 0xF), regB(lo & 0xF)));
            case 0x15 -> emit(startPos, String.format("XOR.B %s, %s",
                    regB((lo >> 4) & 0xF), regB(lo & 0xF)));
            case 0x16 -> emit(startPos, String.format("AND.B %s, %s",
                    regB((lo >> 4) & 0xF), regB(lo & 0xF)));
            case 0x17 -> decode17(startPos, op, lo);
            case 0x18 -> emit(startPos, String.format("SUB.B %s, %s",
                    regB((lo >> 4) & 0xF), regB(lo & 0xF)));
            case 0x19 -> emit(startPos, String.format("SUB.W %s, %s",
                    regW((lo >> 4) & 0xF), regW(lo & 0xF)));
            case 0x1A -> decode1A(startPos, op, lo);
            case 0x1B -> decode1B(startPos, op, lo);
            case 0x1C -> emit(startPos, String.format("CMP.B %s, %s",
                    regB((lo >> 4) & 0xF), regB(lo & 0xF)));
            case 0x1D -> emit(startPos, String.format("CMP.W %s, %s",
                    regW((lo >> 4) & 0xF), regW(lo & 0xF)));
            case 0x1E -> emit(startPos, String.format("SUBX %s, %s",
                    regB((lo >> 4) & 0xF), regB(lo & 0xF)));
            case 0x1F -> decode1F(startPos, op, lo);
            default -> emit(startPos, String.format(".word 0x%04X", op));
        }
    }

    // --- Shift/rotate decoder ---
    private void decodeShift(int startPos, int op, int hi, int lo) {
        int subop = (lo >> 4) & 0xF;
        int rd = lo & 0xF;
        int erd = lo & 0x7;

        // Build mnemonic from group and subop
        String baseName;
        switch (hi) {
            case 0x10 -> baseName = (subop >= 0x8) ? "SHAL" : "SHLL";
            case 0x11 -> baseName = (subop >= 0x8) ? "SHAR" : "SHLR";
            case 0x12 -> baseName = (subop >= 0x8) ? "ROTL" : "ROTXL";
            case 0x13 -> baseName = (subop >= 0x8) ? "ROTR" : "ROTXR";
            default -> { emit(startPos, String.format(".word 0x%04X", op)); return; }
        }

        int effSubop = subop & 0x7;
        switch (effSubop) {
            case 0x0 -> emit(startPos, String.format("%s.B %s", baseName, regB(rd)));
            case 0x1 -> emit(startPos, String.format("%s.W %s", baseName, regW(rd)));
            case 0x3 -> emit(startPos, String.format("%s.L %s", baseName, regL(erd)));
            case 0x4 -> emit(startPos, String.format("%s.B #2, %s", baseName, regB(rd)));
            case 0x5 -> emit(startPos, String.format("%s.W #2, %s", baseName, regW(rd)));
            case 0x7 -> emit(startPos, String.format("%s.L #2, %s", baseName, regL(erd)));
            default -> emit(startPos, String.format(".word 0x%04X", op));
        }
    }

    // --- 0x17: NOT, EXTU, NEG, EXTS ---
    private void decode17(int startPos, int op, int lo) {
        int subop = (lo >> 4) & 0xF;
        int rd = lo & 0xF;
        int erd = lo & 0x7;
        switch (subop) {
            case 0x0 -> emit(startPos, String.format("NOT.B %s", regB(rd)));
            case 0x1 -> emit(startPos, String.format("NOT.W %s", regW(rd)));
            case 0x3 -> emit(startPos, String.format("NOT.L %s", regL(erd)));
            case 0x5 -> emit(startPos, String.format("EXTU.W %s", regW(rd)));
            case 0x7 -> emit(startPos, String.format("EXTU.L %s", regL(erd)));
            case 0x8 -> emit(startPos, String.format("NEG.B %s", regB(rd)));
            case 0x9 -> emit(startPos, String.format("NEG.W %s", regW(rd)));
            case 0xB -> emit(startPos, String.format("NEG.L %s", regL(erd)));
            case 0xD -> emit(startPos, String.format("EXTS.W %s", regW(rd)));
            case 0xF -> emit(startPos, String.format("EXTS.L %s", regL(erd)));
            default -> emit(startPos, String.format(".word 0x%04X", op));
        }
    }

    // --- 0x1A: DEC.B, SUB.L ---
    private void decode1A(int startPos, int op, int lo) {
        if ((lo & 0x80) != 0) {
            int rs = (lo >> 4) & 0x7;
            int rd = lo & 0x7;
            emit(startPos, String.format("SUB.L %s, %s", regL(rs), regL(rd)));
        } else {
            int subNib = (lo >> 4) & 0xF;
            int rd = lo & 0xF;
            switch (subNib) {
                case 0x0 -> emit(startPos, String.format("DEC.B %s", regB(rd)));
                case 0x5 -> emit(startPos, String.format("DEC.W #1, %s", regW(rd)));
                case 0x7 -> emit(startPos, String.format("DEC.L #1, %s", regL(rd & 0x7)));
                default -> emit(startPos, String.format(".word 0x%04X", op));
            }
        }
    }

    // --- 0x1B: SUBS, DEC.W #2, DEC.L #2 ---
    private void decode1B(int startPos, int op, int lo) {
        int subNib = (lo >> 4) & 0xF;
        int rd = lo & 0x7;
        switch (subNib) {
            case 0x0 -> emit(startPos, String.format("SUBS #1, %s", regL(rd)));
            case 0x5 -> emit(startPos, String.format("DEC.W #1, %s", regW(lo & 0xF)));
            case 0x7 -> emit(startPos, String.format("DEC.L #1, %s", regL(rd)));
            case 0x8 -> emit(startPos, String.format("SUBS #2, %s", regL(rd)));
            case 0x9 -> emit(startPos, String.format("SUBS #4, %s", regL(rd)));
            case 0xD -> emit(startPos, String.format("DEC.W #2, %s", regW(lo & 0xF)));
            case 0xF -> emit(startPos, String.format("DEC.L #2, %s", regL(rd)));
            default -> emit(startPos, String.format(".word 0x%04X", op));
        }
    }

    // --- 0x1F: CMP.L, DAS ---
    private void decode1F(int startPos, int op, int lo) {
        if ((lo & 0x80) != 0) {
            int rs = (lo >> 4) & 0x7;
            int rd = lo & 0x7;
            emit(startPos, String.format("CMP.L %s, %s", regL(rs), regL(rd)));
        } else if ((lo & 0xF0) == 0x00) {
            emit(startPos, String.format("DAS %s", regB(lo & 0xF)));
        } else {
            emit(startPos, String.format(".word 0x%04X", 0x1F00 | lo));
        }
    }

    // --- Group 0x5xxx ---
    private void decode5(int startPos, int op, int hi, int lo) {
        switch (hi) {
            case 0x50 -> {
                int rs = (lo >> 4) & 0xF;
                int rd = lo & 0xF;
                emit(startPos, String.format("MULXU.B %s, %s", regB(rs), regW(rd)));
            }
            case 0x51 -> {
                int rs = (lo >> 4) & 0xF;
                int rd = lo & 0xF;
                emit(startPos, String.format("DIVXU.B %s, %s", regB(rs), regW(rd)));
            }
            case 0x52 -> {
                int rs = (lo >> 4) & 0xF;
                int rd = lo & 0x7;
                emit(startPos, String.format("MULXU.W %s, %s", regW(rs), regL(rd)));
            }
            case 0x53 -> {
                int rs = (lo >> 4) & 0xF;
                int rd = lo & 0x7;
                emit(startPos, String.format("DIVXU.W %s, %s", regW(rs), regL(rd)));
            }
            case 0x54 -> {
                if (lo == 0x70) emit(startPos, "RTS");
                else emit(startPos, String.format(".word 0x%04X", op));
            }
            case 0x55 -> {
                int disp = (byte) lo;
                int target = baseAddr + pos + disp;
                emit(startPos, String.format("BSR 0x%06X", target));
            }
            case 0x56 -> {
                if (lo == 0x70) emit(startPos, "RTE");
                else emit(startPos, String.format(".word 0x%04X", op));
            }
            case 0x57 -> {
                int vec = (lo >> 4) & 0x3;
                emit(startPos, String.format("TRAPA #%d", vec));
            }
            case 0x58 -> {
                int cond = (lo >> 4) & 0xF;
                int disp = (short) fetch16();
                int target = baseAddr + pos + disp;
                emit(startPos, String.format("%s:16 0x%06X", condName(cond), target));
            }
            case 0x59 -> {
                int rn = (lo >> 4) & 0x7;
                emit(startPos, String.format("JMP @%s", regL(rn)));
            }
            case 0x5A -> {
                int addr = ((lo << 16) | fetch16()) & 0xFFFFFF;
                emit(startPos, String.format("JMP @0x%06X:24", addr));
            }
            case 0x5B -> {
                emit(startPos, String.format("JMP @@0x%02X:8", lo));
            }
            case 0x5C -> {
                int disp = (short) fetch16();
                int target = baseAddr + pos + disp;
                emit(startPos, String.format("BSR:16 0x%06X", target));
            }
            case 0x5D -> {
                int rn = (lo >> 4) & 0x7;
                emit(startPos, String.format("JSR @%s", regL(rn)));
            }
            case 0x5E -> {
                int addr = ((lo << 16) | fetch16()) & 0xFFFFFF;
                emit(startPos, String.format("JSR @0x%06X:24", addr));
            }
            case 0x5F -> {
                emit(startPos, String.format("JSR @@0x%02X:8", lo));
            }
            default -> emit(startPos, String.format(".word 0x%04X", op));
        }
    }

    // --- Group 0x6xxx ---
    private void decode6(int startPos, int op, int hi, int lo) {
        switch (hi) {
            case 0x60 -> emit(startPos, String.format("BSET %s, %s",
                    regB((lo >> 4) & 0xF), regB(lo & 0xF)));
            case 0x61 -> emit(startPos, String.format("BNOT %s, %s",
                    regB((lo >> 4) & 0xF), regB(lo & 0xF)));
            case 0x62 -> emit(startPos, String.format("BCLR %s, %s",
                    regB((lo >> 4) & 0xF), regB(lo & 0xF)));
            case 0x63 -> emit(startPos, String.format("BTST %s, %s",
                    regB((lo >> 4) & 0xF), regB(lo & 0xF)));
            case 0x64 -> emit(startPos, String.format("OR.W %s, %s",
                    regW((lo >> 4) & 0xF), regW(lo & 0xF)));
            case 0x65 -> emit(startPos, String.format("XOR.W %s, %s",
                    regW((lo >> 4) & 0xF), regW(lo & 0xF)));
            case 0x66 -> emit(startPos, String.format("AND.W %s, %s",
                    regW((lo >> 4) & 0xF), regW(lo & 0xF)));
            case 0x67 -> {
                int bit = (lo >> 4) & 0x7;
                int rd = lo & 0xF;
                if ((lo & 0x80) == 0)
                    emit(startPos, String.format("BST #%d, %s", bit, regB(rd)));
                else
                    emit(startPos, String.format("BIST #%d, %s", bit, regB(rd)));
            }
            case 0x68 -> {
                int erReg = (lo >> 4) & 0x7;
                int bReg = lo & 0xF;
                if ((lo & 0x80) == 0)
                    emit(startPos, String.format("MOV.B @%s, %s", regL(erReg), regB(bReg)));
                else
                    emit(startPos, String.format("MOV.B %s, @%s", regB(bReg), regL(erReg)));
            }
            case 0x69 -> {
                int erReg = (lo >> 4) & 0x7;
                int wReg = lo & 0xF;
                if ((lo & 0x80) == 0)
                    emit(startPos, String.format("MOV.W @%s, %s", regL(erReg), regW(wReg)));
                else
                    emit(startPos, String.format("MOV.W %s, @%s", regW(wReg), regL(erReg)));
            }
            case 0x6A -> decode6A(startPos, op, lo);
            case 0x6B -> decode6B(startPos, op, lo);
            case 0x6C -> {
                int erReg = (lo >> 4) & 0x7;
                int bReg = lo & 0xF;
                if ((lo & 0x80) == 0)
                    emit(startPos, String.format("MOV.B @%s+, %s", regL(erReg), regB(bReg)));
                else
                    emit(startPos, String.format("MOV.B %s, @-%s", regB(bReg), regL(erReg)));
            }
            case 0x6D -> {
                int erReg = (lo >> 4) & 0x7;
                int wReg = lo & 0xF;
                if ((lo & 0x80) == 0)
                    emit(startPos, String.format("MOV.W @%s+, %s", regL(erReg), regW(wReg)));
                else
                    emit(startPos, String.format("MOV.W %s, @-%s", regW(wReg), regL(erReg)));
            }
            case 0x6E -> {
                int erReg = (lo >> 4) & 0x7;
                int bReg = lo & 0xF;
                int disp = (short) fetch16();
                if ((lo & 0x80) == 0)
                    emit(startPos, String.format("MOV.B @(%d, %s), %s", disp, regL(erReg), regB(bReg)));
                else
                    emit(startPos, String.format("MOV.B %s, @(%d, %s)", regB(bReg), disp, regL(erReg)));
            }
            case 0x6F -> {
                int erReg = (lo >> 4) & 0x7;
                int wReg = lo & 0xF;
                int disp = (short) fetch16();
                if ((lo & 0x80) == 0)
                    emit(startPos, String.format("MOV.W @(%d, %s), %s", disp, regL(erReg), regW(wReg)));
                else
                    emit(startPos, String.format("MOV.W %s, @(%d, %s)", regW(wReg), disp, regL(erReg)));
            }
            default -> emit(startPos, String.format(".word 0x%04X", op));
        }
    }

    // --- 0x6A: MOV.B absolute / compound bit ops ---
    private void decode6A(int startPos, int op, int lo) {
        int topNibble = (lo >> 4) & 0xF;

        // Compound bit manipulation: 6A 1x / 6A 3x
        if (topNibble == 1 || topNibble == 3) {
            boolean addr32 = (topNibble == 3);
            int addr;
            if (addr32) {
                addr = fetch32() & 0xFFFFFF;
            } else {
                addr = (short) fetch16();
                addr &= 0xFFFFFF;
            }
            int bitOp = fetch16();
            String target = String.format("@0x%06X", addr);
            emit(startPos, decodeBitOp(bitOp, target));
            return;
        }

        // Standard MOV.B with absolute address
        boolean write = (lo & 0x80) != 0;
        boolean addr24 = (lo & 0x20) != 0;
        int rd = lo & 0xF;
        int addr;
        if (addr24) {
            addr = fetch32() & 0xFFFFFF;
        } else {
            addr = (short) fetch16();
            addr &= 0xFFFFFF;
        }
        String addrSize = addr24 ? ":24" : ":16";
        if (!write)
            emit(startPos, String.format("MOV.B @0x%06X%s, %s", addr, addrSize, regB(rd)));
        else
            emit(startPos, String.format("MOV.B %s, @0x%06X%s", regB(rd), addr, addrSize));
    }

    // --- 0x6B: MOV.W absolute ---
    private void decode6B(int startPos, int op, int lo) {
        boolean write = (lo & 0x80) != 0;
        boolean addr24 = (lo & 0x20) != 0;
        int rd = lo & 0xF;
        int addr;
        if (addr24) {
            addr = fetch32() & 0xFFFFFF;
        } else {
            addr = (short) fetch16();
            addr &= 0xFFFFFF;
        }
        String addrSize = addr24 ? ":24" : ":16";
        if (!write)
            emit(startPos, String.format("MOV.W @0x%06X%s, %s", addr, addrSize, regW(rd)));
        else
            emit(startPos, String.format("MOV.W %s, @0x%06X%s", regW(rd), addr, addrSize));
    }

    // --- Group 0x7xxx ---
    private void decode7(int startPos, int op, int hi, int lo) {
        switch (hi) {
            case 0x70 -> {
                int bit = (lo >> 4) & 0x7;
                int rd = lo & 0xF;
                emit(startPos, String.format("BSET #%d, %s", bit, regB(rd)));
            }
            case 0x71 -> {
                int bit = (lo >> 4) & 0x7;
                int rd = lo & 0xF;
                emit(startPos, String.format("BNOT #%d, %s", bit, regB(rd)));
            }
            case 0x72 -> {
                int bit = (lo >> 4) & 0x7;
                int rd = lo & 0xF;
                emit(startPos, String.format("BCLR #%d, %s", bit, regB(rd)));
            }
            case 0x73 -> {
                int bit = (lo >> 4) & 0x7;
                int rd = lo & 0xF;
                emit(startPos, String.format("BTST #%d, %s", bit, regB(rd)));
            }
            case 0x74 -> {
                int bit = (lo >> 4) & 0x7;
                int rd = lo & 0xF;
                if ((lo & 0x80) == 0)
                    emit(startPos, String.format("BOR #%d, %s", bit, regB(rd)));
                else
                    emit(startPos, String.format("BIOR #%d, %s", bit, regB(rd)));
            }
            case 0x75 -> {
                int bit = (lo >> 4) & 0x7;
                int rd = lo & 0xF;
                if ((lo & 0x80) == 0)
                    emit(startPos, String.format("BXOR #%d, %s", bit, regB(rd)));
                else
                    emit(startPos, String.format("BIXOR #%d, %s", bit, regB(rd)));
            }
            case 0x76 -> {
                int bit = (lo >> 4) & 0x7;
                int rd = lo & 0xF;
                if ((lo & 0x80) == 0)
                    emit(startPos, String.format("BAND #%d, %s", bit, regB(rd)));
                else
                    emit(startPos, String.format("BIAND #%d, %s", bit, regB(rd)));
            }
            case 0x77 -> {
                int bit = (lo >> 4) & 0x7;
                int rd = lo & 0xF;
                if ((lo & 0x80) == 0)
                    emit(startPos, String.format("BLD #%d, %s", bit, regB(rd)));
                else
                    emit(startPos, String.format("BILD #%d, %s", bit, regB(rd)));
            }
            case 0x78 -> {
                // MOV.B/W with 32-bit displacement: 78r0 6Axx/6Bxx disp32
                int erReg = (lo >> 4) & 0x7;
                int op2 = fetch16();
                int hi2 = (op2 >> 8) & 0xFF;
                if (hi2 == 0x6A) {
                    int bReg = op2 & 0xF;
                    int disp = fetch32();
                    if ((op2 & 0x80) == 0)
                        emit(startPos, String.format("MOV.B @(%d, %s), %s", disp, regL(erReg), regB(bReg)));
                    else
                        emit(startPos, String.format("MOV.B %s, @(%d, %s)", regB(bReg), disp, regL(erReg)));
                } else if (hi2 == 0x6B) {
                    int wReg = op2 & 0xF;
                    int disp = fetch32();
                    if ((op2 & 0x80) == 0)
                        emit(startPos, String.format("MOV.W @(%d, %s), %s", disp, regL(erReg), regW(wReg)));
                    else
                        emit(startPos, String.format("MOV.W %s, @(%d, %s)", regW(wReg), disp, regL(erReg)));
                } else {
                    emit(startPos, String.format(".word 0x%04X  ; + 0x%04X", op, op2));
                }
            }
            case 0x79 -> decode79(startPos, lo);
            case 0x7A -> decode7A(startPos, lo);
            case 0x7B -> {
                // EEPMOV
                int op2 = fetch16();
                if (lo == 0x5C && op2 == 0x598F) {
                    emit(startPos, "EEPMOV.B");
                } else if (lo == 0xD4 && op2 == 0x598F) {
                    emit(startPos, "EEPMOV.W");
                } else {
                    emit(startPos, String.format(".word 0x7B%02X  ; + 0x%04X", lo, op2));
                }
            }
            case 0x7C, 0x7D, 0x7E, 0x7F -> decode7C_7F(startPos, hi, lo);
            default -> emit(startPos, String.format(".word 0x%04X", op));
        }
    }

    // --- 0x79: word immediate ops ---
    private void decode79(int startPos, int lo) {
        int subop = (lo >> 4) & 0xF;
        int rd = lo & 0xF;
        int imm = fetch16();
        switch (subop) {
            case 0x0 -> emit(startPos, String.format("MOV.W #0x%04X, %s", imm, regW(rd)));
            case 0x1 -> emit(startPos, String.format("ADD.W #0x%04X, %s", imm, regW(rd)));
            case 0x2 -> emit(startPos, String.format("CMP.W #0x%04X, %s", imm, regW(rd)));
            case 0x3 -> emit(startPos, String.format("SUB.W #0x%04X, %s", imm, regW(rd)));
            case 0x4 -> emit(startPos, String.format("OR.W #0x%04X, %s", imm, regW(rd)));
            case 0x5 -> emit(startPos, String.format("XOR.W #0x%04X, %s", imm, regW(rd)));
            case 0x6 -> emit(startPos, String.format("AND.W #0x%04X, %s", imm, regW(rd)));
            default -> emit(startPos, String.format(".word 0x79%02X  ; imm=0x%04X", lo, imm));
        }
    }

    // --- 0x7A: longword immediate ops ---
    private void decode7A(int startPos, int lo) {
        int subop = (lo >> 4) & 0xF;
        int rd = lo & 0x7;
        int imm = fetch32();
        switch (subop) {
            case 0x0 -> emit(startPos, String.format("MOV.L #0x%08X, %s", imm, regL(rd)));
            case 0x1 -> emit(startPos, String.format("ADD.L #0x%08X, %s", imm, regL(rd)));
            case 0x2 -> emit(startPos, String.format("CMP.L #0x%08X, %s", imm, regL(rd)));
            case 0x3 -> emit(startPos, String.format("SUB.L #0x%08X, %s", imm, regL(rd)));
            case 0x4 -> emit(startPos, String.format("OR.L #0x%08X, %s", imm, regL(rd)));
            case 0x5 -> emit(startPos, String.format("XOR.L #0x%08X, %s", imm, regL(rd)));
            case 0x6 -> emit(startPos, String.format("AND.L #0x%08X, %s", imm, regL(rd)));
            default -> emit(startPos, String.format(".word 0x7A%02X  ; imm=0x%08X", lo, imm));
        }
    }

    // --- 0x7C-0x7F: bit ops on memory ---
    private void decode7C_7F(int startPos, int hi, int lo) {
        int op2 = fetch16();
        switch (hi) {
            case 0x7C -> {
                // Bit ops on @ERd (read-only: BTST, BLD, etc.)
                int r = (lo >> 4) & 0x7;
                String target = String.format("@%s", regL(r));
                emit(startPos, decodeBitOp(op2, target));
            }
            case 0x7D -> {
                // Bit ops on @ERd (read-modify-write: BSET, BCLR, BST, etc.)
                int r = (lo >> 4) & 0x7;
                String target = String.format("@%s", regL(r));
                emit(startPos, decodeBitOp(op2, target));
            }
            case 0x7E -> {
                // Bit ops on @aa:8 (read-only)
                int addr = 0xFFFF00 | (lo & 0xFF);
                String target = String.format("@0x%06X:8", addr);
                emit(startPos, decodeBitOp(op2, target));
            }
            case 0x7F -> {
                // Bit ops on @aa:8 (read-modify-write)
                int addr = 0xFFFF00 | (lo & 0xFF);
                String target = String.format("@0x%06X:8", addr);
                emit(startPos, decodeBitOp(op2, target));
            }
        }
    }

    // --- Main ---

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("Usage: java H8SDisasm <input_file> <hex_offset> <hex_length> [hex_base_addr]");
            System.err.println();
            System.err.println("  input_file    Binary file to disassemble");
            System.err.println("  hex_offset    File offset in hex (e.g., A4500)");
            System.err.println("  hex_length    Number of bytes to disassemble in hex (e.g., 100)");
            System.err.println("  hex_base_addr Display base address in hex (default: offset + 0x400000)");
            System.exit(1);
        }

        String inputFile = args[0];
        int offset = Integer.parseUnsignedInt(args[1], 16);
        int length = Integer.parseUnsignedInt(args[2], 16);
        int baseAddr;
        if (args.length >= 4) {
            baseAddr = Integer.parseUnsignedInt(args[3], 16);
        } else {
            baseAddr = offset + 0x400000;
        }

        // Read the requested bytes
        byte[] fileData;
        try (RandomAccessFile raf = new RandomAccessFile(inputFile, "r")) {
            long fileLen = raf.length();
            if (offset >= fileLen) {
                System.err.printf("Error: offset 0x%X exceeds file size 0x%X%n", offset, fileLen);
                System.exit(1);
            }
            int available = (int) Math.min(length, fileLen - offset);
            fileData = new byte[available];
            raf.seek(offset);
            raf.readFully(fileData);
        }

        H8SDisasm disasm = new H8SDisasm();
        disasm.data = fileData;
        disasm.pos = 0;
        disasm.baseAddr = baseAddr;
        disasm.disassemble();
    }
}
