package dev.rinchan.waystonesitemcost;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Soft-degradation guard for Mixins targeting Waystones / Shogi internal classes.
 * Logs unexpected runtime / signature errors once per hook and falls back cleanly to vanilla behavior.
 */
public final class MixinGuard {
    public static final String MOD_ID = "waystones_item_cost_extended";
    public static final Logger LOGGER = LoggerFactory.getLogger("WaystonesItemCostExtended");
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private MixinGuard() {
    }

    public static void logOnce(String hookName, Throwable error) {
        if (LOGGED.add(hookName)) {
            LOGGER.error("[Waystones Item Cost Extended] Hook '{}' failed; safely falling back to vanilla Waystones behavior.", hookName, error);
        }
    }
}
