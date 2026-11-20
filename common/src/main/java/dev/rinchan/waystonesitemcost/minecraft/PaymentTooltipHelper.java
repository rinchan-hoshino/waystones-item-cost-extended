package dev.rinchan.waystonesitemcost.minecraft;

import dev.rinchan.waystonesitemcost.config.PaymentConfig;
import dev.rinchan.waystonesitemcost.payment.ItemPayment;
import dev.rinchan.waystonesitemcost.payment.PaymentStatus;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Formats localized Waystones cost tooltip breakdown lines and server-side rejection feedback.
 */
public final class PaymentTooltipHelper {
    private PaymentTooltipHelper() {
    }

    public static void appendPreviewLines(ItemPayment payment, List<Component> tooltip) {
        if (!PaymentConfig.showPaymentPreviewTooltip()) return;
        if (payment.status() == PaymentStatus.INVENTORY_FULL_FOR_CHANGE) {
            tooltip.add(Component.translatable(
                "tooltip.waystones_item_cost_extended.inventory_full_for_change",
                Math.max(1, payment.slotsNeededForChange())
            ).withStyle(ChatFormatting.RED));
            return;
        }
        if (payment.status() != PaymentStatus.SUCCESS || !payment.hasSpecialBreakdown()) {
            return;
        }
        var consumed = payment.consumedSummary();
        var change = payment.changeSummary();
        if (!consumed.isEmpty() && !change.isEmpty()) {
            tooltip.add(Component.translatable(
                "tooltip.waystones_item_cost_extended.actual_payment_with_change",
                formatStackList(consumed),
                formatStackList(change)
            ).withStyle(ChatFormatting.AQUA));
        } else if (!consumed.isEmpty() && payment.usedConversion()) {
            tooltip.add(Component.translatable(
                "tooltip.waystones_item_cost_extended.actual_payment",
                formatStackList(consumed)
            ).withStyle(ChatFormatting.AQUA));
        }
        if (payment.usedWallet()) {
            tooltip.add(Component.translatable(
                "tooltip.waystones_item_cost_extended.from_wallet"
            ).withStyle(ChatFormatting.GOLD));
        }
        if (payment.usedNestedContainer()) {
            tooltip.add(Component.translatable(
                "tooltip.waystones_item_cost_extended.from_container"
            ).withStyle(ChatFormatting.GRAY));
        }
    }

    public static void notifyInventoryFull(Player player, int slotsNeeded) {
        if (player == null || player.level().isClientSide()) return;
        RecipeAccess.sendOverlayMessage(player, Component.translatable(
            "message.waystones_item_cost_extended.inventory_full_for_change",
            Math.max(1, slotsNeeded)
        ).withStyle(ChatFormatting.RED));
    }

    public static void notifyInsufficientFunds(Player player) {
        if (player == null || player.level().isClientSide()) return;
        RecipeAccess.sendOverlayMessage(player, Component.translatable(
            "message.waystones_item_cost_extended.insufficient_funds"
        ).withStyle(ChatFormatting.RED));
    }

    private static MutableComponent formatStackList(List<ItemStack> stacks) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < stacks.size(); i++) {
            if (i > 0) out.append(Component.literal(", "));
            var s = stacks.get(i);
            out.append(Component.literal(s.getCount() + "x ")).append(s.getHoverName());
        }
        return out;
    }
}
