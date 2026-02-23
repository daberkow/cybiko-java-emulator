package com.github.daberkow.cybiko.manager.ui;

import com.github.daberkow.cybiko.manager.model.AppEntry;
import com.github.daberkow.cybiko.manager.model.CfsFile;
import com.github.daberkow.cybiko.manager.model.ContentItem;
import com.github.daberkow.cybiko.manager.model.ContentItem.LibraryItem;
import com.github.daberkow.cybiko.manager.model.ContentItem.NvramItem;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.scene.control.*;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Center pane with file list table, search field, and multi-select support.
 */
public class ContentListPane extends VBox {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Label breadcrumb = new Label("No image loaded");
    private final TextField searchField = new TextField();
    private final TableView<ContentItem> table = new TableView<>();
    private final ObservableList<ContentItem> items = FXCollections.observableArrayList();
    private final FilteredList<ContentItem> filteredItems = new FilteredList<>(items, p -> true);

    @SuppressWarnings("unchecked")
    public ContentListPane() {
        breadcrumb.getStyleClass().add("breadcrumb");
        breadcrumb.setMaxWidth(Double.MAX_VALUE);

        searchField.setPromptText("Search...");
        searchField.getStyleClass().add("search-field");
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            String filter = newVal == null ? "" : newVal.toLowerCase();
            filteredItems.setPredicate(item ->
                filter.isEmpty() || item.name().toLowerCase().contains(filter)
            );
        });

        // Name column
        TableColumn<ContentItem, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(p -> new ReadOnlyStringWrapper(p.getValue().name()));
        nameCol.setPrefWidth(250);

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

        getChildren().addAll(breadcrumb, searchField, table);
        VBox.setVgrow(table, Priority.ALWAYS);
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

    public void clear() {
        items.clear();
        breadcrumb.setText("No image loaded");
        searchField.clear();
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
