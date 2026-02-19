package org.example.cybiko;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.image.BufferedImage;

/** Swing-based display renderer with keyboard input. */
public class SwingRenderer implements FrameBufferRenderer {
    private static final int SCALE = 4;

    private final JFrame frame;
    private final BufferedImage image;
    private final int[] rgbBuffer; // Pre-allocated packed RGB buffer for bulk setRGB
    private final JPanel panel;
    private AddressBus bus; // Set after construction to receive key events

    // Fn+letter combo state for number key input.
    // Fn is "sticky": held as long as number keys are active, released after
    // an idle period. Every number key letter is delayed by a few frames so
    // CyOS sees Fn as established before the letter appears in the matrix.
    private static final int FN_FIRST_DELAY = 4;    // Frames to delay first letter (Fn must register)
    private static final int FN_NEXT_DELAY = 3;     // Frames to delay subsequent letters (Fn already held)
    private static final int FN_RELEASE_DELAY = 10;  // Frames to keep Fn after last number released
    private boolean fnHeld = false;           // Whether we're currently holding Fn
    private int fnHeldCount = 0;              // Number of active number keys holding Fn
    private int fnReleaseCountdown = 0;       // Frames until Fn is released (0=not pending)

    // Queue of pending letter key presses (for rapid number typing)
    private static final int MAX_PENDING_LETTERS = 8;
    private final int[] pendingCols = new int[MAX_PENDING_LETTERS];
    private final int[] pendingBits = new int[MAX_PENDING_LETTERS];
    private final int[] pendingDelays = new int[MAX_PENDING_LETTERS];
    private int pendingCount = 0;

    // Minimum key hold time: ensures every keypress stays in the matrix long
    // enough for CyOS to detect it via DMA scan (at least 2 frames = ~33ms).
    // Without this, fast typists can press+release between two scans.
    private static final int MIN_HOLD_FRAMES = 3;
    private static final int MAX_HELD_KEYS = 16;
    private final int[] heldCols = new int[MAX_HELD_KEYS];
    private final int[] heldBits = new int[MAX_HELD_KEYS];
    private final int[] heldFramesLeft = new int[MAX_HELD_KEYS];
    private final boolean[] heldReleasePending = new boolean[MAX_HELD_KEYS];
    private int heldCount = 0;

    // Cybiko Xtreme keyboard matrix mapping (from MAME cybikoxt INPUT_PORTS):
    // Each entry: [hostKeyCode] -> {column, bitmask}
    // Column A.0: F7, M, K, I, O, L
    // Column A.1: F6, G, B, N, H, Y, U, J
    // Column A.2: F5, D, C, V, F, R, T
    // Column A.3: F4, Q, A, Z, X, S, W, E
    // Column A.4: F3, Enter, Select(Home), Space
    // Column A.5: F2, Tab, Del, As(Insert), Esc
    // Column A.6: F1, Up, Right, Down, Left
    // Column A.7: Fn(LCtrl)
    // Column A.8: Shift
    // Column A.13: Help(End), Period, P
    // Column A.14: On/Off(F8)

    public SwingRenderer() {
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

        frame = new JFrame("Cybiko Xtreme Emulator");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.add(panel);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setResizable(false);
        frame.setVisible(true);

        // Add keyboard listener
        frame.addKeyListener(new KeyListener() {
            @Override public void keyTyped(KeyEvent e) {}
            @Override public void keyPressed(KeyEvent e) { handleKey(e.getKeyCode(), true); }
            @Override public void keyReleased(KeyEvent e) { handleKey(e.getKeyCode(), false); }
        });
        frame.setFocusable(true);
        frame.requestFocus();
    }

    public void setBus(AddressBus bus) { this.bus = bus; }

    private void handleKey(int keyCode, boolean pressed) {
        if (bus == null) return;

        // Number keys: simulate Fn + letter combo (Cybiko has no dedicated number keys)
        // Fn+Q=1, Fn+W=2, Fn+E=3, Fn+R=4, Fn+T=5, Fn+Y=6, Fn+U=7, Fn+I=8, Fn+O=9, Fn+P=0
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
            case KeyEvent.VK_0 -> { setFnLetter(13, 0x0010, pressed); return; } // Fn+P
        }

        int col = -1, bit = 0;
        switch (keyCode) {
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
            // Column A.13
            case KeyEvent.VK_END    -> { col = 13; bit = 0x0001; } // Help
            case KeyEvent.VK_PERIOD -> { col = 13; bit = 0x0002; }
            case KeyEvent.VK_P      -> { col = 13; bit = 0x0010; }
            // Column A.14
            case KeyEvent.VK_F8     -> { col = 14; bit = 0x8000; } // On/Off
        }
        if (col >= 0) {
            pressKeyWithHold(col, bit, pressed);
        }
    }

    /**
     * Press/release a key with minimum hold time enforcement.
     * On press: set key in matrix and start hold timer.
     * On release: if hold timer hasn't expired, defer the release.
     */
    private void pressKeyWithHold(int col, int bit, boolean pressed) {
        if (pressed) {
            bus.setKeyState(col, bit, true);
            // Track this key for minimum hold enforcement
            int idx = findHeldKey(col, bit);
            if (idx >= 0) {
                // Already tracked (re-press before hold expired)
                heldFramesLeft[idx] = MIN_HOLD_FRAMES;
                heldReleasePending[idx] = false;
            } else if (heldCount < MAX_HELD_KEYS) {
                heldCols[heldCount] = col;
                heldBits[heldCount] = bit;
                heldFramesLeft[heldCount] = MIN_HOLD_FRAMES;
                heldReleasePending[heldCount] = false;
                heldCount++;
            }
        } else {
            int idx = findHeldKey(col, bit);
            if (idx >= 0 && heldFramesLeft[idx] > 0) {
                // Hold timer still active - defer the release
                heldReleasePending[idx] = true;
            } else {
                // Hold timer expired or not tracked - release immediately
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

    /**
     * Simulate Fn + letter key press for number key input.
     * Fn is "sticky" - stays held while any number keys are active.
     * Every letter is queued with a short delay so CyOS always sees Fn
     * established in the matrix before the letter appears.
     */
    private void setFnLetter(int letterCol, int letterBit, boolean pressed) {
        if (pressed) {
            fnHeldCount++;
            fnReleaseCountdown = 0; // Cancel any pending Fn release

            if (!fnHeld) {
                // First number key: set Fn now, longer delay for letter
                bus.setKeyState(7, 0x8000, true);
                fnHeld = true;
                queuePendingLetter(letterCol, letterBit, FN_FIRST_DELAY);
            } else {
                // Fn already held: shorter delay (just needs 1 scan cycle gap)
                queuePendingLetter(letterCol, letterBit, FN_NEXT_DELAY);
            }
        } else {
            // Release the letter key immediately
            bus.setKeyState(letterCol, letterBit, false);
            removePendingLetter(letterCol, letterBit);
            fnHeldCount = Math.max(0, fnHeldCount - 1);

            // When all number keys released, start Fn release countdown
            if (fnHeldCount == 0 && fnHeld) {
                fnReleaseCountdown = FN_RELEASE_DELAY;
            }
        }
    }

    private void queuePendingLetter(int col, int bit, int delay) {
        // Replace existing entry for same key, or add new
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
            // Process minimum hold timers - release keys whose hold time expired
            for (int i = 0; i < heldCount; i++) {
                if (heldFramesLeft[i] > 0) heldFramesLeft[i]--;
                if (heldFramesLeft[i] <= 0 && heldReleasePending[i]) {
                    bus.setKeyState(heldCols[i], heldBits[i], false);
                    removeHeldKey(i);
                    i--; // re-check this index
                }
            }

            // Process queued Fn+letter presses (each with its own countdown)
            for (int i = 0; i < pendingCount; i++) {
                if (--pendingDelays[i] <= 0) {
                    bus.setKeyState(pendingCols[i], pendingBits[i], true);
                    // Remove from queue (swap with last)
                    pendingCount--;
                    if (i < pendingCount) {
                        pendingCols[i] = pendingCols[pendingCount];
                        pendingBits[i] = pendingBits[pendingCount];
                        pendingDelays[i] = pendingDelays[pendingCount];
                    }
                    i--; // re-check this index
                }
            }

            // Delayed Fn release (keeps Fn held between consecutive numbers)
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
            // Greenish LCD tint (pre-multiplied constants: 180/255≈0.706, 210/255≈0.824, 160/255≈0.627)
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
