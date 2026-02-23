package com.github.daberkow.cybiko.manager.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Non-modal hex viewer window with virtualized ListView and copy support.
 */
public class HexViewerDialog extends Stage {

    private static final int BYTES_PER_ROW = 16;

    private final byte[] data;
    private final ListView<Integer> listView;

    // Find bar
    private final HBox findBar = new HBox(8);
    private final TextField findField = new TextField();
    private final ToggleButton hexModeBtn = new ToggleButton("Hex");
    private final Label matchLabel = new Label();
    private final List<Integer> matchRows = new ArrayList<>();
    private int matchIndex = -1;
    private boolean findBarVisible = false;

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
        goBtn.getStyleClass().add("action-button-secondary");
        goBtn.setOnAction(e -> goToOffset(offsetField.getText()));
        offsetField.setOnAction(e -> goToOffset(offsetField.getText()));

        Button copyBtn = new Button("Copy");
        copyBtn.getStyleClass().add("action-button-secondary");
        copyBtn.setOnAction(e -> copySelection());

        Label sizeLabel = new Label(String.format("Size: %,d bytes (0x%X)", data.length, data.length));
        sizeLabel.getStyleClass().add("capacity-label");

        HBox toolbar = new HBox(8, new Label("Offset:"), offsetField, goBtn, copyBtn, sizeLabel);
        toolbar.getStyleClass().add("hex-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);

        // Find bar
        findField.setPromptText("Search hex (A0 FF) or text...");
        findField.getStyleClass().add("search-field");
        HBox.setHgrow(findField, Priority.ALWAYS);
        findField.setOnAction(e -> findNext());
        findField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) toggleFindBar();
        });

        hexModeBtn.setSelected(true);
        hexModeBtn.getStyleClass().add("action-button-secondary");

        Button findPrevBtn = new Button("<");
        findPrevBtn.getStyleClass().add("action-button-secondary");
        findPrevBtn.setOnAction(e -> findPrev());

        Button findNextBtn = new Button(">");
        findNextBtn.getStyleClass().add("action-button-secondary");
        findNextBtn.setOnAction(e -> findNext());

        Button findCloseBtn = new Button("X");
        findCloseBtn.getStyleClass().add("search-toggle");
        findCloseBtn.setOnAction(e -> toggleFindBar());

        matchLabel.getStyleClass().add("detail-label");

        findBar.getStyleClass().add("find-bar");
        findBar.setAlignment(Pos.CENTER_LEFT);
        findBar.getChildren().addAll(findField, hexModeBtn, findPrevBtn, findNextBtn, matchLabel, findCloseBtn);

        VBox root = new VBox(toolbar, listView);
        VBox.setVgrow(listView, Priority.ALWAYS);

        Scene scene = new Scene(root, 700, 500);

        // Ctrl+C / Cmd+C keyboard shortcut
        scene.getAccelerators().put(
            new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN),
            this::copySelection
        );

        // Ctrl+F / Cmd+F to toggle find bar
        scene.getAccelerators().put(
            new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN),
            this::toggleFindBar
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

    private void toggleFindBar() {
        findBarVisible = !findBarVisible;
        VBox root = (VBox) getScene().getRoot();
        if (findBarVisible) {
            if (!root.getChildren().contains(findBar)) {
                root.getChildren().add(1, findBar); // between toolbar and list
            }
            findField.requestFocus();
        } else {
            root.getChildren().remove(findBar);
            findField.clear();
            matchRows.clear();
            matchIndex = -1;
            matchLabel.setText("");
            listView.refresh(); // clear highlights
        }
    }

    private void findNext() {
        performSearch();
        if (matchRows.isEmpty()) return;
        matchIndex = (matchIndex + 1) % matchRows.size();
        goToMatch();
    }

    private void findPrev() {
        performSearch();
        if (matchRows.isEmpty()) return;
        matchIndex = matchIndex <= 0 ? matchRows.size() - 1 : matchIndex - 1;
        goToMatch();
    }

    private void goToMatch() {
        int row = matchRows.get(matchIndex);
        listView.scrollTo(row);
        listView.getSelectionModel().clearSelection();
        listView.getSelectionModel().select(row);
        matchLabel.setText((matchIndex + 1) + " / " + matchRows.size());
    }

    private void performSearch() {
        String query = findField.getText();
        if (query == null || query.isBlank()) {
            matchRows.clear();
            matchLabel.setText("");
            return;
        }

        matchRows.clear();
        matchIndex = -1;

        if (hexModeBtn.isSelected()) {
            byte[] pattern = parseHexPattern(query);
            if (pattern == null || pattern.length == 0) return;
            for (int i = 0; i <= data.length - pattern.length; i++) {
                boolean match = true;
                for (int j = 0; j < pattern.length; j++) {
                    if (data[i + j] != pattern[j]) { match = false; break; }
                }
                if (match) matchRows.add(i / BYTES_PER_ROW);
            }
        } else {
            byte[] pattern = query.getBytes(StandardCharsets.US_ASCII);
            for (int i = 0; i <= data.length - pattern.length; i++) {
                boolean match = true;
                for (int j = 0; j < pattern.length; j++) {
                    if (data[i + j] != pattern[j]) { match = false; break; }
                }
                if (match) matchRows.add(i / BYTES_PER_ROW);
            }
        }

        // Deduplicate rows (multiple matches in same row)
        LinkedHashSet<Integer> unique = new LinkedHashSet<>(matchRows);
        matchRows.clear();
        matchRows.addAll(unique);

        matchLabel.setText(matchRows.isEmpty() ? "No matches" : matchRows.size() + " matches");
    }

    private byte[] parseHexPattern(String hex) {
        String cleaned = hex.replaceAll("[\\s,]+", "");
        if (cleaned.length() % 2 != 0) return null;
        byte[] result = new byte[cleaned.length() / 2];
        try {
            for (int i = 0; i < result.length; i++) {
                result[i] = (byte) Integer.parseInt(cleaned.substring(i * 2, i * 2 + 2), 16);
            }
            return result;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
