package dev.rinchan.waystonesitemcost.physics;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.rinchan.waystonesitemcost.MixinGuard;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Server-side SavedData cache of last-known real-world coordinates for Waystones
 * located on physics sub-worlds (Minecraft 26.x).
 */
public final class PhysicsPosStorage extends SavedData implements PhysicsPosCore.PosCache {
    private record StoredEntry(String uid, String dimension, double x, double y, double z) {
        private static final Codec<StoredEntry> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.STRING.fieldOf("uid").forGetter(StoredEntry::uid),
            Codec.STRING.fieldOf("dim").forGetter(StoredEntry::dimension),
            Codec.DOUBLE.fieldOf("x").forGetter(StoredEntry::x),
            Codec.DOUBLE.fieldOf("y").forGetter(StoredEntry::y),
            Codec.DOUBLE.fieldOf("z").forGetter(StoredEntry::z)
        ).apply(inst, StoredEntry::new));
    }

    private static final Codec<PhysicsPosStorage> CODEC = RecordCodecBuilder.create(inst -> inst.group(
        StoredEntry.CODEC.listOf().optionalFieldOf("entries", List.of()).forGetter(PhysicsPosStorage::toStoredList)
    ).apply(inst, PhysicsPosStorage::fromStoredList));

    public static final SavedDataType<PhysicsPosStorage> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath(MixinGuard.MOD_ID, "physics_pos"),
        PhysicsPosStorage::new,
        CODEC,
        null
    );

    private final ConcurrentHashMap<UUID, PhysicsPosCore.CachedWorldPos> entries = new ConcurrentHashMap<>();

    public PhysicsPosStorage() {
    }

    private static PhysicsPosStorage fromStoredList(List<StoredEntry> list) {
        var storage = new PhysicsPosStorage();
        for (var e : list) {
            try {
                UUID uid = UUID.fromString(e.uid());
                if (!e.dimension().isEmpty()) {
                    storage.entries.put(uid, new PhysicsPosCore.CachedWorldPos(e.dimension(), e.x(), e.y(), e.z()));
                }
            } catch (IllegalArgumentException ignored) {
                // Skip malformed entry
            }
        }
        return storage;
    }

    private List<StoredEntry> toStoredList() {
        var out = new ArrayList<StoredEntry>(entries.size());
        for (var e : entries.entrySet()) {
            var v = e.getValue();
            out.add(new StoredEntry(e.getKey().toString(), v.dimension(), v.x(), v.y(), v.z()));
        }
        return out;
    }

    public static String dimensionId(ResourceKey<Level> dimension) {
        return dimension.identifier().toString();
    }

    public static PhysicsPosStorage get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
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
            return NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        }
        return NbtIo.read(new DataInputStream(in), NbtAccounter.unlimitedHeap());
    }

    public static SableOfflineStorage.OfflineHeader readSableOfflineHeader(InputStream in, boolean gzip) throws IOException {
        SableHeaderStreamVisitor visitor = new SableHeaderStreamVisitor();
        if (gzip) {
            NbtIo.parseCompressed(in, visitor, NbtAccounter.unlimitedHeap());
        } else {
            NbtIo.parse(new DataInputStream(in), visitor, NbtAccounter.unlimitedHeap());
        }
        return visitor.buildHeader();
    }

    public static int[] readSableRegionPointers(InputStream in, boolean gzip) throws IOException {
        SableHeaderStreamVisitor.RegionPointersVisitor visitor = new SableHeaderStreamVisitor.RegionPointersVisitor();
        if (gzip) {
            NbtIo.parseCompressed(in, visitor, NbtAccounter.unlimitedHeap());
        } else {
            NbtIo.parse(new DataInputStream(in), visitor, NbtAccounter.unlimitedHeap());
        }
        return visitor.pointers();
    }

    public static CompoundTag nbtCompound(CompoundTag tag, String key) {
        return tag != null ? tag.getCompound(key).orElse(null) : null;
    }

    public static Integer nbtInt(CompoundTag tag, String key) {
        return tag != null ? tag.getInt(key).orElse(null) : null;
    }

    public static double nbtDouble(CompoundTag tag, String key, double fallback) {
        return tag != null ? tag.getDoubleOr(key, fallback) : fallback;
    }

    public Map<UUID, PhysicsPosCore.CachedWorldPos> snapshot() {
        if (entries.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }
}
