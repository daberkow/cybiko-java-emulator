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
