package dev.rinchan.waystoneemeralds.payment;

import dev.rinchan.rinlib.payment.DenominatedPayment;
import java.util.Optional;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class EmeraldInventory {
    private static final EmeraldPaymentTransaction.Planner PLANNER = EmeraldInventory::plan;

    private EmeraldInventory() {
    }

    public static int availableUnits(Player player) {
        return EmeraldPaymentTransaction.availableUnits(new PlayerInventoryAccess(player));
    }

    public static boolean canPay(Player player, int requiredUnits) {
        return EmeraldPaymentTransaction.canPay(new PlayerInventoryAccess(player), requiredUnits, PLANNER);
    }

    public static boolean consume(Player player, int requiredUnits) {
        return EmeraldPaymentTransaction.consume(new PlayerInventoryAccess(player), requiredUnits, PLANNER);
    }

    public static void refund(Player player, int units) {
        EmeraldPaymentTransaction.refund(new PlayerInventoryAccess(player), units);
    }

    private static Optional<EmeraldPaymentTransaction.Plan> plan(
        int requiredUnits,
        java.util.List<EmeraldPaymentTransaction.Stack> stacks
    ) {
        var rinlibStacks = stacks.stream()
            .map(stack -> new DenominatedPayment.Stack(stack.slot(), stack.itemCount(), stack.unitValue()))
            .toList();
        return DenominatedPayment.plan(requiredUnits, rinlibStacks).map(plan -> new EmeraldPaymentTransaction.Plan(
            plan.removals().stream()
                .map(removal -> new EmeraldPaymentTransaction.Removal(removal.slot(), removal.itemCount()))
                .toList(),
            plan.changeUnits()
        ));
    }

    private static int unitValue(ItemStack stack) {
        return EmeraldDenomination.units(stack.is(Items.EMERALD), stack.is(Items.EMERALD_BLOCK));
    }

    private static final class PlayerInventoryAccess implements EmeraldPaymentTransaction.InventoryAccess {
        private final Player player;

        private PlayerInventoryAccess(Player player) {
            this.player = player;
        }

        @Override
        public boolean creative() {
            return player.getAbilities().instabuild;
        }

        @Override
        public int size() {
            return player.getInventory().getContainerSize();
        }

        @Override
        public int count(int slot) {
            return player.getInventory().getItem(slot).getCount();
        }

        @Override
        public int unitValue(int slot) {
            return EmeraldInventory.unitValue(player.getInventory().getItem(slot));
        }

        @Override
        public void remove(int slot, int count) {
            player.getInventory().getItem(slot).shrink(count);
        }

        @Override
        public void giveEmeralds(int count) {
            if (count <= 0) {
                return;
            }
            var stack = new ItemStack(Items.EMERALD, count);
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
        }
    }
}
