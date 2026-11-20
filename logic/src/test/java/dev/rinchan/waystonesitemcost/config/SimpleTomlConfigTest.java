package dev.rinchan.waystonesitemcost.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SimpleTomlConfigTest {
    @Test
    void defaultsEnableRecipePaymentsNestedContainersAndTooltip() {
        var defaults = PaymentConfig.Data.defaults();
        assertTrue(defaults.recipePayments());
        assertTrue(defaults.searchNestedContainers());
        assertTrue(defaults.showPaymentPreviewTooltip());
        assertFalse(defaults.legacyEnableCustomItemCosts());
        assertFalse(defaults.legacyWaiveVanillaXpCost());
        assertEquals(1, defaults.legacyItemCosts().size());
        assertEquals("minecraft:emerald", defaults.legacyItemCosts().get(0).itemId());
    }

    @Test
    void roundTripsTomlWithCommentsAndLegacySection() {
        var original = new PaymentConfig.Data(
            false,
            true,
            false,
            true,
            true,
            List.of(
                new LegacyCostEntry("minecraft:emerald", 2, 1, 3),
                new LegacyCostEntry("minecraft:ender_pearl", 1, 0, 1)
            )
        );
        String rendered = PaymentConfig.renderToml(original);
        var parsed = PaymentConfig.parseToml(rendered);
        assertEquals(original, parsed);
    }

    @Test
    void migratesLegacyPropertiesToTomlAndBacksUpOldFile(@TempDir Path tempDir) throws Exception {
        Path legacyProps = tempDir.resolve(PaymentConfig.LEGACY_PROPERTIES_FILENAME);
        Files.writeString(legacyProps, "# Old config\nrecipePayments=false\n");

        PaymentConfig.useConfigDir(tempDir);
        var loaded = PaymentConfig.get();

        assertFalse(loaded.recipePayments());
        assertTrue(loaded.searchNestedContainers());
        assertTrue(loaded.showPaymentPreviewTooltip());
        assertTrue(Files.exists(tempDir.resolve(PaymentConfig.TOML_FILENAME)));
        assertFalse(Files.exists(legacyProps));
        assertTrue(Files.exists(tempDir.resolve(PaymentConfig.LEGACY_BACKUP_FILENAME)));

        // Hot-save updates immediately
        PaymentConfig.save(new PaymentConfig.Data(true, false, true, false, false, loaded.legacyItemCosts()));
        assertTrue(PaymentConfig.recipePayments());
        assertFalse(PaymentConfig.searchNestedContainers());

        // Reload from disk matches saved values
        PaymentConfig.reload();
        assertTrue(PaymentConfig.recipePayments());
        assertFalse(PaymentConfig.searchNestedContainers());
    }

    @Test
    void calculatesLegacyCostWithDistanceAndCrossDimension() {
        var entry = LegacyCostEntry.parse("minecraft:emerald, 2, 1, 5").orElseThrow();
        assertEquals(2, entry.calculateCost(500, false));
        assertEquals(4, entry.calculateCost(2500, false));
        assertEquals(7, entry.calculateCost(0, true));
    }
}
