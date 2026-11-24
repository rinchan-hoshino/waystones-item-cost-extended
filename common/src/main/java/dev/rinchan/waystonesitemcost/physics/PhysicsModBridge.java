package dev.rinchan.waystonesitemcost.physics;

import dev.rinchan.waystonesitemcost.MixinGuard;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Zero-hard-dependency reflection bridge for physics sub-world mods:
 * - Sable (dev.ryanhcode.sable, used by Create: Aeronautics / Simurail / Simulated)
 * - Valkyrien Skies 2 (org.valkyrienskies.mod.common.VSGameUtilsKt)
 *
 * When neither mod is installed, {@link #isAnyPhysicsModLoaded()} is false and all queries
 * return immediately with zero allocations and zero exceptions.
 */
public final class PhysicsModBridge {
    private static volatile boolean initialized;
    private static volatile boolean sableLoaded;
    private static volatile boolean vs2Loaded;

    // Sable handles
    private static Object sableHelper;
    private static MethodHandle sableGetContainingVec3i;
    private static MethodHandle sableGetContainingPosition;
    private static MethodHandle sableProjectOutOfSubLevel;
    private static MethodHandle sableStaticGetContainer;
    private static MethodHandle sableGetSubLevelContainer;
    private static MethodHandle sableContainerInBoundsBlockPos;
    private static MethodHandle sableStaticInBoundsBlockPos;
    private static Class<?> sableServerContainerClass;
    private static MethodHandle sableGetHoldingChunkMap;
    private static MethodHandle sableHoldingUpdateChunkStatus;
    private static MethodHandle sableHoldingProcessChanges;
    private static MethodHandle sableContainerGetLogPlotSize;
    private static MethodHandle sableContainerGetOrigin;
    private static MethodHandle sableContainerGetIndex;
    private static MethodHandle sableContainerGetOccupancy;
    private static MethodHandle jomlVec2iX;
    private static MethodHandle jomlVec2iY;
    private static MethodHandle sableHoldingGetStorage;
    private static MethodHandle sableStorageGetFolder;
    private static Field sableHoldingAllSubLevelsField;
    private static MethodHandle sableHoldingSubLevelData;
    private static MethodHandle sableHoldingSubLevelPointer;
    private static MethodHandle sablePointerChunkPos;
    private static MethodHandle sablePointerStorageIndex;
    private static MethodHandle sablePointerSubLevelIndex;
    private static MethodHandle sableGlobalPtrCtor;
    private static MethodHandle sableHoldingSnatchAndLoad;
    private static MethodHandle sableHoldingGetHoldingSubLevel;
    private static MethodHandle sableDataFullTag;
    private static MethodHandle sableDataOriginChunk;
    private static MethodHandle sableDataUuid;
    private static MethodHandle sableDataBounds;
    private static MethodHandle sableBoundsMinX;
    private static MethodHandle sableBoundsMinZ;
    private static MethodHandle sableBoundsMaxX;
    private static MethodHandle sableBoundsMaxZ;
    private static MethodHandle runDistanceManagerUpdatesHandle;

    private static final long OFFLINE_CACHE_TTL_MS = 30_000L;
    private static final long NEGATIVE_CACHE_TTL_MS = 60_000L;

    public record OfflineSubLevelLocation(
        PhysicsPosCore.Vec3d worldPos,
        ChunkPos holdingChunk,
        UUID subLevelUuid,
        int storageIdx,
        int subLevelIdx,
        int minChunkX,
        int minChunkZ,
        int maxChunkX,
        int maxChunkZ
    ) {
        public boolean hasChunkBounds() {
            return minChunkX <= maxChunkX && minChunkZ <= maxChunkZ;
        }
    }

    private record OfflinePoseEntry(
        SableOfflineStorage.OfflineHeader header,
        ChunkPos holdingChunk,
        int storageIdx,
        int subLevelIdx,
        long cachedAtMs
    ) {
        int plotX() {
            return header.plotX();
        }

        int plotZ() {
            return header.plotZ();
        }

        PhysicsPosCore.Vec3d project(double x, double y, double z) {
            return header.project(x, y, z);
        }

        ChunkPos centerChunk() {
            SableOfflineStorage.ChunkCoords c = header.centerChunk();
            return new ChunkPos(c.x(), c.z());
        }

        ChunkPos effectiveHoldingChunk() {
            return holdingChunk != null ? holdingChunk : centerChunk();
        }

        OfflineSubLevelLocation toLocation(double x, double y, double z) {
            PhysicsPosCore.Vec3d projected = project(x, y, z);
            if (projected == null) {
                return null;
            }
            SableOfflineStorage.ChunkBounds bounds = header.worldChunkBounds();
            return new OfflineSubLevelLocation(
                projected,
                effectiveHoldingChunk(),
                header.subLevelUuid(),
                storageIdx,
                subLevelIdx,
                bounds.minChunkX(),
                bounds.minChunkZ(),
                bounds.maxChunkX(),
                bounds.maxChunkZ()
            );
        }
    }

    private static final ConcurrentHashMap<String, OfflinePoseEntry> OFFLINE_POSE_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> NEGATIVE_OFFLINE_CACHE = new ConcurrentHashMap<>();

    // Valkyrien Skies 2 handles
    private static MethodHandle vs2IsBlockInShipyard;
    private static MethodHandle vs2GetShipManagingPos;
    private static MethodHandle vs2GetShipObjectWorld;
    private static MethodHandle vs2GetAllShips;
    private static MethodHandle vs2AllShipsGetByChunkPos;
    private static MethodHandle vs2GetTransform;
    private static MethodHandle vs2GetShipToWorld;
    private static MethodHandle vs2TransformPosition;
    private static MethodHandle vs2GetWorldAABB;
    private static MethodHandle vs2AabbMinX;
    private static MethodHandle vs2AabbMinZ;
    private static MethodHandle vs2AabbMaxX;
    private static MethodHandle vs2AabbMaxZ;
    private static MethodHandle jomlVec3dCtor;
    private static MethodHandle jomlVec3dX;
    private static MethodHandle jomlVec3dY;
    private static MethodHandle jomlVec3dZ;

    private PhysicsModBridge() {
    }

    public static boolean isAnyPhysicsModLoaded() {
        ensureInitialized();
        return sableLoaded || vs2Loaded;
    }

    public static boolean isSableLoaded() {
        ensureInitialized();
        return sableLoaded;
    }

    public static boolean isVs2Loaded() {
        ensureInitialized();
        return vs2Loaded;
    }

    public static boolean isInPhysicsSpace(Level level, double x, double y, double z) {
        ensureInitialized();
        if (!sableLoaded && !vs2Loaded) {
            return false;
        }
        if (level == null) {
            return false;
        }
        BlockPos blockPos = BlockPos.containing(x, y, z);
        if (sableLoaded && isInSablePlot(level, blockPos)) {
            return true;
        }
        if (vs2Loaded && isInVs2Shipyard(level, blockPos)) {
            return true;
        }
        return false;
    }

    public static PhysicsPosCore.Vec3d projectToWorld(Level level, double x, double y, double z) {
        ensureInitialized();
        if (!sableLoaded && !vs2Loaded) {
            return null;
        }
        if (level == null) {
            return null;
        }
        if (sableLoaded) {
            PhysicsPosCore.Vec3d projected = projectSable(level, x, y, z);
            if (projected != null) {
                return projected;
            }
        }
        if (vs2Loaded) {
            PhysicsPosCore.Vec3d projected = projectVs2(level, x, y, z);
            if (projected != null) {
                return projected;
            }
        }
        return null;
    }

    private static void ensureInitialized() {
        if (initialized) {
            return;
        }
        synchronized (PhysicsModBridge.class) {
            if (initialized) {
                return;
            }
            initSable();
            initVs2();
            initialized = true;
        }
    }

    private static void initSable() {
        try {
            Class<?> sableClass = Class.forName("dev.ryanhcode.sable.Sable");
            Field helperField = sableClass.getField("HELPER");
            Object helper = helperField.get(null);
            if (helper == null) {
                return;
            }
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Class<?> helperClass = helper.getClass();

            Class<?> subLevelClass = findClass(
                "dev.ryanhcode.sable.sublevel.SubLevel",
                "dev.ryanhcode.sable.companion.SubLevelAccess"
            );
            Class<?> subLevelAccessClass = findClass("dev.ryanhcode.sable.companion.SubLevelAccess");

            // Use MethodHandles.Lookup.findVirtual/findStatic directly instead of Class.getMethod:
            // Sable's ActiveSableCompanion and SubLevelContainer also declare ClientLevel overloads,
            // and Class.getMethod forces JVM resolution of all sibling method signatures, which triggers
            // NeoForge RuntimeDistCleaner on Dedicated Servers!
            if (subLevelClass != null) {
                sableGetContainingVec3i = findVirtualExact(lookup, helperClass, "getContaining", subLevelClass, Level.class, Vec3i.class);
                if (sableGetContainingVec3i == null) {
                    sableGetContainingVec3i = findVirtualExact(lookup, helperClass, "getContaining", subLevelClass, Level.class, BlockPos.class);
                }
                sableGetContainingPosition = findVirtualExact(lookup, helperClass, "getContaining", subLevelClass, Level.class, Position.class);
                if (sableGetContainingPosition == null) {
                    sableGetContainingPosition = findVirtualExact(lookup, helperClass, "getContaining", subLevelClass, Level.class, Vec3.class);
                }
            }
            if (sableGetContainingVec3i == null && subLevelAccessClass != null) {
                sableGetContainingVec3i = findVirtualExact(lookup, helperClass, "getContaining", subLevelAccessClass, Level.class, Vec3i.class);
            }
            if (sableGetContainingPosition == null && subLevelAccessClass != null) {
                sableGetContainingPosition = findVirtualExact(lookup, helperClass, "getContaining", subLevelAccessClass, Level.class, Position.class);
            }
            sableProjectOutOfSubLevel = findVirtualExact(lookup, helperClass, "projectOutOfSubLevel", Vec3.class, Level.class, Vec3.class);
            if (sableProjectOutOfSubLevel == null) {
                sableProjectOutOfSubLevel = findVirtualExact(lookup, helperClass, "projectOutOfSubLevel", Vec3.class, Level.class, Position.class);
            }

            Class<?> containerClass = findClass(
                "dev.ryanhcode.sable.api.sublevel.SubLevelContainer",
                "dev.ryanhcode.sable.sublevel.SubLevelContainer"
            );
            if (containerClass != null) {
                sableStaticGetContainer = findStaticExact(lookup, containerClass, "getContainer", containerClass, Level.class);
                sableGetSubLevelContainer = findVirtualExact(lookup, helperClass, "getSubLevelContainer", containerClass, Level.class);
                sableContainerInBoundsBlockPos = findVirtualExact(lookup, containerClass, "inBounds", boolean.class, BlockPos.class);
                sableStaticInBoundsBlockPos = findStaticExact(lookup, containerClass, "inBounds", boolean.class, BlockPos.class);
            }

            try {
                Class<?> serverContainer = Class.forName("dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer");
                Class<?> holdingMapClass = Class.forName("dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap");
                MethodHandle getHolding = findVirtualExact(lookup, serverContainer, "getHoldingChunkMap", holdingMapClass);
                MethodHandle updateStatus = findVirtualExact(lookup, holdingMapClass, "updateChunkStatus", void.class, ChunkPos.class, boolean.class);
                MethodHandle processChanges = findVirtualExact(lookup, holdingMapClass, "processChanges", void.class);
                if (getHolding != null && updateStatus != null && processChanges != null) {
                    sableServerContainerClass = serverContainer;
                    sableGetHoldingChunkMap = getHolding;
                    sableHoldingUpdateChunkStatus = updateStatus;
                    sableHoldingProcessChanges = processChanges;
                }
                if (containerClass != null) {
                    sableContainerGetLogPlotSize = findVirtualExact(lookup, containerClass, "getLogPlotSize", int.class);
                    sableContainerGetIndex = findVirtualExact(lookup, containerClass, "getIndex", int.class, int.class, int.class);
                    sableContainerGetOccupancy = findVirtualExact(lookup, containerClass, "getOccupancy", BitSet.class);
                    Class<?> vec2iClass = findClass("org.joml.Vector2i");
                    if (vec2iClass != null) {
                        sableContainerGetOrigin = findVirtualExact(lookup, containerClass, "getOrigin", vec2iClass);
                        jomlVec2iX = lookup.findGetter(vec2iClass, "x", int.class);
                        jomlVec2iY = lookup.findGetter(vec2iClass, "y", int.class);
                    }
                }
                Class<?> storageClass = findClass("dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelStorage");
                if (storageClass != null) {
                    sableHoldingGetStorage = findVirtualExact(lookup, holdingMapClass, "getStorage", storageClass);
                    sableStorageGetFolder = findVirtualExact(lookup, storageClass, "getFolder", Path.class);
                }
                try {
                    Field allHoldingField = holdingMapClass.getDeclaredField("allHoldingSubLevels");
                    allHoldingField.setAccessible(true);
                    sableHoldingAllSubLevelsField = allHoldingField;
                } catch (Throwable ignored) {
                    // Optional RAM-holding-map inspection
                }
                Class<?> holdingSubLevelClass = findClass("dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel");
                Class<?> subLevelDataClass = findClass("dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData");
                Class<?> globalPtrClass = findClass("dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer");
                Class<?> boundsClass = findClass(
                    "dev.ryanhcode.sable.companion.math.BoundingBox3d",
                    "dev.ryanhcode.sable.companion.math.BoundingBox3dc"
                );
                if (holdingSubLevelClass != null && subLevelDataClass != null) {
                    sableHoldingSubLevelData = findVirtualExact(lookup, holdingSubLevelClass, "data", subLevelDataClass);
                    sableDataFullTag = findVirtualExact(lookup, subLevelDataClass, "fullTag", CompoundTag.class);
                    sableDataOriginChunk = findVirtualExact(lookup, subLevelDataClass, "getOriginLoadedChunk", ChunkPos.class);
                    sableDataUuid = findVirtualExact(lookup, subLevelDataClass, "uuid", UUID.class);
                    if (boundsClass != null) {
                        sableDataBounds = findVirtualExact(lookup, subLevelDataClass, "bounds", boundsClass);
                        sableBoundsMinX = findVirtualExact(lookup, boundsClass, "minX", double.class);
                        sableBoundsMinZ = findVirtualExact(lookup, boundsClass, "minZ", double.class);
                        sableBoundsMaxX = findVirtualExact(lookup, boundsClass, "maxX", double.class);
                        sableBoundsMaxZ = findVirtualExact(lookup, boundsClass, "maxZ", double.class);
                    }
                    if (globalPtrClass != null) {
                        sableHoldingSubLevelPointer = findVirtualExact(lookup, holdingSubLevelClass, "pointer", globalPtrClass);
                        sablePointerChunkPos = findVirtualExact(lookup, globalPtrClass, "chunkPos", ChunkPos.class);
                        sablePointerStorageIndex = findVirtualExact(lookup, globalPtrClass, "storageIndex", short.class);
                        sablePointerSubLevelIndex = findVirtualExact(lookup, globalPtrClass, "subLevelIndex", short.class);
                        try {
                            sableGlobalPtrCtor = lookup.findConstructor(
                                globalPtrClass, MethodType.methodType(void.class, ChunkPos.class, short.class, short.class));
                        } catch (Throwable ignored) {
                            // Optional pointer constructor
                        }
                        sableHoldingSnatchAndLoad = findVirtualExact(
                            lookup, holdingMapClass, "snatchAndLoad", void.class, globalPtrClass, UUID.class);
                    }
                    sableHoldingGetHoldingSubLevel = findVirtualExact(
                        lookup, holdingMapClass, "getHoldingSubLevel", holdingSubLevelClass, UUID.class);
                }
                runDistanceManagerUpdatesHandle = findVirtualExact(
                    lookup, ServerChunkCache.class, "runDistanceManagerUpdates", boolean.class);
            } catch (Throwable ignored) {
                // Optional Sable server holding-chunk wakeup & offline cold-start bridge
            }

            if (sableProjectOutOfSubLevel != null && (sableGetContainingVec3i != null || sableGetContainingPosition != null)) {
                sableHelper = helper;
                sableLoaded = true;
                MixinGuard.LOGGER.info("[Waystones Item Cost Extended] Detected Sable physics sub-world engine; real-world Waystone distance projection enabled.");
            }
        } catch (ClassNotFoundException ignored) {
            // Sable not installed
        } catch (Throwable t) {
            MixinGuard.logOnce("PhysicsModBridge.initSable", t);
        }
    }

    private static void initVs2() {
        try {
            Class<?> vsUtils = Class.forName("org.valkyrienskies.mod.common.VSGameUtilsKt");
            Class<?> vec3dClass = Class.forName("org.joml.Vector3d");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();

            vs2IsBlockInShipyard = findStatic(lookup, vsUtils, "isBlockInShipyard", Level.class, BlockPos.class);
            vs2GetShipManagingPos = findStatic(lookup, vsUtils, "getShipManagingPos", Level.class, BlockPos.class);
            vs2GetShipObjectWorld = findStatic(lookup, vsUtils, "getShipObjectWorld", Level.class);
            if (vs2GetShipObjectWorld == null) {
                vs2GetShipObjectWorld = findStatic(lookup, vsUtils, "getShipObjectWorld", ServerLevel.class);
            }
            jomlVec3dCtor = lookup.findConstructor(vec3dClass, MethodType.methodType(void.class, double.class, double.class, double.class));
            jomlVec3dX = lookup.findGetter(vec3dClass, "x", double.class);
            jomlVec3dY = lookup.findGetter(vec3dClass, "y", double.class);
            jomlVec3dZ = lookup.findGetter(vec3dClass, "z", double.class);

            if (vs2GetShipManagingPos != null) {
                vs2Loaded = true;
                MixinGuard.LOGGER.info("[Waystones Item Cost Extended] Detected Valkyrien Skies 2; real-world Waystone distance projection enabled.");
            }
        } catch (ClassNotFoundException ignored) {
            // VS2 not installed
        } catch (Throwable t) {
            MixinGuard.logOnce("PhysicsModBridge.initVs2", t);
        }
    }

    private static Object getSableContainer(Level level) throws Throwable {
        if (sableStaticGetContainer != null) {
            return sableStaticGetContainer.invoke(level);
        }
        if (sableGetSubLevelContainer != null && sableHelper != null) {
            return sableGetSubLevelContainer.invoke(sableHelper, level);
        }
        return null;
    }

    private static boolean isInSablePlot(Level level, BlockPos pos) {
        try {
            if (sableContainerInBoundsBlockPos != null) {
                Object container = getSableContainer(level);
                if (container != null) {
                    return (boolean) sableContainerInBoundsBlockPos.invoke(container, pos);
                }
            }
            if (sableStaticInBoundsBlockPos != null) {
                return (boolean) sableStaticInBoundsBlockPos.invoke(pos);
            }
        } catch (Throwable t) {
            MixinGuard.logOnce("PhysicsModBridge.isInSablePlot", t);
        }
        return false;
    }

    /**
     * Propagates chunk tickets to DistanceManager and wakes Sable's ServerSubLevelContainer
     * across the full world-space bounding box of {@code loc} (falling back to {@code snatchAndLoad}
     * if {@code updateChunkStatus}/{@code processChanges} did not yet load the SubLevel).
     */
    public static boolean wakeSableSubLevel(
        ServerLevel level,
        OfflineSubLevelLocation loc,
        double plotX,
        double plotY,
        double plotZ
    ) {
        ensureInitialized();
        if (!sableLoaded || level == null || loc == null) {
            return false;
        }
        if (sableServerContainerClass == null || sableGetHoldingChunkMap == null
            || sableHoldingUpdateChunkStatus == null || sableHoldingProcessChanges == null) {
            return false;
        }
        try {
            if (runDistanceManagerUpdatesHandle != null) {
                try {
                    runDistanceManagerUpdatesHandle.invoke(level.getChunkSource());
                } catch (Throwable ignored) {
                    // Best-effort ticket propagation
                }
            }
            Object container = getSableContainer(level);
            if (container == null || !sableServerContainerClass.isInstance(container)) {
                return false;
            }
            Object holdingMap = sableGetHoldingChunkMap.invoke(container);
            if (holdingMap == null) {
                return false;
            }
            if (loc.hasChunkBounds()) {
                for (int cx = loc.minChunkX(); cx <= loc.maxChunkX(); cx++) {
                    for (int cz = loc.minChunkZ(); cz <= loc.maxChunkZ(); cz++) {
                        sableHoldingUpdateChunkStatus.invoke(holdingMap, new ChunkPos(cx, cz), true);
                    }
                }
            }
            if (loc.holdingChunk() != null) {
                sableHoldingUpdateChunkStatus.invoke(holdingMap, loc.holdingChunk(), true);
            }
            sableHoldingProcessChanges.invoke(holdingMap);

            if (projectToWorld(level, plotX, plotY, plotZ) == null
                && loc.subLevelUuid() != null
                && sableHoldingSnatchAndLoad != null) {
                Object pointer = null;
                if (sableHoldingGetHoldingSubLevel != null && sableHoldingSubLevelPointer != null) {
                    Object holdingSub = sableHoldingGetHoldingSubLevel.invoke(holdingMap, loc.subLevelUuid());
                    if (holdingSub != null) {
                        pointer = sableHoldingSubLevelPointer.invoke(holdingSub);
                    }
                }
                if (pointer == null
                    && sableGlobalPtrCtor != null
                    && loc.holdingChunk() != null
                    && loc.storageIdx() >= 0
                    && loc.subLevelIdx() >= 0) {
                    pointer = sableGlobalPtrCtor.invoke(
                        loc.holdingChunk(), (short) loc.storageIdx(), (short) loc.subLevelIdx());
                }
                if (pointer != null) {
                    sableHoldingSnatchAndLoad.invoke(holdingMap, pointer, loc.subLevelUuid());
                }
            }
            return true;
        } catch (Throwable t) {
            MixinGuard.logOnce("PhysicsModBridge.wakeSableSubLevel", t);
            return false;
        }
    }

    /**
     * Prompts Sable's ServerSubLevelContainer holding-chunk map to process loaded world chunks
     * around {@code centerChunk} so an unloaded airship SubLevel wakes up before teleport validation.
     */
    public static boolean wakeSableSubLevelAtWorldChunk(Level level, ChunkPos centerChunk, int chunkRadius) {
        ensureInitialized();
        if (!sableLoaded || level == null || centerChunk == null) {
            return false;
        }
        if (sableServerContainerClass == null || sableGetHoldingChunkMap == null
            || sableHoldingUpdateChunkStatus == null || sableHoldingProcessChanges == null) {
            return false;
        }
        try {
            Object container = getSableContainer(level);
            if (container == null || !sableServerContainerClass.isInstance(container)) {
                return false;
            }
            Object holdingMap = sableGetHoldingChunkMap.invoke(container);
            if (holdingMap == null) {
                return false;
            }
            int centerX = centerChunk.getMinBlockX() >> 4;
            int centerZ = centerChunk.getMinBlockZ() >> 4;
            int r = Math.max(0, Math.min(chunkRadius, 2));
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    sableHoldingUpdateChunkStatus.invoke(holdingMap, new ChunkPos(centerX + dx, centerZ + dz), true);
                }
            }
            sableHoldingProcessChanges.invoke(holdingMap);
            return true;
        } catch (Throwable t) {
            MixinGuard.logOnce("PhysicsModBridge.wakeSableSubLevelAtWorldChunk", t);
            return false;
        }
    }

    public static void clearOfflineCaches() {
        OFFLINE_POSE_CACHE.clear();
        NEGATIVE_OFFLINE_CACHE.clear();
    }

    public static PhysicsPosCore.Vec3d projectOfflineSableToWorld(Level level, double x, double y, double z) {
        OfflineSubLevelLocation loc = resolveOfflineSable(level, x, y, z);
        return loc != null ? loc.worldPos() : null;
    }

    /**
     * Cold-start resolver for an unloaded Sable SubLevel whose Waystone has no live projection
     * and no cached world coordinate yet.
     *
     * Resolution order:
     * 1. O(1) plot-occupancy BitSet check (returns null immediately if SubLevel was destroyed).
     * 2. Negative-cache check (prevents repeated disk scans for deleted/orphaned plot Waystones).
     * 3. In-memory {@code SubLevelHoldingChunkMap.allHoldingSubLevels} (recently unloaded SubLevels
     *    not yet flushed to disk).
     * 4. Read-only single-pass scan of {@code <sublevels>/*.slvls} (and external {@code *.s/<idx>.slvl})
     *    indexing every offline SubLevel's saved {@code plot} and {@code pose} NBT without acquiring
     *    write locks or mutating Sable's storage cache.
     */
    public static OfflineSubLevelLocation resolveOfflineSable(Level level, double x, double y, double z) {
        return resolveOfflineSable(level, x, y, z, false);
    }

    public static OfflineSubLevelLocation resolveOfflineSable(
        Level level,
        double x,
        double y,
        double z,
        boolean bypassNegativeCache
    ) {
        ensureInitialized();
        if (!sableLoaded || level == null || level.isClientSide()) {
            return null;
        }
        if (sableServerContainerClass == null || sableGetHoldingChunkMap == null
            || sableContainerGetLogPlotSize == null || sableContainerGetOrigin == null) {
            return null;
        }
        try {
            Object container = getSableContainer(level);
            if (container == null || !sableServerContainerClass.isInstance(container)) {
                return null;
            }
            BlockPos bp = BlockPos.containing(x, y, z);
            int logPlotSize = (int) sableContainerGetLogPlotSize.invoke(container);
            Object origin = sableContainerGetOrigin.invoke(container);
            if (origin == null || jomlVec2iX == null || jomlVec2iY == null) {
                return null;
            }
            int originX = (int) jomlVec2iX.invoke(origin);
            int originZ = (int) jomlVec2iY.invoke(origin);
            int localPlotX = ((bp.getX() >> 4) >> logPlotSize) - originX;
            int localPlotZ = ((bp.getZ() >> 4) >> logPlotSize) - originZ;

            // O(1) destroyed-plot check via Sable's occupancy BitSet
            if (sableContainerGetIndex != null && sableContainerGetOccupancy != null) {
                int plotIndex = (int) sableContainerGetIndex.invoke(container, localPlotX, localPlotZ);
                BitSet occupancy = (BitSet) sableContainerGetOccupancy.invoke(container);
                if (occupancy != null && (plotIndex < 0 || !occupancy.get(plotIndex))) {
                    return null;
                }
            }

            String dimId = PhysicsPosStorage.dimensionId(level.dimension());
            String plotKey = dimId + "#" + localPlotX + "," + localPlotZ;
            long now = System.currentTimeMillis();

            if (bypassNegativeCache) {
                NEGATIVE_OFFLINE_CACHE.remove(plotKey);
            } else {
                Long negTs = NEGATIVE_OFFLINE_CACHE.get(plotKey);
                if (negTs != null && (now - negTs) < NEGATIVE_CACHE_TTL_MS) {
                    return null;
                }
            }

            Object holdingMap = sableGetHoldingChunkMap.invoke(container);
            if (holdingMap == null) {
                return null;
            }

            // Tier 1: In-memory unloaded SubLevels (before saveAll flushes to disk)
            OfflineSubLevelLocation inMem = findInMemoryHoldingSubLevel(holdingMap, localPlotX, localPlotZ, x, y, z, now);
            if (inMem != null) {
                NEGATIVE_OFFLINE_CACHE.remove(plotKey);
                return inMem;
            }

            // Tier 2a: Short-lived offline pose cache populated by a recent read-only scan
            OfflinePoseEntry cached = OFFLINE_POSE_CACHE.get(plotKey);
            if (cached != null && (now - cached.cachedAtMs()) < OFFLINE_CACHE_TTL_MS) {
                OfflineSubLevelLocation loc = cached.toLocation(x, y, z);
                if (loc != null) {
                    return loc;
                }
            }

            // Tier 2b: Read-only scan of world/sublevels/*.slvlr + *.slvls
            if (sableHoldingGetStorage != null && sableStorageGetFolder != null) {
                Object storage = sableHoldingGetStorage.invoke(holdingMap);
                Path folder = storage != null ? (Path) sableStorageGetFolder.invoke(storage) : null;
                if (folder != null && Files.isDirectory(folder)) {
                    scanSlvlsDirectoryReadOnly(folder, dimId, now);
                    OfflinePoseEntry scanned = OFFLINE_POSE_CACHE.get(plotKey);
                    if (scanned != null) {
                        OfflineSubLevelLocation loc = scanned.toLocation(x, y, z);
                        if (loc != null) {
                            NEGATIVE_OFFLINE_CACHE.remove(plotKey);
                            return loc;
                        }
                    }
                }
            }

            NEGATIVE_OFFLINE_CACHE.put(plotKey, now);
            return null;
        } catch (Throwable t) {
            MixinGuard.logOnce("PhysicsModBridge.resolveOfflineSable", t);
            return null;
        }
    }

    private static OfflineSubLevelLocation findInMemoryHoldingSubLevel(
        Object holdingMap,
        int targetPlotX,
        int targetPlotZ,
        double x,
        double y,
        double z,
        long now
    ) {
        if (sableHoldingAllSubLevelsField == null || sableHoldingSubLevelData == null || sableDataFullTag == null) {
            return null;
        }
        try {
            Object mapObj = sableHoldingAllSubLevelsField.get(holdingMap);
            if (!(mapObj instanceof Map<?, ?> map) || map.isEmpty()) {
                return null;
            }
            for (Object holdingSubLevel : map.values()) {
                if (holdingSubLevel == null) continue;
                Object data = sableHoldingSubLevelData.invoke(holdingSubLevel);
                if (data == null) continue;
                CompoundTag fullTag = (CompoundTag) sableDataFullTag.invoke(data);
                OfflinePoseEntry entry = parseOfflinePoseFromTag(fullTag, now);
                if (entry == null || entry.plotX() != targetPlotX || entry.plotZ() != targetPlotZ) {
                    continue;
                }
                PhysicsPosCore.Vec3d projected = entry.project(x, y, z);
                if (projected == null) {
                    continue;
                }
                UUID uuid = entry.header().subLevelUuid();
                if (uuid == null && sableDataUuid != null) {
                    uuid = (UUID) sableDataUuid.invoke(data);
                }
                ChunkPos holdingChunk = null;
                int storageIdx = -1;
                int subLevelIdx = -1;
                if (sableHoldingSubLevelPointer != null && sablePointerChunkPos != null) {
                    Object ptr = sableHoldingSubLevelPointer.invoke(holdingSubLevel);
                    if (ptr != null) {
                        holdingChunk = (ChunkPos) sablePointerChunkPos.invoke(ptr);
                        if (sablePointerStorageIndex != null) {
                            storageIdx = (short) sablePointerStorageIndex.invoke(ptr);
                        }
                        if (sablePointerSubLevelIndex != null) {
                            subLevelIdx = (short) sablePointerSubLevelIndex.invoke(ptr);
                        }
                    }
                }
                if (holdingChunk == null && sableDataOriginChunk != null) {
                    holdingChunk = (ChunkPos) sableDataOriginChunk.invoke(data);
                }
                if (holdingChunk == null) {
                    holdingChunk = entry.centerChunk();
                }
                SableOfflineStorage.ChunkBounds bounds = readInMemoryBounds(data, entry.header());
                return new OfflineSubLevelLocation(
                    projected,
                    holdingChunk,
                    uuid,
                    storageIdx,
                    subLevelIdx,
                    bounds.minChunkX(),
                    bounds.minChunkZ(),
                    bounds.maxChunkX(),
                    bounds.maxChunkZ()
                );
            }
        } catch (Throwable ignored) {
            // Fall through to disk scan
        }
        return null;
    }

    private static SableOfflineStorage.ChunkBounds readInMemoryBounds(
        Object data,
        SableOfflineStorage.OfflineHeader header
    ) {
        if (sableDataBounds != null && sableBoundsMinX != null && sableBoundsMinZ != null
            && sableBoundsMaxX != null && sableBoundsMaxZ != null) {
            try {
                Object box = sableDataBounds.invoke(data);
                if (box != null) {
                    double minX = (double) sableBoundsMinX.invoke(box);
                    double minZ = (double) sableBoundsMinZ.invoke(box);
                    double maxX = (double) sableBoundsMaxX.invoke(box);
                    double maxZ = (double) sableBoundsMaxZ.invoke(box);
                    return SableOfflineStorage.ChunkBounds.fromWorldAabb(minX, minZ, maxX, maxZ, header.centerChunk());
                }
            } catch (Throwable ignored) {
                // Fall back to header bounds
            }
        }
        return header.worldChunkBounds();
    }

    private static void scanSlvlsDirectoryReadOnly(Path folder, String dimId, long now) {
        List<SableOfflineStorage.ScannedSubLevel> scanned = SableOfflineStorage.scanDirectoryReadOnly(
            folder,
            PhysicsPosStorage::readSableRegionPointers,
            PhysicsPosStorage::readSableOfflineHeader
        );
        for (SableOfflineStorage.ScannedSubLevel item : scanned) {
            SableOfflineStorage.OfflineHeader header = item.header();
            SableOfflineStorage.ChunkCoords hc = item.holdingChunk();
            ChunkPos holdingChunk = hc != null ? new ChunkPos(hc.x(), hc.z()) : null;
            OfflinePoseEntry entry = new OfflinePoseEntry(header, holdingChunk, item.storageIdx(), item.subLevelIdx(), now);
            OFFLINE_POSE_CACHE.put(dimId + "#" + entry.plotX() + "," + entry.plotZ(), entry);
        }
    }

    private static OfflinePoseEntry parseOfflinePoseFromTag(CompoundTag tag, long now) {
        if (tag == null) {
            return null;
        }
        CompoundTag plotTag = PhysicsPosStorage.nbtCompound(tag, "plot");
        CompoundTag poseTag = PhysicsPosStorage.nbtCompound(tag, "pose");
        if (plotTag == null || poseTag == null) {
            return null;
        }
        Integer plotX = PhysicsPosStorage.nbtInt(plotTag, "plot_x");
        Integer plotZ = PhysicsPosStorage.nbtInt(plotTag, "plot_z");
        if (plotX == null || plotZ == null) {
            return null;
        }
        CompoundTag posTag = PhysicsPosStorage.nbtCompound(poseTag, "position");
        CompoundTag rotTag = PhysicsPosStorage.nbtCompound(poseTag, "rotation_point");
        CompoundTag oriTag = PhysicsPosStorage.nbtCompound(poseTag, "orientation");
        if (posTag == null || rotTag == null) {
            return null;
        }
        double posX = PhysicsPosStorage.nbtDouble(posTag, "x", 0.0);
        double posY = PhysicsPosStorage.nbtDouble(posTag, "y", 0.0);
        double posZ = PhysicsPosStorage.nbtDouble(posTag, "z", 0.0);
        double rotX = PhysicsPosStorage.nbtDouble(rotTag, "x", 0.0);
        double rotY = PhysicsPosStorage.nbtDouble(rotTag, "y", 0.0);
        double rotZ = PhysicsPosStorage.nbtDouble(rotTag, "z", 0.0);
        double qx = oriTag != null ? PhysicsPosStorage.nbtDouble(oriTag, "x", 0.0) : 0.0;
        double qy = oriTag != null ? PhysicsPosStorage.nbtDouble(oriTag, "y", 0.0) : 0.0;
        double qz = oriTag != null ? PhysicsPosStorage.nbtDouble(oriTag, "z", 0.0) : 0.0;
        double qw = oriTag != null ? PhysicsPosStorage.nbtDouble(oriTag, "w", 1.0) : 1.0;

        CompoundTag boundsTag = PhysicsPosStorage.nbtCompound(tag, "world_bounds");
        boolean hasBounds = boundsTag != null;
        double minX = hasBounds ? PhysicsPosStorage.nbtDouble(boundsTag, "minX", 0.0) : 0.0;
        double minZ = hasBounds ? PhysicsPosStorage.nbtDouble(boundsTag, "minZ", 0.0) : 0.0;
        double maxX = hasBounds ? PhysicsPosStorage.nbtDouble(boundsTag, "maxX", 0.0) : 0.0;
        double maxZ = hasBounds ? PhysicsPosStorage.nbtDouble(boundsTag, "maxZ", 0.0) : 0.0;

        SableOfflineStorage.OfflineHeader header = new SableOfflineStorage.OfflineHeader(
            null, plotX, plotZ, posX, posY, posZ, qx, qy, qz, qw, rotX, rotY, rotZ,
            hasBounds, minX, minZ, maxX, maxZ
        );
        return new OfflinePoseEntry(header, null, -1, -1, now);
    }

    private static PhysicsPosCore.Vec3d projectSable(Level level, double x, double y, double z) {
        try {
            if (sableHelper == null || sableProjectOutOfSubLevel == null) {
                return null;
            }
            Object containing = null;
            if (sableGetContainingVec3i != null) {
                containing = sableGetContainingVec3i.invoke(sableHelper, level, BlockPos.containing(x, y, z));
            } else if (sableGetContainingPosition != null) {
                containing = sableGetContainingPosition.invoke(sableHelper, level, new Vec3(x, y, z));
            }
            if (containing == null) {
                return null;
            }
            Object projectedObj = sableProjectOutOfSubLevel.invoke(sableHelper, level, new Vec3(x, y, z));
            if (projectedObj instanceof Vec3 projected) {
                return new PhysicsPosCore.Vec3d(projected.x, projected.y, projected.z);
            }
        } catch (Throwable t) {
            MixinGuard.logOnce("PhysicsModBridge.projectSable", t);
        }
        return null;
    }

    private static boolean isInVs2Shipyard(Level level, BlockPos pos) {
        try {
            if (vs2IsBlockInShipyard != null) {
                return (boolean) vs2IsBlockInShipyard.invoke(level, pos);
            }
        } catch (Throwable t) {
            MixinGuard.logOnce("PhysicsModBridge.isInVs2Shipyard", t);
        }
        return false;
    }

    private static Object findVs2Ship(Level level, double x, double y, double z) throws Throwable {
        if (vs2GetShipManagingPos != null) {
            Object ship = vs2GetShipManagingPos.invoke(level, BlockPos.containing(x, y, z));
            if (ship != null) {
                return ship;
            }
        }
        if (vs2GetShipObjectWorld != null && level != null) {
            Object shipWorld = vs2GetShipObjectWorld.invoke(level);
            if (shipWorld != null) {
                if (vs2GetAllShips == null) {
                    synchronized (PhysicsModBridge.class) {
                        if (vs2GetAllShips == null) {
                            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                            vs2GetAllShips = lookup.unreflect(shipWorld.getClass().getMethod("getAllShips"));
                        }
                    }
                }
                Object allShips = vs2GetAllShips.invoke(shipWorld);
                if (allShips != null) {
                    if (vs2AllShipsGetByChunkPos == null) {
                        synchronized (PhysicsModBridge.class) {
                            if (vs2AllShipsGetByChunkPos == null) {
                                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                                vs2AllShipsGetByChunkPos = lookup.unreflect(
                                    allShips.getClass().getMethod("getByChunkPos", int.class, int.class));
                            }
                        }
                    }
                    int cx = ((int) Math.floor(x)) >> 4;
                    int cz = ((int) Math.floor(z)) >> 4;
                    return vs2AllShipsGetByChunkPos.invoke(allShips, cx, cz);
                }
            }
        }
        return null;
    }

    private static PhysicsPosCore.Vec3d projectVs2Ship(Object ship, double x, double y, double z) throws Throwable {
        if (ship == null) {
            return null;
        }
        if (vs2GetTransform == null) {
            synchronized (PhysicsModBridge.class) {
                if (vs2GetTransform == null) {
                    MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                    Method getTransformMethod = ship.getClass().getMethod("getTransform");
                    vs2GetTransform = lookup.unreflect(getTransformMethod);
                    Object transform = vs2GetTransform.invoke(ship);
                    Method getShipToWorldMethod = transform.getClass().getMethod("getShipToWorld");
                    vs2GetShipToWorld = lookup.unreflect(getShipToWorldMethod);
                    Object matrix = vs2GetShipToWorld.invoke(transform);
                    Class<?> vec3dClass = Class.forName("org.joml.Vector3d");
                    Method transformPosMethod = matrix.getClass().getMethod("transformPosition", vec3dClass);
                    vs2TransformPosition = lookup.unreflect(transformPosMethod);
                }
            }
        }
        Object transform = vs2GetTransform.invoke(ship);
        if (transform == null) return null;
        Object matrix = vs2GetShipToWorld.invoke(transform);
        if (matrix == null) return null;
        Object vec = jomlVec3dCtor.invoke(x, y, z);
        Object transformed = vs2TransformPosition.invoke(matrix, vec);
        double wx = (double) jomlVec3dX.invoke(transformed);
        double wy = (double) jomlVec3dY.invoke(transformed);
        double wz = (double) jomlVec3dZ.invoke(transformed);
        return new PhysicsPosCore.Vec3d(wx, wy, wz);
    }

    public static OfflineSubLevelLocation resolveVs2ShipBounds(Level level, double x, double y, double z) {
        ensureInitialized();
        if (!vs2Loaded || level == null) {
            return null;
        }
        try {
            Object ship = findVs2Ship(level, x, y, z);
            if (ship == null) {
                return null;
            }
            PhysicsPosCore.Vec3d worldPos = projectVs2Ship(ship, x, y, z);
            if (worldPos == null) {
                return null;
            }
            SableOfflineStorage.ChunkCoords center = new SableOfflineStorage.ChunkCoords(
                ((int) Math.floor(worldPos.x())) >> 4,
                ((int) Math.floor(worldPos.z())) >> 4
            );
            SableOfflineStorage.ChunkBounds bounds = SableOfflineStorage.ChunkBounds.aroundCenter(
                center, SableOfflineStorage.ChunkBounds.DEFAULT_FALLBACK_RADIUS);
            try {
                if (vs2GetWorldAABB == null) {
                    synchronized (PhysicsModBridge.class) {
                        if (vs2GetWorldAABB == null) {
                            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                            vs2GetWorldAABB = lookup.unreflect(ship.getClass().getMethod("getWorldAABB"));
                            Object aabb = vs2GetWorldAABB.invoke(ship);
                            if (aabb != null) {
                                vs2AabbMinX = lookup.unreflect(aabb.getClass().getMethod("minX"));
                                vs2AabbMinZ = lookup.unreflect(aabb.getClass().getMethod("minZ"));
                                vs2AabbMaxX = lookup.unreflect(aabb.getClass().getMethod("maxX"));
                                vs2AabbMaxZ = lookup.unreflect(aabb.getClass().getMethod("maxZ"));
                            }
                        }
                    }
                }
                Object aabb = vs2GetWorldAABB != null ? vs2GetWorldAABB.invoke(ship) : null;
                if (aabb != null && vs2AabbMinX != null && vs2AabbMinZ != null
                    && vs2AabbMaxX != null && vs2AabbMaxZ != null) {
                    double minX = (double) vs2AabbMinX.invoke(aabb);
                    double minZ = (double) vs2AabbMinZ.invoke(aabb);
                    double maxX = (double) vs2AabbMaxX.invoke(aabb);
                    double maxZ = (double) vs2AabbMaxZ.invoke(aabb);
                    bounds = SableOfflineStorage.ChunkBounds.fromWorldAabb(minX, minZ, maxX, maxZ, center);
                }
            } catch (Throwable ignored) {
                // Fall back to center ± 2 if getWorldAABB is unavailable
            }
            return new OfflineSubLevelLocation(
                worldPos,
                new ChunkPos(center.x(), center.z()),
                null,
                -1,
                -1,
                bounds.minChunkX(),
                bounds.minChunkZ(),
                bounds.maxChunkX(),
                bounds.maxChunkZ()
            );
        } catch (Throwable t) {
            MixinGuard.logOnce("PhysicsModBridge.resolveVs2ShipBounds", t);
            return null;
        }
    }

    private static PhysicsPosCore.Vec3d projectVs2(Level level, double x, double y, double z) {
        try {
            Object ship = findVs2Ship(level, x, y, z);
            return projectVs2Ship(ship, x, y, z);
        } catch (Throwable t) {
            MixinGuard.logOnce("PhysicsModBridge.projectVs2", t);
        }
        return null;
    }

    private static Class<?> findClass(String... names) {
        for (String name : names) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException ignored) {
                // Try next candidate
            }
        }
        return null;
    }

    private static MethodHandle findVirtualExact(
        MethodHandles.Lookup lookup,
        Class<?> owner,
        String name,
        Class<?> returnType,
        Class<?>... params
    ) {
        try {
            return lookup.findVirtual(owner, name, MethodType.methodType(returnType, params));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static MethodHandle findStaticExact(
        MethodHandles.Lookup lookup,
        Class<?> owner,
        String name,
        Class<?> returnType,
        Class<?>... params
    ) {
        try {
            return lookup.findStatic(owner, name, MethodType.methodType(returnType, params));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static MethodHandle findVirtual(MethodHandles.Lookup lookup, Class<?> owner, String name, Class<?>... params) {
        try {
            Method m = owner.getMethod(name, params);
            return lookup.unreflect(m);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static MethodHandle findStatic(MethodHandles.Lookup lookup, Class<?> owner, String name, Class<?>... params) {
        try {
            Method m = owner.getMethod(name, params);
            if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                return null;
            }
            return lookup.unreflect(m);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
