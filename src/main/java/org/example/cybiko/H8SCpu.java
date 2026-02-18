package org.example.cybiko;

/**
 * Hitachi H8S/2323 CPU emulator core.
 *
 * Register layout:
 *   ER0-ER7: 32-bit general registers
 *     E0-E7: upper 16 bits
 *     R0-R7: lower 16 bits
 *     R0H-R7H: high byte of R0-R7
 *     R0L-R7L: low byte of R0-R7
 *   PC: 24-bit program counter
 *   CCR: condition code register (I UI H U N Z V C)
 *   EXR: extended control register
 *
 * ER7 is the stack pointer (SP).
 */
public class H8SCpu {
    // --- Registers ---
    private final int[] er = new int[8]; // ER0-ER7 (32-bit)
    private int pc;                       // 24-bit program counter
    private int lastStartPC;               // PC of the instruction currently executing
    private int ccr;                      // condition code register
    private int exr;                      // extended control register

    // --- CCR bit positions ---
    public static final int CCR_C = 0; // Carry
    public static final int CCR_V = 1; // Overflow
    public static final int CCR_Z = 2; // Zero
    public static final int CCR_N = 3; // Negative
    public static final int CCR_U = 4; // User
    public static final int CCR_H = 5; // Half-carry
    public static final int CCR_UI = 6; // User interrupt
    public static final int CCR_I = 7;  // Interrupt mask

    // --- Bus ---
    private AddressBus bus;

    // --- State ---
    private boolean halted = false;
    private boolean tracing = false;
    private long cycleCount = 0;
    private boolean pcTrapFired = false;
    private boolean loopTraced = false;
    // PC profiler: sample PC every N steps and track hotspots
    private final java.util.Map<Integer,Integer> pcHistogram = new java.util.HashMap<>();
    private long sampleCounter = 0;
    private boolean profileDumped = false;
    // Ring buffer of recent PCs for debugging
    private final int[] pcHistory = new int[64];
    private final int[] opHistory = new int[64];
    private int pcHistoryIdx = 0;
    // --- Interrupt support ---
    // Pending interrupt vectors (priority queue, lower vector = higher priority)
    private final java.util.TreeSet<Integer> pendingInterrupts = new java.util.TreeSet<>();

    public H8SCpu(AddressBus bus) {
        this.bus = bus;
    }

    public void setTracing(boolean tracing) { this.tracing = tracing; }
    public boolean isHalted() { return halted; }
    public long getCycleCount() { return cycleCount; }

    // --- Register access ---
    public int getER(int n) { return er[n]; }
    public void setER(int n, int value) { er[n] = value; }

    /**
     * Get word register by 4-bit encoding: 0-7 = R0-R7 (lower 16 bits), 8-F = E0-E7 (upper 16 bits).
     */
    public int getR(int n) {
        if (n < 8) return er[n] & 0xFFFF;
        else return (er[n - 8] >> 16) & 0xFFFF;
    }

    public void setR(int n, int value) {
        if (n < 8) er[n] = (er[n] & 0xFFFF0000) | (value & 0xFFFF);
        else { int i = n - 8; er[i] = (er[i] & 0x0000FFFF) | ((value & 0xFFFF) << 16); }
    }

    /** Direct access to E0-E7 (upper 16 bits) by index 0-7. */
    public int getE(int n) { return (er[n] >> 16) & 0xFFFF; }
    public void setE(int n, int value) { er[n] = (er[n] & 0x0000FFFF) | ((value & 0xFFFF) << 16); }

    public int getRH(int n) { return (er[n] >> 8) & 0xFF; }
    public void setRH(int n, int value) { er[n] = (er[n] & 0xFFFF00FF) | ((value & 0xFF) << 8); }

    public int getRL(int n) { return er[n] & 0xFF; }
    public void setRL(int n, int value) { er[n] = (er[n] & 0xFFFFFF00) | (value & 0xFF); }

    /**
     * Get byte register by 4-bit encoding: 0-7 = R0H-R7H, 8-F = R0L-R7L.
     */
    public int getRegB(int n) {
        if (n < 8) return getRH(n);
        else return getRL(n - 8);
    }

    /**
     * Set byte register by 4-bit encoding: 0-7 = R0H-R7H, 8-F = R0L-R7L.
     */
    public void setRegB(int n, int value) {
        if (n < 8) setRH(n, value);
        else setRL(n - 8, value);
    }

    public int getPC() { return pc; }
    public int getLastStartPC() { return lastStartPC; }
    public void dumpProfile() {
        if (profileDumped || pcHistogram.isEmpty()) return;
        profileDumped = true;
        System.err.println("\n=== PC Profile (top 30 hotspots) ===");
        pcHistogram.entrySet().stream()
            .sorted((a,b) -> b.getValue() - a.getValue())
            .limit(30)
            .forEach(e -> {
                int addr = e.getKey();
                int count = e.getValue();
                // Read the opcode at that address for context
                int opcode = (bus.read8(addr) << 8) | bus.read8(addr + 1);
                System.err.printf("  0x%06X: %6d samples (op=%04X)%n", addr, count, opcode);
            });
    }
    public int getCCR() { return ccr; }
    public int getSP() { return er[7]; }
    public int getPendingInterruptCount() { return pendingInterrupts.size(); }

    // --- CCR flag helpers ---
    private boolean getFlag(int bit) { return (ccr & (1 << bit)) != 0; }
    private void setFlag(int bit, boolean v) {
        if (v) ccr |= (1 << bit);
        else ccr &= ~(1 << bit);
    }

    private void setNZB(int result) {
        setFlag(CCR_N, (result & 0x80) != 0);
        setFlag(CCR_Z, (result & 0xFF) == 0);
    }

    private void setNZW(int result) {
        setFlag(CCR_N, (result & 0x8000) != 0);
        setFlag(CCR_Z, (result & 0xFFFF) == 0);
    }

    private void setNZL(int result) {
        setFlag(CCR_N, (result & 0x80000000) != 0);
        setFlag(CCR_Z, result == 0);
    }

    // --- Reset ---
    public void reset() {
        for (int i = 0; i < 8; i++) er[i] = 0;
        ccr = 0x80; // I flag set (interrupts masked)
        exr = 0;
        halted = false;
        cycleCount = 0;
        // Reset vector is at address 0x000000, contains 32-bit address
        pc = bus.read32(0x000000) & 0xFFFFFF;
        if (tracing) System.out.printf("RESET: PC = 0x%06X%n", pc);
    }

    // --- Fetch helpers ---
    private int fetch8() {
        int val = bus.read8(pc);
        pc = (pc + 1) & 0xFFFFFF;
        return val;
    }

    private int fetch16() {
        int val = bus.read16(pc);
        pc = (pc + 2) & 0xFFFFFF;
        return val;
    }

    private int fetch32() {
        int hi = fetch16();
        int lo = fetch16();
        return (hi << 16) | lo;
    }

    /** Request an interrupt. Will be serviced when I flag is clear. */
    public void requestInterrupt(int vector) {
        pendingInterrupts.add(vector);
    }

    /** Process pending interrupts if I flag is clear. */
    private boolean processInterrupts() {
        if (pendingInterrupts.isEmpty()) return false;
        if (getFlag(CCR_I)) return false; // interrupts masked

        int vector = pendingInterrupts.pollFirst();

        // Push PC and CCR to stack (H8S pushes as: CCR(padded to 16 bits), then PC(32 bits))
        // Actually H8S/2000 pushes: first PC as 32 bits, then CCR as 16 bits (EXR too if enabled)
        // Stack grows down, and after push SP points to top
        // The order on stack (low to high): CCR_word, PC_high, PC_low
        er[7] -= 4;
        bus.write32(er[7], pc); // push PC (32-bit, high byte is 0)
        er[7] -= 2;
        bus.write16(er[7], ccr); // push CCR (as 16-bit, upper byte is 0)

        // Set I flag to mask further interrupts
        setFlag(CCR_I, true);

        // Jump to vector handler
        int handlerAddr = bus.read32(vector * 4) & 0xFFFFFF;
        if (tracing) System.err.printf("[IRQ] Vec %d -> 0x%06X (from PC=0x%06X, SP=0x%06X)%n", vector, handlerAddr, pc, er[7]);
        pc = handlerAddr;

        // If CPU was sleeping, wake it up
        if (halted) halted = false;

        return true;
    }

    // --- Main execution step ---
    public void step() {
        // Check for pending interrupts
        if (processInterrupts()) {
            cycleCount += 1;
            return; // interrupt processing consumed this cycle
        }

        if (halted) {
            // On real H8S, any interrupt request wakes CPU from sleep, even if masked (I=1).
            // When I=1, CPU wakes and continues from next instruction (no vectoring).
            // When I=0, processInterrupts() above already handled wake + vectoring.
            if (!pendingInterrupts.isEmpty()) {
                halted = false;
                // Don't consume the pending interrupts - they stay pending
                // until the software clears the I flag (e.g. via ANDC/LDC)
            } else {
                cycleCount += 1;
                return;
            }
        }

        int startPC = pc;
        lastStartPC = startPC;
        int op = fetch16();

        if (tracing) {
            System.err.printf("[%06X] %04X SP=%06X ", startPC, op, er[7]);
        }

        // Profile: sample every 1024 steps
        sampleCounter++;
        if ((sampleCounter & 0x3FF) == 0) {
            pcHistogram.merge(startPC, 1, Integer::sum);
        }

        try {
            decode(op, startPC);
        } catch (IllegalStateException e) {
            System.err.printf("CPU error at PC=0x%06X: %s%n", startPC, e.getMessage());
            halted = true;
        }

        // Record PC history
        pcHistory[pcHistoryIdx] = startPC;
        opHistory[pcHistoryIdx] = op;
        pcHistoryIdx = (pcHistoryIdx + 1) & 63;

        // Detect PC jumping to unmapped memory OR odd PC
        if (!pcTrapFired && ((pc > 0x7FFFFF && pc < 0xFFDC00) || (pc & 1) != 0)) {
            System.err.printf("!!! PC jumped to bad addr 0x%06X from instruction at 0x%06X (op=%04X) SP=0x%06X%n",
                pc, startPC, op, er[7]);
            System.err.printf("    Registers: ER0=%08X ER1=%08X ER2=%08X ER3=%08X ER4=%08X ER5=%08X ER6=%08X ER7=%08X%n",
                er[0], er[1], er[2], er[3], er[4], er[5], er[6], er[7]);
            // Dump PC history (most recent last)
            System.err.println("    PC history (oldest to newest):");
            for (int i = 0; i < 64; i++) {
                int idx = (pcHistoryIdx + i) & 63;
                if (pcHistory[idx] != 0 || opHistory[idx] != 0) {
                    System.err.printf("      [%06X] %04X%n", pcHistory[idx], opHistory[idx]);
                }
            }
            // Dump stack area
            System.err.printf("    Stack top:");
            for (int i = 0; i < 16; i++) {
                System.err.printf(" %04X", bus.read16(er[7] + i * 2));
            }
            System.err.println();
            pcTrapFired = true;
        }

        cycleCount++;
    }

    // --- Instruction decoder ---
    private void decode(int op, int startPC) {
        int hi = (op >> 8) & 0xFF;
        int lo = op & 0xFF;

        switch (hi >> 4) {
            case 0x0 -> decode0(op, hi, lo);
            case 0x1 -> decode1(op, hi, lo);
            case 0x2 -> movB_absImm(op); // MOV.B @aa:8, Rd
            case 0x3 -> movB_immAbs(op); // MOV.B Rs, @aa:8
            case 0x4 -> decodeBcc(op);   // Bcc d:8
            case 0x5 -> decode5(op, hi, lo);
            case 0x6 -> decode6(op, hi, lo);
            case 0x7 -> decode7(op, hi, lo);
            case 0x8 -> addB_imm(op);    // ADD.B #imm, Rd
            case 0x9 -> addxB_imm(op);   // ADDX.B #imm, Rd (add with carry)
            case 0xA -> cmpB_imm(op);    // CMP.B #imm, Rd
            case 0xB -> subxB_imm(op);   // SUBX.B #imm, Rd
            case 0xC -> orB_imm(op);     // OR.B #imm, Rd
            case 0xD -> xorB_imm(op);    // XOR.B #imm, Rd
            case 0xE -> andB_imm(op);    // AND.B #imm, Rd
            case 0xF -> movB_imm(op);    // MOV.B #imm, Rd
            default -> unimplemented(op, startPC);
        }
    }

    // --- Group 0x0xxx ---
    private void decode0(int op, int hi, int lo) {
        switch (hi) {
            case 0x00 -> { // NOP
                if (lo == 0x00) { trace("NOP"); }
                else unimplemented(op, pc - 2);
            }
            case 0x01 -> decode01(op, lo);
            case 0x02 -> { // STC CCR, Rd (byte register)
                int rd = lo & 0xF;
                setRegB(rd, ccr);
                trace("STC CCR, R%d%s", rd < 8 ? rd : rd - 8, rd < 8 ? "H" : "L");
            }
            case 0x03 -> { // LDC Rs, CCR (byte register)
                int rs = lo & 0xF;
                ccr = getRegB(rs);
                trace("LDC R%d%s, CCR", rs < 8 ? rs : rs - 8, rs < 8 ? "H" : "L");
            }
            case 0x04 -> { // ORC #imm, CCR
                ccr |= lo;
                trace("ORC #0x%02X, CCR", lo);
            }
            case 0x05 -> { // XORC #imm, CCR
                ccr ^= lo;
                trace("XORC #0x%02X, CCR", lo);
            }
            case 0x06 -> { // ANDC #imm, CCR
                ccr &= lo;
                trace("ANDC #0x%02X, CCR", lo);
            }
            case 0x07 -> { // LDC #imm, CCR
                ccr = lo;
                trace("LDC #0x%02X, CCR", lo);
            }
            case 0x08 -> addB_reg(lo);   // ADD.B Rs, Rd
            case 0x09 -> addW_reg(lo);   // ADD.W Rs, Rd
            case 0x0A -> decode0A(op, lo); // INC.B, ADD.L reg
            case 0x0B -> decode0B(op, lo); // ADDS, INC.W, INC.L
            case 0x0C -> movB_reg(lo);   // MOV.B Rs, Rd
            case 0x0D -> movW_reg(lo);   // MOV.W Rs, Rd
            case 0x0E -> { // ADDX.B Rs, Rd
                int rs = (lo >> 4) & 0xF;
                int rd = lo & 0xF;
                int s = getRegB(rs);
                int d = getRegB(rd);
                int c = getFlag(CCR_C) ? 1 : 0;
                int result = d + s + c;
                setRegB(rd, result & 0xFF);
                setFlag(CCR_C, result > 0xFF);
                setFlag(CCR_V, ((d ^ result) & (s ^ result) & 0x80) != 0);
                setFlag(CCR_H, ((d ^ s ^ result) & 0x10) != 0);
                if ((result & 0xFF) != 0) setFlag(CCR_Z, false); // Z is AND'd
                setFlag(CCR_N, (result & 0x80) != 0);
                trace("ADDX.B R%dL, R%dL", rs, rd);
            }
            case 0x0F -> decode0F(op, lo); // MOV.L reg, DAA
            default -> unimplemented(op, pc - 2);
        }
    }

    // --- Group 0x01xx (prefix for 32-bit operations, STM/LDM, LDC/STC) ---
    private void decode01(int op, int lo) {
        switch (lo) {
            case 0x00 -> {
                // 0x0100 prefix for MOV.L and other 32-bit ops
                int op2 = fetch16();
                decode0100(op2);
            }
            // STM.L / LDM.L (store/load multiple)
            case 0x10, 0x20, 0x30 -> {
                int count = (lo >> 4); // 1=2regs, 2=3regs, 3=4regs
                int op2 = fetch16();
                int rn = op2 & 0x7;
                if ((op2 & 0xFFF8) == 0x6D70) {
                    // LDM.L @SP+, ERn-ERn-(count)
                    // Pops ERn first, then ERn-1, etc.
                    for (int i = 0; i <= count; i++) {
                        int reg = rn - i; // MAME: r32_w(m_IR[1]-i, ...)
                        // But LDM pops in order: ERn first (top of stack), then ERn-1
                        // Actually MAME pops ERn, then ERn-1, etc.
                        er[rn - i] = bus.read32(er[7]);
                        er[7] += 4;
                    }
                    trace("LDM.L @SP+, ER%d-ER%d", rn - count, rn);
                } else if ((op2 & 0xFFF8) == 0x6DF0) {
                    // STM.L ERn-ERn+count, @-SP
                    // Pushes ERn first, then ERn+1, then ERn+2
                    for (int i = 0; i <= count; i++) {
                        er[7] -= 4;
                        bus.write32(er[7], er[rn + i]);
                    }
                    trace("STM.L ER%d-ER%d, @-SP", rn, rn + count);
                } else {
                    unimplemented(op, pc - 4);
                }
            }
            case 0x40 -> {
                // 0x0140 prefix: LDC/STC CCR (word), or 0x0141xx EXR ops
                int op2 = fetch16();
                decode0140(op2);
            }
            case 0x41 -> {
                // 0x0141 prefix: EXR operations
                int op2 = fetch16();
                decode0141(op2);
            }
            case 0xC0, 0xD0, 0xF0 -> {
                // 0x01C0, 0x01D0, 0x01F0: various extended prefixes
                int op2 = fetch16();
                int hi2 = (op2 >> 8) & 0xFF;
                if (hi2 == 0x6B) {
                    decode6B_32(op2);
                } else if (hi2 == 0x50 || hi2 == 0x52) {
                    // MULXS.B (50) / MULXS.W (52) - signed multiply
                    decodeMulxs(hi2, op2);
                } else if (hi2 == 0x51 || hi2 == 0x53) {
                    // DIVXS.B (51) / DIVXS.W (53) - signed divide
                    decodeDivxs(hi2, op2);
                } else if (lo == 0xF0 && hi2 == 0x64) {
                    // OR.L ERs,ERd  (01F0 64sd)
                    int rs = (op2 >> 4) & 0x7;
                    int rd = op2 & 0x7;
                    int result = er[rd] | er[rs];
                    er[rd] = result;
                    setNZL(result); setFlag(CCR_V, false);
                    trace("OR.L ER%d, ER%d", rs, rd);
                } else if (lo == 0xF0 && hi2 == 0x65) {
                    // XOR.L ERs,ERd  (01F0 65sd)
                    int rs = (op2 >> 4) & 0x7;
                    int rd = op2 & 0x7;
                    int result = er[rd] ^ er[rs];
                    er[rd] = result;
                    setNZL(result); setFlag(CCR_V, false);
                    trace("XOR.L ER%d, ER%d", rs, rd);
                } else if (lo == 0xF0 && hi2 == 0x66) {
                    // AND.L ERs,ERd  (01F0 66sd)
                    int rs = (op2 >> 4) & 0x7;
                    int rd = op2 & 0x7;
                    int result = er[rd] & er[rs];
                    er[rd] = result;
                    setNZL(result); setFlag(CCR_V, false);
                    trace("AND.L ER%d, ER%d", rs, rd);
                } else {
                    unimplemented(op, pc - 4);
                }
            }
            case 0x80 -> {
                // SLEEP - halt CPU until interrupt
                halted = true;
                trace("SLEEP");
            }
            default -> {
                int op2 = fetch16();
                unimplemented(op, pc - 4);
            }
        }
    }

    // 0x0140 prefix: LDC.W/STC.W for CCR
    private void decode0140(int op2) {
        int hi2 = (op2 >> 8) & 0xFF;
        switch (hi2) {
            case 0x69 -> {
                // LDC.W @ERs, CCR or STC.W CCR, @ERd
                int r = (op2 >> 4) & 0x7;
                if ((op2 & 0x80) == 0) {
                    ccr = bus.read16(er[r]) & 0xFF;
                    trace("LDC.W @ER%d, CCR", r);
                } else {
                    bus.write16(er[r], ccr);
                    trace("STC.W CCR, @ER%d", r);
                }
            }
            case 0x6B -> {
                boolean write = (op2 & 0x80) != 0;
                boolean addr24 = (op2 & 0x20) != 0;
                int addr = addr24 ? (fetch32() & 0xFFFFFF) : ((short) fetch16() & 0xFFFFFF);
                if (!write) {
                    ccr = bus.read16(addr) & 0xFF;
                    trace("LDC.W @0x%06X, CCR", addr);
                } else {
                    bus.write16(addr, ccr);
                    trace("STC.W CCR, @0x%06X", addr);
                }
            }
            case 0x6D -> {
                int r = (op2 >> 4) & 0x7;
                if ((op2 & 0x80) == 0) {
                    ccr = bus.read16(er[r]) & 0xFF;
                    er[r] += 2;
                    trace("LDC.W @ER%d+, CCR", r);
                } else {
                    er[r] -= 2;
                    bus.write16(er[r], ccr);
                    trace("STC.W CCR, @-ER%d", r);
                }
            }
            case 0x6F -> {
                int r = (op2 >> 4) & 0x7;
                int disp = (short) fetch16();
                if ((op2 & 0x80) == 0) {
                    ccr = bus.read16(er[r] + disp) & 0xFF;
                    trace("LDC.W @(%d, ER%d), CCR", disp, r);
                } else {
                    bus.write16(er[r] + disp, ccr);
                    trace("STC.W CCR, @(%d, ER%d)", disp, r);
                }
            }
            default -> unimplemented(0x0140, pc - 2);
        }
    }

    // 0x0141 prefix: EXR operations
    private void decode0141(int op2) {
        int hi2 = (op2 >> 8) & 0xFF;
        switch (hi2) {
            case 0x04 -> { exr |= (op2 & 0xFF); trace("ORC #0x%02X, EXR", op2 & 0xFF); }
            case 0x05 -> { exr ^= (op2 & 0xFF); trace("XORC #0x%02X, EXR", op2 & 0xFF); }
            case 0x06 -> { exr &= (op2 & 0xFF); trace("ANDC #0x%02X, EXR", op2 & 0xFF); }
            case 0x07 -> { exr = op2 & 0xFF; trace("LDC #0x%02X, EXR", op2 & 0xFF); }
            default -> unimplemented(0x0141, pc - 2);
        }
    }

    // MULXS (signed multiply)
    // hi2=0x50: MULXS.B Rs, Rd (8x8->16 signed)
    // hi2=0x52: MULXS.W Rs, ERd (16x16->32 signed)
    private void decodeMulxs(int hi2, int op2) {
        int rs = (op2 >> 4) & 0xF;
        int rd = op2 & 0xF;
        if (hi2 == 0x50) {
            // MULXS.B Rs, Rd (8x8->16 signed)
            int result = (byte) getRegB(rs) * (byte) getRegB(rd);
            setR(rd, result & 0xFFFF);
            setFlag(CCR_N, (result & 0x8000) != 0);
            setFlag(CCR_Z, (result & 0xFFFF) == 0);
            trace("MULXS.B R%dL, R%d", rs, rd);
        } else {
            // MULXS.W Rs, ERd (16x16->32 signed)
            int result = (short) getR(rs) * (short) getR(rd);
            er[rd & 0x7] = result;
            setFlag(CCR_N, result < 0);
            setFlag(CCR_Z, result == 0);
            trace("MULXS.W R%d, ER%d", rs, rd & 0x7);
        }
    }

    // DIVXS (signed divide)
    // hi2=0x51: DIVXS.B Rs, ERd (16/8 signed divide, quotient in RdL, remainder in RdH)
    // hi2=0x53: DIVXS.W Rs, ERd (32/16 signed divide, quotient in Rd, remainder in Ed)
    private void decodeDivxs(int hi2, int op2) {
        int rs = (op2 >> 4) & 0xF;
        int rd = op2 & 0x7;
        if (hi2 == 0x51) {
            // DIVXS.B Rs, Rd (16/8 signed)
            int dividend = (short) getR(rd);
            int divisor = (byte) getRegB(rs);
            if (divisor == 0) {
                setFlag(CCR_Z, true);
                trace("DIVXS.B R%dL, R%d (div by 0)", rs, rd);
            } else {
                int quotient = dividend / divisor;
                int remainder = dividend % divisor;
                setR(rd, ((remainder & 0xFF) << 8) | (quotient & 0xFF));
                setFlag(CCR_N, quotient < 0);
                setFlag(CCR_Z, quotient == 0);
                trace("DIVXS.B R%dL, R%d", rs, rd);
            }
        } else {
            // DIVXS.W Rs, ERd (32/16 signed)
            int dividend = er[rd];
            int divisor = (short) getR(rs);
            if (divisor == 0) {
                setFlag(CCR_Z, true);
                trace("DIVXS.W R%d, ER%d (div by 0)", rs, rd);
            } else {
                int quotient = dividend / divisor;
                int remainder = dividend % divisor;
                er[rd] = ((remainder & 0xFFFF) << 16) | (quotient & 0xFFFF);
                setFlag(CCR_N, quotient < 0);
                setFlag(CCR_Z, quotient == 0);
                trace("DIVXS.W R%d, ER%d", rs, rd);
            }
        }
    }

    private void decode0100(int op2) {
        int hi2 = (op2 >> 8) & 0xFF;
        int lo2 = op2 & 0xFF;
        switch (hi2) {
            case 0x69 -> {
                // MOV.L @ERs, ERd or MOV.L ERs, @ERd (32-bit indirect)
                // MAME: bits 6-4 = address reg (rh), bits 2-0 = data reg (rl)
                int rh = (lo2 >> 4) & 0x7;
                int rl = lo2 & 0x7;
                if ((lo2 & 0x80) == 0) {
                    // MOV.L @ERs, ERd - rh=addr, rl=dest
                    er[rl] = bus.read32(er[rh]);
                    setNZL(er[rl]);
                    setFlag(CCR_V, false);
                    trace("MOV.L @ER%d, ER%d", rh, rl);
                } else {
                    // MOV.L ERs, @ERd - rl=source, rh=addr
                    bus.write32(er[rh], er[rl]);
                    setNZL(er[rl]);
                    setFlag(CCR_V, false);
                    trace("MOV.L ER%d, @ER%d", rl, rh);
                }
            }
            case 0x6B -> decode6B_32(op2);
            case 0x6D -> {
                // MOV.L @ERs+, ERd or MOV.L ERs, @-ERd (32-bit pre-dec/post-inc)
                // MAME: 01006d00 = mov.l @r32ph, r32l (post-inc src=bits6-4, dest=bits2-0)
                //        01006d80 = mov.l r32l, @pr32h (pre-dec src=bits2-0, addr=bits6-4)
                int rh = (lo2 >> 4) & 0x7;  // bits 6-4
                int rl = lo2 & 0x7;          // bits 2-0
                if ((lo2 & 0x80) == 0) {
                    // MOV.L @ERs+, ERd (pop) - rh=addr(post-inc), rl=dest
                    // Must use temp: when rh==rl, post-increment must not clobber loaded value
                    int val = bus.read32(er[rh]);
                    er[rh] += 4;
                    er[rl] = val;
                    setNZL(er[rl]);
                    setFlag(CCR_V, false);
                    trace("MOV.L @ER%d+, ER%d", rh, rl);
                } else {
                    // MOV.L ERs, @-ERd (push) - rl=source, rh=addr(pre-dec)
                    // Must snapshot source: when rh==rl, pre-dec must not change written value
                    int val = er[rl];
                    er[rh] -= 4;
                    bus.write32(er[rh], val);
                    setNZL(val);
                    setFlag(CCR_V, false);
                    trace("MOV.L ER%d, @-ER%d", rl, rh);
                }
            }
            case 0x6F -> {
                // MOV.L @(d:16, ERs), ERd or MOV.L ERs, @(d:16, ERd)
                // MAME: bits 6-4 = address reg (rh), bits 2-0 = data reg (rl)
                int rh = (lo2 >> 4) & 0x7;
                int rl = lo2 & 0x7;
                int disp = (short) fetch16(); // sign-extend
                if ((lo2 & 0x80) == 0) {
                    er[rl] = bus.read32(er[rh] + disp);
                    setNZL(er[rl]);
                    setFlag(CCR_V, false);
                    trace("MOV.L @(%d, ER%d), ER%d", disp, rh, rl);
                } else {
                    bus.write32(er[rh] + disp, er[rl]);
                    setNZL(er[rl]);
                    setFlag(CCR_V, false);
                    trace("MOV.L ER%d, @(%d, ER%d)", rl, disp, rh);
                }
            }
            case 0x78 -> {
                // MOV.L @(d:24, ERs), ERd -- 0100 78r0 6B20 disp24
                // or MOV.L ERs, @(d:24, ERd) -- 0100 78r0 6BA0 disp24
                int r1 = (lo2 >> 4) & 0x7;
                int op3 = fetch16();
                int disp = fetch32();
                int r2 = op3 & 0x7;
                if ((op3 & 0x0080) == 0) {
                    er[r2] = bus.read32(er[r1] + disp);
                    setNZL(er[r2]);
                    setFlag(CCR_V, false);
                    trace("MOV.L @(%d, ER%d), ER%d", disp, r1, r2);
                } else {
                    bus.write32(er[r2] + disp, er[r1]);
                    setNZL(er[r1]);
                    setFlag(CCR_V, false);
                    trace("MOV.L ER%d, @(%d, ER%d)", r1, disp, r2);
                }
            }
            default -> unimplemented(0x01000000 | (op2 & 0xFFFF), pc - 4);
        }
    }

    // MOV.L with absolute addresses (after 0100 or 01F0 prefix)
    private void decode6B_32(int op2) {
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

        if (!write) {
            er[rd] = bus.read32(addr);
            setNZL(er[rd]);
            setFlag(CCR_V, false);
            trace("MOV.L @0x%06X, ER%d", addr, rd);
        } else {
            bus.write32(addr, er[rd]);
            setNZL(er[rd]);
            setFlag(CCR_V, false);
            trace("MOV.L ER%d, @0x%06X", rd, addr);
        }
    }

    // --- Group 0x1xxx ---
    private void decode1(int op, int hi, int lo) {
        switch (hi) {
            case 0x10 -> decode10_17(op, hi, lo); // SHLL.B, ROTXL.B, ROTL.B, NOT.B
            case 0x11 -> decode10_17(op, hi, lo); // SHLL.W, ROTXL.W, ROTL.W, NOT.W
            case 0x12 -> decode10_17(op, hi, lo); // SHAL.B, ROTXL.B...
            case 0x13 -> decode10_17(op, hi, lo); // SHAL.W, ROTXL.W...
            case 0x14 -> decode10_17(op, hi, lo); // SHLR.B
            case 0x15 -> decode10_17(op, hi, lo); // SHLR.W
            case 0x16 -> decode10_17(op, hi, lo); // SHAR.B
            case 0x17 -> decode10_17(op, hi, lo); // SHAR.W, EXTU, EXTS
            case 0x18 -> subB_reg(lo);   // SUB.B Rs, Rd
            case 0x19 -> subW_reg(lo);   // SUB.W Rs, Rd
            case 0x1A -> decode1A(op, lo); // DEC.B, SUB.L reg, CMP.L reg
            case 0x1B -> decode1B(op, lo); // SUBS, DEC.W, DEC.L, SUB.L reg
            case 0x1C -> cmpB_reg(lo);   // CMP.B Rs, Rd
            case 0x1D -> cmpW_reg(lo);   // CMP.W Rs, Rd
            case 0x1E -> { // SUBX.B Rs, Rd
                int rs = (lo >> 4) & 0xF;
                int rd = lo & 0xF;
                int s = getRegB(rs);
                int d = getRegB(rd);
                int c = getFlag(CCR_C) ? 1 : 0;
                int result = d - s - c;
                setRegB(rd, result & 0xFF);
                setFlag(CCR_C, result < 0);
                setFlag(CCR_V, ((d ^ s) & (d ^ result) & 0x80) != 0);
                setFlag(CCR_H, ((d ^ s ^ result) & 0x10) != 0);
                if ((result & 0xFF) != 0) setFlag(CCR_Z, false);
                setFlag(CCR_N, (result & 0x80) != 0);
                trace("SUBX.B R%dL, R%dL", rs, rd);
            }
            case 0x1F -> decode1F(op, lo); // CMP.L reg, DAS, DIVXS
            default -> unimplemented(op, pc - 2);
        }
    }

    // --- Shift/rotate/extend group (0x10-0x17) ---
    private void decode10_17(int op, int hi, int lo) {
        // MAME encoding: hi byte = operation group, lo byte = ssssrrrr
        // where ssss selects size/count/#2 variant and rrrr is register
        // Key: lo byte determines opcode within the group via full 8-bit match
        // Use mask/match from MAME h8.lst for correct decoding.
        // hi=0x10: SHLL+SHAL, hi=0x11: SHLR+SHAR, hi=0x12: ROTXL+ROTL,
        // hi=0x13: ROTXR+ROTR, hi=0x14: OR.B, hi=0x15: XOR.B, hi=0x16: AND.B,
        // hi=0x17: NOT+EXTU+NEG+EXTS

        // For 0x14-0x16: these are byte register-to-register operations
        if (hi == 0x14) { orB_reg(lo); return; }
        if (hi == 0x15) { xorB_reg(lo); return; }
        if (hi == 0x16) { andB_reg(lo); return; }

        // For 0x17: NOT, EXTU, NEG, EXTS
        if (hi == 0x17) {
            decode17(op, lo);
            return;
        }

        // 0x10-0x13: shift/rotate operations
        // lo byte layout: ssssrrrr where ssss selects the operation
        int subop = (lo >> 4) & 0xF;
        int rd = lo & 0xF;   // byte/word register index
        int erd = lo & 0x7;  // longword register index
        switch (hi) {
            case 0x10 -> { // SHLL / SHAL
                switch (subop) {
                    case 0x0 -> shiftB(rd, true, true, 1, "SHLL.B");     // SHLL.B #1
                    case 0x1 -> shiftW(rd, true, true, 1, "SHLL.W");     // (actually this is wrong - see below)
                    case 0x3 -> shiftL(erd, true, true, 1, "SHLL.L");
                    case 0x4 -> shiftB(rd, true, true, 2, "SHLL.B #2,"); // SHLL.B #2
                    case 0x5 -> shiftW(rd, true, true, 2, "SHLL.W #2,");
                    case 0x7 -> shiftL(erd, true, true, 2, "SHLL.L #2,");
                    case 0x8 -> shiftB(rd, true, false, 1, "SHAL.B");    // SHAL.B #1
                    case 0x9 -> shiftW(rd, true, false, 1, "SHAL.W");
                    case 0xB -> shiftL(erd, true, false, 1, "SHAL.L");
                    case 0xC -> shiftB(rd, true, false, 2, "SHAL.B #2,");
                    case 0xD -> shiftW(rd, true, false, 2, "SHAL.W #2,");
                    case 0xF -> shiftL(erd, true, false, 2, "SHAL.L #2,");
                    default -> unimplemented(op, pc - 2);
                }
            }
            case 0x11 -> { // SHLR / SHAR
                switch (subop) {
                    case 0x0 -> shiftB(rd, false, true, 1, "SHLR.B");
                    case 0x1 -> shiftW(rd, false, true, 1, "SHLR.W");
                    case 0x3 -> shiftL(erd, false, true, 1, "SHLR.L");
                    case 0x4 -> shiftB(rd, false, true, 2, "SHLR.B #2,");
                    case 0x5 -> shiftW(rd, false, true, 2, "SHLR.W #2,");
                    case 0x7 -> shiftL(erd, false, true, 2, "SHLR.L #2,");
                    case 0x8 -> shiftB(rd, false, false, 1, "SHAR.B");
                    case 0x9 -> shiftW(rd, false, false, 1, "SHAR.W");
                    case 0xB -> shiftL(erd, false, false, 1, "SHAR.L");
                    case 0xC -> shiftB(rd, false, false, 2, "SHAR.B #2,");
                    case 0xD -> shiftW(rd, false, false, 2, "SHAR.W #2,");
                    case 0xF -> shiftL(erd, false, false, 2, "SHAR.L #2,");
                    default -> unimplemented(op, pc - 2);
                }
            }
            case 0x12 -> { // ROTXL / ROTL
                switch (subop) {
                    case 0x0 -> rotateB(rd, true, true, 1, "ROTXL.B");
                    case 0x1 -> rotateW(rd, true, true, 1, "ROTXL.W");
                    case 0x3 -> rotateL(erd, true, true, 1, "ROTXL.L");
                    case 0x4 -> rotateB(rd, true, true, 2, "ROTXL.B #2,");
                    case 0x5 -> rotateW(rd, true, true, 2, "ROTXL.W #2,");
                    case 0x7 -> rotateL(erd, true, true, 2, "ROTXL.L #2,");
                    case 0x8 -> rotateB(rd, true, false, 1, "ROTL.B");
                    case 0x9 -> rotateW(rd, true, false, 1, "ROTL.W");
                    case 0xB -> rotateL(erd, true, false, 1, "ROTL.L");
                    case 0xC -> rotateB(rd, true, false, 2, "ROTL.B #2,");
                    case 0xD -> rotateW(rd, true, false, 2, "ROTL.W #2,");
                    case 0xF -> rotateL(erd, true, false, 2, "ROTL.L #2,");
                    default -> unimplemented(op, pc - 2);
                }
            }
            case 0x13 -> { // ROTXR / ROTR
                switch (subop) {
                    case 0x0 -> rotateB(rd, false, true, 1, "ROTXR.B");
                    case 0x1 -> rotateW(rd, false, true, 1, "ROTXR.W");
                    case 0x3 -> rotateL(erd, false, true, 1, "ROTXR.L");
                    case 0x4 -> rotateB(rd, false, true, 2, "ROTXR.B #2,");
                    case 0x5 -> rotateW(rd, false, true, 2, "ROTXR.W #2,");
                    case 0x7 -> rotateL(erd, false, true, 2, "ROTXR.L #2,");
                    case 0x8 -> rotateB(rd, false, false, 1, "ROTR.B");
                    case 0x9 -> rotateW(rd, false, false, 1, "ROTR.W");
                    case 0xB -> rotateL(erd, false, false, 1, "ROTR.L");
                    case 0xC -> rotateB(rd, false, false, 2, "ROTR.B #2,");
                    case 0xD -> rotateW(rd, false, false, 2, "ROTR.W #2,");
                    case 0xF -> rotateL(erd, false, false, 2, "ROTR.L #2,");
                    default -> unimplemented(op, pc - 2);
                }
            }
            default -> unimplemented(op, pc - 2);
        }
    }

    // 0x17: NOT, EXTU, NEG, EXTS
    private void decode17(int op, int lo) {
        int subop = (lo >> 4) & 0xF;
        int rd = lo & 0xF;
        int erd = lo & 0x7;
        switch (subop) {
            case 0x0 -> { int v = (~getRegB(rd)) & 0xFF; setRegB(rd, v); setNZB(v); setFlag(CCR_V, false); trace("NOT.B R%dL", rd); }
            case 0x1 -> { int v = (~getR(rd)) & 0xFFFF; setR(rd, v); setNZW(v); setFlag(CCR_V, false); trace("NOT.W R%d", rd); }
            case 0x3 -> { er[erd] = ~er[erd]; setNZL(er[erd]); setFlag(CCR_V, false); trace("NOT.L ER%d", erd); }
            case 0x5 -> { setR(rd, getRL(rd)); setNZW(getR(rd)); setFlag(CCR_V, false); trace("EXTU.W R%d", rd); }
            case 0x7 -> { er[erd] = getR(erd); setNZL(er[erd]); setFlag(CCR_V, false); trace("EXTU.L ER%d", erd); }
            case 0x8 -> {
                int v = getRegB(rd); int r = (-v) & 0xFF; setRegB(rd, r); setNZB(r);
                setFlag(CCR_C, r != 0); setFlag(CCR_V, v == 0x80); setFlag(CCR_H, ((v ^ r) & 0x10) != 0);
                trace("NEG.B R%dL", rd);
            }
            case 0x9 -> {
                int v = getR(rd); int r = (-v) & 0xFFFF; setR(rd, r); setNZW(r);
                setFlag(CCR_C, r != 0); setFlag(CCR_V, v == 0x8000);
                trace("NEG.W R%d", rd);
            }
            case 0xB -> {
                long v = er[erd] & 0xFFFFFFFFL; er[erd] = (int)(-v); setNZL(er[erd]);
                setFlag(CCR_C, er[erd] != 0); setFlag(CCR_V, v == 0x80000000L);
                trace("NEG.L ER%d", erd);
            }
            case 0xD -> { int v = (byte) getRL(rd); setR(rd, v & 0xFFFF); setNZW(v); setFlag(CCR_V, false); trace("EXTS.W R%d", rd); }
            case 0xF -> { int v = (short) getR(erd); er[erd] = v; setNZL(v); setFlag(CCR_V, false); trace("EXTS.L ER%d", erd); }
            default -> unimplemented(op, pc - 2);
        }
    }

    // --- Generic shift helpers ---
    // left=true for left shift, logical=true for logical (vs arithmetic)
    private void shiftB(int rd, boolean left, boolean logical, int count, String name) {
        int val = getRegB(rd);
        for (int i = 0; i < count; i++) {
            if (left) {
                setFlag(CCR_C, (val & 0x80) != 0);
                int old = val;
                val = (val << 1) & 0xFF;
                if (!logical) setFlag(CCR_V, ((old ^ val) & 0x80) != 0);
            } else {
                setFlag(CCR_C, (val & 1) != 0);
                if (logical) val = (val >> 1) & 0x7F;
                else val = ((byte) val >> 1) & 0xFF;
            }
        }
        setRegB(rd, val); setNZB(val);
        if (left && logical) setFlag(CCR_V, false);
        if (!left) setFlag(CCR_V, false);
        trace("%s R%dL", name, rd);
    }

    private void shiftW(int rd, boolean left, boolean logical, int count, String name) {
        int val = getR(rd);
        for (int i = 0; i < count; i++) {
            if (left) {
                setFlag(CCR_C, (val & 0x8000) != 0);
                int old = val;
                val = (val << 1) & 0xFFFF;
                if (!logical) setFlag(CCR_V, ((old ^ val) & 0x8000) != 0);
            } else {
                setFlag(CCR_C, (val & 1) != 0);
                if (logical) val = (val >> 1) & 0x7FFF;
                else val = ((short) val >> 1) & 0xFFFF;
            }
        }
        setR(rd, val); setNZW(val);
        if (left && logical) setFlag(CCR_V, false);
        if (!left) setFlag(CCR_V, false);
        trace("%s R%d", name, rd);
    }

    private void shiftL(int erd, boolean left, boolean logical, int count, String name) {
        int val = er[erd];
        for (int i = 0; i < count; i++) {
            if (left) {
                setFlag(CCR_C, (val & 0x80000000) != 0);
                int old = val;
                val = val << 1;
                if (!logical) setFlag(CCR_V, ((old ^ val) & 0x80000000) != 0);
            } else {
                setFlag(CCR_C, (val & 1) != 0);
                if (logical) val = val >>> 1;
                else val = val >> 1; // arithmetic
            }
        }
        er[erd] = val; setNZL(val);
        if (left && logical) setFlag(CCR_V, false);
        if (!left) setFlag(CCR_V, false);
        trace("%s ER%d", name, erd);
    }

    // --- Generic rotate helpers ---
    // left=true for left rotate, throughCarry=true for ROTX (rotate through carry)
    private void rotateB(int rd, boolean left, boolean throughCarry, int count, String name) {
        int val = getRegB(rd);
        for (int i = 0; i < count; i++) {
            if (left) {
                int msb = (val >> 7) & 1;
                if (throughCarry) {
                    int oldC = getFlag(CCR_C) ? 1 : 0;
                    setFlag(CCR_C, msb != 0);
                    val = ((val << 1) | oldC) & 0xFF;
                } else {
                    setFlag(CCR_C, msb != 0);
                    val = ((val << 1) | msb) & 0xFF;
                }
            } else {
                int lsb = val & 1;
                if (throughCarry) {
                    int oldC = getFlag(CCR_C) ? 0x80 : 0;
                    setFlag(CCR_C, lsb != 0);
                    val = ((val >> 1) | oldC) & 0xFF;
                } else {
                    setFlag(CCR_C, lsb != 0);
                    val = ((val >> 1) | (lsb << 7)) & 0xFF;
                }
            }
        }
        setRegB(rd, val); setNZB(val); setFlag(CCR_V, false);
        trace("%s R%dL", name, rd);
    }

    private void rotateW(int rd, boolean left, boolean throughCarry, int count, String name) {
        int val = getR(rd);
        for (int i = 0; i < count; i++) {
            if (left) {
                int msb = (val >> 15) & 1;
                if (throughCarry) {
                    int oldC = getFlag(CCR_C) ? 1 : 0;
                    setFlag(CCR_C, msb != 0);
                    val = ((val << 1) | oldC) & 0xFFFF;
                } else {
                    setFlag(CCR_C, msb != 0);
                    val = ((val << 1) | msb) & 0xFFFF;
                }
            } else {
                int lsb = val & 1;
                if (throughCarry) {
                    int oldC = getFlag(CCR_C) ? 0x8000 : 0;
                    setFlag(CCR_C, lsb != 0);
                    val = ((val >> 1) | oldC) & 0xFFFF;
                } else {
                    setFlag(CCR_C, lsb != 0);
                    val = ((val >> 1) | (lsb << 15)) & 0xFFFF;
                }
            }
        }
        setR(rd, val); setNZW(val); setFlag(CCR_V, false);
        trace("%s R%d", name, rd);
    }

    private void rotateL(int erd, boolean left, boolean throughCarry, int count, String name) {
        int val = er[erd];
        for (int i = 0; i < count; i++) {
            if (left) {
                int msb = (val >>> 31) & 1;
                if (throughCarry) {
                    int oldC = getFlag(CCR_C) ? 1 : 0;
                    setFlag(CCR_C, msb != 0);
                    val = (val << 1) | oldC;
                } else {
                    setFlag(CCR_C, msb != 0);
                    val = (val << 1) | msb;
                }
            } else {
                int lsb = val & 1;
                if (throughCarry) {
                    int oldC = getFlag(CCR_C) ? 0x80000000 : 0;
                    setFlag(CCR_C, lsb != 0);
                    val = (val >>> 1) | oldC;
                } else {
                    setFlag(CCR_C, lsb != 0);
                    val = (val >>> 1) | (lsb << 31);
                }
            }
        }
        er[erd] = val; setNZL(val); setFlag(CCR_V, false);
        trace("%s ER%d", name, erd);
    }

    // Byte register-to-register operations for 0x14-0x16
    private void orB_reg(int lo) {
        int rs = (lo >> 4) & 0xF; int rd = lo & 0xF;
        int result = getRegB(rd) | getRegB(rs);
        setRegB(rd, result); setNZB(result); setFlag(CCR_V, false);
        trace("OR.B R%dL, R%dL", rs, rd);
    }
    private void xorB_reg(int lo) {
        int rs = (lo >> 4) & 0xF; int rd = lo & 0xF;
        int result = getRegB(rd) ^ getRegB(rs);
        setRegB(rd, result); setNZB(result); setFlag(CCR_V, false);
        trace("XOR.B R%dL, R%dL", rs, rd);
    }
    private void andB_reg(int lo) {
        int rs = (lo >> 4) & 0xF; int rd = lo & 0xF;
        int result = getRegB(rd) & getRegB(rs);
        setRegB(rd, result); setNZB(result); setFlag(CCR_V, false);
        trace("AND.B R%dL, R%dL", rs, rd);
    }

    // --- 0x0A: INC.B, ADD.L ERs,ERd ---
    private void decode0A(int op, int lo) {
        if ((lo & 0x80) != 0) {
            // ADD.L ERs, ERd: 0x0A_1sss_0ddd where bit7=1
            int rs = (lo >> 4) & 0x7;
            int rd = lo & 0x7;
            long s = er[rs] & 0xFFFFFFFFL;
            long d = er[rd] & 0xFFFFFFFFL;
            long result = d + s;
            er[rd] = (int) result;
            setNZL(er[rd]);
            setFlag(CCR_C, (result & 0x100000000L) != 0);
            setFlag(CCR_V, ((int)((d ^ result) & (s ^ result)) < 0));
            setFlag(CCR_H, (((int)(d ^ s ^ result)) & 0x10000000) != 0);
            trace("ADD.L ER%d, ER%d", rs, rd);
        } else if ((lo & 0xF0) == 0x00) {
            // INC.B Rd
            int rd = lo & 0xF;
            int val = getRegB(rd);
            int result = (val + 1) & 0xFF;
            setRegB(rd, result);
            setNZB(result);
            setFlag(CCR_V, val == 0x7F);
            trace("INC.B R%dL", rd);
        } else if ((lo & 0xF0) == 0x50) {
            // INC.W #1
            int rd = lo & 0xF;
            int val = getR(rd);
            int result = (val + 1) & 0xFFFF;
            setR(rd, result);
            setNZW(result);
            setFlag(CCR_V, val == 0x7FFF);
            trace("INC.W #1, R%d", rd);
        } else if ((lo & 0xF0) == 0x70) {
            // INC.L #1
            int rd = lo & 0x7;
            int val = er[rd];
            er[rd] = val + 1;
            setNZL(er[rd]);
            setFlag(CCR_V, val == 0x7FFFFFFF);
            trace("INC.L #1, ER%d", rd);
        } else {
            unimplemented(op, pc - 2);
        }
    }

    // --- 0x0B: ADDS, INC.W, INC.L ---
    private void decode0B(int op, int lo) {
        switch (lo & 0xF0) {
            case 0x00 -> { int rd = lo & 0x7; er[rd] += 1; trace("ADDS #1, ER%d", rd); }
            case 0x50 -> {
                int rd = lo & 0xF;
                int val = getR(rd); int result = (val + 1) & 0xFFFF;
                setR(rd, result); setNZW(result); setFlag(CCR_V, val == 0x7FFF);
                trace("INC.W #1, R%d", rd);
            }
            case 0x70 -> {
                int rd = lo & 0x7;
                int val = er[rd]; er[rd] = val + 1;
                setNZL(er[rd]); setFlag(CCR_V, val == 0x7FFFFFFF);
                trace("INC.L #1, ER%d", rd);
            }
            case 0x80 -> { int rd = lo & 0x7; er[rd] += 2; trace("ADDS #2, ER%d", rd); }
            case 0x90 -> { int rd = lo & 0x7; er[rd] += 4; trace("ADDS #4, ER%d", rd); }
            case 0xD0 -> {
                int rd = lo & 0xF;
                int val = getR(rd); int result = (val + 2) & 0xFFFF;
                setR(rd, result); setNZW(result);
                trace("INC.W #2, R%d", rd);
            }
            case 0xF0 -> {
                int rd = lo & 0x7; er[rd] += 2;
                setNZL(er[rd]);
                trace("INC.L #2, ER%d", rd);
            }
            default -> unimplemented(op, pc - 2);
        }
    }

    // --- 0x0F: MOV.L ERs,ERd / DAA ---
    private void decode0F(int op, int lo) {
        if ((lo & 0x80) != 0) {
            // MOV.L ERs, ERd
            int rs = (lo >> 4) & 0x7;
            int rd = lo & 0x7;
            er[rd] = er[rs];
            setNZL(er[rd]);
            setFlag(CCR_V, false);
            trace("MOV.L ER%d, ER%d", rs, rd);
        } else if ((lo & 0xF0) == 0x00) {
            // DAA Rd
            trace("DAA R%dL (stub)", lo & 0xF);
        } else {
            unimplemented(op, pc - 2);
        }
    }

    // --- 0x1A: DEC.B, SUB.L ERs,ERd ---
    private void decode1A(int op, int lo) {
        if ((lo & 0x80) != 0) {
            // SUB.L ERs, ERd
            int rs = (lo >> 4) & 0x7;
            int rd = lo & 0x7;
            long s = er[rs] & 0xFFFFFFFFL;
            long d = er[rd] & 0xFFFFFFFFL;
            long result = d - s;
            er[rd] = (int) result;
            setNZL(er[rd]);
            setFlag(CCR_C, (result & 0x100000000L) != 0);
            setFlag(CCR_V, ((int)((d ^ s) & (d ^ result)) < 0));
            setFlag(CCR_H, (((int)(d ^ s ^ result)) & 0x10000000) != 0);
            trace("SUB.L ER%d, ER%d", rs, rd);
        } else if ((lo & 0xF0) == 0x00) {
            // DEC.B Rd
            int rd = lo & 0xF;
            int val = getRegB(rd);
            int result = (val - 1) & 0xFF;
            setRegB(rd, result);
            setNZB(result);
            setFlag(CCR_V, val == 0x80);
            trace("DEC.B R%dL", rd);
        } else if ((lo & 0xF0) == 0x50) {
            // DEC.W #1
            int rd = lo & 0xF;
            int val = getR(rd);
            int result = (val - 1) & 0xFFFF;
            setR(rd, result);
            setNZW(result);
            setFlag(CCR_V, val == 0x8000);
            trace("DEC.W #1, R%d", rd);
        } else if ((lo & 0xF0) == 0x70) {
            // DEC.L #1
            int rd = lo & 0x7;
            int val = er[rd];
            er[rd] = val - 1;
            setNZL(er[rd]);
            setFlag(CCR_V, val == 0x80000000);
            trace("DEC.L #1, ER%d", rd);
        } else {
            unimplemented(op, pc - 2);
        }
    }

    // --- 0x1B: SUBS, DEC.W, DEC.L ---
    private void decode1B(int op, int lo) {
        switch (lo & 0xF0) {
            case 0x00 -> { int rd = lo & 0x7; er[rd] -= 1; trace("SUBS #1, ER%d", rd); }
            case 0x50 -> {
                int rd = lo & 0xF;
                int val = getR(rd); int result = (val - 1) & 0xFFFF;
                setR(rd, result); setNZW(result); setFlag(CCR_V, val == 0x8000);
                trace("DEC.W #1, R%d", rd);
            }
            case 0x70 -> {
                int rd = lo & 0x7;
                int val = er[rd]; er[rd] = val - 1;
                setNZL(er[rd]); setFlag(CCR_V, val == 0x80000000);
                trace("DEC.L #1, ER%d", rd);
            }
            case 0x80 -> { int rd = lo & 0x7; er[rd] -= 2; trace("SUBS #2, ER%d", rd); }
            case 0x90 -> { int rd = lo & 0x7; er[rd] -= 4; trace("SUBS #4, ER%d", rd); }
            case 0xD0 -> {
                int rd = lo & 0xF;
                int val = getR(rd); int result = (val - 2) & 0xFFFF;
                setR(rd, result); setNZW(result);
                setFlag(CCR_V, val == 0x8000 || val == 0x8001);
                trace("DEC.W #2, R%d", rd);
            }
            case 0xF0 -> {
                int rd = lo & 0x7; er[rd] -= 2;
                setNZL(er[rd]);
                trace("DEC.L #2, ER%d", rd);
            }
            default -> unimplemented(op, pc - 2);
        }
    }

    // --- 0x1F: CMP.L ERs,ERd / DAS ---
    private void decode1F(int op, int lo) {
        if ((lo & 0x80) != 0) {
            // CMP.L ERs, ERd
            int rs = (lo >> 4) & 0x7;
            int rd = lo & 0x7;
            long s = er[rs] & 0xFFFFFFFFL;
            long d = er[rd] & 0xFFFFFFFFL;
            long result = d - s;
            setNZL((int) result);
            setFlag(CCR_C, (result & 0x100000000L) != 0);
            setFlag(CCR_V, ((int)((d ^ s) & (d ^ result)) < 0));
            setFlag(CCR_H, (((int)(d ^ s ^ result)) & 0x10000000) != 0);
            trace("CMP.L ER%d, ER%d", rs, rd);
        } else if ((lo & 0xF0) == 0x00) {
            // DAS Rd
            trace("DAS R%dL (stub)", lo & 0xF);
        } else {
            unimplemented(op, pc - 2);
        }
    }

    // --- Group 0x4xxx: Bcc (branch on condition) ---
    private void decodeBcc(int op) {
        int cond = (op >> 8) & 0xF;
        int disp = (byte) (op & 0xFF); // sign-extend 8-bit displacement
        int target = (pc + disp) & 0xFFFFFF;
        boolean taken = evaluateCondition(cond);
        if (taken) {
            pc = target;
        }
        trace("B%s 0x%06X (%s)", condName(cond), target, taken ? "taken" : "not taken");
    }

    private boolean evaluateCondition(int cond) {
        boolean c = getFlag(CCR_C);
        boolean z = getFlag(CCR_Z);
        boolean n = getFlag(CCR_N);
        boolean v = getFlag(CCR_V);

        return switch (cond) {
            case 0x0 -> true;           // BRA (always)
            case 0x1 -> false;          // BRN (never)
            case 0x2 -> !c && !z;       // BHI (higher)
            case 0x3 -> c || z;         // BLS (lower or same)
            case 0x4 -> !c;             // BCC/BHS (carry clear)
            case 0x5 -> c;              // BCS/BLO (carry set)
            case 0x6 -> !z;             // BNE (not equal)
            case 0x7 -> z;              // BEQ (equal)
            case 0x8 -> !v;             // BVC (overflow clear)
            case 0x9 -> v;              // BVS (overflow set)
            case 0xA -> !n;             // BPL (plus)
            case 0xB -> n;              // BMI (minus)
            case 0xC -> !(n ^ v);       // BGE (greater or equal)
            case 0xD -> n ^ v;          // BLT (less than)
            case 0xE -> !(z || (n ^ v)); // BGT (greater than)
            case 0xF -> z || (n ^ v);   // BLE (less or equal)
            default -> false;
        };
    }

    private String condName(int cond) {
        return switch (cond) {
            case 0x0 -> "RA"; case 0x1 -> "RN"; case 0x2 -> "HI"; case 0x3 -> "LS";
            case 0x4 -> "CC"; case 0x5 -> "CS"; case 0x6 -> "NE"; case 0x7 -> "EQ";
            case 0x8 -> "VC"; case 0x9 -> "VS"; case 0xA -> "PL"; case 0xB -> "MI";
            case 0xC -> "GE"; case 0xD -> "LT"; case 0xE -> "GT"; case 0xF -> "LE";
            default -> "??";
        };
    }

    // --- Group 0x5xxx ---
    private void decode5(int op, int hi, int lo) {
        switch (hi) {
            case 0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57 -> {
                // MULXU.B Rs, Rd (0x50sd)
                // Actually 0x50 = MULXU.B
                if (hi == 0x50) {
                    int rs = (lo >> 4) & 0xF;
                    int rd = lo & 0xF;
                    int result = getRegB(rs) * getRegB(rd);
                    setR(rd, result & 0xFFFF);
                    trace("MULXU.B R%dL, R%d", rs, rd);
                } else if (hi == 0x51) {
                    // DIVXU.B Rs, Rd
                    int rs = (lo >> 4) & 0xF;
                    int rd = lo & 0xF;
                    int divisor = getRegB(rs);
                    if (divisor == 0) {
                        setFlag(CCR_Z, true);
                        trace("DIVXU.B R%dL, R%d (div by zero)", rs, rd);
                    } else {
                        int dividend = getR(rd);
                        int quotient = (dividend / divisor) & 0xFF;
                        int remainder = (dividend % divisor) & 0xFF;
                        setR(rd, (remainder << 8) | quotient);
                        setFlag(CCR_N, (quotient & 0x80) != 0);
                        setFlag(CCR_Z, quotient == 0);
                        trace("DIVXU.B R%dL, R%d", rs, rd);
                    }
                } else if (hi == 0x52) {
                    // MULXU.W Rs, ERd
                    int rs = (lo >> 4) & 0xF;
                    int rd = lo & 0x7;
                    int result = getR(rs) * getR(rd);
                    er[rd] = result;
                    trace("MULXU.W R%d, ER%d", rs, rd);
                } else if (hi == 0x53) {
                    // DIVXU.W Rs, ERd
                    int rs = (lo >> 4) & 0xF;
                    int rd = lo & 0x7;
                    int divisor = getR(rs) & 0xFFFF;
                    if (divisor == 0) {
                        setFlag(CCR_Z, true);
                        trace("DIVXU.W R%d, ER%d (div by zero)", rs, rd);
                    } else {
                        long dividend = er[rd] & 0xFFFFFFFFL;
                        int quotient = (int) ((dividend / divisor) & 0xFFFF);
                        int remainder = (int) ((dividend % divisor) & 0xFFFF);
                        er[rd] = (remainder << 16) | quotient;
                        setFlag(CCR_N, (quotient & 0x8000) != 0);
                        setFlag(CCR_Z, quotient == 0);
                        trace("DIVXU.W R%d, ER%d", rs, rd);
                    }
                } else if (hi == 0x54) {
                    // RTS
                    if (lo == 0x70) {
                        pc = bus.read32(er[7]) & 0xFFFFFF;
                        er[7] += 4;
                        trace("RTS -> 0x%06X", pc);
                    } else {
                        unimplemented(op, pc - 2);
                    }
                } else if (hi == 0x55) {
                    // BSR d:8
                    int disp = (byte) lo;
                    er[7] -= 4;
                    bus.write32(er[7], pc);
                    pc = (pc + disp) & 0xFFFFFF;
                    trace("BSR 0x%06X", pc);
                } else if (hi == 0x56) {
                    // RTE - return from exception
                    if (lo == 0x70) {
                        // Pop CCR (16-bit) and PC (32-bit) from stack
                        // Stack layout (low to high): CCR_word, PC_32bit
                        ccr = bus.read16(er[7]) & 0xFF;
                        er[7] += 2;
                        pc = bus.read32(er[7]) & 0xFFFFFF;
                        er[7] += 4;
                        trace("RTE -> 0x%06X (CCR=0x%02X)", pc, ccr);
                    } else {
                        unimplemented(op, pc - 2);
                    }
                } else if (hi == 0x57) {
                    // TRAPA
                    if ((lo & 0xC0) == 0x00) {
                        int vec = (lo >> 4) & 0x3;
                        // Push PC and CCR
                        er[7] -= 4;
                        bus.write32(er[7], (ccr << 24) | pc);
                        // Read vector
                        pc = bus.read32(0x20 + vec * 4) & 0xFFFFFF;
                        setFlag(CCR_I, true);
                        trace("TRAPA #%d -> 0x%06X", vec, pc);
                    } else {
                        unimplemented(op, pc - 2);
                    }
                } else {
                    unimplemented(op, pc - 2);
                }
            }
            case 0x58 -> {
                // Bcc d:16 (16-bit displacement branch)
                int cond = (lo >> 4) & 0xF;
                int disp16Word = fetch16();
                int disp = (short) disp16Word; // sign-extend
                boolean taken = evaluateCondition(cond);
                if (taken) {
                    pc = (pc + disp) & 0xFFFFFF;
                }
                trace("B%s:16 0x%06X (%s)", condName(cond), pc, taken ? "taken" : "not taken");
            }
            case 0x59 -> {
                // JMP @ERn
                int rn = (lo >> 4) & 0x7;
                pc = er[rn] & 0xFFFFFF;
                trace("JMP @ER%d (0x%06X)", rn, pc);
            }
            case 0x5A -> {
                // JMP @aa:24
                int addr = (lo << 16) | fetch16();
                pc = addr & 0xFFFFFF;
                trace("JMP 0x%06X", pc);
            }
            case 0x5B -> {
                // JMP @@aa:8 (memory indirect)
                int addr = bus.read32(lo & 0xFF) & 0xFFFFFF;
                pc = addr;
                trace("JMP @@0x%02X (0x%06X)", lo, pc);
            }
            case 0x5C -> {
                // BSR d:16
                int disp = (short) fetch16();
                er[7] -= 4;
                bus.write32(er[7], pc);
                pc = (pc + disp) & 0xFFFFFF;
                trace("BSR:16 0x%06X", pc);
            }
            case 0x5D -> {
                // JSR @ERn
                int rn = (lo >> 4) & 0x7;
                er[7] -= 4;
                bus.write32(er[7], pc);
                pc = er[rn] & 0xFFFFFF;
                trace("JSR @ER%d (0x%06X)", rn, pc);
            }
            case 0x5E -> {
                // JSR @aa:24
                int addr = (lo << 16) | fetch16();
                er[7] -= 4;
                bus.write32(er[7], pc);
                pc = addr & 0xFFFFFF;
                trace("JSR 0x%06X", pc);
            }
            case 0x5F -> {
                // JSR @@aa:8 (memory indirect)
                int addr = bus.read32(lo & 0xFF) & 0xFFFFFF;
                er[7] -= 4;
                bus.write32(er[7], pc);
                pc = addr;
                trace("JSR @@0x%02X (0x%06X)", lo, pc);
            }
            default -> unimplemented(op, pc - 2);
        }
    }

    // --- Group 0x6xxx ---
    private void decode6(int op, int hi, int lo) {
        switch (hi) {
            case 0x60 -> { // BSET Rn, Rd
                int rn = (lo >> 4) & 0xF;
                int rd = lo & 0xF;
                int bit = getRegB(rn) & 0x7;
                setRegB(rd, getRegB(rd) | (1 << bit));
                trace("BSET R%dL, R%dL", rn, rd);
            }
            case 0x61 -> { // BNOT Rn, Rd
                int rn = (lo >> 4) & 0xF;
                int rd = lo & 0xF;
                int bit = getRegB(rn) & 0x7;
                setRegB(rd, getRegB(rd) ^ (1 << bit));
                trace("BNOT R%dL, R%dL", rn, rd);
            }
            case 0x62 -> { // BCLR Rn, Rd
                int rn = (lo >> 4) & 0xF;
                int rd = lo & 0xF;
                int bit = getRegB(rn) & 0x7;
                setRegB(rd, getRegB(rd) & ~(1 << bit));
                trace("BCLR R%dL, R%dL", rn, rd);
            }
            case 0x63 -> { // BTST Rn, Rd
                int rn = (lo >> 4) & 0xF;
                int rd = lo & 0xF;
                int bit = getRegB(rn) & 0x7;
                setFlag(CCR_Z, (getRegB(rd) & (1 << bit)) == 0);
                trace("BTST R%dL, R%dL", rn, rd);
            }
            case 0x64 -> orW_reg(lo);    // OR.W Rs, Rd
            case 0x65 -> xorW_reg(lo);   // XOR.W Rs, Rd
            case 0x66 -> andW_reg(lo);   // AND.W Rs, Rd
            case 0x67 -> { // BST/BIST #imm, Rd
                int bit = (lo >> 4) & 0x7;
                int rd = lo & 0xF;
                if ((lo & 0x80) == 0) {
                    // BST #imm, Rd (store carry to bit)
                    if (getFlag(CCR_C)) {
                        setRegB(rd, getRegB(rd) | (1 << bit));
                    } else {
                        setRegB(rd, getRegB(rd) & ~(1 << bit));
                    }
                    trace("BST #%d, R%dL", bit, rd);
                } else {
                    // BIST #imm, Rd (store inverted carry to bit)
                    if (!getFlag(CCR_C)) {
                        setRegB(rd, getRegB(rd) | (1 << bit));
                    } else {
                        setRegB(rd, getRegB(rd) & ~(1 << bit));
                    }
                    trace("BIST #%d, R%dL", bit, rd);
                }
            }
            case 0x68 -> {
                // MOV.B: bits 6-4=ER reg (addr), bits 3-0=byte reg (data), bit 7=dir
                int erReg = (lo >> 4) & 0x7;
                int bReg = lo & 0xF;
                if ((lo & 0x80) == 0) {
                    // MOV.B @ERs, Rd
                    int val = bus.read8(er[erReg]);
                    setRegB(bReg, val);
                    setNZB(val);
                    setFlag(CCR_V, false);
                    trace("MOV.B @ER%d, R%dL", erReg, bReg);
                } else {
                    // MOV.B Rs, @ERd
                    int val = getRegB(bReg);
                    bus.write8(er[erReg], val);
                    setNZB(val);
                    setFlag(CCR_V, false);
                    trace("MOV.B R%dL, @ER%d", bReg, erReg);
                }
            }
            case 0x69 -> {
                // MOV.W: bits 6-4=ER reg, bits 3-0=word reg, bit 7=dir
                int erReg = (lo >> 4) & 0x7;
                int wReg = lo & 0xF;
                if ((lo & 0x80) == 0) {
                    int val = bus.read16(er[erReg]);
                    setR(wReg, val);
                    setNZW(val);
                    setFlag(CCR_V, false);
                    trace("MOV.W @ER%d, R%d", erReg, wReg);
                } else {
                    int val = getR(wReg);
                    bus.write16(er[erReg], val);
                    setNZW(val);
                    setFlag(CCR_V, false);
                    trace("MOV.W R%d, @ER%d", wReg, erReg);
                }
            }
            case 0x6A -> decode6A(op, lo);
            case 0x6B -> decode6B(op, lo);
            case 0x6C -> {
                // MOV.B: bits 6-4=ER reg, bits 3-0=byte reg, bit 7=dir
                int erReg = (lo >> 4) & 0x7;
                int bReg = lo & 0xF;
                if ((lo & 0x80) == 0) {
                    // MOV.B @ERs+, Rd (post-increment)
                    int val = bus.read8(er[erReg]);
                    er[erReg] += 1;
                    setRegB(bReg, val);
                    setNZB(val);
                    setFlag(CCR_V, false);
                    trace("MOV.B @ER%d+, R%dL", erReg, bReg);
                } else {
                    // MOV.B Rs, @-ERd (pre-decrement)
                    // Snapshot source before pre-dec in case bReg aliases erReg
                    int val = getRegB(bReg);
                    er[erReg] -= 1;
                    bus.write8(er[erReg], val);
                    setNZB(val);
                    setFlag(CCR_V, false);
                    trace("MOV.B R%dL, @-ER%d", bReg, erReg);
                }
            }
            case 0x6D -> {
                // MOV.W: bits 6-4=ER reg, bits 3-0=word reg, bit 7=dir
                int erReg = (lo >> 4) & 0x7;
                int wReg = lo & 0xF;
                if ((lo & 0x80) == 0) {
                    // MOV.W @ERs+, Rd (post-increment / pop)
                    int val = bus.read16(er[erReg]);
                    er[erReg] += 2;
                    setR(wReg, val);
                    setNZW(val);
                    setFlag(CCR_V, false);
                    trace("MOV.W @ER%d+, R%d", erReg, wReg);
                } else {
                    // MOV.W Rs, @-ERd (pre-decrement / push)
                    // Snapshot source before pre-dec in case wReg aliases erReg
                    int val = getR(wReg);
                    er[erReg] -= 2;
                    bus.write16(er[erReg], val);
                    setNZW(val);
                    setFlag(CCR_V, false);
                    trace("MOV.W R%d, @-ER%d", wReg, erReg);
                }
            }
            case 0x6E -> {
                // MOV.B @(d:16, ERs), Rd or MOV.B Rs, @(d:16, ERd)
                int erReg = (lo >> 4) & 0x7;
                int bReg = lo & 0xF;
                int disp = (short) fetch16();
                if ((lo & 0x80) == 0) {
                    int val = bus.read8(er[erReg] + disp);
                    setRegB(bReg, val);
                    setNZB(val);
                    setFlag(CCR_V, false);
                    trace("MOV.B @(%d, ER%d), R%dL", disp, erReg, bReg);
                } else {
                    int val = getRegB(bReg);
                    bus.write8(er[erReg] + disp, val);
                    setNZB(val);
                    setFlag(CCR_V, false);
                    trace("MOV.B R%dL, @(%d, ER%d)", bReg, disp, erReg);
                }
            }
            case 0x6F -> {
                // MOV.W: bits 6-4=ER reg, bits 3-0=word reg, bit 7=dir
                int erReg = (lo >> 4) & 0x7;
                int wReg = lo & 0xF;
                int disp = (short) fetch16();
                if ((lo & 0x80) == 0) {
                    int val = bus.read16(er[erReg] + disp);
                    setR(wReg, val);
                    setNZW(val);
                    setFlag(CCR_V, false);
                    trace("MOV.W @(%d, ER%d), R%d", disp, erReg, wReg);
                } else {
                    int val = getR(wReg);
                    bus.write16(er[erReg] + disp, val);
                    setNZW(val);
                    setFlag(CCR_V, false);
                    trace("MOV.W R%d, @(%d, ER%d)", wReg, disp, erReg);
                }
            }
            default -> unimplemented(op, pc - 2);
        }
    }

    // MOV.B with absolute address (0x6A), or compound bit manipulation at @aa:16/32
    private void decode6A(int op, int lo) {
        int topNibble = (lo >> 4) & 0xF;

        // 6A 1x/3x = compound bit manipulation instructions at absolute address
        if (topNibble == 1 || topNibble == 3) {
            boolean addr32 = (topNibble == 3);
            int addr;
            if (addr32) {
                addr = fetch32() & 0xFFFFFF;
            } else {
                addr = (short) fetch16();
                addr &= 0xFFFFFF;
            }
            // Next word is the bit operation
            int bitOp = fetch16();
            executeBitOpAtAddress(addr, bitOp);
            return;
        }

        // Standard MOV.B with absolute address
        // Bit 7: direction (0=read, 1=write)
        // Bit 5: address size (0=16-bit, 1=24-bit as 32-bit word)
        // Bits 3-0: byte register
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

        if (!write) {
            int val = bus.read8(addr);
            setRegB(rd, val);
            setNZB(val);
            setFlag(CCR_V, false);
            trace("MOV.B @0x%06X, R%dL", addr, rd);
        } else {
            int val = getRegB(rd);
            bus.write8(addr, val);
            setNZB(val);
            setFlag(CCR_V, false);
            trace("MOV.B R%dL, @0x%06X", rd, addr);
        }
    }

    /**
     * Execute a bit manipulation operation on a byte at a memory address.
     * Used by compound instructions: 7D/7E/7F/6A prefixes.
     * The bitOp word encodes the operation and bit number.
     */
    private void executeBitOpAtAddress(int addr, int bitOp) {
        int opType = (bitOp >> 8) & 0xFF;
        int bitNum = (bitOp >> 4) & 0x07;
        int val = bus.read8(addr);

        switch (opType) {
            case 0x70 -> { // BSET #imm, @aa
                val |= (1 << bitNum);
                bus.write8(addr, val);
                trace("BSET #%d, @0x%06X", bitNum, addr);
            }
            case 0x71 -> { // BNOT #imm, @aa
                val ^= (1 << bitNum);
                bus.write8(addr, val);
                trace("BNOT #%d, @0x%06X", bitNum, addr);
            }
            case 0x72 -> { // BCLR #imm, @aa
                val &= ~(1 << bitNum);
                bus.write8(addr, val);
                trace("BCLR #%d, @0x%06X", bitNum, addr);
            }
            case 0x73 -> { // BTST #imm, @aa
                setFlag(CCR_Z, (val & (1 << bitNum)) == 0);
                trace("BTST #%d, @0x%06X", bitNum, addr);
            }
            case 0x74 -> { // BOR #imm, @aa (OR bit into C)
                if ((val & (1 << bitNum)) != 0) setFlag(CCR_C, true);
                trace("BOR #%d, @0x%06X", bitNum, addr);
            }
            case 0x75 -> { // BXOR #imm, @aa
                if ((val & (1 << bitNum)) != 0) setFlag(CCR_C, !getFlag(CCR_C));
                trace("BXOR #%d, @0x%06X", bitNum, addr);
            }
            case 0x76 -> { // BAND #imm, @aa
                if ((val & (1 << bitNum)) == 0) setFlag(CCR_C, false);
                trace("BAND #%d, @0x%06X", bitNum, addr);
            }
            case 0x77 -> { // BLD #imm, @aa (load bit to C)
                setFlag(CCR_C, (val & (1 << bitNum)) != 0);
                trace("BLD #%d, @0x%06X", bitNum, addr);
            }
            case 0x67 -> { // BST #imm, @aa (store C to bit)
                if (getFlag(CCR_C)) val |= (1 << bitNum);
                else val &= ~(1 << bitNum);
                bus.write8(addr, val);
                trace("BST #%d, @0x%06X", bitNum, addr);
            }
            // Register-specified bit operations (Rn determines bit number)
            case 0x60 -> { // BSET Rn, @aa
                int rn = (bitOp >> 4) & 0xF;
                int bit = getRegB(rn) & 0x7;
                bus.write8(addr, val | (1 << bit));
                trace("BSET R%d, @0x%06X", rn, addr);
            }
            case 0x61 -> { // BNOT Rn, @aa
                int rn = (bitOp >> 4) & 0xF;
                int bit = getRegB(rn) & 0x7;
                bus.write8(addr, val ^ (1 << bit));
                trace("BNOT R%d, @0x%06X", rn, addr);
            }
            case 0x62 -> { // BCLR Rn, @aa
                int rn = (bitOp >> 4) & 0xF;
                int bit = getRegB(rn) & 0x7;
                bus.write8(addr, val & ~(1 << bit));
                trace("BCLR R%d, @0x%06X", rn, addr);
            }
            case 0x63 -> { // BTST Rn, @aa
                int rn = (bitOp >> 4) & 0xF;
                int bit = getRegB(rn) & 0x7;
                setFlag(CCR_Z, (val & (1 << bit)) == 0);
                trace("BTST R%d, @0x%06X", rn, addr);
            }
            default -> unimplemented(bitOp, pc - 2);
        }
    }

    // MOV.W with absolute address (0x6B)
    private void decode6B(int op, int lo) {
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

        if (!write) {
            int val = bus.read16(addr);
            setR(rd, val);
            setNZW(val);
            setFlag(CCR_V, false);
            trace("MOV.W @0x%06X, R%d", addr, rd);
        } else {
            int val = getR(rd);
            bus.write16(addr, val);
            setNZW(val);
            setFlag(CCR_V, false);
            trace("MOV.W R%d, @0x%06X", rd, addr);
        }
    }

    // --- Group 0x7xxx ---
    private void decode7(int op, int hi, int lo) {
        switch (hi) {
            case 0x70 -> { // BSET #imm, Rd
                int bit = (lo >> 4) & 0x7;
                int rd = lo & 0xF;
                setRegB(rd, getRegB(rd) | (1 << bit));
                trace("BSET #%d, R%dL", bit, rd);
            }
            case 0x71 -> { // BNOT #imm, Rd
                int bit = (lo >> 4) & 0x7;
                int rd = lo & 0xF;
                setRegB(rd, getRegB(rd) ^ (1 << bit));
                trace("BNOT #%d, R%dL", bit, rd);
            }
            case 0x72 -> { // BCLR #imm, Rd
                int bit = (lo >> 4) & 0x7;
                int rd = lo & 0xF;
                setRegB(rd, getRegB(rd) & ~(1 << bit));
                trace("BCLR #%d, R%dL", bit, rd);
            }
            case 0x73 -> { // BTST #imm, Rd
                int bit = (lo >> 4) & 0x7;
                int rd = lo & 0xF;
                setFlag(CCR_Z, (getRegB(rd) & (1 << bit)) == 0);
                trace("BTST #%d, R%dL", bit, rd);
            }
            case 0x74 -> { // BOR/BIOR #imm, Rd
                int bit = (lo >> 4) & 0x7;
                int rd = lo & 0xF;
                boolean bitVal = (getRegB(rd) & (1 << bit)) != 0;
                if ((lo & 0x80) == 0) {
                    setFlag(CCR_C, getFlag(CCR_C) | bitVal);
                    trace("BOR #%d, R%dL", bit, rd);
                } else {
                    setFlag(CCR_C, getFlag(CCR_C) | !bitVal);
                    trace("BIOR #%d, R%dL", bit, rd);
                }
            }
            case 0x75 -> { // BXOR/BIXOR #imm, Rd
                int bit = (lo >> 4) & 0x7;
                int rd = lo & 0xF;
                boolean bitVal = (getRegB(rd) & (1 << bit)) != 0;
                if ((lo & 0x80) == 0) {
                    setFlag(CCR_C, getFlag(CCR_C) ^ bitVal);
                    trace("BXOR #%d, R%dL", bit, rd);
                } else {
                    setFlag(CCR_C, getFlag(CCR_C) ^ !bitVal);
                    trace("BIXOR #%d, R%dL", bit, rd);
                }
            }
            case 0x76 -> { // BAND/BIAND #imm, Rd
                int bit = (lo >> 4) & 0x7;
                int rd = lo & 0xF;
                boolean bitVal = (getRegB(rd) & (1 << bit)) != 0;
                if ((lo & 0x80) == 0) {
                    setFlag(CCR_C, getFlag(CCR_C) & bitVal);
                    trace("BAND #%d, R%dL", bit, rd);
                } else {
                    setFlag(CCR_C, getFlag(CCR_C) & !bitVal);
                    trace("BIAND #%d, R%dL", bit, rd);
                }
            }
            case 0x77 -> { // BLD/BILD #imm, Rd
                int bit = (lo >> 4) & 0x7;
                int rd = lo & 0xF;
                boolean bitVal = (getRegB(rd) & (1 << bit)) != 0;
                if ((lo & 0x80) == 0) {
                    setFlag(CCR_C, bitVal);
                    trace("BLD #%d, R%dL", bit, rd);
                } else {
                    setFlag(CCR_C, !bitVal);
                    trace("BILD #%d, R%dL", bit, rd);
                }
            }
            case 0x78 -> {
                // MOV.B/W with 32-bit displacement: 78r0 6Axx/6Bxx disp32
                int erReg = (lo >> 4) & 0x7;
                int op2 = fetch16();
                int hi2 = (op2 >> 8) & 0xFF;
                if (hi2 == 0x6A) {
                    // MOV.B @(d:32, ERs), Rd or MOV.B Rs, @(d:32, ERd)
                    int bReg = op2 & 0xF;
                    int disp = fetch32();
                    if ((op2 & 0x80) == 0) {
                        int val = bus.read8(er[erReg] + disp);
                        setRegB(bReg, val);
                        setNZB(val);
                        setFlag(CCR_V, false);
                        trace("MOV.B @(%d, ER%d), R%dL", disp, erReg, bReg);
                    } else {
                        int val = getRegB(bReg);
                        bus.write8(er[erReg] + disp, val);
                        setNZB(val);
                        setFlag(CCR_V, false);
                        trace("MOV.B R%dL, @(%d, ER%d)", bReg, disp, erReg);
                    }
                } else if (hi2 == 0x6B) {
                    // MOV.W @(d:32, ERs), Rd or MOV.W Rs, @(d:32, ERd)
                    int wReg = op2 & 0xF;
                    int disp = fetch32();
                    if ((op2 & 0x80) == 0) {
                        int val = bus.read16(er[erReg] + disp);
                        setR(wReg, val);
                        setNZW(val);
                        setFlag(CCR_V, false);
                        trace("MOV.W @(%d, ER%d), R%d", disp, erReg, wReg);
                    } else {
                        int val = getR(wReg);
                        bus.write16(er[erReg] + disp, val);
                        setNZW(val);
                        setFlag(CCR_V, false);
                        trace("MOV.W R%d, @(%d, ER%d)", wReg, disp, erReg);
                    }
                } else {
                    unimplemented(op, pc - 4);
                }
            }
            case 0x79 -> decode79(lo);   // word immediate operations
            case 0x7A -> decode7A(lo);   // longword immediate operations
            case 0x7B -> {
                // EEPMOV - block memory copy
                int op2 = fetch16();
                if (lo == 0x5C && op2 == 0x598F) {
                    // EEPMOV.B: count=R4L, src=ER5, dst=ER6
                    while (getRegB(0xC) != 0) { // R4L = reg index 0xC (4+8)
                        int b = bus.read8(er[5]);
                        bus.write8(er[6], b);
                        er[5]++;
                        er[6]++;
                        setRegB(0xC, (getRegB(0xC) - 1) & 0xFF);
                    }
                    trace("EEPMOV.B");
                } else if (lo == 0xD4 && op2 == 0x598F) {
                    // EEPMOV.W: count=R4, src=ER5, dst=ER6
                    while (getR(4) != 0) {
                        int b = bus.read8(er[5]);
                        bus.write8(er[6], b);
                        er[5]++;
                        er[6]++;
                        setR(4, (getR(4) - 1) & 0xFFFF);
                    }
                    trace("EEPMOV.W");
                } else {
                    unimplemented(op, pc - 4);
                }
            }
            case 0x7C, 0x7D, 0x7E, 0x7F -> decode7C_7F(hi, lo);
            default -> unimplemented(op, pc - 2);
        }
    }

    // 0x79xx: word immediate operations
    private void decode79(int lo) {
        int subop = (lo >> 4) & 0xF;
        int rd = lo & 0xF;
        int imm = fetch16();
        switch (subop) {
            case 0x0 -> { // MOV.W #imm, Rd
                setR(rd, imm);
                setNZW(imm);
                setFlag(CCR_V, false);
                trace("MOV.W #0x%04X, R%d", imm, rd);
            }
            case 0x1 -> { // ADD.W #imm, Rd
                int d = getR(rd);
                int result = d + imm;
                setR(rd, result & 0xFFFF);
                setNZW(result);
                setFlag(CCR_C, result > 0xFFFF);
                setFlag(CCR_V, ((d ^ result) & (imm ^ result) & 0x8000) != 0);
                setFlag(CCR_H, ((d ^ imm ^ result) & 0x1000) != 0);
                trace("ADD.W #0x%04X, R%d", imm, rd);
            }
            case 0x2 -> { // CMP.W #imm, Rd
                int d = getR(rd);
                int result = d - imm;
                setNZW(result);
                setFlag(CCR_C, (result & 0x10000) != 0);
                setFlag(CCR_V, ((d ^ imm) & (d ^ result) & 0x8000) != 0);
                setFlag(CCR_H, ((d ^ imm ^ result) & 0x1000) != 0);
                trace("CMP.W #0x%04X, R%d", imm, rd);
            }
            case 0x3 -> { // SUB.W #imm, Rd
                int d = getR(rd);
                int result = d - imm;
                setR(rd, result & 0xFFFF);
                setNZW(result);
                setFlag(CCR_C, (result & 0x10000) != 0);
                setFlag(CCR_V, ((d ^ imm) & (d ^ result) & 0x8000) != 0);
                setFlag(CCR_H, ((d ^ imm ^ result) & 0x1000) != 0);
                trace("SUB.W #0x%04X, R%d", imm, rd);
            }
            case 0x4 -> { // OR.W #imm, Rd
                int result = getR(rd) | imm;
                setR(rd, result & 0xFFFF);
                setNZW(result);
                setFlag(CCR_V, false);
                trace("OR.W #0x%04X, R%d", imm, rd);
            }
            case 0x5 -> { // XOR.W #imm, Rd
                int result = getR(rd) ^ imm;
                setR(rd, result & 0xFFFF);
                setNZW(result);
                setFlag(CCR_V, false);
                trace("XOR.W #0x%04X, R%d", imm, rd);
            }
            case 0x6 -> { // AND.W #imm, Rd
                int result = getR(rd) & imm;
                setR(rd, result & 0xFFFF);
                setNZW(result);
                setFlag(CCR_V, false);
                trace("AND.W #0x%04X, R%d", imm, rd);
            }
            default -> unimplemented(0x7900 | (lo), pc - 4);
        }
    }

    // 0x7Axx: longword immediate operations
    private void decode7A(int lo) {
        int subop = (lo >> 4) & 0xF;
        int rd = lo & 0x7;
        int imm = fetch32();
        switch (subop) {
            case 0x0 -> { // MOV.L #imm, ERd
                er[rd] = imm;
                setNZL(imm);
                setFlag(CCR_V, false);
                trace("MOV.L #0x%08X, ER%d", imm, rd);
            }
            case 0x1 -> { // ADD.L #imm, ERd
                long d = er[rd] & 0xFFFFFFFFL;
                long result = d + (imm & 0xFFFFFFFFL);
                er[rd] = (int) result;
                setNZL(er[rd]);
                setFlag(CCR_C, (result & 0x100000000L) != 0);
                setFlag(CCR_V, ((int)((d ^ result) & (imm ^ result)) < 0));
                setFlag(CCR_H, (((int)(d ^ imm ^ result)) & 0x10000000) != 0);
                trace("ADD.L #0x%08X, ER%d", imm, rd);
            }
            case 0x2 -> { // CMP.L #imm, ERd
                long d = er[rd] & 0xFFFFFFFFL;
                long result = d - (imm & 0xFFFFFFFFL);
                setNZL((int) result);
                setFlag(CCR_C, (result & 0x100000000L) != 0);
                setFlag(CCR_V, ((int)((d ^ imm) & (d ^ result)) < 0));
                setFlag(CCR_H, (((int)(d ^ imm ^ result)) & 0x10000000) != 0);
                trace("CMP.L #0x%08X, ER%d", imm, rd);
            }
            case 0x3 -> { // SUB.L #imm, ERd
                long d = er[rd] & 0xFFFFFFFFL;
                long result = d - (imm & 0xFFFFFFFFL);
                er[rd] = (int) result;
                setNZL(er[rd]);
                setFlag(CCR_C, (result & 0x100000000L) != 0);
                setFlag(CCR_V, ((int)((d ^ imm) & (d ^ result)) < 0));
                setFlag(CCR_H, (((int)(d ^ imm ^ result)) & 0x10000000) != 0);
                trace("SUB.L #0x%08X, ER%d", imm, rd);
            }
            case 0x4 -> { // OR.L #imm, ERd
                er[rd] |= imm;
                setNZL(er[rd]);
                setFlag(CCR_V, false);
                trace("OR.L #0x%08X, ER%d", imm, rd);
            }
            case 0x5 -> { // XOR.L #imm, ERd
                er[rd] ^= imm;
                setNZL(er[rd]);
                setFlag(CCR_V, false);
                trace("XOR.L #0x%08X, ER%d", imm, rd);
            }
            case 0x6 -> { // AND.L #imm, ERd
                er[rd] &= imm;
                setNZL(er[rd]);
                setFlag(CCR_V, false);
                trace("AND.L #0x%08X, ER%d", imm, rd);
            }
            default -> unimplemented(0x7A00 | lo, pc - 6);
        }
    }

    // 0x7C-0x7F: bit operations on memory
    private void decode7C_7F(int hi, int lo) {
        // 0x7Crd followed by bit operation word
        // 0x7Drd followed by bit operation word
        // 0x7Erd followed by bit operation word
        // 0x7Frd followed by bit operation word
        int r = (lo >> 4) & 0x7;
        int op2 = fetch16();
        int bit = (op2 >> 4) & 0x7;
        int subop = (op2 >> 8) & 0xFF;

        switch (hi) {
            case 0x7C -> {
                // Bit ops on @ERd (read-modify)
                int addr = er[r];
                int val = bus.read8(addr);
                switch (subop) {
                    case 0x63 -> { // BTST #imm, @ERd
                        setFlag(CCR_Z, (val & (1 << bit)) == 0);
                        trace("BTST #%d, @ER%d", bit, r);
                    }
                    case 0x73 -> { // BTST #imm, @ERd (alternate encoding)
                        setFlag(CCR_Z, (val & (1 << bit)) == 0);
                        trace("BTST #%d, @ER%d", bit, r);
                    }
                    case 0x77 -> { // BLD #imm, @ERd
                        if ((op2 & 0x80) == 0) {
                            setFlag(CCR_C, (val & (1 << bit)) != 0);
                            trace("BLD #%d, @ER%d", bit, r);
                        } else {
                            setFlag(CCR_C, (val & (1 << bit)) == 0);
                            trace("BILD #%d, @ER%d", bit, r);
                        }
                    }
                    default -> unimplemented(hi << 8 | lo, pc - 2);
                }
            }
            case 0x7D -> {
                // Bit ops on @ERd (read-modify-write)
                int addr = er[r];
                int val = bus.read8(addr);
                switch (subop) {
                    case 0x60 -> { // BSET Rn, @ERd
                        bus.write8(addr, val | (1 << (getRL(op2 & 0xF) & 0x7)));
                        trace("BSET R%dL, @ER%d", op2 & 0xF, r);
                    }
                    case 0x62 -> { // BCLR Rn, @ERd
                        bus.write8(addr, val & ~(1 << (getRL(op2 & 0xF) & 0x7)));
                        trace("BCLR R%dL, @ER%d", op2 & 0xF, r);
                    }
                    case 0x67 -> { // BST #imm, @ERd
                        if ((op2 & 0x80) == 0) {
                            if (getFlag(CCR_C)) val |= (1 << bit);
                            else val &= ~(1 << bit);
                            bus.write8(addr, val);
                            trace("BST #%d, @ER%d", bit, r);
                        } else {
                            if (!getFlag(CCR_C)) val |= (1 << bit);
                            else val &= ~(1 << bit);
                            bus.write8(addr, val);
                            trace("BIST #%d, @ER%d", bit, r);
                        }
                    }
                    case 0x70 -> { // BSET #imm, @ERd
                        bus.write8(addr, val | (1 << bit));
                        trace("BSET #%d, @ER%d", bit, r);
                    }
                    case 0x72 -> { // BCLR #imm, @ERd
                        bus.write8(addr, val & ~(1 << bit));
                        trace("BCLR #%d, @ER%d", bit, r);
                    }
                    default -> unimplemented(hi << 8 | lo, pc - 2);
                }
            }
            case 0x7E -> {
                // Bit ops on @aa:8 (read)
                int addr = 0xFFFF00 | (lo & 0xFF);
                int val = bus.read8(addr);
                switch (subop) {
                    case 0x63, 0x73 -> {
                        setFlag(CCR_Z, (val & (1 << bit)) == 0);
                        trace("BTST #%d, @0x%06X", bit, addr);
                    }
                    case 0x77 -> {
                        if ((op2 & 0x80) == 0) {
                            setFlag(CCR_C, (val & (1 << bit)) != 0);
                            trace("BLD #%d, @0x%06X", bit, addr);
                        } else {
                            setFlag(CCR_C, (val & (1 << bit)) == 0);
                            trace("BILD #%d, @0x%06X", bit, addr);
                        }
                    }
                    default -> unimplemented(hi << 8 | lo, pc - 2);
                }
            }
            case 0x7F -> {
                // Bit ops on @aa:8 (read-modify-write)
                int addr = 0xFFFF00 | (lo & 0xFF);
                int val = bus.read8(addr);
                switch (subop) {
                    case 0x70 -> {
                        bus.write8(addr, val | (1 << bit));
                        trace("BSET #%d, @0x%06X", bit, addr);
                    }
                    case 0x72 -> {
                        bus.write8(addr, val & ~(1 << bit));
                        trace("BCLR #%d, @0x%06X", bit, addr);
                    }
                    default -> unimplemented(hi << 8 | lo, pc - 2);
                }
            }
        }
    }

    // --- Byte-immediate instructions ---
    private void movB_imm(int op) {
        int rd = (op >> 8) & 0xF;
        int imm = op & 0xFF;
        setRegB(rd, imm);
        setNZB(imm);
        setFlag(CCR_V, false);
        trace("MOV.B #0x%02X, R%dL", imm, rd);
    }

    private void addB_imm(int op) {
        int rd = (op >> 8) & 0xF;
        int imm = op & 0xFF;
        int d = getRegB(rd);
        int result = d + imm;
        setRegB(rd, result & 0xFF);
        setNZB(result);
        setFlag(CCR_C, result > 0xFF);
        setFlag(CCR_V, ((d ^ result) & (imm ^ result) & 0x80) != 0);
        setFlag(CCR_H, ((d ^ imm ^ result) & 0x10) != 0);
        trace("ADD.B #0x%02X, R%dL", imm, rd);
    }

    private void addxB_imm(int op) {
        int rd = (op >> 8) & 0xF;
        int imm = op & 0xFF;
        int d = getRegB(rd);
        int c = getFlag(CCR_C) ? 1 : 0;
        int result = d + imm + c;
        setRegB(rd, result & 0xFF);
        setFlag(CCR_C, result > 0xFF);
        setFlag(CCR_V, ((d ^ result) & (imm ^ result) & 0x80) != 0);
        setFlag(CCR_H, ((d ^ imm ^ result) & 0x10) != 0);
        if ((result & 0xFF) != 0) setFlag(CCR_Z, false);
        setFlag(CCR_N, (result & 0x80) != 0);
        trace("ADDX.B #0x%02X, R%dL", imm, rd);
    }

    private void cmpB_imm(int op) {
        int rd = (op >> 8) & 0xF;
        int imm = op & 0xFF;
        int d = getRegB(rd);
        int result = d - imm;
        setNZB(result);
        setFlag(CCR_C, (result & 0x100) != 0);
        setFlag(CCR_V, ((d ^ imm) & (d ^ result) & 0x80) != 0);
        setFlag(CCR_H, ((d ^ imm ^ result) & 0x10) != 0);
        trace("CMP.B #0x%02X, R%dL", imm, rd);
    }

    private void subxB_imm(int op) {
        int rd = (op >> 8) & 0xF;
        int imm = op & 0xFF;
        int d = getRegB(rd);
        int c = getFlag(CCR_C) ? 1 : 0;
        int result = d - imm - c;
        setRegB(rd, result & 0xFF);
        setFlag(CCR_C, result < 0);
        setFlag(CCR_V, ((d ^ imm) & (d ^ result) & 0x80) != 0);
        setFlag(CCR_H, ((d ^ imm ^ result) & 0x10) != 0);
        if ((result & 0xFF) != 0) setFlag(CCR_Z, false);
        setFlag(CCR_N, (result & 0x80) != 0);
        trace("SUBX.B #0x%02X, R%dL", imm, rd);
    }

    private void orB_imm(int op) {
        int rd = (op >> 8) & 0xF;
        int imm = op & 0xFF;
        int result = getRegB(rd) | imm;
        setRegB(rd, result);
        setNZB(result);
        setFlag(CCR_V, false);
        trace("OR.B #0x%02X, R%dL", imm, rd);
    }

    private void xorB_imm(int op) {
        int rd = (op >> 8) & 0xF;
        int imm = op & 0xFF;
        int result = getRegB(rd) ^ imm;
        setRegB(rd, result);
        setNZB(result);
        setFlag(CCR_V, false);
        trace("XOR.B #0x%02X, R%dL", imm, rd);
    }

    private void andB_imm(int op) {
        int rd = (op >> 8) & 0xF;
        int imm = op & 0xFF;
        int result = getRegB(rd) & imm;
        setRegB(rd, result);
        setNZB(result);
        setFlag(CCR_V, false);
        trace("AND.B #0x%02X, R%dL", imm, rd);
    }

    // --- MOV.B @aa:8 (short absolute) ---
    private void movB_absImm(int op) {
        // 0x2rdd: MOV.B @(aa:8), Rd  - address is 0xFFxx00 + dd
        int rd = (op >> 8) & 0xF;
        int addr = 0xFFFF00 | (op & 0xFF);
        int val = bus.read8(addr);
        setRegB(rd, val);
        setNZB(val);
        setFlag(CCR_V, false);
        trace("MOV.B @0x%06X, R%dL", addr, rd);
    }

    private void movB_immAbs(int op) {
        // 0x3rdd: MOV.B Rd, @(aa:8)
        int rd = (op >> 8) & 0xF;
        int addr = 0xFFFF00 | (op & 0xFF);
        bus.write8(addr, getRegB(rd));
        setNZB(getRegB(rd));
        setFlag(CCR_V, false);
        trace("MOV.B R%dL, @0x%06X", rd, addr);
    }

    // --- Register-to-register byte operations ---
    private void addB_reg(int lo) {
        int rs = (lo >> 4) & 0xF;
        int rd = lo & 0xF;
        int s = getRegB(rs);
        int d = getRegB(rd);
        int result = d + s;
        setRegB(rd, result & 0xFF);
        setNZB(result);
        setFlag(CCR_C, result > 0xFF);
        setFlag(CCR_V, ((d ^ result) & (s ^ result) & 0x80) != 0);
        setFlag(CCR_H, ((d ^ s ^ result) & 0x10) != 0);
        trace("ADD.B R%dL, R%dL", rs, rd);
    }

    private void subB_reg(int lo) {
        int rs = (lo >> 4) & 0xF;
        int rd = lo & 0xF;
        int s = getRegB(rs);
        int d = getRegB(rd);
        int result = d - s;
        setRegB(rd, result & 0xFF);
        setNZB(result);
        setFlag(CCR_C, (result & 0x100) != 0);
        setFlag(CCR_V, ((d ^ s) & (d ^ result) & 0x80) != 0);
        setFlag(CCR_H, ((d ^ s ^ result) & 0x10) != 0);
        trace("SUB.B R%dL, R%dL", rs, rd);
    }

    private void cmpB_reg(int lo) {
        int rs = (lo >> 4) & 0xF;
        int rd = lo & 0xF;
        int s = getRegB(rs);
        int d = getRegB(rd);
        int result = d - s;
        setNZB(result);
        setFlag(CCR_C, (result & 0x100) != 0);
        setFlag(CCR_V, ((d ^ s) & (d ^ result) & 0x80) != 0);
        setFlag(CCR_H, ((d ^ s ^ result) & 0x10) != 0);
        trace("CMP.B R%dL, R%dL", rs, rd);
    }

    private void movB_reg(int lo) {
        int rs = (lo >> 4) & 0xF;
        int rd = lo & 0xF;
        int val = getRegB(rs);
        setRegB(rd, val);
        setNZB(val);
        setFlag(CCR_V, false);
        trace("MOV.B R%dL, R%dL", rs, rd);
    }

    // --- Register-to-register word operations ---
    private void addW_reg(int lo) {
        int rs = (lo >> 4) & 0xF;
        int rd = lo & 0xF;
        int s = getR(rs);
        int d = getR(rd);
        int result = d + s;
        setR(rd, result & 0xFFFF);
        setNZW(result);
        setFlag(CCR_C, result > 0xFFFF);
        setFlag(CCR_V, ((d ^ result) & (s ^ result) & 0x8000) != 0);
        setFlag(CCR_H, ((d ^ s ^ result) & 0x1000) != 0);
        trace("ADD.W R%d, R%d", rs, rd);
    }

    private void subW_reg(int lo) {
        int rs = (lo >> 4) & 0xF;
        int rd = lo & 0xF;
        int s = getR(rs);
        int d = getR(rd);
        int result = d - s;
        setR(rd, result & 0xFFFF);
        setNZW(result);
        setFlag(CCR_C, (result & 0x10000) != 0);
        setFlag(CCR_V, ((d ^ s) & (d ^ result) & 0x8000) != 0);
        setFlag(CCR_H, ((d ^ s ^ result) & 0x1000) != 0);
        trace("SUB.W R%d, R%d", rs, rd);
    }

    private void cmpW_reg(int lo) {
        int rs = (lo >> 4) & 0xF;
        int rd = lo & 0xF;
        int s = getR(rs);
        int d = getR(rd);
        int result = d - s;
        setNZW(result);
        setFlag(CCR_C, (result & 0x10000) != 0);
        setFlag(CCR_V, ((d ^ s) & (d ^ result) & 0x8000) != 0);
        setFlag(CCR_H, ((d ^ s ^ result) & 0x1000) != 0);
        trace("CMP.W R%d, R%d", rs, rd);
    }

    private void movW_reg(int lo) {
        int rs = (lo >> 4) & 0xF;
        int rd = lo & 0xF;
        int val = getR(rs);
        setR(rd, val);
        setNZW(val);
        setFlag(CCR_V, false);
        trace("MOV.W R%d, R%d", rs, rd);
    }

    private void orW_reg(int lo) {
        int rs = (lo >> 4) & 0xF;
        int rd = lo & 0xF;
        int result = getR(rd) | getR(rs);
        setR(rd, result);
        setNZW(result);
        setFlag(CCR_V, false);
        trace("OR.W R%d, R%d", rs, rd);
    }

    private void xorW_reg(int lo) {
        int rs = (lo >> 4) & 0xF;
        int rd = lo & 0xF;
        int result = getR(rd) ^ getR(rs);
        setR(rd, result);
        setNZW(result);
        setFlag(CCR_V, false);
        trace("XOR.W R%d, R%d", rs, rd);
    }

    private void andW_reg(int lo) {
        int rs = (lo >> 4) & 0xF;
        int rd = lo & 0xF;
        int result = getR(rd) & getR(rs);
        setR(rd, result);
        setNZW(result);
        setFlag(CCR_V, false);
        trace("AND.W R%d, R%d", rs, rd);
    }

    // --- Longword register operations (0x0Axx, 0x0Bxx, 0x0Fxx, 0x1Axx, etc.) ---
    // These are handled in decode0/decode1 with 0x01 prefix for MOV.L

    // --- 0x0F: MOV.L ERs, ERd ---
    // Actually MOV.L reg-to-reg is in the 0x01 prefix group

    // --- EXTU, EXTS ---
    // These appear as 0x17_5d (EXTU.W), 0x17_7d (EXTU.L)
    // Already handled in SHAR.W case - need to add EXTU/EXTS properly

    // --- SLEEP ---
    // Encoded as 0x0180

    // --- Trace helper ---
    private void trace(String fmt, Object... args) {
        if (tracing) {
            System.err.printf(fmt + "%n", args);
        }
    }

    private int unimplCount = 0;
    private void unimplemented(int op, int addr) {
        unimplCount++;
        if (unimplCount <= 5) {
            System.err.printf("Unimplemented opcode 0x%04X at PC=0x%06X%n", op, addr);
            if (unimplCount == 1) {
                System.err.printf("  Registers:%n");
                for (int i = 0; i < 8; i++)
                    System.err.printf("    ER%d = 0x%08X%n", i, er[i]);
                System.err.printf("    PC=0x%06X CCR=0x%02X%n", pc, ccr);
            }
        }
    }

    /** Dump register state for debugging. */
    public void dumpRegisters() {
        System.out.println("=== CPU Registers ===");
        for (int i = 0; i < 8; i++) {
            System.out.printf("  ER%d = 0x%08X  (E%d=0x%04X R%d=0x%04X R%dH=0x%02X R%dL=0x%02X)%n",
                i, er[i], i, getE(i), i, getR(i), i, getRH(i), i, getRL(i));
        }
        System.out.printf("  PC  = 0x%06X%n", pc);
        System.out.printf("  CCR = 0x%02X [%s%s%s%s%s%s%s%s]%n", ccr,
            getFlag(CCR_I) ? "I" : "-", getFlag(CCR_UI) ? "U" : "-",
            getFlag(CCR_H) ? "H" : "-", getFlag(CCR_U) ? "u" : "-",
            getFlag(CCR_N) ? "N" : "-", getFlag(CCR_Z) ? "Z" : "-",
            getFlag(CCR_V) ? "V" : "-", getFlag(CCR_C) ? "C" : "-");
        System.out.printf("  EXR = 0x%02X%n", exr);
        System.out.printf("  Cycles: %d%n", cycleCount);
    }
}
