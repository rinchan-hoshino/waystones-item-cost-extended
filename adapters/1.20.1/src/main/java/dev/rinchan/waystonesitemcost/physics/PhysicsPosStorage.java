package dev.rinchan.waystonesitemcost.physics;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Server-side SavedData cache of last-known real-world coordinates for Waystones
 * located on physics sub-worlds (Minecraft 1.20.1).
 */
public final class PhysicsPosStorage extends SavedData implements PhysicsPosCore.PosCache {
    private static final String DATA_NAME = "waystones_item_cost_extended_physics_pos";
    private static final String TAG_ENTRIES = "Entries";

    private final ConcurrentHashMap<UUID, PhysicsPosCore.CachedWorldPos> entries = new ConcurrentHashMap<>();

    public PhysicsPosStorage() {
    }

    public static String dimensionId(ResourceKey<Level> dimension) {
        return dimension.location().toString();
    }

    public static PhysicsPosStorage get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            PhysicsPosStorage::load,
            PhysicsPosStorage::new,
            DATA_NAME
        );
    }

    public static PhysicsPosStorage load(CompoundTag tag) {
        var storage = new PhysicsPosStorage();
        ListTag list = tag.getList(TAG_ENTRIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entryTag = list.getCompound(i);
            if (!entryTag.hasUUID("Uid")) continue;
            UUID uid = entryTag.getUUID("Uid");
            String dim = entryTag.getString("Dim");
            double x = entryTag.getDouble("X");
            double y = entryTag.getDouble("Y");
            double z = entryTag.getDouble("Z");
            if (!dim.isEmpty()) {
                storage.entries.put(uid, new PhysicsPosCore.CachedWorldPos(dim, x, y, z));
            }
        }
        return storage;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (var e : entries.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putUUID("Uid", e.getKey());
            entryTag.putString("Dim", e.getValue().dimension());
            entryTag.putDouble("X", e.getValue().x());
            entryTag.putDouble("Y", e.getValue().y());
            entryTag.putDouble("Z", e.getValue().z());
            list.add(entryTag);
        }
        tag.put(TAG_ENTRIES, list);
        return tag;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    @Override
    public PhysicsPosCore.Vec3d get(UUID waystoneUid, String dimension) {
        var cached = entries.get(waystoneUid);
        if (cached != null && cached.dimension().equals(dimension)) {
            return cached.toVec3d();
        }
        return null;
    }

    @Override
    public void put(UUID waystoneUid, String dimension, PhysicsPosCore.Vec3d worldPos) {
        var prev = entries.get(waystoneUid);
        if (prev != null
            && prev.dimension().equals(dimension)
            && prev.toVec3d().distanceSqrTo(worldPos) < 1e-4) {
            return;
        }
        entries.put(waystoneUid, new PhysicsPosCore.CachedWorldPos(dimension, worldPos.x(), worldPos.y(), worldPos.z()));
        setDirty();
    }

    public void remove(UUID waystoneUid) {
        if (waystoneUid != null && entries.remove(waystoneUid) != null) {
            setDirty();
        }
    }

    public void clear() {
        if (!entries.isEmpty()) {
            entries.clear();
            setDirty();
        }
    }

    public static CompoundTag readNbtStream(InputStream in, boolean gzipCompressed) throws IOException {
        if (gzipCompressed) {
            return NbtIo.readCompressed(in);
        }
        return NbtIo.read(new DataInputStream(in));
    }

    public static SableOfflineStorage.OfflineHeader readSableOfflineHeader(InputStream in, boolean gzip) throws IOException {
        SableHeaderStreamVisitor visitor = new SableHeaderStreamVisitor();
        if (gzip) {
            NbtIo.parseCompressed(in, visitor);
        } else {
            NbtIo.parse(new DataInputStream(in), visitor);
        }
        return visitor.buildHeader();
    }

    public static int[] readSableRegionPointers(InputStream in, boolean gzip) throws IOException {
        SableHeaderStreamVisitor.RegionPointersVisitor visitor = new SableHeaderStreamVisitor.RegionPointersVisitor();
        if (gzip) {
            NbtIo.parseCompressed(in, visitor);
        } else {
            NbtIo.parse(new DataInputStream(in), visitor);
        }
        return visitor.pointers();
    }

    public static CompoundTag nbtCompound(CompoundTag tag, String key) {
        return tag != null && tag.contains(key, Tag.TAG_COMPOUND) ? tag.getCompound(key) : null;
    }

    public static Integer nbtInt(CompoundTag tag, String key) {
        return tag != null && tag.contains(key, Tag.TAG_ANY_NUMERIC) ? tag.getInt(key) : null;
    }

    public static double nbtDouble(CompoundTag tag, String key, double fallback) {
        return tag != null && tag.contains(key, Tag.TAG_ANY_NUMERIC) ? tag.getDouble(key) : fallback;
    }

    public Map<UUID, PhysicsPosCore.CachedWorldPos> snapshot() {
        if (entries.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }
}
