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

    // Fn+letter combo state for number key input (XT only).
    private static final int FN_FIRST_DELAY = 4;
    private static final int FN_NEXT_DELAY = 3;
    private static final int FN_RELEASE_DELAY = 10;
    private boolean fnHeld = false;
    private int fnHeldCount = 0;
    private int fnReleaseCountdown = 0;

    private static final int MAX_PENDING_LETTERS = 8;
    private final int[] pendingCols = new int[MAX_PENDING_LETTERS];
    private final int[] pendingBits = new int[MAX_PENDING_LETTERS];
    private final int[] pendingDelays = new int[MAX_PENDING_LETTERS];
    private int pendingCount = 0;

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
            @Override public void keyPressed(KeyEvent e) { handleKey(e.getKeyCode(), true); }
            @Override public void keyReleased(KeyEvent e) { handleKey(e.getKeyCode(), false); }
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

    private void handleKey(int keyCode, boolean pressed) {
        if (bus == null) return;

        if (config.type == MachineConfig.MachineType.XT) {
            handleKeyXT(keyCode, pressed);
        } else {
            handleKeyV1(keyCode, pressed);
        }
    }

    // ========================================================================
    // Cybiko Xtreme keyboard (15 columns x 16-bit, Fn+letter for numbers)
    // ========================================================================
    private void handleKeyXT(int keyCode, boolean pressed) {
        // Number keys: Fn + letter combos
        switch (keyCode) {
            case KeyEvent.VK_1 -> { setFnLetter(3, 0x0002, pressed); return; }
            case KeyEvent.VK_2 -> { setFnLetter(3, 0x0040, pressed); return; }
            case KeyEvent.VK_3 -> { setFnLetter(3, 0x0080, pressed); return; }
            case KeyEvent.VK_4 -> { setFnLetter(2, 0x2000, pressed); return; }
            case KeyEvent.VK_5 -> { setFnLetter(2, 0x4000, pressed); return; }
            case KeyEvent.VK_6 -> { setFnLetter(1, 0x0020, pressed); return; }
            case KeyEvent.VK_7 -> { setFnLetter(1, 0x0040, pressed); return; }
            case KeyEvent.VK_8 -> { setFnLetter(0, 0x1000, pressed); return; }
            case KeyEvent.VK_9 -> { setFnLetter(0, 0x2000, pressed); return; }
            case KeyEvent.VK_0 -> { setFnLetter(9, 0x0010, pressed); return; }
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
            // Column A.8
            case KeyEvent.VK_SHIFT  -> { col = 8; bit = 0x8000; }
            // Column A.9 - P, period, Help, semicolon
            case KeyEvent.VK_END       -> { col = 9; bit = 0x0001; } // Help
            case KeyEvent.VK_PERIOD    -> { col = 9; bit = 0x0002; }
            case KeyEvent.VK_SEMICOLON -> { col = 9; bit = 0x0008; }
            case KeyEvent.VK_P         -> { col = 9; bit = 0x0010; }
        }
        if (col >= 0) {
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
                System.err.printf("[HOLD] OVERFLOW col=%d bit=0x%X heldCount=%d%n", col, bit, heldCount);
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

    /** Fn + letter combo for XT number keys. */
    private void setFnLetter(int letterCol, int letterBit, boolean pressed) {
        if (pressed) {
            fnHeldCount++;
            fnReleaseCountdown = 0;
            if (!fnHeld) {
                bus.setKeyState(7, 0x8000, true);
                fnHeld = true;
                queuePendingLetter(letterCol, letterBit, FN_FIRST_DELAY);
            } else {
                queuePendingLetter(letterCol, letterBit, FN_NEXT_DELAY);
            }
        } else {
            bus.setKeyState(letterCol, letterBit, false);
            removePendingLetter(letterCol, letterBit);
            fnHeldCount = Math.max(0, fnHeldCount - 1);
            if (fnHeldCount == 0 && fnHeld) {
                fnReleaseCountdown = FN_RELEASE_DELAY;
            }
        }
    }

    private void queuePendingLetter(int col, int bit, int delay) {
        for (int i = 0; i < pendingCount; i++) {
            if (pendingCols[i] == col && pendingBits[i] == bit) {
                pendingDelays[i] = delay;
                return;
            }
        }
        if (pendingCount < MAX_PENDING_LETTERS) {
            pendingCols[pendingCount] = col;
            pendingBits[pendingCount] = bit;
            pendingDelays[pendingCount] = delay;
            pendingCount++;
        }
    }

    private void removePendingLetter(int col, int bit) {
        for (int i = 0; i < pendingCount; i++) {
            if (pendingCols[i] == col && pendingBits[i] == bit) {
                pendingCount--;
                if (i < pendingCount) {
                    pendingCols[i] = pendingCols[pendingCount];
                    pendingBits[i] = pendingBits[pendingCount];
                    pendingDelays[i] = pendingDelays[pendingCount];
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

            // Process queued Fn+letter presses (XT only)
            for (int i = 0; i < pendingCount; i++) {
                if (--pendingDelays[i] <= 0) {
                    bus.setKeyState(pendingCols[i], pendingBits[i], true);
                    pendingCount--;
                    if (i < pendingCount) {
                        pendingCols[i] = pendingCols[pendingCount];
                        pendingBits[i] = pendingBits[pendingCount];
                        pendingDelays[i] = pendingDelays[pendingCount];
                    }
                    i--;
                }
            }

            // Delayed Fn release (XT only)
            if (fnReleaseCountdown > 0) {
                fnReleaseCountdown--;
                if (fnReleaseCountdown == 0 && fnHeld) {
                    bus.setKeyState(7, 0x8000, false);
                    fnHeld = false;
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
