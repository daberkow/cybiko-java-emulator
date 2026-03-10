package com.github.daberkow.cybiko.manager.ui;

import com.github.daberkow.cybiko.manager.io.EmulatorConfig;
import com.github.daberkow.cybiko.manager.io.EmulatorLauncher;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Pre-launch dialog showing ROM/JAR paths and emulator options.
 * Returns a LaunchParams record on Launch, or null on cancel.
 */
public class LaunchEmulatorDialog extends Dialog<LaunchEmulatorDialog.LaunchParams> {

    public record LaunchParams(Path emulatorJar, Path bootRom, Path flashRom,
                               EmulatorConfig config) {}

    public LaunchEmulatorDialog(String machineType, Path nvramPath,
                                EmulatorConfig config, Window owner) {
        setTitle("Launch Emulator");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(16));

        int row = 0;

        // --- Machine info (read-only) ---
        Label machineLabel = new Label("Machine:");
        Label machineValue = new Label(machineType.toUpperCase());
        machineValue.setStyle("-fx-font-weight: bold;");
        grid.add(machineLabel, 0, row);
        grid.add(machineValue, 1, row);
        row++;

        Label nvramLabel = new Label("NVRAM:");
        Label nvramValue = new Label(nvramPath.getFileName().toString());
        nvramValue.setStyle("-fx-font-style: italic;");
        grid.add(nvramLabel, 0, row);
        grid.add(nvramValue, 1, row);
        row++;

        // --- Separator ---
        grid.add(new Separator(), 0, row, 2, 1);
        row++;

        // --- ROMs header ---
        Label romsHeader = new Label("ROM Files");
        romsHeader.setStyle("-fx-font-weight: bold;");
        grid.add(romsHeader, 0, row, 2, 1);
        row++;

        // --- Boot ROM ---
        Label bootLabel = new Label("Boot ROM:");
        TextField bootField = new TextField();
        bootField.setPrefWidth(350);
        bootField.setPromptText(EmulatorLauncher.defaultBootRomName(machineType));
        Button bootBrowse = new Button("Browse...");
        bootBrowse.setOnAction(e -> browseRom(bootField, "Boot ROM", owner));

        String resolvedBoot = resolveRomPath(machineType, true, config);
        if (resolvedBoot != null) bootField.setText(resolvedBoot);

        HBox bootBox = new HBox(8, bootField, bootBrowse);
        HBox.setHgrow(bootField, Priority.ALWAYS);
        grid.add(bootLabel, 0, row);
        grid.add(bootBox, 1, row);
        row++;

        // --- Flash ROM ---
        Label flashLabel = new Label("Flash ROM:");
        TextField flashField = new TextField();
        flashField.setPrefWidth(350);
        flashField.setPromptText(EmulatorLauncher.defaultFlashRomName(machineType));
        Button flashBrowse = new Button("Browse...");
        flashBrowse.setOnAction(e -> browseRom(flashField, "Flash ROM", owner));

        String resolvedFlash = resolveRomPath(machineType, false, config);
        if (resolvedFlash != null) flashField.setText(resolvedFlash);

        HBox flashBox = new HBox(8, flashField, flashBrowse);
        HBox.setHgrow(flashField, Priority.ALWAYS);
        grid.add(flashLabel, 0, row);
        grid.add(flashBox, 1, row);
        row++;

        // --- Separator ---
        grid.add(new Separator(), 0, row, 2, 1);
        row++;

        // --- Emulator JAR ---
        Label jarHeader = new Label("Emulator");
        jarHeader.setStyle("-fx-font-weight: bold;");
        grid.add(jarHeader, 0, row, 2, 1);
        row++;

        Label jarLabel = new Label("Emulator JAR:");
        TextField jarField = new TextField();
        jarField.setPrefWidth(350);
        Button jarBrowse = new Button("Browse...");
        jarBrowse.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Locate Emulator JAR");
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("JAR Files", "*.jar"));
            java.io.File file = chooser.showOpenDialog(owner);
            if (file != null) jarField.setText(file.getAbsolutePath());
        });

        String resolvedJar = resolveJarPath(config);
        if (resolvedJar != null) jarField.setText(resolvedJar);

        HBox jarBox = new HBox(8, jarField, jarBrowse);
        HBox.setHgrow(jarField, Priority.ALWAYS);
        grid.add(jarLabel, 0, row);
        grid.add(jarBox, 1, row);
        row++;

        Hyperlink downloadLink = new Hyperlink("Download from GitHub Releases");
        downloadLink.setOnAction(e -> {
            try {
                java.awt.Desktop.getDesktop().browse(
                        java.net.URI.create(EmulatorLauncher.RELEASES_URL));
            } catch (Exception ignored) {}
        });
        grid.add(downloadLink, 1, row);
        row++;

        // --- Separator ---
        grid.add(new Separator(), 0, row, 2, 1);
        row++;

        // --- General options ---
        Label generalHeader = new Label("Options");
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

        // --- Dialog buttons ---
        ScrollPane scrollPane = new ScrollPane(grid);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefViewportHeight(500);
        getDialogPane().setContent(scrollPane);

        ButtonType launchBtn = new ButtonType("Launch", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(launchBtn, ButtonType.CANCEL);

        // Disable Launch button until all required fields are filled
        Button launchButton = (Button) getDialogPane().lookupButton(launchBtn);
        Runnable updateLaunchBtn = () -> {
            boolean valid = !bootField.getText().trim().isEmpty()
                    && !flashField.getText().trim().isEmpty()
                    && !jarField.getText().trim().isEmpty();
            launchButton.setDisable(!valid);
        };
        bootField.textProperty().addListener((o, ov, nv) -> updateLaunchBtn.run());
        flashField.textProperty().addListener((o, ov, nv) -> updateLaunchBtn.run());
        jarField.textProperty().addListener((o, ov, nv) -> updateLaunchBtn.run());
        updateLaunchBtn.run();

        setResultConverter(button -> {
            if (button != launchBtn) return null;
            String jar = jarField.getText().trim();
            String boot = bootField.getText().trim();
            String flash = flashField.getText().trim();
            if (jar.isEmpty() || boot.isEmpty() || flash.isEmpty()) return null;

            // Build updated config from dialog fields
            EmulatorConfig updated = new EmulatorConfig();
            updated.setEmulatorJarPath(jar);
            updated.setMute(muteBox.isSelected());
            updated.setHeadless(headlessBox.isSelected());
            updated.setTrace(traceBox.isSelected());
            updated.setLogging(loggingField.getText().trim());
            updated.setRadioMode(radioCombo.getValue());
            updated.setRadioId(idField.getText().trim());
            updated.setSdrHost(sdrHostField.getText().trim());
            updated.setSdrPort(sdrPortField.getText().trim());
            // Preserve ROM paths from incoming config
            updated.setXtBootRom(config.getXtBootRom());
            updated.setXtFlashRom(config.getXtFlashRom());
            updated.setV1BootRom(config.getV1BootRom());
            updated.setV1FlashRom(config.getV1FlashRom());
            updated.setV2BootRom(config.getV2BootRom());
            updated.setV2FlashRom(config.getV2FlashRom());

            return new LaunchParams(Path.of(jar), Path.of(boot), Path.of(flash), updated);
        });
    }

    private void browseRom(TextField field, String title, Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Locate " + title);
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("ROM Files", "*.bin"));
        java.io.File file = chooser.showOpenDialog(owner);
        if (file != null) field.setText(file.getAbsolutePath());
    }

    private static String resolveRomPath(String machineType, boolean isBootRom,
                                         EmulatorConfig config) {
        String saved = isBootRom ? getBootRomPath(machineType, config)
                : getFlashRomPath(machineType, config);
        if (!saved.isEmpty() && Files.isRegularFile(Path.of(saved))) return saved;

        String filename = isBootRom
                ? EmulatorLauncher.defaultBootRomName(machineType)
                : EmulatorLauncher.defaultFlashRomName(machineType);
        List<Path> searchDirs = List.of(
                Path.of("."),
                Path.of("roms"),
                Path.of("..", "roms")
        );
        Path found = EmulatorLauncher.searchRom(filename, searchDirs);
        return found != null ? found.toString() : null;
    }

    private static String resolveJarPath(EmulatorConfig config) {
        String saved = config.getEmulatorJarPath();
        if (!saved.isEmpty() && Files.isRegularFile(Path.of(saved))) return saved;

        List<Path> searchDirs = List.of(
                Path.of("."),
                Path.of("..", "emulator", "build", "libs")
        );
        Path found = EmulatorLauncher.searchEmulatorJar(searchDirs);
        return found != null ? found.toString() : null;
    }

    private static String getBootRomPath(String machine, EmulatorConfig config) {
        return switch (machine) {
            case "v1" -> config.getV1BootRom();
            case "v2" -> config.getV2BootRom();
            default -> config.getXtBootRom();
        };
    }

    private static String getFlashRomPath(String machine, EmulatorConfig config) {
        return switch (machine) {
            case "v1" -> config.getV1FlashRom();
            case "v2" -> config.getV2FlashRom();
            default -> config.getXtFlashRom();
        };
    }
}
