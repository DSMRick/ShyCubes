package com.shycubes.model;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

public record PlayerEntry(
        UUID uuid,
        String username,
        boolean bedrock,
        String xuid,
        long firstJoin,
        long lastSeen,
        Map<String, Long> stats,
        Map<String, String> attributes
) {
    public PlayerEntry {
        stats = stats != null ? Map.copyOf(stats) : Collections.emptyMap();
        attributes = attributes != null ? Map.copyOf(attributes) : Collections.emptyMap();
    }

    public long getStat(String statKey) {
        return stats.getOrDefault(statKey, 0L);
    }
}
