package org.example.cybiko;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class H8STimer8Test {

    /** Minimal CPU stub that records interrupt requests. */
    private static class CpuStub extends H8SCpu {
        int lastVector = -1;
        int irqCount = 0;

        CpuStub() { super(new AddressBus()); }

        @Override
        public void requestInterrupt(int vector) {
            lastVector = vector;
            irqCount++;
        }
    }

    @Test void stoppedTimerDoesNotCount() {
        CpuStub cpu = new CpuStub();
        H8STimer8 timer = new H8STimer8(0, cpu);
        // TCR default is 0 → clock stopped
        for (int i = 0; i < 1000; i++) timer.tick();
        assertEquals(0, timer.getTcnt());
        assertEquals(0, cpu.irqCount);
    }

    @Test void timerCountsWithPrescaler() {
        CpuStub cpu = new CpuStub();
        H8STimer8 timer = new H8STimer8(0, cpu);
        // Set clock source CKS=1 (divide by 8), no interrupts
        timer.write(0, 0x01);
        assertTrue(timer.isRunning());

        // Tick 8 times → TCNT should increment once
        for (int i = 0; i < 8; i++) timer.tick();
        assertEquals(1, timer.getTcnt());

        // Tick 8 more → TCNT = 2
        for (int i = 0; i < 8; i++) timer.tick();
        assertEquals(2, timer.getTcnt());
    }

    @Test void compareMatchAInterrupt() {
        CpuStub cpu = new CpuStub();
        H8STimer8 timer = new H8STimer8(0, cpu);
        // TCORA = 2, enable CMIA interrupt, CKS=1 (div 8)
        timer.write(4, 0x02);  // TCORA = 2
        timer.write(0, 0x41);  // CMIEA=1, CKS=1

        // Tick to TCNT=2 (match) → 2 * 8 = 16 ticks
        for (int i = 0; i < 16; i++) timer.tick();
        assertEquals(2, timer.getTcnt());
        assertEquals(64, cpu.lastVector); // Channel 0 CMIA = vector 64
        assertEquals(1, cpu.irqCount);
    }

    @Test void compareMatchClearsCounter() {
        CpuStub cpu = new CpuStub();
        H8STimer8 timer = new H8STimer8(0, cpu);
        // TCORA=3, clear on match A (CCLR=01), CKS=1
        timer.write(4, 0x03);  // TCORA = 3
        timer.write(0, 0x09);  // CCLR=01, CKS=1

        // Tick to TCNT=3 (match) → should clear to 0
        for (int i = 0; i < 3 * 8; i++) timer.tick();
        assertEquals(0, timer.getTcnt(), "Counter should clear on compare match A");
    }

    @Test void overflowInterrupt() {
        CpuStub cpu = new CpuStub();
        H8STimer8 timer = new H8STimer8(0, cpu);
        // Enable overflow interrupt, CKS=1 (div 8), start near overflow
        timer.write(0, 0x21); // OVIE=1, CKS=1
        timer.write(8, 0xFE); // TCNT = 254

        // Tick twice: 254 → 255 → 0 (overflow)
        for (int i = 0; i < 2 * 8; i++) timer.tick();
        assertEquals(0, timer.getTcnt());
        assertEquals(66, cpu.lastVector); // Channel 0 OVI = vector 66
    }

    @Test void channel1VectorOffsets() {
        CpuStub cpu = new CpuStub();
        H8STimer8 timer = new H8STimer8(1, cpu);
        // Enable CMIA, TCORA=1, CKS=1
        timer.write(4, 0x01);
        timer.write(0, 0x41);

        for (int i = 0; i < 8; i++) timer.tick();
        assertEquals(68, cpu.lastVector, "Channel 1 CMIA should be vector 68");
    }

    @Test void flagOnlyFiresOnTransition() {
        CpuStub cpu = new CpuStub();
        H8STimer8 timer = new H8STimer8(0, cpu);
        // TCORA=1, CMIA enabled, CKS=1, no clear
        timer.write(4, 0x01);
        timer.write(0, 0x41);

        // First match at TCNT=1
        for (int i = 0; i < 8; i++) timer.tick();
        assertEquals(1, cpu.irqCount);

        // TCNT wraps around to 1 again (256 ticks) without clearing CMFA
        // Should NOT fire again because flag is already set
        for (int i = 0; i < 256 * 8; i++) timer.tick();
        assertEquals(1, cpu.irqCount, "Should not re-fire while flag is still set");
    }

    @Test void isRunningReflectsClockSource() {
        CpuStub cpu = new CpuStub();
        H8STimer8 timer = new H8STimer8(0, cpu);
        assertFalse(timer.isRunning());

        timer.write(0, 0x01); // CKS=1
        assertTrue(timer.isRunning());

        timer.write(0, 0x00); // CKS=0 (stopped)
        assertFalse(timer.isRunning());
    }
}
