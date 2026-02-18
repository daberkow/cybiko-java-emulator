package org.example.cybiko;

import javax.sound.sampled.*;

/**
 * 1-bit speaker audio output using javax.sound.sampled.
 *
 * The Cybiko Xtreme speaker is driven by a single digital pin (Port 1 bit 3).
 * This class converts the pin level transitions into PCM audio samples and
 * streams them to the system audio device.
 *
 * For ESP32 port: replace this class with DAC/I2S output but keep the
 * setLevel(int) interface.
 */
public class SpeakerOutput {
    private static final int SAMPLE_RATE = 44100;
    private static final int BUFFER_SIZE = 2048; // ~46ms of audio

    private SourceDataLine line;
    private final byte[] buffer = new byte[BUFFER_SIZE];
    private int bufferPos = 0;
    private int currentLevel = 0; // 0 or 1
    private boolean open = false;

    // Timing: how many CPU cycles per audio sample
    // 18,432,000 Hz CPU / 44,100 Hz audio = ~417.9 cycles per sample
    private static final double CYCLES_PER_SAMPLE = 18_432_000.0 / SAMPLE_RATE;
    private double cycleFraction = 0;

    public SpeakerOutput() {
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

    /** Set the current speaker pin level (0 or 1). Called from AddressBus on Port 1 writes. */
    public void setLevel(int level) {
        currentLevel = level;
    }

    /**
     * Generate audio samples for the given number of CPU cycles.
     * Call this once per frame from the main loop.
     */
    public void generateSamples(int cpuCycles) {
        if (!open) return;

        cycleFraction += cpuCycles;
        int samplesToWrite = (int) (cycleFraction / CYCLES_PER_SAMPLE);
        cycleFraction -= samplesToWrite * CYCLES_PER_SAMPLE;

        // Convert 0/1 level to unsigned 8-bit PCM (128 = silence, 0/255 = extremes)
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

    /** Flush remaining samples and close the audio line. */
    public void close() {
        if (!open) return;
        flush();
        line.drain();
        line.close();
        open = false;
    }
}
