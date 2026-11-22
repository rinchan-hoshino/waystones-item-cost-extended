package dev.rinchan.waystonesitemcost.mixin;

import dev.rinchan.waystonesitemcost.MixinGuard;
import dev.rinchan.waystonesitemcost.network.PhysicsPosSync;
import dev.rinchan.waystonesitemcost.network.RecipeSync;
import net.blay09.mods.balm.network.BalmNetworking;
import net.blay09.mods.waystones.network.ModNetworking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModNetworking.class)
public abstract class NetworkingMixin {
    @Inject(method = "initialize", at = @At("TAIL"), remap = false)
    private static void itemCostExtended$registerPackets(BalmNetworking networking, CallbackInfo callback) {
        try {
            RecipeSync.register(networking);
        } catch (Throwable t) {
            MixinGuard.logOnce("ModNetworking.initialize.RecipeSync", t);
        }
        try {
            PhysicsPosSync.register(networking);
        } catch (Throwable t) {
            MixinGuard.logOnce("ModNetworking.initialize.PhysicsPosSync", t);
        }
    }
}
