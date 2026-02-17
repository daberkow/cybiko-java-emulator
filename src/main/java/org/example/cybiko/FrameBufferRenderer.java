package org.example.cybiko;

/** Interface for rendering the LCD frame buffer to a display output. */
public interface FrameBufferRenderer {
    /** Render a frame of pixels (grayscale 0-255). */
    void render(int[] pixels, int width, int height);

    /** Called when the emulator shuts down. */
    default void close() {}
}
