package dev.rinchan.waystonesitemcost.mixin;

import dev.rinchan.waystonesitemcost.MixinGuard;
import dev.rinchan.waystonesitemcost.config.PaymentConfig;
import dev.rinchan.waystonesitemcost.physics.PhysicsPosResolver;
import net.blay09.mods.waystones.api.IWaystone;
import net.blay09.mods.waystones.api.IWaystoneTeleportContext;
import net.blay09.mods.waystones.core.PlayerWaystoneManager;
import net.blay09.mods.waystones.core.WarpMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PlayerWaystoneManager.class)
public abstract class LegacyPhysicsDistanceMixin {
    @Redirect(
        method = "getExperienceLevelCost",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;distanceToSqr(DDD)D")
    )
    private static double itemCostExtended$resolveXpDistanceSqr(
        Player receiver,
        double x,
        double y,
        double z,
        Entity entity,
        IWaystone waystone,
        WarpMode warpMode,
        IWaystoneTeleportContext context
    ) {
        try {
            if (PaymentConfig.fixPhysicsShipDistance() && waystone != null) {
                return PhysicsPosResolver.resolveDistanceSqr(
                    receiver,
                    waystone.getWaystoneUid(),
                    waystone.getDimension(),
                    waystone.getPos()
                );
            }
        } catch (Throwable t) {
            MixinGuard.logOnce("PlayerWaystoneManager.getExperienceLevelCost", t);
        }
        return receiver.distanceToSqr(x, y, z);
    }
}
