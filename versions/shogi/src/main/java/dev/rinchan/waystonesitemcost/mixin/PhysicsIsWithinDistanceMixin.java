package dev.rinchan.waystonesitemcost.mixin;

import com.mojang.datafixers.util.Either;
import dev.rinchan.waystonesitemcost.MixinGuard;
import dev.rinchan.waystonesitemcost.config.PaymentConfig;
import dev.rinchan.waystonesitemcost.physics.PhysicsPosResolver;
import net.blay09.mods.shogi.context.ShogiContext;
import net.blay09.mods.waystones.config.rules.IsWithinDistance;
import net.blay09.mods.waystones.config.rules.WaystoneRuleContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(IsWithinDistance.class)
public abstract class PhysicsIsWithinDistanceMixin {
    @Shadow @Final private float distance;

    @Inject(
        method = "apply(Lnet/blay09/mods/shogi/context/ShogiContext;)Lcom/mojang/datafixers/util/Either;",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void itemCostExtended$resolvePhysicsWithinDistance(ShogiContext context, CallbackInfoReturnable<Either<? extends Boolean, ?>> cir) {
        try {
            if (!PaymentConfig.fixPhysicsShipDistance() || context == null) {
                return;
            }
            var targetOpt = WaystoneRuleContext.getTargetWaystone(context);
            if (targetOpt.isEmpty()) {
                return;
            }
            var target = targetOpt.get();
            float dist = (float) PhysicsPosResolver.resolveDistance(
                context.requireEntity(),
                target.getWaystoneUid(),
                target.getDimension(),
                target.getPos()
            );
            cir.setReturnValue(Either.left(dist <= this.distance));
        } catch (Throwable t) {
            MixinGuard.logOnce("IsWithinDistance.apply", t);
        }
    }
}
