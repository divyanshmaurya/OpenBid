package com.openbid.shared;

import java.util.Locale;

public final class Categories {

    public static final String[] ALL = {
            "Electronics", "Collectibles", "Fashion", "Sports", "Music", "Home", "Other"
    };

    private Categories() {}

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Other";
        }
        String trimmed = raw.trim();
        for (String c : ALL) {
            if (c.equalsIgnoreCase(trimmed)) {
                return c;
            }
        }
        return "Other";
    }

    public static String label(String raw) {
        return normalize(raw).toUpperCase(Locale.ROOT);
    }
}
