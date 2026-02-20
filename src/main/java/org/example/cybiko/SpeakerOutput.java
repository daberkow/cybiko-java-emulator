package org.example.cybiko;

import javax.sound.sampled.*;

/**
 * 1-bit speaker audio output using javax.sound.sampled.
 *
 * The Cybiko speaker is driven by a single digital pin (Port 1 bit 3).
 * This class converts the pin level transitions into PCM audio samples and
 * streams them to the system audio device.
 */
public class SpeakerOutput {
    private static final int SAMPLE_RATE = 44100;
    private static final int BUFFER_SIZE = 2048;

    private SourceDataLine line;
    private final byte[] buffer = new byte[BUFFER_SIZE];
    private int bufferPos = 0;
    private int currentLevel = 0;
    private boolean open = false;

    // Timing: how many CPU cycles per audio sample
    private final double cyclesPerSample;
    private double cycleFraction = 0;

    public SpeakerOutput(long clockHz) {
        this.cyclesPerSample = (double) clockHz / SAMPLE_RATE;
        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 8, 1, false, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) {
                System.err.println("[SPEAKER] Audio line not supported");
                return;
            }
            line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(format, BUFFER_SIZE * 4);
            line.start();
            open = true;
            System.err.println("[SPEAKER] Audio output opened: " + SAMPLE_RATE + " Hz, 8-bit mono");
        } catch (LineUnavailableException e) {
            System.err.println("[SPEAKER] Could not open audio: " + e.getMessage());
        }
    }

    /** Backward-compatible constructor (XT clock rate). */
    public SpeakerOutput() {
        this(18_432_000L);
    }

    public void setLevel(int level) {
        currentLevel = level;
    }

    public void generateSamples(int cpuCycles) {
        if (!open) return;

        cycleFraction += cpuCycles;
        int samplesToWrite = (int) (cycleFraction / cyclesPerSample);
        cycleFraction -= samplesToWrite * cyclesPerSample;

        byte sample = currentLevel == 0 ? (byte) 96 : (byte) 160;

        for (int i = 0; i < samplesToWrite; i++) {
            buffer[bufferPos++] = sample;
            if (bufferPos >= buffer.length) {
                flush();
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
