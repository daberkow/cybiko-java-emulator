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
    private record ReceivedFrame(byte[] data, int senderId) {}
    private final Queue<ReceivedFrame> receivedFrames = new ConcurrentLinkedQueue<>();

    // Heartbeat beacon for peer discovery
    private int heartbeatCounter = 0;
    private static final int HEARTBEAT_INTERVAL = 300; // frames (5 sec at 60fps)

    // Track last 2-byte command type for poll/scan distinction.
    // On real hardware, poll (0x30) only captures small frames (RF2915 RX
    // window = 50 bytes), while scan (0xCF) captures any frame (RX = 200).
    private static final int CMD_NONE = 0;
    private static final int CMD_POLL = 1;  // 0x30: small frames only
    private static final int CMD_SCAN = 2;  // 0xCF: any frame
    private int lastCommandType = CMD_NONE;

    // Set by completeTxDtc() or tryAsyncDelivery() to indicate a frame is
    // ready for delivery. Consumed by prepareRxFrame() to decide whether
    // to dequeue.
    private boolean rxFrameReady = false;

    // Set by tryAsyncDelivery() for unsolicited frame delivery (frames that
    // arrive between TX DTC cycles). Separate from rxFrameReady so that a
    // concurrent completeTxDtc() reset doesn't lose the async delivery.
    private boolean asyncRxPending = false;

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
                queueReceivedFrame(data, senderId));
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
     * @param data     raw frame bytes received from the network
     * @param senderId device ID of the sender (used for AVR header bytes 4-7)
     */
    public void queueReceivedFrame(byte[] data, int senderId) {
        StringBuilder hex = new StringBuilder();
        StringBuilder ascii = new StringBuilder();
        for (int i = 0; i < Math.min(data.length, 52); i++) {
            int b = data[i] & 0xFF;
            hex.append(String.format("%02X ", b));
            ascii.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
        }
        System.err.printf("[RADIO] queueReceivedFrame %d bytes from 0x%08X: %s |%s|%n",
                data.length, senderId, hex.toString().trim(), ascii);
        // Cap queue at 4 frames to prevent unbounded growth while allowing
        // retransmit bursts (CyOS sends each frame 3x). Previous cap of 2
        // caused frames to be flushed before the next TX DTC cycle could
        // consume them.
        while (receivedFrames.size() >= 4) {
            receivedFrames.poll(); // Discard oldest
        }
        receivedFrames.add(new ReceivedFrame(data, senderId));
        // Wake up completeTxDtc() if it's waiting for frames
        synchronized (receivedFrames) {
            receivedFrames.notifyAll();
        }
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
            // Short TX DTC frames (e.g. 4-byte init command 01 03 00 00) are
            // AVR commands, not radio packets. Skip network send — they have
            // no RF header and produce 0-byte payloads after stripping.
            if (data.length <= RF_HEADER_SIZE + 1) return;

            // Strip RF preamble + sync word (first 8 bytes) and trailing
            // status byte (last byte). CyOS TX DTC sends frame_size+1 bytes
            // (50+1=51 for polls, 200+1=201 for scans). The extra byte is a
            // status/terminator for the AVR, not part of the radio payload.
            int start = RF_HEADER_SIZE;
            int end = data.length - 1; // strip trailing byte
            byte[] payload = java.util.Arrays.copyOfRange(data, start, end);

            // Extract channel from the frame content's channel byte (byte 0
            // of payload = 0xC0 | channel). CyOS channel-hops during peer
            // discovery (ch=2↔ch=4), and may change currentChannel between
            // preparing the frame and firing the TX DTC. Using the frame's
            // own channel byte ensures the UDP transport channel matches
            // what CyOS wrote into the frame content.
            int frameChannel = payload[0] & 0x3F;
            transport.sendPacket(payload, frameChannel);
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
     * <p>The indicator is the RX DTC byte count that CyOS will set up:
     * <ul>
     *   <li>0x32 (50) — small frame available (poll beacon, ≤50 bytes)</li>
     *   <li>0xC8 (200) — large frame available (scan/chat, &gt;50 bytes)
     *       OR no data (CyOS distinguishes by content: real data vs 0xFF)</li>
     * </ul>
     *
     * @return 0x32 for small frames, 0xC8 for large frames or no data.
     *         Always returns a valid indicator (never -1) so that the full
     *         DTC cycle completes and CyOS recycles its connection buffers.
     *         For no-data cases, prepareRxFrame fills with a null pattern
     *         that CyOS rejects quickly (destination 0x00000000, not broadcast).
     */
    public int completeTxDtc() {
        rxFrameReady = false;
        // On real hardware, the AVR/RF2915 has natural RF round-trip delay
        // (microseconds) before responding. Over UDP, frames from the peer
        // take milliseconds to arrive. Without this wait, completeTxDtc()
        // always returns 0x32 (null frame) because it checks the queue before
        // UDP frames have been delivered by the listener thread. Wait briefly
        // to simulate RF round-trip time and let UDP frames arrive.
        if (receivedFrames.isEmpty() && transport != null) {
            synchronized (receivedFrames) {
                if (receivedFrames.isEmpty()) {
                    try { receivedFrames.wait(15); } catch (InterruptedException ignored) {}
                }
            }
        }
        if (receivedFrames.isEmpty()) return 0x32; // Null frame — 50-byte RX DTC

        ReceivedFrame next = receivedFrames.peek();

        // During poll mode (0x30), only deliver small frames (≤50 bytes).
        // On real hardware, poll RX has a 50-byte window — only beacons fit.
        // Large frames (chat, name exchange) must wait for scan mode (0xCF)
        // which uses a 200-byte RX window. This matches real hardware behavior
        // and prevents delivering oversized frames that poll-mode CyOS code
        // may not expect. Small beacons ARE delivered during polls so peer
        // discovery works even when the user is on the home screen.
        if (lastCommandType == CMD_POLL && next.data().length > 50) {
            return 0x32; // Large frame waits for scan mode
        }

        rxFrameReady = true;
        return (next.data().length > 50) ? 0xC8 : 0x32;
    }

    /**
     * AVR header size prepended to received frames. On real hardware, the AVR
     * co-processor prepends 8 bytes of metadata before the RF payload when
     * forwarding received frames to the H8S via UART. CyOS expects:
     * <ul>
     *   <li>Bytes 0-3: destination peer ID (or 0xFFFFFFFF for broadcast)</li>
     *   <li>Bytes 4-7: sender's device ID (from transport layer)</li>
     *   <li>Byte 8+: RF payload (channel byte, type, frame data)</li>
     * </ul>
     * This header occupies the space before the RF payload in the RX DTC
     * buffer. The math confirms: TX sends 51/201 bytes (8 RF header + payload
     * + 1 trailing), so RF payload = 42/192 bytes. RX DTC receives 50/200
     * bytes. 50 - 42 = 8, 200 - 192 = 8 — exactly this header size.
     */
    private static final int AVR_RX_HEADER_SIZE = 8;

    /**
     * Prepare received frame data for RX DTC transfer. Dequeues one frame
     * from receivedFrames (if available) and loads it into rxQueue with an
     * 8-byte AVR header prepended, padded with 0xFF to fill the requested
     * count. If no frame is available, fills entirely with 0xFF (null read).
     *
     * <p>The AVR header ensures CyOS can process the frame:
     * <ul>
     *   <li>Bytes 0-3: broadcast marker (0xFFFFFFFF) so the main-loop radio
     *       task accepts the frame (checks connObj->0x00 against listener
     *       pointer or broadcast 0xFFFFFFFF at 0x49ADC8-0x49ADE4)</li>
     *   <li>Bytes 4-7: sender's device ID — CyOS uses this for frame
     *       matching at 0x49ED9C (conn->0x00 == frame->0x04) and to
     *       identify who sent the message in Chat/Nearby</li>
     *   <li>Byte 8+: RF payload — channel byte at offset 8 passes the
     *       channel format check (byte & 0xC0 == 0xC0) at 0x49ADAA</li>
     * </ul>
     *
     * <p>The indicator (0x32 or 0xC8) determines the count:
     * <ul>
     *   <li>0x32 → count=50 (small frame: 8 header + 42 payload)</li>
     *   <li>0xC8 → count=200 (large frame: 8 header + 192 payload)</li>
     * </ul>
     *
     * <p>When no frame is available, fills with all 0x00 instead of 0xFF.
     * This is critical: CyOS's main-loop radio task checks connObj->0x00
     * (bytes 0-3) against broadcast 0xFFFFFFFF. All-0xFF would PASS the
     * broadcast check, causing CyOS to queue the null frame for processing.
     * Since D6==0, the frame is never processed and leaks in the queue,
     * eventually exhausting CyOS heap ("not enough memory"). All-0x00
     * fails both the listener and broadcast checks, so CyOS immediately
     * discards the frame and recycles the connection buffer.
     *
     * Called by AddressBus when CyOS sets up RX DTC (DTCERF bit 7 = RXI2).
     *
     * @param count number of bytes CyOS expects (50 or 200)
     */
    public void prepareRxFrame(int count) {
        // Only dequeue a frame if completeTxDtc() or tryAsyncDelivery()
        // confirmed one is ready. This prevents poll-triggered null frame
        // from consuming a large frame that should wait for scan mode.
        ReceivedFrame rf = (rxFrameReady || asyncRxPending) ? receivedFrames.poll() : null;
        rxFrameReady = false;
        asyncRxPending = false;
        if (rf != null) {
            byte[] frame = rf.data();
            int senderId = rf.senderId();
            // Prepend 8-byte AVR header: broadcast destination + sender ID
            rxQueue.add(0xFF); // Bytes 0-3: broadcast destination (0xFFFFFFFF)
            rxQueue.add(0xFF);
            rxQueue.add(0xFF);
            rxQueue.add(0xFF);
            // Bytes 4-7: sender's device ID (big-endian)
            // CyOS uses this to identify the sender in frame matching at
            // 0x49ED9C (conn->0x00 == frame->0x04) and for user display.
            rxQueue.add((senderId >> 24) & 0xFF);
            rxQueue.add((senderId >> 16) & 0xFF);
            rxQueue.add((senderId >> 8) & 0xFF);
            rxQueue.add(senderId & 0xFF);
            // RF payload follows at offset 8
            int payloadSpace = count - AVR_RX_HEADER_SIZE;
            int len = Math.min(frame.length, payloadSpace);
            for (int i = 0; i < len; i++) {
                rxQueue.add(frame[i] & 0xFF);
            }
            // Pad remainder with 0xFF
            for (int i = AVR_RX_HEADER_SIZE + len; i < count; i++) {
                rxQueue.add(0xFF);
            }
        } else {
            // Null frame: all 0x00 so CyOS rejects at listener/broadcast
            // check (connObj->0x00 = 0x00000000 ≠ 0xFFFFFFFF broadcast).
            // CyOS discards and recycles the connection buffer immediately.
            for (int i = 0; i < count; i++) {
                rxQueue.add(0x00);
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
     * Returns true if there are received frames waiting to be delivered.
     * Used by AddressBus to decide whether to attempt async delivery.
     */
    public boolean hasPendingFrames() {
        return !receivedFrames.isEmpty();
    }

    /**
     * Attempt asynchronous frame delivery. Called by AddressBus when CyOS's
     * radio state machine is idle (state 1) and there are pending frames.
     *
     * <p>On real hardware, the AVR co-processor captures frames autonomously
     * from the RF2915 and sends them to the H8S via UART at any time. CyOS's
     * RXI2 ISR processes the indicator byte in state 1 and sets up RX DTC.
     * This method emulates that autonomous behavior — frames received via UDP
     * between TX DTC cycles are delivered without waiting for the next poll/scan.
     *
     * <p>The indicator value matches the frame size: 0x32 (50) for small
     * beacons (≤50 bytes), 0xC8 (200) for large frames (chat, name exchange).
     * CyOS's state 1 handler reads the indicator and sets up the RX DTC
     * count accordingly, regardless of whether the frame was solicited.
     *
     * @return indicator value (0x32 or 0xC8) if a frame was queued for
     *         delivery, or -1 if no frames are available
     */
    public int tryAsyncDelivery() {
        if (receivedFrames.isEmpty()) return -1;

        ReceivedFrame next = receivedFrames.peek();
        int indicator = (next.data().length > 50) ? 0xC8 : 0x32;

        asyncRxPending = true;
        rxQueue.add(indicator);
        System.err.printf("[RADIO-ASYNC] Injecting indicator 0x%02X for %d-byte frame from 0x%08X%n",
                indicator, next.data().length, next.senderId());
        return indicator;
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
            lastCommandType = CMD_POLL;
        } else if (header == 0xCF) {
            // Scan/beacon command — CyOS will follow with TX DTC payload.
            lastCommandType = CMD_SCAN;
        }
        // No immediate response queued — response comes from DTC completion path
    }
}
