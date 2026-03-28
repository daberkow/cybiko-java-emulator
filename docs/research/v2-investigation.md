# V2 Investigation: LCD Never Updates After Boot

## Summary
V2 CyOS (cyrom117.bin + cyos_v1358.bin + flash_v1358.bin) boots past the boot ROM
but the LCD never updates after the initial test pattern. The root cause is that CyOS's
main UI task blocks reading "cyos.cfg" from the SPI flash (AT45DB041) when the file
data spans multiple CFS pages. MAME also cannot fully boot V2 CyOS with these ROMs.

## Boot Sequence (V2)
1. Boot ROM draws gradient test pattern to LCD (frame ~1-45)
2. Boot ROM loads CyOS from memory-mapped flash at 0x100000
3. CyOS decompresses, creates 6 tasks, starts RTOS scheduler (frame ~45-60)
4. DTC+SCI1 reads CFS directory from SPI flash (2,102 DTC transfers during boot)
5. At frame ~123, timer dispatches event handler task (0x20889E, pri=200) and
   animation task (0x208706, pri=50) simultaneously
6. Event handler opens "/default/System.a" (succeeds), then "cyos.cfg"
7. "cyos.cfg" first ~84 bytes read successfully from pre-loaded CFS page
8. Next read (4 bytes of second record) **blocks** - continuation page not fetched
9. Task suspended (pri=0, not re-queued), LCD rendering code at 0x10AEE4 never reached

## V2 CyOS Task Map
| Task Addr | Base Pri | Handler (vtable[4]) | Role |
|-----------|----------|---------------------|------|
| 0x2001C4 | 1 | (idle/SLEEP) | Dispatcher/idle loop |
| 0x208BA4 | 201 | 0x1256C6 | Semaphore event loop |
| 0x20889E | 200 | 0x10B1C4 | **Event dispatcher (UI/LCD)** |
| 0x208706 | 50 | 0x123AB8 | Scrolling/animation |
| 0x20775E | 5 | 0x119F62 | Periodic housekeeping |
| 0x20831E | 5 | 0x117EDE | I2C/RTC related |

## V2 RTOS Scheduler (disassembled)
Task Control Block (partial):
| Offset | Field | Description |
|--------|-------|-------------|
| 44 (0x2C) | state | 0=idle, 1=prepared/ready |
| 48 (0x30) | accum_time | CPU time consumed |
| 84 (0x54) | pri84 | Current/effective priority |
| 85 (0x55) | base_pri | Base priority (restored by prepare_task) |
| 88 (0x58) | next | Queue link pointer |
| 92 (0x5C) | saved_sp | Saved stack pointer |
| 100 (0x64) | queue_ptr | Current wait queue |

Key globals: `0x20022C`=current_task, `0x200230`=ready_queue, `0x200039`=sched_lock

Scheduler functions:
- `yield(task)` at 0x1067F0: sets pri84=0, calls reschedule
- `reschedule()` at 0x1068E4: if ready_head.pri >= current.pri → context switch.
  **Tasks with pri=0 are NOT re-queued** (0x106934: BEQ skips insert_into_queue)
- `prepare_task(task)` at 0x106874: copies base_pri→pri84, inserts into ready_queue
- `setup_dispatch(task)` at 0x106C1A: calls 0x107838 + prepare_task + optional reschedule

## The Blocking Chain
1. Handler 0x10B1C4 calls event_poll (0x10B05E) → blocks until event arrives
2. Timer dispatches task → event_poll returns 0x0007 (bits 0,1,2 set)
3. Handler calls 0x10BDE8 (process event, opens "cyos.cfg")
4. 0x10BDE8 reads file via producer-consumer queue model:
   - `file_read_bytes(handle, buf, N)` at 0x110248
   - Calls `semaphore_wait(handle+8, count)` at 0x10FC4E
   - If queue has data → returns immediately; if empty → blocks task
5. First CFS page data (pre-loaded during file_open) consumed after ~84 bytes
6. Next `semaphore_wait` finds empty queue → `set_task_queue` → yield → **suspended**
7. No mechanism fetches the continuation CFS page → task permanently blocked

## CyOS File I/O Architecture (V2)
```
file_open("cyos.cfg")
  → CFS directory lookup (cached in RAM at 0x20F21C from boot DTC reads)
  → DTC reads first data page from SPI flash (AT45DB041)
  → Pre-loads page data into file handle queue at handle+8
  → Returns handle with ~170 bytes of data ready

file_read_bytes(handle, buf, count)
  → semaphore_wait(handle+8, count) — blocks if queue empty
  → dequeue bytes from queue
  → update file offset at handle+68
  → if queue exhausted: BLOCKS (continuation page fetch broken)
```

File handle structure:
| Offset | Content |
|--------|---------|
| 0 | ptr → status struct (bit 0=READY/closed, bit 1=ERROR) |
| 8 | ptr → semaphore/data queue |
| 68 | file read position (32-bit) |

## Why Continuation Pages Aren't Fetched
The CyOS file I/O uses a queue-based producer-consumer model. When the file handle's
data queue is exhausted, the consumer task blocks on the semaphore waiting for more
data. A producer (likely a callback or background task) should:
1. Detect queue underflow
2. Initiate DTC+SCI1 read of the next CFS page from SPI flash
3. Enqueue the page data into the file handle's queue
4. Signal the semaphore to unblock the consumer

This continuation mechanism is not working. Possible causes:
- The queue underflow callback isn't being triggered
- The DTC setup for continuation reads uses a different code path than the initial read
- The background flash driver task (possibly 0x208BA4) is never dispatched to process
  the I/O request
- MAME's V2 emulation has the same limitation (hangs at "CyOS loading")

## V2 Timer Queue Architecture (disassembled)
The queue at 0x200234 is a lightweight TIMER QUEUE separate from the full RTOS task
scheduler. Timer entry structure (from disassembly of scheduler at 0x1076FA):
| Offset | Field | Description |
|--------|-------|-------------|
| 0x00 | deadline | 32-bit tick count to fire at (compared with 0x20004E) |
| 0x04 | interval | 32-bit repeat period (re-enqueued after each fire) |
| 0x08 | one_shot | Byte flag (1=one-shot, 0=repeating) |
| 0x0C | next | Pointer to next entry in queue |
| 0x10 | desc_ptr | Callback descriptor pointer → descriptor[0x08] = handler function |

Active timer entries at frame ~1200:
- `0x208706`: interval=250, handler=0x1239CC (display handler)
- `0x20775E`: interval=5000, handler=0x106AB2 (idle handler)

## V2 Display Handler Chain (0x1239CC)
The display handler runs every 250 ticks (~once per 16 frames) and gates rendering
through a chain of entry flags:
1. **entry[0x96]** (offset 150): If 0 → calls idle handler (0x106AB2), skips display
2. **entry[0x9A]** (offset 154): If non-zero → consumer loop (display_update 0x123752)
3. **entry[0x91]** (offset 145): **If 0 → skips ALL rendering at 0x123C8E**

The display handler also accumulates scroll velocity and increments a counter. After
256 handler calls, it calls task_resume to wake the display init task (0x123AB8) which
then enters another 256-call cycle. This takes ~4096 frames per cycle.

## V2 Display Rendering Blocked - Root Cause
After extensive debugging (V2Debug106-V2Debug118), the full chain was traced:
1. **entry[0x91] stays 0** — no CyOS code ever sets it because the desktop app never starts
2. **Desktop app depends on RF service readiness** — RF init never completes without hardware
3. **RF hardware not emulated** — RF2915 radio SPI commands get no response
4. **Even forcing entry[0x91]=1** doesn't help — there's no display content registered

The display system itself works correctly (display_update called 594+ times, handler
fires on schedule), but it has no content to render.

## V2 Code Stub Injection Technique
Debug scripts use H8S machine code injection to bypass the RF dependency:
```
Write stub to RAM after splash (not before — CyOS init overwrites early RAM):
  STUB_ADDR = 0x23FE00 (near end of external RAM)
  MOV.L #RF_OBJ, ER0     ; 7A 00 00 20 2C B2
  JSR @change_state_to_1  ; 5E 10 74 6E
  JMP @scheduler_return   ; 5A 10 98 DC
```
When the scheduler runs (0x1098DC), redirect to stub if RF has pending waiters.
This successfully wakes the RF task (rfState 0→1, rfWl cleared), but the desktop
app still doesn't start because it needs more than just RF state=1.

## V2 set_task_state Auto-Resolve Strategy
The emulator always-resolves non-RF set_task_state calls (not once-per-pair). Services
like 0x208B84 are polled ~2.3M times during normal boot. The RF object (0x202CB2) is
blacklisted during boot because resolving it early triggers RF hardware init that
fails and permanently breaks boot (confirmed by V2Debug114).

## Next Steps to Fix V2
1. **Implement RF chip SPI stub** — respond with "initialized OK" to RF2915 commands
2. **Find RF→desktop dependency** — identify which CyOS function starts the desktop app
   and what conditions it checks beyond RF state=1
3. **Try different ROMs** — a web-based V2 emulator exists that works with its own ROMs;
   different CyOS versions may handle RF dependency differently
4. **Compare with V1** — V1 CyOS file I/O works and has no RF dependency for boot
5. **Disassemble 0x10FC4E** (semaphore_wait) to understand queue refill for multi-page reads

## MAME V2 Key Source Files
- `src/mame/cybiko/cybiko.cpp` lines 86-100: V2 memory map
- `src/mame/cybiko/cybiko_m.cpp` lines 126-132: V2 keyboard quirk (ESC bit OR)
- `src/devices/machine/at45dbxx.cpp`: AT45DB041 SPI flash protocol (bit-level)
- `src/tools/imgtool/modules/cybiko.cpp`: V1/V2 CFS format (264-byte pages)

## V2 CFS Format (differs from XT)
V1/V2 use 264-byte pages (vs XT's 258-byte):
| Offset | Size | Content |
|--------|------|---------|
| 0-3 | 4 | CRC32 checksum |
| 4-5 | 2 | Write count |
| 6-7 | 2 | CRC16 verification |
| 8-263 | 256 | Block data (same format as XT) |

flash_v1358.bin: 2048 pages × 264 bytes = 540,672 bytes. "cyos.cfg" found at page 287.
