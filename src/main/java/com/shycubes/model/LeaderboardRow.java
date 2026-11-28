package com.shycubes.model;

import java.util.UUID;

/**
 * Metric-agnostic leaderboard entry rendered on a billboard or in chat.
 */
public record LeaderboardRow(
        UUID uuid,
        String username,
        long value
) {}
