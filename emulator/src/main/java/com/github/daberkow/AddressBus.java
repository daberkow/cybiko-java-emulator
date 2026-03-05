package com.github.daberkow;

import java.util.ArrayList;
import java.util.List;

/**
 * Memory-mapped I/O bus for Cybiko emulators (V1, V2, XT).
 * Routes reads/writes to the correct device based on address and machine config.
 *
 * XT Memory map:
 *   0x000000-0x03FFFF  Boot ROM (32KB mirrored)
 *   0x100000-0x100001  LCD controller (HD66421)
 *   0x200000-0x200003  USB controller (stub)
 *   0x400000-0x5FFFFF  External RAM (2MB)
 *   0x600000-0x7FFFFF  Flash ROM (512KB mirrored)
 *   0xE00000-0xEFFFFF  Keyboard matrix
 *   0xFFDC00-0xFFFFFF  On-chip RAM & I/O registers
 *
 * V1/V2 Memory map:
 *   0x000000-0x007FFF  Boot ROM (32KB, no mirror)
 *   0x200000-0x27FFFF  External RAM (V1: 512KB, V2: 256KB)
 *   0x600000-0x600001  LCD controller
 *   0xE00000-0xEFFFFF  Keyboard matrix
 *   0xFFEC00-0xFFFFFF  On-chip RAM & I/O registers (V1: 4KB)
 *   V2 only: 0x100000  Memory-mapped flash (256KB)
 */
public class AddressBus {
    private final MachineConfig config;

    private Memory bootRom;
    private Memory externalRam;
    private Memory flashRom;       // null for V1
    private Memory onChipRam;
    private HD66421Lcd lcd;

    // Timer peripherals
    private H8STimer8 timer8_0;
    private H8STimer8 timer8_1;
    private final H8STimer16[] timer16 = new H8STimer16[6]; // slots 0-5, null if not present

    // RTC (PCF8593) connected via I2C on Port F
    private PCF8593Rtc rtc = new PCF8593Rtc();

    // I/O registers
    private int tstr = 0;    // Timer Start Register (0xFFFFC0)
    private int ier = 0;     // Interrupt Enable Register (0xFFFF2E)
    private int isr = 0;     // Interrupt Status Register (0xFFFF2F)

    // Serial output buffers (one per SCI channel)
    private final StringBuilder[] sciOutput = {new StringBuilder(), new StringBuilder(), new StringBuilder()};

    // SCI TDR raw hex logs for radio protocol analysis
    private final List<Integer> sci0TdrLog = new ArrayList<>();
    public List<Integer> getSci0TdrLog() { return sci0TdrLog; }
    private final List<Integer> sci2TdrLog = new ArrayList<>();
    public List<Integer> getSci2TdrLog() { return sci2TdrLog; }

    // SCI0 register access log (captures ALL SCI0 interactions: config + data)
    private final List<String> sci0RegLog = new ArrayList<>();
    public List<String> getSci0RegLog() { return sci0RegLog; }
    public boolean isV1RadioBootstrapped() { return v1RadioBootstrapped; }
    // SCI2 register access log
    private final List<String> sci2RegLog = new ArrayList<>();
    public List<String> getSci2RegLog() { return sci2RegLog; }
    private static final String[] SCI_REG_NAMES = {"SMR", "BRR", "SCR", "TDR", "SSR", "RDR", "SCMR"};
    private int sci0SsrLastLogPc = -1; // deduplicate SSR poll logging
    private int sci2SsrLastLogPc = -1;

    // Speaker state - Port 1 bit 3 (TIOCB1) drives the speaker
    private int speakerLevel = 0;
    private SpeakerOutput speakerOutput;
    public void setSpeakerOutput(SpeakerOutput speaker) { this.speakerOutput = speaker; }
    public int getSpeakerLevel() { return speakerLevel; }

    // ADC state
    private int adcsr = 0;
    private int adcr = 0x7E;

    // ICR (Interrupt Control Register) - V1/V2 H8S/2245 only
    private int icr = 0;

    // DMA Controller state (XT only)
    private final int[] dmaRegs = new int[32];
    private final int[] dmaCtrl = new int[8];

    // SPI flash (V1/V2) - AT45DB041 connected via SCI1
    private AT45DB041Flash spiFlash;
    private int sci1Rdr = 0;       // SCI1 receive data register (byte from SPI flash)
    private boolean sci1Rdrf = false; // SCI1 receive data register full

    // Radio co-processor (AVR AT90S2313) connected via SCI0 (V1/V2) or SCI2 (XT)
    private RadioCoProcessor radio;
    private int radioSciChannel = 0; // Which SCI channel the radio is on
    private int sci0Rdr = 0;       // SCI0 receive data register (byte from radio)
    private boolean sci0Rdrf = false; // SCI0 receive data register full

    // SCI0 interrupt-driven protocol state (V1/V2 radio)
    // V1 uses SCI0 in interrupt mode: TXI0 (vector 82) and RXI0 (vector 81) drive
    // the radio state machine. Boot ROM has trampolines that indirect through on-chip
    // RAM function pointers (0xFFEC88-0xFFEC94).
    private int sci0Scr = 0;           // Cached SCI0 SCR register value
    private boolean sci0Tdre = true;   // SCI0 TDRE flag (transmitter data register empty)
    private int sci0TxiDelay = 0;      // Countdown cycles until next TXI0 fires (0 = none pending)
    private static final int SCI0_TXI_DELAY = 32; // Cycles between TXI0 fires (~byte transmission time)

    // SCI2 interrupt-driven protocol state (XT radio)
    private int sci2Scr = 0;           // Cached SCI2 SCR register value
    private boolean sci2Tdre = true;   // SCI2 TDRE flag (transmitter data register empty)
    private int sci2TxiDelay = 0;      // Countdown cycles until next TXI2 fires (0 = none pending)
    private static final int SCI2_TXI_DELAY = 32; // Cycles between TXI2 fires (~byte transmission time)
    private int sci2PendingIndicator = -1; // Deferred frame indicator (0x32/0xC8) from TX DTC completion

    // Async radio frame delivery: check every N cycles if CyOS is idle and
    // frames are waiting. 512 cycles ≈ 28µs at 18.43MHz, responsive enough
    // for frame delivery without significant per-cycle overhead.
    private static final int ASYNC_RADIO_CHECK_INTERVAL = 512;
    private int asyncRadioCheckCountdown = 0;

    // V1 radio bootstrap: connObj is NULL at init and only allocated by the
    // completion handler (0x214876) or frame complete handler (0x2146C8), both of
    // which require an interrupt-driven cycle that needs connObj — chicken-and-egg.
    // We break this by setting state=9 and injecting 0x03 ACK, which triggers the
    // completion handler to allocate connObj. Must avoid re-injecting while the
    // handler is executing (race condition corrupts state).
    private boolean v1RadioBootstrapped = false;
    private int v1BootstrapDelay = 0; // Countdown before bootstrap attempt
    private boolean v1BootstrapPending = false; // True while waiting for handler to finish
    // V1 radio poll/scan driver: CyOS never calls the tick function (0x214760)
    // to start poll/scan TX cycles. We drive the radio by directly setting up
    // the state machine fields and enabling TIE every V1_POLL_INTERVAL frames.
    private int v1PollFrameCounter = 0;
    private int v1PollScanAlternator = 0; // 0-3: poll, 4: scan (4:1 ratio)
    private static final int V1_POLL_INTERVAL = 15; // frames between poll/scan (4/sec)
    private boolean v1DriverInitiatedTx = false; // True when tickV1Radio started this TX cycle
    // Saved connObj address: the completion handler frees connObj to pool +0x32F0
    // and allocates from pool +0x32C8 (which is empty → returns NULL). We save
    // the original address and restore it each cycle.
    private int v1SavedConnObj = 0;
    private int v1RadioId = 0; // Radio device ID for CyID patching (0 = no patch)
    private boolean v1CyIdPatched = false; // True after CyID patched in RAM
    // V1 CyID cached at this RAM address (CyOS v1246 specific)
    private static final int V1_CYID_RAM_ADDR = 0x21F9DC;
    // Initial delay: wait for CyOS to fully boot (memmgr, allocator ready).
    // Radio init fires during early boot (~frame 400), but we need the heap
    // allocator at 0x212D9A to be ready. 2M cycles ≈ 11 frames at 11.06MHz.
    private static final int V1_BOOTSTRAP_INITIAL_DELAY = 2_000_000;
    // Retry delay: if handler didn't allocate connObj, wait before retrying.
    // Must be long enough for the completion handler to fully execute (~1000 insns).
    private static final int V1_BOOTSTRAP_RETRY_DELAY = 500_000;

    // V1 radio object address (CyOS v1246 specific). The pointer to the radio
    // object is stored at this fixed address in decompressed CyOS code.
    private static final int V1_RADIO_OBJ_PTR_ADDR = 0x21F216;
    // V1 radio object field offsets
    private static final int V1_STATE_OFFSET = 0x3370;
    private static final int V1_CONNOBJ_OFFSET = 0x3344;

    // Deferred DTC completion: in real hardware, the DTC transfer takes multiple SPI clock
    // cycles. The completion interrupt fires after the last byte, by which time the CPU has
    // typically re-enabled interrupts. Our synchronous DTC fires during the SCR write (while
    // I=1), so if the caller's saved CCR also has I=1, the interrupt can never be serviced.
    // Fix: defer the interrupt by a few cycles to match real hardware timing.
    private int dtcCompletionDelay = 0; // Countdown cycles until vector 85 fires (0 = none)

    // CPU reference for debug logging
    private H8SCpu cpu;
    public void setCpu(H8SCpu cpu) { this.cpu = cpu; }

    public AddressBus(MachineConfig config) {
        this.config = config;
    }

    /** Backward-compatible constructor (defaults to XT). */
    public AddressBus() {
        this(MachineConfig.forType(MachineConfig.MachineType.XT));
    }

    public void setBootRom(Memory bootRom) { this.bootRom = bootRom; }
    public void setExternalRam(Memory externalRam) { this.externalRam = externalRam; }
    public void setFlashRom(Memory flashRom) { this.flashRom = flashRom; }
    public void setOnChipRam(Memory onChipRam) { this.onChipRam = onChipRam; }
    public void setLcd(HD66421Lcd lcd) { this.lcd = lcd; }
    public void setTimer8_0(H8STimer8 timer) { this.timer8_0 = timer; }
    public void setTimer8_1(H8STimer8 timer) { this.timer8_1 = timer; }
    public void setTimer16(int ch, H8STimer16 timer) { timer16[ch] = timer; }
    public void setSpiFlash(AT45DB041Flash flash) { this.spiFlash = flash; }
    public void setV1RadioId(int id) { this.v1RadioId = id; }
    public void setRadio(RadioCoProcessor radio) {
        this.radio = radio;
        // XT uses SCI2 for radio, V1/V2 use SCI0
        this.radioSciChannel = (config.type == MachineConfig.MachineType.XT) ? 2 : 0;
        // V1 uses byte-by-byte data accumulation (no DTC for radio)
        if (config.type == MachineConfig.MachineType.V1) {
            radio.setV1Mode(true);
        }
    }

    /**
     * Tick deferred DTC completion. Call from main loop after each cpu.step().
     * In real hardware, the DTC transfer takes multiple SPI clock cycles.
     * The completion interrupt fires after the last byte, by which time the CPU
     * has typically re-enabled interrupts (via LDC CCR). Deferring by a few cycles
     * prevents the interrupt from being requested while I=1 in the caller's save/restore.
     */
    public void tickDtcCompletion() {
        if (dtcCompletionDelay > 0) {
            dtcCompletionDelay--;
            if (dtcCompletionDelay == 0 && cpu != null) {
                cpu.requestInterrupt(85);
            }
        }
    }

    /**
     * Tick SCI0 interrupt generation. Call from main loop after each cpu.step().
     * V1/V2 use SCI0 for radio with interrupt-driven protocol:
     *   TXI0 (vector 82): fires when TDRE becomes set and TIE is enabled
     *   RXI0 (vector 81): fires when RDRF becomes set and RIE is enabled
     * H8S/2241 SCI0 vectors: ERI0=80, RXI0=81, TXI0=82, TEI0=83
     */
    public void tickSci0() {
        if (sci0TxiDelay > 0) {
            sci0TxiDelay--;
            if (sci0TxiDelay == 0) {
                sci0Tdre = true;
                // Fire TXI0 if TIE is enabled in SCR
                if ((sci0Scr & 0x80) != 0 && cpu != null) {
                    cpu.requestInterrupt(82); // TXI0
                }
            }
        }
        // RXI0: deliver radio response to RDR when RDRF is clear
        if (radio != null && radioSciChannel == 0 && !sci0Rdrf && radio.hasData()) {
            sci0Rdr = radio.read();
            sci0Rdrf = true;
            // Fire RXI0 if RIE is enabled in SCR
            if ((sci0Scr & 0x40) != 0 && cpu != null) {
                cpu.requestInterrupt(81); // RXI0
            }
        }
        // V1 radio bootstrap: allocate connObj by simulating a TX+ACK cycle.
        // On real hardware, the AVR co-processor's boot response triggers connObj
        // allocation. We emulate this by setting state=9 and injecting 0x03 ACK.
        if (radio != null && radioSciChannel == 0
                && config.type == MachineConfig.MachineType.V1
                && !v1RadioBootstrapped && radio.isInitialized()) {
            v1BootstrapDelay++;
            int delay = v1BootstrapPending ? V1_BOOTSTRAP_RETRY_DELAY : V1_BOOTSTRAP_INITIAL_DELAY;
            if (v1BootstrapDelay >= delay) {
                v1BootstrapDelay = 0;
                int radioObjAddr = (read8(V1_RADIO_OBJ_PTR_ADDR) << 24)
                        | (read8(V1_RADIO_OBJ_PTR_ADDR + 1) << 16)
                        | (read8(V1_RADIO_OBJ_PTR_ADDR + 2) << 8)
                        | read8(V1_RADIO_OBJ_PTR_ADDR + 3);
                if (radioObjAddr >= 0x200000 && radioObjAddr < 0x280000) {
                    int connObj = (read8(radioObjAddr + V1_CONNOBJ_OFFSET) << 24)
                            | (read8(radioObjAddr + V1_CONNOBJ_OFFSET + 1) << 16)
                            | (read8(radioObjAddr + V1_CONNOBJ_OFFSET + 2) << 8)
                            | read8(radioObjAddr + V1_CONNOBJ_OFFSET + 3);
                    if (connObj != 0) {
                        v1RadioBootstrapped = true;
                        v1SavedConnObj = connObj;
                        System.err.printf("[RADIO-V1] Bootstrap complete: radioObj=0x%06X connObj=0x%06X%n",
                                radioObjAddr, connObj);
                    } else {
                        // connObj is NULL — check if pool is ready before injecting
                        int poolAddr = radioObjAddr + 0x32C8;
                        int poolHead = (read8(poolAddr) << 24) | (read8(poolAddr+1) << 16)
                                | (read8(poolAddr+2) << 8) | read8(poolAddr+3);
                        if (poolHead == 0) {
                            // Pool not initialized yet — retry later
                        } else if (!v1BootstrapPending) {
                            // Pool ready, inject state=9 + 0x03 ACK
                            sci0Scr = 0x70;
                            write8(0xFFFF7A, 0x70);
                            write8(radioObjAddr + V1_STATE_OFFSET, 9);
                            radio.queueResponse(0x03);
                            v1BootstrapPending = true;
                        } else {
                            // Previous injection didn't result in connObj allocation
                            v1BootstrapPending = false;
                        }
                    }
                }
            }
        }
        // (Diagnostic state monitoring removed — use SCI0-REG log for debugging)
        // V1 async frame delivery (like XT's tickSci2 async path):
        // When CyOS state == 1 (idle), inject frame indicator for pending frames.
        if (radio != null && radioSciChannel == 0
                && config.type == MachineConfig.MachineType.V1
                && v1RadioBootstrapped
                && !sci0Rdrf && !radio.hasData()
                && radio.hasPendingFrames()) {
            asyncRadioCheckCountdown--;
            if (asyncRadioCheckCountdown <= 0) {
                asyncRadioCheckCountdown = ASYNC_RADIO_CHECK_INTERVAL;
                int radioObjAddr = (read8(V1_RADIO_OBJ_PTR_ADDR) << 24)
                        | (read8(V1_RADIO_OBJ_PTR_ADDR + 1) << 16)
                        | (read8(V1_RADIO_OBJ_PTR_ADDR + 2) << 8)
                        | read8(V1_RADIO_OBJ_PTR_ADDR + 3);
                if (radioObjAddr >= 0x200000 && radioObjAddr < 0x280000) {
                    int cyState = read8(radioObjAddr + V1_STATE_OFFSET);
                    if (cyState == 1) {
                        int indicator = radio.tryAsyncDelivery();
                        if (indicator >= 0) {
                            // Also queue the frame data for byte-by-byte delivery
                            int count = (indicator == 0xC8) ? 200 : 50;
                            radio.prepareRxFrame(count);
                        }
                    }
                }
            }
        }
    }

    /**
     * Drive V1 radio poll/scan cycles. Call once per frame from CybikoEmulator.
     *
     * CyOS V1 never calls the radio tick function (0x214760) from its scheduler
     * after init. Without periodic tick calls, the radio never enters state 7
     * (TX command) and never starts poll/scan cycles. We emulate the tick
     * function's effect by directly setting up the radio state machine fields:
     *   - Command byte (obj+0x336E): 0x30 (poll) or 0xCF (scan), from connObj+0xD2
     *   - Param byte (obj+0x336F): bit 3 of connObj+0x09
     *   - TX pointers: txPtr=connObj, txEnd=connObj+50 (poll) or connObj+200 (scan)
     *   - State = 7 (triggers TXI0 to send command sequence)
     *   - TIE enabled in SCR (starts TXI0 interrupt chain)
     *
     * This lets the TXI0/RXI0 handlers run naturally:
     *   7→8→2→9 (TX) → v1TxComplete → 0x03 ACK (RXI0 state 9→completion→state 1)
     *   → indicator (RXI0 state 1→frame handler→state 5) → frame data → state 1
     */
    public void tickV1Radio() {
        if (!v1RadioBootstrapped || radio == null
                || config.type != MachineConfig.MachineType.V1) return;

        v1PollFrameCounter++;
        if (v1PollFrameCounter < V1_POLL_INTERVAL) return;
        v1PollFrameCounter = 0;

        int radioObjAddr = (read8(V1_RADIO_OBJ_PTR_ADDR) << 24)
                | (read8(V1_RADIO_OBJ_PTR_ADDR + 1) << 16)
                | (read8(V1_RADIO_OBJ_PTR_ADDR + 2) << 8)
                | read8(V1_RADIO_OBJ_PTR_ADDR + 3);
        if (radioObjAddr < 0x200000 || radioObjAddr >= 0x280000) return;

        int state = read8(radioObjAddr + V1_STATE_OFFSET);
        if (state != 1) return; // Only drive when idle

        // Restore connObj if completion handler NULLed it
        int connObj = (read8(radioObjAddr + V1_CONNOBJ_OFFSET) << 24)
                | (read8(radioObjAddr + V1_CONNOBJ_OFFSET + 1) << 16)
                | (read8(radioObjAddr + V1_CONNOBJ_OFFSET + 2) << 8)
                | read8(radioObjAddr + V1_CONNOBJ_OFFSET + 3);
        if (connObj == 0 && v1SavedConnObj != 0) {
            // Restore saved connObj address
            write8(radioObjAddr + V1_CONNOBJ_OFFSET, (v1SavedConnObj >> 24) & 0xFF);
            write8(radioObjAddr + V1_CONNOBJ_OFFSET + 1, (v1SavedConnObj >> 16) & 0xFF);
            write8(radioObjAddr + V1_CONNOBJ_OFFSET + 2, (v1SavedConnObj >> 8) & 0xFF);
            write8(radioObjAddr + V1_CONNOBJ_OFFSET + 3, v1SavedConnObj & 0xFF);
            connObj = v1SavedConnObj;
        } else if (connObj != 0 && v1SavedConnObj == 0) {
            v1SavedConnObj = connObj;
        }
        if (connObj == 0) return; // No connObj available

        // Patch CyID: replace the ROM default with radio-id so each V1 emulator
        // has a unique identity. Patch both the RAM cache (for incoming frame
        // matching) and the connObj beacon frame (for outgoing CyID).
        if (!v1CyIdPatched && v1RadioId != 0) {
            // Patch cached CyID in RAM (0x21F9DC for CyOS v1246)
            write8(V1_CYID_RAM_ADDR, (v1RadioId >> 24) & 0xFF);
            write8(V1_CYID_RAM_ADDR + 1, (v1RadioId >> 16) & 0xFF);
            write8(V1_CYID_RAM_ADDR + 2, (v1RadioId >> 8) & 0xFF);
            write8(V1_CYID_RAM_ADDR + 3, v1RadioId & 0xFF);
            v1CyIdPatched = true;
            System.out.printf("Patched V1 CyID in RAM: 0x%08X%n", v1RadioId);
        }
        // Populate connObj beacon frame data. CyOS never calls tick() to prepare
        // the beacon, so we fill in the required fields matching the RF frame format
        // (see docs/rf2915-research.md). Only done once after CyID patching.
        if (v1RadioId != 0) {
            // Bytes 0-3: preamble (already FF FF FF FF from init)
            // Bytes 4-7: CyID
            write8(connObj + 4, (v1RadioId >> 24) & 0xFF);
            write8(connObj + 5, (v1RadioId >> 16) & 0xFF);
            write8(connObj + 6, (v1RadioId >> 8) & 0xFF);
            write8(connObj + 7, v1RadioId & 0xFF);
            // Byte 8: channel byte (set below based on current channel)
            // Byte 9: frame type = 0x01 (beacon/presence)
            write8(connObj + 9, 0x01);
            // Byte 16: 0x2A (42 decimal, payload length marker)
            write8(connObj + 16, 0x2A);
            // Bytes 20-27: username (default "unknown\0" if not set)
            // CyOS V1 default username is "g" per user observation
            if (read8(connObj + 20) == 0x00) {
                // Only set if not already populated by CyOS
                byte[] defaultName = "Cybiko\0\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                for (int i = 0; i < defaultName.length && i < 8; i++) {
                    write8(connObj + 20 + i, defaultName[i] & 0xFF);
                }
            }
        }

        // Reset txPtr to connObj (completion handler clears it to 0)
        write8(radioObjAddr + 0x3350, (connObj >> 24) & 0xFF);
        write8(radioObjAddr + 0x3351, (connObj >> 16) & 0xFF);
        write8(radioObjAddr + 0x3352, (connObj >> 8) & 0xFF);
        write8(radioObjAddr + 0x3353, connObj & 0xFF);

        // Determine poll vs scan from connObj+0xD2 (matching tick at 0x2147E4):
        // connObj+0xD2 != 0 → poll (0x30, 50 bytes); == 0 → scan (0xCF, 200 bytes)
        // If connObj+0xD2 isn't set, fall back to our alternating pattern.
        int connD2 = read8(connObj + 0xD2);
        int cmd;
        int txSize;
        if (connD2 != 0) {
            cmd = 0x30; // Poll
            txSize = 50;
        } else {
            // Use alternating pattern when connObj+0xD2 is 0
            if (v1PollScanAlternator >= 4) {
                cmd = 0xCF; // Scan
                txSize = 200;
                v1PollScanAlternator = 0;
            } else {
                cmd = 0x30; // Poll
                txSize = 50;
                v1PollScanAlternator++;
            }
        }

        // Read current channel (use connObj+0xD4 if set, else radioObj+0x3371)
        int connD4 = read8(connObj + 0xD4);
        int channel = (connD4 != 0) ? connD4 : read8(radioObjAddr + 0x3371);

        // Update channel byte in beacon frame (offset 8: 0xC0 | channel)
        write8(connObj + 8, 0xC0 | (channel & 0x3F));

        // Set up command and param bytes
        write8(radioObjAddr + 0x336E, cmd);
        // Param byte: bit 3 of connObj+0x09 (matching tick at 0x214800)
        int connObj09 = read8(connObj + 0x09);
        write8(radioObjAddr + 0x336F, (connObj09 >> 3) & 0x01);

        // Set txEnd = connObj + txSize (matching tick at 0x2147DC)
        // Real tick function sends 50 bytes for poll, 200 for scan FROM connObj
        int txEnd = connObj + txSize;
        write8(radioObjAddr + 0x3354, (txEnd >> 24) & 0xFF);
        write8(radioObjAddr + 0x3355, (txEnd >> 16) & 0xFF);
        write8(radioObjAddr + 0x3356, (txEnd >> 8) & 0xFF);
        write8(radioObjAddr + 0x3357, txEnd & 0xFF);

        // Set state = 7 (TX command byte)
        write8(radioObjAddr + V1_STATE_OFFSET, 7);
        v1DriverInitiatedTx = true;

        // Enable TIE in SCR and schedule first TXI0
        sci0Scr |= 0x80;
        if (sci0Tdre && sci0TxiDelay == 0) {
            sci0TxiDelay = SCI0_TXI_DELAY;
        }

    }

    /**
     * When a byte finishes "transmitting" (delay expires), TDRE goes high.
     * If TIE is set in SCR, fires TXI2 (vector 90).
     * When the radio has response data and RDRF is clear, delivers it to RDR
     * and fires RXI2 (vector 89) if RIE is set in SCR.
     */
    public void tickSci2() {
        if (sci2TxiDelay > 0) {
            sci2TxiDelay--;
            if (sci2TxiDelay == 0) {
                sci2Tdre = true;
                // Fire TXI2 if TIE is enabled in SCR
                if ((sci2Scr & 0x80) != 0 && cpu != null) {
                    cpu.requestInterrupt(90); // TXI2
                }
            }
        }
        // RXI2: deliver radio response to RDR when RDRF is clear
        if (radio != null && radioSciChannel == 2 && !sci0Rdrf && radio.hasData()) {
            sci0Rdr = radio.read();
            sci0Rdrf = true;
            // Fire RXI2 if RIE is enabled in SCR
            if ((sci2Scr & 0x40) != 0 && cpu != null) {
                cpu.requestInterrupt(89); // RXI2
            }
        }
        // Async frame delivery: when CyOS's radio state machine is idle
        // (state 1) and frames are waiting in the queue, inject the indicator
        // byte directly. On real hardware, the AVR sends frames to the H8S
        // autonomously — this emulates that behavior for frames received via
        // UDP between TX DTC cycles.
        if (asyncRadioCheckCountdown > 0) {
            asyncRadioCheckCountdown--;
        } else if (radio != null && radioSciChannel == 2
                && !sci0Rdrf && !radio.hasData()
                && sci2PendingIndicator < 0
                && radio.hasPendingFrames()) {
            asyncRadioCheckCountdown = ASYNC_RADIO_CHECK_INTERVAL;
            // Read CyOS radio state — only inject if state == 1 (idle)
            int radioObjAddr = (read8(0x4B49FE) << 24) | (read8(0x4B49FF) << 16)
                    | (read8(0x4B4A00) << 8) | read8(0x4B4A01);
            if (radioObjAddr >= 0x400000 && radioObjAddr < 0x600000) {
                int cyState = read8(radioObjAddr + 0x335A);
                if (cyState == 1) {
                    radio.tryAsyncDelivery();
                    // Indicator byte is now in rxQueue — will be delivered
                    // by the RXI2 logic above on the next tick.
                }
            }
        }
    }

    // Legacy setters (delegate to indexed version)
    public void setTimer16_0(H8STimer16 t) { timer16[0] = t; }
    public void setTimer16_1(H8STimer16 t) { timer16[1] = t; }
    public void setTimer16_2(H8STimer16 t) { timer16[2] = t; }
    public void setTimer16_3(H8STimer16 t) { timer16[3] = t; }
    public void setTimer16_4(H8STimer16 t) { timer16[4] = t; }
    public void setTimer16_5(H8STimer16 t) { timer16[5] = t; }

    /** Tick the RTC. Call once per frame to advance real-time clock. */
    public void tickRtc() { rtc.tick(); }

    public int read8(int address) {
        address &= 0xFFFFFF;
        return switch (decodeRegion(address)) {
            case BOOT_ROM -> bootRom.read8(address & 0x7FFF);
            case LCD -> lcd.read8(address & 1);
            case USB -> 0;
            case EXT_RAM -> externalRam.read8(extRamOffset(address));
            case FLASH -> flashRom.read8(flashOffset(address));
            case KEYBOARD -> {
                int kbVal = readKeyboard(address & ~1);
                yield (address & 1) == 0 ? (kbVal >> 8) & 0xFF : kbVal & 0xFF;
            }
            case ON_CHIP -> readOnChip8(address);
            case UNMAPPED -> { logUnmapped("read8", address); yield 0; }
        };
    }

    public int read16(int address) {
        address &= 0xFFFFFF;
        return switch (decodeRegion(address)) {
            case BOOT_ROM -> bootRom.read16(address & 0x7FFF);
            case LCD -> (lcd.read8(0) << 8) | lcd.read8(1);
            case USB -> 0;
            case EXT_RAM -> externalRam.read16(extRamOffset(address));
            case FLASH -> flashRom.read16(flashOffset(address));
            case KEYBOARD -> readKeyboard(address);
            case ON_CHIP -> readOnChip16(address);
            case UNMAPPED -> { logUnmapped("read16", address); yield 0; }
        };
    }

    public int read32(int address) {
        return (read16(address) << 16) | read16(address + 2);
    }

    public void write8(int address, int value) {
        address &= 0xFFFFFF;
        switch (decodeRegion(address)) {
            case BOOT_ROM -> {}
            case LCD -> lcd.write8(address & 1, value);
            case USB -> {}
            case EXT_RAM -> externalRam.write8(extRamOffset(address), value);
            case FLASH -> {
                if (flashRom != null)
                    flashRom.write8(flashOffset(address), value);
            }
            case KEYBOARD -> {}
            case ON_CHIP -> writeOnChip8(address, value);
            case UNMAPPED -> logUnmapped("write8", address);
        }
    }

    public void write16(int address, int value) {
        address &= 0xFFFFFF;
        switch (decodeRegion(address)) {
            case BOOT_ROM -> {}
            case LCD -> { lcd.write8(0, value >> 8); lcd.write8(1, value & 0xFF); }
            case USB -> {}
            case EXT_RAM -> externalRam.write16(extRamOffset(address), value);
            case FLASH -> {
                if (flashRom != null)
                    flashRom.write16(flashOffset(address), value);
            }
            case KEYBOARD -> {}
            case ON_CHIP -> writeOnChip16(address, value);
            case UNMAPPED -> logUnmapped("write16", address);
        }
    }

    public void write32(int address, int value) {
        write16(address, value >>> 16);
        write16(address + 2, value & 0xFFFF);
    }

    // --- DMA Controller (XT only) ---
    private void executeDmaTransfer(int channel) {
        if (!config.hasDma) return;
        int base = channel * 16;
        int srcAddr = (dmaRegs[base] << 24) | (dmaRegs[base + 1] << 16)
                    | (dmaRegs[base + 2] << 8) | dmaRegs[base + 3];
        int dstAddr = (dmaRegs[base + 8] << 24) | (dmaRegs[base + 9] << 16)
                    | (dmaRegs[base + 10] << 8) | dmaRegs[base + 11];
        int count = (dmaRegs[base + 6] << 8) | dmaRegs[base + 7];

        int dmacrH = dmaCtrl[2 + channel * 2];
        boolean mode16 = (dmacrH & 0x80) != 0;

        srcAddr &= 0xFFFFFF;
        dstAddr &= 0xFFFFFF;

        if (count == 0) {
            if (channel == 1) dmaCtrl[7] &= ~0xC0;
            else dmaCtrl[7] &= ~0x30;
            return;
        }
        if (dmaDebugLog < 50) {
            System.err.printf("[DMA] ch%d: src=0x%06X dst=0x%06X count=%d mode16=%b PC=0x%06X%n",
                channel, srcAddr, dstAddr, count, mode16, cpu != null ? cpu.getLastStartPC() : -1);
            dmaDebugLog++;
        }

        if (mode16) {
            for (int i = 0; i < count; i++) {
                int val = read16(srcAddr);
                write16(dstAddr, val);
                srcAddr = (srcAddr + 2) & 0xFFFFFF;
                dstAddr = (dstAddr + 2) & 0xFFFFFF;
            }
        } else {
            for (int i = 0; i < count; i++) {
                int val = read8(srcAddr);
                write8(dstAddr, val);
                srcAddr = (srcAddr + 1) & 0xFFFFFF;
                dstAddr = (dstAddr + 1) & 0xFFFFFF;
            }
        }

        if (channel == 1) dmaCtrl[7] &= ~0xC0;
        else dmaCtrl[7] &= ~0x30;
        dmaRegs[base + 6] = 0;
        dmaRegs[base + 7] = 0;
    }

    // --- Keyboard matrix ---
    private final int[] keyColumns = new int[15];

    public void setKeyState(int column, int bitmask, boolean pressed) {
        if (column < 0 || column >= 15) return;
        if (pressed) keyColumns[column] |= bitmask;
        else keyColumns[column] &= ~bitmask;
        // V1/V2: fire IRQ2 (vector 18) on key press to trigger keyboard scan ISR
        if (pressed && config.type != MachineConfig.MachineType.XT && cpu != null) {
            cpu.requestInterrupt(18);
        }
    }

    private int readKeyboard(int address) {
        int wordOffset = ((address - 0xE00000) & 0xFFFFF) >> 1;
        int data = 0xFFFF;
        for (int i = 0; i < config.keyboardColumns; i++) {
            if ((wordOffset & (1 << i)) == 0) {
                data &= ~keyColumns[i];
            }
        }
        // V2 keyboard quirk from MAME: force bit 1 when column 0 is selected
        if (config.type == MachineConfig.MachineType.V2 && (wordOffset & 1) == 0) {
            data |= 0x0002;
        }
        return data;
    }


    // --- On-chip memory and I/O routing ---

    private static final int SSR_TDRE = 0x80;
    private static final int SSR_RDRF = 0x40;
    private static final int SSR_TEND = 0x04;

    private int routeTimer16Read8(int address) {
        if (address >= 0xFFFEA0 && address <= 0xFFFEAB && timer16[5] != null)
            return timer16[5].read8(address - 0xFFFEA0);
        if (address >= 0xFFFE90 && address <= 0xFFFE9B && timer16[4] != null)
            return timer16[4].read8(address - 0xFFFE90);
        if (address >= 0xFFFE80 && address <= 0xFFFE8F && timer16[3] != null)
            return timer16[3].read8(address - 0xFFFE80);
        if (address >= 0xFFFFD0 && address <= 0xFFFFDF && timer16[0] != null)
            return timer16[0].read8(address - 0xFFFFD0);
        if (address >= 0xFFFFE0 && address <= 0xFFFFEB && timer16[1] != null)
            return timer16[1].read8(address - 0xFFFFE0);
        if (address >= 0xFFFFF0 && address <= 0xFFFFFB && timer16[2] != null)
            return timer16[2].read8(address - 0xFFFFF0);
        return -1;
    }

    private int routeTimer16Read16(int address) {
        if (address >= 0xFFFEA0 && address <= 0xFFFEAB && timer16[5] != null)
            return timer16[5].read16(address - 0xFFFEA0);
        if (address >= 0xFFFE90 && address <= 0xFFFE9B && timer16[4] != null)
            return timer16[4].read16(address - 0xFFFE90);
        if (address >= 0xFFFE80 && address <= 0xFFFE8F && timer16[3] != null)
            return timer16[3].read16(address - 0xFFFE80);
        if (address >= 0xFFFFD0 && address <= 0xFFFFDF && timer16[0] != null)
            return timer16[0].read16(address - 0xFFFFD0);
        if (address >= 0xFFFFE0 && address <= 0xFFFFEB && timer16[1] != null)
            return timer16[1].read16(address - 0xFFFFE0);
        if (address >= 0xFFFFF0 && address <= 0xFFFFFB && timer16[2] != null)
            return timer16[2].read16(address - 0xFFFFF0);
        return -1;
    }

    private boolean routeTimer16Write8(int address, int value) {
        if (address >= 0xFFFEA0 && address <= 0xFFFEAB && timer16[5] != null) {
            timer16[5].write8(address - 0xFFFEA0, value); return true;
        }
        if (address >= 0xFFFE90 && address <= 0xFFFE9B && timer16[4] != null) {
            timer16[4].write8(address - 0xFFFE90, value); return true;
        }
        if (address >= 0xFFFE80 && address <= 0xFFFE8F && timer16[3] != null) {
            timer16[3].write8(address - 0xFFFE80, value); return true;
        }
        if (address >= 0xFFFFD0 && address <= 0xFFFFDF && timer16[0] != null) {
            timer16[0].write8(address - 0xFFFFD0, value); return true;
        }
        if (address >= 0xFFFFE0 && address <= 0xFFFFEB && timer16[1] != null) {
            timer16[1].write8(address - 0xFFFFE0, value); return true;
        }
        if (address >= 0xFFFFF0 && address <= 0xFFFFFB && timer16[2] != null) {
            timer16[2].write8(address - 0xFFFFF0, value); return true;
        }
        return false;
    }

    private boolean routeTimer16Write16(int address, int value) {
        if (address >= 0xFFFEA0 && address <= 0xFFFEAB && timer16[5] != null) {
            timer16[5].write16(address - 0xFFFEA0, value); return true;
        }
        if (address >= 0xFFFE90 && address <= 0xFFFE9B && timer16[4] != null) {
            timer16[4].write16(address - 0xFFFE90, value); return true;
        }
        if (address >= 0xFFFE80 && address <= 0xFFFE8F && timer16[3] != null) {
            timer16[3].write16(address - 0xFFFE80, value); return true;
        }
        if (address >= 0xFFFFD0 && address <= 0xFFFFDF && timer16[0] != null) {
            timer16[0].write16(address - 0xFFFFD0, value); return true;
        }
        if (address >= 0xFFFFE0 && address <= 0xFFFFEB && timer16[1] != null) {
            timer16[1].write16(address - 0xFFFFE0, value); return true;
        }
        if (address >= 0xFFFFF0 && address <= 0xFFFFFB && timer16[2] != null) {
            timer16[2].write16(address - 0xFFFFF0, value); return true;
        }
        return false;
    }

    // --- Timer8 routing ---
    private int readTimer8(int busOff) {
        H8STimer8 ch = (busOff % 2 == 0) ? timer8_0 : timer8_1;
        int regOff = (busOff & ~1);
        return ch != null ? ch.read(regOff) : 0;
    }

    private void writeTimer8(int busOff, int value) {
        H8STimer8 ch = (busOff % 2 == 0) ? timer8_0 : timer8_1;
        int regOff = (busOff & ~1);
        if (ch != null) ch.write(regOff, value);
    }

    private int onChipOffset(int address) {
        return address - config.onChipRamBase;
    }

    private int readOnChip8(int address) {
        // Timer16 channels
        int t16 = routeTimer16Read8(address);
        if (t16 >= 0) return t16;

        // Timer8 Channel 0/1: 0xFFFFB0-0xFFFFB9
        if (address >= 0xFFFFB0 && address <= 0xFFFFB9) {
            return readTimer8(address - 0xFFFFB0);
        }

        if (address == 0xFFFFC0) return tstr;
        if (address == 0xFFFFC1) return 0; // TSYR stub

        if (address == 0xFFFF2E) return ier;
        if (address == 0xFFFF2F) return isr;

        // SCI SSR registers
        // SCI0 SCR (0xFFFF7A) — return our cached value so BSET/BCLR work correctly.
        // Without this, BSET reads stale on-chip RAM (0x00) instead of the real SCR,
        // causing read-modify-write to clobber TE/RE/RIE bits.
        if (address == 0xFFFF7A) return sci0Scr;
        if (address == 0xFFFF8A) return sci2Scr; // SCI2 SCR

        if (address == 0xFFFF7C) { // SCI0 SSR
            int ssr = (sci0Tdre ? SSR_TDRE : 0) | (sci0Tdre ? SSR_TEND : 0);
            if (radio != null && radioSciChannel == 0 && sci0Rdrf) ssr |= SSR_RDRF;
            if (sci0RegLog.size() < 500) {
                int pc = (cpu != null) ? cpu.getLastStartPC() : -1;
                if (pc != sci0SsrLastLogPc) { // deduplicate tight polling loops
                    sci0RegLog.add(String.format("R SSR=0x%02X PC=0x%06X", ssr, pc));
                    sci0SsrLastLogPc = pc;
                }
            }
            return ssr;
        }
        if (address == 0xFFFF84) {
            // SCI1 SSR - V1 needs RDRF when SPI flash has data
            if (spiFlash != null) {
                return SSR_TDRE | SSR_TEND | (sci1Rdrf ? SSR_RDRF : 0);
            }
            return SSR_TDRE | SSR_TEND;
        }
        if (address == 0xFFFF7D && radio != null && radioSciChannel == 0) { // SCI0 RDR
            if (sci0RegLog.size() < 500) {
                int pc = (cpu != null) ? cpu.getLastStartPC() : -1;
                sci0RegLog.add(String.format("R RDR=0x%02X PC=0x%06X", sci0Rdr, pc));
            }
            sci0Rdrf = false;
            return sci0Rdr;
        }
        if (address == 0xFFFF85) {
            // SCI1 RDR - V1 reads SPI flash response
            if (spiFlash != null) {
                sci1Rdrf = false;
                return sci1Rdr;
            }
            return 0;
        }
        if (address == 0xFFFF8D && radio != null && radioSciChannel == 2) { // SCI2 RDR
            if (sci2RegLog.size() < 500) {
                int pc = (cpu != null) ? cpu.getLastStartPC() : -1;
                sci2RegLog.add(String.format("R RDR=0x%02X PC=0x%06X", sci0Rdr, pc));
            }
            sci0Rdrf = false;
            return sci0Rdr;
        }
        if (address == 0xFFFF8C) { // SCI2 SSR
            int ssr = SSR_TEND; // TEND always set for our purposes
            if (sci2Tdre) ssr |= SSR_TDRE;
            if (radio != null && radioSciChannel == 2 && sci0Rdrf) ssr |= SSR_RDRF;
            if (sci2RegLog.size() < 500) {
                int pc = (cpu != null) ? cpu.getLastStartPC() : -1;
                if (pc != sci2SsrLastLogPc) {
                    sci2RegLog.add(String.format("R SSR=0x%02X PC=0x%06X", ssr, pc));
                    sci2SsrLastLogPc = pc;
                }
            }
            return ssr;
        }

        // ICR registers (V1/V2 only, H8S/2245 interrupt controller)
        if (config.type != MachineConfig.MachineType.XT
                && address >= 0xFFFEC0 && address <= 0xFFFEC2) {
            return (icr >> (8 * (address - 0xFFFEC0))) & 0xFF;
        }

        // DMA registers (XT only)
        if (config.hasDma) {
            if (address >= 0xFFFEE0 && address <= 0xFFFEFF) {
                return dmaRegs[address - 0xFFFEE0];
            }
            if (address >= 0xFFFF00 && address <= 0xFFFF07) {
                return dmaCtrl[address - 0xFFFF00];
            }
        }

        // DTC registers (all machines — H8S/2323 has both DMA and DTC)
        if (address >= 0xFFFF30 && address <= 0xFFFF37) {
            return onChipRam.read8(onChipOffset(address));
        }

        // System control
        if (address == 0xFFFF38) return 0; // SBYCR
        if (address == 0xFFFF39) return 0; // SYSCR
        if (address == 0xFFFF3B) return 0; // MDCR

        // Port Input Data Registers (0xFFFF50-0xFFFF5E)
        if (address >= 0xFFFF50 && address <= 0xFFFF5E) {
            // Power/battery status
            if (address == config.powerPortAddr) {
                return config.powerPortValue;
            }
            // Port F input: default pull-up, with RTC SDA conditionally cleared
            // V1: bit 2 = AT45DB041 RDY/BUSY (always ready), bit 0 = RTC SDA
            // XT: bit 6 = RTC SDA
            if (address == 0xFFFF5E) {
                int val = 0xFF;
                if (!rtc.sda_r()) val &= ~config.rtcSdaReadBit;
                return val;
            }
            return onChipRam.read8(onChipOffset(address));
        }

        // Port Data Registers (0xFFFF60-0xFFFF6F)
        if (address >= 0xFFFF60 && address <= 0xFFFF6F) {
            // XT: Port A (0xFFFF69) returns power status
            if (config.type == MachineConfig.MachineType.XT && address == 0xFFFF69) {
                return 0xC0;
            }
            // Port F DR: default pull-up, with RTC SDA conditionally cleared
            if (address == 0xFFFF6E) {
                int val = 0xFF;
                if (!rtc.sda_r()) val &= ~config.rtcSdaReadBit;
                return val;
            }
            return onChipRam.read8(onChipOffset(address));
        }

        // Watchdog stub
        if (address >= 0xFFFFBC && address <= 0xFFFFBF) return 0;

        // ADC registers
        if (address >= 0xFFFF90 && address <= 0xFFFF99) {
            if (address <= 0xFFFF97) {
                if (config.type != MachineConfig.MachineType.XT) {
                    // V1/V2: ADC data registers (channels 0-3, 10-bit left-aligned).
                    // CyOS battery check at 0x21DF96 has TWO paths:
                    //   (1) ch1_avg - ch2_avg > 15  (signed, passes immediately if ch1 > ch2)
                    //   (2) level > 31  (where level = 2*ch2 - ch1 + offset, offset=-341)
                    // Battery struct uses exponential smoothing (7/8 old + 1/8 new) starting
                    // from 0, so averages take ~16 iterations to converge. Path (1) passes
                    // from the 2nd ADC cycle if ch1 > ch2+15.
                    // ch1 = 0x0300 (768), ch2 = 0x0100 (256): diff converges to 512>15
                    // (passes from 1st ADC cycle: avg diff=64>15). Level stays low (31)
                    // but the diff path is sufficient for CyOS battery check to pass.
                    int channel = (address - 0xFFFF90) / 2;
                    int adcValue = (channel == 1) ? 0x0300 : (channel == 2) ? 0x0100 : 0x0100;
                    // H8S ADC left-aligned format: high byte = value >> 2, low byte = (value & 3) << 6
                    return (address % 2 == 0) ? (adcValue >> 2) & 0xFF : (adcValue & 3) << 6;
                }
                return (address % 2 == 0) ? 0xCC : 0x00;
            }
            if (address == 0xFFFF98) return adcsr;
            return adcr;
        }

        // I/O register fallthrough logging
        if (address >= 0xFFFE00 && ioFallthroughLog < 200) {
            int pc = (cpu != null) ? cpu.getLastStartPC() : -1;
            if (pc >= 0x400000 || (config.type != MachineConfig.MachineType.XT && pc >= 0x200000)) {
                int val = onChipRam.read8(onChipOffset(address));
                System.err.printf("[IO-FALL] read8 0x%06X=0x%02X PC=0x%06X%n", address, val, pc);
                ioFallthroughLog++;
            }
        }
        return onChipRam.read8(onChipOffset(address));
    }

    private int readOnChip16(int address) {
        int t16 = routeTimer16Read16(address);
        if (t16 >= 0) return t16;

        if (address >= 0xFFFE00) {
            return (readOnChip8(address) << 8) | readOnChip8(address + 1);
        }
        return onChipRam.read16(onChipOffset(address));
    }

    private void writeOnChip8(int address, int value) {
        if (routeTimer16Write8(address, value)) return;

        // Timer8 Channel 0/1: 0xFFFFB0-0xFFFFB9
        if (address >= 0xFFFFB0 && address <= 0xFFFFB9) {
            writeTimer8(address - 0xFFFFB0, value);
            return;
        }

        // TSTR - Timer Start Register (0xFFFFC0)
        if (address == 0xFFFFC0) {
            tstr = value & 0xFF;
            if (timer16[0] != null) timer16[0].setEnabled((tstr & 0x01) != 0);
            if (timer16[1] != null) timer16[1].setEnabled((tstr & 0x02) != 0);
            if (timer16[2] != null) timer16[2].setEnabled((tstr & 0x04) != 0);
            if (timer16[3] != null) timer16[3].setEnabled((tstr & 0x08) != 0);
            if (timer16[4] != null) timer16[4].setEnabled((tstr & 0x10) != 0);
            if (timer16[5] != null) timer16[5].setEnabled((tstr & 0x20) != 0);
            return;
        }

        if (address == 0xFFFF2E) { ier = value & 0xFF; return; }
        if (address == 0xFFFF2F) { isr &= value; return; }

        // SCI registers
        if (address >= 0xFFFF78 && address <= 0xFFFF8E) {
            int channel, reg;
            if (address >= 0xFFFF88) {
                channel = 2; reg = address - 0xFFFF88;
            } else if (address >= 0xFFFF80) {
                channel = 1; reg = address - 0xFFFF80;
            } else {
                channel = 0; reg = address - 0xFFFF78;
            }
            // Log SCI0 and SCI2 register writes for radio protocol analysis
            if ((channel == 0 || channel == 2) && sci0RegLog.size() < 500) {
                List<String> log = (channel == 0) ? sci0RegLog : sci2RegLog;
                if (log.size() < 500) {
                    String regName = (reg < SCI_REG_NAMES.length) ? SCI_REG_NAMES[reg] : "?"+reg;
                    int pc = (cpu != null) ? cpu.getLastStartPC() : -1;
                    log.add(String.format("W %s=0x%02X PC=0x%06X", regName, value & 0xFF, pc));
                }
            }
            if (reg == 3) { // TDR - Transmit Data Register
                char c = (char)(value & 0x7F);
                sciOutput[channel].append(c >= 0x20 ? c : (c == '\n' ? '\n' : '.'));
                if (channel == 0) {
                    sci0TdrLog.add(value & 0xFF);
                }
                if (channel == 2) {
                    sci2TdrLog.add(value & 0xFF);
                }
                if (channel == radioSciChannel && radio != null) {
                    if (channel == 2) {
                        // SCI2 (XT): UART half-duplex. Feed byte to radio but don't
                        // consume response. Response delivered asynchronously via RXI2.
                        radio.receive(value & 0xFF);
                    } else {
                        // SCI0 (V1/V2): feed byte to radio. Response delivered via RXI0.
                        radio.receive(value & 0xFF);
                    }
                    // Model TDRE — clear on TDR write, restore after transmission delay.
                    // Always schedule TDRE restoration (real HW restores TDRE after byte TX
                    // regardless of TIE). TIE only controls whether TXI interrupt fires.
                    if (channel == 2) {
                        sci2Tdre = false;
                        sci2TxiDelay = SCI2_TXI_DELAY;
                    }
                    if (channel == 0) {
                        sci0Tdre = false;
                        sci0TxiDelay = SCI0_TXI_DELAY;
                    }
                }
                // V1: SCI1 TDR writes go to SPI flash
                if (channel == 1 && spiFlash != null) {
                    sci1Rdr = spiFlash.transfer(value & 0xFF);
                    sci1Rdrf = true;
                }
            }
            if (reg == 2 && channel == 0) { // SCI0 SCR write
                int oldScr = sci0Scr;
                sci0Scr = value & 0xFF;
                // If TIE just enabled and TDRE is already set, schedule first TXI0
                boolean tieEnabled = (sci0Scr & 0x80) != 0;
                boolean tieWasOff = (oldScr & 0x80) == 0;
                if (tieEnabled && sci0Tdre && (tieWasOff || sci0TxiDelay == 0)) {
                    sci0TxiDelay = SCI0_TXI_DELAY;
                    if (!v1DriverInitiatedTx && v1RadioBootstrapped) {
                        System.err.printf("[RADIO-V1] CyOS enabled TIE (CyOS-initiated TX start)%n");
                    }
                }
                // V1: TIE 1→0 = TXI0 state 9 disabled TIE after TX complete.
                // Process accumulated poll/scan data and queue the response.
                boolean tieCleared = (oldScr & 0x80) != 0 && (sci0Scr & 0x80) == 0;
                if (tieCleared && radio != null && config.type == MachineConfig.MachineType.V1) {
                    if (!v1DriverInitiatedTx) {
                        System.err.printf("[RADIO-V1] CyOS-initiated TX complete (not from tickV1Radio)%n");
                    }
                    v1DriverInitiatedTx = false;
                    radio.v1TxComplete();
                }
            }
            if (reg == 2 && channel == 1 && spiFlash != null) { // SCR write for SCI1
                // Detect DTC-driven SPI flash transfer setup
                // CyOS uses DTC with SCI1 for bulk SPI flash reads/writes
                executeSci1Dtc(value);
            }
            if (reg == 2 && channel == 2) { // SCI2 SCR write
                executeSci2Dtc(value & 0xFF); // Check for DTC transfer
                int oldScr = sci2Scr;
                sci2Scr = value & 0xFF;
                // If TIE just enabled and TDRE is already set, schedule first TXI2
                boolean tieEnabled = (sci2Scr & 0x80) != 0;
                boolean tieWasOff = (oldScr & 0x80) == 0;
                if (tieEnabled && sci2Tdre && (tieWasOff || sci2TxiDelay == 0)) {
                    sci2TxiDelay = SCI2_TXI_DELAY;
                }
                // Note: deferred indicator delivery is handled in the DTCERF handler
                // (when TXI2 ISR clears bit 6), not here. The timer callback also
                // clears TIE before the TXI2 ISR runs, so TIE-clear is unreliable.
            }
            onChipRam.write8(onChipOffset(address), value);
            return;
        }

        // ICR registers (V1/V2 only)
        if (config.type != MachineConfig.MachineType.XT
                && address >= 0xFFFEC0 && address <= 0xFFFEC2) {
            int shift = 8 * (address - 0xFFFEC0);
            icr = (icr & ~(0xFF << shift)) | ((value & 0xFF) << shift);
            return;
        }

        // Port DDR registers (0xFFFEB0-0xFFFEBF)
        if (address >= 0xFFFEB0 && address <= 0xFFFEBF) {
            onChipRam.write8(onChipOffset(address), value);
            // Port F DDR (0xFFFEBE) - I2C SDA is controlled via DDR (open-drain)
            // on ALL variants. CyOS toggles SDA by switching the DDR bit between
            // output (1=drive low via DR=0) and input (0=release high via pull-up).
            // V1/V2 use DDR bit 0, XT uses DDR bit 6. SCL uses DR (not DDR).
            if (address == 0xFFFEBE) {
                // SDA: DDR bit set = output mode, DR=0 drives SDA LOW
                //       DDR bit clear = input mode, SDA released HIGH (pull-up)
                boolean sdaHigh = (value & config.rtcSdaWriteBit) == 0;
                rtc.sda_w(sdaHigh);
            }
            return;
        }

        // Port 1 write (0xFFFF60) - bit 3 (TIOCB1) drives speaker
        if (address == 0xFFFF60) {
            int level = (value & 0x08) != 0 ? 1 : 0;
            if (level != speakerLevel) {
                speakerLevel = level;
                if (speakerOutput != null) speakerOutput.setLevel(level);
            }
            onChipRam.write8(onChipOffset(address), value);
            return;
        }

        // Port 3 write (0xFFFF62) - bit 4 controls SPI flash CS
        // Flash CS is only driven when Port 3 DDR bit 4 = 1 (output mode).
        // When DDR bit 4 = 0 (input), pin floats high via pull-up = flash deselected.
        if (address == 0xFFFF62) {
            if (spiFlash != null) {
                int port3ddr = onChipRam.read8(onChipOffset(0xFFFEB2));
                // CS active only when DDR=output(1) AND DR=low(0)
                boolean nowSelected = (port3ddr & 0x10) != 0 && (value & 0x10) == 0;
                spiFlash.cs(nowSelected);
            }
            onChipRam.write8(onChipOffset(address), value);
            return;
        }

        // Port F write (0xFFFF6E) - I2C RTC SCL bit-banging
        // SDA is controlled via DDR (0xFFFEBE) on all variants, not DR.
        if (address == 0xFFFF6E) {
            rtc.scl_w((value & config.rtcSclBit) != 0);
            onChipRam.write8(onChipOffset(address), value);
            return;
        }

        // Port data register writes (0xFFFF60-0xFFFF6F)
        if (address >= 0xFFFF60 && address <= 0xFFFF6F) {
            onChipRam.write8(onChipOffset(address), value);
            return;
        }

        // DMA registers (XT only)
        if (config.hasDma) {
            if (address >= 0xFFFEE0 && address <= 0xFFFEFF) {
                dmaRegs[address - 0xFFFEE0] = value & 0xFF;
                return;
            }
            if (address >= 0xFFFF00 && address <= 0xFFFF07) {
                int idx = address - 0xFFFF00;
                int oldVal = dmaCtrl[idx];
                dmaCtrl[idx] = value & 0xFF;
                if (idx == 7) {
                    boolean ch0Triggered = false;
                    if ((value & 0x10) != 0 && (oldVal & 0x10) == 0) {
                        executeDmaTransfer(0); ch0Triggered = true;
                    }
                    if (!ch0Triggered && (value & 0x20) != 0 && (oldVal & 0x20) == 0) {
                        executeDmaTransfer(0);
                    }
                    boolean ch1Triggered = false;
                    if ((value & 0x40) != 0 && (oldVal & 0x40) == 0) {
                        executeDmaTransfer(1); ch1Triggered = true;
                    }
                    if (!ch1Triggered && (value & 0x80) != 0 && (oldVal & 0x80) == 0) {
                        executeDmaTransfer(1);
                    }
                }
                return;
            }
        }

        // DTCERF (0xFFFF35) — SCI2 DTC enable register.
        // CyOS may write DTCERF (via BSET) after SCR is already configured with
        // RIE/TIE enabled. In real hardware, the DTC fires on the next interrupt
        // (RDRF/TDRE). Our shortcut triggers DTC on register writes, so we must
        // trigger on DTCERF writes too, not just SCR writes.
        if (address == 0xFFFF35 && radio != null && radioSciChannel == 2) {
            int pc = (cpu != null) ? cpu.getLastStartPC() : -1;
            System.err.printf("[SCI2-DTCERF] write 0x%02X PC=0x%06X sci2Scr=0x%02X%n",
                    value & 0xFF, pc, sci2Scr);
            onChipRam.write8(onChipOffset(address), value);
            if ((value & 0xC0) != 0) {
                executeSci2Dtc(sci2Scr);
            }
            // Deferred delivery: when TXI2 ISR clears DTCERF bit 6 (BCLR #6
            // at 0x49BEEC), queue the packet ACK (0x03) and frame indicator.
            // This happens INSIDE the TXI2 ISR (I-flag set), so RXI2 won't
            // fire until after the ISR completes (RTE).
            //
            // The AVR sends two bytes after TX DTC completion:
            //   1. 0x03 — packet received ACK (consumed by state 6 → completion → state 1)
            //   2. 0x32/0xC8 — frame size indicator (consumed by state 1 → RX DTC setup)
            // tickSci2() delivers them one at a time (gated by sci0Rdrf), so CyOS
            // processes 0x03 first in state 6, then the indicator in state 1.
            // We always send the indicator (even for null frames) so the full DTC
            // cycle completes and CyOS recycles its connection buffers. Null frames
            // use destination 0x00000000 (not broadcast) so CyOS rejects them
            // immediately without queuing for processing.
            if (sci2PendingIndicator >= 0 && (value & 0x40) == 0) {
                System.err.printf("[SCI2-DTC] Deferred 0x03+0x%02X delivered (DTCERF cleared at PC=0x%06X)%n",
                        sci2PendingIndicator, pc);
                radio.queueResponse(0x03); // Packet ACK for state 6
                radio.queueResponse(sci2PendingIndicator); // Frame indicator for state 1
                sci2PendingIndicator = -1;
            }
            return;
        }

        // System control, watchdog stubs
        if (address == 0xFFFF38 || address == 0xFFFF39) return;
        if (address >= 0xFFFFBC && address <= 0xFFFFBF) return;

        // ADC registers
        if (address >= 0xFFFF90 && address <= 0xFFFF99) {
            if (address == 0xFFFF98) {
                if ((value & 0x20) != 0) {
                    // ADST set: start conversion, immediately complete (set ADF),
                    // then clear ADST (conversion done). Matches MAME done() behavior.
                    adcsr = (value & 0x5F) | 0x80; // ADF=1, ADST cleared
                    // Fire ADC interrupt (vector 28) if ADIE is enabled
                    if ((value & 0x40) != 0 && cpu != null) {
                        cpu.requestInterrupt(28);
                    }
                } else {
                    // MAME: ADF cleared only if software writes 0 to bit 7
                    adcsr = (value & 0x7F) | (adcsr & value & 0x80);
                }
            } else if (address == 0xFFFF99) {
                adcr = value & 0xFF;
            }
            return;
        }

        onChipRam.write8(onChipOffset(address), value);
    }

    /**
     * Simulate DTC (Data Transfer Controller) for SCI1 SPI flash transfers.
     * V1 CyOS uses DTC to bulk-read/write SPI flash pages via SCI1.
     * DTC register info block at 0xFFFBD0 (set up by CyOS before SCR write):
     *   Byte 0: MRA (0x20=receive from RDR, 0x80=transmit to TDR)
     *   Bytes 1-3: SAR low 3 bytes (source address)
     *   Byte 4: forced to 0x00 by CyOS
     *   Bytes 5-7: DAR low 3 bytes (dest address)
     *   Bytes 8-9: CRA (transfer count, 16-bit)
     */
    private void executeSci1Dtc(int scrValue) {
        // Only trigger when RE (bit 4) and RIE (bit 6) are set, or TE (bit 5) and TIE (bit 7)
        boolean receiveMode = (scrValue & 0x50) == 0x50; // RE + RIE
        boolean transmitMode = (scrValue & 0xA0) == 0xA0; // TE + TIE
        if (!receiveMode && !transmitMode) return;

        // Check DTCER at 0xFFFF34 - bit 1 must be set for SCI1 DTC
        int dtcer = onChipRam.read8(onChipOffset(0xFFFF34));
        if ((dtcer & 0x02) == 0) return;

        // Read DTC register info block at 0xFFFBD0
        int dtcBase = onChipOffset(0xFFFBD0);
        int mra = onChipRam.read8(dtcBase);
        int darHi = onChipRam.read8(dtcBase + 4);
        int darLo = (onChipRam.read8(dtcBase + 5) << 16)
                  | (onChipRam.read8(dtcBase + 6) << 8)
                  | onChipRam.read8(dtcBase + 7);
        int dar = (darHi << 24) | darLo;
        int sarLo = (onChipRam.read8(dtcBase + 1) << 16)
                  | (onChipRam.read8(dtcBase + 2) << 8)
                  | onChipRam.read8(dtcBase + 3);
        int count = (onChipRam.read8(dtcBase + 8) << 8)
                  | onChipRam.read8(dtcBase + 9);

        if (count == 0) return;

        if (spiFlash != null) {
            // V1/V2: DTC transfers talk to the SPI flash (AT45DB041) for bulk page reads.
            // Both V1 and V2 use SPI flash for CFS filesystem (apps, cyos.cfg, etc.)
            if (mra == 0x20 && receiveMode) {
                // Receive mode: read from SPI flash into destination buffer
                for (int i = 0; i < count; i++) {
                    int b = spiFlash.transfer(0xFF);
                    write8(dar + i, b);
                }
            } else if (mra == 0x80 && transmitMode) {
                // Transmit mode: send from source buffer to SPI flash
                for (int i = 0; i < count; i++) {
                    int b = read8(sarLo + i);
                    sci1Rdr = spiFlash.transfer(b);
                }
                sci1Rdrf = true;
            } else {
                return;
            }
        } else {
            // No SPI flash available — return idle bus
            if (mra == 0x20 && receiveMode) {
                for (int i = 0; i < count; i++) {
                    write8(dar + i, 0xFF);
                }
            } else if (mra == 0x80 && transmitMode) {
                sci1Rdr = 0xFF;
                sci1Rdrf = true;
            } else {
                return;
            }
        }

        // Clear DTC count
        onChipRam.write8(dtcBase + 8, 0);
        onChipRam.write8(dtcBase + 9, 0);

        // Clear DTCER bit to disable DTC for this source
        onChipRam.write8(onChipOffset(0xFFFF34), dtcer & ~0x02);

        if (config.type == MachineConfig.MachineType.V1) {
            // V1: Fire SCI1 RXI interrupt (vector 85) for completion.
            dtcCompletionDelay = 5;
        } else {
            // V2: Set the completion flag directly. Firing vector 85 on V2 causes
            // corrupted task pointers (0xCA0000) in the RTOS scheduler — the V2
            // interrupt handler or vector table setup is incompatible.
            // The ISR at 0x1085E0 (via 0xFFECA0 trampoline) does:
            //   1. Disable SCI1 (SCR = 0), re-enable basic mode (SCR = 0x30)
            //   2. Clear RDRF and ORER in SCI1 SSR
            //   3. Write 0x01 to completion flag at 0x20023C
            onChipRam.write8(onChipOffset(0xFFFF82), 0x00);
            onChipRam.write8(onChipOffset(0xFFFF82), 0x30);
            int ssr = onChipRam.read8(onChipOffset(0xFFFF84));
            onChipRam.write8(onChipOffset(0xFFFF84), ssr & ~0x60);
            write8(0x20023C, 0x01);
        }
    }

    /**
     * Simulate DTC for SCI2 radio bulk transfers (XT).
     * DTCERF (0xFFFF35) bit 7 = RXI2, bit 6 = TXI2.
     * DTC register info blocks: RX at 0xFFFBE8, TX at 0xFFFBF4.
     *
     * CyOS radio protocol (from disassembly at 0x49B9B4):
     *   1. Polled TX: 2-byte command header (30/CF + param)
     *   2. TX DTC: 51 or 201 bytes of packet data from RAM to TDR
     *   3. TXI2 ISR (vector 90, handler at 0x49BEE4): transitions state 2 → 1
     *   4. Radio indicator (0x32/0xC8) via RXI2 → state 1 → frame receive setup
     */
    private void executeSci2Dtc(int scrValue) {
        boolean receiveMode = (scrValue & 0x50) == 0x50; // RE + RIE
        boolean transmitMode = (scrValue & 0xA0) == 0xA0; // TE + TIE
        if (!receiveMode && !transmitMode) return;

        int dtcerf = onChipRam.read8(onChipOffset(0xFFFF35));
        boolean rxiDtc = (dtcerf & 0x80) != 0; // bit 7 = RXI2
        boolean txiDtc = (dtcerf & 0x40) != 0; // bit 6 = TXI2
        if (!rxiDtc && !txiDtc) {
            return;
        }

        // Read DTC register info block
        int dtcBase;
        if (txiDtc && transmitMode) {
            dtcBase = onChipOffset(0xFFFBF4); // TX block
        } else if (rxiDtc && receiveMode) {
            dtcBase = onChipOffset(0xFFFBE8); // RX block
        } else {
            System.err.printf("[SCI2-DTC] mode mismatch: rx=%b tx=%b rxiDtc=%b txiDtc=%b%n",
                    receiveMode, transmitMode, rxiDtc, txiDtc);
            return;
        }

        int mra = onChipRam.read8(dtcBase);
        int sarLo = (onChipRam.read8(dtcBase + 1) << 16)
                  | (onChipRam.read8(dtcBase + 2) << 8)
                  | onChipRam.read8(dtcBase + 3);
        int darHi = onChipRam.read8(dtcBase + 4);
        int darLo = (onChipRam.read8(dtcBase + 5) << 16)
                  | (onChipRam.read8(dtcBase + 6) << 8)
                  | onChipRam.read8(dtcBase + 7);
        int dar = (darHi << 24) | darLo;
        int count = (onChipRam.read8(dtcBase + 8) << 8)
                  | onChipRam.read8(dtcBase + 9);

        if (count == 0) return;

        if (radio != null) {
            if (txiDtc && transmitMode) {
                // TX DTC: read packet bytes from RAM buffer and transmit over network.
                // These are raw radio packet data, NOT command bytes — don't feed to
                // the command parser. handleTransmit sends the data via UDP/SDR.
                byte[] txData = new byte[count];
                for (int i = 0; i < count; i++) {
                    txData[i] = (byte) read8(sarLo + i);
                }
                radio.handleTransmit(txData);
                // Short TX DTC frames (≤9 bytes, e.g. 4-byte init/channel
                // commands like 01 06 19 00) are AVR commands, not radio
                // packets. Skip completeTxDtc() for these — they don't need
                // frame exchange, and calling completeTxDtc() would reset
                // rxFrameReady (losing a pending frame) and block 15ms
                // waiting for UDP frames that will never come.
                if (count <= 9) {
                    sci2PendingIndicator = 0x32; // Null indicator
                } else {
                    // Defer indicator delivery: store the 0x32/0xC8 indicator for
                    // later. The TXI2 ISR must run first (transitions state 2→1)
                    // before the indicator can be processed by RXI2 in state 1.
                    // Delivery happens in the SCR write handler when TIE is cleared.
                    sci2PendingIndicator = radio.completeTxDtc();
                }
                // Fire TXI2 completion interrupt so CyOS's TXI2 ISR runs
                // the TX DTC completion handler (0x49BEE4: clears TIE, calls
                // TX complete handler at 0x49C5F0 which transitions state 2→1).
                if (cpu != null) {
                    cpu.requestInterrupt(90); // TXI2
                }
                // Log TX DTC with hex + ASCII
                StringBuilder txHex = new StringBuilder();
                StringBuilder txAscii = new StringBuilder();
                for (int i = 0; i < Math.min(count, 52); i++) {
                    int b = txData[i] & 0xFF;
                    txHex.append(String.format("%02X ", b));
                    txAscii.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
                }
                System.err.printf("[SCI2-DTC] TX %d bytes MRA=0x%02X from 0x%06X ind=0x%02X: %s |%s|%n",
                        count, mra, sarLo, sci2PendingIndicator,
                        txHex.toString().trim(), txAscii);
            } else if (rxiDtc && receiveMode) {
                // RX DTC: CyOS sets DTCERF bit 7 after receiving 0x32/0xC8 frame
                // indicator, expecting hardware DTC to bulk-transfer N bytes from
                // SCI2 RDR to a RAM buffer. We execute this immediately.
                //
                // MRA determines address mode:
                //   0x20: dest increment (real data DTC, state 4)
                //   0x00: dest fixed (discard DTC, state 5 — channel mismatch)
                // For discard mode, write all bytes to dar+0 (no increment) to
                // avoid corrupting adjacent CyOS memory.
                boolean destIncrement = (mra & 0x30) != 0; // DM bits 5:4
                radio.prepareRxFrame(count);
                StringBuilder rxHex = new StringBuilder();
                StringBuilder rxAscii = new StringBuilder();
                for (int i = 0; i < count; i++) {
                    int b = radio.hasData() ? radio.read() : 0xFF;
                    write8(destIncrement ? (dar + i) : dar, b);
                    if (i < 52) {
                        rxHex.append(String.format("%02X ", b));
                        rxAscii.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
                    }
                }
                // Queue a dummy byte so tickSci2() generates the RXI2 completion
                // interrupt. On real hardware, when DTC count reaches 0, the
                // normal RXI2 ISR fires (DISEL=0). CyOS ISR in state 4/5 calls
                // the completion handler regardless of byte value.
                radio.queueResponse(0xFF);
                // Log CyOS radio state for diagnostics
                int radioObj = (read8(0x4B49FE) << 24) | (read8(0x4B49FF) << 16)
                        | (read8(0x4B4A00) << 8) | read8(0x4B4A01);
                int cyState = -1, chA = -1, chB = -1;
                if (radioObj >= 0x400000 && radioObj < 0x600000) {
                    cyState = read8(radioObj + 0x335A);
                    chA = read8(radioObj + 0x335B);
                    chB = read8(radioObj + 0x335C);
                }
                System.err.printf("[SCI2-DTC] RX %d bytes MRA=0x%02X → 0x%06X (state=%d ch=%d/%d): %s |%s|%n",
                        count, mra, dar, cyState, chA, chB,
                        rxHex.toString().trim(), rxAscii);
            }
        }

        // Clear DTC count and DTCERF bits
        onChipRam.write8(dtcBase + 8, 0);
        onChipRam.write8(dtcBase + 9, 0);
        int clearedBits = txiDtc ? 0x40 : 0x80;
        onChipRam.write8(onChipOffset(0xFFFF35), dtcerf & ~clearedBits);
    }

    private void writeOnChip16(int address, int value) {
        if (routeTimer16Write16(address, value)) return;

        if (address >= 0xFFFE00) {
            writeOnChip8(address, (value >> 8) & 0xFF);
            writeOnChip8(address + 1, value & 0xFF);
            return;
        }
        onChipRam.write16(onChipOffset(address), value);
    }

    // --- Address decoding ---

    private enum Region {
        BOOT_ROM, LCD, USB, EXT_RAM, FLASH, KEYBOARD, ON_CHIP, UNMAPPED
    }

    private Region decodeRegion(int address) {
        // Boot ROM region
        if (address <= config.bootRomMirrorEnd) return Region.BOOT_ROM;

        // LCD (including mirrors - V2 mirrors across 0x600000-0x7FFFFF)
        if (address >= config.lcdBase && address <= config.lcdRegionEnd) return Region.LCD;

        // USB (XT only)
        if (config.type == MachineConfig.MachineType.XT
                && address >= 0x200000 && address <= 0x200003) return Region.USB;

        // External RAM (including mirrors - V2 mirrors 256KB across 0x200000-0x3FFFFF)
        if (address >= config.extRamBase
                && address <= config.extRamRegionEnd) return Region.EXT_RAM;

        // Memory-mapped flash (V2 and XT, including mirrors)
        if (config.flashBase >= 0 && flashRom != null
                && address >= config.flashBase
                && address <= config.flashRegionEnd) return Region.FLASH;

        // Keyboard matrix
        if (address >= 0xE00000 && address <= 0xEFFFFF) return Region.KEYBOARD;

        // On-chip RAM & I/O registers
        if (address >= config.onChipRamBase && address <= 0xFFFFFF) return Region.ON_CHIP;

        return Region.UNMAPPED;
    }

    /** Map a mirrored external RAM address to a physical offset within the RAM array. */
    private int extRamOffset(int address) {
        return (address - config.extRamBase) % config.externalRamSize;
    }

    /** Map a mirrored flash address to a physical offset within the flash ROM array. */
    private int flashOffset(int address) {
        return (address - config.flashBase) % config.flashRomSize;
    }

    public String drainSerialOutput(int channel) {
        if (channel < 0 || channel > 2) return "";
        String s = sciOutput[channel].toString();
        sciOutput[channel].setLength(0);
        return s;
    }

    private int unmappedLogCount = 0;
    private int ioFallthroughLog = 0;
    private int dmaDebugLog = 0;
    private void logUnmapped(String op, int address) {
        if (unmappedLogCount < 200) {
            int pc = (cpu != null) ? cpu.getPC() : -1;
            System.err.printf("Bus: unmapped %s at 0x%06X (PC=0x%06X)%n", op, address, pc);
            unmappedLogCount++;
            if (unmappedLogCount == 200) {
                System.err.println("Bus: suppressing further unmapped warnings (200 reached)");
            }
        }
    }
}
