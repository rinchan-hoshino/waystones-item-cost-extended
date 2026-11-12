package dev.rinchan.waystoneemeralds.mixin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import net.blay09.mods.waystones.api.requirement.WarpRequirement;
import net.blay09.mods.waystones.requirement.CombinedRequirement;
import net.blay09.mods.waystones.requirement.ConfiguredRequirementModifier;
import net.blay09.mods.waystones.requirement.ItemRequirement;
import net.blay09.mods.waystones.requirement.RequirementRegistry.ItemParameter;
import net.blay09.mods.waystones.requirement.RequirementRegistry.VariableScaledItemParameter;
import net.blay09.mods.waystones.requirement.WarpRequirementsContextImpl;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps the native modifier and parser, but gives each currency its own accumulator. */
// Injects at HEAD of apply and RETURN of resolve on WarpRequirementsContextImpl, so the cost is adjusted before and after vanilla resolves it.
@Mixin(WarpRequirementsContextImpl.class)
public abstract class ItemRequirementsContextMixin {
    @Unique private final Map<String, ItemRequirement> itemCostExtended$currencies = new LinkedHashMap<>();

    @Inject(method = "apply", at = @At("HEAD"), cancellable = true, remap = false)
    private <P> void itemCostExtended$separateCurrencies(ConfiguredRequirementModifier<ItemRequirement, P> configured,
                                                       CallbackInfo callback) {
        var requirement = configured.requirement();
        if (!requirement.modifier().getRequirementType().toString().equals("waystones:item")) return;
        var parameters = requirement.parameters();
        String currency;
        if (parameters instanceof ItemParameter item) currency = item.item().value().toString();
        else if (parameters instanceof VariableScaledItemParameter item) currency = item.item().value().toString();
        else return;
        var context = (WarpRequirementsContextImpl) (Object) this;
        callback.cancel();
        for (var condition : configured.conditions()) if (!context.matchesCondition(condition)) return;
        var existing = itemCostExtended$currencies.computeIfAbsent(currency, ignored -> new ItemRequirement(ItemStack.EMPTY, 0));
        itemCostExtended$currencies.put(currency, requirement.modifier().apply(existing, context, parameters));
    }

    @Inject(method = "resolve", at = @At("RETURN"), cancellable = true, remap = false)
    private void itemCostExtended$combineCurrencies(CallbackInfoReturnable<WarpRequirement> callback) {
        if (itemCostExtended$currencies.isEmpty()) return;
        var requirements = new ArrayList<WarpRequirement>();
        requirements.add(callback.getReturnValue());
        requirements.addAll(itemCostExtended$currencies.values());
        callback.setReturnValue(new CombinedRequirement(requirements));
    }
}
