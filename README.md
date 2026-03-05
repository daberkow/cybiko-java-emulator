# Cybiko Emulator & NVRAM Manager

A standalone Java emulator for the Cybiko handheld computer family, plus a desktop NVRAM manager for working with Cybiko flash images. Derived from MAME's emulation by Tim Schuerewegen. Supports the Cybiko Classic (V1), V2, and Xtreme with LCD display, keyboard input, sound, and app loading. This project was something I was interested in, and I wanted to test out Claude Code.

After getting the base emulator working, I went about adding new features like a manager to manage the games, and working on wireless communications.

## Current Functionality

| Feature | Classic V1 | V2 | Xtreme |
|---------|:----------:|:--:|:------:|
| Core (boot to interactive UI) | Yes | Partial (stalls at logo) | Yes |
| Sound (1-bit PWM speaker) | Yes | Yes | Yes |
| MP3 Player | No | No | No |
| Radio (LAN/SDR networking) | Yes | No (RF init blocked) | Yes (peer discovery + chat) |
| RTC (real-time clock) | Partial | Partial | Partial |
| App Loading (CFS + NVRAM) | Yes | Yes | Yes |
| Keyboard | Yes | Yes | Yes |
| DMA | N/A | N/A | Yes |

**Notes:**
- **V2** boots through SPI flash loading and reaches the animated Cybiko logo but never progresses to the desktop. RF hardware init never completes (same limitation as MAME).
- **MP3** playback is not implemented on any variant. Sound is 1-bit PWM only.
- **RTC** communicates over I2C on all variants but does not function as an autonomous clock.but is giving the wrong date.
- **Radio** supports UDP multicast (`--radio lan`) and a TCP bridge to GNU Radio (`--radio sdr`). Chat messaging confirmed working between two Xtreme emulators.

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

## Emulator

### Quick Start

```bash
./gradlew :emulator:build

# Cybiko Xtreme (default)
./gradlew :emulator:run --args="cyrom150.bin cyos_v1508.bin"

# Cybiko Classic V1
./gradlew :emulator:run --args="--machine v1 cyrom112.bin flash_v1246.bin"
```

You need two ROM files per machine:
- **Xtreme**: `cyrom150.bin` + `cyos_v1508.bin` from MAME's `cybikoxt.zip`
- **Classic V1**: `cyrom112.bin` + `flash_v1246.bin` from MAME's `cybiko.zip`

### Loading Apps

Apps are loaded into a virtual NVRAM file that persists between sessions:

```bash
# Add an app and boot
./gradlew :emulator:run --args="cyrom150.bin cyos_v1508.bin --nvram cybiko.nvram --app calc.app"

# Next time, just boot - your apps and settings are saved
./gradlew :emulator:run --args="cyrom150.bin cyos_v1508.bin --nvram cybiko.nvram"

# Add more apps later
./gradlew :emulator:run --args="cyrom150.bin cyos_v1508.bin --nvram cybiko.nvram --app dice.app --app Calendar.app"

# See what's installed
./gradlew :emulator:run --args="cyrom150.bin cyos_v1508.bin --nvram cybiko.nvram --list-apps"
```

Without `--nvram`, apps are loaded into a temporary image that is lost on exit.

### Options

| Flag | Description |
|------|-------------|
| `--machine v1\|v2\|xt` | Select machine type (default: `xt`) |
| `--nvram <file>` | Persistent storage file (created if missing) |
| `--app <file>` | Add a .app file before booting (repeatable) |
| `--list-apps` | List installed apps and exit |
| `--mute` | Disable audio |
| `--headless` | Run without GUI |
| `--trace` | Instruction-level tracing (very slow) |

### Keyboard

The Cybiko keyboard is mapped to your PC keyboard. Letters map directly, navigation with arrow keys, Enter/Space/Tab/Esc as labeled.

- **Xtreme**: Numbers use Fn+letter combos (Fn+Q=1, Fn+W=2, ... Fn+P=0) where Fn is mapped to the right Alt key.
- **Classic V1**: Has dedicated number keys mapped directly to 0-9.

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

## Building

Requires Java 21 (auto-downloaded via Gradle toolchains).

```bash
# Build everything
./gradlew build

# Run all tests (~186 manager tests + emulator tests)
./gradlew test

# Build/test individual subprojects
./gradlew :emulator:test
./gradlew :manager:test
```

## Links

- [Cybiko Archive](https://archive.org/details/cybiko) - ROMs, apps, and documentation
- [Cybiko ROMs (MAME)](https://ia803204.us.archive.org/view_archive.php?archive=/29/items/mame-0.221-roms-merged/cybikov1.zip)
- [MAME Code](https://github.com/mamedev/mame/blob/master/src/mame/cybiko/cybiko.cpp)
- [Tim's Web Emulator](https://www.schuerewegen.tk/cybiko/emu/) - WASM version by the original MAME author
