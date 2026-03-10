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
