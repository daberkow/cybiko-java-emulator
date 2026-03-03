package com.github.daberkow;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RadioCoProcessorTest {

    @Test void emptyRadioHasNoData() {
        RadioCoProcessor radio = new RadioCoProcessor();
        assertFalse(radio.hasData());
        assertEquals(0xFF, radio.transfer(0x00)); // Partial command, 0xFF idle byte
    }

    @Test void threeByteCommandGetsAck() {
        RadioCoProcessor radio = new RadioCoProcessor();
        assertEquals(0xFF, radio.transfer(0x01)); // byte 1 of 3, 0xFF idle
        assertEquals(0xFF, radio.transfer(0x04)); // byte 2 of 3, 0xFF idle
        int response = radio.transfer(0x00);      // byte 3 of 3, triggers response
        assertEquals(0x00, response);             // ACK
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

    @Test void pollingCommandNoImmediateResponse() {
        // 2-byte commands don't queue immediate responses — response comes
        // via DTC completion path (0x03 ACK from AddressBus)
        RadioCoProcessor radio = new RadioCoProcessor();
        radio.receive(0x30);
        radio.receive(0x00);
        assertFalse(radio.hasData()); // No response queued
    }

    @Test void unknownHeaderAcks() {
        RadioCoProcessor radio = new RadioCoProcessor();
        radio.transfer(0xFF);
        int response = radio.transfer(0x00); // 2nd byte completes the 2-byte command
        // Unknown 2-byte commands still don't queue responses (SCI2 path)
        // but transfer() returns 0xFF when queue is empty
        assertEquals(0xFF, response);
    }

    @Test void unknownSubcommandAcks() {
        RadioCoProcessor radio = new RadioCoProcessor();
        radio.transfer(0x01);
        radio.transfer(0xFE); // unknown sub-command
        int response = radio.transfer(0x00);
        assertEquals(0x00, response); // Generic ACK (3-byte commands still ACK)
    }

    @Test void partialCommandNoResponse() {
        RadioCoProcessor radio = new RadioCoProcessor();
        assertEquals(0xFF, radio.transfer(0x01)); // first byte, 0xFF idle
        assertFalse(radio.hasData());
        assertEquals(0xFF, radio.transfer(0x04)); // second byte, 0xFF idle
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

    @Test void twoByteCommandsDoNotCorruptFraming() {
        RadioCoProcessor radio = new RadioCoProcessor();
        // Two consecutive 2-byte polls (no response queued)
        radio.receive(0x30);
        radio.receive(0x00);
        radio.receive(0x30);
        radio.receive(0x00);
        assertFalse(radio.hasData());
        // Then a 3-byte init command — should still work
        radio.transfer(0x01);
        radio.transfer(0x04);
        assertEquals(0x00, radio.transfer(0x00));
        assertTrue(radio.isInitialized());
    }

    @Test void scanCommandNoImmediateResponse() {
        RadioCoProcessor radio = new RadioCoProcessor();
        radio.receive(0xCF);
        radio.receive(0x00);
        assertFalse(radio.hasData()); // No immediate response
    }

    @Test void queueResponseDeliveredViaRead() {
        RadioCoProcessor radio = new RadioCoProcessor();
        assertFalse(radio.hasData());
        radio.queueResponse(0x03);
        assertTrue(radio.hasData());
        assertEquals(0x03, radio.read());
        assertFalse(radio.hasData());
    }

    @Test void mixedCommandLengths() {
        RadioCoProcessor radio = new RadioCoProcessor();
        // 3-byte init
        radio.transfer(0x01);
        radio.transfer(0x02);
        radio.transfer(0x04);
        // 2-byte poll (no immediate response)
        radio.receive(0x30);
        radio.receive(0x00);
        assertFalse(radio.hasData());
        // completeTxDtc returns indicator; AddressBus handles deferred delivery
        int indicator = radio.completeTxDtc();
        assertEquals(0xC8, indicator); // No received frames → null indicator
        assertFalse(radio.hasData()); // completeTxDtc doesn't queue anything
        // Simulate AddressBus deferred delivery: 0x03 + indicator
        radio.queueResponse(0x03);
        radio.queueResponse(indicator);
        assertTrue(radio.hasData());
        assertEquals(0x03, radio.read());
        assertEquals(0xC8, radio.read());
        assertFalse(radio.hasData());
        // 3-byte channel change still works
        radio.transfer(0x01);
        radio.transfer(0x02);
        assertEquals(0x00, radio.transfer(0x07));
        assertEquals(7, radio.getCurrentChannel());
    }

    @Test void completeTxDtcReturnsIndicator() {
        RadioCoProcessor radio = new RadioCoProcessor();
        // Queue a received frame — completeTxDtc returns 0x32 without queuing
        radio.queueReceivedFrame(new byte[]{0x01, 0x02, 0x03});
        int indicator = radio.completeTxDtc();
        assertEquals(0x32, indicator); // Data available
        assertFalse(radio.hasData()); // Nothing queued by completeTxDtc

        // Simulate AddressBus deferred delivery: 0x03 (packet ACK) + indicator
        // This matches the real protocol: state 6 consumes 0x03, state 1 consumes indicator
        radio.queueResponse(0x03);
        radio.queueResponse(indicator);
        assertEquals(0x03, radio.read()); // State 6: packet ACK
        assertEquals(0x32, radio.read()); // State 1: frame indicator
        assertFalse(radio.hasData());

        // Frame data delivered separately via prepareRxFrame (simulates RX DTC)
        radio.prepareRxFrame(50);
        assertEquals(0x01, radio.read());
        assertEquals(0x02, radio.read());
        assertEquals(0x03, radio.read());
        for (int i = 0; i < 47; i++) {
            assertEquals(0xFF, radio.read());
        }
        assertFalse(radio.hasData());
    }

    @Test void completeTxDtcNoFrameReturnsNullIndicator() {
        RadioCoProcessor radio = new RadioCoProcessor();
        // No received frames — returns 0xC8 without queuing
        int indicator = radio.completeTxDtc();
        assertEquals(0xC8, indicator);
        assertFalse(radio.hasData()); // Nothing queued
    }

    @Test void prepareRxFrameLoadsData() {
        RadioCoProcessor radio = new RadioCoProcessor();
        byte[] frame = new byte[]{0x10, 0x20, 0x30};
        radio.queueReceivedFrame(frame);
        radio.prepareRxFrame(50);
        // First 3 bytes are frame data
        assertEquals(0x10, radio.read());
        assertEquals(0x20, radio.read());
        assertEquals(0x30, radio.read());
        // Remaining 47 bytes are 0xFF padding
        for (int i = 0; i < 47; i++) {
            assertEquals(0xFF, radio.read());
        }
        assertFalse(radio.hasData());
    }

    @Test void prepareRxFrameNoFrameFillsFF() {
        RadioCoProcessor radio = new RadioCoProcessor();
        radio.prepareRxFrame(5);
        for (int i = 0; i < 5; i++) {
            assertEquals(0xFF, radio.read());
        }
        assertFalse(radio.hasData());
    }

    @Test void prepareRxLargeFrameDeliveredAt200() {
        RadioCoProcessor radio = new RadioCoProcessor();
        // 192-byte scan/chat frame (larger than 50 bytes)
        byte[] frame = new byte[192];
        frame[0] = (byte) 0xC4; // channel byte
        frame[1] = 0x20;        // scan type
        frame[18] = 0x0F;       // message length
        System.arraycopy("Hi, everybody!".getBytes(), 0, frame, 19, 14);
        radio.queueReceivedFrame(frame);

        // completeTxDtc returns 0xC8 for large frames
        int indicator = radio.completeTxDtc();
        assertEquals(0xC8, indicator);

        // prepareRxFrame(200) delivers the large frame
        radio.prepareRxFrame(200);
        assertEquals(0xC4, radio.read()); // channel byte
        assertEquals(0x20, radio.read()); // scan type
        // Skip to message at offset 18
        for (int i = 2; i < 18; i++) radio.read();
        assertEquals(0x0F, radio.read()); // message length
        // Remaining 181 bytes (frame data + padding)
        for (int i = 19; i < 200; i++) radio.read();
        assertFalse(radio.hasData());
    }

    @Test void prepareRxNullReadNoFrame() {
        RadioCoProcessor radio = new RadioCoProcessor();
        // No frame queued — 200-byte null read fills with 0xFF
        radio.prepareRxFrame(200);
        for (int i = 0; i < 200; i++) {
            assertEquals(0xFF, radio.read());
        }
        assertFalse(radio.hasData());
    }

    @Test void smallFrameGets0x32LargeFrameGets0xC8() {
        RadioCoProcessor radio = new RadioCoProcessor();
        // Small frame (42 bytes, like a poll beacon)
        radio.queueReceivedFrame(new byte[42]);
        assertEquals(0x32, radio.completeTxDtc());

        // Large frame (192 bytes, like a scan/chat)
        radio.queueReceivedFrame(new byte[192]);
        // First frame is still small (peek returns first)
        assertEquals(0x32, radio.completeTxDtc());

        // Consume the small frame
        radio.prepareRxFrame(50);
        for (int i = 0; i < 50; i++) radio.read();

        // Now the large frame is next
        assertEquals(0xC8, radio.completeTxDtc());
    }

    @Test void handleTransmitUsesFrameChannelNotCurrentChannel() {
        RadioCoProcessor radio = new RadioCoProcessor();
        // Track what channel sendPacket is called with
        final int[] sentChannel = {-1};
        final byte[][] sentData = {null};
        RadioTransport mockTransport = new RadioTransport() {
            @Override public void sendPacket(byte[] data, int channel) {
                sentData[0] = data;
                sentChannel[0] = channel;
            }
            @Override public void setPacketListener(PacketListener l) {}
            @Override public void setChannel(int ch) {}
            @Override public void start() {}
            @Override public void close() {}
            @Override public int getDeviceId() { return 0x42; }
        };
        radio.setTransport(mockTransport);

        // Set currentChannel to 2
        radio.transfer(0x01);
        radio.transfer(0x02);
        radio.transfer(0x02);
        assertEquals(2, radio.getCurrentChannel());

        // Build a TX DTC frame with channel byte 0xC4 (channel 4)
        // Format: 8-byte RF header + payload + 1-byte trailing status
        byte[] txData = new byte[51]; // 8 + 42 + 1
        // RF header (preamble + sync) — stripped by handleTransmit
        txData[0] = (byte) 0xFF; txData[1] = (byte) 0xFF;
        txData[2] = (byte) 0xFF; txData[3] = (byte) 0xFF;
        txData[4] = 0x4C; txData[5] = (byte) 0x80;
        txData[6] = 0x51; txData[7] = (byte) 0xA3;
        // Payload byte 0: channel byte 0xC4 = channel 4
        txData[8] = (byte) 0xC4;
        // Trailing status byte
        txData[50] = 0x00;

        radio.handleTransmit(txData);

        // Transport should have been called with channel 4 (from frame content),
        // NOT channel 2 (from currentChannel)
        assertEquals(4, sentChannel[0]);
        assertNotNull(sentData[0]);
        assertEquals((byte) 0xC4, sentData[0][0]);
    }
}
