package com.github.daberkow;

/**
 * Abstraction for the radio network transport layer.
 *
 * Implementations handle the actual packet send/receive over whatever
 * medium is in use (UDP multicast, loopback, hardware, etc.).
 * The emulator's {@link RadioCoProcessor} uses this interface to
 * send and receive radio frames without being coupled to a specific
 * network implementation.
 */
public interface RadioTransport {

    /**
     * Callback interface for incoming packets.
     */
    interface PacketListener {
        /**
         * Called when a packet is received from another device.
         *
         * @param data     raw packet bytes
         * @param channel  radio channel the packet was received on
         * @param senderId device ID of the sender
         */
        void onPacketReceived(byte[] data, int channel, int senderId);
    }

    /**
     * Send a packet on the specified radio channel.
     *
     * @param data    raw packet bytes to send
     * @param channel radio channel to transmit on
     */
    void sendPacket(byte[] data, int channel);

    /**
     * Register a listener for incoming packets.
     *
     * @param listener the listener to notify on packet receipt
     */
    void setPacketListener(PacketListener listener);

    /**
     * Set the radio channel to listen on.
     *
     * @param channel radio channel number
     */
    void setChannel(int channel);

    /**
     * Start the transport (open sockets, begin listening, etc.).
     *
     * @throws java.io.IOException if the transport cannot be started
     */
    void start() throws java.io.IOException;

    /**
     * Shut down the transport and release resources.
     */
    void close();

    /**
     * Returns this device's unique identifier on the radio network.
     *
     * @return device ID
     */
    int getDeviceId();
}
