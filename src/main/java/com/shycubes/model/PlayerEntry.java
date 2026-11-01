package com.shycubes.model;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

public record PlayerEntry(
        UUID uuid,
        String username,
        boolean bedrock,
        String xuid,
        long blocksBroken,
        long firstJoin,
        long lastSeen,
        Map<String, String> attributes
) {
    public PlayerEntry {
        attributes = attributes != null ? Map.copyOf(attributes) : Collections.emptyMap();
    }
}

