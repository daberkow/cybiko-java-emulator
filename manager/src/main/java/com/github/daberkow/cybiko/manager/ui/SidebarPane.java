package com.github.daberkow.cybiko.manager.ui;

import com.github.daberkow.cybiko.manager.cfs.CfsImage;
import com.github.daberkow.cybiko.manager.model.LibraryFolder;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Left sidebar showing opened NVRAM images and library folders.
 */
public class SidebarPane extends VBox {

    public record ImageEntry(Path path, CfsImage image, String customName) {
        public ImageEntry(Path path, CfsImage image) {
            this(path, image, null);
        }
        public String displayName() {
            if (path != null) return path.getFileName().toString();
            if (customName != null && !customName.isEmpty()) return customName;
            return "Untitled";
        }
        public String sizeInfo() {
            long used = image.usedSpace();
            long total = image.totalCapacity();
            return String.format("%d KB / %d KB", used / 1024, total / 1024);
        }
    }

    private final ObservableList<ImageEntry> nvramEntries = FXCollections.observableArrayList();
    private final ListView<ImageEntry> nvramList = new ListView<>(nvramEntries);

    private final ObservableList<LibraryFolder> libraryFolders = FXCollections.observableArrayList();
    private final ListView<LibraryFolder> libraryList = new ListView<>(libraryFolders);

    private boolean suppressSelection = false;

    private Consumer<ImageEntry> onNvramSelected;
    private Consumer<LibraryFolder> onLibrarySelected;
    private Runnable onAddLibraryFolder;
    private Consumer<LibraryFolder> onRemoveLibraryFolder;

    public SidebarPane() {
        setPrefWidth(200);
        setMinWidth(150);

        // --- NVRAM section ---
        Label nvramHeader = new Label("NVRAM IMAGES");
        nvramHeader.getStyleClass().add("section-header");
        nvramHeader.setMaxWidth(Double.MAX_VALUE);

        nvramList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ImageEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                } else {
                    VBox cell = new VBox(2);
                    Label name = new Label(item.displayName());
                    name.getStyleClass().add("detail-value");
                    Label info = new Label(item.sizeInfo());
                    info.getStyleClass().add("detail-label");
                    cell.getChildren().addAll(name, info);
                    setGraphic(cell);
                }
            }
        });

        nvramList.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (suppressSelection) return;
            if (newVal != null) {
                suppressSelection = true;
                libraryList.getSelectionModel().clearSelection();
                suppressSelection = false;
                if (onNvramSelected != null) onNvramSelected.accept(newVal);
            }
        });

        // --- LIBRARY section ---
        Label libraryHeader = new Label("LIBRARY");
        libraryHeader.getStyleClass().add("section-header");

        Button addButton = new Button("+");
        addButton.getStyleClass().add("add-button");
        addButton.setOnAction(e -> {
            if (onAddLibraryFolder != null) onAddLibraryFolder.run();
        });

        HBox libraryHeaderBox = new HBox();
        libraryHeaderBox.getChildren().addAll(libraryHeader, addButton);
        HBox.setHgrow(libraryHeader, Priority.ALWAYS);
        libraryHeader.setMaxWidth(Double.MAX_VALUE);

        libraryList.setCellFactory(lv -> {
            ListCell<LibraryFolder> cell = new ListCell<>() {
                @Override
                protected void updateItem(LibraryFolder item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setGraphic(null);
                    } else {
                        VBox box = new VBox(2);
                        Label name = new Label(item.label());
                        name.getStyleClass().add("detail-value");
                        Label path = new Label(item.path().toString());
                        path.getStyleClass().add("detail-label");
                        box.getChildren().addAll(name, path);
                        setGraphic(box);
                    }
                }
            };

            ContextMenu contextMenu = new ContextMenu();
            MenuItem removeItem = new MenuItem("Remove Folder");
            removeItem.setOnAction(e -> {
                LibraryFolder folder = cell.getItem();
                if (folder != null && onRemoveLibraryFolder != null) {
                    onRemoveLibraryFolder.accept(folder);
                }
            });
            contextMenu.getItems().add(removeItem);
            cell.setContextMenu(contextMenu);

            return cell;
        });

        libraryList.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (suppressSelection) return;
            if (newVal != null) {
                suppressSelection = true;
                nvramList.getSelectionModel().clearSelection();
                suppressSelection = false;
                if (onLibrarySelected != null) onLibrarySelected.accept(newVal);
            }
        });

        VBox.setVgrow(nvramList, Priority.ALWAYS);
        VBox.setVgrow(libraryList, Priority.ALWAYS);

        getChildren().addAll(nvramHeader, nvramList, libraryHeaderBox, libraryList);
    }

    // --- NVRAM methods ---

    public void addImage(Path path, CfsImage image) {
        nvramEntries.add(new ImageEntry(path, image));
        nvramList.getSelectionModel().selectLast();
    }

    public void addImage(Path path, CfsImage image, String customName) {
        nvramEntries.add(new ImageEntry(path, image, customName));
        nvramList.getSelectionModel().selectLast();
    }

    public void setOnSelectionChanged(Consumer<ImageEntry> callback) {
        this.onNvramSelected = callback;
    }

    public ImageEntry getSelectedEntry() {
        return nvramList.getSelectionModel().getSelectedItem();
    }

    public void updateSelected(Path newPath) {
        int idx = nvramList.getSelectionModel().getSelectedIndex();
        if (idx >= 0) {
            ImageEntry old = nvramEntries.get(idx);
            nvramEntries.set(idx, new ImageEntry(newPath, old.image()));
        }
    }

    /** Refresh the display of the currently selected NVRAM entry (e.g., after file changes). */
    public void refreshSelectedEntry() {
        int idx = nvramList.getSelectionModel().getSelectedIndex();
        if (idx >= 0) {
            ImageEntry entry = nvramEntries.get(idx);
            nvramEntries.set(idx, new ImageEntry(entry.path(), entry.image(), entry.customName()));
        }
    }

    /** Select the NVRAM entry if it exists. */
    public void selectNvramEntry(CfsImage image) {
        for (int i = 0; i < nvramEntries.size(); i++) {
            if (nvramEntries.get(i).image() == image) {
                nvramList.getSelectionModel().select(i);
                return;
            }
        }
    }

    /** Get all NVRAM entries for unsaved changes check. */
    public List<ImageEntry> getNvramEntries() {
        return List.copyOf(nvramEntries);
    }

    // --- Library methods ---

    public void setLibraryFolders(List<LibraryFolder> folders) {
        libraryFolders.setAll(folders);
    }

    public void setOnLibrarySelected(Consumer<LibraryFolder> callback) {
        this.onLibrarySelected = callback;
    }

    public void setOnAddLibraryFolder(Runnable callback) {
        this.onAddLibraryFolder = callback;
    }

    public void setOnRemoveLibraryFolder(Consumer<LibraryFolder> callback) {
        this.onRemoveLibraryFolder = callback;
    }

    public LibraryFolder getSelectedLibraryFolder() {
        return libraryList.getSelectionModel().getSelectedItem();
    }
}
