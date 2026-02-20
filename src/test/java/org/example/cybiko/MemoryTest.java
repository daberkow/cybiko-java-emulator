package org.example.cybiko;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MemoryTest {

    @Test void read8Write8() {
        Memory mem = new Memory(256, true);
        mem.write8(0, 0xAB);
        assertEquals(0xAB, mem.read8(0));
    }

    @Test void read16BigEndian() {
        Memory mem = new Memory(256, true);
        mem.write8(0, 0x12);
        mem.write8(1, 0x34);
        assertEquals(0x1234, mem.read16(0));
    }

    @Test void write16BigEndian() {
        Memory mem = new Memory(256, true);
        mem.write16(0, 0xABCD);
        assertEquals(0xAB, mem.read8(0));
        assertEquals(0xCD, mem.read8(1));
    }

    @Test void read32BigEndian() {
        Memory mem = new Memory(256, true);
        mem.write32(0, 0x12345678);
        assertEquals(0x12345678, mem.read32(0));
        assertEquals(0x12, mem.read8(0));
        assertEquals(0x34, mem.read8(1));
        assertEquals(0x56, mem.read8(2));
        assertEquals(0x78, mem.read8(3));
    }

    @Test void romIsNotWritable() {
        Memory rom = new Memory(256, false);
        rom.write8(0, 0xFF);
        assertEquals(0, rom.read8(0), "ROM write should be ignored");
    }

    @Test void loadIntoRom() {
        Memory rom = new Memory(256, false);
        rom.load(new byte[]{0x12, 0x34, 0x56}, 0);
        assertEquals(0x12, rom.read8(0));
        assertEquals(0x34, rom.read8(1));
        assertEquals(0x56, rom.read8(2));
    }

    @Test void outOfBoundsReturnsZero() {
        Memory mem = new Memory(16, true);
        assertEquals(0, mem.read8(100));
        assertEquals(0, mem.read16(100));
        assertEquals(0, mem.read32(100));
    }

    @Test void outOfBoundsWriteIgnored() {
        Memory mem = new Memory(16, true);
        mem.write8(100, 0xFF); // should not throw
    }

    @Test void loadAtOffset() {
        Memory mem = new Memory(256, true);
        mem.load(new byte[]{(byte) 0xAA, (byte) 0xBB}, 10);
        assertEquals(0, mem.read8(9));
        assertEquals(0xAA, mem.read8(10));
        assertEquals(0xBB, mem.read8(11));
    }

    @Test void size() {
        assertEquals(1024, new Memory(1024, true).size());
    }
}
