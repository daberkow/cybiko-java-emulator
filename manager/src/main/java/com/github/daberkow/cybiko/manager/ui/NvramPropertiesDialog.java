package com.github.daberkow.cybiko.manager.ui;

import com.github.daberkow.cybiko.manager.cfs.CfsImage;
import com.github.daberkow.cybiko.manager.cfs.FlashGeometry;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;

import java.nio.file.Path;

/**
 * Dialog showing NVRAM image properties and integrity status.
 */
public class NvramPropertiesDialog extends Dialog<Void> {

    public NvramPropertiesDialog(CfsImage image, Path path) {
        setTitle("NVRAM Properties");

        DialogPane dialogPane = getDialogPane();
        dialogPane.getStyleClass().add("dialog-pane");

        FlashGeometry geom = image.geometry();

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(6);
        grid.setPadding(new Insets(16));

        int row = 0;

        addRow(grid, row++, "File:", path != null ? path.toString() : "Untitled (unsaved)");
        addRow(grid, row++, "Flash type:", geom.name());
        addRow(grid, row++, "Page count:", String.valueOf(geom.pageCount()));
        addRow(grid, row++, "Page size:", geom.pageSize() + " bytes");
        addRow(grid, row++, "Total image size:", String.format("%,d bytes (%.1f KB)", geom.totalImageSize(), geom.totalImageSize() / 1024.0));
        addSeparator(grid, row++);

        addRow(grid, row++, "Boot blocks:", String.valueOf(FlashGeometry.BOOT_BLOCKS));
        addRow(grid, row++, "File blocks:", String.valueOf(geom.fileBlockCount()));
        addRow(grid, row++, "Used blocks:", String.valueOf(image.usedBlockCount()));
        addRow(grid, row++, "Free blocks:", String.valueOf(image.freeBlockCount()));
        addSeparator(grid, row++);

        long used = image.usedSpace();
        long free = image.freeSpace();
        long total = image.totalCapacity();
        addRow(grid, row++, "Used space:", String.format("%,d bytes (%.1f KB)", used, used / 1024.0));
        addRow(grid, row++, "Free space:", String.format("%,d bytes (%.1f KB)", free, free / 1024.0));
        addRow(grid, row++, "Total capacity:", String.format("%,d bytes (%.1f KB)", total, total / 1024.0));
        addRow(grid, row++, "File count:", String.valueOf(image.listFiles().size()));
        addSeparator(grid, row++);

        boolean valid = image.verify();
        addRow(grid, row, "Checksum status:", valid ? "All valid" : "Errors detected");

        dialogPane.setContent(grid);
        dialogPane.getButtonTypes().add(ButtonType.CLOSE);

        setResultConverter(button -> null);
    }

    private void addRow(GridPane grid, int row, String label, String value) {
        Label lbl = new Label(label);
        lbl.getStyleClass().add("detail-label");
        Label val = new Label(value);
        val.getStyleClass().add("detail-value");
        grid.add(lbl, 0, row);
        grid.add(val, 1, row);
    }

    private void addSeparator(GridPane grid, int row) {
        Separator sep = new Separator();
        grid.add(sep, 0, row, 2, 1);
    }
}
