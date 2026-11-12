package dev.rinchan.waystoneemeralds.mixin;
import dev.rinchan.waystoneemeralds.network.RecipeSync;
import net.blay09.mods.balm.api.network.BalmNetworking;
import net.blay09.mods.waystones.network.ModNetworking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
// Injects at TAIL of ModNetworking.initialize.
@Mixin(ModNetworking.class)
public abstract class NetworkingMixin {
    @Inject(method="initialize", at=@At("TAIL"), remap=false)
    private static void itemCostExtended$register(BalmNetworking networking, CallbackInfo callback) { RecipeSync.register(networking); }
}
