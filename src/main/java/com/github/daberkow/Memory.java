package com.github.daberkow;

/**
 * Simple byte-array backed memory region with big-endian access.
 * Used for ROM, RAM, and Flash storage.
 */
public class Memory {
    private final byte[] data;
    private final boolean writable;

    public Memory(int size, boolean writable) {
        this.data = new byte[size];
        this.writable = writable;
    }

    public int size() {
        return data.length;
    }

    public int read8(int offset) {
        if (offset < 0 || offset >= data.length) return 0;
        return data[offset] & 0xFF;
    }

    public int read16(int offset) {
        if (offset < 0 || offset + 1 >= data.length) return 0;
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    public int read32(int offset) {
        if (offset < 0 || offset + 3 >= data.length) return 0;
        return ((data[offset] & 0xFF) << 24)
             | ((data[offset + 1] & 0xFF) << 16)
             | ((data[offset + 2] & 0xFF) << 8)
             | (data[offset + 3] & 0xFF);
    }

    public void write8(int offset, int value) {
        if (!writable) return;
        if (offset < 0 || offset >= data.length) return;
        data[offset] = (byte) value;
    }

    public void write16(int offset, int value) {
        if (!writable) return;
        if (offset < 0 || offset + 1 >= data.length) return;
        data[offset] = (byte) (value >> 8);
        data[offset + 1] = (byte) value;
    }

    public void write32(int offset, int value) {
        if (!writable) return;
        if (offset < 0 || offset + 3 >= data.length) return;
        data[offset] = (byte) (value >> 24);
        data[offset + 1] = (byte) (value >> 16);
        data[offset + 2] = (byte) (value >> 8);
        data[offset + 3] = (byte) value;
    }

    /** Load raw bytes into this memory region (for ROM loading). */
    public void load(byte[] src, int destOffset) {
        System.arraycopy(src, 0, data, destOffset, Math.min(src.length, data.length - destOffset));
    }

    /** Direct access to backing array (for bulk operations). */
    public byte[] getRawData() {
        return data;
    }
}
