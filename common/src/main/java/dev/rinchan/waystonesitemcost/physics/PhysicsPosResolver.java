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

        // Server cold-start path: if target is inside an unloaded Sable Plot with no cached world pos yet,
        // resolve its parked world coordinate from Sable's offline storage before computing distance.
        if (level instanceof ServerLevel serverLevel
            && targetUid != null
            && PhysicsModBridge.isAnyPhysicsModLoaded()
            && cache.get(targetUid, targetDimId) == null) {
            ResourceKey<Level> resolvedTargetDim = targetDim != null ? targetDim : level.dimension();
            ServerLevel targetLevel = serverLevel.getServer().getLevel(resolvedTargetDim);
            if (targetLevel != null
                && PhysicsModBridge.isInPhysicsSpace(targetLevel, targetCenter.x, targetCenter.y, targetCenter.z)
                && PhysicsModBridge.projectToWorld(targetLevel, targetCenter.x, targetCenter.y, targetCenter.z) == null) {
                resolveOfflineLocationOnServer(serverLevel.getServer(), targetUid, resolvedTargetDim, targetPos);
            }
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
        String dimId = PhysicsPosStorage.dimensionId(dimension);
        PhysicsPosStorage storage = PhysicsPosStorage.get(server);
        if (projected != null) {
            storage.put(waystoneUid, dimId, projected);
            return;
        }
        if (storage.get(waystoneUid, dimId) == null
            && PhysicsModBridge.isInPhysicsSpace(level, center.x, center.y, center.z)) {
            PhysicsPosCore.Vec3d offline = PhysicsModBridge.projectOfflineSableToWorld(level, center.x, center.y, center.z);
            if (offline != null) {
                storage.put(waystoneUid, dimId, offline);
            }
        }
    }

    /**
     * Resolves an unloaded Sable SubLevel's parked world position and holding chunk directly from
     * Sable's holding map / offline storage, persisting the world position into {@link PhysicsPosStorage}.
     */
    public static PhysicsModBridge.OfflineSubLevelLocation resolveOfflineLocationOnServer(
        MinecraftServer server,
        UUID waystoneUid,
        ResourceKey<Level> dimension,
        BlockPos pos
    ) {
        return resolveOfflineLocationOnServer(server, waystoneUid, dimension, pos, false);
    }

    public static PhysicsModBridge.OfflineSubLevelLocation resolveOfflineLocationOnServer(
        MinecraftServer server,
        UUID waystoneUid,
        ResourceKey<Level> dimension,
        BlockPos pos,
        boolean bypassNegativeCache
    ) {
        if (server == null || waystoneUid == null || dimension == null || pos == null) {
            return null;
        }
        if (!PaymentConfig.fixPhysicsShipDistance() || !PhysicsModBridge.isAnyPhysicsModLoaded()) {
            return null;
        }
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            return null;
        }
        Vec3 center = Vec3.atCenterOf(pos);
        PhysicsModBridge.OfflineSubLevelLocation loc = PhysicsModBridge.isSableLoaded()
            ? PhysicsModBridge.resolveOfflineSable(level, center.x, center.y, center.z, bypassNegativeCache)
            : null;
        if (loc == null && PhysicsModBridge.isVs2Loaded()) {
            loc = PhysicsModBridge.resolveVs2ShipBounds(level, center.x, center.y, center.z);
        }
        if (loc != null && loc.worldPos() != null) {
            PhysicsPosStorage.get(server).put(waystoneUid, PhysicsPosStorage.dimensionId(dimension), loc.worldPos());
        }
        return loc;
    }

    public static void removeServerPosition(MinecraftServer server, UUID waystoneUid) {
        if (server == null || waystoneUid == null) {
            return;
        }
        PhysicsPosStorage.get(server).remove(waystoneUid);
    }

    public static void clearServerCache(MinecraftServer server) {
        if (server != null) {
            PhysicsPosStorage.get(server).clear();
        }
        PhysicsModBridge.clearOfflineCaches();
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
