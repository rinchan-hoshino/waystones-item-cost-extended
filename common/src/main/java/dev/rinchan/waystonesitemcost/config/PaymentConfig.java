package dev.rinchan.waystonesitemcost.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Zero-dependency TOML configuration with automatic lossless migration from legacy
 * config/waystones-item-cost-extended.properties and instant hot-save support.
 */
public final class PaymentConfig {
    public static final String TOML_FILENAME = "waystones-item-cost-extended.toml";
    public static final String LEGACY_PROPERTIES_FILENAME = "waystones-item-cost-extended.properties";
    public static final String LEGACY_BACKUP_FILENAME = "waystones-item-cost-extended.properties.bak";

    private static volatile Path configDir = Path.of("config");
    private static volatile Data current;

    private PaymentConfig() {
    }

    public record Data(
        boolean recipePayments,
        boolean searchNestedContainers,
        boolean showPaymentPreviewTooltip,
        boolean fixPhysicsShipDistance,
        boolean legacyEnableCustomItemCosts,
        boolean legacyWaiveVanillaXpCost,
        List<LegacyCostEntry> legacyItemCosts
    ) {
        public static Data defaults() {
            return new Data(
                true,
                true,
                true,
                true,
                false,
                false,
                List.of(new LegacyCostEntry("minecraft:emerald", 1, 0, 0))
            );
        }
    }

    /** Visible for testing: switches the config directory and reloads. */
    public static synchronized void useConfigDir(Path dir) {
        configDir = dir;
        current = null;
    }

    public static Data get() {
        Data local = current;
        if (local == null) {
            synchronized (PaymentConfig.class) {
                local = current;
                if (local == null) {
                    local = loadOrMigrate(configDir);
                    current = local;
                }
            }
        }
        return local;
    }

    public static synchronized void reload() {
        current = loadOrMigrate(configDir);
    }

    public static synchronized void save(Data updated) {
        current = updated;
        writeToml(configDir.resolve(TOML_FILENAME), updated);
    }

    public static boolean recipePayments() {
        return get().recipePayments();
    }

    public static boolean searchNestedContainers() {
        return get().searchNestedContainers();
    }

    public static boolean showPaymentPreviewTooltip() {
        return get().showPaymentPreviewTooltip();
    }

    public static boolean fixPhysicsShipDistance() {
        return get().fixPhysicsShipDistance();
    }

    public static boolean legacyEnableCustomItemCosts() {
        return get().legacyEnableCustomItemCosts();
    }

    public static boolean legacyWaiveVanillaXpCost() {
        return get().legacyWaiveVanillaXpCost();
    }

    public static List<LegacyCostEntry> legacyItemCosts() {
        return get().legacyItemCosts();
    }

    private static Data loadOrMigrate(Path dir) {
        Path tomlPath = dir.resolve(TOML_FILENAME);
        Path legacyPropsPath = dir.resolve(LEGACY_PROPERTIES_FILENAME);
        try {
            if (Files.exists(tomlPath)) {
                return parseToml(Files.readString(tomlPath));
            }
            Data defaults = Data.defaults();
            if (Files.exists(legacyPropsPath)) {
                var props = new Properties();
                try (var reader = Files.newBufferedReader(legacyPropsPath)) {
                    props.load(reader);
                }
                boolean migratedRecipePayments = Boolean.parseBoolean(
                    props.getProperty("recipePayments", String.valueOf(defaults.recipePayments())));
                Data migrated = new Data(
                    migratedRecipePayments,
                    defaults.searchNestedContainers(),
                    defaults.showPaymentPreviewTooltip(),
                    defaults.fixPhysicsShipDistance(),
                    defaults.legacyEnableCustomItemCosts(),
                    defaults.legacyWaiveVanillaXpCost(),
                    defaults.legacyItemCosts()
                );
                writeToml(tomlPath, migrated);
                Path backupPath = dir.resolve(LEGACY_BACKUP_FILENAME);
                Files.move(legacyPropsPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
                return migrated;
            }
            writeToml(tomlPath, defaults);
            return defaults;
        } catch (IOException failure) {
            throw new UncheckedIOException("Unable to read Waystones Item Cost Extended configuration", failure);
        }
    }

    public static Data parseToml(String content) {
        var toml = SimpleToml.parse(content);
        var defaults = Data.defaults();
        boolean recipePayments = toml.getBoolean("recipePayments", defaults.recipePayments());
        boolean searchNested = toml.getBoolean("searchNestedContainers", defaults.searchNestedContainers());
        boolean showTooltip = toml.getBoolean("showPaymentPreviewTooltip", defaults.showPaymentPreviewTooltip());
        boolean fixPhysics = toml.getBoolean("fixPhysicsShipDistance", defaults.fixPhysicsShipDistance());
        boolean legacyEnable = toml.getBoolean("legacy_1_20_1.enableCustomItemCosts", defaults.legacyEnableCustomItemCosts());
        boolean legacyWaiveXp = toml.getBoolean("legacy_1_20_1.waiveVanillaXpCost", defaults.legacyWaiveVanillaXpCost());

        List<LegacyCostEntry> entries;
        if (toml.contains("legacy_1_20_1.itemCosts")) {
            var rawList = toml.getStringList("legacy_1_20_1.itemCosts", List.of());
            var parsed = new ArrayList<LegacyCostEntry>();
            for (var spec : rawList) {
                LegacyCostEntry.parse(spec).ifPresent(parsed::add);
            }
            entries = List.copyOf(parsed);
        } else {
            entries = defaults.legacyItemCosts();
        }
        return new Data(recipePayments, searchNested, showTooltip, fixPhysics, legacyEnable, legacyWaiveXp, entries);
    }

    public static String renderToml(Data data) {
        var sb = new StringBuilder();
        sb.append("# Waystones Item Cost Extended 2.1.0 Configuration\n");
        sb.append("# Changes saved via the in-game config screen take effect immediately.\n\n");
        sb.append("# Allow items linked by reversible lossless crafting recipes (e.g. nugget <-> ingot <-> block)\n");
        sb.append("# to pay item costs with automatic greedy high-denomination change.\n");
        sb.append("recipePayments = ").append(data.recipePayments()).append("\n\n");
        sb.append("# Allow deducting payment items from Bundles and Shulker Boxes inside the player's inventory.\n");
        sb.append("# Returned change is ALWAYS placed into main inventory / hotbar slots (0..35) and never dropped.\n");
        sb.append("searchNestedContainers = ").append(data.searchNestedContainers()).append("\n\n");
        sb.append("# Client-side: append actual multi-denomination payment, change, wallet, and full-inventory warnings\n");
        sb.append("# to Waystones teleport cost tooltips.\n");
        sb.append("showPaymentPreviewTooltip = ").append(data.showPaymentPreviewTooltip()).append("\n\n");
        sb.append("# Resolve real-world coordinates for Waystones placed on physics airships/trains/ships\n");
        sb.append("# (Sable / Create: Aeronautics / Simurail and Valkyrien Skies 2), including unloaded & out-of-view ships.\n");
        sb.append("fixPhysicsShipDistance = ").append(data.fixPhysicsShipDistance()).append("\n\n");
        sb.append("# Minecraft 1.20.1 only (Waystones 14.1.x does not support warpRequirements).\n");
        sb.append("# Ignored on Minecraft 1.21.1+ where Waystones natively configures warpRequirements.\n");
        sb.append("[legacy_1_20_1]\n");
        sb.append("# Set to true to enable custom item costs on Minecraft 1.20.1.\n");
        sb.append("enableCustomItemCosts = ").append(data.legacyEnableCustomItemCosts()).append("\n");
        sb.append("# When custom item costs are enabled, waive Waystones' vanilla XP cost.\n");
        sb.append("waiveVanillaXpCost = ").append(data.legacyWaiveVanillaXpCost()).append("\n");
        sb.append("# Format: \"item_id,base_count,per_km_extra,cross_dim_extra\"\n");
        sb.append("itemCosts = [");
        if (!data.legacyItemCosts().isEmpty()) {
            sb.append("\n");
            for (int i = 0; i < data.legacyItemCosts().size(); i++) {
                sb.append("    ").append(SimpleToml.quote(data.legacyItemCosts().get(i).serialize()));
                if (i + 1 < data.legacyItemCosts().size()) sb.append(",");
                sb.append("\n");
            }
        }
        sb.append("]\n");
        return sb.toString();
    }

    private static void writeToml(Path path, Data data) {
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(path, renderToml(data));
        } catch (IOException failure) {
            throw new UncheckedIOException("Unable to write Waystones Item Cost Extended configuration", failure);
        }
    }
}
