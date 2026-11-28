package com.shycubes.model;

import java.util.Locale;

public enum StatOrder {
    DESC,
    ASC;

    public static StatOrder fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return DESC;
        }
        try {
            return StatOrder.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return DESC;
        }
    }
}
