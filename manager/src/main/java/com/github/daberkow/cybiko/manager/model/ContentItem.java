package com.github.daberkow.cybiko.manager.model;

import java.time.LocalDateTime;

/**
 * Unified content item for display in the content table.
 * Can represent either an NVRAM file or a library app entry.
 */
public sealed interface ContentItem permits ContentItem.NvramItem, ContentItem.LibraryItem {

    String name();
    String extension();
    long sizeBytes();
    LocalDateTime lastModified();
    boolean inNvram();

    /** An item from the currently loaded NVRAM image. */
    record NvramItem(CfsFile file) implements ContentItem {
        @Override public String name() { return file.name(); }
        @Override public String extension() { return file.extension(); }
        @Override public long sizeBytes() { return file.size(); }
        @Override public LocalDateTime lastModified() { return file.lastModified(); }
        @Override public boolean inNvram() { return true; }
    }

    /** An item from a library folder on disk. */
    record LibraryItem(AppEntry entry, boolean inNvram) implements ContentItem {
        @Override public String name() { return entry.name(); }
        @Override public String extension() { return entry.extension(); }
        @Override public long sizeBytes() { return entry.sizeBytes(); }
        @Override public LocalDateTime lastModified() { return entry.lastModifiedDateTime(); }
    }
}
