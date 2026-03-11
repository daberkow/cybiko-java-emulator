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

    // Fn+letter combo queue (numbers and symbols, all machine types).
    // EDT queues combos; render() processes ONE at a time through the full
    // Fn-then-letter sequence on the emulation thread (no DMA race).
    // Queue entries are packed as (col << 16) | bit.
    // Fn key position varies: XT = col 7 bit 0x8000, V1/V2 = col 1 bit 0x80.
    private final int fnCol;
    private final int fnBit;
    private static final int FN_PRESS_DELAY = 8;   // Frames Fn must be held alone before first letter
    private static final int FN_BETWEEN_DELAY = 3; // Frames between consecutive letters (Fn stays held)
    private static final int FN_RELEASE_HOLD = 6;  // Frames to hold Fn after last letter released
    private static final int FN_KEY_HOLD = 6;      // Minimum frames to hold letter in matrix
    private static final int FN_QUEUE_SIZE = 16;
    private final int[] fnQueue = new int[FN_QUEUE_SIZE];
    private int fnQueueHead = 0, fnQueueTail = 0;
    // Current combo being processed (-1 = idle, otherwise packed col:bit)
    private int currentCombo = -1;
    private int comboHoldLeft = 0;   // hold countdown for current letter
    private boolean fnActive = false;
    private int fnDelay = 0;
    private int fnReleaseDelay = 0;

    // Lazy Shift: PC Shift key sets this flag but does NOT immediately put Shift
    // into the Cybiko keyboard matrix.  Shift is added to the matrix only when a
    // key that needs CyOS-level shifting (letters, ;, etc.) is pressed alongside it.
    // Keys that map to dedicated unshifted Cybiko keys (like Shift+1 → !) bypass
    // Shift entirely, preventing CyOS from seeing Shift+! = ^.
    private boolean pcShiftHeld = false;

    // Shift+key combo queue: like Fn combos but presses Cybiko Shift before the key.
    // Used for keys like ~ (Shift+comma) and ^ (Shift+!) where CyOS needs to see
    // Shift stable in the matrix before the key appears.
    private static final int SHIFT_PRESS_DELAY = 4; // Frames Shift must be held before key
    private static final int SHIFT_KEY_HOLD = 6;    // Frames to hold key
    private int shiftCombo = -1;       // packed (col << 16) | bit, or -1 = idle
    private int shiftComboDelay = 0;
    private int shiftComboHold = 0;

    // Keyboard probe mode: F12 cycles through unknown column:bit positions.
    // Each press activates the next position for PROBE_HOLD frames.
    private static final int PROBE_HOLD = 10;
    private int probeIndex = -1;
    private int probeHoldLeft = 0;
    private int probeCol = -1, probeBit = 0;
    // Candidate positions to test — unused bits in cols 0-8.
    // Looking for: , (comma), ( (left paren), ) (right paren)
    // ( and ) physically flank Space (col 4), so try col 4 first.
    // , is near . (col 9) and ! (col 9), try nearby cols.
    private static final int[][] PROBE_POSITIONS = {
        // col 4 unused bits — ( and ) likely here (near Space at 0x0040)
        {4, 0x0002}, {4, 0x0004}, {4, 0x0020}, {4, 0x0080},
        {4, 0x0100}, {4, 0x0200}, {4, 0x0400}, {4, 0x0800},
        {4, 0x1000}, {4, 0x2000}, {4, 0x4000}, {4, 0x8000},
        // col 5 unused bits — near Tab/Del/Esc cluster
        {5, 0x0002}, {5, 0x0004}, {5, 0x0008}, {5, 0x0010},
        {5, 0x0020}, {5, 0x0040}, {5, 0x0800},
        {5, 0x1000}, {5, 0x2000}, {5, 0x4000}, {5, 0x8000},
        // col 7 unused bits — Fn column, maybe ( ) near Fn/Shift
        {7, 0x0001}, {7, 0x0002}, {7, 0x0004}, {7, 0x0008},
        {7, 0x0010}, {7, 0x0020}, {7, 0x0040}, {7, 0x0080},
        {7, 0x0100}, {7, 0x0200}, {7, 0x0400}, {7, 0x0800},
        {7, 0x1000}, {7, 0x2000}, {7, 0x4000},
        // col 8 unused bits — Shift column
        {8, 0x0001}, {8, 0x0002}, {8, 0x0004}, {8, 0x0008},
        {8, 0x0010}, {8, 0x0020}, {8, 0x0040}, {8, 0x0080},
        {8, 0x0100}, {8, 0x0200}, {8, 0x0400}, {8, 0x0800},
        {8, 0x1000}, {8, 0x2000}, {8, 0x4000},
        // col 0 unused bits — near M/K/I/O/L
        {0, 0x0002}, {0, 0x0004}, {0, 0x0008}, {0, 0x0010},
        {0, 0x0020}, {0, 0x0040}, {0, 0x0080}, {0, 0x0200},
        {0, 0x0400}, {0, 0x8000},
        // col 6 unused bits — near arrows/F1
        {6, 0x0002}, {6, 0x0004}, {6, 0x0008}, {6, 0x0010},
        {6, 0x0020}, {6, 0x0040}, {6, 0x0080}, {6, 0x0100},
        {6, 0x0200}, {6, 0x0400}, {6, 0x8000},
        // col 1 unused bits
        {1, 0x0100}, {1, 0x0200}, {1, 0x0400}, {1, 0x0800},
        {1, 0x1000}, {1, 0x2000}, {1, 0x4000}, {1, 0x8000},
        // col 2 unused bits
        {2, 0x0002}, {2, 0x0004}, {2, 0x0008}, {2, 0x0010},
        {2, 0x0020}, {2, 0x0040}, {2, 0x0080}, {2, 0x0400},
        {2, 0x8000},
        // col 3 unused bits
        {3, 0x0100}, {3, 0x0200}, {3, 0x0400}, {3, 0x0800},
        {3, 0x1000}, {3, 0x2000}, {3, 0x4000}, {3, 0x8000},
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
        // Fn key position: XT = col 7 bit 0x8000, V1/V2 = col 1 bit 0x80
        if (config.type == MachineConfig.MachineType.XT) {
            fnCol = 7; fnBit = 0x8000;
        } else {
            fnCol = 1; fnBit = 0x80;
        }
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
            handleKeyV1(keyCode, pressed, shiftDown);
        }
    }

    // ========================================================================
    // Cybiko Xtreme keyboard (15 columns x 16-bit, Fn+letter for numbers)
    // ========================================================================
    private void handleKeyXT(int keyCode, boolean pressed, boolean shiftDown) {
        // === Shifted PC keys → Cybiko Fn+letter symbols or Shift+key combos ===
        // With lazy Shift, Cybiko Shift is NOT in the matrix, so Fn combos work cleanly.
        if (shiftDown) {
            switch (keyCode) {
                // Shift+number → symbols
                case KeyEvent.VK_1 -> { pressKeyWithHold(9, 0x0004, pressed); return; } // ! (direct key)
                case KeyEvent.VK_2 -> { enqueueFnCombo(3, 0x0004, pressed); return; }   // @ = Fn+A
                case KeyEvent.VK_3 -> { enqueueFnCombo(3, 0x0010, pressed); return; }   // # = Fn+X
                case KeyEvent.VK_4 -> { enqueueFnCombo(2, 0x0100, pressed); return; }   // $ = Fn+D
                case KeyEvent.VK_5 -> { enqueueFnCombo(2, 0x1000, pressed); return; }   // % = Fn+F
                case KeyEvent.VK_6 -> { pressShiftedKey(9, 0x0004, pressed); return; }  // ^ = Shift+!
                case KeyEvent.VK_7 -> { enqueueFnCombo(3, 0x0020, pressed); return; }   // & = Fn+S
                case KeyEvent.VK_8 -> { enqueueFnCombo(1, 0x0002, pressed); return; }   // * = Fn+G
                case KeyEvent.VK_9 -> { pressKeyWithHold(2, 0x0400, pressed); return; } // ( (direct key)
                case KeyEvent.VK_0 -> { pressKeyWithHold(0, 0x0200, pressed); return; } // ) (direct key)
                // Shift+punctuation → symbols
                case KeyEvent.VK_BACK_QUOTE -> { pressShiftedKey(0, 0x0400, pressed); return; } // ~ = Shift+,
                case KeyEvent.VK_MINUS  -> { enqueueFnCombo(0, 0x0800, pressed); return; }  // _ = Fn+K
                case KeyEvent.VK_EQUALS -> { enqueueFnCombo(1, 0x0010, pressed); return; }  // + = Fn+H
                case KeyEvent.VK_SEMICOLON -> { enqueueFnCombo(9, 0x0008, pressed); return; } // : = Fn+;
                case KeyEvent.VK_QUOTE  -> { enqueueFnCombo(3, 0x0008, pressed); return; }  // " = Fn+Z
                case KeyEvent.VK_OPEN_BRACKET  -> { enqueueFnCombo(2, 0x0800, pressed); return; } // { = Fn+V
                case KeyEvent.VK_CLOSE_BRACKET -> { enqueueFnCombo(1, 0x0004, pressed); return; } // } = Fn+B
                case KeyEvent.VK_COMMA  -> { enqueueFnCombo(1, 0x0008, pressed); return; }  // < = Fn+N
                case KeyEvent.VK_PERIOD -> { enqueueFnCombo(0, 0x0100, pressed); return; }  // > = Fn+M
                case KeyEvent.VK_SLASH  -> { enqueueFnCombo(9, 0x0004, pressed); return; }  // ? = Fn+!
                case KeyEvent.VK_BACK_SLASH -> { pressShiftedKey(9, 0x0008, pressed); return; } // | = Shift+;
            }
            // Fall through for shifted letter keys — lazy Shift adds Cybiko Shift below
        }

        // === Unshifted PC keys → numbers (Fn+letter) and symbols ===
        if (!shiftDown) {
            switch (keyCode) {
                // Numbers: Fn + top-row letter combos
                case KeyEvent.VK_1 -> { enqueueFnCombo(3, 0x0002, pressed); return; } // 1 = Fn+Q
                case KeyEvent.VK_2 -> { enqueueFnCombo(3, 0x0040, pressed); return; } // 2 = Fn+W
                case KeyEvent.VK_3 -> { enqueueFnCombo(3, 0x0080, pressed); return; } // 3 = Fn+E
                case KeyEvent.VK_4 -> { enqueueFnCombo(2, 0x2000, pressed); return; } // 4 = Fn+R
                case KeyEvent.VK_5 -> { enqueueFnCombo(2, 0x4000, pressed); return; } // 5 = Fn+T
                case KeyEvent.VK_6 -> { enqueueFnCombo(1, 0x0020, pressed); return; } // 6 = Fn+Y
                case KeyEvent.VK_7 -> { enqueueFnCombo(1, 0x0040, pressed); return; } // 7 = Fn+U
                case KeyEvent.VK_8 -> { enqueueFnCombo(0, 0x1000, pressed); return; } // 8 = Fn+I
                case KeyEvent.VK_9 -> { enqueueFnCombo(0, 0x2000, pressed); return; } // 9 = Fn+O
                case KeyEvent.VK_0 -> { enqueueFnCombo(9, 0x0010, pressed); return; } // 0 = Fn+P
                // Symbols: Fn + letter combos
                case KeyEvent.VK_MINUS  -> { enqueueFnCombo(1, 0x0080, pressed); return; } // - = Fn+J
                case KeyEvent.VK_EQUALS -> { enqueueFnCombo(0, 0x4000, pressed); return; } // = = Fn+L
                case KeyEvent.VK_SLASH  -> { enqueueFnCombo(9, 0x0002, pressed); return; } // / = Fn+.
                case KeyEvent.VK_BACK_SLASH -> { pressShiftedKey(9, 0x0002, pressed); return; } // \ = Cybiko Shift+.
                case KeyEvent.VK_QUOTE -> { enqueueFnCombo(0, 0x0400, pressed); return; } // ' = Fn+,
                case KeyEvent.VK_OPEN_BRACKET  -> { enqueueFnCombo(2, 0x0400, pressed); return; } // [ = Fn+(
                case KeyEvent.VK_CLOSE_BRACKET -> { enqueueFnCombo(0, 0x0200, pressed); return; } // ] = Fn+)
            }
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
            case KeyEvent.VK_COMMA  -> { col = 0; bit = 0x0400; } // , (comma/tilde key)
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
    private void handleKeyV1(int keyCode, boolean pressed, boolean shiftDown) {
        // Shift+' on PC = " on V1 Cybiko (Fn+' combo, queue-based)
        if (shiftDown && keyCode == KeyEvent.VK_QUOTE) {
            enqueueFnCombo(8, 0x01, pressed); // ' key, Fn added by queue state machine
            return;
        }

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
     *  Types one combo at a time: Fn press → delay → letter press → hold → release.
     *  Fn stays held between consecutive combos (shorter delay). */
    private void processFnCombos() {
        boolean hasQueued = fnQueueHead != fnQueueTail;

        // State: idle, no queued combos → release Fn if active
        if (currentCombo < 0 && !hasQueued) {
            if (fnActive) {
                fnReleaseDelay++;
                if (fnReleaseDelay >= FN_RELEASE_HOLD) {
                    bus.setKeyState(fnCol, fnBit, false);
                    fnActive = false;
                    fnReleaseDelay = 0;
                }
            }
            return;
        }
        fnReleaseDelay = 0;

        // Start next combo from queue if idle
        if (currentCombo < 0 && hasQueued) {
            currentCombo = fnQueue[fnQueueHead];
            fnQueueHead = (fnQueueHead + 1) % FN_QUEUE_SIZE;
            if (!fnActive) {
                bus.setKeyState(fnCol, fnBit, true);
                fnActive = true;
                fnDelay = FN_PRESS_DELAY;
            } else {
                fnDelay = FN_BETWEEN_DELAY;
            }
            comboHoldLeft = -1; // letter not yet pressed
        }

        // Fn delay countdown
        if (fnDelay > 0) {
            fnDelay--;
            return;
        }

        // Unpack current combo
        int col = currentCombo >>> 16;
        int cbit = currentCombo & 0xFFFF;

        // Press letter if not yet pressed
        if (currentCombo >= 0 && comboHoldLeft < 0) {
            bus.setKeyState(col, cbit, true);
            comboHoldLeft = FN_KEY_HOLD;
            return;
        }

        // Hold countdown
        if (comboHoldLeft > 0) {
            comboHoldLeft--;
            return;
        }

        // Release letter, move to next combo
        if (currentCombo >= 0 && comboHoldLeft == 0) {
            bus.setKeyState(col, cbit, false);
            currentCombo = -1;
        }
    }

    /** Queue an Fn+letter combo. EDT enqueues; render() processes on emulation thread.
     *  Only queues on key press; release is automatic after hold timer. */
    private void enqueueFnCombo(int letterCol, int letterBit, boolean pressed) {
        if (!pressed) return;
        int next = (fnQueueTail + 1) % FN_QUEUE_SIZE;
        if (next != fnQueueHead) {
            fnQueue[fnQueueTail] = (letterCol << 16) | letterBit;
            fnQueueTail = next;
        }
    }

    /** Queue a key that needs Cybiko Shift (e.g., ^ = Shift+!, ~ = Shift+,, | = Shift+;).
     *  Shift is pressed first and held for SHIFT_PRESS_DELAY frames before pressing the key,
     *  ensuring CyOS sees Shift stable in the matrix. Only queues on press; release is automatic. */
    private void pressShiftedKey(int col, int bit, boolean pressed) {
        if (!pressed || shiftCombo >= 0) return; // only on press; ignore auto-repeat
        shiftCombo = (col << 16) | bit;
        shiftComboDelay = SHIFT_PRESS_DELAY;
        shiftComboHold = 0;
        // Press Shift immediately
        bus.setKeyState(8, 0x8000, true);
    }

    /** Process Shift+key state machine on emulation thread. */
    private void processShiftCombos() {
        if (shiftCombo < 0) return;

        int col = shiftCombo >>> 16;
        int cbit = shiftCombo & 0xFFFF;

        if (shiftComboDelay > 0) {
            shiftComboDelay--;
            return;
        }

        if (shiftComboHold == 0) {
            // Press the key
            bus.setKeyState(col, cbit, true);
            shiftComboHold = SHIFT_KEY_HOLD;
            return;
        }

        shiftComboHold--;
        if (shiftComboHold == 0) {
            // Release key and Shift
            bus.setKeyState(col, cbit, false);
            if (!pcShiftHeld) {
                bus.setKeyState(8, 0x8000, false);
            }
            shiftCombo = -1;
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

            // Fn+letter and Shift+key state machines — all on emulation thread
            processFnCombos();
            if (config.type == MachineConfig.MachineType.XT) {
                processShiftCombos();
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
