package com.github.daberkow.cybiko.manager;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import com.github.daberkow.cybiko.manager.io.LibraryConfig;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * Cybiko NVRAM Manager - JavaFX desktop application for managing CFS flash images.
 */
public class App extends Application {

    @Override
    public void start(Stage primaryStage) {
        String themeName = LibraryConfig.loadTheme();
        if ("light".equals(themeName)) {
            Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());
        } else {
            Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
        }

        MainWindow mainWindow = new MainWindow(primaryStage, themeName);

        Scene scene = new Scene(mainWindow, 1000, 700);

        String css = getClass().getResource("css/dark-theme.css").toExternalForm();
        scene.getStylesheets().add(css);

        // Ctrl+F / Cmd+F to open search
        scene.getAccelerators().put(
            new javafx.scene.input.KeyCodeCombination(
                javafx.scene.input.KeyCode.F,
                javafx.scene.input.KeyCombination.SHORTCUT_DOWN),
            mainWindow::showSearch
        );

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

    @Override
    public void stop() {
        System.exit(0);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
