package com.github.daberkow;

import java.io.*;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * PTY-based serial port for Linux. Connects SCI2 to a pseudo-terminal
 * so external tools (minicom, CyberLoad via Wine) can communicate.
 */
public class PtySerialPort implements SerialPort {
    private final FileInputStream inputStream;
    private final FileOutputStream outputStream;
    private final String slavePath;
    private final Process socatProcess; // null in explicit mode
    private final LinkedBlockingQueue<Integer> rxQueue = new LinkedBlockingQueue<>(256);
    private final Thread readerThread;
    private volatile boolean closed = false;

    private PtySerialPort(FileInputStream in, FileOutputStream out,
                          String slavePath, Process socatProcess) {
        this.inputStream = in;
        this.outputStream = out;
        this.slavePath = slavePath;
        this.socatProcess = socatProcess;

        this.readerThread = new Thread(this::readLoop, "serial-pty-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
    }

    /**
     * Auto mode: spawn socat to create a PTY pair.
     * We open the first PTY (master side). The second is for external tools.
     */
    public static PtySerialPort createAuto() throws IOException {
        ProcessBuilder pb = new ProcessBuilder("socat", "-d", "-d",
                "pty,raw,echo=0", "pty,raw,echo=0");
        pb.redirectErrorStream(true);
        Process socat = pb.start();

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(socat.getInputStream()));

        String masterPath = null, slavePath = null;
        long deadline = System.currentTimeMillis() + 5000;
        while ((masterPath == null || slavePath == null)
                && System.currentTimeMillis() < deadline) {
            String line = reader.readLine();
            if (line == null) break;
            if (line.contains("PTY is")) {
                String path = line.substring(line.indexOf("/dev/"));
                if (masterPath == null) masterPath = path.trim();
                else slavePath = path.trim();
            }
        }

        if (masterPath == null || slavePath == null) {
            socat.destroyForcibly();
            throw new IOException("Failed to create PTY pair via socat. Is socat installed?");
        }

        FileInputStream in = new FileInputStream(masterPath);
        FileOutputStream out = new FileOutputStream(masterPath);
        return new PtySerialPort(in, out, slavePath, socat);
    }

    /**
     * Explicit mode: open a user-provided PTY/device path directly.
     */
    public static PtySerialPort createExplicit(String path) throws IOException {
        FileInputStream in = new FileInputStream(path);
        FileOutputStream out = new FileOutputStream(path);
        return new PtySerialPort(in, out, path, null);
    }

    @Override
    public void write(int b) throws IOException {
        outputStream.write(b);
        outputStream.flush();
    }

    @Override
    public boolean hasData() {
        return !rxQueue.isEmpty();
    }

    @Override
    public int read() {
        Integer b = rxQueue.poll();
        return (b != null) ? b : -1;
    }

    @Override
    public void close() {
        closed = true;
        readerThread.interrupt();
        try { inputStream.close(); } catch (IOException ignored) {}
        try { outputStream.close(); } catch (IOException ignored) {}
        if (socatProcess != null) {
            socatProcess.destroyForcibly();
        }
    }

    @Override
    public String getPath() {
        return slavePath;
    }

    private void readLoop() {
        byte[] buf = new byte[256];
        try {
            while (!closed) {
                int n = inputStream.read(buf);
                if (n <= 0) break;
                for (int i = 0; i < n; i++) {
                    if (!rxQueue.offer(buf[i] & 0xFF)) {
                        rxQueue.poll(); // drop oldest on overflow
                        rxQueue.offer(buf[i] & 0xFF);
                    }
                }
            }
        } catch (IOException e) {
            if (!closed) {
                Log.log(Log.Category.SERIAL, "[SERIAL] Read error: %s", e.getMessage());
            }
        }
    }
}
