package org.example.cybiko;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PCF8593RtcTest {

    @Test void sdaIdleHigh() {
        PCF8593Rtc rtc = new PCF8593Rtc();
        assertTrue(rtc.sda_r(), "SDA should be high (released) when idle");
    }

    @Test void startCondition() {
        PCF8593Rtc rtc = new PCF8593Rtc();
        // SCL high, SDA goes high→low = START
        rtc.scl_w(true);
        rtc.sda_w(true);  // SDA high
        rtc.sda_w(false); // SDA low while SCL high = START
        // After START, SDA might be pulled low for ACK eventually
        // Just verify no crash
    }

    @Test void stopCondition() {
        PCF8593Rtc rtc = new PCF8593Rtc();
        // START first
        rtc.scl_w(true);
        rtc.sda_w(true);
        rtc.sda_w(false); // START

        // STOP: SDA low→high while SCL high
        rtc.scl_w(true);
        rtc.sda_w(false); // SDA low
        rtc.sda_w(true);  // SDA high = STOP

        assertTrue(rtc.sda_r(), "SDA should be released after STOP");
    }

    /** Send one byte via I2C bit-bang and return the ACK state. */
    private boolean sendByte(PCF8593Rtc rtc, int value) {
        for (int bit = 7; bit >= 0; bit--) {
            rtc.scl_w(false);
            rtc.sda_w(((value >> bit) & 1) != 0);
            rtc.scl_w(true); // Rising edge clocks in the bit
        }
        // 9th clock = ACK
        rtc.scl_w(false);
        rtc.sda_w(true); // Release SDA for ACK
        rtc.scl_w(true);
        boolean ack = !rtc.sda_r(); // ACK = SDA pulled low by device
        return ack;
    }

    /** Receive one byte via I2C bit-bang, send ACK/NACK. */
    private int recvByte(PCF8593Rtc rtc, boolean sendAck) {
        int value = 0;
        for (int bit = 7; bit >= 0; bit--) {
            rtc.scl_w(false);
            rtc.sda_w(true); // Release SDA for device to drive
            rtc.scl_w(true);
            if (rtc.sda_r()) value |= (1 << bit);
        }
        // Send ACK (low) or NACK (high)
        rtc.scl_w(false);
        rtc.sda_w(sendAck); // true = SDA low = ACK (inverted in our API? No, sda_w(true) = SDA high in pcf8593)
        // Wait - sda_w convention: the parameter is the logical level AFTER inversion
        // In PCF8593Rtc: sda_w(true) means SDA high, sda_w(false) means SDA low
        // For ACK from master: pull SDA low = sda_w(false)
        // For NACK: leave SDA high = sda_w(true)
        rtc.sda_w(!sendAck); // ACK = pull low = sda_w(false)
        rtc.scl_w(true);
        return value;
    }

    private void i2cStart(PCF8593Rtc rtc) {
        rtc.sda_w(true);  // SDA high
        rtc.scl_w(true);  // SCL high
        rtc.sda_w(false); // SDA goes low while SCL high = START
    }

    private void i2cStop(PCF8593Rtc rtc) {
        rtc.sda_w(false); // SDA low
        rtc.scl_w(true);  // SCL high
        rtc.sda_w(true);  // SDA goes high while SCL high = STOP
    }

    @Test void writeAddressAcknowledged() {
        PCF8593Rtc rtc = new PCF8593Rtc();
        i2cStart(rtc);
        boolean ack = sendByte(rtc, 0xA2); // Write address
        assertTrue(ack, "RTC should ACK its write address 0xA2");
        i2cStop(rtc);
    }

    @Test void readAddressAcknowledged() {
        PCF8593Rtc rtc = new PCF8593Rtc();
        i2cStart(rtc);
        boolean ack = sendByte(rtc, 0xA3); // Read address
        assertTrue(ack, "RTC should ACK its read address 0xA3");
        i2cStop(rtc);
    }

    @Test void readControlRegister() {
        PCF8593Rtc rtc = new PCF8593Rtc();

        // Set register pointer to 0 (control register)
        i2cStart(rtc);
        sendByte(rtc, 0xA2); // Write address
        sendByte(rtc, 0x00); // Register pointer = 0
        i2cStop(rtc);

        // Read register 0
        i2cStart(rtc);
        sendByte(rtc, 0xA3); // Read address
        int control = recvByte(rtc, false); // NACK = end read
        i2cStop(rtc);

        // Control register default: 0x00 (counting enabled)
        assertEquals(0x00, control, "Control register should default to 0x00");
    }

    @Test void sdaReleasedAfterStop() {
        PCF8593Rtc rtc = new PCF8593Rtc();
        i2cStart(rtc);
        sendByte(rtc, 0xA2);
        i2cStop(rtc);
        assertTrue(rtc.sda_r(), "SDA should be released after STOP");
    }
}
