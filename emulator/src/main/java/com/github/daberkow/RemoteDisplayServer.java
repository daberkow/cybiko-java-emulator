package com.github.daberkow;

import java.io.*;
import java.net.*;

/**
 * TCP server for remote display/keyboard.
 * Streams raw VRAM frames to a connected client and receives key events.
 *
 * Protocol (all multi-byte values little-endian for ESP32):
 *   Server->Client:
 *     0x01 + 4000 bytes VRAM (2-bit packed grayscale)
 *   Client->Server:
 *     0x10 + uint8 column + uint16_LE bitmask  (key down)
 *     0x11 + uint8 column + uint16_LE bitmask  (key up)
 */
public class RemoteDisplayServer implements FrameBufferRenderer {
    private static final byte MSG_FRAME = 0x01;
    private static final byte MSG_KEY_DOWN = 0x10;
    private static final byte MSG_KEY_UP = 0x11;

    private final int port;
    private final AddressBus bus;
    private final HD66421Lcd lcd;
    private ServerSocket serverSocket;
    private Socket clientSocket;
    private OutputStream clientOut;
    private InputStream clientIn;
    private Thread acceptThread;
    private Thread readThread;
    private volatile boolean running = false;

    // Frame buffer: header byte + 4000 bytes VRAM
    private final byte[] framePacket = new byte[1 + HD66421Lcd.VRAM_SIZE];

    public RemoteDisplayServer(int port, AddressBus bus, HD66421Lcd lcd) {
        this.port = port;
        this.bus = bus;
        this.lcd = lcd;
        framePacket[0] = MSG_FRAME;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        Log.log(Log.Category.BOOT, "[REMOTE] Listening on port %d", port);

        acceptThread = new Thread(this::acceptLoop, "remote-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                disconnectClient();
                clientSocket = socket;
                clientOut = new BufferedOutputStream(socket.getOutputStream(), 8192);
                clientIn = socket.getInputStream();
                socket.setTcpNoDelay(true);
                Log.log(Log.Category.BOOT, "[REMOTE] Client connected: %s",
                    socket.getRemoteSocketAddress());

                readThread = new Thread(this::readLoop, "remote-read");
                readThread.setDaemon(true);
                readThread.start();
            } catch (IOException e) {
                if (running) {
                    System.err.println("[REMOTE] Accept error: " + e.getMessage());
                }
            }
        }
    }

    private void readLoop() {
        try {
            while (running && clientSocket != null && !clientSocket.isClosed()) {
                int msgType = clientIn.read();
                if (msgType < 0) break;

                if (msgType == MSG_KEY_DOWN || msgType == MSG_KEY_UP) {
                    int col = clientIn.read();
                    int lo = clientIn.read();
                    int hi = clientIn.read();
                    if (col < 0 || lo < 0 || hi < 0) break;
                    int bitmask = (hi << 8) | lo; // little-endian
                    bus.setKeyState(col, bitmask, msgType == MSG_KEY_DOWN);
                }
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("[REMOTE] Read error: " + e.getMessage());
            }
        }
        disconnectClient();
    }

    @Override
    public void render(int[] pixels, int width, int height) {
        if (clientOut == null) return;

        // Copy raw VRAM directly - no re-quantization needed
        System.arraycopy(lcd.getVram(), 0, framePacket, 1, HD66421Lcd.VRAM_SIZE);

        try {
            clientOut.write(framePacket);
            clientOut.flush();
        } catch (IOException e) {
            disconnectClient();
        }
    }

    private synchronized void disconnectClient() {
        try { if (clientOut != null) clientOut.close(); } catch (IOException ignored) {}
        try { if (clientIn != null) clientIn.close(); } catch (IOException ignored) {}
        try { if (clientSocket != null) clientSocket.close(); } catch (IOException ignored) {}
        clientOut = null;
        clientIn = null;
        clientSocket = null;
    }

    @Override
    public void close() {
        running = false;
        disconnectClient();
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
    }
}
