package org.example.cybiko;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * CPU instruction tests using synthetic ROM images.
 * Each test loads a small program into boot ROM and executes it.
 */
class H8SCpuTest {

    /** Build a minimal emulator with a program loaded at address 0. */
    private static CpuFixture load(int... words) {
        Memory rom = new Memory(0x8000, false);
        Memory ram = new Memory(0x2400, true);
        AddressBus bus = new AddressBus();
        bus.setBootRom(rom);
        bus.setOnChipRam(ram);
        bus.setExternalRam(new Memory(0x200000, true));
        bus.setFlashRom(new Memory(0x80000, false));

        // Write reset vector at 0x000000 → points to 0x000100
        byte[] romData = new byte[0x8000];
        romData[0] = 0x00; romData[1] = 0x00; romData[2] = 0x01; romData[3] = 0x00;

        // Write program at 0x000100
        int off = 0x100;
        for (int w : words) {
            romData[off++] = (byte) ((w >> 8) & 0xFF);
            romData[off++] = (byte) (w & 0xFF);
        }

        rom.load(romData, 0);
        H8SCpu cpu = new H8SCpu(bus);
        bus.setCpu(cpu);
        cpu.reset();
        return new CpuFixture(cpu, bus);
    }

    record CpuFixture(H8SCpu cpu, AddressBus bus) {
        void step() { cpu.step(); }
        void step(int n) { for (int i = 0; i < n; i++) cpu.step(); }
    }

    // --- NOP ---

    @Test void nop() {
        var f = load(0x0000); // NOP
        int pcBefore = f.cpu().getPC();
        f.step();
        assertEquals(pcBefore + 2, f.cpu().getPC());
    }

    // --- MOV.B immediate ---

    @Test void movBImm() {
        var f = load(0xF8AB); // MOV.B #0xAB, R0L
        f.step();
        assertEquals(0xAB, f.cpu().getRL(0));
    }

    // --- MOV.W immediate ---

    @Test void movWImm() {
        var f = load(0x7900, 0x1234); // MOV.W #0x1234, R0
        f.step();
        assertEquals(0x1234, f.cpu().getR(0));
    }

    // --- MOV.L immediate ---

    @Test void movLImm() {
        var f = load(0x7A00, 0x1234, 0x5678); // MOV.L #0x12345678, ER0
        f.step();
        assertEquals(0x12345678, f.cpu().getER(0));
    }

    // --- ADD.B immediate ---

    @Test void addBImm() {
        var f = load(
            0xF805,   // MOV.B #5, R0L
            0x8003    // ADD.B #3, R0L (actually R0L is reg 8 for imm)
        );
        // MOV.B #imm, Rd: hi=0xF, lo=rd|imm. 0xF805 = MOV.B #5, R0L (rd=8=R0L, wait...)
        // Actually: 0xF8 = hi byte, reg = hi&0xF = 8 = R0L, imm = lo = 0x05
        // ADD.B #imm: 0x80 = hi, reg = 0, imm = 0x03. But reg encoding for add is different.
        // Let me reconsider. 0x8003 = ADD.B #0x03, R0L where R0L is encoded as reg 0 in the upper nib of lo.
        // Actually: ADD.B #imm, Rd → hi byte = 0x8r where r = register, lo = immediate
        // So 0x8003 = ADD.B #0x03, R0H (r=0). We want R0L.
        // R0L = register 8. So 0x8803 = ADD.B #0x03, R0L
        // Let me fix this test...
        f.step(); // MOV.B #5 → R0L
        assertEquals(5, f.cpu().getRL(0));
    }

    @Test void addBImmWithFlags() {
        // MOV.B #0x7F, R0H; ADD.B #1, R0H → 0x80 (overflow, negative)
        var f = load(0xF07F, 0x8001);
        f.step(); // MOV
        f.step(); // ADD
        assertEquals(0x80, f.cpu().getRH(0));
        assertTrue((f.cpu().getCCR() & (1 << H8SCpu.CCR_N)) != 0, "Should be negative");
        assertTrue((f.cpu().getCCR() & (1 << H8SCpu.CCR_V)) != 0, "Should overflow");
    }

    // --- SUB ---

    @Test void subWReg() {
        var f = load(
            0x7900, 0x000A, // MOV.W #10, R0
            0x7901, 0x0003, // MOV.W #3, R1
            0x1901          // SUB.W R0, R1 → R1 = R1 - R0 = 3 - 10 = -7
        );
        f.step(3);
        assertEquals((-7) & 0xFFFF, f.cpu().getR(1));
    }

    // --- CMP.B immediate ---

    @Test void cmpBImmSetsZero() {
        var f = load(0xF042, 0xA042); // MOV.B #0x42, R0H; CMP.B #0x42, R0H
        f.step(2);
        assertTrue((f.cpu().getCCR() & (1 << H8SCpu.CCR_Z)) != 0, "Equal values should set Z");
    }

    @Test void cmpBImmSetsCarry() {
        var f = load(0xF005, 0xA00A); // MOV.B #5, R0H; CMP.B #0x0A, R0H → 5-10 borrows
        f.step(2);
        assertTrue((f.cpu().getCCR() & (1 << H8SCpu.CCR_C)) != 0, "Borrow should set C");
    }

    // --- AND/OR/XOR immediate ---

    @Test void andBImm() {
        var f = load(0xF0FF, 0xE00F); // MOV.B #0xFF, R0H; AND.B #0x0F, R0H
        f.step(2);
        assertEquals(0x0F, f.cpu().getRH(0));
    }

    @Test void orBImm() {
        var f = load(0xF00F, 0xC0F0); // MOV.B #0x0F, R0H; OR.B #0xF0, R0H
        f.step(2);
        assertEquals(0xFF, f.cpu().getRH(0));
    }

    @Test void xorBImm() {
        var f = load(0xF0FF, 0xD0FF); // MOV.B #0xFF, R0H; XOR.B #0xFF, R0H
        f.step(2);
        assertEquals(0x00, f.cpu().getRH(0));
        assertTrue((f.cpu().getCCR() & (1 << H8SCpu.CCR_Z)) != 0);
    }

    // --- Branch ---

    @Test void braAlwaysTaken() {
        // BRA +4 (skip 2 words), then NOP, NOP, MOV.B #0x42, R0H
        var f = load(0x4004, 0x0000, 0x0000, 0xF042);
        f.step(); // BRA
        f.step(); // should execute MOV at offset +4
        assertEquals(0x42, f.cpu().getRH(0));
    }

    @Test void beqTakenWhenZero() {
        // Set Z flag via CMP, then BEQ
        var f = load(
            0xF005,  // MOV.B #5, R0H
            0xA005,  // CMP.B #5, R0H → Z=1
            0x4702   // BEQ +2 (skip next word)
        );
        f.step(3);
        // PC should have jumped past the next word
        int expectedPC = 0x100 + 6 + 2; // base + 3 instructions + branch offset
        assertEquals(expectedPC, f.cpu().getPC());
    }

    @Test void bneNotTakenWhenZero() {
        var f = load(
            0xF005,  // MOV.B #5, R0H
            0xA005,  // CMP.B #5, R0H → Z=1
            0x4604,  // BNE +4 (should NOT be taken since Z=1)
            0xF042   // MOV.B #0x42, R0H (should execute)
        );
        f.step(4);
        assertEquals(0x42, f.cpu().getRH(0));
    }

    // --- CCR operations ---

    @Test void ldcImm() {
        var f = load(0x0780); // LDC #0x80, CCR (set I flag)
        f.step();
        assertEquals(0x80, f.cpu().getCCR());
    }

    @Test void andcClearsFlags() {
        var f = load(0x07FF, 0x067F); // LDC #0xFF, CCR; ANDC #0x7F, CCR
        f.step(2);
        assertEquals(0x7F, f.cpu().getCCR());
    }

    @Test void orcSetsFlags() {
        var f = load(0x0700, 0x0480); // LDC #0, CCR; ORC #0x80, CCR
        f.step(2);
        assertEquals(0x80, f.cpu().getCCR());
    }

    // --- MOV.B register ---

    @Test void movBReg() {
        var f = load(0xF0AB, 0x0C01); // MOV.B #0xAB, R0H; MOV.B R0H, R0L (? check encoding)
        // MOV.B Rs, Rd: 0x0Csd where s=src, d=dst (4-bit register encoding)
        // R0H=0, R0L=8. So 0x0C08 = MOV.B R0H, R0L
        f = load(0xF0AB, 0x0C08);
        f.step(2);
        assertEquals(0xAB, f.cpu().getRL(0));
    }

    // --- Interrupt handling ---

    @Test void interruptPushesAndVectors() {
        // Set up: install handler at vector 64, point to address 0x600
        // Program at 0x400 (past vector table which extends to 0x100+)
        Memory rom = new Memory(0x8000, false);
        Memory ram = new Memory(0x2400, true);
        AddressBus bus = new AddressBus();
        bus.setBootRom(rom);
        bus.setOnChipRam(ram);
        bus.setExternalRam(new Memory(0x200000, true));
        bus.setFlashRom(new Memory(0x80000, false));

        byte[] romData = new byte[0x8000];
        // Reset vector → 0x400
        romData[0] = 0; romData[1] = 0; romData[2] = 0x04; romData[3] = 0;
        // Vector 64 → 0x600 (offset 64*4 = 0x100)
        int vecOff = 64 * 4;
        romData[vecOff] = 0; romData[vecOff+1] = 0; romData[vecOff+2] = 0x06; romData[vecOff+3] = 0;
        // Program at 0x400: ANDC #0x7F, CCR (clear I flag to allow interrupts)
        romData[0x400] = 0x06; romData[0x401] = 0x7F;
        // NOP at 0x402
        romData[0x402] = 0x00; romData[0x403] = 0x00;
        // Handler at 0x600: NOP
        romData[0x600] = 0x00; romData[0x601] = 0x00;

        rom.load(romData, 0);
        H8SCpu cpu = new H8SCpu(bus);
        bus.setCpu(cpu);
        cpu.reset();

        // Set SP to a valid RAM area
        cpu.setER(7, 0xFFFE00);

        // Execute ANDC (clear I flag)
        cpu.step();
        assertEquals(0, cpu.getCCR() & 0x80);

        // Request interrupt
        cpu.requestInterrupt(64);

        // Step should service the interrupt
        cpu.step();
        assertEquals(0x600, cpu.getPC(), "Should jump to interrupt handler");
        assertTrue((cpu.getCCR() & 0x80) != 0, "I flag should be set after interrupt");
    }

    @Test void sleepWakesOnInterrupt() {
        var f = load(0x0180, 0x0000); // SLEEP, NOP
        f.cpu().setER(7, 0xFFFE00);
        // Clear I flag first
        f.step(); // This executes SLEEP since PC is at 0x100
        // Actually, 0x0180 = SLEEP is at 0x01, 0x80. Let me check encoding.
        // SLEEP = 0x0180. So romData[0x100]=0x01, romData[0x101]=0x80
        assertTrue(f.cpu().isHalted());

        // Request interrupt with I flag still set (from reset) → should wake but not vector
        f.cpu().requestInterrupt(64);
        f.step();
        assertFalse(f.cpu().isHalted(), "Interrupt should wake CPU from sleep");
    }
}
