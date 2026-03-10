package com.github.daberkow.cybiko.manager.ui;

import com.github.daberkow.cybiko.manager.cfs.CfsImage;
import com.github.daberkow.cybiko.manager.model.ContentItem.LibraryItem;
import com.github.daberkow.cybiko.manager.model.LibraryFolder;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
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

        @Override
        public String toString() {
            return displayName() + " (" + sizeInfo() + ")";
        }
    }

    /** Smart list virtual folder types. */
    public enum SmartListType {
        RECENTLY_ADDED("Recently Added"),
        NOT_IN_NVRAM("Not in Any NVRAM");

        private final String label;
        SmartListType(String label) { this.label = label; }
        public String label() { return label; }
        @Override public String toString() { return label; }
    }

    private final ObservableList<ImageEntry> nvramEntries = FXCollections.observableArrayList();
    private final ListView<ImageEntry> nvramList = new ListView<>(nvramEntries);

    // Track unsaved adds/removes per image (keyed by identity, not equals)
    private final Map<CfsImage, int[]> changeCounts = new IdentityHashMap<>();

    private final ObservableList<LibraryFolder> libraryFolders = FXCollections.observableArrayList();
    private final ListView<LibraryFolder> libraryList = new ListView<>(libraryFolders);

    private final ObservableList<SmartListType> smartListItems =
        FXCollections.observableArrayList(SmartListType.RECENTLY_ADDED, SmartListType.NOT_IN_NVRAM);
    private final ListView<SmartListType> smartList = new ListView<>(smartListItems);

    private boolean suppressSelection = false;

    private Consumer<ImageEntry> onNvramSelected;
    private Consumer<ImageEntry> onCloseNvram;
    private Runnable onLaunchEmulator;
    private Runnable onRefreshNvram;
    private Consumer<LibraryFolder> onLibrarySelected;
    private Runnable onAddLibraryFolder;
    private Consumer<LibraryFolder> onRemoveLibraryFolder;
    private BiConsumer<List<LibraryItem>, ImageEntry> onDropLibraryItems;
    private Consumer<SmartListType> onSmartListSelected;

    public SidebarPane() {
        setPrefWidth(200);
        setMinWidth(150);
        getStyleClass().add("sidebar");

        // --- NVRAM section ---
        Label nvramHeader = new Label("NVRAM IMAGES");
        nvramHeader.getStyleClass().add("section-header");
        nvramHeader.setMaxWidth(Double.MAX_VALUE);

        nvramList.setCellFactory(lv -> {
            ListCell<ImageEntry> cell = new ListCell<>() {
                @Override
                protected void updateItem(ImageEntry item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setGraphic(null);
                    } else {
                        HBox row = new HBox(8);
                        row.setAlignment(Pos.CENTER_LEFT);

                        Region dot = new Region();
                        dot.getStyleClass().add(item.image().isModified() ? "dot-modified" : "dot-saved");
                        dot.setMinSize(8, 8);
                        dot.setMaxSize(8, 8);

                        VBox box = new VBox(2);
                        Label name = new Label(item.displayName());
                        name.getStyleClass().add("detail-value");

                        // Build info line with size and unsaved change counts
                        StringBuilder infoText = new StringBuilder(item.sizeInfo());
                        int[] counts = changeCounts.get(item.image());
                        if (counts != null && (counts[0] > 0 || counts[1] > 0)) {
                            infoText.append("  ");
                            if (counts[0] > 0) infoText.append("+").append(counts[0]);
                            if (counts[0] > 0 && counts[1] > 0) infoText.append(" ");
                            if (counts[1] > 0) infoText.append("-").append(counts[1]);
                        }
                        Label info = new Label(infoText.toString());
                        info.getStyleClass().add("detail-label");
                        box.getChildren().addAll(name, info);

                        row.getChildren().addAll(dot, box);
                        setGraphic(row);
                    }
                }
            };

            // Drop target for library items
            cell.setOnDragOver(event -> {
                if (event.getGestureSource() != cell
                        && event.getDragboard().hasContent(ContentListPane.LIBRARY_ITEMS)
                        && cell.getItem() != null) {
                    event.acceptTransferModes(TransferMode.COPY);
                }
                event.consume();
            });
            cell.setOnDragEntered(event -> {
                if (event.getDragboard().hasContent(ContentListPane.LIBRARY_ITEMS)
                        && cell.getItem() != null) {
                    cell.getStyleClass().add("drop-target");
                }
                event.consume();
            });
            cell.setOnDragExited(event -> {
                cell.getStyleClass().remove("drop-target");
                event.consume();
            });
            cell.setOnDragDropped(event -> {
                List<LibraryItem> items = ContentListPane.getDraggedItems();
                if (items != null && !items.isEmpty() && cell.getItem() != null
                        && onDropLibraryItems != null) {
                    onDropLibraryItems.accept(items, cell.getItem());
                    event.setDropCompleted(true);
                } else {
                    event.setDropCompleted(false);
                }
                event.consume();
            });

            ContextMenu contextMenu = new ContextMenu();
            MenuItem launchItem = new MenuItem("Launch Emulator...");
            launchItem.setOnAction(e -> {
                if (onLaunchEmulator != null) onLaunchEmulator.run();
            });
            MenuItem refreshItem = new MenuItem("Refresh from Disk");
            refreshItem.setOnAction(e -> {
                if (onRefreshNvram != null) onRefreshNvram.run();
            });
            MenuItem closeItem = new MenuItem("Close");
            closeItem.setOnAction(e -> {
                ImageEntry entry = cell.getItem();
                if (entry != null && onCloseNvram != null) {
                    onCloseNvram.accept(entry);
                }
            });
            contextMenu.getItems().addAll(launchItem, refreshItem, new SeparatorMenuItem(), closeItem);
            cell.setContextMenu(contextMenu);

            return cell;
        });

        nvramList.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (suppressSelection) return;
            if (newVal != null) {
                suppressSelection = true;
                libraryList.getSelectionModel().clearSelection();
                smartList.getSelectionModel().clearSelection();
                suppressSelection = false;
                if (onNvramSelected != null) onNvramSelected.accept(newVal);
            }
        });

        // --- SMART LISTS section ---
        Label smartHeader = new Label("SMART LISTS");
        smartHeader.getStyleClass().add("section-header");
        smartHeader.setMaxWidth(Double.MAX_VALUE);

        smartList.setPrefHeight(56);
        smartList.setMinHeight(56);
        smartList.setMaxHeight(56);

        smartList.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (suppressSelection) return;
            if (newVal != null) {
                suppressSelection = true;
                nvramList.getSelectionModel().clearSelection();
                libraryList.getSelectionModel().clearSelection();
                suppressSelection = false;
                if (onSmartListSelected != null) onSmartListSelected.accept(newVal);
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
                smartList.getSelectionModel().clearSelection();
                suppressSelection = false;
                if (onLibrarySelected != null) onLibrarySelected.accept(newVal);
            }
        });

        VBox.setVgrow(nvramList, Priority.ALWAYS);
        VBox.setVgrow(libraryList, Priority.ALWAYS);

        getChildren().addAll(nvramHeader, nvramList, smartHeader, smartList, libraryHeaderBox, libraryList);
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

    /** Record that a file was added to the given image. */
    public void recordAdd(CfsImage image) {
        changeCounts.computeIfAbsent(image, k -> new int[2])[0]++;
        refreshEntry(image);
    }

    /** Record that files were removed from the given image. */
    public void recordRemove(CfsImage image, int count) {
        changeCounts.computeIfAbsent(image, k -> new int[2])[1] += count;
        refreshEntry(image);
    }

    /** Clear change counts for an image (e.g., after saving). */
    public void clearChangeCounts(CfsImage image) {
        changeCounts.remove(image);
        refreshEntry(image);
    }

    /** Refresh the sidebar display for a specific image. */
    public void refreshEntry(CfsImage image) {
        for (int i = 0; i < nvramEntries.size(); i++) {
            ImageEntry entry = nvramEntries.get(i);
            if (entry.image() == image) {
                nvramEntries.set(i, new ImageEntry(entry.path(), entry.image(), entry.customName()));
                return;
            }
        }
    }

    /** Replace an NVRAM image in-place (e.g. after reloading from disk). */
    public void replaceImage(CfsImage oldImage, CfsImage newImage, Path path) {
        changeCounts.remove(oldImage);
        for (int i = 0; i < nvramEntries.size(); i++) {
            if (nvramEntries.get(i).image() == oldImage) {
                nvramEntries.set(i, new ImageEntry(path, newImage));
                nvramList.getSelectionModel().select(i);
                return;
            }
        }
    }

    /** Remove an NVRAM image from the sidebar and clean up change counts. */
    public void removeImage(CfsImage image) {
        changeCounts.remove(image);
        nvramEntries.removeIf(e -> e.image() == image);
    }

    public void setOnCloseNvram(Consumer<ImageEntry> callback) {
        this.onCloseNvram = callback;
    }

    public void setOnLaunchEmulator(Runnable callback) {
        this.onLaunchEmulator = callback;
    }

    public void setOnRefreshNvram(Runnable callback) {
        this.onRefreshNvram = callback;
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

    // --- Smart list methods ---

    public void setOnSmartListSelected(Consumer<SmartListType> callback) {
        this.onSmartListSelected = callback;
    }

    // --- Drag-and-drop ---

    public void setOnDropLibraryItems(BiConsumer<List<LibraryItem>, ImageEntry> callback) {
        this.onDropLibraryItems = callback;
    }
}
