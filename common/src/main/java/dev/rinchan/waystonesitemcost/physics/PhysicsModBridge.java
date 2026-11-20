package dev.rinchan.waystonesitemcost.physics;

import dev.rinchan.waystonesitemcost.MixinGuard;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
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
    private static MethodHandle sableGetContainingBlockPos;
    private static MethodHandle sableGetContainingVec3;
    private static MethodHandle sableProjectOutOfSubLevel;
    private static MethodHandle sableGetSubLevelContainer;
    private static MethodHandle sableContainerInBoundsBlockPos;
    private static MethodHandle sableStaticInBoundsBlockPos;

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

            sableGetContainingBlockPos = findVirtual(lookup, helperClass, "getContaining", Level.class, BlockPos.class);
            sableGetContainingVec3 = findVirtual(lookup, helperClass, "getContaining", Level.class, Vec3.class);
            sableProjectOutOfSubLevel = findVirtual(lookup, helperClass, "projectOutOfSubLevel", Level.class, Vec3.class);
            sableGetSubLevelContainer = findVirtual(lookup, helperClass, "getSubLevelContainer", Level.class);

            try {
                Class<?> containerClass = Class.forName("dev.ryanhcode.sable.sublevel.SubLevelContainer");
                sableContainerInBoundsBlockPos = findVirtual(lookup, containerClass, "inBounds", BlockPos.class);
                sableStaticInBoundsBlockPos = findStatic(lookup, containerClass, "inBounds", BlockPos.class);
            } catch (Throwable ignored) {
                // Optional bounds helper
            }

            if (sableProjectOutOfSubLevel != null && (sableGetContainingBlockPos != null || sableGetContainingVec3 != null)) {
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

    private static boolean isInSablePlot(Level level, BlockPos pos) {
        try {
            if (sableContainerInBoundsBlockPos != null && sableGetSubLevelContainer != null && sableHelper != null) {
                Object container = sableGetSubLevelContainer.invoke(sableHelper, level);
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

    private static PhysicsPosCore.Vec3d projectSable(Level level, double x, double y, double z) {
        try {
            if (sableHelper == null || sableProjectOutOfSubLevel == null) {
                return null;
            }
            Object containing = null;
            if (sableGetContainingBlockPos != null) {
                containing = sableGetContainingBlockPos.invoke(sableHelper, level, BlockPos.containing(x, y, z));
            } else if (sableGetContainingVec3 != null) {
                containing = sableGetContainingVec3.invoke(sableHelper, level, new Vec3(x, y, z));
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
