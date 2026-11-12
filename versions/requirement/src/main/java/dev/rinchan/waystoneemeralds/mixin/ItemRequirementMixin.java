package dev.rinchan.waystoneemeralds.mixin;

import dev.rinchan.waystoneemeralds.payment.RequirementPayment;
import net.blay09.mods.waystones.api.requirement.WarpRequirement;
import net.blay09.mods.waystones.requirement.ItemRequirement;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Injects at HEAD of ItemRequirement.canAfford and consume, so the emerald payment is checked at the requirement entry point.
@Mixin(ItemRequirement.class)
public abstract class ItemRequirementMixin {
    @Unique private RequirementPayment itemCostExtended$payment;

    @Unique private RequirementPayment itemCostExtended$payment() {
        if (itemCostExtended$payment == null)
            itemCostExtended$payment = new RequirementPayment((WarpRequirement) (Object) this);
        return itemCostExtended$payment;
    }

    @Inject(method = "canAfford", at = @At("HEAD"), cancellable = true, remap = false)
    private void itemCostExtended$canPay(Player player, CallbackInfoReturnable<Boolean> callback) {
        callback.setReturnValue(itemCostExtended$payment().canAfford(player));
    }

    @Inject(method = "consume", at = @At("HEAD"), cancellable = true, remap = false)
    private void itemCostExtended$pay(Player player, CallbackInfo callback) {
        itemCostExtended$payment().consume(player, requirement -> requirement.consume(player));
        callback.cancel();
    }

    @Inject(method = "rollback", at = @At("HEAD"), cancellable = true, remap = false)
    private void itemCostExtended$refund(Player player, CallbackInfo callback) {
        itemCostExtended$payment().rollback(requirement -> requirement.rollback(player));
        callback.cancel();
    }
}
