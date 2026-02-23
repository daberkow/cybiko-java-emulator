package com.github.daberkow.cybiko.manager.ui;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Non-modal hex viewer window with virtualized ListView and copy support.
 */
public class HexViewerDialog extends Stage {

    private static final int BYTES_PER_ROW = 16;

    private final byte[] data;
    private final ListView<Integer> listView;

    public HexViewerDialog(String title, byte[] data) {
        this.data = data;

        setTitle("Hex Viewer - " + title);

        int rowCount = (data.length + BYTES_PER_ROW - 1) / BYTES_PER_ROW;

        listView = new ListView<>();
        listView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        for (int i = 0; i < rowCount; i++) {
            listView.getItems().add(i);
        }

        listView.setCellFactory(lv -> new ListCell<>() {
            {
                getStyleClass().add("hex-viewer-cell");
            }

            @Override
            protected void updateItem(Integer rowIndex, boolean empty) {
                super.updateItem(rowIndex, empty);
                if (empty || rowIndex == null) {
                    setText(null);
                } else {
                    setText(formatRow(rowIndex));
                }
            }
        });

        listView.setFixedCellSize(20);

        // Go to offset controls
        TextField offsetField = new TextField();
        offsetField.setPromptText("Hex offset");
        offsetField.setPrefWidth(120);
        offsetField.getStyleClass().add("search-field");

        Button goBtn = new Button("Go");
        goBtn.setOnAction(e -> goToOffset(offsetField.getText()));
        offsetField.setOnAction(e -> goToOffset(offsetField.getText()));

        Button copyBtn = new Button("Copy");
        copyBtn.getStyleClass().add("action-button");
        copyBtn.setOnAction(e -> copySelection());

        Label sizeLabel = new Label(String.format("Size: %,d bytes (0x%X)", data.length, data.length));
        sizeLabel.getStyleClass().add("capacity-label");

        HBox toolbar = new HBox(8, new Label("Offset:"), offsetField, goBtn, copyBtn, sizeLabel);
        toolbar.setPadding(new Insets(8));
        toolbar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        VBox root = new VBox(toolbar, listView);
        VBox.setVgrow(listView, Priority.ALWAYS);

        Scene scene = new Scene(root, 700, 500);

        // Ctrl+C / Cmd+C keyboard shortcut
        scene.getAccelerators().put(
            new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN),
            this::copySelection
        );

        // Try to load the same CSS as the main window
        String css = getClass().getResource("/com/github/daberkow/cybiko/manager/css/dark-theme.css") != null
            ? getClass().getResource("/com/github/daberkow/cybiko/manager/css/dark-theme.css").toExternalForm()
            : null;
        if (css != null) {
            scene.getStylesheets().add(css);
        }

        setScene(scene);
    }

    private void copySelection() {
        List<Integer> selected = listView.getSelectionModel().getSelectedItems();
        if (selected.isEmpty()) return;

        String text = selected.stream()
            .map(this::formatRow)
            .collect(Collectors.joining("\n"));

        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private String formatRow(int rowIndex) {
        int offset = rowIndex * BYTES_PER_ROW;
        StringBuilder hex = new StringBuilder();
        StringBuilder ascii = new StringBuilder();

        for (int i = 0; i < BYTES_PER_ROW; i++) {
            int pos = offset + i;
            if (pos < data.length) {
                int b = data[pos] & 0xFF;
                hex.append(String.format("%02X ", b));
                ascii.append(b >= 0x20 && b <= 0x7E ? (char) b : '.');
            } else {
                hex.append("   ");
                ascii.append(' ');
            }
            if (i == 7) hex.append(' ');
        }

        return String.format("%08X  %-49s |%-16s|", offset, hex.toString(), ascii.toString());
    }

    private void goToOffset(String text) {
        if (text == null || text.isBlank()) return;
        try {
            String cleaned = text.strip();
            if (cleaned.startsWith("0x") || cleaned.startsWith("0X")) {
                cleaned = cleaned.substring(2);
            }
            int offset = Integer.parseInt(cleaned, 16);
            int row = offset / BYTES_PER_ROW;
            if (row >= 0 && row < listView.getItems().size()) {
                listView.scrollTo(row);
                listView.getSelectionModel().select(row);
            }
        } catch (NumberFormatException e) {
            // ignore invalid input
        }
    }
}
