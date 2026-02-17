package org.example.cybiko;

/** ASCII art console renderer for debugging. */
public class ConsoleRenderer implements FrameBufferRenderer {
    // Characters from darkest to lightest
    private static final char[] SHADES = {' ', '.', '+', '#'};

    @Override
    public void render(int[] pixels, int width, int height) {
        StringBuilder sb = new StringBuilder();
        sb.append("+" + "-".repeat(width / 2) + "+\n");
        // Sample every other row and every other column for compact output
        for (int y = 0; y < height; y += 2) {
            sb.append('|');
            for (int x = 0; x < width; x += 2) {
                int gray = pixels[y * width + x];
                int shade = (gray * 3) / 255;
                shade = Math.max(0, Math.min(3, shade));
                sb.append(SHADES[shade]);
            }
            sb.append("|\n");
        }
        sb.append("+" + "-".repeat(width / 2) + "+");
        System.out.println(sb);
    }
}
