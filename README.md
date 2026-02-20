# Cybiko Emulator

A standalone Java emulator for the Cybiko handheld computer family, derived from MAME's emulation by Tim Schuerewegen. Supports the Cybiko Classic (V1), V2, and Xtreme with LCD display, keyboard input, sound, and app loading. This project was something I was interested in, and I wanted to test out Claude Code. 99% of this project was created with Claude Code Opus 4.6.

## Quick Start

```bash
./gradlew build

# Cybiko Xtreme (default)
./gradlew run --args="cyrom150.bin cyos_v1508.bin"

# Cybiko Classic V1
./gradlew run --args="--machine v1 cyrom112.bin flash_v1246.bin"
```

You need two ROM files per machine:
- **Xtreme**: `cyrom150.bin` + `cyos_v1508.bin` from MAME's `cybikoxt.zip`
- **Classic V1**: `cyrom112.bin` + `flash_v1246.bin` from MAME's `cybiko.zip`

## Loading Apps

Apps are loaded into a virtual NVRAM file that persists between sessions:

```bash
# Add an app and boot
./gradlew run --args="cyrom150.bin cyos_v1508.bin --nvram cybiko.nvram --app calc.app"

# Next time, just boot - your apps and settings are saved
./gradlew run --args="cyrom150.bin cyos_v1508.bin --nvram cybiko.nvram"

# Add more apps later
./gradlew run --args="cyrom150.bin cyos_v1508.bin --nvram cybiko.nvram --app dice.app --app Calendar.app"

# See what's installed
./gradlew run --args="cyrom150.bin cyos_v1508.bin --nvram cybiko.nvram --list-apps"
```

Without `--nvram`, apps are loaded into a temporary image that is lost on exit.

## Options

| Flag | Description |
|------|-------------|
| `--machine v1\|v2\|xt` | Select machine type (default: `xt`) |
| `--nvram <file>` | Persistent storage file (created if missing) |
| `--app <file>` | Add a .app file before booting (repeatable) |
| `--list-apps` | List installed apps and exit |
| `--mute` | Disable audio |
| `--headless` | Run without GUI |
| `--trace` | Instruction-level tracing (very slow) |

## Keyboard

The Cybiko keyboard is mapped to your PC keyboard. Letters map directly, navigation with arrow keys, Enter/Space/Tab/Esc as labeled.

- **Xtreme**: Numbers use Fn+letter combos (Fn+Q=1, Fn+W=2, ... Fn+P=0) where Fn is mapped to the right Alt key.
- **Classic V1**: Has dedicated number keys mapped directly to 0-9.

## Links

- [Cybiko Archive](https://archive.org/details/cybiko) - ROMs, apps, and documentation
- [Cybiko ROMs (MAME)](https://ia803204.us.archive.org/view_archive.php?archive=/29/items/mame-0.221-roms-merged/cybikov1.zip)
- [MAME Code](https://github.com/mamedev/mame/blob/master/src/mame/cybiko/cybiko.cpp)
- [Tim's Web Emulator](https://www.schuerewegen.tk/cybiko/emu/) - WASM version by the original MAME author