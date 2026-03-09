# Cybiko Emulator & NVRAM Manager

A standalone Java emulator for the [Cybiko](https://en.wikipedia.org/wiki/Cybiko) handheld computer family, plus a desktop NVRAM manager for working with Cybiko flash images. Derived from MAME's emulation by Tim Schuerewegen.

## Quick Start

**Requirements:** Java 21+

### 1. Get the ROMs

You need two ROM files per machine variant. These come from MAME ROM sets:

| Machine | ROM Files | MAME ZIP |
|---------|-----------|----------|
| Cybiko Xtreme (default) | `cyrom150.bin` + `cyos_v1508.bin` | `cybikoxt.zip` |
| Cybiko Classic V1 | `cyrom112.bin` + `flash_v1246.bin` | `cybiko.zip` |

ROMs can be found at the [Cybiko Archive on archive.org](https://archive.org/details/cybiko) or in MAME ROM collections.

### 2. Download and Run

Grab the latest zip from the [Releases page](https://github.com/daberkow/cybiko-java-emulator/releases), unzip it, and place your ROM files in the same folder.

```bash
# Cybiko Xtreme (default)
bin/cybiko-emulator cyrom150.bin cyos_v1508.bin

# Cybiko Classic V1
bin/cybiko-emulator --machine v1 cyrom112.bin flash_v1246.bin
```

### 3. Load Apps

Download `.app` files from the [Cybiko Archive](https://archive.org/details/cybiko) and load them with `--app`:

```bash
# Load an app and save to persistent NVRAM
bin/cybiko-emulator cyrom150.bin cyos_v1508.bin --nvram cybiko.nvram --app calc.app

# Next time, just boot — your apps and settings are saved
bin/cybiko-emulator cyrom150.bin cyos_v1508.bin --nvram cybiko.nvram
```

### 4. Keyboard

Letters map directly to your keyboard. Arrow keys for navigation, Enter/Space/Tab/Esc as labeled.

- **Xtreme**: Numbers via right Alt + letter (Alt+Q=1, Alt+W=2, ... Alt+P=0)
- **Classic V1**: Dedicated number keys 0-9

---

## About

This project was something I was interested in, and I wanted to test out Claude Code. After getting the base emulator working, I went about adding new features like a manager to manage the games, and working on wireless communications.

This is the main repo for me working on this project. A few others have been made for a [C Version](https://github.com/daberkow/cybiko-c-emulator) and a port to the [LilyGo T-Deck](https://github.com/daberkow/cybiko-lilygo). Java is my best known language, thus I started here to be able to debug.

Please report issues on the [Issues tab](https://github.com/daberkow/cybiko-java-emulator/issues).

## Current Functionality

| Feature | Classic V1 | V2 | Xtreme |
|---------|:----------:|:--:|:------:|
| Core (boot to interactive UI) | Yes | Yes (CyOS v1.3.57) | Yes |
| Sound (1-bit PWM speaker) | Yes | Yes | Yes |
| MP3 Player | No | No | No |
| Radio (LAN/SDR networking) | Yes | Partial | Yes |
| RTC (real-time clock) | Partial | Partial | Partial |
| App Loading (CFS + NVRAM) | Yes | Yes | Yes |
| Keyboard | Yes | Yes | Yes |
| DMA | N/A | N/A | Yes |

**Notes:**
- **V2** fully boots with CyOS v1.3.57. CyOS v1.3.58 stalls at the animated logo (RF hardware init never completes, same as MAME).
- **MP3** playback is not implemented on any variant. Sound is 1-bit PWM only.
- **RTC** communicates over I2C on all variants but does not function as an autonomous clock and gives the wrong date.

### Radio Networking

Radio supports UDP multicast (`--radio lan`) for LAN play and a TCP bridge (`--radio sdr`) for GNU Radio integration.

| Capability | V1 | V2 | XT |
|------------|:--:|:--:|:--:|
| Peer discovery | Yes (cross-version) | Yes (cross-version) | Yes (cross-version) |
| Chat (same version) | Yes | Not tested | Yes |
| Chat (cross-version) | Yes | No | Yes |
| Beacon TX | Yes | Yes | Yes |
| Frame data TX | Yes | No (protocol limitation) | Yes (DTC) |
| Frame data RX | Yes (50-byte only) | Yes (50-byte only) | Yes (50/200-byte) |

**Details:**
- **V1↔V1** and **XT↔XT** chat is fully working — nearby peer discovery and messaging confirmed between emulators of the same type.
- **V1↔XT** cross-version chat works — peer discovery and messaging confirmed between Classic V1 and Xtreme emulators.
- **V2↔XT** peer discovery works — both see each other in the Chat nearby list. Chat messaging does not work because V2 (CyOS v1.3.57) and XT (CyOS v1.5.08) use incompatible radio protocols. V2 only sends 2-byte radio commands and cannot transmit frame data. V2 also only supports 50-byte receive frames, while XT sends 192-byte chat frames. **V2 radio requires CyOS v1.3.57 specifically** — the emulator uses hardcoded RAM offsets for CyID patching and beacon data that differ between ROM versions.

## Project Structure

This is a multi-module Gradle project:

```
cybiko-java/
├── emulator/          # H8S CPU emulator + CyOS boot (Swing UI)
├── manager/           # NVRAM Manager desktop app (JavaFX)
├── tools/             # Standalone utilities (H8S disassembler)
├── build.gradle       # Root build config (Java 21 toolchain)
└── settings.gradle    # Subproject includes
```

## Emulator Options

### All Options

| Flag | Description |
|------|-------------|
| `--machine v1\|v2\|xt` | Select machine type (default: `xt`) |
| `--nvram <file>` | Persistent storage file (created if missing) |
| `--app <file>` | Add a .app file before booting (repeatable) |
| `--list-apps` | List installed apps and exit |
| `--mute` | Disable audio |
| `--headless` | Run without GUI |
| `--trace` | Instruction-level tracing (very slow) |
| `--radio lan\|sdr` | Enable radio (lan = UDP multicast, sdr = TCP bridge) |
| `--radio-id <n>` | Set radio device ID (default: random) |
| `--sdr-host <ip>` | SDR bridge host (default: localhost) |
| `--sdr-port <port>` | SDR bridge port (default: 19201) |

## NVRAM Manager

A JavaFX desktop application for managing Cybiko NVRAM/flash images without running the emulator. Browse, add, remove, and inspect files on any Cybiko flash image.

### Quick Start

```bash
./gradlew :manager:build
./gradlew :manager:run
```

### Features

- **Open/save/create** NVRAM images (.nvram, .bin, .nv) for all hardware variants (Classic V1/V2, Xtreme)
- **Library folders** -- configure directories of .app files, browse and add to NVRAM with one click
- **Drag-and-drop** -- drag library items onto NVRAM entries in the sidebar to add files directly (multi-select supported)
- **Smart lists** -- "Recently Added" and "Not in Any NVRAM" virtual folders that update automatically
- **Search/filter** -- real-time search across file listings
- **Hex viewer** -- browse raw file data with multi-select and copy to clipboard
- **Integrity validation** -- CyOS-level flash checks: checksums, boot blocks, block flags, file structure, data sizes
- **Flash repair** -- automatically recover files from corrupted images
- **NVRAM properties** -- flash geometry, block stats, checksum status
- **CSV export** -- export file listings
- **Dark theme** -- GitHub-style dark mode UI, Wayland-compatible in-window dialogs

### Supported Flash Types

| Type | Hardware | Page Size | Total Size |
|------|----------|-----------|------------|
| AT45DB041 | Classic V1/V2 (4Mbit) | 264 bytes | 540 KB |
| AT45DB081 | 8Mbit variant | 264 bytes | 1.05 MB |
| AT45DB161 | 16Mbit variant | 528 bytes | 2.11 MB |
| SST 39VF400A | Xtreme | 258 bytes | 517 KB |

## Building from Source

If you prefer to build from source instead of using the [release zips](https://github.com/daberkow/cybiko-java-emulator/releases):

```bash
git clone https://github.com/daberkow/cybiko-java-emulator.git
cd cybiko-java-emulator

# Build everything (requires Java 21, auto-downloaded via Gradle toolchains)
./gradlew build

# Run the emulator from source
./gradlew :emulator:run --args="cyrom150.bin cyos_v1508.bin"

# Run all tests (~186 manager tests + emulator tests)
./gradlew test
```

## Documentation

- [Bugs Fixed](docs/bugs-fixed.md) - Detailed log of emulation bugs found and fixed
- [Wireless Protocol](docs/wireless-protocol.md) - Cybiko radio protocol analysis
- [RF2915 Research](docs/rf2915-research.md) - RF2915 transceiver and frame format research
- [V2 Investigation](docs/v2-investigation.md) - V2 CyOS boot stall investigation

## Links

- [Cybiko Archive](https://archive.org/details/cybiko) - ROMs, apps, and documentation
- [Cybiko ROMs (MAME)](https://ia803204.us.archive.org/view_archive.php?archive=/29/items/mame-0.221-roms-merged/cybikov1.zip)
- [MAME Code](https://github.com/mamedev/mame/blob/master/src/mame/cybiko/cybiko.cpp)
- [Tim's Web Emulator](https://www.schuerewegen.tk/cybiko/emu/) - WASM version by the original MAME author

