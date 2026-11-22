package dev.rinchan.waystonesitemcost.network;

import dev.rinchan.waystonesitemcost.MixinGuard;
import dev.rinchan.waystonesitemcost.config.PaymentConfig;
import dev.rinchan.waystonesitemcost.physics.PhysicsModBridge;
import dev.rinchan.waystonesitemcost.physics.PhysicsPosCore;
import dev.rinchan.waystonesitemcost.physics.PhysicsPosResolver;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.blay09.mods.balm.Balm;
import net.blay09.mods.balm.network.BalmNetworking;
import net.blay09.mods.balm.platform.event.callback.ServerPlayerCallback;
import net.blay09.mods.waystones.api.event.BuildWaystoneSelectionMenuEvent;
import net.blay09.mods.waystones.store.SavedDataWaystonesStore;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Server -> Client physics sub-world Waystone world-position cache sync (Minecraft 26.x). */
public record PhysicsPosSync(Map<UUID, PhysicsPosCore.CachedWorldPos> entries) implements CustomPacketPayload {
    public static final Type<PhysicsPosSync> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MixinGuard.MOD_ID, "physics_pos"));
    private static final int PERIODIC_REFRESH_TICKS = 100;

    public static void register(BalmNetworking networking) {
        try {
            networking.registerClientboundPacket(TYPE, PhysicsPosSync.class, StreamCodec.of(PhysicsPosSync::encode, PhysicsPosSync::decode), PhysicsPosSync::handle);
        } catch (Throwable t) {
            MixinGuard.logOnce("PhysicsPosSync.registerPacket", t);
        }
        try {
            ServerPlayerCallback.Join.EVENT.register(PhysicsPosSync::syncAllTo);
        } catch (Throwable t) {
            MixinGuard.logOnce("PhysicsPosSync.registerPlayerLogin", t);
        }
        try {
            BuildWaystoneSelectionMenuEvent.EVENT.register(event -> {
                if (!(event.getPlayer() instanceof ServerPlayer serverPlayer)) return;
                if (!(serverPlayer.level() instanceof ServerLevel serverLevel)) return;
                MinecraftServer server = serverLevel.getServer();
                if (PaymentConfig.fixPhysicsShipDistance() && PhysicsModBridge.isAnyPhysicsModLoaded()) {
                    for (var ws : event.getWaystones()) {
                        PhysicsPosResolver.refreshWaystoneOnServer(server, ws.getWaystoneUid(), ws.getDimension(), ws.getPos());
                    }
                }
                sendSnapshot(serverPlayer, server);
            });
        } catch (Throwable t) {
            MixinGuard.logOnce("PhysicsPosSync.registerMenuSync", t);
        }
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null || server.getTickCount() % PERIODIC_REFRESH_TICKS != 0) {
            return;
        }
        if (!PaymentConfig.fixPhysicsShipDistance() || !PhysicsModBridge.isAnyPhysicsModLoaded()) {
            return;
        }
        try {
            for (var ws : SavedDataWaystonesStore.get(server).getWaystones()) {
                ServerLevel level = server.getLevel(ws.getDimension());
                if (level == null) continue;
                Vec3 center = Vec3.atCenterOf(ws.getPos());
                if (PhysicsModBridge.isInPhysicsSpace(level, center.x, center.y, center.z)) {
                    PhysicsPosResolver.refreshWaystoneOnServer(server, ws.getWaystoneUid(), ws.getDimension(), ws.getPos());
                }
            }
        } catch (Throwable t) {
            MixinGuard.logOnce("PhysicsPosSync.onServerTick", t);
        }
    }

    private static void syncAllTo(ServerPlayer serverPlayer) {
        if (!(serverPlayer.level() instanceof ServerLevel serverLevel)) return;
        MinecraftServer server = serverLevel.getServer();
        if (PaymentConfig.fixPhysicsShipDistance() && PhysicsModBridge.isAnyPhysicsModLoaded()) {
            for (var ws : SavedDataWaystonesStore.get(server).getWaystones()) {
                PhysicsPosResolver.refreshWaystoneOnServer(server, ws.getWaystoneUid(), ws.getDimension(), ws.getPos());
            }
        }
        sendSnapshot(serverPlayer, server);
    }

    private static void sendSnapshot(ServerPlayer player, MinecraftServer server) {
        Map<UUID, PhysicsPosCore.CachedWorldPos> snapshot = PhysicsPosResolver.snapshotServerEntries(server);
        Balm.networking().sendTo(player, new PhysicsPosSync(snapshot));
    }

    public static void encode(RegistryFriendlyByteBuf buffer, PhysicsPosSync packet) {
        buffer.writeVarInt(packet.entries().size());
        for (var e : packet.entries().entrySet()) {
            var pos = e.getValue();
            buffer.writeUUID(e.getKey());
            buffer.writeUtf(pos.dimension());
            buffer.writeDouble(pos.x());
            buffer.writeDouble(pos.y());
            buffer.writeDouble(pos.z());
        }
    }

    public static PhysicsPosSync decode(RegistryFriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > 16384) throw new IllegalArgumentException("Invalid physics pos count");
        var map = new LinkedHashMap<UUID, PhysicsPosCore.CachedWorldPos>(size);
        for (int i = 0; i < size; i++) {
            UUID uid = buffer.readUUID();
            String dim = buffer.readUtf();
            double x = buffer.readDouble();
            double y = buffer.readDouble();
            double z = buffer.readDouble();
            map.put(uid, new PhysicsPosCore.CachedWorldPos(dim, x, y, z));
        }
        return new PhysicsPosSync(map);
    }

    public static void handle(Player player, PhysicsPosSync packet) {
        PhysicsPosResolver.receiveClientSync(packet.entries());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
