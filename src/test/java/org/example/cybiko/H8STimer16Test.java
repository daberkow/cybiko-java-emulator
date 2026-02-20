package org.example.cybiko;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class H8STimer16Test {

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

    @Test void disabledTimerDoesNotCount() {
        CpuStub cpu = new CpuStub();
        H8STimer16 timer = new H8STimer16(0, 4, 32, cpu);
        // Not enabled via TSTR
        for (int i = 0; i < 1000; i++) timer.tick();
        assertEquals(0, timer.read16(6)); // TCNT
    }

    @Test void enabledTimerCountsAtDiv1() {
        CpuStub cpu = new CpuStub();
        H8STimer16 timer = new H8STimer16(0, 4, 32, cpu);
        timer.write8(0, 0x00); // TPSC=0 → /1
        timer.setEnabled(true);
        assertTrue(timer.isRunning());

        for (int i = 0; i < 10; i++) timer.tick();
        assertEquals(10, timer.read16(6));
    }

    @Test void enabledTimerCountsAtDiv4() {
        CpuStub cpu = new CpuStub();
        H8STimer16 timer = new H8STimer16(0, 4, 32, cpu);
        timer.write8(0, 0x01); // TPSC=1 → /4
        timer.setEnabled(true);

        for (int i = 0; i < 8; i++) timer.tick();
        assertEquals(2, timer.read16(6));
    }

    @Test void compareMatchAInterrupt() {
        CpuStub cpu = new CpuStub();
        H8STimer16 timer = new H8STimer16(0, 4, 32, cpu);
        timer.write16(8, 5);    // TGRA = 5
        timer.write8(4, 0x01);  // TIER: TGIEA = 1
        timer.write8(0, 0x00);  // TPSC=0 → /1
        timer.setEnabled(true);

        for (int i = 0; i < 5; i++) timer.tick();
        assertEquals(5, timer.read16(6));
        assertEquals(32, cpu.lastVector); // TGIA = base vector 32
        assertEquals(1, cpu.irqCount);
    }

    @Test void compareMatchBInterrupt() {
        CpuStub cpu = new CpuStub();
        H8STimer16 timer = new H8STimer16(0, 4, 32, cpu);
        timer.write16(0xA, 3);  // TGRB = 3
        timer.write8(4, 0x02);  // TIER: TGIEB = 1
        timer.write8(0, 0x00);  // /1
        timer.setEnabled(true);

        for (int i = 0; i < 3; i++) timer.tick();
        assertEquals(33, cpu.lastVector); // TGIB = base + 1
    }

    @Test void compareMatchClearsOnTGRA() {
        CpuStub cpu = new CpuStub();
        H8STimer16 timer = new H8STimer16(0, 4, 32, cpu);
        timer.write16(8, 3);    // TGRA = 3
        timer.write8(0, 0x20);  // CCLR=01 (clear on TGRA), TPSC=0
        timer.setEnabled(true);

        for (int i = 0; i < 3; i++) timer.tick();
        assertEquals(0, timer.read16(6), "TCNT should clear on compare match A");
    }

    @Test void overflowAt0xFFFF() {
        CpuStub cpu = new CpuStub();
        H8STimer16 timer = new H8STimer16(0, 4, 32, cpu);
        timer.write8(4, 0x10);    // TIER: OVIE = 1
        timer.write8(0, 0x00);    // /1
        timer.write16(6, 0xFFFE); // TCNT near overflow
        timer.setEnabled(true);

        timer.tick(); // 0xFFFE → 0xFFFF
        assertEquals(0, cpu.irqCount);
        timer.tick(); // 0xFFFF → 0x0000 (overflow)
        assertEquals(36, cpu.lastVector); // OVF = base + tgrCount(4) = 36
    }

    @Test void channel1Vectors() {
        CpuStub cpu = new CpuStub();
        H8STimer16 timer = new H8STimer16(1, 2, 40, cpu);
        timer.write16(8, 1);    // TGRA = 1
        timer.write8(4, 0x01);  // TGIEA
        timer.write8(0, 0x00);  // /1
        timer.setEnabled(true);

        timer.tick();
        assertEquals(40, cpu.lastVector); // Channel 1 base = 40
    }

    @Test void isRunningRequiresEnableAndValidClock() {
        CpuStub cpu = new CpuStub();
        H8STimer16 timer = new H8STimer16(0, 4, 32, cpu);

        assertFalse(timer.isRunning(), "Not enabled");

        timer.setEnabled(true);
        // TPSC=0 → /1, valid
        assertTrue(timer.isRunning());

        timer.write8(0, 0x04); // TPSC=4 → extA (divisor=0 for ch0)
        assertFalse(timer.isRunning(), "External clock should not be running");
    }

    @Test void perChannelClockDivisors() {
        CpuStub cpu = new CpuStub();

        // Channel 1 TPSC=6 → /256
        H8STimer16 ch1 = new H8STimer16(1, 2, 40, cpu);
        ch1.write8(0, 0x06);
        ch1.setEnabled(true);
        assertTrue(ch1.isRunning());
        for (int i = 0; i < 256; i++) ch1.tick();
        assertEquals(1, ch1.read16(6));

        // Channel 3 TPSC=7 → /4096
        H8STimer16 ch3 = new H8STimer16(3, 4, 48, cpu);
        ch3.write8(0, 0x07);
        ch3.setEnabled(true);
        assertTrue(ch3.isRunning());
        for (int i = 0; i < 4096; i++) ch3.tick();
        assertEquals(1, ch3.read16(6));
    }
}
