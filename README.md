# Cybiko Xtreme Emulator

A standalone Java emulator for the Cybiko Xtreme handheld computer, derived from MAME's emulation by Tim Schuerewegen. Runs CyOS with LCD display, keyboard input, sound, and app loading.

## Quick Start

```bash
./gradlew build
./gradlew run --args="src/main/resources/cybikoxt/cyrom150.bin src/main/resources/cybikoxt/cyos_v1508.bin"
```

You need two ROM files from MAME's `cybikoxt.zip`: `cyrom150.bin` (boot ROM) and `cyos_v1508.bin` (CyOS flash).

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
| `--nvram <file>` | Persistent storage file (created if missing) |
| `--app <file>` | Add a .app file before booting (repeatable) |
| `--list-apps` | List installed apps and exit |
| `--mute` | Disable audio |
| `--headless` | Run without GUI |
| `--trace` | Instruction-level tracing (very slow) |

## Keyboard

The Cybiko keyboard is mapped to your PC keyboard. Letters map directly, navigation with arrow keys, Enter/Space/Tab/Esc as labeled. Numbers use Fn+letter combos (Fn+Q=1, Fn+W=2, ... Fn+P=0) where Fn is mapped to the right Alt key.

## Links

- [Cybiko Archive](https://archive.org/details/cybiko) - ROMs, apps, and documentation
- [Cybiko ROMs (MAME)](https://ia803204.us.archive.org/view_archive.php?archive=/29/items/mame-0.221-roms-merged/cybikov1.zip)
- [Tim's Web Emulator](https://www.schuerewegen.tk/cybiko/emu/) - WASM version by the original MAME author