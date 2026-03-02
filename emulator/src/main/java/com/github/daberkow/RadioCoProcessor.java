package com.github.daberkow;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * AVR radio co-processor stub (AT90S2313).
 *
 * Emulates the UART-facing behavior of the AVR radio co-processor on SCI0.
 * The Cybiko's H8S CPU communicates with this co-processor over SCI0 using
 * a simple 3-byte command protocol: header(0x01) + command + parameter.
 *
 * Known commands (from SCI0 traffic analysis):
 *   0x01 0x04 0x00 - Init command (V2 first boot)
 *   0x01 0x02 0xNN - Channel/config command (V1 + V2, param = channel)
 *   0x01 0x03 0x00 - V1 second init command
 *   0x30 0x00 0x?? - Polling command (V2 periodic)
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
    private final Queue<byte[]> receivedFrames = new ArrayDeque<>();

    /**
     * Full-duplex byte transfer. Called when the H8S writes to SCI0 TDR.
     *
     * Accumulates bytes into a 3-byte command buffer. When the third byte
     * arrives, the command is processed and a response (ACK) is queued.
     *
     * @param value byte sent from CPU
     * @return queued response byte, or -1 if no response is available
     */
    public int transfer(int value) {
        cmdBuffer[cmdPos++] = value & 0xFF;

        if (cmdPos >= 3) {
            processCommand(cmdBuffer[0], cmdBuffer[1], cmdBuffer[2]);
            cmdPos = 0;
        }

        return rxQueue.isEmpty() ? -1 : rxQueue.poll();
    }

    /** Returns true if there are buffered response bytes waiting to be read. */
    public boolean hasData() {
        return !rxQueue.isEmpty();
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
        receivedFrames.add(data);
    }

    /**
     * Transmit a frame over the network transport.
     *
     * Called when the H8S CPU has assembled a complete outgoing frame.
     * If no transport is attached, the frame is silently dropped.
     *
     * @param data raw frame bytes to transmit
     */
    public void handleTransmit(byte[] data) {
        if (transport != null) {
            transport.sendPacket(data, currentChannel);
        }
    }

    /**
     * Process a complete 3-byte command and queue the appropriate response.
     *
     * @param header first byte (0x01 for standard commands, 0x30 for polling)
     * @param cmd    command byte
     * @param param  parameter byte
     */
    private void processCommand(int header, int cmd, int param) {
        if (header == 0x01) {
            switch (cmd) {
                case 0x04 -> {
                    // Init command (V2 first boot sequence)
                    rxQueue.add(0x00); // ACK
                    initialized = true;
                }
                case 0x02 -> {
                    // Channel/config command (shared V1 + V2)
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
        } else if (header == 0x30) {
            // Polling command (V2 periodic check for received packets)
            rxQueue.add(0x00); // No data available
        } else {
            // Unknown header, ACK to avoid blocking caller
            rxQueue.add(0x00);
        }
    }
}
