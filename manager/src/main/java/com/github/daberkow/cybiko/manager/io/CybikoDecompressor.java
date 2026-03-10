package com.github.daberkow.cybiko.manager.io;

import java.util.Arrays;

/**
 * Decompresses Cybiko filer.exe compressed data blobs.
 * <p>
 * Type byte 0x00: uncompressed — raw bytes follow the type byte.
 * Type byte 0x02: compressed LZ77 bitstream with prefix-tree coded lengths
 * and dynamically-sized offsets.
 */
public final class CybikoDecompressor {

    private CybikoDecompressor() {}

    /**
     * Decompress a Cybiko data blob.
     *
     * @param blob the raw blob (first byte is type)
     * @return decompressed data
     * @throws IllegalArgumentException if the type byte is not recognized
     */
    public static byte[] decompress(byte[] blob) {
        if (blob.length == 0) {
            throw new IllegalArgumentException("Empty blob");
        }
        int type = blob[0] & 0xFF;
        return switch (type) {
            case 0x00 -> Arrays.copyOfRange(blob, 1, blob.length);
            case 0x02 -> decompressLz77(blob);
            default -> throw new IllegalArgumentException(
                    "Unknown compression type: 0x" + Integer.toHexString(type));
        };
    }

    private static byte[] decompressLz77(byte[] blob) {
        // Bytes 1-4: uncompressed size (big-endian)
        int uncompressedSize = ((blob[1] & 0xFF) << 24)
                | ((blob[2] & 0xFF) << 16)
                | ((blob[3] & 0xFF) << 8)
                | (blob[4] & 0xFF);

        byte[] output = new byte[uncompressedSize];
        BitReader bits = new BitReader(blob, 5);
        int pos = 0;

        while (pos < uncompressedSize) {
            int flag = bits.readBit();
            if (flag == 0) {
                // Literal byte (MSB-first accumulation)
                output[pos++] = (byte) bits.readBitsMsb(8);
            } else {
                // Back-reference
                int length = decodeLength(bits);
                int offset = decodeOffset(bits, pos, length);
                int source = pos - offset - length;
                int actual = Math.min(length, uncompressedSize - pos);
                for (int i = 0; i < actual; i++) {
                    int idx = source + i;
                    output[pos++] = (idx >= 0) ? output[idx] : 0;
                }
            }
        }

        return output;
    }

    /**
     * Decode match length using prefix tree:
     * <pre>
     * 0 → 2
     * 1,0 → 3
     * 1,1,0 → 4
     * 1,1,1,0,0 → 5
     * 1,1,1,0,1 → 6
     * 1,1,1,1,0,0 → 7
     * 1,1,1,1,0,1 → 8
     * 1,1,1,1,1 → read 8 bits MSB + 1
     * </pre>
     */
    private static int decodeLength(BitReader bits) {
        if (bits.readBit() == 0) return 2;
        if (bits.readBit() == 0) return 3;
        if (bits.readBit() == 0) return 4;
        if (bits.readBit() == 0) {
            return 5 + bits.readBit();
        }
        if (bits.readBit() == 0) {
            return 7 + bits.readBit();
        }
        return bits.readBitsMsb(8) + 1;
    }

    /**
     * Decode match offset using dynamic bit width based on current position.
     * The number of bits read equals {@code Integer.SIZE - Integer.numberOfLeadingZeros(pos - length)}
     * (i.e., the bit length of the maximum possible back-reference distance).
     * Bits are accumulated MSB-first.
     */
    private static int decodeOffset(BitReader bits, int pos, int length) {
        int maxBack = pos - length;
        if (maxBack <= 0) {
            return 0;
        }
        int offsetBits = 32 - Integer.numberOfLeadingZeros(maxBack);
        return bits.readBitsMsb(offsetBits);
    }

    /**
     * Bit reader that extracts bits LSB-first from each byte.
     * Multi-bit reads accumulate MSB-first (first bit read becomes the
     * highest bit of the result value).
     */
    static final class BitReader {
        private final byte[] data;
        private int bytePos;
        private int currentByte;
        private int bitsLeft;

        BitReader(byte[] data, int startOffset) {
            this.data = data;
            this.bytePos = startOffset;
            this.bitsLeft = 0;
        }

        /**
         * Read a single bit, LSB-first from the current byte.
         */
        int readBit() {
            if (bitsLeft == 0) {
                currentByte = data[bytePos++] & 0xFF;
                bitsLeft = 8;
            }
            int bit = currentByte & 1;
            currentByte >>>= 1;
            bitsLeft--;
            return bit;
        }

        /**
         * Read multiple bits, MSB-first accumulation.
         * The first bit read becomes the highest bit of the result.
         */
        int readBitsMsb(int count) {
            int value = 0;
            for (int i = 0; i < count; i++) {
                value = (value << 1) | readBit();
            }
            return value;
        }
    }
}
