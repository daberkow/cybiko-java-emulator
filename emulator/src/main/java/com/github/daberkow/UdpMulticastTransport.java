package com.github.daberkow;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;

/**
 * UDP multicast transport for LAN radio networking between Cybiko emulators.
 *
 * <p>Joins a multicast group (default {@code 239.0.0.42:19200}) and sends/receives
 * radio packets over the local network. Each packet on the wire has the format:
 * <pre>
 *   [4-byte device ID big-endian][1-byte channel][N-byte payload]
 * </pre>
 *
 * <p>Packets from this device (matching device ID) are filtered out on receive.
 * Channel filtering is NOT done at the transport level — CyOS handles it via
 * its own protocol layer (state=5 / MRA=0x00 discard for mismatched channels).
 * TTL is set to 1 to keep traffic LAN-only.
 */
public class UdpMulticastTransport implements RadioTransport {
    static final String DEFAULT_GROUP = "239.0.0.42";
    static final int DEFAULT_PORT = 19200;
    /** Minimum wire packet size: 4-byte device ID + 1-byte channel. */
    static final int HEADER_SIZE = 5;

    private final int deviceId;
    private final InetAddress group;
    private final int port;
    private MulticastSocket socket;
    private PacketListener listener;
    private volatile int currentChannel = 0;
    private volatile boolean running = false;
    private Thread listenerThread;

    /**
     * Create a transport using the default multicast group and port.
     *
     * @param deviceId this device's unique radio ID
     * @throws IOException if the group address cannot be resolved
     */
    public UdpMulticastTransport(int deviceId) throws IOException {
        this(deviceId, DEFAULT_GROUP, DEFAULT_PORT);
    }

    /**
     * Create a transport with a custom multicast group and port.
     *
     * @param deviceId  this device's unique radio ID
     * @param groupAddr multicast group address (e.g. {@code "239.0.0.42"})
     * @param port      UDP port number
     * @throws IOException if the group address cannot be resolved
     */
    public UdpMulticastTransport(int deviceId, String groupAddr, int port) throws IOException {
        this.deviceId = deviceId;
        this.group = InetAddress.getByName(groupAddr);
        this.port = port;
    }

    @Override
    public void start() throws IOException {
        socket = new MulticastSocket(port);
        socket.setTimeToLive(1); // LAN only
        socket.setLoopbackMode(false); // Enable loopback (same-host delivery)
        // Join on all interfaces to ensure same-host delivery works regardless
        // of which interface the kernel routes multicast through.
        var ifaces = NetworkInterface.getNetworkInterfaces();
        boolean joined = false;
        while (ifaces.hasMoreElements()) {
            NetworkInterface ni = ifaces.nextElement();
            if (ni.isUp() && ni.supportsMulticast()) {
                try {
                    socket.joinGroup(new InetSocketAddress(group, port), ni);
                    joined = true;
                } catch (IOException e) {
                    // Some interfaces can't join multicast (e.g. tun/tap)
                }
            }
        }
        if (!joined) {
            // Fallback: join with default interface
            socket.joinGroup(new InetSocketAddress(group, port), null);
        }
        running = true;

        listenerThread = new Thread(this::listenLoop, "radio-udp");
        listenerThread.setDaemon(true);
        listenerThread.start();
        System.err.printf("[RADIO-UDP] Joined %s:%d as device 0x%08X%n",
                group.getHostAddress(), port, deviceId);
    }

    @Override
    public void sendPacket(byte[] data, int channel) {
        if (!running || socket == null) return;
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + data.length);
        buf.putInt(deviceId);
        buf.put((byte) channel);
        buf.put(data);
        try {
            DatagramPacket pkt = new DatagramPacket(
                    buf.array(), buf.array().length, group, port);
            socket.send(pkt);
        } catch (IOException e) {
            System.err.println("[RADIO-UDP] Send error: " + e.getMessage());
        }
    }

    @Override
    public void setPacketListener(PacketListener listener) {
        this.listener = listener;
    }

    @Override
    public void setChannel(int channel) {
        this.currentChannel = channel;
    }

    @Override
    public int getDeviceId() {
        return deviceId;
    }

    @Override
    public void close() {
        running = false;
        if (socket != null) {
            socket.close();
        }
        if (listenerThread != null) {
            listenerThread.interrupt();
        }
    }

    private void listenLoop() {
        byte[] buf = new byte[1500];
        while (running) {
            try {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                socket.receive(pkt);
                if (pkt.getLength() < HEADER_SIZE) continue;

                ByteBuffer bb = ByteBuffer.wrap(
                        pkt.getData(), pkt.getOffset(), pkt.getLength());
                int senderId = bb.getInt();
                int channel = bb.get() & 0xFF;

                // Filter own packets only. Channel filtering is handled by CyOS
                // at the protocol layer (state=5 / MRA=0x00 discard for mismatched
                // channels). We don't filter by channel here because CyOS channel-hops
                // during peer discovery (ch=2↔ch=4) and UDP latency causes frames to
                // arrive after the channel has already changed. On real hardware,
                // RF signals arrive in microseconds; UDP takes milliseconds.
                if (senderId == deviceId) continue;

                byte[] payload = new byte[pkt.getLength() - HEADER_SIZE];
                bb.get(payload);

                System.err.printf("[RADIO-UDP] RX from 0x%08X ch=%d %d bytes%n",
                        senderId, channel, payload.length);
                PacketListener l = listener;
                if (l != null) {
                    l.onPacketReceived(payload, channel, senderId);
                }
            } catch (IOException e) {
                if (running) {
                    System.err.println("[RADIO-UDP] Receive error: " + e.getMessage());
                }
            }
        }
    }
}
