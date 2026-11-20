package dev.rinchan.waystonesitemcost.config;

import java.util.Optional;

/**
 * Custom item cost rule for Minecraft 1.20.1 (Waystones 14.1.x, which lacks warpRequirements).
 * Format: "item_id,base_count,per_km_extra,cross_dim_extra"
 * Example: "minecraft:emerald,1,1,2"
 */
public record LegacyCostEntry(String itemId, int baseCount, int perKmExtra, int crossDimExtra) {
    public LegacyCostEntry {
        if (itemId == null || itemId.isBlank()) throw new IllegalArgumentException("itemId required");
        if (baseCount < 0 || perKmExtra < 0 || crossDimExtra < 0) {
            throw new IllegalArgumentException("Counts must not be negative");
        }
    }

    public int calculateCost(double distanceBlocks, boolean crossDimension) {
        long total = baseCount;
        if (perKmExtra > 0 && distanceBlocks > 0) {
            total += (long) Math.floor(distanceBlocks / 1000.0) * perKmExtra;
        }
        if (crossDimension && crossDimExtra > 0) {
            total += crossDimExtra;
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, total));
    }

    public String serialize() {
        return itemId + "," + baseCount + "," + perKmExtra + "," + crossDimExtra;
    }

    public static Optional<LegacyCostEntry> parse(String spec) {
        if (spec == null || spec.isBlank()) return Optional.empty();
        var parts = spec.split(",");
        if (parts.length < 2 || parts.length > 4) return Optional.empty();
        String id = parts[0].trim();
        if (id.isEmpty()) return Optional.empty();
        try {
            int base = Integer.parseInt(parts[1].trim());
            int perKm = parts.length >= 3 ? Integer.parseInt(parts[2].trim()) : 0;
            int crossDim = parts.length >= 4 ? Integer.parseInt(parts[3].trim()) : 0;
            if (base < 0 || perKm < 0 || crossDim < 0) return Optional.empty();
            return Optional.of(new LegacyCostEntry(id, base, perKm, crossDim));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }
}
