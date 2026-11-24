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
import net.blay09.mods.balm.Balm;
import net.blay09.mods.balm.network.BalmNetworking;
import net.blay09.mods.balm.platform.event.callback.ServerPlayerCallback;
import net.blay09.mods.waystones.api.Waystone;
import net.blay09.mods.waystones.api.error.WaystoneTeleportError;
import net.blay09.mods.waystones.api.event.BuildWaystoneSelectionMenuEvent;
import net.blay09.mods.waystones.api.event.WaystoneTeleportEvent;
import net.blay09.mods.waystones.store.SavedDataWaystonesStore;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/** Server -> Client physics sub-world Waystone world-position cache sync & teleport wakeup (Minecraft 26.x). */
public record PhysicsPosSync(Map<UUID, PhysicsPosCore.CachedWorldPos> entries) implements CustomPacketPayload {
    public static final Type<PhysicsPosSync> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MixinGuard.MOD_ID, "physics_pos"));
    private static final int PERIODIC_REFRESH_TICKS = 100;
    private static final int WAKE_TICKET_RADIUS = 2;
    private static final int MAX_WAKE_TICKS = 60;

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
        try {
            WaystoneTeleportEvent.Prepare.EVENT.register(PhysicsPosSync::onPrepareTeleport);
        } catch (Throwable t) {
            MixinGuard.logOnce("PhysicsPosSync.registerPrepareTeleport", t);
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
            if (!(entity.level() instanceof ServerLevel entityLevel)) return;
            MinecraftServer server = entityLevel.getServer();
            if (server == null) return;
            ServerLevel targetLevel = server.getLevel(target.getDimension());
            if (targetLevel == null) return;

            Vec3 targetCenter = Vec3.atCenterOf(target.getPos());
            if (!PhysicsModBridge.isInPhysicsSpace(targetLevel, targetCenter.x, targetCenter.y, targetCenter.z)) {
                return;
            }
            if (PhysicsModBridge.projectToWorld(targetLevel, targetCenter.x, targetCenter.y, targetCenter.z) != null
                && !targetLevel.getBlockState(target.getPos()).isAir()) {
                PhysicsPosResolver.refreshWaystoneOnServer(server, target.getWaystoneUid(), target.getDimension(), target.getPos());
                return;
            }

            PhysicsModBridge.OfflineSubLevelLocation offline = PhysicsPosResolver.resolveOfflineLocationOnServer(
                server, target.getWaystoneUid(), target.getDimension(), target.getPos(), true);
            if (offline == null) {
                PhysicsPosCore.Vec3d cachedWorld = PhysicsPosResolver.getCachedWorldPos(
                    server, target.getWaystoneUid(), target.getDimension());
                if (cachedWorld == null) {
                    return;
                }
                ChunkPos fallbackCenter = ChunkPos.containing(BlockPos.containing(cachedWorld.x(), cachedWorld.y(), cachedWorld.z()));
                offline = new PhysicsModBridge.OfflineSubLevelLocation(
                    cachedWorld,
                    fallbackCenter,
                    null,
                    -1,
                    -1,
                    fallbackCenter.x() - 2,
                    fallbackCenter.z() - 2,
                    fallbackCenter.x() + 2,
                    fallbackCenter.z() + 2
                );
            }

            addWakeChunks(event, targetLevel, offline);
            final PhysicsModBridge.OfflineSubLevelLocation finalLoc = offline;
            event.addPreparationTask(prevResult -> {
                if (prevResult.right().isPresent()) {
                    return CompletableFuture.completedFuture(prevResult);
                }
                CompletableFuture<Either<Void, WaystoneTeleportError>> future = new CompletableFuture<>();
                pollWakeSubLevel(server, targetLevel, target, finalLoc, prevResult, future, 0);
                return future;
            });
        } catch (Throwable t) {
            MixinGuard.logOnce("PhysicsPosSync.onPrepareTeleport", t);
        }
    }

    private static void addWakeChunks(
        WaystoneTeleportEvent.Prepare event,
        ServerLevel level,
        PhysicsModBridge.OfflineSubLevelLocation loc
    ) {
        if (loc.hasChunkBounds()) {
            for (int cx = loc.minChunkX(); cx <= loc.maxChunkX(); cx++) {
                for (int cz = loc.minChunkZ(); cz <= loc.maxChunkZ(); cz++) {
                    ChunkPos cp = new ChunkPos(cx, cz);
                    level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, cp, WAKE_TICKET_RADIUS);
                    event.addChunkPosition(cp);
                }
            }
        }
        if (loc.holdingChunk() != null) {
            level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, loc.holdingChunk(), WAKE_TICKET_RADIUS);
            event.addChunkPosition(loc.holdingChunk());
        }
    }

    private static void pollWakeSubLevel(
        MinecraftServer server,
        ServerLevel targetLevel,
        Waystone target,
        PhysicsModBridge.OfflineSubLevelLocation loc,
        Either<Void, WaystoneTeleportError> prevResult,
        CompletableFuture<Either<Void, WaystoneTeleportError>> future,
        int attempt
    ) {
        Runnable step = () -> {
            try {
                PhysicsModBridge.OfflineSubLevelLocation activeLoc = loc;
                if (attempt == 4) {
                    PhysicsModBridge.OfflineSubLevelLocation refreshed = PhysicsPosResolver.resolveOfflineLocationOnServer(
                        server, target.getWaystoneUid(), target.getDimension(), target.getPos(), true);
                    if (refreshed != null) {
                        activeLoc = refreshed;
                        if (activeLoc.hasChunkBounds()) {
                            for (int cx = activeLoc.minChunkX(); cx <= activeLoc.maxChunkX(); cx++) {
                                for (int cz = activeLoc.minChunkZ(); cz <= activeLoc.maxChunkZ(); cz++) {
                                    targetLevel.getChunkSource().addTicketWithRadius(
                                        TicketType.ENDER_PEARL, new ChunkPos(cx, cz), WAKE_TICKET_RADIUS);
                                }
                            }
                        }
                        if (activeLoc.holdingChunk() != null) {
                            targetLevel.getChunkSource().addTicketWithRadius(
                                TicketType.ENDER_PEARL, activeLoc.holdingChunk(), WAKE_TICKET_RADIUS);
                        }
                    }
                }
                Vec3 center = Vec3.atCenterOf(target.getPos());
                PhysicsModBridge.wakeSableSubLevel(targetLevel, activeLoc, center.x, center.y, center.z);
                if (PhysicsModBridge.projectToWorld(targetLevel, center.x, center.y, center.z) != null
                    && !targetLevel.getBlockState(target.getPos()).isAir()) {
                    PhysicsPosResolver.refreshWaystoneOnServer(server, target.getWaystoneUid(), target.getDimension(), target.getPos());
                    future.complete(prevResult);
                    return;
                }
                if (attempt < MAX_WAKE_TICKS) {
                    final PhysicsModBridge.OfflineSubLevelLocation nextLoc = activeLoc;
                    server.schedule(new TickTask(server.getTickCount() + 1, () ->
                        pollWakeSubLevel(server, targetLevel, target, nextLoc, prevResult, future, attempt + 1)));
                    return;
                }
                ChunkPos failChunk = activeLoc.holdingChunk() != null
                    ? activeLoc.holdingChunk()
                    : ChunkPos.containing(target.getPos());
                future.complete(Either.right(new WaystoneTeleportError.DestinationChunkLoadFailed(
                    target.getDimension(),
                    failChunk,
                    "Physics sub-level did not wake within " + MAX_WAKE_TICKS + " ticks"
                )));
                return;
            } catch (Throwable t) {
                MixinGuard.logOnce("PhysicsPosSync.pollWakeSubLevel", t);
            }
            future.complete(Either.right(new WaystoneTeleportError.DestinationChunkLoadFailed(
                target.getDimension(),
                ChunkPos.containing(target.getPos()),
                "Physics sub-level wakeup failed"
            )));
        };
        if (server.isSameThread()) {
            step.run();
        } else {
            server.execute(step);
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
