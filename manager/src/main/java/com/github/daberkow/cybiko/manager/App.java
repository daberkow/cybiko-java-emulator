package com.github.daberkow.cybiko.manager;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * Cybiko NVRAM Manager - JavaFX desktop application for managing CFS flash images.
 */
public class App extends Application {

    @Override
    public void start(Stage primaryStage) {
        MainWindow mainWindow = new MainWindow(primaryStage);

        Scene scene = new Scene(mainWindow, 1000, 700);

        String css = getClass().getResource("css/dark-theme.css").toExternalForm();
        scene.getStylesheets().add(css);

        primaryStage.setTitle("Cybiko NVRAM Manager");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(800);
        primaryStage.setMinHeight(500);

        // Prompt for unsaved changes on close
        primaryStage.setOnCloseRequest(event -> {
            if (!mainWindow.confirmClose()) {
                event.consume();
            }
        });

        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
