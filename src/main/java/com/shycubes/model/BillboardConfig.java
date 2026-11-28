package com.shycubes.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Collections;
import java.util.List;

/**
 * Configuration for a single placed billboard defined under billboards.<id> in config.yml.
 */
public record BillboardConfig(
        String id,
        boolean enabled,
        String stat,
        StatOrder order,
        int entries,
        String unit,
        List<String> header,
        boolean autoPlaceAtSpawn,
        double spawnOffsetX,
        double spawnOffsetY,
        double spawnOffsetZ,
        String worldName,
        Double x,
        Double y,
        Double z
) {
    public BillboardConfig {
        order = order != null ? order : StatOrder.DESC;
        entries = Math.max(1, entries);
        unit = unit != null ? unit : "";
        header = header != null ? List.copyOf(header) : Collections.emptyList();
    }

    public boolean hasLocation() {
        return worldName != null && !worldName.isBlank() && x != null && y != null && z != null;
    }

    public Location toLocation() {
        if (!hasLocation()) {
            return null;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return new Location(world, x, y, z);
    }

    public StatQuery toStatQuery() {
        return new StatQuery(stat, order, entries);
    }

    public BillboardConfig withLocation(Location location, boolean newEnabled) {
        double rx = Math.round(location.getX() * 100.0D) / 100.0D;
        double ry = Math.round(location.getY() * 100.0D) / 100.0D;
        double rz = Math.round(location.getZ() * 100.0D) / 100.0D;
        return new BillboardConfig(
                id,
                newEnabled,
                stat,
                order,
                entries,
                unit,
                header,
                autoPlaceAtSpawn,
                spawnOffsetX,
                spawnOffsetY,
                spawnOffsetZ,
                location.getWorld().getName(),
                rx,
                ry,
                rz
        );
    }

    public BillboardConfig withEnabled(boolean newEnabled) {
        return new BillboardConfig(
                id,
                newEnabled,
                stat,
                order,
                entries,
                unit,
                header,
                newEnabled ? autoPlaceAtSpawn : false,
                spawnOffsetX,
                spawnOffsetY,
                spawnOffsetZ,
                worldName,
                x,
                y,
                z
        );
    }
}
