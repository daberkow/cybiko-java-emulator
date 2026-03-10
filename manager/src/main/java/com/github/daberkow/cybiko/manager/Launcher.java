package com.github.daberkow.cybiko.manager;

/**
 * Non-Application entry point for fat JAR execution.
 * JavaFX requires the main class of a fat JAR to NOT extend Application,
 * otherwise the module system check fails at startup.
 */
public class Launcher {
    public static void main(String[] args) {
        App.main(args);
    }
}
