package com.github.daberkow;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

/**
 * Cybiko emulator - main orchestrator.
 * Supports V1 (Classic), V2, and XT (Xtreme) hardware variants.
 *
 * Usage: CybikoEmulator [--machine v1|v2|xt] <boot_rom.bin> [flash_rom.bin] [options]
 */
public class CybikoEmulator {
    private final MachineConfig config;
    private final Memory bootRom;
    private final Memory externalRam;
    private final Memory flashRom;     // null for V1 (uses SPI flash)
    private final Memory onChipRam;
    private final AddressBus bus;
    private final HD66421Lcd lcd;
    private final H8SCpu cpu;
    private final H8STimer8 timer8_0;
    private final H8STimer8 timer8_1;
    private final H8STimer16[] timer16;

    private AT45DB041Flash spiFlash;   // V1/V2
    private RadioCoProcessor radio;
    private FrameBufferRenderer renderer;
    private SpeakerOutput speaker;
    private boolean running = false;
    private boolean headless = false;
    private Path nvramPath;
    private Thread emulatorThread;

    public interface StateListener {
        void onStateChanged(boolean running);
    }
    private StateListener stateListener;
    public void setStateListener(StateListener listener) { this.stateListener = listener; }
    public boolean isRunning() { return running; }

    // V2: RTOS set_task_state address for auto-resolving service startup waits.
    // V2 CyOS starts RF driver, RF monitor, messenger, and receiver services during
    // boot. Each uses set_task_state to wait for the service to reach a ready state.
    // Without RF hardware emulation, these waits block forever. Auto-resolving writes
    // the expected state to the object before the check, letting boot continue.
    //
    // Always-resolve (not once-per-pair): services like 0x208B84 are polled ~2.3M
    // times during normal boot. The RF task object (0x202CB2) is blacklisted because
    // resolving it during boot triggers RF hardware init that fails and permanently
    // blocks boot. The RF object address is ROM-specific — 0x202CB2 is for CyOS v1358.
    private static final int V2_SET_TASK_STATE_ADDR = 0x1073AC;
    private static final int V2_STATE_OFFSET = 0x14;
    private static final int V2_RF_OBJ = 0x202CB2;  // RF task object (CyOS v1358)
    private final boolean v2ServiceStub;

    private static final long NANOS_PER_FRAME = 1_000_000_000L / 60;
    private static final int AUTOSAVE_INTERVAL_FRAMES = 60 * 300; // 5 minutes at 60fps

    public CybikoEmulator(MachineConfig config) {
        this.config = config;

        bootRom = new Memory(config.bootRomSize, false);
        externalRam = new Memory(config.externalRamSize, true);
        flashRom = config.flashRomSize > 0 ? new Memory(config.flashRomSize, false) : null;
        onChipRam = new Memory(config.onChipRamSize, true);

        lcd = new HD66421Lcd();
        bus = new AddressBus(config);

        bus.setBootRom(bootRom);
        bus.setExternalRam(externalRam);
        if (flashRom != null) bus.setFlashRom(flashRom);
        bus.setOnChipRam(onChipRam);
        bus.setLcd(lcd);

        cpu = new H8SCpu(bus);
        bus.setCpu(cpu);

        radio = new RadioCoProcessor();
        bus.setRadio(radio);

        // Create timer peripherals
        timer8_0 = new H8STimer8(0, cpu);
        timer8_1 = new H8STimer8(1, cpu);
        bus.setTimer8_0(timer8_0);
        bus.setTimer8_1(timer8_1);

        // Timer16 channels - only create as many as this machine has
        // Ch0-2 are common to all variants (regs at 0xFFFFD0, 0xFFFFE0, 0xFFFFF0)
        // Ch3-5 are XT only (regs at 0xFFFE80, 0xFFFE90, 0xFFFEA0)
        timer16 = new H8STimer16[config.timer16Channels];
        timer16[0] = new H8STimer16(0, 4, 32, cpu);
        timer16[1] = new H8STimer16(1, 2, 40, cpu);
        timer16[2] = new H8STimer16(2, 2, 44, cpu);
        bus.setTimer16(0, timer16[0]);
        bus.setTimer16(1, timer16[1]);
        bus.setTimer16(2, timer16[2]);
        if (config.timer16Channels > 3) {
            timer16[3] = new H8STimer16(3, 4, 48, cpu);
            timer16[4] = new H8STimer16(4, 2, 56, cpu);
            timer16[5] = new H8STimer16(5, 2, 60, cpu);
            bus.setTimer16(3, timer16[3]);
            bus.setTimer16(4, timer16[4]);
            bus.setTimer16(5, timer16[5]);
        }

        // V2: enable service startup stub to auto-resolve service waits during boot.
        // V2 CyOS starts several services that wait on each other; without this,
        // boot stalls before reaching CyOS init.
        v2ServiceStub = (config.type == MachineConfig.MachineType.V2);
    }

    /** Convenience constructor for XT (backward compatibility). */
    public CybikoEmulator() {
        this(MachineConfig.forType(MachineConfig.MachineType.XT));
    }

    public void loadBootRom(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        bootRom.load(data, 0);
        System.out.printf("Loaded boot ROM: %s (%d bytes)%n", path, data.length);
    }

    public void loadFlashRom(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        if (config.flashRomSize > 0 && flashRom != null) {
            // V2/XT: load into memory-mapped flash
            flashRom.load(data, 0);
            System.out.printf("Loaded flash ROM: %s (%d bytes)%n", path, data.length);
        } else if (config.hasSpiFlash) {
            // V1: no memory-mapped flash, load into SPI flash instead
            spiFlash = new AT45DB041Flash(data);
            bus.setSpiFlash(spiFlash);
            System.out.printf("Loaded SPI flash: %s (%d bytes)%n", path, data.length);
        }
    }

    public void loadSpiFlash(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        spiFlash = new AT45DB041Flash(data);
        bus.setSpiFlash(spiFlash);
        System.out.printf("Loaded SPI dataflash: %s (%d bytes)%n", path, data.length);
    }

    public void setRenderer(FrameBufferRenderer renderer) {
        this.renderer = renderer;
    }

    public void setSpeaker(SpeakerOutput speaker) {
        this.speaker = speaker;
        bus.setSpeakerOutput(speaker);
        // Wire Timer16 ch1 TIOCB1 output compare to speaker (PWM audio)
        timer16[1].setOutputBCallback(level -> speaker.setLevel(level));
    }

    public void setHeadless(boolean headless) {
        this.headless = headless;
    }

    public MachineConfig getConfig() { return config; }
    public H8SCpu getCpu() { return cpu; }
    public AddressBus getBus() { return bus; }
    public HD66421Lcd getLcd() { return lcd; }
    public Memory getExternalRam() { return externalRam; }
    public H8STimer8 getTimer8(int ch) { return ch == 0 ? timer8_0 : timer8_1; }
    public H8STimer16 getTimer16(int ch) { return ch < timer16.length ? timer16[ch] : null; }
    public int getTimer16Count() { return timer16.length; }
    public RadioCoProcessor getRadio() { return radio; }

    /** Initialize and start running (non-blocking). */
    public void start() {
        if (running) return;
        cpu.reset();

        System.out.println("=== Initial state (" + config.name + ") ===");
        cpu.dumpRegisters();
        System.out.printf("Reset vector: 0x%08X%n", bus.read32(0x000000));
        System.out.println();

        running = true;
        emulatorThread = new Thread(this::run, "emulator");
        emulatorThread.setDaemon(true);
        emulatorThread.start();
        if (stateListener != null) stateListener.onStateChanged(true);
    }

    private void run() {
        int frameCounter = 0;
        long totalSteps = 0;
        long maxSteps = 5_000_000_000L;
        int cyclesPerFrame = config.cyclesPerFrame;
        int numTimer16 = timer16.length;

        System.err.println("=== Starting execution ===");
        long frameDeadline = System.nanoTime() + NANOS_PER_FRAME;
        long frameTotalNanos = 0;
        int frameTimingSamples = 0;

        while (running && totalSteps < maxSteps) {
            long remaining = maxSteps - totalSteps;
            int cycleBudget = (remaining > cyclesPerFrame) ? cyclesPerFrame : (int) remaining;

            // Cache which timers are active per frame
            boolean t8_0_run = timer8_0.isRunning();
            boolean t8_1_run = timer8_1.isRunning();
            // Cache timer16 running state into local booleans for the inner loop
            boolean t16_0_run = timer16[0].isRunning();
            boolean t16_1_run = timer16[1].isRunning();
            boolean t16_2_run = timer16[2].isRunning();
            boolean t16_3_run = numTimer16 > 3 && timer16[3].isRunning();
            boolean t16_4_run = numTimer16 > 4 && timer16[4].isRunning();
            boolean t16_5_run = numTimer16 > 5 && timer16[5].isRunning();

            // Begin audio frame for transition tracking
            if (speaker != null) speaker.beginFrame();

            long frameStartNanos = System.nanoTime();
            for (int i = 0; i < cycleBudget; i++) {
                if (speaker != null) speaker.setFrameCycle(i);
                if (t8_0_run) timer8_0.tick();
                if (t8_1_run) timer8_1.tick();
                if (t16_0_run) timer16[0].tick();
                if (t16_1_run) timer16[1].tick();
                if (t16_2_run) timer16[2].tick();
                if (t16_3_run) timer16[3].tick();
                if (t16_4_run) timer16[4].tick();
                if (t16_5_run) timer16[5].tick();

                // V2: auto-resolve set_task_state for services that wait on
                // unimplemented hardware (RF, etc). Phased: resolve base services
                // always, all non-RF services after frame 1200.
                // RF (0x202CB2) is only resolved when radio is initialized with
                // a transport (i.e., user passed --radio); otherwise it stays
                // blacklisted to avoid failed HW init that permanently blocks boot.
                if (v2ServiceStub && cpu.getPC() == V2_SET_TASK_STATE_ADDR) {
                    int obj = cpu.getER(0);
                    int expectedByte = cpu.getER(1) & 0xFF;
                    int currentByte = bus.read8(obj + V2_STATE_OFFSET);
                    boolean isBase = (obj == 0x208B84 || obj == 0x203512);
                    boolean isRf = (obj == V2_RF_OBJ);
                    boolean rfAllowed = isRf && radio != null && radio.isInitialized()
                            && radio.getTransport() != null && frameCounter >= 1200;
                    boolean nonRfAllowed = !isRf && frameCounter >= 1200;
                    boolean isLate = rfAllowed || nonRfAllowed;
                    // Debug: log only actual state changes
                    if (currentByte != expectedByte) {
                        boolean resolved = isBase || isLate;
                        System.err.printf("[V2-SVC] frame=%d obj=0x%06X 0x%02X->0x%02X %s%n",
                            frameCounter, obj, currentByte, expectedByte,
                            resolved ? "RESOLVED" : (isRf ? "RF-BLOCKED" : "BLOCKED"));
                    }
                    if ((isBase || isLate) && currentByte != expectedByte) {
                        bus.write8(obj + V2_STATE_OFFSET, expectedByte);
                        cpu.setCCR(cpu.getCCR() & ~0x80);
                    }
                }

                cpu.step();
                bus.tickDtcCompletion();
                bus.tickSci2();
                totalSteps++;

                if (totalSteps >= maxSteps) break;
            }

            // Render frame
            if (renderer != null) {
                renderer.render(lcd.getFrameBuffer(), HD66421Lcd.WIDTH, HD66421Lcd.HEIGHT);
            }

            // Generate audio samples for this frame
            if (speaker != null) {
                speaker.generateSamples(cyclesPerFrame);
            }

            // Tick the RTC once per frame
            bus.tickRtc();

            // Tick radio co-processor (heartbeat beacons)
            if (radio != null) radio.tick();

            // Print serial output from boot loader / CyOS
            for (int sci = 0; sci < 3; sci++) {
                String s = bus.drainSerialOutput(sci);
                if (!s.isEmpty()) {
                    System.err.printf("[SCI%d] %s%n", sci, s);
                }
            }

            frameCounter++;

            // RAM dump feature: -Dcybiko.ramdump=/path writes external RAM at frame 300
            String ramdumpPath = System.getProperty("cybiko.ramdump");
            if (ramdumpPath != null && frameCounter == 300) {
                try {
                    Files.write(Path.of(ramdumpPath), externalRam.getRawData());
                    System.err.printf("[RAMDUMP] Wrote %d bytes to %s at frame %d%n",
                        externalRam.getRawData().length, ramdumpPath, frameCounter);
                } catch (IOException e) {
                    System.err.printf("[RAMDUMP] Failed: %s%n", e.getMessage());
                }
            }

            // Auto-save NVRAM every 5 minutes
            if (nvramPath != null && frameCounter % AUTOSAVE_INTERVAL_FRAMES == 0) {
                saveNvram();
            }

            // Track frame work time
            frameTotalNanos += System.nanoTime() - frameStartNanos;
            frameTimingSamples++;

            // Periodic status (every ~1 second at 60fps)
            if (frameCounter % 60 == 0) {
                CRC32 crc = new CRC32();
                crc.update(lcd.getVram());
                long vramHash = crc.getValue();
                double avgFrameMs = (frameTimingSamples > 0) ? (frameTotalNanos / (double) frameTimingSamples) / 1_000_000.0 : 0;
                double usagePct = (avgFrameMs / 16.67) * 100;
                System.err.printf("[STATUS] frame=%d steps=%d PC=0x%06X halted=%b vram=%08X frame=%.1fms(%.0f%%) t8_0[tcr=%02X irqs=%d] t8_1[tcr=%02X irqs=%d]%n",
                    frameCounter, totalSteps, cpu.getPC(), cpu.isHalted(), vramHash,
                    avgFrameMs, usagePct,
                    timer8_0.getTcr(), timer8_0.getInterruptCount(),
                    timer8_1.getTcr(), timer8_1.getInterruptCount());
                frameTotalNanos = 0;
                frameTimingSamples = 0;

                if (!bus.getSci0TdrLog().isEmpty()) {
                    StringBuilder hex = new StringBuilder("[SCI0-TX] ");
                    for (int b : bus.getSci0TdrLog()) hex.append(String.format("%02X ", b));
                    System.err.println(hex);
                    bus.getSci0TdrLog().clear();
                }
                if (!bus.getSci0RegLog().isEmpty()) {
                    System.err.println("[SCI0-REG] " + String.join(" | ", bus.getSci0RegLog()));
                    bus.getSci0RegLog().clear();
                }
                if (!bus.getSci2TdrLog().isEmpty()) {
                    StringBuilder hex = new StringBuilder("[SCI2-TX] ");
                    for (int b : bus.getSci2TdrLog()) hex.append(String.format("%02X ", b));
                    System.err.println(hex);
                    bus.getSci2TdrLog().clear();
                }
                if (!bus.getSci2RegLog().isEmpty()) {
                    System.err.println("[SCI2-REG] " + String.join(" | ", bus.getSci2RegLog()));
                    bus.getSci2RegLog().clear();
                }
            }

            // Precise frame rate limiting (if we have a display)
            if (!headless && renderer != null) {
                long now = System.nanoTime();
                long sleepNanos = frameDeadline - now;
                if (sleepNanos > 1_000_000) {
                    try { Thread.sleep(sleepNanos / 1_000_000, (int)(sleepNanos % 1_000_000)); } catch (InterruptedException e) { break; }
                }
                frameDeadline += NANOS_PER_FRAME;
                if (frameDeadline < now - NANOS_PER_FRAME * 2) {
                    frameDeadline = now + NANOS_PER_FRAME;
                }
            }
        }

        if (speaker != null) speaker.close();
        cpu.dumpProfile();
        System.out.println("\n=== Execution stopped ===");
        System.out.printf("Total steps: %d, frames: %d%n", totalSteps, frameCounter);
        if (cpu.isHalted()) System.out.println("Reason: CPU halted");
        else if (!running) System.out.println("Reason: Stopped by user");
        cpu.dumpRegisters();
        if (stateListener != null) {
            javax.swing.SwingUtilities.invokeLater(() -> stateListener.onStateChanged(false));
        }
    }

    public void stop() {
        running = false;
    }

    /** Save external RAM to the NVRAM file. */
    public void saveNvram() {
        if (nvramPath == null) return;
        try {
            byte[] data = externalRam.getRawData();
            Files.write(nvramPath, data);
            System.out.printf("Saved NVRAM: %s (%d bytes)%n", nvramPath, data.length);
        } catch (IOException e) {
            System.err.println("Error saving NVRAM: " + e.getMessage());
        }
    }

    public void setNvramPath(Path path) { this.nvramPath = path; }
    public Path getNvramPath() { return nvramPath; }
    public Thread getEmulatorThread() { return emulatorThread; }

    // --- Main entry point ---
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: cybiko-java [--machine v1|v2|xt] <boot_rom.bin> [flash_rom.bin] [dataflash.bin] [options]");
            System.out.println();
            System.out.println("  --machine <type>  - Machine type: v1 (Classic), v2, xt (Xtreme, default)");
            System.out.println("  boot_rom.bin      - 32KB boot ROM");
            System.out.println("  flash_rom.bin     - Flash ROM (memory-mapped for V2/XT, SPI for V1)");
            System.out.println("  dataflash.bin     - SPI dataflash (V2 only, 3rd positional arg or --dataflash)");
            System.out.println("  --dataflash <file> - SPI dataflash file (AT45DB041, V2 only)");
            System.out.println("  --headless        - Run without GUI window");
            System.out.println("  --trace           - Enable instruction tracing");
            System.out.println("  --nvram <file>    - Load/save NVRAM (persistent RAM with CFS filesystem)");
            System.out.println("  --app <file>      - Add .app to NVRAM before booting");
            System.out.println("  --list-apps       - List apps in NVRAM and exit");
            System.out.println("  --mute            - Disable audio output");
            System.out.println("  --remote-display <port> - Enable remote display TCP server on port");
            System.out.println("  --radio <lan|sdr>  - Enable radio (lan=UDP multicast, sdr=TCP bridge)");
            System.out.println("  --radio-id <n>     - Set radio device ID (default: random)");
            System.out.println("  --sdr-host <ip>    - SDR bridge host (default: localhost)");
            System.out.println("  --sdr-port <port>  - SDR bridge port (default: 19201)");
            System.exit(1);
        }

        boolean headless = false;
        boolean trace = false;
        boolean mute = false;
        boolean listApps = false;
        int remotePort = 0;
        String radioMode = null;
        int radioDeviceId = 0;
        String sdrHost = "localhost";
        int sdrPort = 19201;
        MachineConfig.MachineType machineType = MachineConfig.MachineType.XT;

        // Parse arguments
        String bootRomPath = null;
        String flashRomPath = null;
        String spiFlashPath = null;
        String nvramFile = null;
        java.util.List<String> appPaths = new java.util.ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--headless" -> headless = true;
                case "--trace" -> trace = true;
                case "--no-trace" -> trace = false;
                case "--mute" -> mute = true;
                case "--list-apps" -> listApps = true;
                case "--machine" -> {
                    if (i + 1 < args.length) {
                        String mt = args[++i].toLowerCase();
                        machineType = switch (mt) {
                            case "v1" -> MachineConfig.MachineType.V1;
                            case "v2" -> MachineConfig.MachineType.V2;
                            case "xt", "xtreme" -> MachineConfig.MachineType.XT;
                            default -> {
                                System.err.println("Unknown machine type: " + mt + " (use v1, v2, or xt)");
                                yield MachineConfig.MachineType.XT;
                            }
                        };
                    }
                }
                case "--nvram" -> { if (i + 1 < args.length) nvramFile = args[++i]; }
                case "--app" -> { if (i + 1 < args.length) appPaths.add(args[++i]); }
                case "--dataflash" -> { if (i + 1 < args.length) spiFlashPath = args[++i]; }
                case "--remote-display" -> { if (i + 1 < args.length) remotePort = Integer.parseInt(args[++i]); }
                case "--radio" -> { if (i + 1 < args.length) radioMode = args[++i].toLowerCase(); }
                case "--radio-id" -> { if (i + 1 < args.length) radioDeviceId = Integer.parseInt(args[++i]); }
                case "--sdr-host" -> { if (i + 1 < args.length) sdrHost = args[++i]; }
                case "--sdr-port" -> { if (i + 1 < args.length) sdrPort = Integer.parseInt(args[++i]); }
                default -> {
                    if (bootRomPath == null) bootRomPath = args[i];
                    else if (flashRomPath == null) flashRomPath = args[i];
                    else if (spiFlashPath == null) spiFlashPath = args[i];
                }
            }
        }

        MachineConfig config = MachineConfig.forType(machineType);
        CybikoEmulator emu = new CybikoEmulator(config);

        try {
            emu.loadBootRom(Path.of(bootRomPath));
            if (flashRomPath != null) {
                emu.loadFlashRom(Path.of(flashRomPath));
            }
            if (spiFlashPath != null && config.hasSpiFlash) {
                emu.loadSpiFlash(Path.of(spiFlashPath));
            }

            // NVRAM handling (CFS filesystem in external RAM)
            if (nvramFile != null) {
                Path nvPath = Path.of(nvramFile);
                emu.nvramPath = nvPath;
                CfsImage cfs;

                if (Files.exists(nvPath)) {
                    byte[] nvData = Files.readAllBytes(nvPath);
                    if (CfsImage.isCfsImage(nvData)) {
                        cfs = new CfsImage(nvData);
                        System.out.printf("Loaded NVRAM: %s (%d bytes, CFS image)%n",
                            nvramFile, nvData.length);
                    } else {
                        System.err.printf("Warning: %s is not a CFS image, creating fresh%n", nvramFile);
                        cfs = new CfsImage();
                    }
                } else {
                    cfs = new CfsImage();
                    System.out.printf("Created new NVRAM: %s%n", nvramFile);
                }

                for (String appPath : appPaths) {
                    Path ap = Path.of(appPath);
                    byte[] appData = Files.readAllBytes(ap);
                    String appName = ap.getFileName().toString();
                    if (CfsImage.isCfsImage(appData)) {
                        cfs = new CfsImage(appData);
                        System.out.printf("Loaded CFS image: %s%n", appPath);
                    } else {
                        cfs.addFile(appName, appData);
                    }
                }

                if (listApps) {
                    var files = cfs.listFiles();
                    System.out.println("=== Apps in NVRAM ===");
                    if (files.isEmpty()) {
                        System.out.println("  (none)");
                    } else {
                        for (String f : files) System.out.println("  " + f);
                    }
                    emu.getExternalRam().load(cfs.getImageData(), 0);
                    emu.saveNvram();
                    System.exit(0);
                }

                emu.getExternalRam().load(cfs.getImageData(), 0);
            } else if (!appPaths.isEmpty()) {
                CfsImage cfs = new CfsImage();
                for (String appPath : appPaths) {
                    Path ap = Path.of(appPath);
                    byte[] appData = Files.readAllBytes(ap);
                    String appName = ap.getFileName().toString();
                    if (CfsImage.isCfsImage(appData)) {
                        cfs = new CfsImage(appData);
                    } else {
                        cfs.addFile(appName, appData);
                    }
                }
                emu.getExternalRam().load(cfs.getImageData(), 0);
            }
        } catch (IOException e) {
            System.err.println("Error loading ROM/app: " + e.getMessage());
            System.exit(1);
        }

        emu.setHeadless(headless);
        emu.getCpu().setTracing(trace);

        // Set up audio
        if (!mute) {
            emu.setSpeaker(new SpeakerOutput(config.clockHz));
        }

        // Wire radio transport
        if ("lan".equals(radioMode)) {
            try {
                int id = radioDeviceId != 0 ? radioDeviceId
                       : Math.abs(java.util.UUID.randomUUID().hashCode());
                UdpMulticastTransport udp = new UdpMulticastTransport(id);
                udp.start();
                emu.getRadio().setTransport(udp);
                Runtime.getRuntime().addShutdownHook(new Thread(udp::close));
            } catch (IOException e) {
                System.err.println("Warning: UDP multicast not available: " + e.getMessage());
            }
        } else if ("sdr".equals(radioMode)) {
            try {
                int id = radioDeviceId != 0 ? radioDeviceId
                       : Math.abs(java.util.UUID.randomUUID().hashCode());
                SdrTransport sdr = new SdrTransport(id, sdrHost, sdrPort);
                sdr.start();
                emu.getRadio().setTransport(sdr);
                Runtime.getRuntime().addShutdownHook(new Thread(sdr::close));
            } catch (IOException e) {
                System.err.println("Warning: SDR bridge not available: " + e.getMessage());
            }
        }

        // Set up renderer and run
        if (!headless) {
            SwingRenderer swing = new SwingRenderer(config);
            swing.setBus(emu.getBus());
            if (remotePort > 0) {
                try {
                    RemoteDisplayServer remote = new RemoteDisplayServer(remotePort, emu.getBus(), emu.getLcd());
                    remote.start();
                    emu.setRenderer(new MultiRenderer(swing, remote));
                } catch (IOException ex) {
                    System.err.println("Error starting remote display server: " + ex.getMessage());
                    System.exit(1);
                }
            } else {
                emu.setRenderer(swing);
            }

            // Wire menu bar
            swing.buildMenuBar(
                // Open NVRAM
                e -> {
                    JFileChooser fc = new JFileChooser();
                    fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                        "NVRAM files", "nvram", "bin", "nv"));
                    if (fc.showOpenDialog(swing.getFrame()) == JFileChooser.APPROVE_OPTION) {
                        try {
                            Path path = fc.getSelectedFile().toPath();
                            byte[] nvData = Files.readAllBytes(path);
                            emu.getExternalRam().load(nvData, 0);
                            emu.setNvramPath(path);
                            System.out.printf("Loaded NVRAM: %s (%d bytes)%n", path, nvData.length);
                        } catch (IOException ex) {
                            JOptionPane.showMessageDialog(swing.getFrame(),
                                "Error loading NVRAM: " + ex.getMessage(),
                                "Error", JOptionPane.ERROR_MESSAGE);
                        }
                    }
                },
                // Save NVRAM As
                e -> {
                    JFileChooser fc = new JFileChooser();
                    fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                        "NVRAM files", "nvram", "bin", "nv"));
                    if (fc.showSaveDialog(swing.getFrame()) == JFileChooser.APPROVE_OPTION) {
                        try {
                            Path path = fc.getSelectedFile().toPath();
                            Files.write(path, emu.getExternalRam().getRawData());
                            emu.setNvramPath(path);
                            System.out.printf("Saved NVRAM: %s%n", path);
                        } catch (IOException ex) {
                            JOptionPane.showMessageDialog(swing.getFrame(),
                                "Error saving NVRAM: " + ex.getMessage(),
                                "Error", JOptionPane.ERROR_MESSAGE);
                        }
                    }
                },
                // Start/Stop
                e -> {
                    if (emu.isRunning()) {
                        emu.stop();
                    } else {
                        emu.start();
                    }
                },
                // Quit
                e -> {
                    emu.stop();
                    emu.saveNvram();
                    System.exit(0);
                }
            );

            // Listen for state changes to update menu
            emu.setStateListener(running -> swing.updateMenuState(running));
            swing.updateMenuState(false); // initial state: stopped

            // Save NVRAM on shutdown (JVM exit)
            if (emu.getNvramPath() != null) {
                Runtime.getRuntime().addShutdownHook(new Thread(emu::saveNvram));
            }

            // Auto-start
            emu.start();

            // Keep main thread alive
            try {
                emu.getEmulatorThread().join();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        } else {
            if (remotePort > 0) {
                try {
                    RemoteDisplayServer remote = new RemoteDisplayServer(remotePort, emu.getBus(), emu.getLcd());
                    remote.start();
                    emu.setRenderer(remote);
                } catch (IOException ex) {
                    System.err.println("Error starting remote display server: " + ex.getMessage());
                    System.exit(1);
                }
            } else {
                emu.setRenderer(new ConsoleRenderer());
            }

            // Save NVRAM on shutdown (JVM exit)
            if (emu.getNvramPath() != null) {
                Runtime.getRuntime().addShutdownHook(new Thread(emu::saveNvram));
            }

            emu.start();
            try {
                emu.getEmulatorThread().join();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
