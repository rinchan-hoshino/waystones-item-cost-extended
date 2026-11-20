package dev.rinchan.waystonesitemcost.payment;

import dev.rinchan.waystonesitemcost.MixinGuard;
import dev.rinchan.waystonesitemcost.config.LegacyCostEntry;
import dev.rinchan.waystonesitemcost.config.PaymentConfig;
import dev.rinchan.waystonesitemcost.minecraft.PaymentTooltipHelper;
import dev.rinchan.waystonesitemcost.minecraft.RecipeCurrencies;
import java.util.ArrayList;
import java.util.List;
import net.blay09.mods.balm.api.Balm;
import net.blay09.mods.waystones.api.IWaystone;
import net.blay09.mods.waystones.api.WaystoneTeleportEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Minecraft 1.20.1 custom item cost handler using Waystones' official WaystoneTeleportEvent.Pre,
 * reusing the exact same ItemPayment multi-denomination & Lightman's Currency engine.
 */
public final class LegacyTeleportHandler {
    private static boolean registered;

    private LegacyTeleportHandler() {}

    public record ResolvedCost(ItemStack item, int count) {}

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        Balm.getEvents().onEvent(WaystoneTeleportEvent.Pre.class, LegacyTeleportHandler::onPreTeleport);
    }

    public static List<ResolvedCost> resolveCosts(Player player, IWaystone target) {
        if (!PaymentConfig.legacyEnableCustomItemCosts() || player == null || target == null) {
            return List.of();
        }
        boolean crossDim = !player.level().dimension().equals(target.getDimension());
        double distance = crossDim ? 0.0 : Math.sqrt(player.blockPosition().distSqr(target.getPos()));
        var out = new ArrayList<ResolvedCost>();
        for (LegacyCostEntry entry : PaymentConfig.legacyItemCosts()) {
            int qty = entry.calculateCost(distance, crossDim);
            if (qty <= 0) continue;
            Item item = RecipeCurrencies.item(entry.itemId());
            if (item == Items.AIR) continue;
            out.add(new ResolvedCost(new ItemStack(item), qty));
        }
        return out;
    }

    public static ItemPayment preview(Player player, IWaystone target) {
        var payment = new ItemPayment(player);
        for (var cost : resolveCosts(player, target)) {
            if (!payment.charge(cost.item(), cost.count())) {
                break;
            }
        }
        return payment;
    }

    private static void onPreTeleport(WaystoneTeleportEvent.Pre event) {
        try {
            if (!PaymentConfig.legacyEnableCustomItemCosts()) return;
            var context = event.getContext();
            if (!(context.getEntity() instanceof Player player)) return;
            if (player.getAbilities().instabuild) {
                if (PaymentConfig.legacyWaiveVanillaXpCost()) event.setXpCost(0);
                return;
            }
            var costs = resolveCosts(player, context.getTargetWaystone());
            if (costs.isEmpty()) return;

            var payment = new ItemPayment(player);
            for (var cost : costs) {
                if (!payment.charge(cost.item(), cost.count())) {
                    event.setCanceled(true);
                    if (payment.status() == PaymentStatus.INVENTORY_FULL_FOR_CHANGE) {
                        PaymentTooltipHelper.notifyInventoryFull(player, payment.slotsNeededForChange());
                    } else {
                        PaymentTooltipHelper.notifyInsufficientFunds(player);
                    }
                    return;
                }
            }
            if (!payment.commit()) {
                event.setCanceled(true);
                return;
            }
            if (PaymentConfig.legacyWaiveVanillaXpCost()) {
                event.setXpCost(0);
            }
        } catch (Throwable t) {
            MixinGuard.logOnce("WaystoneTeleportEvent.Pre", t);
        }
    }
}
