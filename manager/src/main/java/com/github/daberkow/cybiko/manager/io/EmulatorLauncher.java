package com.github.daberkow.cybiko.manager.io;

import com.github.daberkow.cybiko.manager.cfs.FlashGeometry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves ROM/JAR locations and spawns the emulator as a separate JVM process.
 */
public final class EmulatorLauncher {

    /** GitHub releases URL shown when emulator JAR is not found. */
    public static final String RELEASES_URL = "https://github.com/daberkow/cybiko-java-emulator/releases";

    private EmulatorLauncher() {}

    /** Map FlashGeometry to --machine CLI value. */
    public static String machineType(FlashGeometry geometry) {
        return switch (geometry) {
            case XTREME -> "xt";
            case AT45DB041, AT45DB161 -> "v1";
            case AT45DB081 -> "v2";
        };
    }

    /** Default boot ROM filename for a machine type. */
    public static String defaultBootRomName(String machineType) {
        return switch (machineType) {
            case "v1" -> "cyrom112.bin";
            case "v2" -> "cyrom112.bin";
            default -> "cyrom150.bin";
        };
    }

    /** Default flash ROM filename for a machine type. */
    public static String defaultFlashRomName(String machineType) {
        return switch (machineType) {
            case "v1" -> "flash_v1246.bin";
            case "v2" -> "flash_v1246.bin";
            default -> "cyos_v1508.bin";
        };
    }

    /**
     * Search for a ROM file by name in a list of directories.
     * @return path to the file if found, null otherwise
     */
    public static Path searchRom(String filename, List<Path> searchDirs) {
        for (Path dir : searchDirs) {
            Path candidate = dir.resolve(filename);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    /**
     * Search for the emulator JAR in common locations.
     * @return path to JAR if found, null otherwise
     */
    public static Path searchEmulatorJar(List<Path> searchDirs) {
        for (Path dir : searchDirs) {
            try (var stream = Files.list(dir)) {
                Path found = stream
                        .filter(p -> p.getFileName().toString().startsWith("emulator"))
                        .filter(p -> p.getFileName().toString().endsWith(".jar"))
                        .findFirst().orElse(null);
                if (found != null) return found;
            } catch (IOException ignored) {}
        }
        return null;
    }

    /**
     * Build the full command line for launching the emulator.
     * Does NOT include "java -jar emulator.jar" prefix — caller adds that.
     * @return list of CLI arguments for the emulator
     */
    public static List<String> buildCommandLine(
            Path bootRom, Path flashRom, Path nvram,
            FlashGeometry geometry, EmulatorConfig cfg) {

        List<String> args = new ArrayList<>();
        String machine = machineType(geometry);

        args.add("--machine");
        args.add(machine);
        args.add(bootRom.toString());
        args.add(flashRom.toString());
        args.add("--nvram");
        args.add(nvram.toString());

        if (cfg.isMute()) args.add("--mute");
        if (cfg.isHeadless()) args.add("--headless");
        if (cfg.isTrace()) args.add("--trace");

        if (!"off".equals(cfg.getRadioMode()) && !cfg.getRadioMode().isEmpty()) {
            args.add("--radio");
            args.add(cfg.getRadioMode());

            if (!cfg.getRadioId().isEmpty()) {
                args.add("--radio-id");
                args.add(cfg.getRadioId());
            }

            if ("sdr".equals(cfg.getRadioMode())) {
                if (!cfg.getSdrHost().isEmpty()) {
                    args.add("--sdr-host");
                    args.add(cfg.getSdrHost());
                }
                if (!cfg.getSdrPort().isEmpty()) {
                    args.add("--sdr-port");
                    args.add(cfg.getSdrPort());
                }
            }
        }

        if (!cfg.getLogging().isEmpty()) {
            args.add("--logging");
            args.add(cfg.getLogging());
        }

        return args;
    }

    /**
     * Launch the emulator as a separate process.
     * @param emulatorJar path to the emulator JAR
     * @param emulatorArgs CLI arguments (from buildCommandLine)
     * @return the spawned Process
     */
    public static Process launch(Path emulatorJar, List<String> emulatorArgs) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(ProcessHandle.current().info().command().orElse("java"));
        cmd.add("-jar");
        cmd.add(emulatorJar.toString());
        cmd.addAll(emulatorArgs);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        return pb.start();
    }
}
