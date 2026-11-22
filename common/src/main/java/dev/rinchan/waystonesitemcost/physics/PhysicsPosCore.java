package dev.rinchan.waystonesitemcost.physics;

import java.util.UUID;

/**
 * Pure-Java coordinate resolution and distance kernel for Waystones located inside
 * physics sub-worlds (Sable / Create: Aeronautics / Simurail and Valkyrien Skies 2).
 *
 * Resolution priority for any position:
 * 1. Live sub-world projection (if physics mod reports the position inside a loaded ship/sub-level).
 *    When allowWriteCache is true (server-side) and waystoneUid != null, refreshes the persistent cache.
 * 2. Cached last-known world position (server SavedData for unloaded ships, or client S2C sync cache
 *    for out-of-view ships).
 * 3. Safety valve: if the raw position is still known to lie in a physics Plot/Shipyard area
 *    (e.g. ~20,480,000) with neither live projection nor cache hit, returns null so callers degrade
 *    gracefully to 0 distance (base cost) instead of computing a 28,960 km astronomical fee.
 * 4. Normal world coordinate: returned unchanged.
 */
public final class PhysicsPosCore {
    private PhysicsPosCore() {
    }

    public record Vec3d(double x, double y, double z) {
        public double distanceSqrTo(Vec3d other) {
            double dx = this.x - other.x;
            double dy = this.y - other.y;
            double dz = this.z - other.z;
            return dx * dx + dy * dy + dz * dz;
        }

        public double distanceTo(Vec3d other) {
            return Math.sqrt(distanceSqrTo(other));
        }
    }

    public record CachedWorldPos(String dimension, double x, double y, double z) {
        public Vec3d toVec3d() {
            return new Vec3d(x, y, z);
        }
    }

    /**
     * Pure-Java rigid-body pose transformation matching Sable's {@code Pose3dc.transformPosition}:
     * {@code world = rotate(q, point - rotationPoint) + position} (unit scale).
     */
    public static Vec3d transformPose(
        Vec3d point,
        Vec3d position,
        double qx,
        double qy,
        double qz,
        double qw,
        Vec3d rotationPoint
    ) {
        if (point == null || position == null || rotationPoint == null) {
            return null;
        }
        double vx = point.x() - rotationPoint.x();
        double vy = point.y() - rotationPoint.y();
        double vz = point.z() - rotationPoint.z();

        // Standard Hamilton quaternion rotation: v' = v + qw * t + cross(q.xyz, t), where t = 2 * cross(q.xyz, v)
        double tx = 2.0 * (qy * vz - qz * vy);
        double ty = 2.0 * (qz * vx - qx * vz);
        double tz = 2.0 * (qx * vy - qy * vx);

        double rx = vx + qw * tx + (qy * tz - qz * ty);
        double ry = vy + qw * ty + (qz * tx - qx * tz);
        double rz = vz + qw * tz + (qx * ty - qy * tx);

        return new Vec3d(position.x() + rx, position.y() + ry, position.z() + rz);
    }

    public interface LiveProjector {
        boolean isInPhysicsSpace(double x, double y, double z);

        /**
         * Projects a raw coordinate out of its physics sub-world into real world coordinates,
         * or returns null if the coordinate is not inside any currently loaded/tracked sub-world.
         */
        Vec3d projectToWorld(double x, double y, double z);
    }

    public interface PosCache {
        Vec3d get(UUID waystoneUid, String dimension);

        void put(UUID waystoneUid, String dimension, Vec3d worldPos);
    }

    public static Vec3d resolveWorldPos(
        UUID waystoneUid,
        String dimension,
        Vec3d rawPos,
        LiveProjector projector,
        PosCache cache,
        boolean allowWriteCache
    ) {
        if (rawPos == null) {
            return null;
        }
        if (projector != null) {
            Vec3d live = projector.projectToWorld(rawPos.x(), rawPos.y(), rawPos.z());
            if (live != null) {
                if (allowWriteCache && waystoneUid != null && cache != null && dimension != null) {
                    cache.put(waystoneUid, dimension, live);
                }
                return live;
            }
        }
        if (waystoneUid != null && cache != null && dimension != null) {
            Vec3d cached = cache.get(waystoneUid, dimension);
            if (cached != null) {
                return cached;
            }
        }
        if (projector != null && projector.isInPhysicsSpace(rawPos.x(), rawPos.y(), rawPos.z())) {
            // Unloaded/untracked physics sub-world coordinate with no cached world position yet.
            return null;
        }
        return rawPos;
    }

    public static double resolveDistance(
        Vec3d originRawPos,
        String originDimension,
        Vec3d targetRawPos,
        UUID targetUid,
        String targetDimension,
        LiveProjector projector,
        PosCache cache,
        boolean allowWriteCache
    ) {
        if (originRawPos == null || targetRawPos == null) {
            return 0.0;
        }
        if (originDimension != null && targetDimension != null && !originDimension.equals(targetDimension)) {
            return originRawPos.distanceTo(targetRawPos);
        }
        Vec3d resolvedOrigin = resolveWorldPos(null, originDimension, originRawPos, projector, null, false);
        Vec3d resolvedTarget = resolveWorldPos(targetUid, targetDimension, targetRawPos, projector, cache, allowWriteCache);
        if (resolvedOrigin == null || resolvedTarget == null) {
            return 0.0;
        }
        return resolvedOrigin.distanceTo(resolvedTarget);
    }

    public static double resolveDistanceSqr(
        Vec3d originRawPos,
        String originDimension,
        Vec3d targetRawPos,
        UUID targetUid,
        String targetDimension,
        LiveProjector projector,
        PosCache cache,
        boolean allowWriteCache
    ) {
        double dist = resolveDistance(
            originRawPos, originDimension, targetRawPos, targetUid, targetDimension,
            projector, cache, allowWriteCache
        );
        return dist * dist;
    }
}
