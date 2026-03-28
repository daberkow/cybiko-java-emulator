# Cybiko .app File Format

Cybiko applications are distributed as `.app` archive files created by `filer.exe` from the Cybiko SDK (by Vadim Sytnikov, Cybiko Inc., 1999-2001). Each archive contains embedded resource files like the executable (`main.e`), icon (`root.ico`), splash screen (`root.spl`), and other assets.

## Archive Structure

```
Offset 0-1:  Magic bytes "Cy" (0x43 0x79)
Offset 2-3:  File count N (16-bit big-endian)
Offset 4-5:  Header payload size (16-bit big-endian) — total size of entry table + name strings
Offset 6:    File entry table (N entries, 10 bytes each)
After table: Null-terminated filename strings
After names: File data blobs (contiguous)
```

### File Entry (10 bytes each)

| Offset | Size | Description |
|--------|------|-------------|
| 0-1 | 2 | Name string offset (absolute, big-endian) |
| 2-5 | 4 | Data offset (absolute, big-endian) |
| 6-9 | 4 | Data size (big-endian) |

### Example: calc.app

```
00: 43 79        "Cy" magic
02: 00 06        6 files
04: 00 7A        122 bytes of header payload (entries + names)
06: [entry table starts — 6 entries x 10 bytes = 60 bytes]
42: [filename strings start]
80: [file data starts]
```

Typical files inside an app: `root.inf`, `root.ico`, `root.spl`, `main.e`, `.help`, and various `.pic` resources.

## Compression

Each file data blob starts with a 1-byte type indicator:

| Byte | Meaning |
|------|---------|
| 0x00 | Uncompressed — raw data follows from byte 1 |
| 0x02 | Compressed — bytes 1-4 = uncompressed size (32-bit BE), bytes 5+ = compressed bitstream |

### Compression Algorithm

The compression is a custom LZ77 variant with variable-length prefix codes
(reverse-engineered from filer.exe x86 disassembly at VMA 0x402ba8):

- **Bitstream-based** (not byte-aligned), LSB-first bit ordering within each byte
- **Multi-bit reads**: MSB-first accumulation (first bit read = highest bit of result)
- **Flag bit**: 0 = literal byte (readBits(8)), 1 = back-reference match (decode length + offset)
- **Match length**: Multi-level prefix code tree supporting lengths 2-256
- **Match offset**: 3-bit prefix + variable suffix, supporting offsets 0-8191+
- **Back-reference**: `source = position - offset - length` (offset counts from end of match)

#### Length Decode Tree

```
readBits(2):
  00 → 2
  01 → 3
  10 → readBit() + 4                                    (4-5)
  11 → readBits(2):
    00 → 6
    01 → readBit() + 8                                   (8-9)
    10 → readBit(): 0→7, 1→readBit()+10                  (7, 10-11)
    11 → readBits(3) jump table:
      0→12, 1→13, 2→readBits(2)+20, 3→readBit()+16,
      4→readBits(3)+24, 5→14,
      6→readBit(): 0→readBits(5)+32, 1→readBit()+18,
      >6→readBit(): 0→15,
        1→readBit(): 0→readBits(6)+64,
          1→readBit(): 0→readBits(7)+128, 1→256
```

#### Offset Decode Tree

```
readBits(3):
  0 → readBits(10) + 1024                               (1024-2047)
  1 → readBits(9) + 512                                  (512-1023)
  2 → readBits(8) + 256                                  (256-511)
  3 → readBits(6) + 64                                   (64-127)
  4 → readBits(7) + 128                                  (128-255)
  5 → readBit(): 0→readBits(11)+2048, 1→readBits(12)+4096
  6 → readBit(): 0→readBits(5)+32, 1→readBits(4)+16
  7 → readBits(2):
    0 → readBit()                                        (0-1)
    1 → readBits(2) + 6                                  (6-9)
    2 → readBit(): 0→readBit()+4, 1→2                    (2, 4-5)
    3 → readBits(2): 0→readBit()+12, 1→readBit()+14, 2→3, 3→readBit()+10
```

## Image Format (.ico / .pic)

Icon and picture files share the same format. The Cybiko LCD is 160x100 pixels, 2-bit grayscale (4 shades).

### Header (8 bytes)

| Offset | Size | Description |
|--------|------|-------------|
| 0 | 1 | File type (0x02 = picture) |
| 1 | 1 | Number of images in file |
| 2 | 1 | Width in pixels |
| 3 | 1 | Height in pixels |
| 4 | 1 | Left coordinate (display hint) |
| 5 | 1 | Top coordinate (display hint) |
| 6 | 1 | Display width |
| 7 | 1 | Display height |

### Pixel Data (byte 8+)

- 2 bits per pixel, 4 pixels per byte
- MSB = leftmost pixel within each byte
- Pixel values: 0 = white, 1 = light gray, 2 = dark gray, 3 = black
- Row size: `ceil(width / 4)` bytes
- Total pixel data: `ceil(width / 4) * height` bytes

### Standard Icon Dimensions

- `root.ico`: typically 48x47 pixels (572 bytes total: 8 header + 564 pixel data)
- `root.spl`: typically 160x100 pixels (full screen splash)

## root.inf (App Metadata)

The `root.inf` file contains application metadata as key-value pairs. Format varies by SDK version but typically includes description text, author/company, and version info as plain text strings within the binary.

## SDK Tools Reference

From the Cybiko SDK (`filer.exe` v2.0.8):
- `filer.exe` — creates .app archives from resource files
- `2pic.exe` — converts BMP images to .pic/.ico format
- `bmp2spr.exe` — converts BMP to sprite format
- `PicView.exe` — previews .pic/.ico images
