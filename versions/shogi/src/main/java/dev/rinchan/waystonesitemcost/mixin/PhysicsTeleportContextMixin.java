package dev.rinchan.waystonesitemcost.mixin;

import dev.rinchan.waystonesitemcost.MixinGuard;
import dev.rinchan.waystonesitemcost.config.PaymentConfig;
import dev.rinchan.waystonesitemcost.physics.PhysicsPosResolver;
import java.util.Optional;
import net.blay09.mods.waystones.api.Waystone;
import net.blay09.mods.waystones.core.WaystoneTeleportContextImpl;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WaystoneTeleportContextImpl.class)
public abstract class PhysicsTeleportContextMixin {
    @Shadow @Final private Entity entity;
    @Shadow @Final private Waystone targetWaystone;

    @Inject(method = "getVariable", at = @At("HEAD"), cancellable = true, remap = false)
    private void itemCostExtended$overrideDistanceVariable(String name, CallbackInfoReturnable<Optional<Object>> cir) {
        try {
            if (!"distance".equals(name) || !PaymentConfig.fixPhysicsShipDistance()) {
                return;
            }
            if (entity == null || targetWaystone == null) {
                return;
            }
            float dist = (float) PhysicsPosResolver.resolveDistance(
                entity,
                targetWaystone.getWaystoneUid(),
                targetWaystone.getDimension(),
                targetWaystone.getPos()
            );
            cir.setReturnValue(Optional.of(dist));
        } catch (Throwable t) {
            MixinGuard.logOnce("WaystoneTeleportContextImpl.getVariable", t);
        }
    }
}
