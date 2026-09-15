package dev.rinchan.waystoneemeralds.payment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class PaymentConfig {
    private PaymentConfig() {}

    public static boolean recipePayments() {
        return Settings.RECIPE_PAYMENTS;
    }

    private static final class Settings {
        private static final boolean RECIPE_PAYMENTS = load();

        private static boolean load() {
            var path = Path.of("config", "waystones-item-cost-extended.properties");
            var properties = new Properties();
            try {
                if (Files.exists(path)) {
                    try (var reader = Files.newBufferedReader(path)) {
                        properties.load(reader);
                    }
                } else {
                    Files.createDirectories(path.getParent());
                    Files.writeString(path, "# Accept reversible crafting denominations and return change.\nrecipePayments=false\n");
                }
            } catch (IOException failure) {
                throw new UncheckedIOException("Unable to read Waystones Item Cost Extended configuration", failure);
            }
            return Boolean.parseBoolean(properties.getProperty("recipePayments", "false"));
        }
    }
}
