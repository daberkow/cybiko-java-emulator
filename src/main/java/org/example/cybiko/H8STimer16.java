package org.example.cybiko;

/**
 * Simple H8S 16-bit timer channel emulation (TPU - Timer Pulse Unit).
 *
 * Each channel has:
 *   - TCR  (Timer Control Register)
 *   - TMDR (Timer Mode Register)
 *   - TIOR (Timer I/O Control Register) - 2 bytes for channels with 4 TGRs
 *   - TIER (Timer Interrupt Enable Register)
 *   - TSR  (Timer Status Register)
 *   - TCNT (Timer Counter, 16-bit)
 *   - TGR  (Timer General Registers, 2-4 per channel)
 *
 * Clock source mapping is per-channel (from MAME h8s2319.cpp):
 *   Ch0: /1, /4, /16, /64, extA, extB, extC, extD
 *   Ch1: /1, /4, /16, /64, extA, extB, /256, chain
 *   Ch2: /1, /4, /16, /64, extA, extB, extC, /1024
 *   Ch3: /1, /4, /16, /64, extA, /1024, /256, /4096
 *   Ch4: /1, /4, /16, /64, extA, extC, /1024, chain
 *   Ch5: /1, /4, /16, /64, extA, extC, /256, extD
 */
public class H8STimer16 {
    private final int channel;
    private final int tgrCount;     // 2 or 4 TGRs depending on channel
    private final int vecTGIA;      // Interrupt vector for compare match A
    private final int vecTGIB;      // Interrupt vector for compare match B
    private final int vecOVF;       // Interrupt vector for overflow

    // Registers
    private int tcr = 0;
    private int tmdr = 0;
    private int tior = 0;
    private int tier = 0;
    private int tsr = 0;
    private int tcnt = 0;      // 16-bit counter
    private int tgra = 0xFFFF; // compare match A
    private int tgrb = 0xFFFF; // compare match B

    // Internal
    private int prescaleCounter = 0;
    private boolean enabled = false; // Controlled by TSTR register
    private int cachedDivisor = 0;   // 0 = stopped/external
    private final H8SCpu cpu;
    private final int[] clockDivisors; // per-channel clock source table
    private int interruptCount = 0; // Debug counter

    // TIER bits
    private static final int TIER_TGIEA = 0x01; // TGR Interrupt Enable A
    private static final int TIER_TGIEB = 0x02; // TGR Interrupt Enable B
    private static final int TIER_OVIE  = 0x10; // Overflow Interrupt Enable

    // TSR bits
    private static final int TSR_TGFA = 0x01; // TGR Flag A
    private static final int TSR_TGFB = 0x02; // TGR Flag B
    private static final int TSR_OVF  = 0x10; // Overflow Flag

    // Per-channel clock source tables (from MAME h8s2319.cpp)
    // Index = TPSC value (TCR bits 0-2). 0 = external/chained (not ticked internally).
    private static final int[][] CHANNEL_CLOCK_DIVISORS = {
        // Ch0: /1, /4, /16, /64, extA, extB, extC, extD
        {1, 4, 16, 64, 0, 0, 0, 0},
        // Ch1: /1, /4, /16, /64, extA, extB, /256, chain
        {1, 4, 16, 64, 0, 0, 256, 0},
        // Ch2: /1, /4, /16, /64, extA, extB, extC, /1024
        {1, 4, 16, 64, 0, 0, 0, 1024},
        // Ch3: /1, /4, /16, /64, extA, /1024, /256, /4096
        {1, 4, 16, 64, 0, 1024, 256, 4096},
        // Ch4: /1, /4, /16, /64, extA, extC, /1024, chain
        {1, 4, 16, 64, 0, 0, 1024, 0},
        // Ch5: /1, /4, /16, /64, extA, extC, /256, extD
        {1, 4, 16, 64, 0, 0, 256, 0},
    };

    /**
     * @param channel   Channel number (0-5)
     * @param tgrCount  Number of TGRs (2 or 4) - affects interrupt vector layout
     * @param baseVector Base interrupt vector number
     * @param cpu       CPU for interrupt requests
     */
    public H8STimer16(int channel, int tgrCount, int baseVector, H8SCpu cpu) {
        this.channel = channel;
        this.tgrCount = tgrCount;
        this.cpu = cpu;
        // Interrupt vectors: TGIA=base, TGIB=base+1, [TGIC=base+2, TGID=base+3], OVF=base+tgrCount
        this.vecTGIA = baseVector;
        this.vecTGIB = baseVector + 1;
        this.vecOVF  = baseVector + tgrCount; // OVF follows after all TGR vectors
        this.clockDivisors = CHANNEL_CLOCK_DIVISORS[channel];
    }

    /** Read 8-bit register by offset. */
    public int read8(int offset) {
        return switch (offset) {
            case 0 -> tcr;
            case 1 -> tmdr;
            case 2 -> tior;
            case 4 -> tier;
            case 5 -> tsr;
            case 6 -> (tcnt >> 8) & 0xFF;
            case 7 -> tcnt & 0xFF;
            case 8 -> (tgra >> 8) & 0xFF;
            case 9 -> tgra & 0xFF;
            case 0xA -> (tgrb >> 8) & 0xFF;
            case 0xB -> tgrb & 0xFF;
            default -> 0;
        };
    }

    /** Write 8-bit register by offset. */
    public void write8(int offset, int value) {
        switch (offset) {
            case 0 -> { tcr = value & 0xFF; updateCachedDivisor(); }
            case 1 -> tmdr = value & 0xFF;
            case 2 -> tior = value & 0xFF;
            case 4 -> tier = value & 0xFF;
            case 5 -> tsr = tsr & (value | ~(TSR_TGFA | TSR_TGFB | TSR_OVF));
            case 6 -> tcnt = ((value & 0xFF) << 8) | (tcnt & 0xFF);
            case 7 -> tcnt = (tcnt & 0xFF00) | (value & 0xFF);
            case 8 -> tgra = ((value & 0xFF) << 8) | (tgra & 0xFF);
            case 9 -> tgra = (tgra & 0xFF00) | (value & 0xFF);
            case 0xA -> tgrb = ((value & 0xFF) << 8) | (tgrb & 0xFF);
            case 0xB -> tgrb = (tgrb & 0xFF00) | (value & 0xFF);
        }
    }

    /** Read 16-bit register by offset. */
    public int read16(int offset) {
        return switch (offset) {
            case 6 -> tcnt;
            case 8 -> tgra;
            case 0xA -> tgrb;
            default -> (read8(offset) << 8) | read8(offset + 1);
        };
    }

    /** Write 16-bit register by offset. */
    public void write16(int offset, int value) {
        switch (offset) {
            case 6 -> tcnt = value & 0xFFFF;
            case 8 -> tgra = value & 0xFFFF;
            case 0xA -> tgrb = value & 0xFFFF;
            default -> { write8(offset, (value >> 8) & 0xFF); write8(offset + 1, value & 0xFF); }
        }
    }

    private void updateCachedDivisor() {
        cachedDivisor = enabled ? clockDivisors[tcr & 0x07] : 0;
    }

    /** Enable/disable this timer channel (controlled by TSTR register). */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        updateCachedDivisor();
    }
    public boolean isEnabled() { return enabled; }
    public int getInterruptCount() { return interruptCount; }
    /** True when the timer is enabled and has a valid internal clock source. */
    public boolean isRunning() { return enabled && clockDivisors[tcr & 0x07] != 0; }

    /** Tick the timer. Called every CPU cycle. */
    public void tick() {
        int divisor = cachedDivisor;
        if (divisor == 0) return; // Stopped, external, or disabled

        prescaleCounter++;
        if (prescaleCounter < divisor) return;
        prescaleCounter = 0;

        int prevTcnt = tcnt;
        tcnt = (tcnt + 1) & 0xFFFF;

        // Compare match A
        if (tcnt == tgra) {
            boolean wasSet = (tsr & TSR_TGFA) != 0;
            tsr |= TSR_TGFA;
            if (!wasSet && (tier & TIER_TGIEA) != 0) {
                cpu.requestInterrupt(vecTGIA);
                interruptCount++;
            }
            // Clear on compare match A if configured
            int clearMode = (tcr >> 5) & 0x03;
            if (clearMode == 1) tcnt = 0;
        }

        // Compare match B
        if (tcnt == tgrb) {
            boolean wasSet = (tsr & TSR_TGFB) != 0;
            tsr |= TSR_TGFB;
            if (!wasSet && (tier & TIER_TGIEB) != 0) {
                cpu.requestInterrupt(vecTGIB);
                interruptCount++;
            }
            int clearMode = (tcr >> 5) & 0x03;
            if (clearMode == 2) tcnt = 0;
        }

        // Overflow (when counter wraps from 0xFFFF to 0)
        if (tcnt == 0 && prevTcnt == 0xFFFF) {
            boolean wasSet = (tsr & TSR_OVF) != 0;
            tsr |= TSR_OVF;
            if (!wasSet && (tier & TIER_OVIE) != 0) {
                cpu.requestInterrupt(vecOVF);
                interruptCount++;
            }
        }
    }
}
