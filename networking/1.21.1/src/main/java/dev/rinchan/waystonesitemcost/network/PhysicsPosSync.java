package dev.rinchan.waystonesitemcost.network;

import com.mojang.datafixers.util.Either;
import dev.rinchan.waystonesitemcost.MixinGuard;
import dev.rinchan.waystonesitemcost.config.PaymentConfig;
import dev.rinchan.waystonesitemcost.physics.PhysicsModBridge;
import dev.rinchan.waystonesitemcost.physics.PhysicsPosCore;
import dev.rinchan.waystonesitemcost.physics.PhysicsPosResolver;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.blay09.mods.balm.api.Balm;
import net.blay09.mods.balm.api.event.PlayerLoginEvent;
import net.blay09.mods.balm.api.event.ServerTickHandler;
import net.blay09.mods.balm.api.event.TickPhase;
import net.blay09.mods.balm.api.event.TickType;
import net.blay09.mods.balm.api.network.BalmNetworking;
import net.blay09.mods.waystones.api.Waystone;
import net.blay09.mods.waystones.api.error.WaystoneTeleportError;
import net.blay09.mods.waystones.api.event.BuildWaystoneSelectionMenuEvent;
import net.blay09.mods.waystones.api.event.WaystoneTeleportEvent;
import net.blay09.mods.waystones.core.WaystoneManagerImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/** Server -> Client physics sub-world Waystone world-position cache sync & teleport wakeup (Minecraft 1.21.1). */
public record PhysicsPosSync(Map<UUID, PhysicsPosCore.CachedWorldPos> entries) implements CustomPacketPayload {
    public static final Type<PhysicsPosSync> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MixinGuard.MOD_ID, "physics_pos"));
    private static final int PERIODIC_REFRESH_TICKS = 100;
    private static final int WAKE_CHUNK_RADIUS = 1;
    private static final int WAKE_TICKET_RADIUS = 3;
    private static final int MAX_WAKE_TICKS = 10;

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
        Balm.getEvents().onEvent(WaystoneTeleportEvent.Prepare.class, PhysicsPosSync::onPrepareTeleport);
        Balm.getEvents().onTickEvent(TickType.Server, TickPhase.End, (ServerTickHandler) PhysicsPosSync::onServerTick);
    }

    private static void onServerTick(MinecraftServer server) {
        if (server == null || server.getTickCount() % PERIODIC_REFRESH_TICKS != 0) {
            return;
        }
        if (!PaymentConfig.fixPhysicsShipDistance() || !PhysicsModBridge.isAnyPhysicsModLoaded()) {
            return;
        }
        try {
            WaystoneManagerImpl.get(server).getWaystones().forEach(ws -> {
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

    private static void onPrepareTeleport(WaystoneTeleportEvent.Prepare event) {
        try {
            if (!PaymentConfig.fixPhysicsShipDistance() || !PhysicsModBridge.isAnyPhysicsModLoaded()) {
                return;
            }
            var context = event.getContext();
            if (context == null) return;
            Waystone target = context.getTargetWaystone();
            Entity entity = context.getEntity();
            if (target == null || entity == null) return;
            MinecraftServer server = entity.getServer();
            if (server == null) return;
            ServerLevel targetLevel = server.getLevel(target.getDimension());
            if (targetLevel == null) return;

            Vec3 targetCenter = Vec3.atCenterOf(target.getPos());
            if (!PhysicsModBridge.isInPhysicsSpace(targetLevel, targetCenter.x, targetCenter.y, targetCenter.z)) {
                return;
            }
            // Sub-level is already loaded and resolvable; refresh its cached world position and proceed normally.
            if (PhysicsModBridge.projectToWorld(targetLevel, targetCenter.x, targetCenter.y, targetCenter.z) != null) {
                PhysicsPosResolver.refreshWaystoneOnServer(server, target.getWaystoneUid(), target.getDimension(), target.getPos());
                return;
            }

            // Target Waystone is inside an unloaded physics sub-level (e.g. Sable airship parked in an unloaded chunk).
            // Load its cached world-space chunks with a block-ticking ticket so Sable's SubLevelHoldingChunkMap
            // deserializes the SubLevel before Waystones runs isValidInLevel() and resolveDestination().
            PhysicsPosCore.Vec3d cachedWorld = PhysicsPosResolver.getCachedWorldPos(server, target.getWaystoneUid(), target.getDimension());
            if (cachedWorld == null) {
                return;
            }
            ChunkPos centerChunk = new ChunkPos(BlockPos.containing(cachedWorld.x(), cachedWorld.y(), cachedWorld.z()));
            int centerX = centerChunk.getMinBlockX() >> 4;
            int centerZ = centerChunk.getMinBlockZ() >> 4;
            int entityId = entity.getId();
            targetLevel.getChunkSource().addRegionTicket(TicketType.POST_TELEPORT, centerChunk, WAKE_TICKET_RADIUS, entityId);
            for (int dx = -WAKE_CHUNK_RADIUS; dx <= WAKE_CHUNK_RADIUS; dx++) {
                for (int dz = -WAKE_CHUNK_RADIUS; dz <= WAKE_CHUNK_RADIUS; dz++) {
                    event.addChunkPosition(new ChunkPos(centerX + dx, centerZ + dz));
                }
            }
            event.addPreparationTask(prevResult -> {
                if (prevResult.right().isPresent()) {
                    return CompletableFuture.completedFuture(prevResult);
                }
                CompletableFuture<Either<Void, WaystoneTeleportError>> future = new CompletableFuture<>();
                pollWakeSubLevel(server, targetLevel, target, centerChunk, entityId, prevResult, future, 0);
                return future;
            });
        } catch (Throwable t) {
            MixinGuard.logOnce("PhysicsPosSync.onPrepareTeleport", t);
        }
    }

    private static void pollWakeSubLevel(
        MinecraftServer server,
        ServerLevel targetLevel,
        Waystone target,
        ChunkPos centerChunk,
        int entityId,
        Either<Void, WaystoneTeleportError> prevResult,
        CompletableFuture<Either<Void, WaystoneTeleportError>> future,
        int attempt
    ) {
        Runnable step = () -> {
            try {
                if (attempt == 4) {
                    targetLevel.getChunkSource().addRegionTicket(TicketType.POST_TELEPORT, centerChunk, WAKE_TICKET_RADIUS, entityId);
                }
                PhysicsModBridge.wakeSableSubLevelAtWorldChunk(targetLevel, centerChunk, WAKE_CHUNK_RADIUS);
                Vec3 center = Vec3.atCenterOf(target.getPos());
                if (PhysicsModBridge.projectToWorld(targetLevel, center.x, center.y, center.z) != null) {
                    PhysicsPosResolver.refreshWaystoneOnServer(server, target.getWaystoneUid(), target.getDimension(), target.getPos());
                    future.complete(prevResult);
                    return;
                }
                if (attempt < MAX_WAKE_TICKS) {
                    server.tell(new TickTask(server.getTickCount() + 1, () ->
                        pollWakeSubLevel(server, targetLevel, target, centerChunk, entityId, prevResult, future, attempt + 1)));
                    return;
                }
            } catch (Throwable t) {
                MixinGuard.logOnce("PhysicsPosSync.pollWakeSubLevel", t);
            }
            future.complete(prevResult);
        };
        if (server.isSameThread()) {
            step.run();
        } else {
            server.execute(step);
        }
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
