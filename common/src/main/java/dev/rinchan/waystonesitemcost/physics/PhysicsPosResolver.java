package dev.rinchan.waystonesitemcost.physics;

import dev.rinchan.waystonesitemcost.config.PaymentConfig;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Unified real-world Waystone coordinate resolver for physics sub-world mods
 * (Sable / Create: Aeronautics / Simurail and Valkyrien Skies 2).
 *
 * Dedicated-server safe: contains zero client-only class references.
 */
public final class PhysicsPosResolver {
    private static volatile Map<UUID, PhysicsPosCore.CachedWorldPos> clientCache = Collections.emptyMap();

    private static final PhysicsPosCore.PosCache CLIENT_CACHE_VIEW = new PhysicsPosCore.PosCache() {
        @Override
        public PhysicsPosCore.Vec3d get(UUID waystoneUid, String dimension) {
            Map<UUID, PhysicsPosCore.CachedWorldPos> cache = clientCache;
            if (cache.isEmpty()) return null;
            var entry = cache.get(waystoneUid);
            if (entry != null && entry.dimension().equals(dimension)) {
                return entry.toVec3d();
            }
            return null;
        }

        @Override
        public void put(UUID waystoneUid, String dimension, PhysicsPosCore.Vec3d worldPos) {
            // Client never mutates authoritative cache from local queries
        }
    };

    private PhysicsPosResolver() {
    }

    public static double resolveDistance(Entity entity, UUID targetUid, ResourceKey<Level> targetDim, BlockPos targetPos) {
        if (entity == null || targetPos == null) {
            return 0.0;
        }
        Vec3 targetCenter = Vec3.atCenterOf(targetPos);
        if (!PaymentConfig.fixPhysicsShipDistance()) {
            return Math.sqrt(entity.distanceToSqr(targetCenter));
        }

        Level level = entity.level();
        String originDimId = PhysicsPosStorage.dimensionId(level.dimension());
        String targetDimId = targetDim != null ? PhysicsPosStorage.dimensionId(targetDim) : originDimId;

        PhysicsPosCore.PosCache cache;
        boolean allowWriteCache;
        if (level instanceof ServerLevel serverLevel) {
            cache = PhysicsPosStorage.get(serverLevel.getServer());
            allowWriteCache = true;
        } else {
            cache = CLIENT_CACHE_VIEW;
            allowWriteCache = false;
        }

        PhysicsPosCore.LiveProjector projector = PhysicsModBridge.isAnyPhysicsModLoaded()
            ? new LevelLiveProjector(level)
            : null;

        // Fast path: no physics mod loaded AND no cached entries -> pure vanilla distance
        if (projector == null && (level.isClientSide() ? clientCache.isEmpty() : isStorageEmpty(cache))) {
            return Math.sqrt(entity.distanceToSqr(targetCenter));
        }

        Vec3 origin = entity.position();
        return PhysicsPosCore.resolveDistance(
            new PhysicsPosCore.Vec3d(origin.x, origin.y, origin.z),
            originDimId,
            new PhysicsPosCore.Vec3d(targetCenter.x, targetCenter.y, targetCenter.z),
            targetUid,
            targetDimId,
            projector,
            cache,
            allowWriteCache
        );
    }

    public static double resolveDistanceSqr(Entity entity, UUID targetUid, ResourceKey<Level> targetDim, BlockPos targetPos) {
        double dist = resolveDistance(entity, targetUid, targetDim, targetPos);
        return dist * dist;
    }

    public static void refreshWaystoneOnServer(MinecraftServer server, UUID waystoneUid, ResourceKey<Level> dimension, BlockPos pos) {
        if (server == null || waystoneUid == null || dimension == null || pos == null) {
            return;
        }
        if (!PaymentConfig.fixPhysicsShipDistance() || !PhysicsModBridge.isAnyPhysicsModLoaded()) {
            return;
        }
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            return;
        }
        Vec3 center = Vec3.atCenterOf(pos);
        PhysicsPosCore.Vec3d projected = PhysicsModBridge.projectToWorld(level, center.x, center.y, center.z);
        if (projected != null) {
            PhysicsPosStorage.get(server).put(waystoneUid, PhysicsPosStorage.dimensionId(dimension), projected);
        }
    }

    /**
     * Records or updates a known world-space coordinate for a Waystone in server SavedData.
     * Used both by live physics refresh and by E2E integration tests.
     */
    public static void recordServerPosition(
        MinecraftServer server,
        UUID waystoneUid,
        ResourceKey<Level> dimension,
        double worldX,
        double worldY,
        double worldZ
    ) {
        if (server == null || waystoneUid == null || dimension == null) {
            return;
        }
        PhysicsPosStorage.get(server).put(
            waystoneUid,
            PhysicsPosStorage.dimensionId(dimension),
            new PhysicsPosCore.Vec3d(worldX, worldY, worldZ)
        );
    }

    public static PhysicsPosCore.Vec3d getCachedWorldPos(MinecraftServer server, UUID waystoneUid, ResourceKey<Level> dimension) {
        if (server == null || waystoneUid == null || dimension == null) {
            return null;
        }
        return PhysicsPosStorage.get(server).get(waystoneUid, PhysicsPosStorage.dimensionId(dimension));
    }

    public static Map<UUID, PhysicsPosCore.CachedWorldPos> snapshotServerEntries(MinecraftServer server) {
        if (server == null) {
            return Collections.emptyMap();
        }
        return PhysicsPosStorage.get(server).snapshot();
    }

    public static void receiveClientSync(Map<UUID, PhysicsPosCore.CachedWorldPos> entries) {
        if (entries == null || entries.isEmpty()) {
            clientCache = Collections.emptyMap();
        } else {
            clientCache = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        }
    }

    private static boolean isStorageEmpty(PhysicsPosCore.PosCache cache) {
        return cache instanceof PhysicsPosStorage storage && storage.isEmpty();
    }

    private record LevelLiveProjector(Level level) implements PhysicsPosCore.LiveProjector {
        @Override
        public boolean isInPhysicsSpace(double x, double y, double z) {
            return PhysicsModBridge.isInPhysicsSpace(level, x, y, z);
        }

        @Override
        public PhysicsPosCore.Vec3d projectToWorld(double x, double y, double z) {
            return PhysicsModBridge.projectToWorld(level, x, y, z);
        }
    }
}
