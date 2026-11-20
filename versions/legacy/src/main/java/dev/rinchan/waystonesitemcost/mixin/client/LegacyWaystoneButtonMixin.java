package dev.rinchan.waystonesitemcost.mixin.client;

import dev.rinchan.waystonesitemcost.MixinGuard;
import dev.rinchan.waystonesitemcost.config.PaymentConfig;
import dev.rinchan.waystonesitemcost.minecraft.PaymentTooltipHelper;
import dev.rinchan.waystonesitemcost.payment.LegacyTeleportHandler;
import dev.rinchan.waystonesitemcost.payment.PaymentStatus;
import java.util.ArrayList;
import net.blay09.mods.waystones.api.IWaystone;
import net.blay09.mods.waystones.client.gui.widget.WaystoneButton;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WaystoneButton.class)
public abstract class LegacyWaystoneButtonMixin {
    @Shadow @Final private IWaystone waystone;

    @Inject(method = "renderWidget", at = @At("TAIL"))
    private void itemCostExtended$renderCustomCost(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        try {
            if (!PaymentConfig.legacyEnableCustomItemCosts()) return;
            Minecraft mc = Minecraft.getInstance();
            var player = mc.player;
            if (player == null || waystone == null) return;
            var costs = LegacyTeleportHandler.resolveCosts(player, waystone);
            if (costs.isEmpty()) return;

            var self = (WaystoneButton) (Object) this;
            var preview = LegacyTeleportHandler.preview(player, waystone);
            boolean canPay = preview.status() == PaymentStatus.SUCCESS;

            // Render compact item icon + count on the right side of the button
            int iconX = self.getX() + self.getWidth() - 20;
            int iconY = self.getY() + 2;
            var first = costs.get(0);
            graphics.renderFakeItem(first.item(), iconX, iconY);
            String label = String.valueOf(first.count());
            int color = canPay ? 0x55FF55 : 0xFF5555;
            graphics.drawString(mc.font, label, iconX - mc.font.width(label) - 2, self.getY() + 6, color, true);

            if (self.isHoveredOrFocused() && PaymentConfig.showPaymentPreviewTooltip()) {
                var tooltip = new ArrayList<Component>();
                for (var cost : costs) {
                    tooltip.add(Component.translatable(
                        "tooltip.waystones_item_cost_extended.legacy_item_cost",
                        cost.count(),
                        cost.item().getHoverName()
                    ).withStyle(canPay ? ChatFormatting.GREEN : ChatFormatting.RED));
                }
                PaymentTooltipHelper.appendPreviewLines(preview, tooltip);
                graphics.renderComponentTooltip(mc.font, tooltip, mouseX, mouseY);
            }
        } catch (Throwable t) {
            MixinGuard.logOnce("WaystoneButton.renderWidget", t);
        }
    }
}
