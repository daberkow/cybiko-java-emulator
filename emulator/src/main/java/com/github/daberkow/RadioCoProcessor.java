package com.github.daberkow;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * AVR radio co-processor stub (AT90S2313).
 *
 * Emulates the UART-facing behavior of the AVR radio co-processor on SCI0/SCI2.
 * The Cybiko's H8S CPU communicates with this co-processor using a variable-length
 * command protocol. Command length is determined by the first byte:
 *
 * <ul>
 *   <li>{@code 0x01 XX XX} - 3-byte commands (init, channel, config)</li>
 *   <li>{@code 0x30 XX}    - 2-byte poll command (check for received data)</li>
 *   <li>{@code 0xCF XX}    - 2-byte scan/beacon command (peer discovery)</li>
 *   <li>All other first bytes default to 2-byte length</li>
 * </ul>
 *
 * Known commands (from SCI0/SCI2 traffic analysis):
 *   0x01 0x04 0x00 - Init command (V2 first boot)
 *   0x01 0x02 0xNN - Channel/config command (V1 + V2 + XT, param = channel)
 *   0x01 0x03 0x00 - V1 second init command
 *   0x30 0x00      - Poll for received frames
 *   0xCF 0x00      - Scan/beacon for peer discovery (Chat)
 *
 * Follows the same transfer() pattern as {@link AT45DB041Flash}: each byte
 * written by the CPU is passed to {@link #transfer(int)}, which returns a
 * response byte or -1 if no response is available yet.
 */
public class RadioCoProcessor {
    private final Queue<Integer> rxQueue = new ArrayDeque<>();
    private int currentChannel = 0;
    private boolean initialized = false;

    // Command accumulator (3-byte commands: header + cmd + param)
    private final int[] cmdBuffer = new int[3];
    private int cmdPos = 0;

    // Network transport layer (optional, null if no networking)
    private RadioTransport transport;

    // Queue of frames received from the transport layer, waiting to be
    // delivered to the H8S CPU via the SCI0 protocol.
    // ConcurrentLinkedQueue because the UDP listener thread adds frames
    // while the emulator thread reads them.
    private final Queue<byte[]> receivedFrames = new ConcurrentLinkedQueue<>();

    // Heartbeat beacon for peer discovery
    private int heartbeatCounter = 0;
    private static final int HEARTBEAT_INTERVAL = 300; // frames (5 sec at 60fps)

    /**
     * Receive a byte from the CPU (TX path only). Accumulates bytes into a
     * variable-length command buffer. When the command is complete, processes it
     * and queues the response for later retrieval via {@link #read()}.
     *
     * Use this for SCI2 (XT) where TX and RX are independent UART paths.
     * The response is delivered asynchronously via RXI2 interrupt, not
     * consumed synchronously during the TDR write.
     *
     * @param value byte sent from CPU
     */
    public void receive(int value) {
        cmdBuffer[cmdPos++] = value & 0xFF;

        // Determine command length from first byte
        int cmdLen = (cmdBuffer[0] == 0x01) ? 3 : 2;

        if (cmdPos >= cmdLen) {
            if (cmdLen == 3) {
                processCommand(cmdBuffer[0], cmdBuffer[1], cmdBuffer[2]);
            } else {
                processCommand2(cmdBuffer[0], cmdBuffer[1]);
            }
            cmdPos = 0;
        }
    }

    /**
     * Full-duplex byte transfer. Called when the H8S writes to SCI0 TDR.
     *
     * Accumulates bytes and returns a response byte synchronously. This matches
     * SPI full-duplex behavior used by SCI0 (V1/V2) where every TX byte
     * generates an immediate RX byte.
     *
     * @param value byte sent from CPU
     * @return queued response byte, or 0xFF if no response is available
     */
    public int transfer(int value) {
        receive(value);

        // Return response byte (0xFF for intermediate command bytes,
        // or queued response for final command byte).
        return rxQueue.isEmpty() ? 0xFF : rxQueue.poll();
    }

    /** Returns true if there are buffered response bytes waiting to be read. */
    public boolean hasData() {
        return !rxQueue.isEmpty();
    }

    /**
     * Queue a response byte for delivery to the CPU via RXI2.
     * Called by AddressBus after TX DTC completion to send protocol ACKs.
     *
     * @param value response byte to queue (e.g. 0x03 = packet received ACK)
     */
    public void queueResponse(int value) {
        rxQueue.add(value & 0xFF);
    }

    /**
     * Read the next buffered response byte without sending a command byte.
     *
     * @return next response byte, or 0xFF if no data available
     */
    public int read() {
        return rxQueue.isEmpty() ? 0xFF : rxQueue.poll();
    }

    /** Returns true if the co-processor has received an init or channel command. */
    public boolean isInitialized() {
        return initialized;
    }

    /** Returns the currently configured radio channel. */
    public int getCurrentChannel() {
        return currentChannel;
    }

    /**
     * Set the network transport layer for sending and receiving radio packets.
     *
     * Wires the transport's packet listener so that incoming packets are
     * queued for delivery to the H8S CPU. Pass {@code null} to disconnect.
     *
     * @param transport the transport to use, or null to disable networking
     */
    public void setTransport(RadioTransport transport) {
        this.transport = transport;
        if (transport != null) {
            transport.setPacketListener((data, channel, senderId) ->
                queueReceivedFrame(data));
        }
    }

    /** Returns the currently attached transport, or null if none. */
    public RadioTransport getTransport() {
        return transport;
    }

    /**
     * Queue a received frame for delivery to the H8S CPU.
     *
     * Called by the transport listener when a packet arrives from the network.
     * The frame will be delivered to the CPU the next time it polls for data.
     *
     * @param data raw frame bytes received from the network
     */
    public void queueReceivedFrame(byte[] data) {
        StringBuilder hex = new StringBuilder();
        StringBuilder ascii = new StringBuilder();
        for (int i = 0; i < Math.min(data.length, 52); i++) {
            int b = data[i] & 0xFF;
            hex.append(String.format("%02X ", b));
            ascii.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
        }
        System.err.printf("[RADIO] queueReceivedFrame %d bytes: %s |%s|%n",
                data.length, hex.toString().trim(), ascii);
        receivedFrames.add(data);
    }

    /**
     * RF preamble + sync word size. CyOS includes a 4-byte preamble (FF FF FF FF)
     * and 4-byte sync word (4C 80 51 A3) at the start of every TX buffer for the
     * AVR's RF2915 programming. On real hardware, the RF2915 generates preamble
     * and sync automatically on TX, and strips them on RX. We strip them before
     * forwarding over the network so the receiving emulator gets only the payload
     * that CyOS's RX parser expects.
     */
    private static final int RF_HEADER_SIZE = 8;

    /**
     * Transmit a frame over the network transport.
     *
     * Called when the H8S CPU has assembled a complete outgoing frame.
     * Strips the 8-byte RF header (preamble + sync word) before sending,
     * since the RF2915 handles those automatically and CyOS's RX parser
     * doesn't expect them in received frames.
     *
     * @param data raw frame bytes from CyOS TX buffer
     */
    public void handleTransmit(byte[] data) {
        if (transport != null) {
            // Strip RF preamble + sync word (first 8 bytes) and trailing
            // status byte (last byte). CyOS TX DTC sends frame_size+1 bytes
            // (50+1=51 for polls, 200+1=201 for scans). The extra byte is a
            // status/terminator for the AVR, not part of the radio payload.
            int start = Math.min(RF_HEADER_SIZE, data.length);
            int end = Math.max(start, data.length - 1); // strip trailing byte
            byte[] payload = java.util.Arrays.copyOfRange(data, start, end);
            transport.sendPacket(payload, currentChannel);
        }
    }

    /**
     * Called by AddressBus after TX DTC completion. Returns the frame size
     * indicator value for deferred delivery via RXI2.
     *
     * <p>On real hardware, the AVR sends the indicator byte only after the
     * TXI2 ISR has finished (which transitions CyOS from state 2 to state 1).
     * AddressBus defers delivery by storing the return value and queuing it
     * only when the TXI2 ISR clears TIE (detected via the SCR write handler).
     * This ensures the indicator arrives in state 1 where CyOS can process it.
     *
     * <p>Indicator values (decimal frame sizes):
     * <ul>
     *   <li>0x32 = 50 → "data available" (CyOS sets up 50-byte RX DTC)</li>
     *   <li>0xC8 = 200 → "no data" (CyOS sets up 200-byte null RX DTC)</li>
     * </ul>
     *
     * @return 0x32 if received frames are available, 0xC8 if none
     */
    /**
     * Returns the frame size indicator value for the current state.
     * Does NOT queue the indicator — AddressBus handles deferred delivery
     * to ensure TXI2 ISR runs before the indicator reaches RXI2.
     *
     * @return 0x32 if received frames are available, 0xC8 if none
     */
    public int completeTxDtc() {
        return receivedFrames.isEmpty() ? 0xC8 : 0x32;
    }

    /**
     * Prepare received frame data for RX DTC transfer. For real data reads
     * (count ≤ 50, triggered by 0x32 indicator), dequeues one frame from
     * receivedFrames and loads it into rxQueue padded with 0xFF.
     * For null reads (count > 50, triggered by 0xC8 "no data"), fills
     * with 0xFF without consuming any received frames.
     *
     * Called by AddressBus when CyOS sets up RX DTC (DTCERF bit 7 = RXI2).
     *
     * @param count number of bytes CyOS expects (50 for data, 200 for null)
     */
    public void prepareRxFrame(int count) {
        // Only dequeue a real frame for data reads (0x32 → count=50).
        // Null reads (0xC8 → count=200) must NOT consume received frames,
        // otherwise frames get silently eaten during "no data" DTC cycles.
        byte[] frame = (count <= 50) ? receivedFrames.poll() : null;
        if (frame != null) {
            int len = Math.min(frame.length, count);
            for (int i = 0; i < len; i++) {
                rxQueue.add(frame[i] & 0xFF);
            }
            for (int i = len; i < count; i++) {
                rxQueue.add(0xFF);
            }
        } else {
            for (int i = 0; i < count; i++) {
                rxQueue.add(0xFF);
            }
        }
    }

    /**
     * Tick once per frame. Reserved for future periodic radio tasks.
     * Call from the emulator's per-frame code (after rendering, before sleep).
     *
     * Note: heartbeat beacons were removed — CyOS handles peer discovery
     * through its own TX DTC scan/poll frames. Custom beacons were polluting
     * receivedFrames with non-CyOS data that CyOS couldn't parse.
     */
    public void tick() {
        // No-op for now. CyOS drives all radio traffic via TX DTC.
    }

    /**
     * Process a complete 3-byte command (header 0x01) and queue the response.
     *
     * @param header first byte (always 0x01)
     * @param cmd    command byte
     * @param param  parameter byte
     */
    private void processCommand(int header, int cmd, int param) {
        switch (cmd) {
            case 0x04 -> {
                // Init command (V2 first boot sequence)
                rxQueue.add(0x00); // ACK
                initialized = true;
            }
            case 0x02 -> {
                // Channel/config command (shared V1 + V2 + XT)
                currentChannel = param;
                if (transport != null) {
                    transport.setChannel(param);
                }
                rxQueue.add(0x00); // ACK
                initialized = true;
            }
            case 0x03 -> {
                // V1 second init command
                rxQueue.add(0x00); // ACK
            }
            default -> {
                // Unknown command, ACK to avoid blocking caller
                rxQueue.add(0x00);
            }
        }
    }

    /**
     * Process a complete 2-byte command. Does NOT queue an immediate response —
     * CyOS follows the 2-byte command with a TX DTC transfer (51 or 201 bytes
     * of packet data). The response sequence is driven by DTC completion:
     *   1. TX DTC completes → AddressBus queues 0x03 (packet ACK)
     *   2. CyOS state machine processes 0x03 → completion handler
     *
     * @param header first byte (0x30 = poll, 0xCF = scan/beacon)
     * @param param  parameter byte
     */
    private void processCommand2(int header, int param) {
        if (header == 0x30) {
            // Poll command — CyOS will follow with TX DTC payload
        } else if (header == 0xCF) {
            // Scan/beacon command — CyOS will follow with TX DTC payload.
            // CyOS assembles its own scan frames in the TX DTC buffer;
            // no need for us to send extra beacons.
        }
        // No immediate response queued — response comes from DTC completion path
    }
}
