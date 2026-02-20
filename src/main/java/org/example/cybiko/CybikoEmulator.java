package org.example.cybiko;

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

    private AT45DB041Flash spiFlash;   // V1 only
    private FrameBufferRenderer renderer;
    private SpeakerOutput speaker;
    private boolean running = false;
    private boolean headless = false;
    private Path nvramPath;

    private static final long NANOS_PER_FRAME = 1_000_000_000L / 60;

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
        if (config.hasSpiFlash) {
            // V1: load into AT45DB041 SPI flash
            spiFlash = new AT45DB041Flash(data);
            bus.setSpiFlash(spiFlash);
            System.out.printf("Loaded SPI flash: %s (%d bytes)%n", path, data.length);
        } else if (flashRom != null) {
            flashRom.load(data, 0);
            System.out.printf("Loaded flash ROM: %s (%d bytes)%n", path, data.length);
        }
    }

    public void setRenderer(FrameBufferRenderer renderer) {
        this.renderer = renderer;
    }

    public void setSpeaker(SpeakerOutput speaker) {
        this.speaker = speaker;
        bus.setSpeakerOutput(speaker);
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

    /** Initialize and start running. */
    public void start() {
        cpu.reset();

        System.out.println("=== Initial state (" + config.name + ") ===");
        cpu.dumpRegisters();
        System.out.printf("Reset vector: 0x%08X%n", bus.read32(0x000000));
        System.out.println();

        running = true;
        run();
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

            long frameStartNanos = System.nanoTime();
            for (int i = 0; i < cycleBudget; i++) {
                if (t8_0_run) timer8_0.tick();
                if (t8_1_run) timer8_1.tick();
                if (t16_0_run) timer16[0].tick();
                if (t16_1_run) timer16[1].tick();
                if (t16_2_run) timer16[2].tick();
                if (t16_3_run) timer16[3].tick();
                if (t16_4_run) timer16[4].tick();
                if (t16_5_run) timer16[5].tick();

                cpu.step();
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

            // Print serial output from boot loader / CyOS
            for (int sci = 0; sci < 3; sci++) {
                String s = bus.drainSerialOutput(sci);
                if (!s.isEmpty()) {
                    System.err.printf("[SCI%d] %s%n", sci, s);
                }
            }

            frameCounter++;

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
    }

    public void stop() {
        running = false;
    }

    /** Save external RAM to the NVRAM file. */
    private void saveNvram() {
        if (nvramPath == null) return;
        try {
            byte[] data = externalRam.getRawData();
            Files.write(nvramPath, data);
            System.out.printf("Saved NVRAM: %s (%d bytes)%n", nvramPath, data.length);
        } catch (IOException e) {
            System.err.println("Error saving NVRAM: " + e.getMessage());
        }
    }

    // --- Main entry point ---
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: cybiko-java [--machine v1|v2|xt] <boot_rom.bin> [flash_rom.bin] [options]");
            System.out.println();
            System.out.println("  --machine <type> - Machine type: v1 (Classic), v2, xt (Xtreme, default)");
            System.out.println("  boot_rom.bin     - 32KB boot ROM (e.g., cyrom150.bin for XT, cyrom112.bin for V1)");
            System.out.println("  flash_rom.bin    - Flash ROM (e.g., cyos_v1508.bin for XT, flash_v1246.bin for V1)");
            System.out.println("  --headless       - Run without GUI window");
            System.out.println("  --trace          - Enable instruction tracing");
            System.out.println("  --nvram <file>   - Load/save NVRAM (persistent RAM with CFS filesystem)");
            System.out.println("  --app <file>     - Add .app to NVRAM before booting (requires --nvram or XT)");
            System.out.println("  --list-apps      - List apps in NVRAM and exit");
            System.out.println("  --mute           - Disable audio output");
            System.exit(1);
        }

        boolean headless = false;
        boolean trace = false;
        boolean mute = false;
        boolean listApps = false;
        MachineConfig.MachineType machineType = MachineConfig.MachineType.XT;

        // Parse arguments
        String bootRomPath = null;
        String flashRomPath = null;
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
                default -> {
                    if (bootRomPath == null) bootRomPath = args[i];
                    else if (flashRomPath == null) flashRomPath = args[i];
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

        // Set up renderer
        if (!headless) {
            SwingRenderer swing = new SwingRenderer(config);
            swing.setBus(emu.getBus());
            emu.setRenderer(swing);
        } else {
            emu.setRenderer(new ConsoleRenderer());
        }

        // Save NVRAM on shutdown (JVM exit)
        if (emu.nvramPath != null) {
            Runtime.getRuntime().addShutdownHook(new Thread(emu::saveNvram));
        }

        emu.start();
    }
}
