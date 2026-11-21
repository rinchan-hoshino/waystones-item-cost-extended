package dev.rinchan.waystonesitemcost.physics;

import dev.rinchan.waystonesitemcost.MixinGuard;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.core.Vec3i;
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

    // Valkyrien Skies 2 handles
    private static MethodHandle vs2IsBlockInShipyard;
    private static MethodHandle vs2GetShipManagingPos;
    private static MethodHandle vs2GetTransform;
    private static MethodHandle vs2GetShipToWorld;
    private static MethodHandle vs2TransformPosition;
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
            } catch (Throwable ignored) {
                // Optional Sable server holding-chunk wakeup bridge
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

    private static PhysicsPosCore.Vec3d projectVs2(Level level, double x, double y, double z) {
        try {
            if (vs2GetShipManagingPos == null) {
                return null;
            }
            Object ship = vs2GetShipManagingPos.invoke(level, BlockPos.containing(x, y, z));
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
