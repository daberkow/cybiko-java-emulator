package com.github.daberkow;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.image.BufferedImage;

/** Swing-based display renderer with keyboard input. */
public class SwingRenderer implements FrameBufferRenderer {
    private static final int SCALE = 4;

    private final JFrame frame;
    private final BufferedImage image;
    private final int[] rgbBuffer;
    private final JPanel panel;
    private final MachineConfig config;
    private AddressBus bus;

    // Fn+letter combo for XT number keys.
    // EDT queues digits; render() processes ONE at a time through the full
    // Fn-then-letter sequence on the emulation thread (no DMA race).
    private static final int FN_PRESS_DELAY = 8;   // Frames Fn must be held alone before first letter
    private static final int FN_BETWEEN_DELAY = 3; // Frames between consecutive letters (Fn stays held)
    private static final int FN_RELEASE_HOLD = 6;  // Frames to hold Fn after last letter released
    private static final int NUM_MIN_HOLD = 6;     // Minimum frames to hold letter in matrix
    private static final int[] NUM_COLS = {9, 3, 3, 3, 2, 2, 1, 1, 0, 0};
    private static final int[] NUM_BITS = {0x0010, 0x0002, 0x0040, 0x0080, 0x2000, 0x4000, 0x0020, 0x0040, 0x1000, 0x2000};
    // EDT queue: digits waiting to be typed (circular buffer)
    private static final int NUM_QUEUE_SIZE = 16;
    private final int[] numQueue = new int[NUM_QUEUE_SIZE];
    private int numQueueHead = 0, numQueueTail = 0;
    // Current digit being processed (-1 = idle)
    private int currentDigit = -1;
    private int numHoldLeft = 0;     // hold countdown for current letter
    private boolean fnActive = false;
    private int fnDelay = 0;
    private int fnReleaseDelay = 0;

    // Lazy Shift: PC Shift key sets this flag but does NOT immediately put Shift
    // into the Cybiko keyboard matrix.  Shift is added to the matrix only when a
    // key that needs CyOS-level shifting (letters, ;, etc.) is pressed alongside it.
    // Keys that map to dedicated unshifted Cybiko keys (like Shift+1 → !) bypass
    // Shift entirely, preventing CyOS from seeing Shift+! = ^.
    private boolean pcShiftHeld = false;

    // Keyboard probe mode: F12 cycles through unknown column:bit positions.
    // Each press activates the next position for PROBE_HOLD frames.
    private static final int PROBE_HOLD = 10;
    private int probeIndex = -1;
    private int probeHoldLeft = 0;
    private int probeCol = -1, probeBit = 0;
    // Candidate positions to test (columns 9-13, unused bits)
    private static final int[][] PROBE_POSITIONS = {
        // col 9 unused bits
        {9, 0x0004}, {9, 0x0020}, {9, 0x0040}, {9, 0x0080},
        {9, 0x0100}, {9, 0x0200}, {9, 0x0400}, {9, 0x0800},
        {9, 0x1000}, {9, 0x2000}, {9, 0x4000}, {9, 0x8000},
        // col 10
        {10, 0x0001}, {10, 0x0002}, {10, 0x0004}, {10, 0x0008},
        {10, 0x0010}, {10, 0x0020}, {10, 0x0040}, {10, 0x0080},
        {10, 0x0100}, {10, 0x0200}, {10, 0x0400}, {10, 0x0800},
        {10, 0x1000}, {10, 0x2000}, {10, 0x4000}, {10, 0x8000},
        // col 11
        {11, 0x0001}, {11, 0x0002}, {11, 0x0004}, {11, 0x0008},
        {11, 0x0010}, {11, 0x0020}, {11, 0x0040}, {11, 0x0080},
        {11, 0x0100}, {11, 0x0200}, {11, 0x0400}, {11, 0x0800},
        {11, 0x1000}, {11, 0x2000}, {11, 0x4000}, {11, 0x8000},
        // col 12
        {12, 0x0001}, {12, 0x0002}, {12, 0x0004}, {12, 0x0008},
        {12, 0x0010}, {12, 0x0020}, {12, 0x0040}, {12, 0x0080},
        {12, 0x0100}, {12, 0x0200}, {12, 0x0400}, {12, 0x0800},
        {12, 0x1000}, {12, 0x2000}, {12, 0x4000}, {12, 0x8000},
        // col 13 unused bits
        {13, 0x0002}, {13, 0x0004}, {13, 0x0008}, {13, 0x0010},
        {13, 0x0020}, {13, 0x0040}, {13, 0x0080},
        {13, 0x0100}, {13, 0x0200}, {13, 0x0400}, {13, 0x0800},
        {13, 0x1000}, {13, 0x2000}, {13, 0x4000}, {13, 0x8000},
    };

    // Minimum key hold time
    private static final int MIN_HOLD_FRAMES = 3;
    private static final int MAX_HELD_KEYS = 16;
    private final int[] heldCols = new int[MAX_HELD_KEYS];
    private final int[] heldBits = new int[MAX_HELD_KEYS];
    private final int[] heldFramesLeft = new int[MAX_HELD_KEYS];
    private final boolean[] heldReleasePending = new boolean[MAX_HELD_KEYS];
    private int heldCount = 0;

    // Menu bar
    private JMenuBar menuBar;
    private JMenuItem openNvramItem;
    private JMenuItem saveNvramAsItem;
    private JMenuItem startStopItem;

    public SwingRenderer(MachineConfig config) {
        this.config = config;
        int w = HD66421Lcd.WIDTH;
        int h = HD66421Lcd.HEIGHT;
        image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        rgbBuffer = new int[w * h];

        panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g2.drawImage(image, 0, 0, w * SCALE, h * SCALE, null);
            }
        };
        panel.setPreferredSize(new Dimension(w * SCALE, h * SCALE));
        panel.setBackground(Color.BLACK);

        frame = new JFrame(config.name + " Emulator");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.add(panel);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setResizable(false);
        frame.setVisible(true);

        frame.addKeyListener(new KeyListener() {
            @Override public void keyTyped(KeyEvent e) {}
            @Override public void keyPressed(KeyEvent e) { handleKey(e.getKeyCode(), true, e.isShiftDown()); }
            @Override public void keyReleased(KeyEvent e) { handleKey(e.getKeyCode(), false, e.isShiftDown()); }
        });
        frame.setFocusable(true);
        frame.requestFocus();
    }

    /** Backward-compatible constructor (XT). */
    public SwingRenderer() {
        this(MachineConfig.forType(MachineConfig.MachineType.XT));
    }

    public void setBus(AddressBus bus) { this.bus = bus; }

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
        frame.pack();
    }

    public void updateMenuState(boolean emulatorRunning) {
        openNvramItem.setEnabled(!emulatorRunning);
        saveNvramAsItem.setEnabled(emulatorRunning);
        startStopItem.setText(emulatorRunning ? "Stop" : "Start");
    }

    private void handleKey(int keyCode, boolean pressed, boolean shiftDown) {
        if (bus == null) return;

        if (config.type == MachineConfig.MachineType.XT) {
            handleKeyXT(keyCode, pressed, shiftDown);
        } else {
            handleKeyV1(keyCode, pressed);
        }
    }

    // ========================================================================
    // Cybiko Xtreme keyboard (15 columns x 16-bit, Fn+letter for numbers)
    // ========================================================================
    private void handleKeyXT(int keyCode, boolean pressed, boolean shiftDown) {
        // Shift+1 on PC = ! on Cybiko (dedicated unshifted key at col 9, bit 0x0004).
        // With lazy Shift, Shift is NOT in the Cybiko matrix, so pressing ! directly
        // gives CyOS unshifted ! (not Shift+! = ^).
        if (shiftDown && keyCode == KeyEvent.VK_1) {
            pressKeyWithHold(9, 0x0004, pressed);
            return;
        }

        // Number keys: Fn + top-row letter combos (skip when Shift held)
        if (!shiftDown) {
            switch (keyCode) {
                case KeyEvent.VK_1 -> { setFnLetter(3, 0x0002, pressed); return; } // Fn+Q
                case KeyEvent.VK_2 -> { setFnLetter(3, 0x0040, pressed); return; } // Fn+W
                case KeyEvent.VK_3 -> { setFnLetter(3, 0x0080, pressed); return; } // Fn+E
                case KeyEvent.VK_4 -> { setFnLetter(2, 0x2000, pressed); return; } // Fn+R
                case KeyEvent.VK_5 -> { setFnLetter(2, 0x4000, pressed); return; } // Fn+T
                case KeyEvent.VK_6 -> { setFnLetter(1, 0x0020, pressed); return; } // Fn+Y
                case KeyEvent.VK_7 -> { setFnLetter(1, 0x0040, pressed); return; } // Fn+U
                case KeyEvent.VK_8 -> { setFnLetter(0, 0x1000, pressed); return; } // Fn+I
                case KeyEvent.VK_9 -> { setFnLetter(0, 0x2000, pressed); return; } // Fn+O
                case KeyEvent.VK_0 -> { setFnLetter(9, 0x0010, pressed); return; } // Fn+P
            }
        }

        // Fn + middle-row symbols: @&$%*+-_=:
        // Fn + bottom-row symbols: "#☆{}<>'/?
        // Map PC punctuation keys to the Cybiko Fn+letter that produces them
        switch (keyCode) {
            // Middle row: Fn+A=@, Fn+S=&, Fn+D=$, Fn+F=%, Fn+G=*, Fn+H=+, Fn+J=-, Fn+K=_, Fn+L==, Fn+;=:
            case KeyEvent.VK_MINUS     -> { setFnLetter(1, 0x0080, pressed); return; } // Fn+J = -
            case KeyEvent.VK_EQUALS    -> { setFnLetter(0, 0x4000, pressed); return; } // Fn+L = =
            case KeyEvent.VK_SLASH     -> { setFnLetter(9, 0x0002, pressed); return; } // Fn+. = /
            case KeyEvent.VK_QUOTE     -> { setFnLetter(0, 0x0100, pressed); return; } // Fn+M = '
            // Bottom row: Fn+Z=", Fn+X=#, Fn+C=☆, Fn+V={, Fn+B=}, Fn+N=<, Fn+M=>, Fn+,=', Fn+.=/, Fn+!=?
        }

        int col = -1, bit = 0;
        switch (keyCode) {
            // XT keyboard matrix (from MAME cybikoxt INPUT_PORTS)
            // Column A.0
            case KeyEvent.VK_F7     -> { col = 0; bit = 0x0001; }
            case KeyEvent.VK_M      -> { col = 0; bit = 0x0100; }
            case KeyEvent.VK_K      -> { col = 0; bit = 0x0800; }
            case KeyEvent.VK_I      -> { col = 0; bit = 0x1000; }
            case KeyEvent.VK_O      -> { col = 0; bit = 0x2000; }
            case KeyEvent.VK_L      -> { col = 0; bit = 0x4000; }
            // Column A.1
            case KeyEvent.VK_F6     -> { col = 1; bit = 0x0001; }
            case KeyEvent.VK_G      -> { col = 1; bit = 0x0002; }
            case KeyEvent.VK_B      -> { col = 1; bit = 0x0004; }
            case KeyEvent.VK_N      -> { col = 1; bit = 0x0008; }
            case KeyEvent.VK_H      -> { col = 1; bit = 0x0010; }
            case KeyEvent.VK_Y      -> { col = 1; bit = 0x0020; }
            case KeyEvent.VK_U      -> { col = 1; bit = 0x0040; }
            case KeyEvent.VK_J      -> { col = 1; bit = 0x0080; }
            // Column A.2
            case KeyEvent.VK_F5     -> { col = 2; bit = 0x0001; }
            case KeyEvent.VK_D      -> { col = 2; bit = 0x0100; }
            case KeyEvent.VK_C      -> { col = 2; bit = 0x0200; }
            case KeyEvent.VK_V      -> { col = 2; bit = 0x0800; }
            case KeyEvent.VK_F      -> { col = 2; bit = 0x1000; }
            case KeyEvent.VK_R      -> { col = 2; bit = 0x2000; }
            case KeyEvent.VK_T      -> { col = 2; bit = 0x4000; }
            // Column A.3
            case KeyEvent.VK_F4     -> { col = 3; bit = 0x0001; }
            case KeyEvent.VK_Q      -> { col = 3; bit = 0x0002; }
            case KeyEvent.VK_A      -> { col = 3; bit = 0x0004; }
            case KeyEvent.VK_Z      -> { col = 3; bit = 0x0008; }
            case KeyEvent.VK_X      -> { col = 3; bit = 0x0010; }
            case KeyEvent.VK_S      -> { col = 3; bit = 0x0020; }
            case KeyEvent.VK_W      -> { col = 3; bit = 0x0040; }
            case KeyEvent.VK_E      -> { col = 3; bit = 0x0080; }
            // Column A.4
            case KeyEvent.VK_F3     -> { col = 4; bit = 0x0001; }
            case KeyEvent.VK_ENTER  -> { col = 4; bit = 0x0008; }
            case KeyEvent.VK_HOME   -> { col = 4; bit = 0x0010; } // Select
            case KeyEvent.VK_SPACE  -> { col = 4; bit = 0x0040; }
            // Column A.5
            case KeyEvent.VK_F2     -> { col = 5; bit = 0x0001; }
            case KeyEvent.VK_TAB    -> { col = 5; bit = 0x0080; }
            case KeyEvent.VK_DELETE, KeyEvent.VK_BACK_SPACE
                                    -> { col = 5; bit = 0x0100; }
            case KeyEvent.VK_INSERT -> { col = 5; bit = 0x0200; } // As
            case KeyEvent.VK_ESCAPE -> { col = 5; bit = 0x0400; }
            // Column A.6
            case KeyEvent.VK_F1     -> { col = 6; bit = 0x0001; }
            case KeyEvent.VK_UP     -> { col = 6; bit = 0x0800; }
            case KeyEvent.VK_RIGHT  -> { col = 6; bit = 0x1000; }
            case KeyEvent.VK_DOWN   -> { col = 6; bit = 0x2000; }
            case KeyEvent.VK_LEFT   -> { col = 6; bit = 0x4000; }
            // Column A.7
            case KeyEvent.VK_CONTROL -> { col = 7; bit = 0x8000; } // Fn
            // Lazy Shift: set flag only, don't touch Cybiko matrix directly.
            // Shift is added to matrix alongside letter keys that need it.
            case KeyEvent.VK_SHIFT  -> {
                if (pressed) {
                    pcShiftHeld = true;
                } else {
                    pcShiftHeld = false;
                    bus.setKeyState(8, 0x8000, false);
                    int idx = findHeldKey(8, 0x8000);
                    if (idx >= 0) removeHeldKey(idx);
                }
                return;
            }
            // Column A.9 - P, period, Help, semicolon
            case KeyEvent.VK_END       -> { col = 9; bit = 0x0001; } // Help
            case KeyEvent.VK_PERIOD    -> { col = 9; bit = 0x0002; }
            case KeyEvent.VK_SEMICOLON -> { col = 9; bit = 0x0008; }
            case KeyEvent.VK_P         -> { col = 9; bit = 0x0010; }
            // Exclamation mark (some platforms send this instead of Shift+VK_1)
            case KeyEvent.VK_EXCLAMATION_MARK -> { col = 9; bit = 0x0004; }
            // F12: keyboard probe — advance to next unknown matrix position
            case KeyEvent.VK_F12 -> {
                if (pressed) {
                    // Release previous probe position
                    if (probeCol >= 0) {
                        bus.setKeyState(probeCol, probeBit, false);
                    }
                    probeIndex = (probeIndex + 1) % PROBE_POSITIONS.length;
                    probeCol = PROBE_POSITIONS[probeIndex][0];
                    probeBit = PROBE_POSITIONS[probeIndex][1];
                    probeHoldLeft = PROBE_HOLD;
                    bus.setKeyState(probeCol, probeBit, true);
                    System.err.printf("[PROBE] #%d: col=%d bit=0x%04X%n", probeIndex, probeCol, probeBit);
                }
                return;
            }
        }
        if (col >= 0) {
            // Lazy Shift: add Cybiko Shift to matrix alongside this key
            // when PC Shift is held (so CyOS sees both in the same DMA scan)
            if (pcShiftHeld && pressed) {
                pressKeyWithHold(8, 0x8000, true);
            }
            pressKeyWithHold(col, bit, pressed);
        }
    }

    // ========================================================================
    // Cybiko V1/V2 keyboard (9 columns x 8-bit, dedicated number keys)
    // From MAME cybiko INPUT_PORTS (A.0-A.8)
    // ========================================================================
    private void handleKeyV1(int keyCode, boolean pressed) {
        int col = -1, bit = 0;
        switch (keyCode) {
            // Column 0 (A.0): F7, Esc, Del, Left, Q, A, `, Shift
            case KeyEvent.VK_F7     -> { col = 0; bit = 0x01; }
            case KeyEvent.VK_ESCAPE -> { col = 0; bit = 0x02; }
            case KeyEvent.VK_DELETE, KeyEvent.VK_BACK_SPACE
                                    -> { col = 0; bit = 0x04; }
            case KeyEvent.VK_LEFT   -> { col = 0; bit = 0x08; }
            case KeyEvent.VK_Q      -> { col = 0; bit = 0x10; }
            case KeyEvent.VK_A      -> { col = 0; bit = 0x20; }
            case KeyEvent.VK_BACK_QUOTE -> { col = 0; bit = 0x40; }
            case KeyEvent.VK_SHIFT  -> { col = 0; bit = 0x80; }

            // Column 1 (A.1): F6, Up, As/Insert, 2, W, S, Z, Fn
            case KeyEvent.VK_F6     -> { col = 1; bit = 0x01; }
            case KeyEvent.VK_UP     -> { col = 1; bit = 0x02; }
            case KeyEvent.VK_INSERT -> { col = 1; bit = 0x04; }
            case KeyEvent.VK_2      -> { col = 1; bit = 0x08; }
            case KeyEvent.VK_W      -> { col = 1; bit = 0x10; }
            case KeyEvent.VK_S      -> { col = 1; bit = 0x20; }
            case KeyEvent.VK_Z      -> { col = 1; bit = 0x40; }
            case KeyEvent.VK_CONTROL -> { col = 1; bit = 0x80; } // Fn

            // Column 2 (A.2): F5, F3, Space, 3, E, D, X, Help/End
            case KeyEvent.VK_F5     -> { col = 2; bit = 0x01; }
            case KeyEvent.VK_F3     -> { col = 2; bit = 0x02; }
            case KeyEvent.VK_SPACE  -> { col = 2; bit = 0x04; }
            case KeyEvent.VK_3      -> { col = 2; bit = 0x08; }
            case KeyEvent.VK_E      -> { col = 2; bit = 0x10; }
            case KeyEvent.VK_D      -> { col = 2; bit = 0x20; }
            case KeyEvent.VK_X      -> { col = 2; bit = 0x40; }
            case KeyEvent.VK_END    -> { col = 2; bit = 0x80; } // Help

            // Column 3 (A.3): F4, 1, Tab, 4, R, F, C, [
            case KeyEvent.VK_F4     -> { col = 3; bit = 0x01; }
            case KeyEvent.VK_1      -> { col = 3; bit = 0x02; }
            case KeyEvent.VK_TAB    -> { col = 3; bit = 0x04; }
            case KeyEvent.VK_4      -> { col = 3; bit = 0x08; }
            case KeyEvent.VK_R      -> { col = 3; bit = 0x10; }
            case KeyEvent.VK_F      -> { col = 3; bit = 0x20; }
            case KeyEvent.VK_C      -> { col = 3; bit = 0x40; }
            case KeyEvent.VK_OPEN_BRACKET -> { col = 3; bit = 0x80; }

            // Column 4 (A.4): Right, Down, Select/Home, 5, T, G, V, ]
            case KeyEvent.VK_RIGHT  -> { col = 4; bit = 0x01; }
            case KeyEvent.VK_DOWN   -> { col = 4; bit = 0x02; }
            case KeyEvent.VK_HOME   -> { col = 4; bit = 0x04; } // Select
            case KeyEvent.VK_5      -> { col = 4; bit = 0x08; }
            case KeyEvent.VK_T      -> { col = 4; bit = 0x10; }
            case KeyEvent.VK_G      -> { col = 4; bit = 0x20; }
            case KeyEvent.VK_V      -> { col = 4; bit = 0x40; }
            case KeyEvent.VK_CLOSE_BRACKET -> { col = 4; bit = 0x80; }

            // Column 5 (A.5): F2, ;, Enter, 6, Y, H, B, backslash
            case KeyEvent.VK_F2     -> { col = 5; bit = 0x01; }
            case KeyEvent.VK_SEMICOLON -> { col = 5; bit = 0x02; }
            case KeyEvent.VK_ENTER  -> { col = 5; bit = 0x04; }
            case KeyEvent.VK_6      -> { col = 5; bit = 0x08; }
            case KeyEvent.VK_Y      -> { col = 5; bit = 0x10; }
            case KeyEvent.VK_H      -> { col = 5; bit = 0x20; }
            case KeyEvent.VK_B      -> { col = 5; bit = 0x40; }
            case KeyEvent.VK_BACK_SLASH -> { col = 5; bit = 0x80; }

            // Column 6 (A.6): F1, /, BkSp, 7, U, J, N
            case KeyEvent.VK_F1     -> { col = 6; bit = 0x01; }
            case KeyEvent.VK_SLASH  -> { col = 6; bit = 0x02; }
            // Note: BkSp handled above as Delete in column 0 for V1
            case KeyEvent.VK_7      -> { col = 6; bit = 0x08; }
            case KeyEvent.VK_U      -> { col = 6; bit = 0x10; }
            case KeyEvent.VK_J      -> { col = 6; bit = 0x20; }
            case KeyEvent.VK_N      -> { col = 6; bit = 0x40; }

            // Column 7 (A.7): -, ., 0, 8, I, K, M
            case KeyEvent.VK_MINUS  -> { col = 7; bit = 0x01; }
            case KeyEvent.VK_PERIOD -> { col = 7; bit = 0x02; }
            case KeyEvent.VK_0      -> { col = 7; bit = 0x04; }
            case KeyEvent.VK_8      -> { col = 7; bit = 0x08; }
            case KeyEvent.VK_I      -> { col = 7; bit = 0x10; }
            case KeyEvent.VK_K      -> { col = 7; bit = 0x20; }
            case KeyEvent.VK_M      -> { col = 7; bit = 0x40; }

            // Column 8 (A.8): ', =, 9, P, O, L, ,
            case KeyEvent.VK_QUOTE  -> { col = 8; bit = 0x01; }
            case KeyEvent.VK_EQUALS -> { col = 8; bit = 0x02; }
            case KeyEvent.VK_9      -> { col = 8; bit = 0x04; }
            case KeyEvent.VK_P      -> { col = 8; bit = 0x08; }
            case KeyEvent.VK_O      -> { col = 8; bit = 0x10; }
            case KeyEvent.VK_L      -> { col = 8; bit = 0x20; }
            case KeyEvent.VK_COMMA  -> { col = 8; bit = 0x40; }
        }
        if (col >= 0) {
            pressKeyWithHold(col, bit, pressed);
        }
    }

    private void pressKeyWithHold(int col, int bit, boolean pressed) {
        if (pressed) {
            bus.setKeyState(col, bit, true);
            int idx = findHeldKey(col, bit);
            if (idx >= 0) {
                heldFramesLeft[idx] = MIN_HOLD_FRAMES;
                heldReleasePending[idx] = false;
            } else if (heldCount < MAX_HELD_KEYS) {
                heldCols[heldCount] = col;
                heldBits[heldCount] = bit;
                heldFramesLeft[heldCount] = MIN_HOLD_FRAMES;
                heldReleasePending[heldCount] = false;
                heldCount++;
            } else {
                Log.log(Log.Category.IO, "[HOLD] OVERFLOW col=%d bit=0x%X heldCount=%d", col, bit, heldCount);
            }
        } else {
            int idx = findHeldKey(col, bit);
            if (idx >= 0 && heldFramesLeft[idx] > 0) {
                heldReleasePending[idx] = true;
            } else {
                bus.setKeyState(col, bit, false);
                if (idx >= 0) removeHeldKey(idx);
            }
        }
    }

    private int findHeldKey(int col, int bit) {
        for (int i = 0; i < heldCount; i++) {
            if (heldCols[i] == col && heldBits[i] == bit) return i;
        }
        return -1;
    }

    private void removeHeldKey(int idx) {
        heldCount--;
        if (idx < heldCount) {
            heldCols[idx] = heldCols[heldCount];
            heldBits[idx] = heldBits[heldCount];
            heldFramesLeft[idx] = heldFramesLeft[heldCount];
            heldReleasePending[idx] = heldReleasePending[heldCount];
        }
    }

    /** Process Fn+letter state machine on emulation thread.
     *  Types one digit at a time: Fn press → delay → letter press → hold → release.
     *  Fn stays held between consecutive digits (shorter delay). */
    private void processFnLetters() {
        boolean hasQueued = numQueueHead != numQueueTail;

        // State: idle, no queued digits → release Fn if active
        if (currentDigit < 0 && !hasQueued) {
            if (fnActive) {
                fnReleaseDelay++;
                if (fnReleaseDelay >= FN_RELEASE_HOLD) {
                    bus.setKeyState(7, 0x8000, false);
                    fnActive = false;
                    fnReleaseDelay = 0;
                }
            }
            return;
        }
        fnReleaseDelay = 0;

        // Start next digit from queue if idle
        if (currentDigit < 0 && hasQueued) {
            currentDigit = numQueue[numQueueHead];
            numQueueHead = (numQueueHead + 1) % NUM_QUEUE_SIZE;
            if (!fnActive) {
                // First digit: press Fn and wait
                bus.setKeyState(7, 0x8000, true);
                fnActive = true;
                fnDelay = FN_PRESS_DELAY;
            } else {
                // Fn already held from previous digit: shorter delay
                fnDelay = FN_BETWEEN_DELAY;
            }
            numHoldLeft = -1; // letter not yet pressed
        }

        // Fn delay countdown
        if (fnDelay > 0) {
            fnDelay--;
            return;
        }

        // Press letter if not yet pressed
        if (currentDigit >= 0 && numHoldLeft < 0) {
            bus.setKeyState(NUM_COLS[currentDigit], NUM_BITS[currentDigit], true);
            numHoldLeft = NUM_MIN_HOLD;
            return;
        }

        // Hold countdown
        if (numHoldLeft > 0) {
            numHoldLeft--;
            return;
        }

        // Release letter, move to next digit
        if (currentDigit >= 0 && numHoldLeft == 0) {
            bus.setKeyState(NUM_COLS[currentDigit], NUM_BITS[currentDigit], false);
            currentDigit = -1;
        }
    }

    /** Fn + letter combo for XT number keys. EDT enqueues digit;
     *  render() types them one at a time on the emulation thread. */
    private void setFnLetter(int letterCol, int letterBit, boolean pressed) {
        if (!pressed) return; // only queue on key down; release is automatic after hold
        for (int i = 0; i < 10; i++) {
            if (NUM_COLS[i] == letterCol && NUM_BITS[i] == letterBit) {
                int next = (numQueueTail + 1) % NUM_QUEUE_SIZE;
                if (next != numQueueHead) { // not full
                    numQueue[numQueueTail] = i;
                    numQueueTail = next;
                }
                return;
            }
        }
    }

    @Override
    public void render(int[] pixels, int width, int height) {
        if (bus != null) {
            // Process minimum hold timers
            for (int i = 0; i < heldCount; i++) {
                if (heldFramesLeft[i] > 0) heldFramesLeft[i]--;
                if (heldFramesLeft[i] <= 0 && heldReleasePending[i]) {
                    bus.setKeyState(heldCols[i], heldBits[i], false);
                    removeHeldKey(i);
                    i--;
                }
            }

            // Fn+letter state machine (XT only) — all on emulation thread
            if (config.type == MachineConfig.MachineType.XT) {
                processFnLetters();
            }

            // Keyboard probe: auto-release after hold expires
            if (probeHoldLeft > 0) {
                probeHoldLeft--;
                if (probeHoldLeft == 0 && probeCol >= 0) {
                    bus.setKeyState(probeCol, probeBit, false);
                }
            }
        }

        int len = Math.min(pixels.length, width * height);
        for (int i = 0; i < len; i++) {
            int gray = pixels[i];
            if (gray < 0) gray = 0; else if (gray > 255) gray = 255;
            rgbBuffer[i] = (((gray * 180) >> 8) << 16) | (((gray * 210) >> 8) << 8) | ((gray * 160) >> 8);
        }
        image.setRGB(0, 0, width, height, rgbBuffer, 0, width);
        panel.repaint();
    }

    @Override
    public void close() {
        frame.dispose();
    }

    public JFrame getFrame() { return frame; }
}
