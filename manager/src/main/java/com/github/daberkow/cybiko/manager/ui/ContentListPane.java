package com.github.daberkow.cybiko.manager.ui;

import com.github.daberkow.cybiko.manager.model.AppEntry;
import com.github.daberkow.cybiko.manager.model.CfsFile;
import com.github.daberkow.cybiko.manager.model.ContentItem;
import com.github.daberkow.cybiko.manager.model.ContentItem.LibraryItem;
import com.github.daberkow.cybiko.manager.model.ContentItem.NvramItem;
import com.github.daberkow.cybiko.manager.io.CybikoIconRenderer;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.KeyCode;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Center pane with file list table, search field, and multi-select support.
 */
public class ContentListPane extends VBox {

    static final DataFormat LIBRARY_ITEMS = new DataFormat("application/x-cybiko-library-items");
    private static List<LibraryItem> draggedItems;

    /** Get the items currently being dragged (same-JVM transfer). */
    public static List<LibraryItem> getDraggedItems() { return draggedItems; }

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Label breadcrumb = new Label("No image loaded");
    private final TextField searchField = new TextField();
    private final Button searchToggle = new Button("Search");
    private final Button searchClose = new Button("X");
    private final HBox headerBar = new HBox(8);
    private final HBox searchBar = new HBox(8);
    private boolean searchVisible = false;
    private final TableView<ContentItem> table = new TableView<>();
    private final ObservableList<ContentItem> items = FXCollections.observableArrayList();
    private final FilteredList<ContentItem> filteredItems = new FilteredList<>(items, p -> true);
    private final Map<Path, Image> iconCache = new HashMap<>();

    @SuppressWarnings("unchecked")
    public ContentListPane() {
        // Header bar with breadcrumb and search toggle
        breadcrumb.getStyleClass().add("breadcrumb");
        breadcrumb.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(breadcrumb, Priority.ALWAYS);

        searchToggle.getStyleClass().add("search-toggle");
        searchToggle.setOnAction(e -> toggleSearch());

        headerBar.setAlignment(Pos.CENTER_LEFT);
        headerBar.getChildren().addAll(breadcrumb, searchToggle);

        // Search bar (initially hidden)
        searchField.setPromptText("Search...");
        searchField.getStyleClass().add("search-field");
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            String filter = newVal == null ? "" : newVal.toLowerCase();
            filteredItems.setPredicate(item ->
                filter.isEmpty() || item.name().toLowerCase().contains(filter)
            );
        });
        searchField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) toggleSearch();
        });
        HBox.setHgrow(searchField, Priority.ALWAYS);

        searchClose.getStyleClass().add("search-toggle");
        searchClose.setOnAction(e -> toggleSearch());

        searchBar.setPadding(new Insets(4, 12, 4, 12));
        searchBar.setAlignment(Pos.CENTER_LEFT);
        searchBar.getChildren().addAll(searchField, searchClose);

        // Name column
        TableColumn<ContentItem, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(p -> new ReadOnlyStringWrapper(p.getValue().name()));
        nameCol.setPrefWidth(250);
        nameCol.setCellFactory(col -> new TableCell<>() {
            private final ImageView iv = new ImageView();
            private final Label label = new Label();
            private final HBox box = new HBox(4, iv, label);
            {
                iv.setFitWidth(24);
                iv.setFitHeight(24);
                iv.setPreserveRatio(true);
                iv.setSmooth(false);
                box.setAlignment(Pos.CENTER_LEFT);
            }
            @Override
            protected void updateItem(String name, boolean empty) {
                super.updateItem(name, empty);
                if (empty || name == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                label.setText(name);
                Image icon = null;
                int idx = getIndex();
                if (idx >= 0 && idx < getTableView().getItems().size()) {
                    var item = getTableView().getItems().get(idx);
                    if (item instanceof LibraryItem li && li.entry().iconData() != null) {
                        icon = iconCache.computeIfAbsent(li.entry().path(),
                            p -> CybikoIconRenderer.toImage(li.entry().iconData()));
                    }
                }
                if (icon != null) {
                    iv.setImage(icon);
                    setGraphic(box);
                    setText(null);
                } else {
                    iv.setImage(null);
                    setGraphic(null);
                    setText(name);
                }
            }
        });

        // Type column
        TableColumn<ContentItem, String> extCol = new TableColumn<>("Type");
        extCol.setCellValueFactory(p -> new ReadOnlyStringWrapper(p.getValue().extension()));
        extCol.setPrefWidth(80);

        // Size column
        TableColumn<ContentItem, String> sizeCol = new TableColumn<>("Size");
        sizeCol.setCellValueFactory(p -> {
            long bytes = p.getValue().sizeBytes();
            if (bytes < 1024) return new ReadOnlyStringWrapper(bytes + " B");
            return new ReadOnlyStringWrapper(String.format("%.1f KB", bytes / 1024.0));
        });
        sizeCol.setPrefWidth(80);

        // Status column
        TableColumn<ContentItem, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(p -> {
            ContentItem val = p.getValue();
            if (val instanceof LibraryItem li && li.inNvram()) {
                return new ReadOnlyStringWrapper(li.nvramStatus());
            }
            return new ReadOnlyStringWrapper(val.inNvram() ? "In NVRAM" : "");
        });
        statusCol.setCellFactory(col -> new TableCell<>() {
            private final Label badge = new Label();
            {
                badge.getStyleClass().add("badge-in-nvram");
            }
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.isEmpty()) {
                    setGraphic(null);
                } else {
                    badge.setText(item);
                    setGraphic(badge);
                }
            }
        });
        statusCol.setPrefWidth(120);

        // Date column
        TableColumn<ContentItem, String> dateCol = new TableColumn<>("Modified");
        dateCol.setCellValueFactory(p -> {
            try {
                return new ReadOnlyStringWrapper(p.getValue().lastModified().format(DATE_FMT));
            } catch (Exception e) {
                return new ReadOnlyStringWrapper("-");
            }
        });
        dateCol.setPrefWidth(140);

        SortedList<ContentItem> sortedList = new SortedList<>(filteredItems);
        sortedList.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sortedList);
        table.getColumns().addAll(nameCol, extCol, sizeCol, statusCol, dateCol);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No files"));

        // Drag source: only LibraryItems are draggable
        table.setOnDragDetected(event -> {
            List<LibraryItem> libs = table.getSelectionModel().getSelectedItems().stream()
                .filter(LibraryItem.class::isInstance)
                .map(LibraryItem.class::cast)
                .toList();
            if (libs.isEmpty()) return;

            draggedItems = libs;
            var db = table.startDragAndDrop(TransferMode.COPY);
            ClipboardContent cc = new ClipboardContent();
            cc.put(LIBRARY_ITEMS, libs.size() + " file(s)");
            db.setContent(cc);
            event.consume();
        });
        table.setOnDragDone(event -> {
            draggedItems = null;
            event.consume();
        });

        getChildren().addAll(headerBar, table);
        VBox.setVgrow(table, Priority.ALWAYS);
    }

    private void toggleSearch() {
        searchVisible = !searchVisible;
        if (searchVisible) {
            if (!getChildren().contains(searchBar)) {
                getChildren().add(1, searchBar); // between header and table
            }
            searchField.requestFocus();
        } else {
            getChildren().remove(searchBar);
            searchField.clear();
        }
    }

    public void showSearch() {
        if (!searchVisible) toggleSearch();
        else searchField.requestFocus();
    }

    /** Set content from NVRAM image files. */
    public void setNvramFiles(List<CfsFile> files, String imageName) {
        items.setAll(files.stream()
            .map(NvramItem::new)
            .toList());
        breadcrumb.setText(imageName + " - " + files.size() + " files");
        searchField.clear();
    }

    /** Set content from library folder entries. */
    public void setLibraryFiles(List<AppEntry> entries, String folderLabel,
                                Map<String, List<String>> nvramFileMap) {
        items.setAll(entries.stream()
            .map(e -> {
                List<String> nvrams = nvramFileMap != null ? nvramFileMap.get(e.name()) : null;
                String status = (nvrams != null && !nvrams.isEmpty())
                    ? String.join(", ", nvrams) : "";
                return (ContentItem) new LibraryItem(e, status);
            })
            .toList());
        breadcrumb.setText(folderLabel + " - " + entries.size() + " files");
        searchField.clear();
    }

    /** Legacy method for backward compatibility during refactoring. */
    public void setFiles(List<CfsFile> fileList, String imageName) {
        setNvramFiles(fileList, imageName);
    }

    public void clearIconCache() { iconCache.clear(); }

    public void clear() {
        items.clear();
        breadcrumb.setText("No image loaded");
        if (searchVisible) toggleSearch();
    }

    /** Get all selected items (supports multi-select). */
    public List<ContentItem> getSelectedItems() {
        return List.copyOf(table.getSelectionModel().getSelectedItems());
    }

    /** Register callback for item selection changes. */
    public void setOnItemSelected(Consumer<ContentItem> callback) {
        table.getSelectionModel().selectedItemProperty().addListener(
            (obs, oldVal, newVal) -> callback.accept(newVal)
        );
    }

    /** Legacy callback wiring. */
    public void setOnFileSelected(Consumer<CfsFile> callback) {
        setOnItemSelected(item -> {
            if (item instanceof NvramItem ni) {
                callback.accept(ni.file());
            } else {
                callback.accept(null);
            }
        });
    }
}
