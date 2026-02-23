**CYBIKO NVRAM MANAGER**

Design & Implementation Document

For Claude Code Implementation

Platform: **Java 21 / JavaFX / Gradle**

License: Open Source (reference: MAME imgtool, BSD-3-Clause)

Date: February 2026

**1. Project Overview**

The Cybiko NVRAM Manager is a desktop GUI utility for managing Cybiko
flash memory images. It allows users to browse, add, remove, and inspect
applications (games, books, utilities) stored in Cybiko CFS (Cybiko
Flash Storage) NVRAM image files. The interface follows an iTunes-style
three-panel layout: a source sidebar, a content list, and a detail
inspector.

**1.1 Goals**

- Open and parse Cybiko CFS NVRAM image files (AT45DB041, AT45DB081,
  AT45DB161)

- Display contents with file metadata (name, size, type, timestamp)

- Add applications from a local library (zip archive / folder of Cybiko
  apps)

- Remove applications from NVRAM images

- Validate CFS integrity (CRC32 + secondary XOR checksum)

- Hex viewer for raw NVRAM inspection

- Save modified NVRAM images back to disk

- **Stretch Goal:** Serial connection to real Cybiko devices via the
  original protocol

**1.2 Technology Stack**

- **Java 21** (LTS) with preview features disabled for stability

- **JavaFX** for the GUI (via org.openjfx Gradle plugin)

- **Gradle** build system (Kotlin DSL preferred)

- **jSerialComm** for serial port communication (stretch goal)

- No external database; library is filesystem-based

**2. CFS Binary Format Specification**

This section documents the exact byte-level layout of Cybiko Flash
Storage images, derived from MAME imgtool (author: Tim Schuerewegen,
2007, BSD-3-Clause). All multi-byte integers are **big-endian** unless
noted.

**2.1 Flash Image Geometry**

The image file size determines the flash chip type:

  -------------- ---------------- ---------------- ----------- ------------
  **File Size    **File Size      **Flash Chip**   **Pages**   **Page
  (hex)**        (dec)**                                       Size**

  0x084000       540,672 bytes    AT45DB041        2,048       264 bytes

  0x108000       1,081,344 bytes  AT45DB081        4,096       264 bytes

  0x210000       2,162,688 bytes  AT45DB161        4,096       528 bytes
  -------------- ---------------- ---------------- ----------- ------------

Pages 0 through 4 are boot blocks (5 pages). File storage begins at page
5.

**2.2 Page Layout**

Every page on disk has the following structure:

  ------------ ---------- ---------------- -------------------------------------
  **Offset**   **Size**   **Field**        **Description**

  0            4          CRC32            Checksum of data payload (bytes 8 to
                                           N-2)

  4            2          Write Counter    Monotonically incrementing uint16

  6            2          Secondary        XOR-based checksum (seed 0xAF17)
                          Checksum         

  8            N-10       Data Payload     The actual block content

  N-2          2          End Marker       Always 0xFFFF
  ------------ ---------- ---------------- -------------------------------------

Where N is the page size (264 or 528). The CRC32 covers bytes 8 through
N-3 (exclusive of the end marker). The secondary checksum is computed by
XOR-ing the first three big-endian uint16 words at offsets 0, 2, and 4,
then byte-swapping the result. Both checksums must match for a page to
be considered valid.

**2.3 Secondary Checksum Algorithm**

Pseudocode for the secondary (XOR) checksum:

> uint16 computeSecondaryChecksum(byte\[\] page) {
>
> uint16 w0 = getUint16BE(page, 0);
>
> uint16 w1 = getUint16BE(page, 2);
>
> uint16 w2 = getUint16BE(page, 4);
>
> uint16 result = 0xAF17 \^ w0 \^ w1 \^ w2;
>
> return swapBytes16(result);
>
> }

**2.4 Block (Payload) Layout**

The data payload within each page (bytes 8 to N-10) has the following
structure:

  ------------ ---------- ---------------- -------------------------------------
  **Offset**   **Size**   **Field**        **Description**

  0            1          Flags            Bit 7: BLOCK_USED (0x80 = in use,
                                           0x00 = free)

  1            1          Data Byte Count  Number of valid data bytes in this
                                           block

  2            2          File ID          uint16 BE --- page index of file's
                                           first block

  4            2          Part ID          uint16 BE --- sequence number (0 =
                                           first chunk)

  6            1          Unknown          0x00 on first block

  7            40         Filename         Null-terminated string (valid only
                                           when Part ID == 0)
  ------------ ---------- ---------------- -------------------------------------

**2.5 File Header (Part 0 Only)**

When Part ID == 0 (the first block of a file), a 72-byte file header
(FILE_HEADER_SIZE = 0x48) begins at offset 6 in the payload. The last 4
bytes of this header contain the file timestamp as a uint32 big-endian
value representing seconds since 1900-01-01 00:00:00 UTC.

Data layout for first block (Part ID == 0):

- Bytes 6 to 77 (6 + 72 - 1): File header including filename and
  timestamp

- Byte 78 onward: Actual file data begins

Data layout for subsequent blocks (Part ID \> 0):

- Byte 6 onward: File data continues

**2.6 File Enumeration Algorithm**

To list all files in a CFS image:

> for page = 5 to pageCount - 1:
>
> rawPage = readPage(image, page)
>
> if not validateChecksums(rawPage): skip
>
> payload = rawPage\[8 .. N-2\]
>
> if payload\[0\] & 0x80 == 0: skip // not in use
>
> if getUint16BE(payload, 4) != 0: skip // not first part
>
> filename = readNullString(payload, 7, 40)
>
> fileId = getUint16BE(payload, 2)
>
> timestamp = getUint32BE(payload, 6 + 0x48 - 4)
>
> addDirectoryEntry(filename, fileId, page, timestamp)

**2.7 File Reassembly**

To read the complete data for a file, collect all blocks with the same
File ID, order them by Part ID, and concatenate their data payloads
(respecting the Data Byte Count field and the offset difference between
Part 0 and subsequent parts).

**3. Application Architecture**

**3.1 Package Structure**

> com.cybiko.nvram
>
> ├─ App.java // JavaFX Application entry point
>
> ├─ model/
>
> │ ├─ CfsImage.java // Parsed CFS flash image
>
> │ ├─ CfsPage.java // Single page with checksums
>
> │ ├─ CfsBlock.java // Decoded block payload
>
> │ ├─ CfsFile.java // Reassembled file (all parts)
>
> │ ├─ CfsChecksum.java // CRC32 + XOR checksum logic
>
> │ ├─ FlashGeometry.java // Enum: AT45DB041/081/161
>
> │ ├─ AppEntry.java // Library entry (game/book/util)
>
> │ ├─ AppLibrary.java // Filesystem-based app collection
>
> │ └─ AppType.java // Enum: GAME, BOOK, UTILITY, OTHER
>
> ├─ io/
>
> │ ├─ CfsReader.java // Read/parse CFS images
>
> │ ├─ CfsWriter.java // Write/modify CFS images
>
> │ └─ LibraryScanner.java // Scan folders/zips for apps
>
> ├─ ui/
>
> │ ├─ MainWindow.java // Root layout + menu bar
>
> │ ├─ SidebarPane.java // Left: library + NVRAM list
>
> │ ├─ ContentListPane.java // Middle: sortable app table
>
> │ ├─ DetailPane.java // Right: app details + actions
>
> │ ├─ CapacityBar.java // Bottom: iTunes-style usage bar
>
> │ └─ HexViewerDialog.java // Modal hex viewer window
>
> └─ serial/ // Stretch goal
>
> ├─ CybikoSerialConnection.java // jSerialComm wrapper
>
> └─ CyberloadProtocol.java // Cyberload/EZ Loader protocol

**3.2 Model Layer**

**3.2.1 FlashGeometry Enum**

Determines image parameters from file size:

> public enum FlashGeometry {
>
> AT45DB041(0x084000, 2048, 264),
>
> AT45DB081(0x108000, 4096, 264),
>
> AT45DB161(0x210000, 4096, 528);
>
> final int imageSize, pageCount, pageSize;
>
> int payloadSize() { return pageSize - 10; }
>
> int bootPages() { return 5; }
>
> static FlashGeometry fromFileSize(long size) { \... }
>
> }

**3.2.2 CfsImage**

Holds the complete parsed image:

- **byte\[\] rawData** --- the entire image file contents

- **FlashGeometry geometry** --- detected flash type

- **List\<CfsPage\> pages** --- all parsed pages

- **List\<CfsFile\> files** --- reassembled files with metadata

- **boolean dirty** --- tracks unsaved modifications

- **Path sourcePath** --- original file path

**3.2.3 CfsFile**

Represents a single file stored in the image:

- **String filename** --- from the Part 0 header

- **int fileId** --- page index of first block

- **List\<Integer\> blockPages** --- ordered list of page indices

- **byte\[\] data** --- reassembled file contents

- **long timestamp** --- seconds since 1900-01-01

- **LocalDateTime dateTime** --- converted timestamp

- **int totalSize** --- sum of all data byte counts

**3.2.4 AppLibrary**

Filesystem-based library that scans a root directory (or zip archive)
for Cybiko application files. The directory structure provides
categories:

> library_root/
>
> games/ → AppType.GAME
>
> books/ → AppType.BOOK
>
> apps/ → AppType.UTILITY
>
> other/ → AppType.OTHER

Each .app file found becomes an AppEntry. The library supports
re-scanning and watches for filesystem changes.

**3.2.5 AppEntry**

Represents a single application in the local library:

- **String name** --- display name (derived from filename, cleaned up)

- **Path filePath** --- location on disk

- **AppType type** --- category from parent directory name

- **long fileSize** --- size in bytes

- **byte\[\] data** --- lazily loaded file contents

**4. User Interface Specification**

The UI follows an iTunes-era three-panel layout. The overall window
structure from top to bottom is: menu bar, toolbar, three-panel content
area, and a bottom status/capacity bar.

**4.1 Window Layout**

> ┌───────────────────────────────────────────────────────────┐
>
> │ \[File\] \[Edit\] \[NVRAM\] \[View\] \[Help\] │ Menu Bar
>
> ├───────────────────────────────────────────────────────────┤
>
> │ \[Open\] \[Save\] \| \[+ Add\] \[− Remove\] \| \[Import\]
> \[Search\...\] │ Toolbar
>
> ├──────────┬────────────────────────────────┬───────────────┤
>
> │ LIBRARY │ Name Type Size NVRAM │ \[icon\] │
>
> │ │ │ │
>
> │ \> All │ Alien.. Game 14K ● │ CyBattle │
>
> │ Games │ Battle.. Game 18K ● │ Strategy Game │
>
> │ Books │ CyBook.. Book 8K ─ │ v1.2 │
>
> │ Utils │ \[CyBattle Game 22K ●\] │ │
>
> │ │ CyLand.. Game 11K ● │ \[Add\] \[Remove\]│
>
> │──────────│ CyRace.. Game 19K ─ │ │
>
> │ NVRAM │ Dict\... Book 31K ● │ File Info │
>
> │ │ Dungeon.. Game 24K ● │ Size: 22.1 KB │
>
> │ \> main │ FileMgr.. Util 6K ● │ Offset: 0xA4..│
>
> │ backup │ │ │
>
> │ │ │ Description │
>
> │──────────│ │ Turn-based\... │
>
> │ SMART │ │ │
>
> │ Recently │ │ │
>
> ├──────────┴────────────────────────────────┴───────────────┤
>
> │ cybiko_main.bin \[███████████▓▓▓▓░░░░ \] 192K/256K │ Capacity Bar
>
> └───────────────────────────────────────────────────────────┘

**4.2 Menu Bar**

**File Menu**

- **Open NVRAM Image\...** (Ctrl+O) --- FileChooser for .bin files

- **Save NVRAM Image** (Ctrl+S) --- Save in place

- **Save As\...** (Ctrl+Shift+S) --- Save to new file

- **Import App to Library\...** (Ctrl+I) --- Copy .app file into library
  folder

- **Import Folder to Library\...** --- Bulk import

- **Exit** --- With unsaved changes prompt

**Edit Menu**

- **Select All** (Ctrl+A)

- **Delete Selected** (Del)

- **Preferences\...** (Ctrl+,) --- Library path, theme, default NVRAM
  size

**NVRAM Menu**

- **Add Selected to NVRAM** --- Write selected library apps into active
  NVRAM

- **Remove Selected from NVRAM** --- Mark blocks as free

- **NVRAM Properties\...** --- Dialog showing flash type, page count,
  usage stats

- **Validate NVRAM Integrity** --- Check all page checksums, report
  errors

- **Export File Table\...** --- Export directory listing as CSV

**View Menu**

- **Show Games / Show Books / Show Utilities** --- Toggle filters

- **Hex Viewer\...** (Ctrl+H) --- Opens hex viewer for the active NVRAM
  image

**Help Menu**

- **About Cybiko NVRAM Manager**

- **Cybiko File Format Reference** --- Opens a summary of the CFS layout

**4.3 Left Sidebar (SidebarPane)**

The sidebar is a TreeView or VBox with three sections:

**Library Section**

- **All Applications** --- Shows all library entries in the middle panel

- **Games** --- Filtered view

- **Books** --- Filtered view

- **Utilities** --- Filtered view

**NVRAM Images Section**

Each opened NVRAM file appears here with its filename, app count, and a
mini capacity indicator showing used/total KB. Multiple NVRAM images can
be open simultaneously. Clicking one makes it the active image and shows
its contents in the middle panel.

**Smart Lists Section**

- **Recently Added** --- Library items added in the last 7 days

- **Not in Any NVRAM** --- Library items not present in any open NVRAM
  image

**4.4 Middle Content List (ContentListPane)**

A TableView with the following columns:

  ------------ ---------------------- ------------- -----------------------
  **Column**   **Source**             **Width**     **Notes**

  Name         Filename or display    Flex (fills   With type icon prefix
               name                   remaining)    

  Type         AppType enum           90px fixed    Game / Book / Utility /
                                                    Other

  Size         File size in KB        80px fixed    Right-aligned,
                                                    monospace

  NVRAM        Presence in active     100px fixed   Badge: \'On NVRAM\' or
               NVRAM                                dash
  ------------ ---------------------- ------------- -----------------------

The table supports: click-to-select with detail panel update,
multi-select (Ctrl+click, Shift+click) for batch operations, column
header click to sort, and the search bar in the toolbar filters by name.

A breadcrumb label above the table shows what is being viewed (e.g.,
\"Viewing: cybiko_main.bin • 12 items\").

**4.5 Right Detail Panel (DetailPane)**

Shows details for the currently selected item in the content list:

- **App Icon** --- Large icon area (80x80) with color-coded background
  by type

- **Title** --- Application name

- **Subtitle** --- Type and version if known

- **Action Buttons** --- \"Add to NVRAM\" and \"Remove\"
  (context-dependent enable/disable)

- **File Info Section** --- Filename, type, size, CyOS version
  requirement if known

- **NVRAM Location Section** --- (only when viewing NVRAM contents)
  Image name, byte offset, block count

- **Description** --- Free text area, potentially from a metadata
  sidecar file

**4.6 Bottom Capacity Bar (CapacityBar)**

An iTunes-style horizontal bar showing NVRAM usage, only visible when an
NVRAM image is selected:

- Color segments: **Green** = Games, **Blue** = Books, **Gray** =
  System/Boot, **Dark** = Free

- Legend row above the bar with segment labels and sizes

- Right-aligned label: \"192 KB / 256 KB\"

- Updates dynamically on add/remove operations

**4.7 Hex Viewer (HexViewerDialog)**

A modal or detached window showing the raw bytes of the currently active
NVRAM image:

- Three-column layout: offset (hex), hex bytes (16 per row), ASCII
  representation

- Highlight the page/block boundaries with alternating background colors

- Click a page to highlight it and show its decoded metadata in a side
  pane

- Search by hex pattern or ASCII string

- Read-only in v1 (editing is a future enhancement)

- Use a monospaced font (Consolas or JetBrains Mono) and virtual
  scrolling for large images

**5. Key Operations**

**5.1 Opening an NVRAM Image**

> 1\. User selects File \> Open NVRAM Image
>
> 2\. FileChooser opens (filter: \*.bin)
>
> 3\. CfsReader.read(path) is called:
>
> a\. Read entire file into byte\[\]
>
> b\. Detect FlashGeometry from file size
>
> c\. Parse each page (validate checksums, decode payload)
>
> d\. Enumerate files (scan Part ID == 0 blocks)
>
> e\. Reassemble multi-part files
>
> f\. Return CfsImage object
>
> 4\. CfsImage added to sidebar NVRAM section
>
> 5\. If first/only NVRAM, it becomes the active image

**5.2 Adding an App to NVRAM**

> 1\. User selects app(s) in content list
>
> 2\. Clicks \'Add to NVRAM\' button or menu item
>
> 3\. CfsWriter.addFile(image, appEntry) is called:
>
> a\. Calculate blocks needed: ceil(dataSize / payloadDataSize)
>
> b\. Find N contiguous or scattered free blocks
>
> c\. If insufficient space, show error dialog with deficit
>
> d\. Write Part 0 block: flags, file header, filename, timestamp, data
>
> e\. Write Part 1..N blocks: continuation data
>
> f\. Recompute CRC32 and secondary checksum for each modified page
>
> g\. Mark image as dirty
>
> 4\. Content list updates NVRAM status badge
>
> 5\. Capacity bar updates

**5.3 Removing an App from NVRAM**

> 1\. User selects app(s) in content list (while viewing NVRAM)
>
> 2\. Clicks \'Remove\' button
>
> 3\. Confirmation dialog: \'Remove CyBattle from cybiko_main.bin?\'
>
> 4\. CfsWriter.removeFile(image, cfsFile) is called:
>
> a\. For each block belonging to the file:
>
> \- Clear BLOCK_USED flag (set byte 0 of payload to 0x00)
>
> \- Recompute page checksums
>
> b\. Mark image as dirty
>
> 5\. File disappears from NVRAM listing
>
> 6\. Capacity bar updates (free space increases)

**5.4 Saving an NVRAM Image**

> 1\. User selects File \> Save (or Save As)
>
> 2\. If Save As, FileChooser for destination path
>
> 3\. CfsWriter.write(image, path) is called:
>
> a\. Final integrity check on all modified pages
>
> b\. Write rawData byte\[\] to file
>
> c\. Clear dirty flag
>
> 4\. Title bar updated (remove asterisk if present)

**5.5 Library Scanning**

> 1\. On startup, LibraryScanner reads configured library path
>
> 2\. If path is a .zip, extract and scan internal structure
>
> 3\. If path is a directory, walk the tree:
>
> \- games/ -\> AppType.GAME
>
> \- books/ -\> AppType.BOOK
>
> \- apps/ -\> AppType.UTILITY
>
> \- other/ -\> AppType.OTHER (or uncategorized files)
>
> 4\. Each discovered file becomes an AppEntry
>
> 5\. Results populate the Library section sidebar counts
>
> 6\. File watch (WatchService) monitors for added/removed files

**6. JavaFX Implementation Notes**

**6.1 Recommended JavaFX Structure**

The main window uses a BorderPane as the root layout:

- **Top:** VBox containing MenuBar and ToolBar

- **Center:** SplitPane with three sections (sidebar, content list,
  detail panel)

- **Bottom:** HBox containing the CapacityBar

**6.2 CSS Theming**

Use a dark theme via an external CSS file. The mock uses these colors
which translate well to JavaFX CSS:

  -------------------- ------------- --------------------------------------
  **CSS Variable**     **Value**     **Usage**

  -fx-bg-deep          #0d1117       Main background

  -fx-bg-panel         #161b22       Sidebar, toolbar, status bar

  -fx-bg-hover         #21262d       Hover states

  -fx-bg-selected      #1a3a2a       Selected items (green tint)

  -fx-border           #30363d       All borders

  -fx-accent           #3fb950       Active indicators, primary buttons

  -fx-text-primary     #e6edf3       Main text

  -fx-text-secondary   #8b949e       Secondary text

  -fx-text-muted       #6e7681       Muted labels
  -------------------- ------------- --------------------------------------

**6.3 TableView Configuration**

The content list TableView should use:

- **ObservableList\<AppEntry\>** as the data source with FilteredList
  and SortedList wrappers

- **Custom cell factories** for the NVRAM status column (badge
  rendering) and the Name column (icon + text)

- **Column resize policy:** CONSTRAINED_RESIZE_POLICY with the Name
  column growing

- Multi-select enabled via SelectionMode.MULTIPLE

**6.4 Hex Viewer Implementation**

The hex viewer should use a **VirtualFlow** or **ListView** with a
custom cell factory for performance with large images (up to 2MB). Each
row renders 16 bytes with three regions: offset label, hex bytes, and
ASCII representation. Use a monospaced font at 12-13px.

**6.5 Drag and Drop**

Support dragging AppEntry items from the library content list onto an
NVRAM sidebar item to add apps. The drag gesture should show a count
badge for multi-select drags. The NVRAM sidebar item should highlight as
a valid drop target when a compatible drag is in progress.

**6.6 Thread Safety**

File I/O operations (reading NVRAM images, scanning library, saving)
should run on background threads via Task\<\> to avoid blocking the
JavaFX application thread. Use Platform.runLater() to update UI elements
from background threads. Show progress indicators in the status bar for
long operations.

**7. Gradle Build Configuration**

**7.1 build.gradle.kts**

> plugins {
>
> java
>
> application
>
> id(\"org.openjfx.javafxplugin\") version \"0.1.0\"
>
> }
>
> java {
>
> toolchain {
>
> languageVersion.set(JavaLanguageVersion.of(21))
>
> }
>
> }
>
> javafx {
>
> version = \"21.0.2\"
>
> modules = listOf(\"javafx.controls\", \"javafx.fxml\")
>
> }
>
> dependencies {
>
> // Serial communication (stretch goal)
>
> // implementation(\"com.fazecast:jSerialComm:2.10.4\")
>
> }
>
> application {
>
> mainClass.set(\"com.cybiko.nvram.App\")
>
> }

**7.2 Module Info**

> module com.cybiko.nvram {
>
> requires javafx.controls;
>
> requires javafx.fxml;
>
> exports com.cybiko.nvram;
>
> exports com.cybiko.nvram.ui;
>
> }

**8. Stretch Goal: Serial Device Connection**

**8.1 Overview**

The original Cybiko desktop software (CyberLoad, running on Windows
95/98) communicated with the device over a serial cable. Reimplementing
this connection would allow the NVRAM Manager to read from and write to
real Cybiko hardware.

**8.2 Known Serial Parameters**

From reverse engineering and community documentation:

- **External serial port baud rate:** Typically 57600 baud (CyberLoad
  default), some tools use 115200

- **Internal H8S to AVR coprocessor:** 53,333 baud (not directly
  relevant for external communication)

- **Data format:** 8N1 (8 data bits, no parity, 1 stop bit)

- **Flow control:** Hardware (RTS/CTS) or none depending on cable

**8.3 Interface Design**

The serial connection should be modeled behind a clean interface so the
rest of the application doesn't need to know whether it's working with a
file or a live device:

> public interface CfsStorage {
>
> CfsImage read() throws IOException;
>
> void write(CfsImage image) throws IOException;
>
> FlashGeometry getGeometry();
>
> boolean isConnected();
>
> }
>
> // File-based implementation (v1)
>
> public class CfsFileStorage implements CfsStorage { \... }
>
> // Serial-based implementation (stretch goal)
>
> public class CfsSerialStorage implements CfsStorage { \... }

**8.4 jSerialComm Integration**

The jSerialComm library provides cross-platform serial port access with
no native dependencies:

- Port enumeration: **SerialPort.getCommPorts()**

- Configuration: baud rate, data bits, stop bits, parity, flow control

- Blocking and non-blocking read/write modes

- Event-driven data listening

**8.5 UI Integration**

When serial support is enabled, add to the sidebar NVRAM section:

- A \"Connect Device\...\" item that opens a port selection dialog

- Connected devices appear alongside file-based NVRAM images

- A green dot indicator shows connection status (connected/disconnected)

- Read/write operations show progress with the option to cancel

**9. Implementation Phases**

**Phase 1: Core Model + CFS Parser**

- Implement FlashGeometry, CfsPage, CfsBlock, CfsFile, CfsImage

- Implement CfsChecksum (CRC32 + XOR secondary)

- Implement CfsReader with full page validation

- Write unit tests against known NVRAM image files

- Implement file enumeration and reassembly

**Phase 2: Basic UI Shell**

- Set up Gradle project with JavaFX 21

- Implement MainWindow with BorderPane layout

- Implement dark theme CSS

- Implement SidebarPane with hardcoded Library section

- Implement ContentListPane with TableView (sortable, filterable)

- Implement DetailPane with static layout

- Wire up File \> Open to CfsReader and populate the table

**Phase 3: Library + Full NVRAM Operations**

- Implement LibraryScanner (folder + zip support)

- Implement AppLibrary with filesystem watching

- Implement CfsWriter (add file, remove file, save)

- Wire up Add/Remove buttons and menu items

- Implement CapacityBar with color-coded segments

- Implement unsaved changes tracking and save prompts

**Phase 4: Polish**

- Implement HexViewerDialog with virtual scrolling

- Implement drag-and-drop between library and NVRAM

- Implement multi-select batch operations

- Add Smart Lists (Recently Added, Not in Any NVRAM)

- Add NVRAM Properties dialog

- Add Validate NVRAM Integrity function

- Add Export File Table as CSV

**Phase 5: Stretch --- Serial Connection**

- Add jSerialComm dependency

- Implement CfsStorage interface + CfsFileStorage refactor

- Implement CfsSerialStorage with CyberLoad protocol

- Add Connect Device dialog

- Add live device indicator in sidebar

- Test with real hardware

**10. Reference**

**10.1 CFS Format Source**

The CFS binary format specification in this document is derived from
MAME's imgtool implementation by Tim Schuerewegen (2007), licensed under
BSD-3-Clause. Source location: src/lib/formats/cybiko_dsk.cpp in the
MAME repository.

**10.2 UI Mock**

An interactive HTML mock of the UI is provided alongside this document.
Open cybiko-nvram-manager.html in a browser to see the target look and
feel. The mock demonstrates the three-panel layout, dark theme, capacity
bar, menu structure, and hover interactions.

**10.3 Cybiko Hardware Reference**

- **Cybiko Classic v1:** H8S/2246 CPU, Atmel AT45DB041 flash (264-byte
  pages, 2048 pages)

- **Cybiko Classic v2:** H8S/2246 CPU, separate boot ROM, AT45DB081
  flash

- **Cybiko Xtreme:** H8S/2323 CPU, AT45DB161 flash (528-byte pages, 4096
  pages)

- **Serial port:** Proprietary connector, RS-232 levels, 57600 baud
  default

**10.4 Key Libraries**

- **JavaFX 21:** UI framework (org.openjfx Gradle plugin)

- **jSerialComm 2.10.x:** Serial port communication (stretch goal)

- **java.util.zip.CRC32:** Standard library CRC32 for checksum
  validation
