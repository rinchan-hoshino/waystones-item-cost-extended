package dev.rinchan.waystonesitemcost.network;

import dev.rinchan.waystonesitemcost.MixinGuard;
import dev.rinchan.waystonesitemcost.config.PaymentConfig;
import dev.rinchan.waystonesitemcost.physics.PhysicsModBridge;
import dev.rinchan.waystonesitemcost.physics.PhysicsPosCore;
import dev.rinchan.waystonesitemcost.physics.PhysicsPosResolver;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.blay09.mods.balm.api.Balm;
import net.blay09.mods.balm.api.event.PlayerLoginEvent;
import net.blay09.mods.balm.api.network.BalmNetworking;
import net.blay09.mods.waystones.api.event.BuildWaystoneSelectionMenuEvent;
import net.blay09.mods.waystones.core.WaystoneManagerImpl;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/** Server -> Client physics sub-world Waystone world-position cache sync (Minecraft 1.21.1). */
public record PhysicsPosSync(Map<UUID, PhysicsPosCore.CachedWorldPos> entries) implements CustomPacketPayload {
    public static final Type<PhysicsPosSync> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MixinGuard.MOD_ID, "physics_pos"));

    public static void register(BalmNetworking networking) {
        networking.registerClientboundPacket(TYPE, PhysicsPosSync.class, PhysicsPosSync::encode, PhysicsPosSync::decode, PhysicsPosSync::handle);
        Balm.getEvents().onEvent(PlayerLoginEvent.class, event -> syncAllTo(event.getPlayer()));
        Balm.getEvents().onEvent(BuildWaystoneSelectionMenuEvent.class, event -> {
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
    }

    private static void syncAllTo(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        if (!(serverPlayer.level() instanceof ServerLevel serverLevel)) return;
        MinecraftServer server = serverLevel.getServer();
        if (PaymentConfig.fixPhysicsShipDistance() && PhysicsModBridge.isAnyPhysicsModLoaded()) {
            WaystoneManagerImpl.get(server).getWaystones().forEach(ws ->
                PhysicsPosResolver.refreshWaystoneOnServer(server, ws.getWaystoneUid(), ws.getDimension(), ws.getPos()));
        }
        sendSnapshot(serverPlayer, server);
    }

    private static void sendSnapshot(ServerPlayer player, MinecraftServer server) {
        Map<UUID, PhysicsPosCore.CachedWorldPos> snapshot = PhysicsPosResolver.snapshotServerEntries(server);
        Balm.getNetworking().sendTo(player, new PhysicsPosSync(snapshot));
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
