# Emulator Extensions Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add file menu with NVRAM management, remote display/keyboard for ESP32 T-Deck Plus over WiFi TCP, and prepare architecture for RF2915 radio emulation.

**Architecture:** Thread the emulator loop for GUI interaction. Add a TCP server that streams raw VRAM frames and receives keyboard events. Create a PlatformIO ESP32 project that connects as a thin display/input client. Feature C (radio) is research-only in this plan.

**Tech Stack:** Java 21 (Swing, java.net, java.nio), PlatformIO/Arduino (ESP32-S3, TFT_eSPI, WiFi)

---

### Task 1: Thread the Emulator Loop

Currently `CybikoEmulator.start()` calls `run()` synchronously, blocking forever.
We need it to return immediately so the Swing EDT can handle menus.

**Files:**
- Modify: `emulator/src/main/java/com/github/daberkow/CybikoEmulator.java`

**Step 1: Add state tracking and listener interface**

Add above the constructor (after line 32):

```java
private Thread emulatorThread;

public interface StateListener {
    void onStateChanged(boolean running);
}
private StateListener stateListener;
public void setStateListener(StateListener listener) { this.stateListener = listener; }
public boolean isRunning() { return running; }
```

**Step 2: Make start() non-blocking**

Replace the `start()` method (lines 158-169):

```java
public void start() {
    if (running) return;
    cpu.reset();

    System.out.println("=== Initial state (" + config.name + ") ===");
    cpu.dumpRegisters();
    System.out.printf("Reset vector: 0x%08X%n", bus.read32(0x000000));
    System.out.println();

    running = true;
    emulatorThread = new Thread(this::run, "emulator");
    emulatorThread.setDaemon(true);
    emulatorThread.start();
    if (stateListener != null) stateListener.onStateChanged(true);
}
```

**Step 3: Notify listener on stop**

At the end of the `run()` method (line 299, after the final `cpu.dumpRegisters()`), add:

```java
if (stateListener != null) {
    javax.swing.SwingUtilities.invokeLater(() -> stateListener.onStateChanged(false));
}
```

**Step 4: Make saveNvram and nvramPath accessible**

Change `saveNvram()` from `private` to `public` (line 307).
Add a setter for nvramPath:

```java
public void setNvramPath(Path path) { this.nvramPath = path; }
public Path getNvramPath() { return nvramPath; }
```

**Step 5: Update main() to not block on start**

In `main()`, after `emu.start()` (line 482), the method currently returns (since start
blocked). Now start() returns immediately, so main() needs to wait. Add after `emu.start()`:

```java
// Wait for emulator thread to finish (it's a daemon, so JVM can still exit)
try {
    emu.emulatorThread.join();
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
}
```

**Step 6: Verify it compiles and runs**

Run: `./gradlew :emulator:build`
Then: `./gradlew :emulator:run --args="src/main/resources/cybikoxt/cyrom150.bin src/main/resources/cybikoxt/cyos_v1508.bin"`
Expected: Same behavior as before (boots to CyOS).

**Step 7: Commit**

```bash
git add emulator/src/main/java/com/github/daberkow/CybikoEmulator.java
git commit -m "feat: thread emulator loop for non-blocking start()"
```

---

### Task 2: Add Menu Bar to SwingRenderer

**Files:**
- Modify: `emulator/src/main/java/com/github/daberkow/SwingRenderer.java`

**Step 1: Add menu bar infrastructure**

Add imports at top:
```java
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.InputEvent;
```

Add fields after the `heldCount` field (after line 41):
```java
// Menu bar
private JMenuBar menuBar;
private JMenuItem openNvramItem;
private JMenuItem saveNvramAsItem;
private JMenuItem startStopItem;
```

**Step 2: Build the menu bar**

Add a method after the constructor:

```java
public void buildMenuBar(ActionListener onOpenNvram, ActionListener onSaveNvramAs,
                         ActionListener onStartStop, ActionListener onQuit) {
    menuBar = new JMenuBar();

    JMenu fileMenu = new JMenu("File");
    fileMenu.setMnemonic('F');

    openNvramItem = new JMenuItem("Open NVRAM...");
    openNvramItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O,
        Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
    openNvramItem.addActionListener(onOpenNvram);
    fileMenu.add(openNvramItem);

    saveNvramAsItem = new JMenuItem("Save NVRAM As...");
    saveNvramAsItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S,
        Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | InputEvent.SHIFT_DOWN_MASK));
    saveNvramAsItem.addActionListener(onSaveNvramAs);
    fileMenu.add(saveNvramAsItem);

    fileMenu.addSeparator();

    JMenuItem quitItem = new JMenuItem("Quit");
    quitItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Q,
        Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
    quitItem.addActionListener(onQuit);
    fileMenu.add(quitItem);

    JMenu emuMenu = new JMenu("Emulator");
    emuMenu.setMnemonic('E');

    startStopItem = new JMenuItem("Start");
    startStopItem.addActionListener(onStartStop);
    emuMenu.add(startStopItem);

    menuBar.add(fileMenu);
    menuBar.add(emuMenu);
    frame.setJMenuBar(menuBar);
    frame.pack(); // repack to accommodate menu bar height
}
```

**Step 3: Add state update methods**

```java
public void updateMenuState(boolean emulatorRunning) {
    openNvramItem.setEnabled(!emulatorRunning);
    saveNvramAsItem.setEnabled(emulatorRunning);
    startStopItem.setText(emulatorRunning ? "Stop" : "Start");
}
```

**Step 4: Verify it compiles**

Run: `./gradlew :emulator:build`

**Step 5: Commit**

```bash
git add emulator/src/main/java/com/github/daberkow/SwingRenderer.java
git commit -m "feat: add menu bar to SwingRenderer"
```

---

### Task 3: Wire Menu Actions in main()

Connect the menu bar to the emulator lifecycle and NVRAM operations.

**Files:**
- Modify: `emulator/src/main/java/com/github/daberkow/CybikoEmulator.java` (main method)

**Step 1: Restructure main() for menu-driven control**

Replace the end of main() (from the renderer setup at line 468 through end of method)
with menu wiring. The key change: `emu.start()` is now triggered by menu or auto-started.

After the renderer is created (`SwingRenderer swing = ...`), add:

```java
if (!headless) {
    SwingRenderer swing = new SwingRenderer(config);
    swing.setBus(emu.getBus());
    emu.setRenderer(swing);

    // Wire menu bar
    swing.buildMenuBar(
        // Open NVRAM
        e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "NVRAM files", "nvram", "bin", "nv"));
            if (fc.showOpenDialog(swing.getFrame()) == JFileChooser.APPROVE_OPTION) {
                try {
                    Path path = fc.getSelectedFile().toPath();
                    byte[] nvData = Files.readAllBytes(path);
                    emu.getExternalRam().load(nvData, 0);
                    emu.setNvramPath(path);
                    System.out.printf("Loaded NVRAM: %s (%d bytes)%n", path, nvData.length);
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(swing.getFrame(),
                        "Error loading NVRAM: " + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        },
        // Save NVRAM As
        e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "NVRAM files", "nvram", "bin", "nv"));
            if (fc.showSaveDialog(swing.getFrame()) == JFileChooser.APPROVE_OPTION) {
                try {
                    Path path = fc.getSelectedFile().toPath();
                    Files.write(path, emu.getExternalRam().getRawData());
                    emu.setNvramPath(path);
                    System.out.printf("Saved NVRAM: %s%n", path);
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(swing.getFrame(),
                        "Error saving NVRAM: " + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        },
        // Start/Stop
        e -> {
            if (emu.isRunning()) {
                emu.stop();
            } else {
                emu.start();
            }
        },
        // Quit
        e -> {
            emu.stop();
            emu.saveNvram();
            System.exit(0);
        }
    );

    // Listen for state changes to update menu
    emu.setStateListener(running -> swing.updateMenuState(running));
    swing.updateMenuState(false); // initial state

    // Auto-start if ROMs are loaded
    emu.start();

    // Keep main thread alive (daemon emulator thread needs this)
    try {
        emu.emulatorThread.join();
    } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
    }
} else {
    emu.setRenderer(new ConsoleRenderer());
    emu.start();
    try {
        emu.emulatorThread.join();
    } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
    }
}
```

Note: The `emu.emulatorThread` field needs to be package-private or have a getter.
Add to CybikoEmulator:
```java
public Thread getEmulatorThread() { return emulatorThread; }
```

**Step 2: Remove the old shutdown hook / renderer setup code**

The old code from line 468-482 is replaced by the above. Delete the old `emu.start()` call
and the old renderer setup block. Keep the shutdown hook for NVRAM save.

**Step 3: Verify it compiles and runs with menu**

Run: `./gradlew :emulator:run --args="src/main/resources/cybikoxt/cyrom150.bin src/main/resources/cybikoxt/cyos_v1508.bin"`
Expected: Window appears with File and Emulator menus. Emulator auto-starts.
File > Quit exits. Emulator > Stop stops emulation. Emulator > Start restarts.

**Step 4: Commit**

```bash
git add emulator/src/main/java/com/github/daberkow/CybikoEmulator.java
git commit -m "feat: wire menu bar to emulator lifecycle and NVRAM operations"
```

---

### Task 4: Add NVRAM Auto-Save

**Files:**
- Modify: `emulator/src/main/java/com/github/daberkow/CybikoEmulator.java`

**Step 1: Add auto-save to the run loop**

Add a constant near the top of the class:
```java
private static final int AUTOSAVE_INTERVAL_FRAMES = 60 * 300; // 5 minutes at 60fps
```

In `run()`, after `frameCounter++` (line 257), add:
```java
// Auto-save NVRAM every 5 minutes
if (nvramPath != null && frameCounter % AUTOSAVE_INTERVAL_FRAMES == 0) {
    saveNvram();
}
```

**Step 2: Verify auto-save doesn't break timing**

Run emulator for a few minutes, check STATUS log for frame timing.
The `saveNvram()` writes 2MB to disk which should take <50ms (well within frame budget
since it happens between frames).

**Step 3: Commit**

```bash
git add emulator/src/main/java/com/github/daberkow/CybikoEmulator.java
git commit -m "feat: auto-save NVRAM every 5 minutes"
```

---

### Task 5: Create RemoteDisplayServer (Java TCP Server)

This is the Java-side server that streams VRAM frames and receives keyboard events.

**Files:**
- Create: `emulator/src/main/java/com/github/daberkow/RemoteDisplayServer.java`

**Step 1: Write the server class**

```java
package com.github.daberkow;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;

/**
 * TCP server for remote display/keyboard.
 * Streams raw VRAM frames to a connected client and receives key events.
 *
 * Protocol (all multi-byte values little-endian for ESP32):
 *   Server→Client:
 *     0x01 + 4000 bytes VRAM (2-bit packed grayscale)
 *   Client→Server:
 *     0x10 + uint8 column + uint16_LE bitmask  (key down)
 *     0x11 + uint8 column + uint16_LE bitmask  (key up)
 */
public class RemoteDisplayServer implements FrameBufferRenderer {
    private static final byte MSG_FRAME = 0x01;
    private static final byte MSG_KEY_DOWN = 0x10;
    private static final byte MSG_KEY_UP = 0x11;

    private final int port;
    private final AddressBus bus;
    private ServerSocket serverSocket;
    private Socket clientSocket;
    private OutputStream clientOut;
    private InputStream clientIn;
    private Thread acceptThread;
    private Thread readThread;
    private volatile boolean running = false;

    // Frame buffer: header byte + 4000 bytes VRAM
    private final byte[] framePacket = new byte[1 + HD66421Lcd.VRAM_SIZE];

    public RemoteDisplayServer(int port, AddressBus bus) {
        this.port = port;
        this.bus = bus;
        framePacket[0] = MSG_FRAME;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        System.err.printf("[REMOTE] Listening on port %d%n", port);

        acceptThread = new Thread(this::acceptLoop, "remote-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                // Close previous client if any
                disconnectClient();
                clientSocket = socket;
                clientOut = new BufferedOutputStream(socket.getOutputStream(), 8192);
                clientIn = socket.getInputStream();
                socket.setTcpNoDelay(true);
                System.err.printf("[REMOTE] Client connected: %s%n",
                    socket.getRemoteSocketAddress());

                // Start reading key events from client
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
        byte[] buf = new byte[4];
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

        // Convert grayscale pixels (0-255) back to 2-bit packed VRAM format
        // 4 pixels per byte, 2 bits each, matching HD66421 VRAM layout
        // pixels[] is already in screen order from getFrameBuffer()
        // We need VRAM order: bottom-to-top, left-to-right
        int vramIdx = 1; // skip header byte
        for (int y = height - 1; y >= 0; y--) {
            for (int x = 0; x < width; x += 4) {
                int base = y * width + x;
                int b = (quantize2bit(pixels[base]) << 6)
                       | (quantize2bit(pixels[base + 1]) << 4)
                       | (quantize2bit(pixels[base + 2]) << 2)
                       | quantize2bit(pixels[base + 3]);
                framePacket[vramIdx++] = (byte) b;
            }
        }

        try {
            clientOut.write(framePacket);
            clientOut.flush();
        } catch (IOException e) {
            disconnectClient();
        }
    }

    /** Quantize a 0-255 grayscale value to 2-bit (0-3). */
    private static int quantize2bit(int gray) {
        if (gray >= 192) return 0; // white
        if (gray >= 128) return 1; // light gray
        if (gray >= 64) return 2;  // dark gray
        return 3;                   // black
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
```

**Step 2: Verify it compiles**

Run: `./gradlew :emulator:build`

**Step 3: Commit**

```bash
git add emulator/src/main/java/com/github/daberkow/RemoteDisplayServer.java
git commit -m "feat: add RemoteDisplayServer for ESP32 remote display/keyboard"
```

---

### Task 6: Add Multi-Renderer Support

The emulator currently supports a single `FrameBufferRenderer`. We need both
SwingRenderer and RemoteDisplayServer to receive frames simultaneously.

**Files:**
- Create: `emulator/src/main/java/com/github/daberkow/MultiRenderer.java`
- Modify: `emulator/src/main/java/com/github/daberkow/CybikoEmulator.java`

**Step 1: Create MultiRenderer**

```java
package com.github.daberkow;

/**
 * Multiplexes frame rendering to multiple FrameBufferRenderer instances.
 */
public class MultiRenderer implements FrameBufferRenderer {
    private final FrameBufferRenderer[] renderers;

    public MultiRenderer(FrameBufferRenderer... renderers) {
        this.renderers = renderers;
    }

    @Override
    public void render(int[] pixels, int width, int height) {
        for (FrameBufferRenderer r : renderers) {
            r.render(pixels, width, height);
        }
    }

    @Override
    public void close() {
        for (FrameBufferRenderer r : renderers) {
            r.close();
        }
    }
}
```

**Step 2: Add --remote-display flag to main()**

In `CybikoEmulator.main()`, add to argument parsing (after the `--mute` case):
```java
case "--remote-display" -> { if (i + 1 < args.length) remotePort = Integer.parseInt(args[++i]); }
```

Add variable declaration near the other argument variables:
```java
int remotePort = 0;
```

In the renderer setup, if remotePort > 0, wrap with MultiRenderer:
```java
if (remotePort > 0) {
    RemoteDisplayServer remote = new RemoteDisplayServer(remotePort, emu.getBus());
    try {
        remote.start();
    } catch (IOException ex) {
        System.err.println("Error starting remote display: " + ex.getMessage());
    }
    if (!headless) {
        emu.setRenderer(new MultiRenderer(swing, remote));
    } else {
        emu.setRenderer(remote);
    }
}
```

Also add to usage text:
```java
System.out.println("  --remote-display <port> - Enable TCP remote display server");
```

**Step 3: Verify it compiles and test with netcat**

Run: `./gradlew :emulator:run --args="src/main/resources/cybikoxt/cyrom150.bin src/main/resources/cybikoxt/cyos_v1508.bin --remote-display 6502"`
In another terminal: `nc localhost 6502 | xxd | head` — should see 0x01 followed by VRAM data.

**Step 4: Commit**

```bash
git add emulator/src/main/java/com/github/daberkow/MultiRenderer.java \
        emulator/src/main/java/com/github/daberkow/CybikoEmulator.java
git commit -m "feat: add --remote-display flag with multi-renderer support"
```

---

### Task 7: Create ESP32 PlatformIO Project Structure

Create the ESP32 remote client project. This is a PlatformIO/Arduino project for
the T-Deck Plus that connects to the emulator's TCP server.

**Files:**
- Create: `esp32-remote/platformio.ini`
- Create: `esp32-remote/src/main.cpp`
- Create: `esp32-remote/src/config.h`

**Step 1: Create platformio.ini**

```ini
[env:tdeck]
platform = espressif32@6.3.0
board = lilygo-t-deck
framework = arduino
monitor_speed = 115200
build_flags =
    -DBOARD_HAS_PSRAM=1
    -DARDUINO_LOOP_STACK_SIZE=16384
    -O2
lib_deps =
    TFT_eSPI

; WiFi and server config — set via environment or edit directly
build_flags_extra =
    -DWIFI_SSID=\"${sysenv.WIFI_SSID}\"
    -DWIFI_PASS=\"${sysenv.WIFI_PASS}\"
    -DSERVER_IP=\"192.168.1.100\"
    -DSERVER_PORT=6502
```

**Step 2: Create config.h with T-Deck pin definitions**

```cpp
#pragma once

// T-Deck Plus pin definitions
#define PIN_TFT_CS    12
#define PIN_TFT_DC    11
#define PIN_TFT_BL    42
#define PIN_TFT_SCLK  40
#define PIN_TFT_MOSI  41
#define PIN_TFT_MISO  38

// Keyboard (I2C slave at 0x55)
#define PIN_KB_SDA    18
#define PIN_KB_SCL    8
#define KB_I2C_ADDR   0x55

// Trackball
#define PIN_TBALL_UP    3
#define PIN_TBALL_DOWN  15
#define PIN_TBALL_LEFT  1
#define PIN_TBALL_RIGHT 2
#define PIN_TBALL_CLICK 0

// Display dimensions
#define CYBIKO_W 160
#define CYBIKO_H 100
#define SCALE    2
#define SCALED_W (CYBIKO_W * SCALE)
#define SCALED_H (CYBIKO_H * SCALE)

// Protocol message types
#define MSG_FRAME    0x01
#define MSG_KEY_DOWN 0x10
#define MSG_KEY_UP   0x11
```

**Step 3: Create main.cpp**

```cpp
#include <Arduino.h>
#include <WiFi.h>
#include <Wire.h>
#include <TFT_eSPI.h>
#include "config.h"

// WiFi credentials — set via build flags or override here
#ifndef WIFI_SSID
#define WIFI_SSID "your_ssid"
#endif
#ifndef WIFI_PASS
#define WIFI_PASS "your_password"
#endif
#ifndef SERVER_IP
#define SERVER_IP "192.168.1.100"
#endif
#ifndef SERVER_PORT
#define SERVER_PORT 6502
#endif

static TFT_eSPI tft;
static WiFiClient client;

// VRAM buffer (4000 bytes = 160x100 at 2bpp)
static uint8_t vram_buf[4000];

// Display buffer in PSRAM (320x200 pixels, RGB565)
static uint16_t *display_buf;

// Grayscale LUT: 4 shades → RGB565
// Matching the Cybiko's 2-bit palette (0=white, 3=black)
static const uint16_t gray_lut[4] = {
    0xFFFF, // 0: white
    0xAD55, // 1: light gray
    0x5AAB, // 2: dark gray
    0x0000  // 3: black
};

// Keyboard ASCII → Cybiko matrix mapping (from C emulator)
typedef struct {
    uint8_t  ascii;
    uint8_t  col;
    uint16_t mask;
    bool     is_fn_combo;  // true = needs Fn held
} key_map_entry_t;

static const key_map_entry_t key_map[] = {
    // Letters
    {'a', 3, 0x0004, false}, {'b', 1, 0x0004, false}, {'c', 2, 0x0200, false},
    {'d', 2, 0x0100, false}, {'e', 3, 0x0080, false}, {'f', 2, 0x1000, false},
    {'g', 1, 0x0002, false}, {'h', 1, 0x0010, false}, {'i', 0, 0x1000, false},
    {'j', 1, 0x0080, false}, {'k', 0, 0x0800, false}, {'l', 0, 0x4000, false},
    {'m', 0, 0x0100, false}, {'n', 1, 0x0008, false}, {'o', 0, 0x2000, false},
    {'p', 9, 0x0010, false}, {'q', 3, 0x0002, false}, {'r', 2, 0x2000, false},
    {'s', 3, 0x0020, false}, {'t', 2, 0x4000, false}, {'u', 1, 0x0040, false},
    {'v', 2, 0x0800, false}, {'w', 3, 0x0040, false}, {'x', 3, 0x0010, false},
    {'y', 1, 0x0020, false}, {'z', 3, 0x0008, false},
    // Numbers (Fn+letter combos on XT)
    {'1', 3, 0x0002, true},  {'2', 3, 0x0040, true},  {'3', 3, 0x0080, true},
    {'4', 2, 0x2000, true},  {'5', 2, 0x4000, true},  {'6', 1, 0x0020, true},
    {'7', 1, 0x0040, true},  {'8', 0, 0x1000, true},  {'9', 0, 0x2000, true},
    {'0', 9, 0x0010, true},
    // Control keys
    {'\n', 4, 0x0008, false},  // Enter
    {'\b', 5, 0x0100, false},  // Backspace/Del
    {' ',  4, 0x0040, false},  // Space
    {'\t', 5, 0x0080, false},  // Tab
    {0x1B, 5, 0x0400, false},  // Escape
    {'.', 9, 0x0002, false},   // Period
    {';', 9, 0x0008, false},   // Semicolon
};
static const int KEY_MAP_SIZE = sizeof(key_map) / sizeof(key_map[0]);

// Key hold state
static uint8_t  held_col = 0;
static uint16_t held_mask = 0;
static int      held_frames = 0;
static bool     fn_held = false;

// --- Network ---

static void send_key_event(uint8_t msg_type, uint8_t col, uint16_t mask) {
    if (!client.connected()) return;
    uint8_t buf[4] = {msg_type, col, (uint8_t)(mask & 0xFF), (uint8_t)(mask >> 8)};
    client.write(buf, 4);
}

static bool connect_to_server() {
    Serial.printf("Connecting to %s:%d...\n", SERVER_IP, SERVER_PORT);
    if (client.connect(SERVER_IP, SERVER_PORT)) {
        client.setNoDelay(true);
        Serial.println("Connected!");
        return true;
    }
    Serial.println("Connection failed");
    return false;
}

// --- Display ---

static void render_vram() {
    // Convert 2-bit packed VRAM to RGB565 with 2x upscaling
    // VRAM is bottom-to-top, left-to-right (byte 0 = row 99)
    for (int vy = 0; vy < CYBIKO_H; vy++) {
        int screen_y = vy; // VRAM row 0 = screen bottom (row 99), but we invert
        int vram_row = (CYBIKO_H - 1 - vy);
        for (int vx = 0; vx < CYBIKO_W / 4; vx++) {
            uint8_t byte_val = vram_buf[vram_row * (CYBIKO_W / 4) + vx];
            for (int px = 0; px < 4; px++) {
                int shade = (byte_val >> (6 - px * 2)) & 0x03;
                uint16_t color = gray_lut[shade];
                int sx = (vx * 4 + px) * SCALE;
                int sy = screen_y * SCALE;
                // 2x2 pixel block
                for (int dy = 0; dy < SCALE; dy++) {
                    for (int dx = 0; dx < SCALE; dx++) {
                        display_buf[(sy + dy) * SCALED_W + sx + dx] = color;
                    }
                }
            }
        }
    }
    tft.pushImage(0, 20, SCALED_W, SCALED_H, display_buf); // 20px top border
}

// --- Keyboard ---

static void poll_keyboard() {
    Wire.requestFrom(KB_I2C_ADDR, 1);
    if (Wire.available()) {
        uint8_t ch = Wire.read();
        if (ch == 0) return; // no key

        // Find in map
        for (int i = 0; i < KEY_MAP_SIZE; i++) {
            if (key_map[i].ascii == ch) {
                // Release previous key if held
                if (held_mask != 0) {
                    send_key_event(MSG_KEY_UP, held_col, held_mask);
                    if (fn_held) {
                        send_key_event(MSG_KEY_UP, 7, 0x8000); // release Fn
                        fn_held = false;
                    }
                }
                // Press new key
                if (key_map[i].is_fn_combo) {
                    send_key_event(MSG_KEY_DOWN, 7, 0x8000); // press Fn
                    fn_held = true;
                    delay(50); // brief delay for CyOS to see Fn
                }
                send_key_event(MSG_KEY_DOWN, key_map[i].col, key_map[i].mask);
                held_col = key_map[i].col;
                held_mask = key_map[i].mask;
                held_frames = 4; // hold for ~67ms
                return;
            }
        }
    }
}

static void poll_trackball() {
    // Trackball pins are active LOW
    struct { int pin; uint8_t col; uint16_t mask; } dirs[] = {
        {PIN_TBALL_UP,    6, 0x0800},
        {PIN_TBALL_DOWN,  6, 0x2000},
        {PIN_TBALL_LEFT,  6, 0x4000},
        {PIN_TBALL_RIGHT, 6, 0x1000},
        {PIN_TBALL_CLICK, 4, 0x0010}, // Select
    };
    for (auto &d : dirs) {
        if (digitalRead(d.pin) == LOW) {
            send_key_event(MSG_KEY_DOWN, d.col, d.mask);
            delay(100);
            send_key_event(MSG_KEY_UP, d.col, d.mask);
        }
    }
}

// --- Arduino setup/loop ---

void setup() {
    Serial.begin(115200);
    Serial.println("Cybiko Remote Display");

    // Allocate display buffer in PSRAM
    display_buf = (uint16_t *)ps_malloc(SCALED_W * SCALED_H * sizeof(uint16_t));
    if (!display_buf) {
        Serial.println("FATAL: PSRAM alloc failed");
        while (1) delay(1000);
    }

    // Init I2C for keyboard
    Wire.begin(PIN_KB_SDA, PIN_KB_SCL);
    delay(500); // keyboard startup time

    // Init trackball
    pinMode(PIN_TBALL_UP, INPUT_PULLUP);
    pinMode(PIN_TBALL_DOWN, INPUT_PULLUP);
    pinMode(PIN_TBALL_LEFT, INPUT_PULLUP);
    pinMode(PIN_TBALL_RIGHT, INPUT_PULLUP);
    pinMode(PIN_TBALL_CLICK, INPUT_PULLUP);

    // Init TFT
    tft.init();
    tft.setRotation(1);
    tft.fillScreen(TFT_BLACK);
    tft.setSwapBytes(true);
    pinMode(PIN_TFT_BL, OUTPUT);
    digitalWrite(PIN_TFT_BL, HIGH);

    // Connect WiFi
    WiFi.begin(WIFI_SSID, WIFI_PASS);
    tft.setCursor(10, 10);
    tft.setTextColor(TFT_WHITE);
    tft.printf("Connecting to WiFi...");
    while (WiFi.status() != WL_CONNECTED) {
        delay(500);
        Serial.print(".");
    }
    Serial.printf("\nWiFi connected: %s\n", WiFi.localIP().toString().c_str());
    tft.fillScreen(TFT_BLACK);
    tft.setCursor(10, 10);
    tft.printf("IP: %s", WiFi.localIP().toString().c_str());
    tft.setCursor(10, 30);
    tft.printf("Connecting to %s:%d", SERVER_IP, SERVER_PORT);
}

void loop() {
    // Reconnect if needed
    if (!client.connected()) {
        if (!connect_to_server()) {
            delay(2000);
            return;
        }
    }

    // Read frames from server
    if (client.available() >= 1) {
        uint8_t msg_type = client.read();
        if (msg_type == MSG_FRAME) {
            // Read 4000 bytes of VRAM
            int remaining = 4000;
            int offset = 0;
            while (remaining > 0 && client.connected()) {
                int n = client.read(vram_buf + offset, remaining);
                if (n > 0) {
                    offset += n;
                    remaining -= n;
                } else if (n < 0) {
                    break;
                }
            }
            if (remaining == 0) {
                render_vram();
            }
        }
    }

    // Poll keyboard and trackball
    poll_keyboard();
    poll_trackball();

    // Release held key after timeout
    if (held_frames > 0 && --held_frames == 0 && held_mask != 0) {
        send_key_event(MSG_KEY_UP, held_col, held_mask);
        held_mask = 0;
        if (fn_held) {
            send_key_event(MSG_KEY_UP, 7, 0x8000);
            fn_held = false;
        }
    }
}
```

**Step 4: Verify the project structure is correct**

```bash
ls esp32-remote/platformio.ini esp32-remote/src/main.cpp esp32-remote/src/config.h
```

**Step 5: Commit**

```bash
git add esp32-remote/
git commit -m "feat: add ESP32 remote display client for T-Deck Plus"
```

---

### Task 8: Test End-to-End with Simple TCP Client

Before flashing the ESP32, verify the Java server works correctly with a simple
Python test client.

**Files:**
- Create: `tools/test_remote_display.py`

**Step 1: Write a test client**

```python
#!/usr/bin/env python3
"""Test client for Cybiko remote display server.
Connects to the emulator, receives frames, and prints frame stats.
Press arrow keys to send key events.
"""
import socket
import struct
import sys
import time

HOST = sys.argv[1] if len(sys.argv) > 1 else "localhost"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 6502

MSG_FRAME = 0x01
MSG_KEY_DOWN = 0x10
MSG_KEY_UP = 0x11
VRAM_SIZE = 4000

def recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("Server disconnected")
        buf += chunk
    return buf

def main():
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.connect((HOST, PORT))
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    print(f"Connected to {HOST}:{PORT}")

    frame_count = 0
    start_time = time.time()

    try:
        while True:
            msg_type = sock.recv(1)
            if not msg_type:
                break
            if msg_type[0] == MSG_FRAME:
                vram = recv_exact(sock, VRAM_SIZE)
                frame_count += 1
                if frame_count % 60 == 0:
                    elapsed = time.time() - start_time
                    fps = frame_count / elapsed
                    # Count non-zero bytes as rough "content" metric
                    nonzero = sum(1 for b in vram if b != 0)
                    print(f"Frame {frame_count}: {fps:.1f} fps, {nonzero}/4000 non-zero bytes")
    except KeyboardInterrupt:
        print(f"\n{frame_count} frames received")
    finally:
        sock.close()

if __name__ == "__main__":
    main()
```

**Step 2: Test**

Terminal 1: `./gradlew :emulator:run --args="src/main/resources/cybikoxt/cyrom150.bin src/main/resources/cybikoxt/cyos_v1508.bin --remote-display 6502"`
Terminal 2: `python3 tools/test_remote_display.py`

Expected: ~60 fps, increasing non-zero byte count as CyOS draws to screen.

**Step 3: Commit**

```bash
git add tools/test_remote_display.py
git commit -m "test: add Python test client for remote display server"
```

---

### Task 9: Feature C Research — RF2915 Analysis

This task is research only. No code changes. Document findings for future implementation.

**Files:**
- Create: `docs/rf2915-research.md`

**Step 1: Search MAME source for RF2915 / radio references**

Look in `../mame/src/mame/cybiko/cybiko.cpp` and related files for:
- How the RF chip is connected (SPI? which SCI channel?)
- Any register stubs or pin connections
- The cybiko machine driver's handling of radio

**Step 2: Search for RF2915 datasheet information**

The RFMD RF2915 is a 900MHz ISM band transceiver. Key info needed:
- SPI register map (command format, register addresses)
- Init sequence (what CyOS writes during boot)
- TX/RX packet interface

**Step 3: Disassemble CyOS radio init code**

Use H8SDisasm to look at the code around the V2 RF init that stalls:
- The RF object at 0x202CB2 (V2 CyOS v1358)
- What registers CyOS writes during radio init
- What status values CyOS expects back

**Step 4: Document findings**

Write up everything found in `docs/rf2915-research.md`:
- RF2915 register map (as much as discoverable)
- CyOS radio init sequence
- How emulator-to-emulator networking would map to RF registers
- Gaps that need real hardware testing or SDR capture to fill

**Step 5: Commit**

```bash
git add docs/rf2915-research.md
git commit -m "docs: RF2915 radio research for future emulation"
```

---

## Implementation Notes

### Key Architectural Decisions
- **Raw VRAM over TCP**: Send the 4000-byte VRAM buffer directly rather than rendered
  pixels. The ESP32 client handles palette and scaling. This is more bandwidth-efficient
  (4KB vs 16KB per frame) and lets the client control display characteristics.
- **Little-endian protocol**: ESP32 is little-endian, Java is big-endian. The protocol
  uses LE for multi-byte values (bitmask in key events). Java side handles conversion.
- **Column/bitmask key events**: Matches the existing `AddressBus.setKeyState()` interface
  directly. No translation layer needed on the Java side.

### Testing Strategy
- Task 1-4: Manual testing — boot CyOS, verify menu works, verify auto-save
- Task 5-6: Python test client (Task 8) validates frame streaming
- Task 7: Requires actual T-Deck Plus hardware for full test
- Task 9: Research only, no testing needed

### Risk Areas
- **SwingRenderer menu + keyboard focus**: Adding a menu bar may interfere with
  keyboard event handling (menus consume key events when open). May need to call
  `frame.requestFocus()` after menu interactions.
- **ESP32 TFT_eSPI config**: The T-Deck Plus needs specific TFT_eSPI build flags
  for the ST7789 display. May need a `User_Setup.h` file in the PlatformIO lib.
- **TCP frame pacing**: If the ESP32 can't process frames fast enough, the TCP buffer
  will grow. May need frame skipping logic (server only sends if client is ready).
