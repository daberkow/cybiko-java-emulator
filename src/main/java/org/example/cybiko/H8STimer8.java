package org.example.cybiko;

/**
 * Simple H8S 8-bit timer emulation.
 *
 * The H8S/2323 has two 8-bit timer channels (TMR0, TMR1).
 * Each has:
 *   - TCR  (Timer Control Register) - clock source, clear condition
 *   - TCSR (Timer Control/Status Register) - match/overflow flags
 *   - TCORA (Timer Constant Register A) - compare match A value
 *   - TCORB (Timer Constant Register B) - compare match B value
 *   - TCNT (Timer Counter) - 8-bit counter
 *
 * Register offsets (from base):
 *   +0: TCR, +1: (unused), +2: TCSR, +3: (unused),
 *   +4: TCORA, +5: (unused), +6: TCORB, +7: (unused), +8: TCNT
 *
 * Interrupt vectors:
 *   Channel 0: CMIA=64, CMIB=65, OVI=66
 *   Channel 1: CMIA=68, CMIB=69, OVI=70
 */
public class H8STimer8 {
    private final int channel;
    private final int vectorCMIA;
    private final int vectorCMIB;
    private final int vectorOVI;

    // Registers
    private int tcr = 0;     // Timer Control Register
    private int tcsr = 0;    // Timer Control/Status Register
    private int tcora = 0xFF; // Compare match A
    private int tcorb = 0xFF; // Compare match B
    private int tcnt = 0;    // Timer Counter

    // Internal state
    private int prescaleCounter = 0;
    private final H8SCpu cpu;
    private int interruptCount = 0; // Debug counter
    private int cmiaCount = 0;
    private int cmibCount = 0;
    private int oviCount = 0;
    private long totalTicks = 0;
    private long lastIrqTick = 0;
    private long absoluteTicks = 0; // increments on every tick() call
    private int tcsrClearLog = 0; // debug: limit OVF clear logs
    private int ovfSetLog = 0;    // debug: limit OVF set logs

    // TCR bits (from MAME h8_timer8.h)
    private static final int TCR_CMIEB = 0x80; // Bit 7: Compare Match Interrupt Enable B
    private static final int TCR_CMIEA = 0x40; // Bit 6: Compare Match Interrupt Enable A
    private static final int TCR_OVIE  = 0x20; // Bit 5: Overflow Interrupt Enable
    // Bits 4-3: CCLR (clear mode): 0=none, 1=TCORA, 2=TCORB, 3=external
    // Bits 2-0: CKS (clock select): 0=stop, 1-3=internal divider, 4-7=external/chain

    // TCSR bits (from MAME h8_timer8.h)
    private static final int TCSR_CMFB = 0x80; // Bit 7: Compare Match Flag B
    private static final int TCSR_CMFA = 0x40; // Bit 6: Compare Match Flag A
    private static final int TCSR_OVF  = 0x20; // Bit 5: Overflow Flag

    public H8STimer8(int channel, H8SCpu cpu) {
        this.channel = channel;
        this.cpu = cpu;
        this.vectorCMIA = (channel == 0) ? 64 : 68;
        this.vectorCMIB = (channel == 0) ? 65 : 69;
        this.vectorOVI  = (channel == 0) ? 66 : 70;
    }

    /** Read register by offset (0-8). */
    public int read(int offset) {
        return switch (offset) {
            case 0 -> tcr;
            case 2 -> tcsr;
            case 4 -> tcora;
            case 6 -> tcorb;
            case 8 -> tcnt;
            default -> 0;
        };
    }

    /** Write register by offset (0-8). */
    public void write(int offset, int value) {
        switch (offset) {
            case 0 -> {
                if (channel == 0 && interruptCount < 10) {
                    System.err.printf("[TMR%d] TCR write: 0x%02X (was 0x%02X)%n", channel, value & 0xFF, tcr);
                }
                tcr = value & 0xFF;
            }
            case 2 -> {
                int oldTcsr = tcsr;
                // Flags (bits 7-5) only clear when written as 0; lower bits writable directly
                tcsr = (tcsr & ~0x1F) | (value & 0x1F); // write lower 5 bits normally
                tcsr &= value | 0x1F; // clear flag bits only where value has 0
                // Debug: log when ch1's OVF flag is cleared
                if (channel == 1 && (oldTcsr & TCSR_OVF) != 0 && (tcsr & TCSR_OVF) == 0 && tcsrClearLog < 20) {
                    int spc = cpu.getLastStartPC();
                    System.err.printf("[TMR1] OVF CLEARED! val=0x%02X old=0x%02X new=0x%02X startPC=0x%06X nextPC=0x%06X%n",
                        value & 0xFF, oldTcsr, tcsr, spc, cpu.getPC());
                    tcsrClearLog++;
                }
            }
            case 4 -> {
                if (channel == 0 && interruptCount < 10) {
                    System.err.printf("[TMR%d] TCORA write: 0x%02X (was 0x%02X)%n", channel, value & 0xFF, tcora);
                }
                tcora = value & 0xFF;
            }
            case 6 -> tcorb = value & 0xFF;
            case 8 -> {
                if (channel == 0 && interruptCount < 10) {
                    System.err.printf("[TMR%d] TCNT write: 0x%02X (was 0x%02X)%n", channel, value & 0xFF, tcnt);
                }
                tcnt = value & 0xFF;
            }
        }
    }

    /**
     * Tick the timer. Call this every N CPU cycles based on the prescaler.
     * For simplicity, we tick once per call and let the caller handle prescaling.
     */
    public void tick() {
        absoluteTicks++;
        // Get prescaler from TCR bits 0-2
        int clockSelect = tcr & 0x07;
        if (clockSelect == 0) return; // timer stopped

        // Prescale divisors: 0=stop, 1=/8, 2=/64, 3=/8192, 4-7=external/special
        int divisor = switch (clockSelect) {
            case 1 -> 8;
            case 2 -> 64;
            case 3 -> 8192;
            default -> 256; // approximate for external clock sources
        };

        totalTicks++;
        prescaleCounter++;
        if (prescaleCounter < divisor) return;
        prescaleCounter = 0;

        // Increment counter
        tcnt = (tcnt + 1) & 0xFF;

        // Check compare match A (MAME compares against tcor+1, but since we
        // increment first, comparing against tcora is equivalent)
        if (tcnt == tcora) {
            // Only fire interrupt on flag transition 0→1 (per MAME)
            if ((tcsr & TCSR_CMFA) == 0) {
                tcsr |= TCSR_CMFA;
                if ((tcr & TCR_CMIEA) != 0) {
                    cpu.requestInterrupt(vectorCMIA);
                    interruptCount++;
                    cmiaCount++;
                }
            }
            // Clear on compare match A if configured (TCR bits 4-3)
            int clearMode = (tcr >> 3) & 0x03;
            if (clearMode == 1) tcnt = 0;
        }

        // Check compare match B
        if (tcnt == tcorb) {
            if ((tcsr & TCSR_CMFB) == 0) {
                tcsr |= TCSR_CMFB;
                if ((tcr & TCR_CMIEB) != 0) {
                    cpu.requestInterrupt(vectorCMIB);
                    interruptCount++;
                    cmibCount++;
                }
            }
            int clearMode = (tcr >> 3) & 0x03;
            if (clearMode == 2) tcnt = 0;
        }

        // Check overflow (only on natural wrap 0xFF→0x00)
        if (tcnt == 0) {
            if (channel == 1 && ovfSetLog < 30) {
                boolean wasSet = (tcsr & TCSR_OVF) != 0;
                System.err.printf("[TMR1] tcnt=0 OVF_was=%s tcsr=0x%02X absTicks=%d%n",
                    wasSet ? "SET" : "CLR", tcsr, absoluteTicks);
                ovfSetLog++;
            }
            if ((tcsr & TCSR_OVF) == 0) {
                tcsr |= TCSR_OVF;
                if ((tcr & TCR_OVIE) != 0) {
                    cpu.requestInterrupt(vectorOVI);
                    interruptCount++;
                    oviCount++;
                    if (oviCount <= 5) {
                        System.err.printf("[TMR%d] OVI #%d: totalTicks=%d ticksSinceLast=%d tcr=0x%02X divisor=%d%n",
                            channel, interruptCount, totalTicks, totalTicks - lastIrqTick, tcr, divisor);
                    }
                    lastIrqTick = totalTicks;
                }
            }
        }
    }

    public int getInterruptCount() { return interruptCount; }
    public String getIrqBreakdown() { return "cmia=" + cmiaCount + " cmib=" + cmibCount + " ovi=" + oviCount + " absTicks=" + absoluteTicks + " runTicks=" + totalTicks; }
    public int getTcr() { return tcr; }
    public int getTcnt() { return tcnt; }
    public int getTcora() { return tcora; }
    public int getTcorb() { return tcorb; }
}
