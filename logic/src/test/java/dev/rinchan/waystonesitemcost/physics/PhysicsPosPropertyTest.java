package dev.rinchan.waystonesitemcost.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rinchan.waystonesitemcost.physics.PhysicsPosCore.LiveProjector;
import dev.rinchan.waystonesitemcost.physics.PhysicsPosCore.PosCache;
import dev.rinchan.waystonesitemcost.physics.PhysicsPosCore.Vec3d;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Property-based invariant suite (>= 1,500 randomized trials) verifying physics sub-world
 * coordinate projection, server SavedData unloading fallback, client S2C out-of-view cache,
 * uncached safety valve, ship-to-ship symmetry, and triangle inequality.
 */
final class PhysicsPosPropertyTest {
    private static final double PLOT_ORIGIN = 20_480_000.0;
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final double EPS = 1e-6;

    private static final class MapCache implements PosCache {
        private final Map<UUID, PhysicsPosCore.CachedWorldPos> store = new HashMap<>();

        @Override
        public Vec3d get(UUID waystoneUid, String dimension) {
            var e = store.get(waystoneUid);
            return (e != null && e.dimension().equals(dimension)) ? e.toVec3d() : null;
        }

        @Override
        public void put(UUID waystoneUid, String dimension, Vec3d worldPos) {
            store.put(waystoneUid, new PhysicsPosCore.CachedWorldPos(dimension, worldPos.x(), worldPos.y(), worldPos.z()));
        }

        MapCache snapshotCopy() {
            var copy = new MapCache();
            copy.store.putAll(this.store);
            return copy;
        }
    }

    /**
     * Simulates a rigid-body ship transform (3D Euler rotation + translation) from Sable/VS2
     * Plot/Shipyard local coordinates into real-world coordinates.
     */
    private record RigidBodyShip(
        double plotOriginX,
        double plotOriginY,
        double plotOriginZ,
        double halfExtent,
        double worldTx,
        double worldTy,
        double worldTz,
        double yaw,
        double pitch,
        double roll,
        boolean loaded
    ) {
        boolean containsPlot(double x, double y, double z) {
            return Math.abs(x - plotOriginX) <= halfExtent
                && Math.abs(y - plotOriginY) <= halfExtent
                && Math.abs(z - plotOriginZ) <= halfExtent;
        }

        Vec3d transform(double x, double y, double z) {
            double lx = x - plotOriginX;
            double ly = y - plotOriginY;
            double lz = z - plotOriginZ;

            // Rotate around Y (yaw), X (pitch), Z (roll)
            double cy = Math.cos(yaw), sy = Math.sin(yaw);
            double x1 = lx * cy - lz * sy;
            double z1 = lx * sy + lz * cy;
            double y1 = ly;

            double cp = Math.cos(pitch), sp = Math.sin(pitch);
            double y2 = y1 * cp - z1 * sp;
            double z2 = y1 * sp + z1 * cp;
            double x2 = x1;

            double cr = Math.cos(roll), sr = Math.sin(roll);
            double x3 = x2 * cr - y2 * sr;
            double y3 = x2 * sr + y2 * cr;
            double z3 = z2;

            return new Vec3d(worldTx + x3, worldTy + y3, worldTz + z3);
        }
    }

    private static LiveProjector projectorFor(RigidBodyShip... ships) {
        return new LiveProjector() {
            @Override
            public boolean isInPhysicsSpace(double x, double y, double z) {
                return x >= PLOT_ORIGIN - 10_000.0 && z >= PLOT_ORIGIN - 10_000.0;
            }

            @Override
            public Vec3d projectToWorld(double x, double y, double z) {
                for (var ship : ships) {
                    if (ship.loaded() && ship.containsPlot(x, y, z)) {
                        return ship.transform(x, y, z);
                    }
                }
                return null;
            }
        };
    }

    @Test
    void randomizedShipProjectionCacheFallbackSafetyValveSymmetryAndTriangleInequality() {
        Random rng = new Random(0x210_5AB1EL);
        int rounds = 1_500;

        for (int i = 0; i < rounds; i++) {
            // Ship A in Plot 0, Ship B in Plot 1
            RigidBodyShip shipA = new RigidBodyShip(
                PLOT_ORIGIN + 500.0, 64.0, PLOT_ORIGIN + 500.0, 256.0,
                (rng.nextDouble() - 0.5) * 4000.0,
                40.0 + rng.nextDouble() * 180.0,
                (rng.nextDouble() - 0.5) * 4000.0,
                rng.nextDouble() * Math.PI * 2.0,
                (rng.nextDouble() - 0.5) * 0.5,
                (rng.nextDouble() - 0.5) * 0.5,
                true
            );
            RigidBodyShip shipB = new RigidBodyShip(
                PLOT_ORIGIN + 5000.0, 64.0, PLOT_ORIGIN + 5000.0, 256.0,
                (rng.nextDouble() - 0.5) * 4000.0,
                40.0 + rng.nextDouble() * 180.0,
                (rng.nextDouble() - 0.5) * 4000.0,
                rng.nextDouble() * Math.PI * 2.0,
                (rng.nextDouble() - 0.5) * 0.5,
                (rng.nextDouble() - 0.5) * 0.5,
                true
            );

            Vec3d plotPosA = new Vec3d(
                shipA.plotOriginX() + (rng.nextDouble() - 0.5) * 100.0,
                shipA.plotOriginY() + (rng.nextDouble() - 0.5) * 40.0,
                shipA.plotOriginZ() + (rng.nextDouble() - 0.5) * 100.0
            );
            Vec3d plotPosB = new Vec3d(
                shipB.plotOriginX() + (rng.nextDouble() - 0.5) * 100.0,
                shipB.plotOriginY() + (rng.nextDouble() - 0.5) * 40.0,
                shipB.plotOriginZ() + (rng.nextDouble() - 0.5) * 100.0
            );
            Vec3d groundPos = new Vec3d(
                (rng.nextDouble() - 0.5) * 4000.0,
                64.0 + (rng.nextDouble() - 0.5) * 30.0,
                (rng.nextDouble() - 0.5) * 4000.0
            );

            UUID uidA = new UUID(rng.nextLong(), rng.nextLong());
            UUID uidB = new UUID(rng.nextLong(), rng.nextLong());
            UUID uidUncached = new UUID(rng.nextLong(), rng.nextLong());

            Vec3d expectedWorldA = shipA.transform(plotPosA.x(), plotPosA.y(), plotPosA.z());
            Vec3d expectedWorldB = shipB.transform(plotPosB.x(), plotPosB.y(), plotPosB.z());

            MapCache serverSavedData = new MapCache();
            LiveProjector liveBoth = projectorFor(shipA, shipB);

            // 1. Server live projection: ground -> ship A (also populates SavedData)
            double serverLiveDist = PhysicsPosCore.resolveDistance(
                groundPos, OVERWORLD, plotPosA, uidA, OVERWORLD, liveBoth, serverSavedData, true
            );
            double expectedGroundToA = groundPos.distanceTo(expectedWorldA);
            assertEquals(expectedGroundToA, serverLiveDist, EPS, "Live projection must match true Euclidean distance");
            assertTrue(serverLiveDist < 10_000.0, "Must never produce 28,960km plot distance");

            // 2. Client out-of-view S2C cache hit: client projector has NO loaded ships, only S2C sync snapshot
            MapCache clientS2cCache = serverSavedData.snapshotCopy();
            LiveProjector clientOutOfView = projectorFor(); // no loaded ships tracked on client
            double clientDist = PhysicsPosCore.resolveDistance(
                groundPos, OVERWORLD, plotPosA, uidA, OVERWORLD, clientOutOfView, clientS2cCache, false
            );
            assertEquals(serverLiveDist, clientDist, EPS, "Client S2C cache must match server live distance 1:1");

            // 3. Server unloaded ship SavedData fallback: ship A unloads on server
            RigidBodyShip unloadedShipA = new RigidBodyShip(
                shipA.plotOriginX(), shipA.plotOriginY(), shipA.plotOriginZ(), shipA.halfExtent(),
                shipA.worldTx(), shipA.worldTy(), shipA.worldTz(), shipA.yaw(), shipA.pitch(), shipA.roll(),
                false
            );
            LiveProjector serverUnloadedA = projectorFor(unloadedShipA, shipB);
            double serverUnloadedDist = PhysicsPosCore.resolveDistance(
                groundPos, OVERWORLD, plotPosA, uidA, OVERWORLD, serverUnloadedA, serverSavedData, true
            );
            assertEquals(expectedGroundToA, serverUnloadedDist, EPS, "Server unloaded ship must fall back to SavedData");

            // 4. Uncached safety valve: ship in plot space with neither live tracking nor cache -> 0 distance (never 28.96M)
            double uncachedDist = PhysicsPosCore.resolveDistance(
                groundPos, OVERWORLD, plotPosA, uidUncached, OVERWORLD, clientOutOfView, new MapCache(), false
            );
            assertEquals(0.0, uncachedDist, EPS, "Uncached physics-space Waystone must trigger 0-distance safety valve");

            // 5. Ship-to-ship symmetry & triangle inequality: d(A, B) == d(B, A) and d(A, B) <= d(A, C) + d(C, B)
            double dAB = PhysicsPosCore.resolveDistance(
                plotPosA, OVERWORLD, plotPosB, uidB, OVERWORLD, liveBoth, serverSavedData, true
            );
            double dBA = PhysicsPosCore.resolveDistance(
                plotPosB, OVERWORLD, plotPosA, uidA, OVERWORLD, liveBoth, serverSavedData, true
            );
            double dAC = PhysicsPosCore.resolveDistance(
                plotPosA, OVERWORLD, groundPos, null, OVERWORLD, liveBoth, serverSavedData, false
            );
            double dCB = PhysicsPosCore.resolveDistance(
                groundPos, OVERWORLD, plotPosB, uidB, OVERWORLD, liveBoth, serverSavedData, false
            );
            assertEquals(expectedWorldA.distanceTo(expectedWorldB), dAB, EPS, "Ship-to-ship distance must match world distance");
            assertEquals(dAB, dBA, EPS, "Ship-to-ship distance must be symmetric");
            assertTrue(dAB <= dAC + dCB + EPS, "Triangle inequality must hold across ships and ground");

            // 6. Dimension isolation: Nether lookup must not return Overworld cached coordinates
            Vec3d wrongDim = PhysicsPosCore.resolveWorldPos(uidA, NETHER, plotPosA, clientOutOfView, clientS2cCache, false);
            assertNull(wrongDim, "Cached Overworld position must not leak into Nether lookup");
        }
    }
}
