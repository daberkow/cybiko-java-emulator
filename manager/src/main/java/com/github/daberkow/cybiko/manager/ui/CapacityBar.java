package com.github.daberkow.cybiko.manager.ui;

import com.github.daberkow.cybiko.manager.cfs.CfsImage;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/**
 * Bottom bar showing storage capacity usage.
 */
public class CapacityBar extends HBox {

    private final ProgressBar progressBar = new ProgressBar(0);
    private final Label usageLabel = new Label("No image");
    private final Label geomLabel = new Label();

    public CapacityBar() {
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(10);
        setPadding(new Insets(6, 10, 6, 10));
        getStyleClass().add("capacity-bar");

        progressBar.setPrefWidth(200);
        progressBar.setMaxWidth(300);

        usageLabel.getStyleClass().add("capacity-label");
        geomLabel.getStyleClass().add("capacity-label");

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        getChildren().addAll(progressBar, usageLabel, spacer, geomLabel);
    }

    public void update(CfsImage image, String imageName) {
        if (image == null) {
            progressBar.setProgress(0);
            usageLabel.setText("No image");
            geomLabel.setText("");
            return;
        }

        long used = image.usedSpace();
        long total = image.totalCapacity();
        double ratio = total > 0 ? (double) used / total : 0;

        progressBar.setProgress(ratio);
        int fileCount = image.listFiles().size();
        String prefix = (imageName != null && !imageName.isEmpty()) ? imageName + " — " : "";
        usageLabel.setText(String.format("%s%d files | %d KB / %d KB (%d%%)",
            prefix, fileCount, used / 1024, total / 1024, Math.round(ratio * 100)));
        geomLabel.setText(image.geometry().name());
    }
}
