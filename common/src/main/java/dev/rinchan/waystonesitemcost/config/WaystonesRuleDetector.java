package dev.rinchan.waystonesitemcost.config;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Detects whether Waystones currently has item cost rules configured, for display in the config screen status bar.
 */
public final class WaystonesRuleDetector {
    private WaystonesRuleDetector() {}

    public enum Status {
        CONFIGURED,
        NOT_CONFIGURED,
        LEGACY_ENABLED,
        LEGACY_DISABLED
    }

    public static Status detect(boolean isLegacy1201) {
        if (isLegacy1201) {
            return PaymentConfig.legacyEnableCustomItemCosts() && !PaymentConfig.legacyItemCosts().isEmpty()
                ? Status.LEGACY_ENABLED
                : Status.LEGACY_DISABLED;
        }
        Path[] candidates = {
            Path.of("config", "waystones-common.toml"),
            Path.of("config", "waystones-server.toml")
        };
        for (Path path : candidates) {
            try {
                if (!Files.exists(path)) continue;
                String text = Files.readString(path);
                for (String line : text.split("\\r?\\n")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("#")) continue;
                    if (trimmed.contains("add_item_cost(") || trimmed.contains("item_cost(")) {
                        return Status.CONFIGURED;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return Status.NOT_CONFIGURED;
    }
}
