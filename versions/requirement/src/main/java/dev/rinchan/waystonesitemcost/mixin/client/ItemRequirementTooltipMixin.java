package dev.rinchan.waystonesitemcost.mixin.client;

import dev.rinchan.waystonesitemcost.MixinGuard;
import dev.rinchan.waystonesitemcost.minecraft.PaymentTooltipHelper;
import dev.rinchan.waystonesitemcost.payment.ItemPayment;
import java.util.List;
import net.blay09.mods.waystones.requirement.ItemRequirement;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemRequirement.class)
public abstract class ItemRequirementTooltipMixin {
    @Inject(method = "appendHoverText", at = @At("TAIL"), remap = false)
    private void itemCostExtended$appendTooltip(Player player, List<Component> tooltip, CallbackInfo ci) {
        try {
            var self = (ItemRequirement) (Object) this;
            if (self.isEmpty() || player == null) return;
            var payment = new ItemPayment(player);
            payment.charge(self.getItemStack(), self.getCount());
            PaymentTooltipHelper.appendPreviewLines(payment, tooltip);
        } catch (Throwable t) {
            MixinGuard.logOnce("ItemRequirement.appendHoverText", t);
        }
    }
}
