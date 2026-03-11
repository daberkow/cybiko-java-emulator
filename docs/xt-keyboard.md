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
| `!`         | `^`           | FOUND: col 9, bit 0x0004 |
| `,`         | `~`           | NOT MAPPED |
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
| 0-9           | Fn + letter| Queue-based combo (reliable) |
| Shift+1       | !          | Lazy Shift: ! pressed without Shift in matrix |
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
| Shift         | Shift      | Lazy: only enters matrix with letter keys |
| Ctrl          | Fn         | Direct |
| Period        | .          | Direct |
| Semicolon     | ;          | Direct |
| - (minus)     | Fn+J       | Queue-based combo |
| = (equals)    | Fn+L       | Queue-based combo |
| / (slash)     | Fn+.       | Queue-based combo |
| ' (quote)     | Fn+M       | Queue-based combo |
| ,             | —          | NOT MAPPED (unknown matrix position) |
| (             | —          | NOT MAPPED (unknown matrix position) |
| )             | —          | NOT MAPPED (unknown matrix position) |

## Number Key Implementation

Number keys (0-9) are Fn+letter combos. The emulator uses a queue-based state
machine that runs entirely on the emulation thread to avoid DMA scan races:

1. **EDT** enqueues digit on key press (release ignored — auto-completes)
2. **render()** processes one digit at a time:
   - First digit: press Fn → 8-frame delay (Fn must stabilize across multiple CyOS scans)
   - Press letter → 6-frame hold → release letter
   - Consecutive digits: Fn stays held, 3-frame delay between letters
   - After queue empty: 6-frame delay → release Fn

CyOS requires Fn to be visible in several consecutive keyboard DMA scans before it
activates "Fn mode." A shorter Fn delay causes the letter to register as unmodified.

## Lazy Shift

PC Shift key does NOT immediately enter the Cybiko keyboard matrix. Instead, a
`pcShiftHeld` flag is set. Shift is only added to the matrix when a key that needs
CyOS-level shifting (letters, semicolon, etc.) is pressed alongside it.

This prevents the `!` key problem: on PC, Shift+1 produces `!`. On the Cybiko, `!`
is a dedicated unshifted key (col 9, bit 0x0004). If Shift entered the matrix first
(as it does on a real PC keyboard — Shift is pressed before 1), CyOS would see
Shift+! = `^` (the shifted variant). With lazy Shift, pressing Shift+1 on PC sends
only `!` to the Cybiko matrix — Shift never appears.

## Known Issues

### 1. Missing Punctuation Keys
Several physical keyboard keys have no matrix mapping. MAME's XT INPUT_PORTS
definition is incomplete compared to the actual hardware. Keys `,`, `(`, `)`
are physical keys on the Cybiko XT but their column:bit positions are unknown.
`!` was found at col 9, bit 0x0004 via keyboard probe mode.

### 2. Missing Fn+Letter Symbols
The Fn layer has symbols on the middle and bottom rows (e.g., Fn+A=@, Fn+Z=")
that are not yet mapped in the emulator.

### 3. Shift Combinations
Shift key is mapped but CyOS handles the shifted character generation internally.
The emulator correctly passes Shift state to CyOS via the matrix.
