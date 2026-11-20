package dev.rinchan.waystonesitemcost.mixin;

import dev.rinchan.waystonesitemcost.MixinGuard;
import dev.rinchan.waystonesitemcost.config.PaymentConfig;
import dev.rinchan.waystonesitemcost.physics.PhysicsPosResolver;
import net.blay09.mods.waystones.api.Waystone;
import net.blay09.mods.waystones.comparator.DistanceToPlayerComparator;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DistanceToPlayerComparator.class)
public abstract class PhysicsDistanceComparatorMixin {
    @Shadow @Final private Player player;

    @Inject(
        method = "compare(Lnet/blay09/mods/waystones/api/Waystone;Lnet/blay09/mods/waystones/api/Waystone;)I",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void itemCostExtended$comparePhysicsDistance(Waystone first, Waystone second, CallbackInfoReturnable<Integer> cir) {
        try {
            if (!PaymentConfig.fixPhysicsShipDistance() || player == null || first == null || second == null) {
                return;
            }
            double d1 = PhysicsPosResolver.resolveDistanceSqr(
                player, first.getWaystoneUid(), first.getDimension(), first.getPos());
            double d2 = PhysicsPosResolver.resolveDistanceSqr(
                player, second.getWaystoneUid(), second.getDimension(), second.getPos());
            cir.setReturnValue(Double.compare(d1, d2));
        } catch (Throwable t) {
            MixinGuard.logOnce("DistanceToPlayerComparator.compare", t);
        }
    }
}
