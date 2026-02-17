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
    private final JPanel panel;
    private AddressBus bus; // Set after construction to receive key events

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
            bus.setKeyState(col, bit, pressed);
        }
    }

    /** Simulate Fn + letter key press for number key input. */
    private void setFnLetter(int letterCol, int letterBit, boolean pressed) {
        bus.setKeyState(7, 0x8000, pressed);          // Fn key
        bus.setKeyState(letterCol, letterBit, pressed); // Letter key
    }

    @Override
    public void render(int[] pixels, int width, int height) {
        for (int i = 0; i < pixels.length && i < width * height; i++) {
            int gray = Math.max(0, Math.min(255, pixels[i]));
            // Greenish LCD tint
            int r = (gray * 180) / 255;
            int g = (gray * 210) / 255;
            int b = (gray * 160) / 255;
            image.setRGB(i % width, i / width, (r << 16) | (g << 8) | b);
        }
        panel.repaint();
    }

    @Override
    public void close() {
        frame.dispose();
    }

    public JFrame getFrame() { return frame; }
}
