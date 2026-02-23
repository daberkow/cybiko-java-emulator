package com.github.daberkow.cybiko.manager.ui;

import javafx.scene.control.Alert;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * About dialog showing version and system information.
 */
public class AboutDialog extends Alert {

    public AboutDialog() {
        super(AlertType.INFORMATION);
        setTitle("About Cybiko NVRAM Manager");
        setHeaderText("Cybiko NVRAM Manager");

        String version = loadVersion();
        String content = "Version: " + version + "\n"
            + "Java: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")\n"
            + "JavaFX: " + System.getProperty("javafx.version", "unknown") + "\n"
            + "OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version");

        setContentText(content);
    }

    private static String loadVersion() {
        try (InputStream is = AboutDialog.class.getResourceAsStream("/VERSION")) {
            if (is != null) {
                return new BufferedReader(new InputStreamReader(is)).readLine().trim();
            }
        } catch (Exception e) {
            // fall through
        }
        return "unknown";
    }
}
