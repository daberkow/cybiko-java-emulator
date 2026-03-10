# Launch Emulator from NVRAM Manager — Design

## Goal

Add the ability to launch the Cybiko emulator directly from the NVRAM Manager,
with configurable settings and automatic ROM/JAR discovery.

## Architecture

The manager spawns the emulator as a separate JVM process via `ProcessBuilder`.
Configuration (ROM paths, emulator JAR path, and emulator settings) is persisted
in `~/.cybiko-manager/roms.properties`. Machine type is inferred from the NVRAM's
`FlashGeometry`.

## ROM Resolution Chain

When launching, resolve boot ROM and flash ROM paths in order:
1. Check `roms.properties` for saved paths matching the machine type
2. Search current working directory for known ROM filenames
3. Search `./roms/` subdirectory
4. If not found, show a file chooser dialog asking the user to locate them
5. Once found, save paths to `roms.properties` for next time

Known ROM filenames per machine type:
- **XT**: `cyrom150.bin` (boot) + `cyos_v1508.bin` (flash)
- **V1**: `cyrom112.bin` (boot) + `flash_v1246.bin` (flash)
- **V2**: similar pattern to V1

## Emulator JAR Resolution

Search order:
1. Same directory as manager JAR
2. `../emulator/build/libs/` (dev environment)
3. Path saved in `roms.properties` (if previously located)
4. Ask user via file chooser, with message: "Emulator JAR not found. Download
   from https://github.com/daberkow/cybiko-java-emulator/releases or browse
   to locate it." Download link is clickable in the dialog.
5. Save located path to `roms.properties`

## Emulator Configuration Dialog

Small settings dialog accessible from NVRAM menu. Settings map to CLI args:

| Setting | Type | CLI arg | Default |
|---------|------|---------|---------|
| Headless | checkbox | `--headless` | off |
| Mute audio | checkbox | `--mute` | off |
| Instruction trace | checkbox | `--trace` | off |
| Radio enabled | dropdown: off/lan/sdr | `--radio` | off |
| Radio ID | text field (number) | `--radio-id` | empty (random) |
| SDR host | text field | `--sdr-host` | localhost |
| SDR port | text field | `--sdr-port` | 19201 |
| Logging categories | text field | `--logging` | empty |

Radio-specific fields only enabled when radio is not "off". SDR fields only
enabled when radio is "sdr". Settings are global (not per-NVRAM). Stored in
`roms.properties`.

## UI

- **NVRAM menu**: "Launch Emulator" item (disabled when no NVRAM selected or
  unsaved). "Emulator Settings..." item.
- **Detail pane**: Launch button when viewing an NVRAM.

## Launch Flow

1. User clicks "Launch Emulator"
2. If NVRAM has unsaved changes, prompt to save
3. Resolve ROMs + emulator JAR (search chain above)
4. Build command line from saved settings
5. Spawn process via ProcessBuilder (fire and forget)

## New Classes

- `EmulatorConfig` (io package) — settings record + load/save to properties
- `EmulatorLauncher` (io package) — ROM/JAR resolution, command building, process spawn
- `EmulatorSettingsDialog` (ui package) — in-window overlay dialog for settings
