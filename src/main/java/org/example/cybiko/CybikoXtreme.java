package org.example.cybiko;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Cybiko Xtreme emulator - main orchestrator.
 *
 * Usage: CybikoXtreme <boot_rom.bin> [flash_rom.bin]
 *
 * The boot ROM (32KB) is loaded at 0x000000.
 * The flash ROM (512KB) is loaded at 0x600000.
 */
public class CybikoXtreme {
    private static final long CLOCK_HZ = 18_432_000L; // 18.432 MHz
    private static final int CYCLES_PER_FRAME = (int) (CLOCK_HZ / 60); // ~307,200 cycles per frame at 60fps

    private final Memory bootRom;      // 32KB
    private final Memory externalRam;  // 2MB
    private final Memory flashRom;     // 512KB
    private final Memory onChipRam;    // ~9KB
    private final AddressBus bus;
    private final HD66421Lcd lcd;
    private final H8SCpu cpu;
    private final H8STimer8 timer8_0;
    private final H8STimer8 timer8_1;
    private final H8STimer16[] timer16 = new H8STimer16[6];

    private FrameBufferRenderer renderer;
    private SpeakerOutput speaker;
    private boolean running = false;
    private boolean headless = false;
    private Path nvramPath; // If set, save external RAM to this file on exit

    private static final long NANOS_PER_FRAME = 1_000_000_000L / 60; // ~16.67ms

    public CybikoXtreme() {
        bootRom = new Memory(0x8000, false);       // 32KB ROM
        externalRam = new Memory(0x200000, true);   // 2MB RAM
        flashRom = new Memory(0x80000, false);      // 512KB Flash
        onChipRam = new Memory(0x2400, true);       // 9KB on-chip

        lcd = new HD66421Lcd();
        bus = new AddressBus();

        bus.setBootRom(bootRom);
        bus.setExternalRam(externalRam);
        bus.setFlashRom(flashRom);
        bus.setOnChipRam(onChipRam);
        bus.setLcd(lcd);

        cpu = new H8SCpu(bus);
        bus.setCpu(cpu);

        // Create timer peripherals
        timer8_0 = new H8STimer8(0, cpu);
        timer8_1 = new H8STimer8(1, cpu);
        bus.setTimer8_0(timer8_0);
        bus.setTimer8_1(timer8_1);

        // Timer16 channels (from MAME h8s2319.cpp):
        //   Ch0: 4 TGRs, base vector 32, regs at 0xFFFFD0
        //   Ch1: 2 TGRs, base vector 40, regs at 0xFFFFE0
        //   Ch2: 2 TGRs, base vector 44, regs at 0xFFFFF0
        //   Ch3: 4 TGRs, base vector 48, regs at 0xFFFE80
        //   Ch4: 2 TGRs, base vector 56, regs at 0xFFFE90
        //   Ch5: 2 TGRs, base vector 60, regs at 0xFFFEA0
        timer16[0] = new H8STimer16(0, 4, 32, cpu);
        timer16[1] = new H8STimer16(1, 2, 40, cpu);
        timer16[2] = new H8STimer16(2, 2, 44, cpu);
        timer16[3] = new H8STimer16(3, 4, 48, cpu);
        timer16[4] = new H8STimer16(4, 2, 56, cpu);
        timer16[5] = new H8STimer16(5, 2, 60, cpu);
        bus.setTimer16_0(timer16[0]);
        bus.setTimer16_1(timer16[1]);
        bus.setTimer16_2(timer16[2]);
        bus.setTimer16_3(timer16[3]);
        bus.setTimer16_4(timer16[4]);
        bus.setTimer16_5(timer16[5]);
    }

    public void loadBootRom(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        bootRom.load(data, 0);
        System.out.printf("Loaded boot ROM: %s (%d bytes)%n", path, data.length);
    }

    public void loadFlashRom(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        flashRom.load(data, 0);
        System.out.printf("Loaded flash ROM: %s (%d bytes)%n", path, data.length);
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

    public H8SCpu getCpu() { return cpu; }
    public AddressBus getBus() { return bus; }
    public HD66421Lcd getLcd() { return lcd; }
    public Memory getExternalRam() { return externalRam; }

    /** Initialize and start running. */
    public void start() {
        cpu.reset();

        System.out.println("=== Initial state ===");
        cpu.dumpRegisters();
        System.out.printf("Reset vector: 0x%08X%n", bus.read32(0x000000));
        System.out.println();

        running = true;
        run();
    }

    private void run() {
        int frameCounter = 0;
        long totalSteps = 0;
        long maxSteps = 1_000_000_000L; // ~54 seconds of emulated time at 18MHz

        System.err.println("=== Starting execution ===");
        long frameDeadline = System.nanoTime() + NANOS_PER_FRAME;

        while (running && totalSteps < maxSteps) {
            // Execute one frame's worth of cycles
            long remaining = maxSteps - totalSteps;
            int cycleBudget = (remaining > CYCLES_PER_FRAME) ? CYCLES_PER_FRAME : (int) remaining;
            // Snapshot which timers are running to avoid method calls on stopped timers
            boolean t8_0_run = timer8_0.isRunning();
            boolean t8_1_run = timer8_1.isRunning();
            boolean t16_0_run = timer16[0].isRunning();
            boolean t16_1_run = timer16[1].isRunning();
            boolean t16_2_run = timer16[2].isRunning();
            boolean t16_3_run = timer16[3].isRunning();
            boolean t16_4_run = timer16[4].isRunning();
            boolean t16_5_run = timer16[5].isRunning();
            for (int i = 0; i < cycleBudget; i++) {
                // Tick timers every cycle (even when CPU is halted/sleeping)
                if (t8_0_run) timer8_0.tick();
                if (t8_1_run) timer8_1.tick();
                if (t16_0_run) timer16[0].tick();
                if (t16_1_run) timer16[1].tick();
                if (t16_2_run) timer16[2].tick();
                if (t16_3_run) timer16[3].tick();
                if (t16_4_run) timer16[4].tick();
                if (t16_5_run) timer16[5].tick();

                cpu.step(); // step() handles halt state and interrupt wake-up
                totalSteps++;


                if (totalSteps >= maxSteps) break;
            }

            // Render frame
            if (renderer != null) {
                renderer.render(lcd.getFrameBuffer(), HD66421Lcd.WIDTH, HD66421Lcd.HEIGHT);
            }

            // Generate audio samples for this frame
            if (speaker != null) {
                speaker.generateSamples(CYCLES_PER_FRAME);
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

            // Periodic status (every ~1 second at 60fps)
            if (frameCounter % 60 == 0) {
                int isrCb = bus.read32(0xFFECA8);
                System.err.printf("[STATUS] frame=%d steps=%d PC=0x%06X halted=%b CCR=0x%02X pending=%d isr=0x%06X t8_0[tcr=%02X cnt=%02X cora=%02X irqs=%d] t8_1[tcr=%02X cnt=%02X cora=%02X irqs=%d]%n",
                    frameCounter, totalSteps, cpu.getPC(), cpu.isHalted(),
                    cpu.getCCR(), cpu.getPendingInterruptCount(), isrCb,
                    timer8_0.getTcr(), timer8_0.getTcnt(), timer8_0.getTcora(), timer8_0.getInterruptCount(),
                    timer8_1.getTcr(), timer8_1.getTcnt(), timer8_1.getTcora(), timer8_1.getInterruptCount());
                if (frameCounter <= 300) {
                    System.err.printf("  t8_0 breakdown: %s%n", timer8_0.getIrqBreakdown());
                }
            }

            // Precise frame rate limiting (if we have a display)
            if (!headless && renderer != null) {
                long now = System.nanoTime();
                long sleepNanos = frameDeadline - now;
                if (sleepNanos > 1_000_000) { // only sleep if > 1ms remaining
                    try { Thread.sleep(sleepNanos / 1_000_000, (int)(sleepNanos % 1_000_000)); } catch (InterruptedException e) { break; }
                }
                frameDeadline += NANOS_PER_FRAME;
                // If we fell behind by more than 2 frames, reset deadline to prevent catch-up spiral
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
            System.out.println("Usage: cybiko-java <boot_rom.bin> [flash_rom.bin] [options]");
            System.out.println();
            System.out.println("  boot_rom.bin   - 32KB boot ROM (e.g., cyrom150.bin)");
            System.out.println("  flash_rom.bin  - 512KB flash ROM (e.g., cyos_v1508.bin)");
            System.out.println("  --headless     - Run without GUI window");
            System.out.println("  --trace        - Enable instruction tracing");
            System.out.println("  --nvram <file> - Load/save NVRAM (persistent RAM with CFS filesystem)");
            System.out.println("  --app <file>   - Add .app to NVRAM before booting (requires --nvram)");
            System.out.println("  --list-apps    - List apps in NVRAM and exit");
            System.out.println("  --mute         - Disable audio output");
            System.exit(1);
        }

        CybikoXtreme emu = new CybikoXtreme();
        boolean headless = false;
        boolean trace = false;
        boolean mute = false;
        boolean listApps = false;

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
                case "--nvram" -> { if (i + 1 < args.length) nvramFile = args[++i]; }
                case "--app" -> { if (i + 1 < args.length) appPaths.add(args[++i]); }
                default -> {
                    if (bootRomPath == null) bootRomPath = args[i];
                    else if (flashRomPath == null) flashRomPath = args[i];
                }
            }
        }

        try {
            emu.loadBootRom(Path.of(bootRomPath));
            if (flashRomPath != null) {
                emu.loadFlashRom(Path.of(flashRomPath));
            }

            // NVRAM handling
            if (nvramFile != null) {
                Path nvPath = Path.of(nvramFile);
                emu.nvramPath = nvPath;
                CfsImage cfs;

                if (Files.exists(nvPath)) {
                    // Load existing NVRAM
                    byte[] nvData = Files.readAllBytes(nvPath);
                    if (CfsImage.isCfsImage(nvData)) {
                        cfs = new CfsImage(nvData);
                        System.out.printf("Loaded NVRAM: %s (%d bytes, CFS image)%n",
                            nvramFile, nvData.length);
                    } else {
                        // Not a CFS image - create fresh and warn
                        System.err.printf("Warning: %s is not a CFS image, creating fresh%n", nvramFile);
                        cfs = new CfsImage();
                    }
                } else {
                    // Create fresh CFS image
                    cfs = new CfsImage();
                    System.out.printf("Created new NVRAM: %s%n", nvramFile);
                }

                // Add any requested apps
                for (String appPath : appPaths) {
                    Path ap = Path.of(appPath);
                    byte[] appData = Files.readAllBytes(ap);
                    String appName = ap.getFileName().toString();
                    if (CfsImage.isCfsImage(appData)) {
                        // Already a CFS image - load directly
                        cfs = new CfsImage(appData);
                        System.out.printf("Loaded CFS image: %s%n", appPath);
                    } else {
                        // Raw .app file - wrap in CFS
                        cfs.addFile(appName, appData);
                    }
                }

                // List apps if requested
                if (listApps) {
                    var files = cfs.listFiles();
                    System.out.println("=== Apps in NVRAM ===");
                    if (files.isEmpty()) {
                        System.out.println("  (none)");
                    } else {
                        for (String f : files) System.out.println("  " + f);
                    }
                    // Save any changes from --app, then exit
                    emu.getExternalRam().load(cfs.getImageData(), 0);
                    emu.saveNvram();
                    System.exit(0);
                }

                // Load CFS image into external RAM
                emu.getExternalRam().load(cfs.getImageData(), 0);
            } else if (!appPaths.isEmpty()) {
                // --app without --nvram: create a temporary CFS image in RAM
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
            emu.setSpeaker(new SpeakerOutput());
        }

        // Set up renderer
        if (!headless) {
            SwingRenderer swing = new SwingRenderer();
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
