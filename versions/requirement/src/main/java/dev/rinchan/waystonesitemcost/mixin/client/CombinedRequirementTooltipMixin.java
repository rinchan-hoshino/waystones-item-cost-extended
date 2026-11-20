package dev.rinchan.waystonesitemcost.mixin.client;

import dev.rinchan.waystonesitemcost.MixinGuard;
import dev.rinchan.waystonesitemcost.minecraft.PaymentTooltipHelper;
import dev.rinchan.waystonesitemcost.payment.RequirementPayment;
import java.util.List;
import net.blay09.mods.waystones.api.requirement.WarpRequirement;
import net.blay09.mods.waystones.requirement.CombinedRequirement;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CombinedRequirement.class)
public abstract class CombinedRequirementTooltipMixin {
    @Inject(method = "appendHoverText", at = @At("TAIL"), remap = false)
    private void itemCostExtended$appendCombinedTooltip(Player player, List<Component> tooltip, CallbackInfo ci) {
        try {
            if (player == null) return;
            var reqPayment = new RequirementPayment((WarpRequirement) (Object) this);
            if (!reqPayment.hasItemRequirements()) return;
            var preview = reqPayment.previewItems(player);
            // Only append combined-level warning if inventory is full for change across combined costs
            // (individual ItemRequirement tooltips already handle single-item breakdowns; avoid duplicating)
            if (preview.status() == dev.rinchan.waystonesitemcost.payment.PaymentStatus.INVENTORY_FULL_FOR_CHANGE) {
                var warning = Component.translatable(
                    "tooltip.waystones_item_cost_extended.inventory_full_for_change",
                    Math.max(1, preview.slotsNeededForChange())
                );
                boolean alreadyPresent = tooltip.stream().anyMatch(c -> c.getString().equals(warning.getString()));
                if (!alreadyPresent) {
                    PaymentTooltipHelper.appendPreviewLines(preview, tooltip);
                }
            }
        } catch (Throwable t) {
            MixinGuard.logOnce("CombinedRequirement.appendHoverText", t);
        }
    }
}
