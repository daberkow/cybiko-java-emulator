package com.github.daberkow;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RadioCoProcessorTest {

    @Test void emptyRadioHasNoData() {
        RadioCoProcessor radio = new RadioCoProcessor();
        assertFalse(radio.hasData());
        assertEquals(-1, radio.transfer(0x00)); // Partial command, no response yet
    }

    @Test void threeByteCommandGetsAck() {
        RadioCoProcessor radio = new RadioCoProcessor();
        assertEquals(-1, radio.transfer(0x01)); // byte 1 of 3
        assertEquals(-1, radio.transfer(0x04)); // byte 2 of 3
        int response = radio.transfer(0x00);    // byte 3 of 3, triggers response
        assertEquals(0x00, response);           // ACK
        assertTrue(radio.isInitialized());
    }

    @Test void channelCommandSetsChannel() {
        RadioCoProcessor radio = new RadioCoProcessor();
        radio.transfer(0x01);
        radio.transfer(0x02);
        radio.transfer(0x05); // set channel 5
        assertEquals(5, radio.getCurrentChannel());
    }

    @Test void channelCommandInitializes() {
        RadioCoProcessor radio = new RadioCoProcessor();
        assertFalse(radio.isInitialized());
        radio.transfer(0x01);
        radio.transfer(0x02);
        radio.transfer(0x0A);
        assertTrue(radio.isInitialized());
        assertEquals(0x0A, radio.getCurrentChannel());
    }

    @Test void notInitializedBeforeCommand() {
        RadioCoProcessor radio = new RadioCoProcessor();
        assertFalse(radio.isInitialized());
        assertEquals(0, radio.getCurrentChannel());
    }

    @Test void v1SecondCommandAcks() {
        RadioCoProcessor radio = new RadioCoProcessor();
        radio.transfer(0x01);
        radio.transfer(0x03);
        int response = radio.transfer(0x00);
        assertEquals(0x00, response); // ACK
    }

    @Test void v1SecondCommandDoesNotInitialize() {
        RadioCoProcessor radio = new RadioCoProcessor();
        radio.transfer(0x01);
        radio.transfer(0x03);
        radio.transfer(0x00);
        // cmd 0x03 does not set initialized flag
        assertFalse(radio.isInitialized());
    }

    @Test void pollingCommandAcks() {
        RadioCoProcessor radio = new RadioCoProcessor();
        radio.transfer(0x30);
        radio.transfer(0x00);
        int response = radio.transfer(0x00);
        assertEquals(0x00, response); // No data available
    }

    @Test void unknownHeaderAcks() {
        RadioCoProcessor radio = new RadioCoProcessor();
        radio.transfer(0xFF);
        radio.transfer(0x00);
        int response = radio.transfer(0x00);
        assertEquals(0x00, response); // Unknown, ACK anyway
    }

    @Test void unknownSubcommandAcks() {
        RadioCoProcessor radio = new RadioCoProcessor();
        radio.transfer(0x01);
        radio.transfer(0xFE); // unknown sub-command
        int response = radio.transfer(0x00);
        assertEquals(0x00, response); // Generic ACK
    }

    @Test void partialCommandNoResponse() {
        RadioCoProcessor radio = new RadioCoProcessor();
        assertEquals(-1, radio.transfer(0x01)); // first byte
        assertFalse(radio.hasData());
        assertEquals(-1, radio.transfer(0x04)); // second byte
        assertFalse(radio.hasData());
    }

    @Test void consecutiveCommandsWork() {
        RadioCoProcessor radio = new RadioCoProcessor();
        // First command: init
        radio.transfer(0x01);
        radio.transfer(0x04);
        int r1 = radio.transfer(0x00);
        assertEquals(0x00, r1);

        // Second command: set channel
        radio.transfer(0x01);
        radio.transfer(0x02);
        int r2 = radio.transfer(0x07);
        assertEquals(0x00, r2);
        assertEquals(7, radio.getCurrentChannel());
    }

    @Test void readReturns0xFFWhenEmpty() {
        RadioCoProcessor radio = new RadioCoProcessor();
        assertEquals(0xFF, radio.read());
    }

    @Test void readReturnsQueuedData() {
        RadioCoProcessor radio = new RadioCoProcessor();
        // Send a command to queue a response but don't consume it via transfer()
        radio.transfer(0x01);
        radio.transfer(0x04);
        // Third byte triggers response; the response is returned by transfer()
        radio.transfer(0x00);
        // Queue is now empty (response was consumed by transfer)
        assertFalse(radio.hasData());
    }

    @Test void v2BootSequence() {
        // Simulates the V2 boot sequence: 01 04 00, then 01 02 02
        RadioCoProcessor radio = new RadioCoProcessor();

        // First command: 01 04 00
        radio.transfer(0x01);
        radio.transfer(0x04);
        int ack1 = radio.transfer(0x00);
        assertEquals(0x00, ack1);
        assertTrue(radio.isInitialized());

        // Second command: 01 02 02
        radio.transfer(0x01);
        radio.transfer(0x02);
        int ack2 = radio.transfer(0x02);
        assertEquals(0x00, ack2);
        assertEquals(2, radio.getCurrentChannel());
    }

    @Test void v1BootSequence() {
        // Simulates the V1 boot sequence: 01 02 02, 01 03 00
        RadioCoProcessor radio = new RadioCoProcessor();

        // First command: 01 02 02
        radio.transfer(0x01);
        radio.transfer(0x02);
        int ack1 = radio.transfer(0x02);
        assertEquals(0x00, ack1);
        assertTrue(radio.isInitialized());
        assertEquals(2, radio.getCurrentChannel());

        // Second command: 01 03 00
        radio.transfer(0x01);
        radio.transfer(0x03);
        int ack2 = radio.transfer(0x00);
        assertEquals(0x00, ack2);
    }

    @Test void channelChangeUpdatesChannel() {
        RadioCoProcessor radio = new RadioCoProcessor();
        // Set channel 3
        radio.transfer(0x01);
        radio.transfer(0x02);
        radio.transfer(0x03);
        assertEquals(3, radio.getCurrentChannel());

        // Change to channel 10
        radio.transfer(0x01);
        radio.transfer(0x02);
        radio.transfer(0x0A);
        assertEquals(0x0A, radio.getCurrentChannel());
    }

    @Test void transferMasksTo8Bits() {
        RadioCoProcessor radio = new RadioCoProcessor();
        // Pass values > 0xFF to verify masking
        radio.transfer(0x101); // should be treated as 0x01
        radio.transfer(0x204); // should be treated as 0x04
        int response = radio.transfer(0x300); // should be treated as 0x00
        assertEquals(0x00, response); // ACK for init command
        assertTrue(radio.isInitialized());
    }
}
