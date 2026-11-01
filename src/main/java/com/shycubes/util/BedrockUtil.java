package com.shycubes.util;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Utility for detecting Bedrock Edition players via Floodgate API or Floodgate UUID convention.
 */
public final class BedrockUtil {

    private BedrockUtil() {
    }

    public static boolean isBedrockPlayer(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        // Floodgate UUIDs always have most significant bits == 0 (00000000-0000-0000-...)
        if (uuid.getMostSignificantBits() == 0L) {
            return true;
        }
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object apiInstance = apiClass.getMethod("getInstance").invoke(null);
            if (apiInstance != null) {
                Method isFloodgate = apiClass.getMethod("isFloodgatePlayer", UUID.class);
                Object result = isFloodgate.invoke(apiInstance, uuid);
                if (result instanceof Boolean b) {
                    return b;
                }
            }
        } catch (Throwable ignored) {
            // Floodgate not loaded or not present
        }
        return false;
    }

    public static String getFloodgateXuid(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object apiInstance = apiClass.getMethod("getInstance").invoke(null);
            if (apiInstance != null) {
                Method getPlayer = apiClass.getMethod("getPlayer", UUID.class);
                Object fgPlayer = getPlayer.invoke(apiInstance, uuid);
                if (fgPlayer != null) {
                    Method getXuid = fgPlayer.getClass().getMethod("getXuid");
                    Object xuid = getXuid.invoke(fgPlayer);
                    if (xuid != null) {
                        return xuid.toString();
                    }
                }
            }
        } catch (Throwable ignored) {
            // Fallback to deriving hex/decimal XUID from Floodgate UUID least significant bits
        }
        if (uuid.getMostSignificantBits() == 0L) {
            return Long.toUnsignedString(uuid.getLeastSignificantBits());
        }
        return null;
    }
}

