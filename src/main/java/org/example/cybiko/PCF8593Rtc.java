package org.example.cybiko;

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

    // RTC registers (16 bytes)
    private final int[] data = new int[16];

    // Time advancement
    private long lastTickNanos;
    private long nanosAccum = 0;

    private int debugCount = 0;

    public PCF8593Rtc() {
        // Initialize with current system time
        LocalDateTime now = LocalDateTime.now();
        data[0] = 0x00;  // Control: counting enabled
        data[1] = 0x00;  // Hundredths
        data[2] = toBcd(now.getSecond());
        data[3] = toBcd(now.getMinute());
        data[4] = toBcd(now.getHour());
        data[5] = (((now.getYear() % 4)) << 6) | toBcd(now.getDayOfMonth());
        data[6] = toBcd(now.getMonthValue());
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
                        if (debugCount < 30) {
                            System.err.printf("[I2C] Received byte 0x%02X (idx=%d)%n", received, dataRecvIndex);
                        }
                        // First byte 0xA3 = switch to read/send mode
                        if (dataRecv[0] == 0xA3 && dataRecvIndex == 0) {
                            mode = Mode.SEND;
                            if (debugCount < 30) {
                                System.err.printf("[I2C] READ mode, sending from pos=%d%n", pos);
                            }
                        }
                        // First byte 0xA2 + second byte = set register position
                        if (dataRecv[0] == 0xA2 && dataRecvIndex == 1) {
                            pos = dataRecv[1] & 0x0F;
                            if (debugCount < 30) {
                                System.err.printf("[I2C] Register pointer = %d%n", pos);
                            }
                        }
                        // 0xA2 + pos + data bytes = write registers
                        if (dataRecv[0] == 0xA2 && dataRecvIndex >= 2) {
                            int rtcPos = (dataRecv[1] + (dataRecvIndex - 2)) & 0x0F;
                            data[rtcPos] = received;
                            if (debugCount < 30) {
                                System.err.printf("[I2C] Write reg[%d] = 0x%02X%n", rtcPos, received);
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
                        if (debugCount < 30) {
                            System.err.printf("[I2C] Sent byte 0x%02X from pos=%d%n", data[pos], pos);
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
                if (debugCount++ < 30) {
                    System.err.println("[I2C] START condition");
                }
                active = true;
                bits = 0;
                dataRecvIndex = 0;
                clearBufferRx();
            }
            // STOP: SDA low -> high while SCL high
            if (state != 0 && pinSda == 0) {
                if (debugCount++ < 30) {
                    System.err.println("[I2C] STOP condition");
                }
                active = false;
                inp = 1; // Release SDA on stop
            }
        }
        pinSda = state;
    }

    private void clearBufferRx() {
        java.util.Arrays.fill(dataRecv, 0);
        dataRecvIndex = 0;
    }
}
