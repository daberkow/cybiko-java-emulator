package com.github.daberkow.cybiko.manager.io;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Persists recently opened NVRAM file paths to ~/.cybiko-manager/recent.properties.
 */
public final class RecentFiles {

    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".cybiko-manager");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("recent.properties");
    private static final int MAX_RECENT = 5;

    private RecentFiles() {}

    /** Load recent file paths. Returns empty list if file doesn't exist. */
    public static List<Path> load() {
        return load(CONFIG_FILE);
    }

    /** Load recent file paths from a specific path (for testing). */
    public static List<Path> load(Path configFile) {
        if (!Files.exists(configFile)) return new ArrayList<>();

        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(configFile)) {
            props.load(reader);
        } catch (IOException e) {
            return new ArrayList<>();
        }

        int count;
        try {
            count = Integer.parseInt(props.getProperty("recent.count", "0"));
        } catch (NumberFormatException e) {
            return new ArrayList<>();
        }

        List<Path> paths = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String path = props.getProperty("recent." + i);
            if (path != null && !path.isEmpty()) {
                paths.add(Path.of(path));
            }
        }
        return paths;
    }

    /** Save recent file paths. */
    public static void save(List<Path> paths) throws IOException {
        save(paths, CONFIG_FILE);
    }

    /** Save recent file paths to a specific path (for testing). */
    public static void save(List<Path> paths, Path configFile) throws IOException {
        Files.createDirectories(configFile.getParent());

        Properties props = new Properties();
        int count = Math.min(paths.size(), MAX_RECENT);
        props.setProperty("recent.count", String.valueOf(count));
        for (int i = 0; i < count; i++) {
            props.setProperty("recent." + i, paths.get(i).toString());
        }

        try (Writer writer = Files.newBufferedWriter(configFile)) {
            props.store(writer, "Cybiko NVRAM Manager - Recent Files");
        }
    }

    /** Add a path to the front of the list, deduplicating and trimming to MAX_RECENT. */
    public static List<Path> addRecent(List<Path> existing, Path newPath) {
        List<Path> result = new ArrayList<>();
        result.add(newPath);
        for (Path p : existing) {
            if (!p.equals(newPath) && result.size() < MAX_RECENT) {
                result.add(p);
            }
        }
        return result;
    }
}
