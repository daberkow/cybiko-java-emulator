package com.github.daberkow;

import java.io.IOException;

/**
 * Abstraction for serial port I/O. Implemented by PtySerialPort (Phase 1)
 * and potentially jSerialComm wrapper (Phase 4) for real hardware.
 */
public interface SerialPort {
    /** Send one byte to the external tool. */
    void write(int b) throws IOException;

    /** Check if received data is available. */
    boolean hasData();

    /** Read one byte from the receive queue. Returns -1 if empty. Non-blocking. */
    int read();

    /** Close the port and release resources. */
    void close();

    /** Return the path external tools should connect to (e.g. /dev/pts/X). */
    String getPath();
}
