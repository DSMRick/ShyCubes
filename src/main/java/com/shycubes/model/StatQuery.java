package com.shycubes.model;

import java.util.Objects;

/**
 * Cache key for a ranked leaderboard query against player_stats.
 */
public record StatQuery(
        String statKey,
        StatOrder order,
        int limit
) {
    public StatQuery {
        Objects.requireNonNull(statKey, "statKey");
        order = order != null ? order : StatOrder.DESC;
        limit = Math.max(1, limit);
    }
}
