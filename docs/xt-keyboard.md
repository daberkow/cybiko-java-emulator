# Cybiko Xtreme Keyboard Layout & Matrix

## Physical Keyboard Layout

```
┌──────────────────────────────────────────────────────────┐
│  [F1]  [F2]  [F3]  [F4]  [F5]  [F6]  [F7]              │
│  Menu  Chat  Phone Cal   Near  Notes Calc               │
│                                                          │
│                  ┌──────────────┐                        │
│                  │              │                        │
│                  │    SCREEN    │                        │
│                  │   160x100    │                        │
│                  │              │                        │
│                  └──────────────┘                        │
│                                                          │
│  [Esc]  [Tab]  [Del]  [Enter]  [Ins]  [Select]          │
│                                                          │
│                  [▲]                                     │
│           [◄]         [►]            [On/Off]            │
│                  [▼]                                     │
│                                                          │
│  Q   W   E   R   T   Y   U   I   O   P                 │
│  A   S   D   F   G   H   J   K   L   ;|                │
│  Z   X   C   V   B   N   M  ,~  .\  !^                 │
│                                                          │
│        [Shift]  [(]  [Space]  [)]  [Fn]                 │
└──────────────────────────────────────────────────────────┘
```

### Key Notation
- Two characters on one key (like `;|`): unshifted = first, Shift = second
- `,~` = comma (unshifted), tilde (Shift)
- `.\` = period (unshifted), backslash (Shift)
- `!^` = exclamation (unshifted), caret (Shift)
- `(` and `)` are dedicated keys flanking Space

### Fn Layer (hold Fn + key)
```
  1   2   3   4   5   6   7   8   9   0      ← top row
  @   &   $   %   *   +   -   _   =   :      ← middle row
  "   #   ☆   {   }   <   >   '   /   ?      ← bottom row (☆ = Cybiko symbol)
```

## Keyboard Matrix (15 columns x 16-bit)

Addresses at 0xE00000-0xEFFFFF, active-LOW column select.
CyOS reads via DMA walking-zero scan.

### Matrix from MAME + Emulator

| Col | Address    | Bit → Key                                              | Status |
|-----|------------|--------------------------------------------------------|--------|
| 0   | 0xE00100   | 0x0001=F7, 0x0100=M, 0x0800=K, 0x1000=I, 0x2000=O, 0x4000=L | OK |
| 1   | 0xE00200   | 0x0001=F6, 0x0002=G, 0x0004=B, 0x0008=N, 0x0010=H, 0x0020=Y, 0x0040=U, 0x0080=J | OK |
| 2   | 0xE00400   | 0x0001=F5, 0x0100=D, 0x0200=C, 0x0800=V, 0x1000=F, 0x2000=R, 0x4000=T | OK |
| 3   | 0xE00800   | 0x0001=F4, 0x0002=Q, 0x0004=A, 0x0008=Z, 0x0010=X, 0x0020=S, 0x0040=W, 0x0080=E | OK |
| 4   | 0xE01000   | 0x0001=F3, 0x0008=Enter, 0x0010=Select, 0x0040=Space  | OK |
| 5   | 0xE02000   | 0x0001=F2, 0x0080=Tab, 0x0100=Del, 0x0200=Ins, 0x0400=Esc | OK |
| 6   | 0xE04000   | 0x0001=F1, 0x0800=Up, 0x1000=Right, 0x2000=Down, 0x4000=Left | OK |
| 7   | 0xE08000   | 0x8000=Fn                                              | OK |
| 8   | 0xE10000   | 0x8000=Shift                                           | OK |
| 9   | 0xE20000   | 0x0001=Help, 0x0002=Period, 0x0008=Semicolon, 0x0010=P | Partial? |
| 10  | 0xE40000   | ?                                                      | Unknown |
| 11  | 0xE80000   | ?                                                      | Unknown |
| 12  | 0xF00000   | ?                                                      | Unknown |
| 13  | 0xF80000   | (MAME: 0x0001=Help, 0x0002=Period, 0x0010=P)           | See note |
| 14  | 0xFC0000   | 0x8000=Power                                           | OK |

**Note:** MAME maps Help/Period/P to column 13, but the emulator has them on column 9
and they work. The MAME XT keyboard matrix appears incomplete — columns 9-12 are
largely undefined, and several physical keys are missing entirely.

### Missing Keys (not mapped in MAME or emulator)

| Physical Key | Shift Variant | Status |
|-------------|---------------|--------|
| `,`         | `~`           | NOT MAPPED |
| `!`         | `^`           | NOT MAPPED |
| `(`         | —             | NOT MAPPED |
| `)`         | —             | NOT MAPPED |

These keys exist on the physical device but their matrix column:bit positions are
unknown. They likely occupy columns 9-12. Finding them requires either:
- Keyboard PCB trace analysis
- CyOS disassembly of the keyboard scan/decode routine
- Empirical testing with different column:bit combinations

## PC Keyboard Mapping

| PC Key        | Cybiko Key | Notes |
|---------------|------------|-------|
| A-Z           | A-Z        | Direct |
| 0-9           | Fn + letter| Simulated combo (unreliable ~80-90%) |
| F1-F7         | F1-F7      | Direct |
| Arrow keys    | Arrows     | Direct |
| Enter         | Enter      | Direct |
| Space         | Space      | Direct |
| Tab           | Tab        | Direct |
| Escape        | Esc        | Direct |
| Delete/Backsp | Del        | Direct |
| Insert        | Ins        | Direct |
| Home          | Select     | Direct |
| End           | Help       | Direct |
| Shift         | Shift      | Direct |
| Ctrl          | Fn         | Direct |
| Period        | .          | Direct |
| Semicolon     | ;          | Direct |
| !             | —          | NOT WORKING (unmapped) |
| ,             | —          | NOT WORKING (unmapped) |
| (             | —          | NOT WORKING (unmapped) |
| )             | —          | NOT WORKING (unmapped) |

## Known Issues

### 1. Number Keys Unreliable (~80-90% success rate)
When pressing a number (e.g., `1`), the emulator simulates Fn+Q by:
1. Pressing Fn immediately on the Swing EDT thread
2. Queuing the letter (Q) to be pressed 4 frames later in render()
3. Holding both for minimum 3 frames, then releasing letter, then Fn

**Failure mode:** The letter (Q) appears instead of the number (1). This happens
because CyOS DMA-scans the keyboard matrix and can catch a window where the letter
is pressed but Fn timing doesn't align with the scan.

**Root cause:** Thread synchronization — Swing EDT modifies the key matrix array
while the emulation thread reads it. No synchronization primitives protect access.

### 2. Missing Punctuation Keys
Several physical keyboard keys have no matrix mapping. MAME's XT INPUT_PORTS
definition is incomplete compared to the actual hardware.

### 3. Shift Combinations
Shift key is mapped but CyOS handles the shifted character generation internally.
The emulator correctly passes Shift state to CyOS via the matrix.
