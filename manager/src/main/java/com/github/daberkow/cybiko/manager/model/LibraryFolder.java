package com.github.daberkow.cybiko.manager.model;

import java.nio.file.Path;

/**
 * A user-configured library folder for browsing .app files.
 *
 * @param path     absolute path to the directory
 * @param label    display label (e.g., "Games")
 * @param category category tag (e.g., "games")
 */
public record LibraryFolder(
    Path path,
    String label,
    String category
) {
}
