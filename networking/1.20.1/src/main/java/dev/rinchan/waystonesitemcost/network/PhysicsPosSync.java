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
import net.blay09.mods.waystones.core.WaystoneManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Server -> Client physics sub-world Waystone world-position cache sync (Minecraft 1.20.1). */
public record PhysicsPosSync(Map<UUID, PhysicsPosCore.CachedWorldPos> entries) {
    public static final ResourceLocation ID = new ResourceLocation(MixinGuard.MOD_ID, "physics_pos");
    private static final int PERIODIC_REFRESH_TICKS = 100;

    public static void register(BalmNetworking networking) {
        try {
            networking.registerClientboundPacket(ID, PhysicsPosSync.class, PhysicsPosSync::encode, PhysicsPosSync::decode, PhysicsPosSync::handle);
        } catch (Throwable t) {
            MixinGuard.logOnce("PhysicsPosSync.registerPacket", t);
        }
        try {
            Balm.getEvents().onEvent(PlayerLoginEvent.class, event -> syncTo(event.getPlayer()));
        } catch (Throwable t) {
            MixinGuard.logOnce("PhysicsPosSync.registerPlayerLogin", t);
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
            WaystoneManager.get(server).getWaystones().forEach(ws -> {
                ServerLevel level = server.getLevel(ws.getDimension());
                if (level == null) return;
                Vec3 center = Vec3.atCenterOf(ws.getPos());
                if (PhysicsModBridge.isInPhysicsSpace(level, center.x, center.y, center.z)) {
                    PhysicsPosResolver.refreshWaystoneOnServer(server, ws.getWaystoneUid(), ws.getDimension(), ws.getPos());
                }
            });
        } catch (Throwable t) {
            MixinGuard.logOnce("PhysicsPosSync.onServerTick", t);
        }
    }

    public static void syncTo(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        if (!(serverPlayer.level() instanceof ServerLevel serverLevel)) return;
        MinecraftServer server = serverLevel.getServer();
        if (PaymentConfig.fixPhysicsShipDistance() && PhysicsModBridge.isAnyPhysicsModLoaded()) {
            WaystoneManager.get(server).getWaystones().forEach(ws ->
                PhysicsPosResolver.refreshWaystoneOnServer(server, ws.getWaystoneUid(), ws.getDimension(), ws.getPos()));
        }
        Map<UUID, PhysicsPosCore.CachedWorldPos> snapshot = PhysicsPosResolver.snapshotServerEntries(server);
        Balm.getNetworking().sendTo(serverPlayer, new PhysicsPosSync(snapshot));
    }

    public static void encode(PhysicsPosSync packet, FriendlyByteBuf buffer) {
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

    public static PhysicsPosSync decode(FriendlyByteBuf buffer) {
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
}
