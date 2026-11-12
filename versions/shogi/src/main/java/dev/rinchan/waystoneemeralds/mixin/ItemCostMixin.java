package dev.rinchan.waystoneemeralds.mixin;

import com.mojang.datafixers.util.Either;
import dev.rinchan.waystoneemeralds.payment.ItemPayment;
import net.blay09.mods.shogi.coercion.Coercion;
import net.blay09.mods.shogi.common.effect.cost.ItemCost;
import net.blay09.mods.shogi.common.effect.cost.ItemCostInformation;
import net.blay09.mods.shogi.context.ShogiContext;
import net.blay09.mods.shogi.context.executor.aggregate.AggregateKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Injects at HEAD of ItemCost.apply, so the item cost is replaced before vanilla applies it.
@Mixin(ItemCost.class)
public abstract class ItemCostMixin {
    @Unique private static final AggregateKey<ItemPayment> ITEM_COST_EXTENDED_PAYMENT = AggregateKey.of(
        Identifier.fromNamespaceAndPath("waystone_emeralds", "item_payment"));

    @Inject(method = "apply", at = @At("HEAD"), cancellable = true, remap = false)
    private void itemCostExtended$pay(ShogiContext context, CallbackInfoReturnable<Either<ItemCostInformation, Object>> callback) {
        if (!(context.getVariable("target_waystone").orElse(null) instanceof net.blay09.mods.waystones.api.Waystone)) return;
        var cost = (ItemCost) (Object) this;
        int requested = cost.count().apply(context).mapLeft(Coercion.NON_NEGATIVE_INT).orThrow();
        var information = context.statefulAggregate(ITEM_COST_EXTENDED_PAYMENT,
            () -> new ItemPayment(context.requirePlayer()), payment -> {
                int available;
                boolean paid;
                if (cost.item().size() == 1) {
                    var currency = new ItemStack(cost.item().stream().findFirst().orElseThrow().value());
                    available = payment.available(currency);
                    paid = payment.charge(currency, requested);
                } else {
                    java.util.function.Predicate<ItemStack> matcher = stack -> stack.is(cost.item());
                    available = payment.available(matcher);
                    paid = payment.charge(matcher, requested);
                }
                var result = new ItemCostInformation(cost.item(), available, requested);
                return paid ? Either.<ItemCostInformation, Object>left(result) : Either.<ItemCostInformation, Object>right(result);
            });
        if (information.left().isPresent()) {
            context.consume(ITEM_COST_EXTENDED_PAYMENT, payment -> {
                if (!payment.commit()) throw new IllegalStateException("Inventory changed during item payment");
            });
        }
        callback.setReturnValue(information);
    }
}
