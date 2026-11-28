package com.shycubes.model;

import java.util.List;
import java.util.Map;

/**
 * Canonical built-in player_stats keys and default display units.
 */
public final class StatKeys {

    public static final String BLOCKS_BROKEN = "blocks_broken";
    public static final String BLOCKS_PLACED = "blocks_placed";
    public static final String MOBS_KILLED = "mobs_killed";
    public static final String ANIMALS_KILLED = "animals_killed";
    public static final String CROPS_HARVESTED = "crops_harvested";
    public static final String DEATHS = "deaths";
    public static final String PLAYER_KILLS = "player_kills";

    public static final List<String> ALL_BUILTINS = List.of(
            BLOCKS_BROKEN,
            BLOCKS_PLACED,
            MOBS_KILLED,
            ANIMALS_KILLED,
            CROPS_HARVESTED,
            DEATHS,
            PLAYER_KILLS
    );

    private static final Map<String, String> DEFAULT_UNITS = Map.of(
            BLOCKS_BROKEN, "blocks",
            BLOCKS_PLACED, "blocks",
            MOBS_KILLED, "mobs",
            ANIMALS_KILLED, "animals",
            CROPS_HARVESTED, "crops",
            DEATHS, "deaths",
            PLAYER_KILLS, "kills"
    );

    private StatKeys() {}

    public static String defaultUnitFor(String statKey) {
        if (statKey == null) {
            return "";
        }
        return DEFAULT_UNITS.getOrDefault(statKey, "");
    }
}
