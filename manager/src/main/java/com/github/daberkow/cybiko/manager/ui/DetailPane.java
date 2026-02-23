package com.github.daberkow.cybiko.manager.ui;

import com.github.daberkow.cybiko.manager.model.CfsFile;
import com.github.daberkow.cybiko.manager.model.ContentItem;
import com.github.daberkow.cybiko.manager.model.ContentItem.LibraryItem;
import com.github.daberkow.cybiko.manager.model.ContentItem.NvramItem;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * Right panel showing details of the selected file with action buttons.
 */
public class DetailPane extends VBox {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Label placeholder = new Label("No file selected");
    private final VBox content = new VBox(8);

    private final Label nameValue = new Label();
    private final Label sizeValue = new Label();
    private final Label dateValue = new Label();
    private final Label blocksValue = new Label();
    private final Label fileIdValue = new Label();
    private final Label statusValue = new Label();

    private final VBox blocksRow;
    private final VBox fileIdRow;

    private final Button addToNvramBtn = new Button("Add to NVRAM");
    private final Button removeFromNvramBtn = new Button("Remove from NVRAM");
    private final Button viewHexBtn = new Button("View Hex");
    private final HBox actionBox = new HBox(8);

    private boolean nvramAvailable = false;
    private ContentItem currentItem;

    private Consumer<LibraryItem> onAddToNvram;
    private Consumer<NvramItem> onRemoveFromNvram;
    private Consumer<ContentItem> onViewHex;

    public DetailPane() {
        setPrefWidth(250);
        setMinWidth(200);
        setPadding(new Insets(8));

        Label header = new Label("FILE DETAILS");
        header.getStyleClass().add("section-header");

        content.setPadding(new Insets(4, 0, 0, 0));

        blocksRow = makeRow("Blocks", blocksValue);
        fileIdRow = makeRow("File ID", fileIdValue);

        content.getChildren().addAll(
            makeRow("Name", nameValue),
            makeRow("Size", sizeValue),
            makeRow("Modified", dateValue),
            makeRow("Status", statusValue),
            blocksRow,
            fileIdRow
        );

        // Action buttons
        addToNvramBtn.getStyleClass().add("action-button");
        addToNvramBtn.setOnAction(e -> {
            if (currentItem instanceof LibraryItem li && onAddToNvram != null) {
                onAddToNvram.accept(li);
            }
        });

        removeFromNvramBtn.getStyleClass().add("action-button-danger");
        removeFromNvramBtn.setOnAction(e -> {
            if (currentItem instanceof NvramItem ni && onRemoveFromNvram != null) {
                onRemoveFromNvram.accept(ni);
            }
        });

        viewHexBtn.getStyleClass().add("action-button");
        viewHexBtn.setOnAction(e -> {
            if (currentItem != null && onViewHex != null) {
                onViewHex.accept(currentItem);
            }
        });

        actionBox.setPadding(new Insets(8, 0, 0, 0));

        placeholder.getStyleClass().add("detail-label");
        placeholder.setPadding(new Insets(20));

        getChildren().addAll(header, placeholder);
    }

    /** Show details for a ContentItem. */
    public void showItem(ContentItem item) {
        currentItem = item;

        if (item == null) {
            getChildren().setAll(getChildren().get(0), placeholder);
            return;
        }

        nameValue.setText(item.name());

        long bytes = item.sizeBytes();
        if (bytes < 1024) {
            sizeValue.setText(bytes + " bytes");
        } else {
            sizeValue.setText(String.format("%.1f KB (%,d bytes)", bytes / 1024.0, bytes));
        }

        try {
            dateValue.setText(item.lastModified().format(DATE_FMT));
        } catch (Exception e) {
            dateValue.setText("-");
        }

        // Configure NVRAM-specific fields and action buttons
        if (item instanceof NvramItem ni) {
            statusValue.setText("In NVRAM");
            blocksValue.setText(String.valueOf(ni.file().blockCount()));
            fileIdValue.setText(String.valueOf(ni.file().fileId()));
            blocksRow.setVisible(true);
            blocksRow.setManaged(true);
            fileIdRow.setVisible(true);
            fileIdRow.setManaged(true);

            actionBox.getChildren().setAll(removeFromNvramBtn, viewHexBtn);
        } else if (item instanceof LibraryItem li) {
            statusValue.setText(li.inNvram() ? "In NVRAM" : "Not in NVRAM");
            blocksRow.setVisible(false);
            blocksRow.setManaged(false);
            fileIdRow.setVisible(false);
            fileIdRow.setManaged(false);

            addToNvramBtn.setDisable(!nvramAvailable);
            actionBox.getChildren().setAll(addToNvramBtn, viewHexBtn);
        }

        content.getChildren().removeIf(n -> n == actionBox);
        content.getChildren().add(actionBox);

        if (getChildren().size() < 2 || getChildren().get(1) != content) {
            getChildren().setAll(getChildren().get(0), content);
        }
    }

    /** Legacy method: show a CfsFile directly. */
    public void showFile(CfsFile file) {
        if (file == null) {
            showItem(null);
        } else {
            showItem(new NvramItem(file));
        }
    }

    public void setNvramAvailable(boolean available) {
        this.nvramAvailable = available;
        if (currentItem instanceof LibraryItem) {
            addToNvramBtn.setDisable(!available);
        }
    }

    public void setOnAddToNvram(Consumer<LibraryItem> callback) {
        this.onAddToNvram = callback;
    }

    public void setOnRemoveFromNvram(Consumer<NvramItem> callback) {
        this.onRemoveFromNvram = callback;
    }

    public void setOnViewHex(Consumer<ContentItem> callback) {
        this.onViewHex = callback;
    }

    private VBox makeRow(String label, Label value) {
        Label lbl = new Label(label);
        lbl.getStyleClass().add("detail-label");
        value.getStyleClass().add("detail-value");
        value.setWrapText(true);
        VBox row = new VBox(2, lbl, value);
        row.setPadding(new Insets(0, 0, 4, 0));
        return row;
    }
}
