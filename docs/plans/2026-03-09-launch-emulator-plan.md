# Launch Emulator from Manager — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Allow users to launch the Cybiko emulator directly from the NVRAM Manager with configurable settings and automatic ROM/JAR discovery.

**Architecture:** New `EmulatorConfig` class persists emulator settings (ROM paths, JAR path, radio config) to `~/.cybiko-manager/emulator.properties`. New `EmulatorLauncher` class resolves ROM/JAR locations via a search chain (saved paths → local dir → ./roms/ → user prompt) and spawns the emulator as a separate JVM process. New `EmulatorSettingsDialog` provides a UI for all emulator CLI options. MainWindow gets menu items and a detail pane button to trigger launch.

**Tech Stack:** Java 21, JavaFX (AtlantaFX themed), ProcessBuilder for process spawning, java.util.Properties for config persistence.

---

### Task 1: EmulatorConfig — settings model and persistence

**Files:**
- Create: `manager/src/main/java/com/github/daberkow/cybiko/manager/io/EmulatorConfig.java`
- Test: `manager/src/test/java/com/github/daberkow/cybiko/manager/io/EmulatorConfigTest.java`

**Step 1: Write the failing test**

```java
package com.github.daberkow.cybiko.manager.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EmulatorConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void saveAndLoadRoundtrip() throws IOException {
        Path config = tempDir.resolve("emulator.properties");

        EmulatorConfig cfg = new EmulatorConfig();
        cfg.setEmulatorJarPath("/opt/cybiko/emulator.jar");
        cfg.setXtBootRom("/roms/cyrom150.bin");
        cfg.setXtFlashRom("/roms/cyos_v1508.bin");
        cfg.setV1BootRom("/roms/cyrom112.bin");
        cfg.setV1FlashRom("/roms/flash_v1246.bin");
        cfg.setRadioMode("lan");
        cfg.setRadioId("42");
        cfg.setMute(true);
        cfg.setHeadless(false);
        cfg.setTrace(false);
        cfg.setSdrHost("192.168.1.50");
        cfg.setSdrPort("19201");
        cfg.setLogging("radio,status");

        EmulatorConfig.save(cfg, config);
        EmulatorConfig loaded = EmulatorConfig.load(config);

        assertEquals("/opt/cybiko/emulator.jar", loaded.getEmulatorJarPath());
        assertEquals("/roms/cyrom150.bin", loaded.getXtBootRom());
        assertEquals("/roms/cyos_v1508.bin", loaded.getXtFlashRom());
        assertEquals("/roms/cyrom112.bin", loaded.getV1BootRom());
        assertEquals("/roms/flash_v1246.bin", loaded.getV1FlashRom());
        assertEquals("lan", loaded.getRadioMode());
        assertEquals("42", loaded.getRadioId());
        assertTrue(loaded.isMute());
        assertFalse(loaded.isHeadless());
        assertFalse(loaded.isTrace());
        assertEquals("192.168.1.50", loaded.getSdrHost());
        assertEquals("19201", loaded.getSdrPort());
        assertEquals("radio,status", loaded.getLogging());
    }

    @Test
    void loadMissingFileReturnsDefaults() {
        Path config = tempDir.resolve("nonexistent.properties");
        EmulatorConfig loaded = EmulatorConfig.load(config);

        assertEquals("", loaded.getEmulatorJarPath());
        assertEquals("off", loaded.getRadioMode());
        assertFalse(loaded.isMute());
        assertEquals("localhost", loaded.getSdrHost());
        assertEquals("19201", loaded.getSdrPort());
    }

    @Test
    void defaultRadioModeIsOff() {
        EmulatorConfig cfg = new EmulatorConfig();
        assertEquals("off", cfg.getRadioMode());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :manager:test --tests "com.github.daberkow.cybiko.manager.io.EmulatorConfigTest" -q`
Expected: Compilation error — EmulatorConfig does not exist.

**Step 3: Write minimal implementation**

```java
package com.github.daberkow.cybiko.manager.io;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Persists emulator launch settings to ~/.cybiko-manager/emulator.properties.
 */
public final class EmulatorConfig {

    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".cybiko-manager");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("emulator.properties");

    private String emulatorJarPath = "";
    private String xtBootRom = "";
    private String xtFlashRom = "";
    private String v1BootRom = "";
    private String v1FlashRom = "";
    private String v2BootRom = "";
    private String v2FlashRom = "";
    private String radioMode = "off";   // "off", "lan", "sdr"
    private String radioId = "";
    private String sdrHost = "localhost";
    private String sdrPort = "19201";
    private boolean mute = false;
    private boolean headless = false;
    private boolean trace = false;
    private String logging = "";

    public EmulatorConfig() {}

    // --- Getters and setters ---

    public String getEmulatorJarPath() { return emulatorJarPath; }
    public void setEmulatorJarPath(String v) { emulatorJarPath = v != null ? v : ""; }

    public String getXtBootRom() { return xtBootRom; }
    public void setXtBootRom(String v) { xtBootRom = v != null ? v : ""; }

    public String getXtFlashRom() { return xtFlashRom; }
    public void setXtFlashRom(String v) { xtFlashRom = v != null ? v : ""; }

    public String getV1BootRom() { return v1BootRom; }
    public void setV1BootRom(String v) { v1BootRom = v != null ? v : ""; }

    public String getV1FlashRom() { return v1FlashRom; }
    public void setV1FlashRom(String v) { v1FlashRom = v != null ? v : ""; }

    public String getV2BootRom() { return v2BootRom; }
    public void setV2BootRom(String v) { v2BootRom = v != null ? v : ""; }

    public String getV2FlashRom() { return v2FlashRom; }
    public void setV2FlashRom(String v) { v2FlashRom = v != null ? v : ""; }

    public String getRadioMode() { return radioMode; }
    public void setRadioMode(String v) { radioMode = v != null ? v : "off"; }

    public String getRadioId() { return radioId; }
    public void setRadioId(String v) { radioId = v != null ? v : ""; }

    public String getSdrHost() { return sdrHost; }
    public void setSdrHost(String v) { sdrHost = v != null ? v : "localhost"; }

    public String getSdrPort() { return sdrPort; }
    public void setSdrPort(String v) { sdrPort = v != null ? v : "19201"; }

    public boolean isMute() { return mute; }
    public void setMute(boolean v) { mute = v; }

    public boolean isHeadless() { return headless; }
    public void setHeadless(boolean v) { headless = v; }

    public boolean isTrace() { return trace; }
    public void setTrace(boolean v) { trace = v; }

    public String getLogging() { return logging; }
    public void setLogging(String v) { logging = v != null ? v : ""; }

    // --- Persistence ---

    public static EmulatorConfig load() { return load(CONFIG_FILE); }

    public static EmulatorConfig load(Path configFile) {
        EmulatorConfig cfg = new EmulatorConfig();
        if (!Files.exists(configFile)) return cfg;

        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(configFile)) {
            props.load(reader);
        } catch (IOException e) {
            return cfg;
        }

        cfg.emulatorJarPath = props.getProperty("emulator.jar", "");
        cfg.xtBootRom = props.getProperty("xt.bootrom", "");
        cfg.xtFlashRom = props.getProperty("xt.flash", "");
        cfg.v1BootRom = props.getProperty("v1.bootrom", "");
        cfg.v1FlashRom = props.getProperty("v1.flash", "");
        cfg.v2BootRom = props.getProperty("v2.bootrom", "");
        cfg.v2FlashRom = props.getProperty("v2.flash", "");
        cfg.radioMode = props.getProperty("radio.mode", "off");
        cfg.radioId = props.getProperty("radio.id", "");
        cfg.sdrHost = props.getProperty("sdr.host", "localhost");
        cfg.sdrPort = props.getProperty("sdr.port", "19201");
        cfg.mute = Boolean.parseBoolean(props.getProperty("mute", "false"));
        cfg.headless = Boolean.parseBoolean(props.getProperty("headless", "false"));
        cfg.trace = Boolean.parseBoolean(props.getProperty("trace", "false"));
        cfg.logging = props.getProperty("logging", "");
        return cfg;
    }

    public static void save(EmulatorConfig cfg) throws IOException { save(cfg, CONFIG_FILE); }

    public static void save(EmulatorConfig cfg, Path configFile) throws IOException {
        Files.createDirectories(configFile.getParent());
        Properties props = new Properties();
        props.setProperty("emulator.jar", cfg.emulatorJarPath);
        props.setProperty("xt.bootrom", cfg.xtBootRom);
        props.setProperty("xt.flash", cfg.xtFlashRom);
        props.setProperty("v1.bootrom", cfg.v1BootRom);
        props.setProperty("v1.flash", cfg.v1FlashRom);
        props.setProperty("v2.bootrom", cfg.v2BootRom);
        props.setProperty("v2.flash", cfg.v2FlashRom);
        props.setProperty("radio.mode", cfg.radioMode);
        props.setProperty("radio.id", cfg.radioId);
        props.setProperty("sdr.host", cfg.sdrHost);
        props.setProperty("sdr.port", cfg.sdrPort);
        props.setProperty("mute", String.valueOf(cfg.mute));
        props.setProperty("headless", String.valueOf(cfg.headless));
        props.setProperty("trace", String.valueOf(cfg.trace));
        props.setProperty("logging", cfg.logging);
        try (Writer writer = Files.newBufferedWriter(configFile)) {
            props.store(writer, "Cybiko NVRAM Manager - Emulator Settings");
        }
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew :manager:test --tests "com.github.daberkow.cybiko.manager.io.EmulatorConfigTest" -q`
Expected: All 3 tests PASS.

**Step 5: Commit**

```bash
git add manager/src/main/java/com/github/daberkow/cybiko/manager/io/EmulatorConfig.java \
       manager/src/test/java/com/github/daberkow/cybiko/manager/io/EmulatorConfigTest.java
git commit -m "feat(manager): add EmulatorConfig for persisting emulator launch settings"
```

---

### Task 2: EmulatorLauncher — ROM/JAR resolution and process spawning

**Files:**
- Create: `manager/src/main/java/com/github/daberkow/cybiko/manager/io/EmulatorLauncher.java`
- Test: `manager/src/test/java/com/github/daberkow/cybiko/manager/io/EmulatorLauncherTest.java`

**Step 1: Write the failing test**

```java
package com.github.daberkow.cybiko.manager.io;

import com.github.daberkow.cybiko.manager.cfs.FlashGeometry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EmulatorLauncherTest {

    @TempDir
    Path tempDir;

    @Test
    void buildCommandLineXtMinimal() {
        EmulatorConfig cfg = new EmulatorConfig();
        Path bootRom = tempDir.resolve("cyrom150.bin");
        Path flashRom = tempDir.resolve("cyos_v1508.bin");
        Path nvram = tempDir.resolve("test.nvram");

        List<String> cmd = EmulatorLauncher.buildCommandLine(
                bootRom, flashRom, nvram, FlashGeometry.XTREME, cfg);

        assertTrue(cmd.contains("--machine"));
        assertEquals("xt", cmd.get(cmd.indexOf("--machine") + 1));
        assertTrue(cmd.contains("--nvram"));
        assertEquals(nvram.toString(), cmd.get(cmd.indexOf("--nvram") + 1));
        assertTrue(cmd.contains(bootRom.toString()));
        assertTrue(cmd.contains(flashRom.toString()));
        // No radio flags when mode is "off"
        assertFalse(cmd.contains("--radio"));
    }

    @Test
    void buildCommandLineV1WithRadio() {
        EmulatorConfig cfg = new EmulatorConfig();
        cfg.setRadioMode("lan");
        cfg.setRadioId("7");
        cfg.setMute(true);
        cfg.setLogging("radio,status");

        Path bootRom = tempDir.resolve("cyrom112.bin");
        Path flashRom = tempDir.resolve("flash_v1246.bin");
        Path nvram = tempDir.resolve("v1.nvram");

        List<String> cmd = EmulatorLauncher.buildCommandLine(
                bootRom, flashRom, nvram, FlashGeometry.AT45DB041, cfg);

        assertEquals("v1", cmd.get(cmd.indexOf("--machine") + 1));
        assertTrue(cmd.contains("--radio"));
        assertEquals("lan", cmd.get(cmd.indexOf("--radio") + 1));
        assertTrue(cmd.contains("--radio-id"));
        assertEquals("7", cmd.get(cmd.indexOf("--radio-id") + 1));
        assertTrue(cmd.contains("--mute"));
        assertTrue(cmd.contains("--logging"));
        assertEquals("radio,status", cmd.get(cmd.indexOf("--logging") + 1));
    }

    @Test
    void buildCommandLineSdrIncludesHostAndPort() {
        EmulatorConfig cfg = new EmulatorConfig();
        cfg.setRadioMode("sdr");
        cfg.setSdrHost("192.168.1.50");
        cfg.setSdrPort("19202");

        Path bootRom = tempDir.resolve("boot.bin");
        Path flashRom = tempDir.resolve("flash.bin");
        Path nvram = tempDir.resolve("test.nvram");

        List<String> cmd = EmulatorLauncher.buildCommandLine(
                bootRom, flashRom, nvram, FlashGeometry.XTREME, cfg);

        assertTrue(cmd.contains("--sdr-host"));
        assertEquals("192.168.1.50", cmd.get(cmd.indexOf("--sdr-host") + 1));
        assertTrue(cmd.contains("--sdr-port"));
        assertEquals("19202", cmd.get(cmd.indexOf("--sdr-port") + 1));
    }

    @Test
    void machineTypeFromGeometry() {
        assertEquals("xt", EmulatorLauncher.machineType(FlashGeometry.XTREME));
        assertEquals("v1", EmulatorLauncher.machineType(FlashGeometry.AT45DB041));
        assertEquals("v2", EmulatorLauncher.machineType(FlashGeometry.AT45DB081));
        assertEquals("v1", EmulatorLauncher.machineType(FlashGeometry.AT45DB161));
    }

    @Test
    void searchRomInDirectory() throws IOException {
        Path romsDir = tempDir.resolve("roms");
        Files.createDirectories(romsDir);
        Path rom = Files.createFile(romsDir.resolve("cyrom150.bin"));

        Path found = EmulatorLauncher.searchRom("cyrom150.bin", List.of(tempDir, romsDir));
        assertNotNull(found);
        assertEquals(rom, found);
    }

    @Test
    void searchRomReturnsNullWhenNotFound() {
        Path found = EmulatorLauncher.searchRom("nonexistent.bin", List.of(tempDir));
        assertNull(found);
    }

    @Test
    void knownRomFilenames() {
        // XT
        assertEquals("cyrom150.bin", EmulatorLauncher.defaultBootRomName("xt"));
        assertEquals("cyos_v1508.bin", EmulatorLauncher.defaultFlashRomName("xt"));
        // V1
        assertEquals("cyrom112.bin", EmulatorLauncher.defaultBootRomName("v1"));
        assertEquals("flash_v1246.bin", EmulatorLauncher.defaultFlashRomName("v1"));
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :manager:test --tests "com.github.daberkow.cybiko.manager.io.EmulatorLauncherTest" -q`
Expected: Compilation error — EmulatorLauncher does not exist.

**Step 3: Write minimal implementation**

```java
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
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew :manager:test --tests "com.github.daberkow.cybiko.manager.io.EmulatorLauncherTest" -q`
Expected: All 7 tests PASS.

**Step 5: Commit**

```bash
git add manager/src/main/java/com/github/daberkow/cybiko/manager/io/EmulatorLauncher.java \
       manager/src/test/java/com/github/daberkow/cybiko/manager/io/EmulatorLauncherTest.java
git commit -m "feat(manager): add EmulatorLauncher for ROM/JAR resolution and process spawning"
```

---

### Task 3: EmulatorSettingsDialog — in-window configuration dialog

**Files:**
- Create: `manager/src/main/java/com/github/daberkow/cybiko/manager/ui/EmulatorSettingsDialog.java`

**Step 1: Write the dialog class**

This follows the same pattern as `LibraryFolderDialog` (extends `Dialog<EmulatorConfig>`,
uses GridPane layout, returns config on OK).

```java
package com.github.daberkow.cybiko.manager.ui;

import com.github.daberkow.cybiko.manager.io.EmulatorConfig;
import com.github.daberkow.cybiko.manager.io.EmulatorLauncher;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * In-window overlay dialog for configuring emulator launch settings.
 * All fields map directly to EmulatorConfig properties and CLI arguments.
 */
public class EmulatorSettingsDialog extends Dialog<EmulatorConfig> {

    public EmulatorSettingsDialog(EmulatorConfig config, Window owner) {
        setTitle("Emulator Settings");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(16));

        int row = 0;

        // --- Emulator JAR ---
        Label jarLabel = new Label("Emulator JAR:");
        TextField jarField = new TextField(config.getEmulatorJarPath());
        jarField.setPrefWidth(300);
        Button jarBrowse = new Button("Browse...");
        jarBrowse.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Locate Emulator JAR");
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("JAR Files", "*.jar"));
            java.io.File file = chooser.showOpenDialog(owner);
            if (file != null) jarField.setText(file.getAbsolutePath());
        });
        Hyperlink downloadLink = new Hyperlink("Download from GitHub Releases");
        downloadLink.setOnAction(e -> {
            try {
                java.awt.Desktop.getDesktop().browse(
                        java.net.URI.create(EmulatorLauncher.RELEASES_URL));
            } catch (Exception ignored) {}
        });
        grid.add(jarLabel, 0, row);
        grid.add(new HBox(8, jarField, jarBrowse), 1, row);
        row++;
        grid.add(downloadLink, 1, row);
        row++;

        // --- Separator ---
        grid.add(new Separator(), 0, row, 2, 1);
        row++;

        // --- General options ---
        Label generalHeader = new Label("General");
        generalHeader.setStyle("-fx-font-weight: bold;");
        grid.add(generalHeader, 0, row, 2, 1);
        row++;

        CheckBox muteBox = new CheckBox("Mute audio");
        muteBox.setSelected(config.isMute());
        grid.add(muteBox, 1, row++);

        CheckBox headlessBox = new CheckBox("Headless (no GUI)");
        headlessBox.setSelected(config.isHeadless());
        grid.add(headlessBox, 1, row++);

        CheckBox traceBox = new CheckBox("Instruction trace");
        traceBox.setSelected(config.isTrace());
        grid.add(traceBox, 1, row++);

        Label loggingLabel = new Label("Logging:");
        TextField loggingField = new TextField(config.getLogging());
        loggingField.setPromptText("e.g. radio,status,boot");
        grid.add(loggingLabel, 0, row);
        grid.add(loggingField, 1, row);
        row++;

        // --- Separator ---
        grid.add(new Separator(), 0, row, 2, 1);
        row++;

        // --- Radio options ---
        Label radioHeader = new Label("Radio");
        radioHeader.setStyle("-fx-font-weight: bold;");
        grid.add(radioHeader, 0, row, 2, 1);
        row++;

        Label radioLabel = new Label("Radio mode:");
        ComboBox<String> radioCombo = new ComboBox<>();
        radioCombo.getItems().addAll("off", "lan", "sdr");
        radioCombo.setValue(config.getRadioMode());
        grid.add(radioLabel, 0, row);
        grid.add(radioCombo, 1, row);
        row++;

        Label idLabel = new Label("Radio ID:");
        TextField idField = new TextField(config.getRadioId());
        idField.setPromptText("Random if empty");
        grid.add(idLabel, 0, row);
        grid.add(idField, 1, row);
        row++;

        Label sdrHostLabel = new Label("SDR host:");
        TextField sdrHostField = new TextField(config.getSdrHost());
        grid.add(sdrHostLabel, 0, row);
        grid.add(sdrHostField, 1, row);
        row++;

        Label sdrPortLabel = new Label("SDR port:");
        TextField sdrPortField = new TextField(config.getSdrPort());
        grid.add(sdrPortLabel, 0, row);
        grid.add(sdrPortField, 1, row);
        row++;

        // Enable/disable radio fields based on mode
        Runnable updateRadioFields = () -> {
            String mode = radioCombo.getValue();
            boolean radioOn = !"off".equals(mode);
            boolean sdrOn = "sdr".equals(mode);
            idField.setDisable(!radioOn);
            sdrHostField.setDisable(!sdrOn);
            sdrPortField.setDisable(!sdrOn);
        };
        radioCombo.setOnAction(e -> updateRadioFields.run());
        updateRadioFields.run();

        getDialogPane().setContent(grid);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        setResultConverter(button -> {
            if (button != ButtonType.OK) return null;
            EmulatorConfig result = new EmulatorConfig();
            result.setEmulatorJarPath(jarField.getText().trim());
            result.setMute(muteBox.isSelected());
            result.setHeadless(headlessBox.isSelected());
            result.setTrace(traceBox.isSelected());
            result.setLogging(loggingField.getText().trim());
            result.setRadioMode(radioCombo.getValue());
            result.setRadioId(idField.getText().trim());
            result.setSdrHost(sdrHostField.getText().trim());
            result.setSdrPort(sdrPortField.getText().trim());
            // Preserve ROM paths from incoming config (not edited here)
            result.setXtBootRom(config.getXtBootRom());
            result.setXtFlashRom(config.getXtFlashRom());
            result.setV1BootRom(config.getV1BootRom());
            result.setV1FlashRom(config.getV1FlashRom());
            result.setV2BootRom(config.getV2BootRom());
            result.setV2FlashRom(config.getV2FlashRom());
            return result;
        });
    }
}
```

**Step 2: Verify compilation**

Run: `./gradlew :manager:compileJava -q`
Expected: BUILD SUCCESSFUL.

**Step 3: Commit**

```bash
git add manager/src/main/java/com/github/daberkow/cybiko/manager/ui/EmulatorSettingsDialog.java
git commit -m "feat(manager): add EmulatorSettingsDialog for emulator launch configuration"
```

---

### Task 4: Wire launch into MainWindow — menu items + detail pane button

**Files:**
- Modify: `manager/src/main/java/com/github/daberkow/cybiko/manager/MainWindow.java`
- Modify: `manager/src/main/java/com/github/daberkow/cybiko/manager/ui/DetailPane.java`

**Step 1: Add emulator config field and menu items to MainWindow**

In `MainWindow.java`, add field:
```java
private EmulatorConfig emulatorConfig;
```

In the constructor, after `recentPaths = RecentFiles.load();`, add:
```java
emulatorConfig = EmulatorConfig.load();
```

In `createMenuBar()`, add to the NVRAM menu after the `exportCsvItem` block:

```java
MenuItem launchItem = new MenuItem("Launch Emulator");
launchItem.setOnAction(e -> launchEmulator());

MenuItem settingsItem = new MenuItem("Emulator Settings...");
settingsItem.setOnAction(e -> showEmulatorSettings());
```

Update the `nvramMenu.getItems().addAll(...)` call to include:
```java
nvramMenu.getItems().addAll(
    addFileItem, removeSelectedItem,
    new SeparatorMenuItem(),
    validateItem, propertiesItem,
    new SeparatorMenuItem(),
    exportCsvItem,
    new SeparatorMenuItem(),
    launchItem, settingsItem
);
```

**Step 2: Add showEmulatorSettings() method to MainWindow**

```java
private void showEmulatorSettings() {
    EmulatorSettingsDialog dialog = new EmulatorSettingsDialog(emulatorConfig, stage);
    Optional<EmulatorConfig> result = showDialog(dialog);
    result.ifPresent(cfg -> {
        emulatorConfig = cfg;
        try {
            EmulatorConfig.save(cfg);
        } catch (IOException ex) {
            showError("Failed to save settings", ex.getMessage());
        }
    });
}
```

**Step 3: Add launchEmulator() method to MainWindow**

This is the core method. It:
1. Checks NVRAM is selected and saved
2. Resolves ROMs (from config, then search dirs, then prompt)
3. Resolves emulator JAR (from config, then search dirs, then prompt with download link)
4. Builds command line and spawns process

```java
private void launchEmulator() {
    if (currentImage == null) {
        showError("No NVRAM Selected", "Please select an NVRAM image first.");
        return;
    }
    if (currentPath == null || currentImage.isModified()) {
        showError("Unsaved NVRAM",
                "Please save the NVRAM image before launching the emulator.");
        return;
    }

    FlashGeometry geometry = currentImage.getGeometry();
    String machine = EmulatorLauncher.machineType(geometry);

    // --- Resolve ROMs ---
    Path bootRom = resolveRom(machine, true);
    if (bootRom == null) return; // user cancelled
    Path flashRom = resolveRom(machine, false);
    if (flashRom == null) return;

    // --- Resolve emulator JAR ---
    Path emulatorJar = resolveEmulatorJar();
    if (emulatorJar == null) return;

    // --- Save resolved paths ---
    setRomPath(machine, true, bootRom.toString());
    setRomPath(machine, false, flashRom.toString());
    emulatorConfig.setEmulatorJarPath(emulatorJar.toString());
    try { EmulatorConfig.save(emulatorConfig); } catch (IOException ignored) {}

    // --- Build command line and launch ---
    List<String> args = EmulatorLauncher.buildCommandLine(
            bootRom, flashRom, currentPath, geometry, emulatorConfig);
    try {
        EmulatorLauncher.launch(emulatorJar, args);
    } catch (IOException ex) {
        showError("Failed to launch emulator", ex.getMessage());
    }
}

private Path resolveRom(String machineType, boolean isBootRom) {
    // Check saved config first
    String savedPath = isBootRom ? getBootRomPath(machineType) : getFlashRomPath(machineType);
    if (!savedPath.isEmpty()) {
        Path p = Path.of(savedPath);
        if (java.nio.file.Files.isRegularFile(p)) return p;
    }

    // Search common directories
    String filename = isBootRom
            ? EmulatorLauncher.defaultBootRomName(machineType)
            : EmulatorLauncher.defaultFlashRomName(machineType);
    List<Path> searchDirs = List.of(
            Path.of("."),
            Path.of("roms"),
            Path.of("..", "roms")
    );
    Path found = EmulatorLauncher.searchRom(filename, searchDirs);
    if (found != null) return found;

    // Ask user
    FileChooser chooser = new FileChooser();
    chooser.setTitle("Locate " + (isBootRom ? "Boot ROM" : "Flash ROM")
            + " for " + machineType.toUpperCase() + " (" + filename + ")");
    chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("ROM Files", "*.bin"));
    java.io.File file = chooser.showOpenDialog(stage);
    return file != null ? file.toPath() : null;
}

private Path resolveEmulatorJar() {
    // Check saved config
    String saved = emulatorConfig.getEmulatorJarPath();
    if (!saved.isEmpty()) {
        Path p = Path.of(saved);
        if (java.nio.file.Files.isRegularFile(p)) return p;
    }

    // Search common locations
    List<Path> searchDirs = List.of(
            Path.of("."),
            Path.of("..", "emulator", "build", "libs")
    );
    Path found = EmulatorLauncher.searchEmulatorJar(searchDirs);
    if (found != null) return found;

    // Ask user with download link
    Alert alert = new Alert(Alert.AlertType.INFORMATION);
    alert.setTitle("Emulator Not Found");
    alert.setHeaderText("Could not find the emulator JAR.");
    Hyperlink link = new Hyperlink(EmulatorLauncher.RELEASES_URL);
    link.setOnAction(ev -> {
        try {
            java.awt.Desktop.getDesktop().browse(
                    java.net.URI.create(EmulatorLauncher.RELEASES_URL));
        } catch (Exception ignored) {}
    });
    javafx.scene.layout.VBox content = new javafx.scene.layout.VBox(8,
            new Label("Download from:"), link,
            new Label("Or click OK to browse for it."));
    alert.getDialogPane().setContent(content);
    showDialog(alert);

    FileChooser chooser = new FileChooser();
    chooser.setTitle("Locate Emulator JAR");
    chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("JAR Files", "*.jar"));
    java.io.File file = chooser.showOpenDialog(stage);
    return file != null ? file.toPath() : null;
}

// Helper methods for per-machine ROM paths
private String getBootRomPath(String machine) {
    return switch (machine) {
        case "v1" -> emulatorConfig.getV1BootRom();
        case "v2" -> emulatorConfig.getV2BootRom();
        default -> emulatorConfig.getXtBootRom();
    };
}

private String getFlashRomPath(String machine) {
    return switch (machine) {
        case "v1" -> emulatorConfig.getV1FlashRom();
        case "v2" -> emulatorConfig.getV2FlashRom();
        default -> emulatorConfig.getXtFlashRom();
    };
}

private void setRomPath(String machine, boolean isBootRom, String path) {
    if (isBootRom) {
        switch (machine) {
            case "v1" -> emulatorConfig.setV1BootRom(path);
            case "v2" -> emulatorConfig.setV2BootRom(path);
            default -> emulatorConfig.setXtBootRom(path);
        }
    } else {
        switch (machine) {
            case "v1" -> emulatorConfig.setV1FlashRom(path);
            case "v2" -> emulatorConfig.setV2FlashRom(path);
            default -> emulatorConfig.setXtFlashRom(path);
        }
    }
}
```

**Step 4: Add launch button to DetailPane**

In `DetailPane.java`, add a new button field:
```java
private final Button launchEmulatorBtn = new Button("Launch Emulator");
```

Add a callback field and setter:
```java
private Runnable onLaunchEmulator;

public void setOnLaunchEmulator(Runnable callback) {
    this.onLaunchEmulator = callback;
}
```

In the constructor, wire the button:
```java
launchEmulatorBtn.getStyleClass().add("action-button");
launchEmulatorBtn.setOnAction(e -> {
    if (onLaunchEmulator != null) onLaunchEmulator.run();
});
```

In the `showItem()` method, when showing an NvramItem, add the launch button
to the `actionBox` alongside the existing buttons:
```java
// In the NvramItem branch of showItem():
actionBox.getChildren().setAll(launchEmulatorBtn, removeFromNvramBtn, viewHexBtn);
```

**Step 5: Wire the detail pane callback in MainWindow constructor**

After the existing `detail.setOnViewHex(...)` line:
```java
detail.setOnLaunchEmulator(this::launchEmulator);
```

**Step 6: Add imports to MainWindow**

```java
import com.github.daberkow.cybiko.manager.io.EmulatorConfig;
import com.github.daberkow.cybiko.manager.io.EmulatorLauncher;
import com.github.daberkow.cybiko.manager.ui.EmulatorSettingsDialog;
```

**Step 7: Verify compilation and run all tests**

Run: `./gradlew :manager:build -q`
Expected: BUILD SUCCESSFUL, all tests pass.

**Step 8: Commit**

```bash
git add manager/src/main/java/com/github/daberkow/cybiko/manager/MainWindow.java \
       manager/src/main/java/com/github/daberkow/cybiko/manager/ui/DetailPane.java
git commit -m "feat(manager): wire Launch Emulator into NVRAM menu and detail pane"
```

---

### Task 5: Manual testing and final polish

**Step 1: Test the settings dialog**

Run: `./gradlew :manager:run`
- Open an NVRAM image
- Go to NVRAM → Emulator Settings...
- Verify all fields appear and radio fields enable/disable correctly
- Click OK, reopen dialog — verify settings persisted
- Check `~/.cybiko-manager/emulator.properties` was created

**Step 2: Test ROM resolution**

- Remove any saved ROM paths from emulator.properties
- Place ROM files in ./roms/ directory
- Click NVRAM → Launch Emulator
- Verify ROMs are found automatically
- If no ROMs in search path, verify file chooser appears

**Step 3: Test emulator JAR resolution**

- With no JAR configured, click Launch Emulator
- Verify the "not found" dialog appears with the GitHub download link
- Verify the link opens in browser
- Browse to select JAR, verify it saves to config

**Step 4: Test full launch flow**

- Configure ROM paths and JAR path
- Select an NVRAM, click Launch Emulator (both from menu and detail pane button)
- Verify emulator window opens with correct machine type
- Verify NVRAM is loaded correctly

**Step 5: Run full test suite**

Run: `./gradlew :manager:test -q`
Expected: All tests pass (existing + new EmulatorConfig + EmulatorLauncher tests).

**Step 6: Final commit**

```bash
git add -A
git commit -m "feat(manager): launch emulator from manager with configurable settings"
```
