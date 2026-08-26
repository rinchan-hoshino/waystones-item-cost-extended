package dev.rinchan.waystoneemeralds.payment;

import dev.rinchan.rinlib.payment.DenominatedPayment;
import java.util.ArrayList;
import java.util.Optional;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class EmeraldInventory {
    private static final int EMERALD_VALUE = 1;
    private static final int EMERALD_BLOCK_VALUE = 9;

    private EmeraldInventory() {
    }

    public static int availableUnits(Player player) {
        long units = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            int unitValue = unitValue(stack);
            if (unitValue != 0) {
                units += (long) stack.getCount() * unitValue;
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, units);
    }

    public static boolean canPay(Player player, int requiredUnits) {
        return player.getAbilities().instabuild || createPlan(player, requiredUnits).isPresent();
    }

    public static boolean consume(Player player, int requiredUnits) {
        if (player.getAbilities().instabuild) {
            return true;
        }

        var plan = createPlan(player, requiredUnits);
        if (plan.isEmpty()) {
            return false;
        }

        var inventory = player.getInventory();
        for (var removal : plan.orElseThrow().removals()) {
            inventory.getItem(removal.slot()).shrink(removal.itemCount());
        }
        giveEmeralds(player, plan.orElseThrow().changeUnits());
        return true;
    }

    public static void refund(Player player, int units) {
        if (!player.getAbilities().instabuild) {
            giveEmeralds(player, units);
        }
    }

    private static Optional<DenominatedPayment.Plan> createPlan(Player player, int requiredUnits) {
        var inventory = player.getInventory();
        var stacks = new ArrayList<DenominatedPayment.Stack>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            int unitValue = unitValue(stack);
            if (unitValue != 0) {
                stacks.add(new DenominatedPayment.Stack(slot, stack.getCount(), unitValue));
            }
        }
        return DenominatedPayment.plan(requiredUnits, stacks);
    }

    private static int unitValue(ItemStack stack) {
        if (stack.is(Items.EMERALD)) {
            return EMERALD_VALUE;
        }
        if (stack.is(Items.EMERALD_BLOCK)) {
            return EMERALD_BLOCK_VALUE;
        }
        return 0;
    }

    private static void giveEmeralds(Player player, int count) {
        if (count <= 0) {
            return;
        }
        var stack = new ItemStack(Items.EMERALD, count);
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }
}
