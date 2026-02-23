package com.github.daberkow.cybiko.manager.io;

import com.github.daberkow.cybiko.manager.model.LibraryFolder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Persists library folder configuration to ~/.cybiko-manager/library.properties.
 */
public final class LibraryConfig {

    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".cybiko-manager");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("library.properties");
    private static final Path THEME_FILE = CONFIG_DIR.resolve("theme.properties");

    private LibraryConfig() {}

    /** Load library folders from config file. Returns empty list if file doesn't exist. */
    public static List<LibraryFolder> load() {
        return load(CONFIG_FILE);
    }

    /** Load library folders from a specific path (for testing). */
    public static List<LibraryFolder> load(Path configFile) {
        if (!Files.exists(configFile)) return new ArrayList<>();

        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(configFile)) {
            props.load(reader);
        } catch (IOException e) {
            return new ArrayList<>();
        }

        int count;
        try {
            count = Integer.parseInt(props.getProperty("folder.count", "0"));
        } catch (NumberFormatException e) {
            return new ArrayList<>();
        }

        List<LibraryFolder> folders = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String path = props.getProperty("folder." + i + ".path");
            String label = props.getProperty("folder." + i + ".label", "");
            String category = props.getProperty("folder." + i + ".category", "");
            if (path != null && !path.isEmpty()) {
                folders.add(new LibraryFolder(Path.of(path), label, category));
            }
        }
        return folders;
    }

    /** Save library folders to config file. */
    public static void save(List<LibraryFolder> folders) throws IOException {
        save(folders, CONFIG_FILE);
    }

    /** Save library folders to a specific path (for testing). */
    public static void save(List<LibraryFolder> folders, Path configFile) throws IOException {
        Files.createDirectories(configFile.getParent());

        Properties props = new Properties();
        props.setProperty("folder.count", String.valueOf(folders.size()));
        for (int i = 0; i < folders.size(); i++) {
            LibraryFolder f = folders.get(i);
            props.setProperty("folder." + i + ".path", f.path().toString());
            props.setProperty("folder." + i + ".label", f.label());
            props.setProperty("folder." + i + ".category", f.category());
        }

        try (Writer writer = Files.newBufferedWriter(configFile)) {
            props.store(writer, "Cybiko NVRAM Manager - Library Folders");
        }
    }

    /** Load saved theme preference. Returns "dark" or "light", defaults to "dark". */
    public static String loadTheme() {
        if (!Files.exists(THEME_FILE)) return "dark";
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(THEME_FILE)) {
            props.load(reader);
        } catch (IOException e) {
            return "dark";
        }
        String theme = props.getProperty("theme", "dark");
        return "light".equals(theme) ? "light" : "dark";
    }

    /** Save theme preference ("dark" or "light"). */
    public static void saveTheme(String themeName) throws IOException {
        Files.createDirectories(THEME_FILE.getParent());
        Properties props = new Properties();
        props.setProperty("theme", themeName);
        try (Writer writer = Files.newBufferedWriter(THEME_FILE)) {
            props.store(writer, "Cybiko NVRAM Manager - Theme Preference");
        }
    }
}
