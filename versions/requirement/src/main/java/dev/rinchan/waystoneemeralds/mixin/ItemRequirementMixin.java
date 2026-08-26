package dev.rinchan.waystoneemeralds.mixin;

import dev.rinchan.waystoneemeralds.payment.EmeraldInventory;
import net.blay09.mods.waystones.requirement.ItemRequirement;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemRequirement.class)
public abstract class ItemRequirementMixin {
    @Unique
    private int paidWaystones$consumedUnits;

    @Inject(method = "canAfford", at = @At("HEAD"), cancellable = true, remap = false)
    private void paidWaystones$acceptEmeraldBlocks(Player player, CallbackInfoReturnable<Boolean> cir) {
        var requirement = (ItemRequirement) (Object) this;
        if (requirement.getItemStack().is(Items.EMERALD)) {
            cir.setReturnValue(EmeraldInventory.canPay(player, requirement.getCount()));
        }
    }

    @Inject(method = "consume", at = @At("HEAD"), cancellable = true, remap = false)
    private void paidWaystones$consumeEmeraldValue(Player player, CallbackInfo ci) {
        var requirement = (ItemRequirement) (Object) this;
        if (requirement.getItemStack().is(Items.EMERALD)) {
            int requiredUnits = requirement.getCount();
            paidWaystones$consumedUnits = EmeraldInventory.consume(player, requiredUnits) && !player.getAbilities().instabuild
                ? requiredUnits
                : 0;
            ci.cancel();
        }
    }

    @Inject(method = "rollback", at = @At("HEAD"), cancellable = true, remap = false)
    private void paidWaystones$refundEmeraldValue(Player player, CallbackInfo ci) {
        var requirement = (ItemRequirement) (Object) this;
        if (requirement.getItemStack().is(Items.EMERALD)) {
            EmeraldInventory.refund(player, paidWaystones$consumedUnits);
            paidWaystones$consumedUnits = 0;
            ci.cancel();
        }
    }
}
