package com.github.daberkow;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SerialBridgeTest {

    /** Mock serial port for testing without PTY. */
    static class MockSerialPort implements SerialPort {
        final java.util.List<Integer> written = new java.util.ArrayList<>();
        final java.util.Queue<Integer> rxQueue = new java.util.LinkedList<>();

        @Override public void write(int b) { written.add(b); }
        @Override public boolean hasData() { return !rxQueue.isEmpty(); }
        @Override public int read() { Integer b = rxQueue.poll(); return b != null ? b : -1; }
        @Override public void close() {}
        @Override public String getPath() { return "/dev/null"; }

        void queueRx(int... bytes) {
            for (int b : bytes) rxQueue.add(b);
        }
    }

    /** Create an AddressBus with on-chip RAM wired up (needed for SCI register writes). */
    private static AddressBus createBus(MachineConfig.MachineType type) {
        MachineConfig config = MachineConfig.forType(type);
        AddressBus bus = new AddressBus(config);
        bus.setOnChipRam(new Memory(config.onChipRamSize, true));
        return bus;
    }

    @Test
    void sci2TdrWriteForwardsToSerialPort() {
        AddressBus bus = createBus(MachineConfig.MachineType.V1);
        MockSerialPort serial = new MockSerialPort();
        bus.setSerialPort(serial);

        // Write 'H' to SCI2 TDR (0xFFFF8B)
        bus.write8(0xFFFF8B, 0x48);

        assertEquals(1, serial.written.size());
        assertEquals(0x48, serial.written.get(0));
    }

    @Test
    void sci2SsrReflectsSerialRdrf() {
        AddressBus bus = createBus(MachineConfig.MachineType.V1);
        MockSerialPort serial = new MockSerialPort();
        bus.setSerialPort(serial);

        // SSR should not have RDRF when no data
        int ssr = bus.read8(0xFFFF8C);
        assertEquals(0, ssr & 0x40, "RDRF should be clear with no data");

        // Queue a byte and tick
        serial.queueRx(0x41);
        bus.tickSerial();

        // SSR should now have RDRF
        ssr = bus.read8(0xFFFF8C);
        assertNotEquals(0, ssr & 0x40, "RDRF should be set after tickSerial with data");
    }

    @Test
    void sci2RdrReturnsSerialByte() {
        AddressBus bus = createBus(MachineConfig.MachineType.V1);
        MockSerialPort serial = new MockSerialPort();
        bus.setSerialPort(serial);

        serial.queueRx(0x55);
        bus.tickSerial();

        // Read RDR should return the queued byte
        int rdr = bus.read8(0xFFFF8D);
        assertEquals(0x55, rdr);

        // RDRF should be cleared after read
        int ssr = bus.read8(0xFFFF8C);
        assertEquals(0, ssr & 0x40, "RDRF should clear after RDR read");
    }

    @Test
    void serialNotActiveOnXt() {
        AddressBus bus = createBus(MachineConfig.MachineType.XT);
        RadioCoProcessor radio = new RadioCoProcessor();
        bus.setRadio(radio); // Sets radioSciChannel = 2

        MockSerialPort serial = new MockSerialPort();
        bus.setSerialPort(serial);

        // Write to SCI2 TDR — should NOT go to serial (XT uses SCI2 for radio)
        bus.write8(0xFFFF8B, 0x48);
        assertTrue(serial.written.isEmpty(), "Serial should be inactive on XT");
    }

    @Test
    void tdreRestoredAfterDelay() {
        AddressBus bus = createBus(MachineConfig.MachineType.V1);
        MockSerialPort serial = new MockSerialPort();
        bus.setSerialPort(serial);

        // Write to TDR — TDRE should clear
        bus.write8(0xFFFF8B, 0x41);
        int ssr = bus.read8(0xFFFF8C);
        assertEquals(0, ssr & 0x80, "TDRE should be clear right after TDR write");

        // Tick enough times for TDRE to restore (SCI2_TXI_DELAY = 32)
        // tickSci2() handles the TXI delay countdown
        for (int i = 0; i < 33; i++) bus.tickSci2();

        ssr = bus.read8(0xFFFF8C);
        assertNotEquals(0, ssr & 0x80, "TDRE should be set after delay ticks");
    }
}
