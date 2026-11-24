package dev.rinchan.waystonesitemcost.mixin;

import com.mojang.datafixers.util.Either;
import dev.rinchan.waystonesitemcost.MixinGuard;
import dev.rinchan.waystonesitemcost.config.PaymentConfig;
import dev.rinchan.waystonesitemcost.physics.PhysicsModBridge;
import dev.rinchan.waystonesitemcost.physics.PhysicsPosResolver;
import net.blay09.mods.waystones.InternalMethodsImpl;
import net.blay09.mods.waystones.api.IWaystone;
import net.blay09.mods.waystones.api.IWaystoneTeleportContext;
import net.blay09.mods.waystones.api.WaystoneTeleportError;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = InternalMethodsImpl.class, remap = false)
public abstract class LegacyPhysicsWakeupMixin {
    @Inject(
        method = "createCustomTeleportContext",
        at = @At(
            value = "INVOKE",
            target = "Lnet/blay09/mods/waystones/api/IWaystone;isValidInLevel(Lnet/minecraft/server/level/ServerLevel;)Z"
        )
    )
    private void itemCostExtended$wakePhysicsSubLevelBeforeValidation(
        Entity entity,
        IWaystone waystone,
        CallbackInfoReturnable<Either<IWaystoneTeleportContext, WaystoneTeleportError>> cir
    ) {
        try {
            if (!PaymentConfig.fixPhysicsShipDistance() || !PhysicsModBridge.isAnyPhysicsModLoaded()) {
                return;
            }
            if (entity == null || waystone == null) {
                return;
            }
            MinecraftServer server = entity.getServer();
            if (server == null) {
                return;
            }
            ServerLevel targetLevel = server.getLevel(waystone.getDimension());
            if (targetLevel == null) {
                return;
            }
            Vec3 center = Vec3.atCenterOf(waystone.getPos());
            if (!PhysicsModBridge.isInPhysicsSpace(targetLevel, center.x, center.y, center.z)) {
                return;
            }
            if (PhysicsModBridge.projectToWorld(targetLevel, center.x, center.y, center.z) != null
                && !targetLevel.getBlockState(waystone.getPos()).isAir()) {
                PhysicsPosResolver.refreshWaystoneOnServer(server, waystone.getWaystoneUid(), waystone.getDimension(), waystone.getPos());
                return;
            }
            PhysicsModBridge.OfflineSubLevelLocation loc = PhysicsPosResolver.resolveOfflineLocationOnServer(
                server, waystone.getWaystoneUid(), waystone.getDimension(), waystone.getPos(), true);
            if (loc == null) {
                return;
            }
            int entityId = entity.getId();
            if (loc.hasChunkBounds()) {
                for (int cx = loc.minChunkX(); cx <= loc.maxChunkX(); cx++) {
                    for (int cz = loc.minChunkZ(); cz <= loc.maxChunkZ(); cz++) {
                        ChunkPos cp = new ChunkPos(cx, cz);
                        targetLevel.getChunkSource().addRegionTicket(TicketType.POST_TELEPORT, cp, 2, entityId);
                        targetLevel.getChunk(cx, cz);
                    }
                }
            }
            if (loc.holdingChunk() != null) {
                targetLevel.getChunkSource().addRegionTicket(TicketType.POST_TELEPORT, loc.holdingChunk(), 2, entityId);
                targetLevel.getChunk(loc.holdingChunk().x, loc.holdingChunk().z);
            }
            PhysicsModBridge.wakeSableSubLevel(targetLevel, loc, center.x, center.y, center.z);
            if (PhysicsModBridge.projectToWorld(targetLevel, center.x, center.y, center.z) != null) {
                PhysicsPosResolver.refreshWaystoneOnServer(server, waystone.getWaystoneUid(), waystone.getDimension(), waystone.getPos());
            }
        } catch (Throwable t) {
            MixinGuard.logOnce("InternalMethodsImpl.createCustomTeleportContext", t);
        }
    }
}
