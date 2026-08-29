package dev.rinchan.waystoneemeralds.mixin;

import com.mojang.datafixers.util.Either;
import dev.rinchan.waystoneemeralds.payment.EmeraldCostAccumulator;
import dev.rinchan.waystoneemeralds.payment.EmeraldInventory;
import net.blay09.mods.shogi.coercion.Coercion;
import net.blay09.mods.shogi.common.effect.cost.ItemCost;
import net.blay09.mods.shogi.common.effect.cost.ItemCostInformation;
import net.blay09.mods.shogi.context.ShogiContext;
import net.blay09.mods.shogi.context.executor.aggregate.AggregateKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemCost.class)
public abstract class ItemCostMixin {
    @Unique
    private static final AggregateKey<EmeraldCostAggregate> PAID_WAYSTONES_EMERALD_COST = AggregateKey.of(
        Identifier.fromNamespaceAndPath("paid_waystones", "emerald_cost")
    );

    @Inject(method = "apply", at = @At("HEAD"), cancellable = true, remap = false)
    private void paidWaystones$acceptEmeraldBlocks(
        ShogiContext context,
        CallbackInfoReturnable<Either<ItemCostInformation, Object>> cir
    ) {
        var itemCost = (ItemCost) (Object) this;
        if (itemCost.item().size() != 1 || itemCost.item().stream().noneMatch(holder -> holder.value() == Items.EMERALD)) {
            return;
        }

        Player player = context.requirePlayer();
        int requestedUnits = itemCost.count().apply(context).mapLeft(Coercion.NON_NEGATIVE_INT).orThrow();
        int availableUnits = EmeraldInventory.availableUnits(player);
        var information = new ItemCostInformation(itemCost.item(), availableUnits, requestedUnits);
        if (player.getAbilities().instabuild) {
            cir.setReturnValue(Either.left(information));
            return;
        }

        int consumedUnits = context.statefulAggregate(
            PAID_WAYSTONES_EMERALD_COST,
            () -> new EmeraldCostAggregate(player),
            aggregate -> aggregate.simulateConsume(requestedUnits)
        );
        if (consumedUnits < requestedUnits) {
            cir.setReturnValue(Either.right(information));
            return;
        }

        context.consume(PAID_WAYSTONES_EMERALD_COST, EmeraldCostAggregate::consume);
        cir.setReturnValue(Either.left(information));
    }

    @Unique
    private static final class EmeraldCostAggregate {
        private final Player player;
        private final EmeraldCostAccumulator accumulator;

        private EmeraldCostAggregate(Player player) {
            this.player = player;
            accumulator = new EmeraldCostAccumulator(EmeraldInventory.availableUnits(player));
        }

        private int simulateConsume(int requestedUnits) {
            return accumulator.simulateConsume(requestedUnits);
        }

        private void consume() {
            EmeraldInventory.consume(player, accumulator.consumedUnits());
        }
    }
}
