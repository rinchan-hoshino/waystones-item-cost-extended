package dev.rinchan.waystonesitemcost.mixin.client;

import dev.rinchan.waystonesitemcost.MixinGuard;
import dev.rinchan.waystonesitemcost.minecraft.PaymentTooltipHelper;
import dev.rinchan.waystonesitemcost.payment.ItemPayment;
import java.util.List;
import java.util.function.Predicate;
import net.blay09.mods.shogi.common.effect.cost.ItemCostInformation;
import net.blay09.mods.waystones.client.requirement.ItemRequirementRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemRequirementRenderer.class)
public abstract class ShogiItemTooltipMixin {
    @Inject(method = "appendHoverText(Lnet/minecraft/world/entity/player/Player;Lnet/blay09/mods/shogi/common/effect/cost/ItemCostInformation;Ljava/util/List;)V", at = @At("TAIL"), remap = false)
    private void itemCostExtended$appendTooltip(Player player, ItemCostInformation info, List<Component> tooltip, CallbackInfo ci) {
        try {
            if (player == null || info == null || info.required() <= 0) return;
            var payment = new ItemPayment(player);
            if (info.item().size() == 1) {
                var currency = new ItemStack(info.item().stream().findFirst().orElseThrow().value());
                payment.charge(currency, info.required());
            } else {
                Predicate<ItemStack> matcher = stack -> stack.is(info.item());
                payment.charge(matcher, info.required());
            }
            PaymentTooltipHelper.appendPreviewLines(payment, tooltip);
        } catch (Throwable t) {
            MixinGuard.logOnce("ItemRequirementRenderer.appendHoverText", t);
        }
    }
}
