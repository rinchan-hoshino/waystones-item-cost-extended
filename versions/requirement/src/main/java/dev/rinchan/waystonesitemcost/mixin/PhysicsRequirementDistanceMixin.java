package dev.rinchan.waystonesitemcost.mixin;

import dev.rinchan.waystonesitemcost.MixinGuard;
import dev.rinchan.waystonesitemcost.physics.PhysicsPosResolver;
import net.blay09.mods.waystones.requirement.RequirementRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RequirementRegistry.class)
public abstract class PhysicsRequirementDistanceMixin {
    @Inject(method = "registerDefaults", at = @At("TAIL"), remap = false)
    private static void itemCostExtended$overrideDistanceResolvers(CallbackInfo ci) {
        try {
            RequirementRegistry.registerBoundVariableResolver("distance", context -> {
                var target = context.getTargetWaystone();
                return (float) PhysicsPosResolver.resolveDistance(
                    context.getEntity(),
                    target.getWaystoneUid(),
                    target.getDimension(),
                    target.getPos()
                );
            });
            RequirementRegistry.registerBoundConditionResolver(
                "is_within_distance",
                RequirementRegistry.FloatParameter.class,
                (context, param) -> {
                    var target = context.getTargetWaystone();
                    float dist = (float) PhysicsPosResolver.resolveDistance(
                        context.getEntity(),
                        target.getWaystoneUid(),
                        target.getDimension(),
                        target.getPos()
                    );
                    return dist <= param.value();
                }
            );
        } catch (Throwable t) {
            MixinGuard.logOnce("RequirementRegistry.registerDefaults", t);
        }
    }
}
