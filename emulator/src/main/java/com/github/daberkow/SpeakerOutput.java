package com.github.daberkow;

import javax.sound.sampled.*;

/**
 * 1-bit speaker audio output using javax.sound.sampled.
 *
 * The Cybiko speaker is driven by a single digital pin (Port 1 bit 3).
 * This class converts the pin level transitions into PCM audio samples and
 * streams them to the system audio device.
 */
public class SpeakerOutput {
    private static final int SAMPLE_RATE = 48000;
    private static final int BUFFER_SIZE = 2048;
    private static final int MAX_TRANSITIONS = 4096;

    private SourceDataLine line;
    private final byte[] buffer = new byte[BUFFER_SIZE];
    private int bufferPos = 0;
    private int currentLevel = 0;
    private boolean open = false;

    // Timing: how many CPU cycles per audio sample
    private final double cyclesPerSample;
    private double cycleFraction = 0;

    // Transition tracking for proper waveform reconstruction
    private int frameStartLevel = 0;
    private int frameCycle = 0;
    private final int[] transitionCycles = new int[MAX_TRANSITIONS];
    private final int[] transitionLevels = new int[MAX_TRANSITIONS];
    private int transitionCount = 0;

    public SpeakerOutput(long clockHz) {
        this.cyclesPerSample = (double) clockHz / SAMPLE_RATE;
        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 8, 1, false, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) {
                Log.log(Log.Category.SPEAKER, "[SPEAKER] Audio line not supported");
                return;
            }
            line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(format, BUFFER_SIZE * 4);
            line.start();
            open = true;
            Log.log(Log.Category.SPEAKER, "[SPEAKER] Audio output opened: " + SAMPLE_RATE + " Hz, 8-bit mono");
        } catch (LineUnavailableException e) {
            Log.log(Log.Category.SPEAKER, "[SPEAKER] Could not open audio: " + e.getMessage());
        }
    }

    /** Backward-compatible constructor (XT clock rate). */
    public SpeakerOutput() {
        this(18_432_000L);
    }

    /** Call at the start of each frame before the CPU cycle loop. */
    public void beginFrame() {
        frameStartLevel = currentLevel;
        frameCycle = 0;
        transitionCount = 0;
    }

    /** Set the current frame cycle (call each iteration of the CPU loop). */
    public void setFrameCycle(int cycle) {
        frameCycle = cycle;
    }

    public void setLevel(int level) {
        if (level != currentLevel) {
            currentLevel = level;
            if (transitionCount < MAX_TRANSITIONS) {
                transitionCycles[transitionCount] = frameCycle;
                transitionLevels[transitionCount] = level;
                transitionCount++;
            }
        }
    }

    public void generateSamples(int cpuCycles) {
        if (!open) return;

        cycleFraction += cpuCycles;
        int samplesToWrite = (int) (cycleFraction / cyclesPerSample);
        cycleFraction -= samplesToWrite * cyclesPerSample;

        if (transitionCount == 0) {
            // No transitions this frame - output silence
            for (int i = 0; i < samplesToWrite; i++) {
                buffer[bufferPos++] = (byte) 128;
                if (bufferPos >= buffer.length) flush();
            }
        } else {
            // Walk through transitions to produce proper waveform
            int transIdx = 0;
            int level = frameStartLevel;

            for (int i = 0; i < samplesToWrite; i++) {
                double sampleCycle = (i + 0.5) * cyclesPerSample;

                while (transIdx < transitionCount &&
                       transitionCycles[transIdx] <= (int) sampleCycle) {
                    level = transitionLevels[transIdx];
                    transIdx++;
                }

                buffer[bufferPos++] = (level == 0) ? (byte) 96 : (byte) 160;
                if (bufferPos >= buffer.length) flush();
            }
        }
    }

    private void flush() {
        if (!open || bufferPos == 0) return;
        line.write(buffer, 0, bufferPos);
        bufferPos = 0;
    }

    public void close() {
        if (!open) return;
        flush();
        line.drain();
        line.close();
        open = false;
    }
}
