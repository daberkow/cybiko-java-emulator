package com.github.daberkow.cybiko.manager.io;

import java.util.Arrays;

/**
 * Decompresses Cybiko filer.exe compressed data blobs.
 * <p>
 * Type byte 0x00: uncompressed — raw bytes follow the type byte.
 * Type byte 0x02: compressed LZ77 bitstream with prefix-tree coded lengths
 * and prefix-tree coded offsets (reverse-engineered from filer.exe).
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
                int offset = decodeOffset(bits);
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
     * Decode match length (reverse-engineered from filer.exe 0x402bc9-0x402db4).
     * <pre>
     * readBits(2):
     *   00 → 2
     *   01 → 3
     *   10 → readBit() + 4                          (4-5)
     *   11 → readBits(2):
     *     00 → 6
     *     01 → readBit() + 8                         (8-9)
     *     10 → readBit(): 0→7, 1→readBit()+10        (7,10-11)
     *     11 → readBits(3) jump table:
     *       0→12, 1→13, 2→readBits(2)+20, 3→readBit()+16,
     *       4→readBits(3)+24, 5→14,
     *       6→readBit(): 0→readBits(5)+32, 1→readBit()+18,
     *       7→readBit(): 0→15, 1→readBit(): 0→readBits(6)+64,
     *         1→readBit(): 0→readBits(7)+128, 1→256
     * </pre>
     */
    private static int decodeLength(BitReader bits) {
        int prefix = bits.readBitsMsb(2);
        return switch (prefix) {
            case 0 -> 2;
            case 1 -> 3;
            case 2 -> 4 + bits.readBit();
            case 3 -> {
                int sub = bits.readBitsMsb(2);
                yield switch (sub) {
                    case 0 -> 6;
                    case 1 -> 8 + bits.readBit();
                    case 2 -> bits.readBit() == 0 ? 7 : 10 + bits.readBit();
                    case 3 -> decodeLengthLevel3(bits);
                    default -> throw new IllegalStateException();
                };
            }
            default -> throw new IllegalStateException();
        };
    }

    private static int decodeLengthLevel3(BitReader bits) {
        int idx = bits.readBitsMsb(3);
        if (idx <= 6) {
            return switch (idx) {
                case 0 -> 12;
                case 1 -> 13;
                case 2 -> 20 + bits.readBitsMsb(2);
                case 3 -> 16 + bits.readBit();
                case 4 -> 24 + bits.readBitsMsb(3);
                case 5 -> 14;
                case 6 -> bits.readBit() == 0
                        ? 32 + bits.readBitsMsb(5)
                        : 18 + bits.readBit();
                default -> throw new IllegalStateException();
            };
        }
        // idx > 6 (case 7)
        if (bits.readBit() == 0) return 15;
        if (bits.readBit() == 0) return 64 + bits.readBitsMsb(6);
        if (bits.readBit() == 0) return 128 + bits.readBitsMsb(7);
        return 256;
    }

    /**
     * Decode match offset (reverse-engineered from filer.exe 0x402db4-0x402f8b).
     * <pre>
     * readBits(3):
     *   0 → readBits(10) + 1024       (1024-2047)
     *   1 → readBits(9) + 512         (512-1023)
     *   2 → readBits(8) + 256         (256-511)
     *   3 → readBits(6) + 64          (64-127)
     *   4 → readBits(7) + 128         (128-255)
     *   5 → readBit(): 0→readBits(11)+2048, 1→readBits(12)+4096
     *   6 → readBit(): 0→readBits(5)+32, 1→readBits(4)+16
     *   7 → readBits(2):
     *     0 → readBit()               (0-1)
     *     1 → readBits(2) + 6         (6-9)
     *     2 → readBit(): 0→readBit()+4, 1→2       (2,4-5)
     *     3 → readBits(2):
     *       0→readBit()+12, 1→readBit()+14, 2→3, 3→readBit()+10
     * </pre>
     */
    private static int decodeOffset(BitReader bits) {
        int prefix = bits.readBitsMsb(3);
        return switch (prefix) {
            case 0 -> 1024 + bits.readBitsMsb(10);
            case 1 -> 512 + bits.readBitsMsb(9);
            case 2 -> 256 + bits.readBitsMsb(8);
            case 3 -> 64 + bits.readBitsMsb(6);
            case 4 -> 128 + bits.readBitsMsb(7);
            case 5 -> bits.readBit() == 0
                    ? 2048 + bits.readBitsMsb(11)
                    : 4096 + bits.readBitsMsb(12);
            case 6 -> bits.readBit() == 0
                    ? 32 + bits.readBitsMsb(5)
                    : 16 + bits.readBitsMsb(4);
            case 7 -> {
                int sub = bits.readBitsMsb(2);
                yield switch (sub) {
                    case 0 -> bits.readBit();
                    case 1 -> 6 + bits.readBitsMsb(2);
                    case 2 -> bits.readBit() == 0
                            ? 4 + bits.readBit()
                            : 2;
                    case 3 -> {
                        int sub2 = bits.readBitsMsb(2);
                        yield switch (sub2) {
                            case 0 -> 12 + bits.readBit();
                            case 1 -> 14 + bits.readBit();
                            case 2 -> 3;
                            case 3 -> 10 + bits.readBit();
                            default -> throw new IllegalStateException();
                        };
                    }
                    default -> throw new IllegalStateException();
                };
            }
            default -> throw new IllegalStateException();
        };
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
