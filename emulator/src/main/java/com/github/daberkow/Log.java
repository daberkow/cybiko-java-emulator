package com.github.daberkow;

import java.util.EnumSet;
import java.util.Set;

/**
 * Simple logging with selectable categories.
 *
 * Usage: --logging cpu,radio,rtc,dma,io,status,boot,cfs,speaker
 * Default (no --logging flag): boot,status
 * --logging all: enables everything
 * --logging none: disables everything
 */
public final class Log {

    public enum Category {
        STATUS,   // Frame status every 60 frames
        CPU,      // Instruction tracing, IRQ dispatch
        RADIO,    // Radio frames, transport, SCI DTC protocol
        RTC,      // I2C / PCF8593 RTC protocol
        DMA,      // DMA and DTC transfers
        IO,       // Unmapped I/O fallthrough
        BOOT,     // Startup milestones, ROM loading
        CFS,      // Filesystem operations
        SPEAKER;  // Audio initialization

        /** Parse comma-separated category names (case-insensitive). */
        static Set<Category> parse(String spec) {
            if (spec == null || spec.isBlank()) return defaults();
            String lower = spec.strip().toLowerCase();
            if ("all".equals(lower)) return EnumSet.allOf(Category.class);
            if ("none".equals(lower)) return EnumSet.noneOf(Category.class);
            EnumSet<Category> set = EnumSet.noneOf(Category.class);
            for (String part : lower.split(",")) {
                try {
                    set.add(Category.valueOf(part.strip().toUpperCase()));
                } catch (IllegalArgumentException e) {
                    System.err.println("Unknown log category: " + part.strip()
                            + " (valid: " + java.util.Arrays.toString(Category.values()) + ")");
                }
            }
            return set;
        }

        static Set<Category> defaults() {
            return EnumSet.of(BOOT, STATUS);
        }
    }

    private static Set<Category> enabled = Category.defaults();

    /** Configure which categories are enabled. */
    public static void setEnabled(Set<Category> categories) {
        enabled = categories.isEmpty()
                ? EnumSet.noneOf(Category.class)
                : EnumSet.copyOf(categories);
    }

    /** Check if a category is enabled (for guarding expensive log construction). */
    public static boolean isEnabled(Category cat) {
        return enabled.contains(cat);
    }

    /** Log a formatted message if the category is enabled. */
    public static void log(Category cat, String format, Object... args) {
        if (enabled.contains(cat)) {
            System.err.printf(format + "%n", args);
        }
    }

    /** Log a plain message if the category is enabled. */
    public static void log(Category cat, String msg) {
        if (enabled.contains(cat)) {
            System.err.println(msg);
        }
    }

    private Log() {}
}
