package dev.rinchan.waystonesitemcost.mixin;

import com.mojang.datafixers.util.Either;
import dev.rinchan.waystonesitemcost.MixinGuard;
import dev.rinchan.waystonesitemcost.minecraft.PaymentTooltipHelper;
import dev.rinchan.waystonesitemcost.payment.ItemPayment;
import dev.rinchan.waystonesitemcost.payment.PaymentStatus;
import java.util.function.Predicate;
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

@Mixin(ItemCost.class)
public abstract class ItemCostMixin {
    @Unique
    private static final AggregateKey<ItemPayment> ITEM_COST_EXTENDED_PAYMENT = AggregateKey.of(
        Identifier.fromNamespaceAndPath(MixinGuard.MOD_ID, "item_payment"));

    @Inject(method = "apply", at = @At("HEAD"), cancellable = true, remap = false)
    private void itemCostExtended$pay(ShogiContext context, CallbackInfoReturnable<Either<ItemCostInformation, Object>> callback) {
        try {
            if (!(context.getVariable("target_waystone").orElse(null) instanceof net.blay09.mods.waystones.api.Waystone)) {
                return;
            }
            var cost = (ItemCost) (Object) this;
            int requested = cost.count().apply(context).mapLeft(Coercion.NON_NEGATIVE_INT).orThrow();
            var player = context.requirePlayer();
            var information = context.statefulAggregate(ITEM_COST_EXTENDED_PAYMENT,
                () -> new ItemPayment(player), payment -> {
                    int available;
                    boolean paid;
                    if (cost.item().size() == 1) {
                        var currency = new ItemStack(cost.item().stream().findFirst().orElseThrow().value());
                        available = payment.available(currency);
                        paid = payment.charge(currency, requested);
                    } else {
                        Predicate<ItemStack> matcher = stack -> stack.is(cost.item());
                        available = payment.available(matcher);
                        paid = payment.charge(matcher, requested);
                    }
                    if (!paid && payment.status() == PaymentStatus.INVENTORY_FULL_FOR_CHANGE) {
                        PaymentTooltipHelper.notifyInventoryFull(player, payment.slotsNeededForChange());
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
        } catch (Throwable t) {
            MixinGuard.logOnce("ItemCost.apply", t);
        }
    }
}
