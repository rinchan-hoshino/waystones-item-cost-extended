package dev.rinchan.waystonesitemcost.mixin;

import dev.rinchan.waystonesitemcost.MixinGuard;
import dev.rinchan.waystonesitemcost.network.PhysicsPosSync;
import net.blay09.mods.waystones.api.IWaystone;
import net.blay09.mods.waystones.core.WarpMode;
import net.blay09.mods.waystones.menu.WaystoneSelectionMenu;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WaystoneSelectionMenu.class)
public abstract class LegacyWaystoneMenuSyncMixin {
    @Inject(method = "createWaystoneSelection", at = @At("HEAD"), remap = false)
    private static void itemCostExtended$syncPhysicsPosBeforeMenu(
        int containerId,
        Player player,
        WarpMode warpMode,
        IWaystone fromWaystone,
        CallbackInfoReturnable<WaystoneSelectionMenu> cir
    ) {
        try {
            PhysicsPosSync.syncTo(player);
        } catch (Throwable t) {
            MixinGuard.logOnce("WaystoneSelectionMenu.createWaystoneSelection", t);
        }
    }
}
