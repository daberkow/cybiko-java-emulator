package com.github.daberkow;

import java.time.LocalDateTime;

/**
 * PCF8593 Real-Time Clock emulation with I2C interface.
 *
 * Connected to CPU Port F on the Cybiko Xtreme:
 *   Write: bit 1 (0x02) = SCL, bit 6 (0x40) = SDA (inverted: 0=high, 1=low)
 *   Read:  bit 6 (0x40) = SDA output (1=high, 0=low)
 *
 * I2C address: 0xA2 (write) / 0xA3 (read)
 *
 * Internal registers (BCD format):
 *   [0] Control/Status (bit 7=stop counting)
 *   [1] Hundredths of seconds
 *   [2] Seconds
 *   [3] Minutes
 *   [4] Hours
 *   [5] Day (bits 0-5) + Year within cycle (bits 6-7)
 *   [6] Month
 *   [7-15] Alarm/timer registers
 *
 * I2C protocol matches MAME pcf8593.cpp: MSB-first data, rising-edge clocking.
 */
public class PCF8593Rtc {
    private static final int I2C_ADDR = 0xA2; // 0xA2=write, 0xA3=read

    // I2C state machine (matches MAME's RTC_MODE_RECV/SEND/NONE)
    private enum Mode { RECV, SEND }
    private boolean active = false; // I2C transaction in progress
    private Mode mode = Mode.RECV;
    private int pinScl = 1;         // SCL pin state (0/1, matches MAME)
    private int pinSda = 1;         // SDA pin state from host (0/1, matches MAME)
    private int inp = 1;            // SDA output from RTC (0=pull low, 1=release/high), read via sda_r()
    private int bits = 0;           // Bit counter within current byte
    private int pos = 0;            // Current register pointer for send
    private int dataRecvIndex = 0;  // Index into received bytes buffer
    private final int[] dataRecv = new int[50]; // Received bytes buffer
    private boolean pendingReload = false; // Deferred reload on next STOP

    // RTC registers (16 bytes)
    private final int[] data = new int[16];
    private static final boolean DEBUG = false;
    private int debugTxnCount = 0;

    // Time advancement
    private long lastTickNanos;
    private long nanosAccum = 0;

    public PCF8593Rtc() {
        // Initialize with current system time.
        // CyOS year encoding: reg7 = toBcd(year - 2000).
        // CyOS reads: binary = fromBcd(reg7); if != 99 then += 100; year = 1900 + binary.
        // So reg7=0x26 → 26+100=126 → 1900+126=2026.
        LocalDateTime now = LocalDateTime.now();
        data[0] = 0x00;  // Control: counting enabled
        data[1] = 0x00;  // Hundredths
        data[2] = toBcd(now.getSecond());
        data[3] = toBcd(now.getMinute());
        data[4] = toBcd(now.getHour());
        int yearInCycle = now.getYear() % 4;
        data[5] = (yearInCycle << 6) | toBcd(now.getDayOfMonth());
        data[6] = toBcd(now.getMonthValue());
        // Register 7: CyOS year counter.
        // CyOS reads: binary = fromBcd(reg7); if (binary != 99) binary += 100;
        // year = 1900 + binary. So reg7 = toBcd(year - 2000) for years >= 2000.
        data[7] = toBcd(now.getYear() - 2000);
        lastTickNanos = System.nanoTime();
    }

    private static int toBcd(int val) {
        return ((val / 10) << 4) | (val % 10);
    }

    private static int fromBcd(int bcd) {
        return ((bcd >> 4) & 0x0F) * 10 + (bcd & 0x0F);
    }

    /** Advance the clock based on real elapsed time. Call periodically. */
    public void tick() {
        // Only advance if counting is enabled (bit 7 of control = 0)
        if ((data[0] & 0x80) != 0) return;

        long now = System.nanoTime();
        nanosAccum += now - lastTickNanos;
        lastTickNanos = now;

        // Advance seconds
        while (nanosAccum >= 1_000_000_000L) {
            nanosAccum -= 1_000_000_000L;
            advanceSeconds();
        }
    }

    private void advanceSeconds() {
        int sec = fromBcd(data[2]) + 1;
        if (sec < 60) { data[2] = toBcd(sec); return; }
        data[2] = 0;
        int min = fromBcd(data[3]) + 1;
        if (min < 60) { data[3] = toBcd(min); return; }
        data[3] = 0;
        int hour = fromBcd(data[4]) + 1;
        if (hour < 24) { data[4] = toBcd(hour); return; }
        data[4] = 0;
        int day = fromBcd(data[5] & 0x3F) + 1;
        int yearBits = data[5] & 0xC0;
        if (day <= 28) { data[5] = yearBits | toBcd(day); return; }
        // Simplified: max 28 days per month
        data[5] = yearBits | toBcd(1);
        int month = fromBcd(data[6]) + 1;
        if (month <= 12) { data[6] = toBcd(month); return; }
        data[6] = toBcd(1);
        int year = ((data[5] >> 6) & 3) + 1;
        data[5] = ((year % 4) << 6) | (data[5] & 0x3F);
    }

    /**
     * Reload registers 1-7 with the current system time.
     * Called when CyOS finishes its boot init (writes 0x04 to reg 0).
     */
    private void reloadSystemTime() {
        LocalDateTime now = LocalDateTime.now();
        data[1] = 0x00;  // Hundredths
        data[2] = toBcd(now.getSecond());
        data[3] = toBcd(now.getMinute());
        data[4] = toBcd(now.getHour());
        int yearInCycle = now.getYear() % 4;
        data[5] = (yearInCycle << 6) | toBcd(now.getDayOfMonth());
        data[6] = toBcd(now.getMonthValue());
        data[7] = toBcd(now.getYear() - 2000);
        lastTickNanos = System.nanoTime();
        nanosAccum = 0;
    }

    /** Read SDA line state (called from Port F read). */
    public boolean sda_r() {
        return inp != 0;
    }

    /**
     * Set SCL line state (from Port F write bit 1).
     * Matches MAME pcf8593.cpp scl_w() exactly.
     */
    public void scl_w(boolean high) {
        int state = high ? 1 : 0;

        // Process on rising edge of SCL while active
        if (active && pinScl == 0 && state != 0) {
            switch (mode) {
                case RECV -> {
                    // Release SDA at start of each new byte (was low for ACK)
                    if (bits == 0) {
                        inp = 1;
                    }
                    // HOST -> RTC: clock in a bit
                    if (pinSda != 0) {
                        dataRecv[dataRecvIndex] |= (0x80 >> bits);
                    }
                    bits++;
                    // After 8 data bits + 1 ACK = bit 9
                    if (bits > 8) {
                        // ACK: pull SDA low to acknowledge the received byte
                        inp = 0;
                        int received = dataRecv[dataRecvIndex] & 0xFF;
                        // First byte 0xA3 = switch to read/send mode
                        if (dataRecv[0] == 0xA3 && dataRecvIndex == 0) {
                            mode = Mode.SEND;
                        }
                        // First byte 0xA2 + second byte = set register position
                        if (dataRecv[0] == 0xA2 && dataRecvIndex == 1) {
                            pos = dataRecv[1] & 0x0F;
                        }
                        // 0xA2 + pos + data bytes = write registers
                        if (dataRecv[0] == 0xA2 && dataRecvIndex >= 2) {
                            int rtcPos = (dataRecv[1] + (dataRecvIndex - 2)) & 0x0F;
                            data[rtcPos] = received;
                            if (DEBUG && debugTxnCount < 50) {
                                System.err.printf("[RTC] WRITE reg[%d]=0x%02X%n", rtcPos, received);
                            }
                            // CyOS boot init resets time to Jan 1, 2000, then writes
                            // 0x04 to reg 0 to start counting. When we see that write,
                            // reload the real system time so the "Set Date" screen
                            // shows the correct date (matching other emulators).
                            if (rtcPos == 0 && received == 0x04) {
                                pendingReload = true;
                            }
                        }
                        bits = 0;
                        dataRecvIndex++;
                    }
                }
                case SEND -> {
                    // RTC -> HOST: clock out a bit
                    inp = (data[pos] >> (7 - bits)) & 1;
                    bits++;
                    // After 8 data bits + ACK
                    if (bits > 8) {
                        if (DEBUG && debugTxnCount < 50) {
                            System.err.printf("[RTC] READ reg[%d]=0x%02X%n", pos, data[pos]);
                        }
                        // Check master ACK/NACK
                        if (pinSda != 0) {
                            // Master NACK = end of read
                            mode = Mode.RECV;
                            inp = 1; // Release SDA
                            clearBufferRx();
                        }
                        bits = 0;
                        pos = (pos + 1) & 0x0F;
                    }
                }
            }
        }
        pinScl = state;
    }

    /** Set SDA line state (from Port F write bit 6, INVERTED). */
    public void sda_w(boolean high) {
        int state = high ? 1 : 0;

        // Check for START/STOP conditions while SCL is high
        if (pinScl != 0) {
            // START: SDA high -> low while SCL high
            if (state == 0 && pinSda != 0) {
                active = true;
                bits = 0;
                dataRecvIndex = 0;
                clearBufferRx();
                if (DEBUG && debugTxnCount < 50) {
                    System.err.printf("[RTC] --- START (txn %d) ---%n", debugTxnCount);
                }
            }
            // STOP: SDA low -> high while SCL high
            if (state != 0 && pinSda == 0) {
                active = false;
                inp = 1; // Release SDA on stop
                if (DEBUG && debugTxnCount < 50) {
                    System.err.printf("[RTC] --- STOP (txn %d, pendingReload=%b) ---%n", debugTxnCount, pendingReload);
                }
                debugTxnCount++;
                if (pendingReload) {
                    pendingReload = false;
                    reloadSystemTime();
                    if (DEBUG) {
                        System.err.printf("[RTC] RELOAD done: reg7=0x%02X reg5=0x%02X reg6=0x%02X%n", data[7], data[5], data[6]);
                    }
                }
            }
        }
        pinSda = state;
    }

    private void clearBufferRx() {
        java.util.Arrays.fill(dataRecv, 0);
        dataRecvIndex = 0;
    }
}
