package com.github.daberkow;

import java.io.*;
import java.net.Socket;

/**
 * TCP client transport for connecting to a GNU Radio flowgraph server.
 *
 * <p>Connects to a TCP server (e.g. a GNU Radio SDR bridge) and exchanges
 * radio packets using a simple length-prefixed protocol:
 * <pre>
 *   [2-byte length big-endian][1-byte channel][N-byte payload]
 * </pre>
 * where {@code length} = 1 (channel byte) + payload size.
 *
 * <p>Default connection target is {@code localhost:19201}. The transport
 * runs a daemon read thread that dispatches incoming packets to the
 * registered {@link PacketListener}. On disconnect the transport logs
 * and stops; no automatic reconnection is attempted.
 *
 * <p>Follows the same TCP networking pattern as {@link RemoteDisplayServer},
 * but operates as a client rather than a server.
 */
public class SdrTransport implements RadioTransport {
    static final String DEFAULT_HOST = "localhost";
    static final int DEFAULT_PORT = 19201;

    private final int deviceId;
    private final String host;
    private final int port;
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private PacketListener listener;
    private volatile int currentChannel = 0;
    private volatile boolean running = false;
    private Thread readerThread;

    /**
     * Create a transport using the default host and port ({@code localhost:19201}).
     *
     * @param deviceId this device's unique radio ID
     */
    public SdrTransport(int deviceId) {
        this(deviceId, DEFAULT_HOST, DEFAULT_PORT);
    }

    /**
     * Create a transport with a custom host and port.
     *
     * @param deviceId this device's unique radio ID
     * @param host     server hostname or IP address
     * @param port     server TCP port
     */
    public SdrTransport(int deviceId, String host, int port) {
        this.deviceId = deviceId;
        this.host = host;
        this.port = port;
    }

    @Override
    public void start() throws IOException {
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        running = true;

        readerThread = new Thread(this::readLoop, "radio-sdr");
        readerThread.setDaemon(true);
        readerThread.start();
        System.err.printf("[RADIO-SDR] Connected to %s:%d as device 0x%08X%n", host, port, deviceId);
    }

    @Override
    public void sendPacket(byte[] data, int channel) {
        if (!running || out == null) return;
        try {
            synchronized (out) {
                out.writeShort(data.length + 1); // length = channel byte + payload
                out.writeByte(channel);
                out.write(data);
                out.flush();
            }
        } catch (IOException e) {
            System.err.println("[RADIO-SDR] Send error: " + e.getMessage());
            close();
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
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        if (readerThread != null) readerThread.interrupt();
    }

    private void readLoop() {
        try {
            while (running) {
                int length = in.readUnsignedShort();
                if (length < 1) continue;
                int channel = in.readUnsignedByte();
                byte[] data = new byte[length - 1];
                in.readFully(data);

                if (channel != currentChannel) continue;

                PacketListener l = listener;
                if (l != null) {
                    l.onPacketReceived(data, channel, 0); // senderId unknown over TCP
                }
            }
        } catch (EOFException e) {
            if (running) System.err.println("[RADIO-SDR] Server disconnected");
        } catch (IOException e) {
            if (running) System.err.println("[RADIO-SDR] Read error: " + e.getMessage());
        }
    }
}
