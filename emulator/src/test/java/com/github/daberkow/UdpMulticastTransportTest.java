package com.github.daberkow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link UdpMulticastTransport}.
 *
 * <p>Uses a non-standard port (19299) to avoid conflicts with running emulators.
 * Tests gracefully skip if the environment does not support multicast (e.g. some
 * CI containers).
 */
@Timeout(10)
class UdpMulticastTransportTest {
    private static final String TEST_GROUP = "239.0.0.42";
    private static final int TEST_PORT = 19299;

    /** Two transports with different device IDs can exchange packets. */
    @Test void twoTransportsCanCommunicate() throws Exception {
        UdpMulticastTransport a = null;
        UdpMulticastTransport b = null;
        try {
            a = new UdpMulticastTransport(0x0001, TEST_GROUP, TEST_PORT);
            b = new UdpMulticastTransport(0x0002, TEST_GROUP, TEST_PORT);

            try {
                a.start();
                b.start();
            } catch (IOException e) {
                System.err.println("Multicast not available, skipping: " + e.getMessage());
                return; // Graceful skip
            }

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<byte[]> received = new AtomicReference<>();
            AtomicInteger receivedChannel = new AtomicInteger(-1);
            AtomicInteger receivedSender = new AtomicInteger(-1);

            b.setChannel(7);
            b.setPacketListener((data, channel, senderId) -> {
                received.set(data);
                receivedChannel.set(channel);
                receivedSender.set(senderId);
                latch.countDown();
            });

            byte[] payload = {0x10, 0x20, 0x30};
            a.sendPacket(payload, 7);

            assertTrue(latch.await(2, TimeUnit.SECONDS),
                    "Timed out waiting for packet from transport A to transport B");
            assertArrayEquals(payload, received.get());
            assertEquals(7, receivedChannel.get());
            assertEquals(0x0001, receivedSender.get());
        } finally {
            if (a != null) a.close();
            if (b != null) b.close();
        }
    }

    /** A transport must not deliver packets that it sent itself. */
    @Test void ownPacketsAreFiltered() throws Exception {
        UdpMulticastTransport a = null;
        try {
            a = new UdpMulticastTransport(0x0042, TEST_GROUP, TEST_PORT + 1);

            try {
                a.start();
            } catch (IOException e) {
                System.err.println("Multicast not available, skipping: " + e.getMessage());
                return;
            }

            CountDownLatch latch = new CountDownLatch(1);
            a.setChannel(0);
            a.setPacketListener((data, channel, senderId) -> latch.countDown());

            a.sendPacket(new byte[]{0x01}, 0);

            // Should NOT receive our own packet -- wait briefly and assert timeout
            assertFalse(latch.await(500, TimeUnit.MILLISECONDS),
                    "Transport should not deliver its own packets");
        } finally {
            if (a != null) a.close();
        }
    }

    /** Packets on a different channel than the listener's channel are discarded. */
    @Test void wrongChannelIsFiltered() throws Exception {
        UdpMulticastTransport a = null;
        UdpMulticastTransport b = null;
        try {
            a = new UdpMulticastTransport(0x0010, TEST_GROUP, TEST_PORT + 2);
            b = new UdpMulticastTransport(0x0020, TEST_GROUP, TEST_PORT + 2);

            try {
                a.start();
                b.start();
            } catch (IOException e) {
                System.err.println("Multicast not available, skipping: " + e.getMessage());
                return;
            }

            CountDownLatch latch = new CountDownLatch(1);
            b.setChannel(5);
            b.setPacketListener((data, channel, senderId) -> latch.countDown());

            // Send on channel 3 -- B is listening on channel 5
            a.sendPacket(new byte[]{(byte) 0xAA}, 3);

            assertFalse(latch.await(500, TimeUnit.MILLISECONDS),
                    "Transport should not deliver packets from a different channel");
        } finally {
            if (a != null) a.close();
            if (b != null) b.close();
        }
    }

    /** Verify the on-the-wire packet format: [4-byte ID][1-byte channel][payload]. */
    @Test void wireFormatIsCorrect() throws Exception {
        // We'll capture the raw datagram with a plain MulticastSocket
        MulticastSocket receiver = null;
        UdpMulticastTransport sender = null;
        try {
            int wirePort = TEST_PORT + 3;
            InetAddress group = InetAddress.getByName(TEST_GROUP);

            try {
                receiver = new MulticastSocket(wirePort);
                receiver.setSoTimeout(2000);
                receiver.joinGroup(new InetSocketAddress(group, wirePort), null);
            } catch (IOException e) {
                System.err.println("Multicast not available, skipping: " + e.getMessage());
                return;
            }

            sender = new UdpMulticastTransport(0xDEADBEEF, TEST_GROUP, wirePort);
            sender.start();

            byte[] payload = {0x01, 0x02, 0x03, 0x04, 0x05};
            sender.sendPacket(payload, 12);

            byte[] buf = new byte[1500];
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            receiver.receive(pkt);

            // Parse the wire packet
            ByteBuffer bb = ByteBuffer.wrap(pkt.getData(), pkt.getOffset(), pkt.getLength());
            assertEquals(UdpMulticastTransport.HEADER_SIZE + payload.length, pkt.getLength(),
                    "Wire packet length should be header + payload");
            assertEquals(0xDEADBEEF, bb.getInt(), "First 4 bytes should be device ID");
            assertEquals(12, bb.get() & 0xFF, "5th byte should be channel");

            byte[] wirePayload = new byte[payload.length];
            bb.get(wirePayload);
            assertArrayEquals(payload, wirePayload, "Remaining bytes should be the payload");
        } finally {
            if (sender != null) sender.close();
            if (receiver != null) {
                try {
                    receiver.leaveGroup(
                            new InetSocketAddress(InetAddress.getByName(TEST_GROUP), TEST_PORT + 3),
                            null);
                } catch (IOException ignored) {}
                receiver.close();
            }
        }
    }

    /** Verify default constants. */
    @Test void defaultConstants() {
        assertEquals("239.0.0.42", UdpMulticastTransport.DEFAULT_GROUP);
        assertEquals(19200, UdpMulticastTransport.DEFAULT_PORT);
        assertEquals(5, UdpMulticastTransport.HEADER_SIZE);
    }

    /** getDeviceId returns the ID passed at construction time. */
    @Test void deviceIdIsRetained() throws Exception {
        UdpMulticastTransport t = new UdpMulticastTransport(0x12345678, TEST_GROUP, TEST_PORT + 4);
        assertEquals(0x12345678, t.getDeviceId());
    }
}
