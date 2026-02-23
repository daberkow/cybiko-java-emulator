package com.github.daberkow.cybiko.manager.ui;

import com.github.daberkow.cybiko.manager.model.LibraryFolder;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;

/**
 * Dialog for adding a library folder with label and category.
 */
public class LibraryFolderDialog extends Dialog<LibraryFolder> {

    private final Window owner;
    private Path selectedPath;
    private final Label pathLabel = new Label("No folder selected");
    private final TextField labelField = new TextField();
    private final TextField categoryField = new TextField();

    public LibraryFolderDialog(Window owner) {
        this.owner = owner;
        setTitle("Add Library Folder");

        DialogPane dialogPane = getDialogPane();
        dialogPane.getStyleClass().add("dialog-pane");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));

        Button browseBtn = new Button("Browse...");
        browseBtn.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Select Library Folder");
            File dir = chooser.showDialog(owner);
            if (dir != null) {
                selectedPath = dir.toPath();
                pathLabel.setText(selectedPath.toString());
                if (labelField.getText().isEmpty()) {
                    labelField.setText(dir.getName());
                }
                if (categoryField.getText().isEmpty()) {
                    categoryField.setText(dir.getName().toLowerCase());
                }
            }
        });

        HBox pathBox = new HBox(8, pathLabel, browseBtn);
        HBox.setHgrow(pathLabel, Priority.ALWAYS);
        pathLabel.setMaxWidth(Double.MAX_VALUE);

        grid.add(new Label("Folder:"), 0, 0);
        grid.add(pathBox, 1, 0);
        grid.add(new Label("Label:"), 0, 1);
        grid.add(labelField, 1, 1);
        grid.add(new Label("Category:"), 0, 2);
        grid.add(categoryField, 1, 2);

        labelField.setPromptText("Display name");
        categoryField.setPromptText("Category tag");

        dialogPane.setContent(grid);
        dialogPane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // Disable OK until folder is selected
        dialogPane.lookupButton(ButtonType.OK).setDisable(true);
        pathLabel.textProperty().addListener((obs, oldVal, newVal) ->
            dialogPane.lookupButton(ButtonType.OK).setDisable(selectedPath == null)
        );

        setResultConverter(button -> {
            if (button == ButtonType.OK && selectedPath != null) {
                String label = labelField.getText().isBlank()
                    ? selectedPath.getFileName().toString()
                    : labelField.getText();
                String category = categoryField.getText().isBlank()
                    ? label.toLowerCase()
                    : categoryField.getText();
                return new LibraryFolder(selectedPath, label, category);
            }
            return null;
        });
    }
}
