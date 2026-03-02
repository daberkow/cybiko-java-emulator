package com.github.daberkow;

import java.util.ArrayList;
import java.util.List;

/**
 * Test mock for {@link RadioTransport}.
 *
 * Records all sent packets and allows simulating incoming packets
 * for unit testing of {@link RadioCoProcessor} and other components
 * that interact with the radio transport layer.
 */
public class MockRadioTransport implements RadioTransport {
    private PacketListener listener;
    private int channel = 0;
    private final int deviceId;
    private boolean started = false;
    private boolean closed = false;
    private final List<byte[]> sentPackets = new ArrayList<>();
    private final List<Integer> sentChannels = new ArrayList<>();

    public MockRadioTransport(int deviceId) {
        this.deviceId = deviceId;
    }

    @Override
    public void sendPacket(byte[] data, int channel) {
        sentPackets.add(data);
        sentChannels.add(channel);
    }

    @Override
    public void setPacketListener(PacketListener listener) {
        this.listener = listener;
    }

    @Override
    public void setChannel(int channel) {
        this.channel = channel;
    }

    @Override
    public void start() {
        started = true;
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public int getDeviceId() {
        return deviceId;
    }

    // --- Test helpers ---

    /** Returns all packets that were sent via {@link #sendPacket}. */
    public List<byte[]> getSentPackets() {
        return sentPackets;
    }

    /** Returns the channel argument for each call to {@link #sendPacket}. */
    public List<Integer> getSentChannels() {
        return sentChannels;
    }

    /** Returns true if {@link #start()} has been called. */
    public boolean isStarted() {
        return started;
    }

    /** Returns true if {@link #close()} has been called. */
    public boolean isClosed() {
        return closed;
    }

    /** Returns the current listening channel set via {@link #setChannel}. */
    public int getChannel() {
        return channel;
    }

    /**
     * Simulate receiving a packet from another device.
     * Calls the registered {@link PacketListener} if one is set.
     *
     * @param data     raw packet bytes
     * @param channel  radio channel
     * @param senderId device ID of the simulated sender
     */
    public void simulateReceive(byte[] data, int channel, int senderId) {
        if (listener != null) {
            listener.onPacketReceived(data, channel, senderId);
        }
    }
}
