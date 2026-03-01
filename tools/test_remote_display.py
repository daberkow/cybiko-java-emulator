#!/usr/bin/env python3
"""Test client for Cybiko remote display server.
Connects to the emulator, receives frames, and prints frame stats.
Press arrow keys to send key events.
"""
import socket
import sys
import time

HOST = sys.argv[1] if len(sys.argv) > 1 else "localhost"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 6502

MSG_FRAME = 0x01
MSG_KEY_DOWN = 0x10
MSG_KEY_UP = 0x11
VRAM_SIZE = 4000

def recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("Server disconnected")
        buf += chunk
    return buf

def main():
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.connect((HOST, PORT))
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    print(f"Connected to {HOST}:{PORT}")

    frame_count = 0
    start_time = time.time()

    try:
        while True:
            msg_type = sock.recv(1)
            if not msg_type:
                break
            if msg_type[0] == MSG_FRAME:
                vram = recv_exact(sock, VRAM_SIZE)
                frame_count += 1
                if frame_count % 60 == 0:
                    elapsed = time.time() - start_time
                    fps = frame_count / elapsed
                    # Count non-zero bytes as rough "content" metric
                    nonzero = sum(1 for b in vram if b != 0)
                    print(f"Frame {frame_count}: {fps:.1f} fps, {nonzero}/4000 non-zero bytes")
    except KeyboardInterrupt:
        print(f"\n{frame_count} frames received")
    finally:
        sock.close()

if __name__ == "__main__":
    main()
