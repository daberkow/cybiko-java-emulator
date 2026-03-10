package com.github.daberkow.cybiko.manager.io;

import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

/**
 * Renders Cybiko 2bpp grayscale icon/picture data to ARGB pixels and JavaFX Images.
 * <p>
 * Icon format (.ico / .pic) after decompression:
 * <pre>
 * Byte 0:   file type (0x02 = picture)
 * Byte 1:   number of images
 * Byte 2:   width in pixels
 * Byte 3:   height in pixels
 * Byte 4:   left coordinate
 * Byte 5:   top coordinate
 * Byte 6:   display width
 * Byte 7:   display height
 * Byte 8+:  pixel data (2bpp, 4 pixels per byte, MSB = leftmost)
 * </pre>
 */
public final class CybikoIconRenderer {

    /** Header size in bytes. */
    private static final int HEADER_SIZE = 8;

    /** ARGB palette: 0=white, 1=light gray, 2=dark gray, 3=black. */
    private static final int[] PALETTE = {
            0xFFFFFFFF, // 0 → white
            0xFFAAAAAA, // 1 → light gray
            0xFF555555, // 2 → dark gray
            0xFF000000  // 3 → black
    };

    private CybikoIconRenderer() {}

    /** Width and height parsed from an icon header. */
    public record IconInfo(int width, int height) {}

    /**
     * Parse the icon header to extract dimensions.
     *
     * @param icoData decompressed icon bytes
     * @return icon dimensions, or null if data is null or too short
     */
    public static IconInfo parseHeader(byte[] icoData) {
        if (icoData == null || icoData.length < HEADER_SIZE) {
            return null;
        }
        int width = icoData[2] & 0xFF;
        int height = icoData[3] & 0xFF;
        return new IconInfo(width, height);
    }

    /**
     * Decode 2bpp pixel data to an ARGB int array.
     *
     * @param icoData decompressed icon bytes (header + pixel data)
     * @return ARGB pixels (width * height), or null if data is invalid
     */
    public static int[] toArgbPixels(byte[] icoData) {
        IconInfo info = parseHeader(icoData);
        if (info == null) {
            return null;
        }
        int width = info.width();
        int height = info.height();
        int rowBytes = (width + 3) / 4; // ceil(width / 4)
        int expectedSize = HEADER_SIZE + rowBytes * height;
        if (icoData.length < expectedSize) {
            return null;
        }

        int[] pixels = new int[width * height];
        int offset = HEADER_SIZE;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int byteIndex = offset + y * rowBytes + (x / 4);
                int shift = 6 - (x % 4) * 2; // MSB = leftmost pixel
                int value = (icoData[byteIndex] >> shift) & 0x03;
                pixels[y * width + x] = PALETTE[value];
            }
        }
        return pixels;
    }

    /**
     * Decode icon data to a JavaFX Image.
     * <p>
     * Requires JavaFX runtime — not suitable for headless unit tests.
     *
     * @param icoData decompressed icon bytes
     * @return JavaFX Image, or null if data is invalid
     */
    public static Image toImage(byte[] icoData) {
        IconInfo info = parseHeader(icoData);
        if (info == null) {
            return null;
        }
        int[] pixels = toArgbPixels(icoData);
        if (pixels == null) {
            return null;
        }
        int width = info.width();
        int height = info.height();
        WritableImage image = new WritableImage(width, height);
        image.getPixelWriter().setPixels(0, 0, width, height,
                PixelFormat.getIntArgbInstance(), pixels, 0, width);
        return image;
    }
}
