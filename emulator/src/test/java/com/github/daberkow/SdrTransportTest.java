package com.github.daberkow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SdrTransport}.
 *
 * <p>Uses ephemeral ports ({@code new ServerSocket(0)}) to avoid conflicts.
 */
@Timeout(10)
class SdrTransportTest {

    /** Verify default constants. */
    @Test void defaultConstants() {
        assertEquals("localhost", SdrTransport.DEFAULT_HOST);
        assertEquals(19201, SdrTransport.DEFAULT_PORT);
    }

    /** getDeviceId returns the ID passed at construction time. */
    @Test void deviceIdIsRetained() {
        SdrTransport t = new SdrTransport(0xCAFEBABE);
        assertEquals(0xCAFEBABE, t.getDeviceId());
    }

    /** Send a packet and verify the server receives the correct wire format. */
    @Test void sendPacketWireFormat() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            SdrTransport transport = new SdrTransport(0x0001, "localhost", port);

            // Accept in background
            CountDownLatch connected = new CountDownLatch(1);
            AtomicReference<Socket> clientRef = new AtomicReference<>();
            Thread acceptThread = new Thread(() -> {
                try {
                    clientRef.set(server.accept());
                    connected.countDown();
                } catch (IOException ignored) {}
            });
            acceptThread.setDaemon(true);
            acceptThread.start();

            try {
                transport.start();
                assertTrue(connected.await(2, TimeUnit.SECONDS), "Server should accept connection");

                byte[] payload = {0x10, 0x20, 0x30, 0x40, 0x50};
                transport.sendPacket(payload, 7);

                // Read the wire packet from the server side
                Socket client = clientRef.get();
                DataInputStream serverIn = new DataInputStream(client.getInputStream());

                int length = serverIn.readUnsignedShort();
                assertEquals(payload.length + 1, length,
                        "Wire length should be channel byte + payload size");

                int channel = serverIn.readUnsignedByte();
                assertEquals(7, channel, "Channel byte should match");

                byte[] received = new byte[length - 1];
                serverIn.readFully(received);
                assertArrayEquals(payload, received, "Payload should match");
            } finally {
                transport.close();
                Socket c = clientRef.get();
                if (c != null) c.close();
            }
        }
    }

    /** Server sends a length-prefixed message; verify the listener receives it. */
    @Test void receivePacketFromServer() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            SdrTransport transport = new SdrTransport(0x0002, "localhost", port);

            CountDownLatch connected = new CountDownLatch(1);
            AtomicReference<Socket> clientRef = new AtomicReference<>();
            Thread acceptThread = new Thread(() -> {
                try {
                    clientRef.set(server.accept());
                    connected.countDown();
                } catch (IOException ignored) {}
            });
            acceptThread.setDaemon(true);
            acceptThread.start();

            try {
                CountDownLatch received = new CountDownLatch(1);
                AtomicReference<byte[]> receivedData = new AtomicReference<>();
                AtomicInteger receivedChannel = new AtomicInteger(-1);

                transport.setChannel(3);
                transport.setPacketListener((data, channel, senderId) -> {
                    receivedData.set(data);
                    receivedChannel.set(channel);
                    received.countDown();
                });

                transport.start();
                assertTrue(connected.await(2, TimeUnit.SECONDS), "Server should accept connection");

                // Server sends a packet to the transport
                Socket client = clientRef.get();
                DataOutputStream serverOut = new DataOutputStream(client.getOutputStream());
                byte[] payload = {(byte) 0xAA, (byte) 0xBB, (byte) 0xCC};
                synchronized (serverOut) {
                    serverOut.writeShort(payload.length + 1); // length = channel + payload
                    serverOut.writeByte(3); // channel
                    serverOut.write(payload);
                    serverOut.flush();
                }

                assertTrue(received.await(2, TimeUnit.SECONDS),
                        "Listener should receive the packet");
                assertArrayEquals(payload, receivedData.get());
                assertEquals(3, receivedChannel.get());
            } finally {
                transport.close();
                Socket c = clientRef.get();
                if (c != null) c.close();
            }
        }
    }

    /** Packets on a different channel than the listening channel are discarded. */
    @Test void wrongChannelIsFiltered() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            SdrTransport transport = new SdrTransport(0x0003, "localhost", port);

            CountDownLatch connected = new CountDownLatch(1);
            AtomicReference<Socket> clientRef = new AtomicReference<>();
            Thread acceptThread = new Thread(() -> {
                try {
                    clientRef.set(server.accept());
                    connected.countDown();
                } catch (IOException ignored) {}
            });
            acceptThread.setDaemon(true);
            acceptThread.start();

            try {
                CountDownLatch received = new CountDownLatch(1);
                transport.setChannel(5);
                transport.setPacketListener((data, channel, senderId) -> received.countDown());

                transport.start();
                assertTrue(connected.await(2, TimeUnit.SECONDS));

                // Send on channel 9, transport listens on channel 5
                Socket client = clientRef.get();
                DataOutputStream serverOut = new DataOutputStream(client.getOutputStream());
                serverOut.writeShort(2); // length = 1 channel + 1 byte payload
                serverOut.writeByte(9);  // wrong channel
                serverOut.writeByte(0x42);
                serverOut.flush();

                assertFalse(received.await(500, TimeUnit.MILLISECONDS),
                        "Transport should not deliver packets from a different channel");
            } finally {
                transport.close();
                Socket c = clientRef.get();
                if (c != null) c.close();
            }
        }
    }

    /** close() disconnects cleanly without errors. */
    @Test void closeDisconnects() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            SdrTransport transport = new SdrTransport(0x0004, "localhost", port);

            CountDownLatch connected = new CountDownLatch(1);
            AtomicReference<Socket> clientRef = new AtomicReference<>();
            Thread acceptThread = new Thread(() -> {
                try {
                    clientRef.set(server.accept());
                    connected.countDown();
                } catch (IOException ignored) {}
            });
            acceptThread.setDaemon(true);
            acceptThread.start();

            transport.start();
            assertTrue(connected.await(2, TimeUnit.SECONDS));

            transport.close();

            // After close, the server-side socket should detect the disconnect
            Socket client = clientRef.get();
            assertNotNull(client);
            // Reading from the server side should return -1 (EOF) or throw
            try {
                int b = client.getInputStream().read();
                assertEquals(-1, b, "Server should see EOF after client closes");
            } catch (IOException expected) {
                // Also acceptable -- connection reset
            }
            client.close();
        }
    }
}
