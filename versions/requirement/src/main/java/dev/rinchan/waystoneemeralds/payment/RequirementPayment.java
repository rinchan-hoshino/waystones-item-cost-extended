package dev.rinchan.waystoneemeralds.payment;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.blay09.mods.waystones.api.requirement.WarpRequirement;
import net.blay09.mods.waystones.requirement.CombinedRequirement;
import net.blay09.mods.waystones.requirement.ItemRequirement;
import net.minecraft.world.entity.player.Player;

public final class RequirementPayment {
    private final List<WarpRequirement> requirements = new ArrayList<>();
    private ItemPayment receipt;

    public RequirementPayment(WarpRequirement requirement) { collect(requirement); }

    private void collect(WarpRequirement requirement) {
        if (requirement instanceof CombinedRequirement combined) {
            for (var child : combined.getRequirements()) collect(child);
        } else requirements.add(requirement);
    }

    public boolean canAfford(Player player) {
        var payment = new ItemPayment(player);
        for (var requirement : requirements) {
            boolean affordable = requirement instanceof ItemRequirement item
                ? payment.charge(item.getItemStack(), item.getCount()) : requirement.canAfford(player);
            if (!affordable) return false;
        }
        return true;
    }

    public void consume(Player player, Consumer<WarpRequirement> consumeOther) {
        var payment = new ItemPayment(player);
        for (var requirement : requirements) {
            if (requirement instanceof ItemRequirement item && !payment.charge(item.getItemStack(), item.getCount()))
                throw new IllegalStateException("Item payment changed after teleport validation");
        }
        if (!payment.commit()) throw new IllegalStateException("Inventory changed during item payment");
        receipt = payment;
        for (var requirement : requirements) if (!(requirement instanceof ItemRequirement)) consumeOther.accept(requirement);
    }

    public void rollback(Consumer<WarpRequirement> rollbackOther) {
        if (receipt == null) return;
        for (var requirement : requirements) if (!(requirement instanceof ItemRequirement)) rollbackOther.accept(requirement);
        receipt.rollback();
        receipt = null;
    }
}
