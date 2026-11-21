package dev.rinchan.waystonesitemcost.minecraft;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

public final class RecipeAccess {
    private RecipeAccess() {}

    public static boolean isLegacy1201() {
        return false;
    }

    public static String configExampleSnippet() {
        return "warpRequirements = [\n    \"item_cost('minecraft:emerald', 1)\"\n]";
    }

    public static Collection<RecipeHolder<?>> all(Player player) {
        var server = player.level().getServer();
        if (server == null) return List.of();
        return server.getRecipeManager().getRecipes();
    }

    public static RecipeCurrencies.Recipe read(RecipeHolder<?> holder, Player player) {
        var recipe = holder.value();
        if (recipe.getClass() != ShapedRecipe.class && recipe.getClass() != ShapelessRecipe.class) return null;
        return new RecipeCurrencies.Recipe(
            recipe instanceof ShapedRecipe shaped ? shaped.assemble(CraftingInput.EMPTY) : ((ShapelessRecipe) recipe).assemble(CraftingInput.EMPTY),
            recipe.placementInfo().ingredients().stream().map(ingredient -> ingredient.items().map(item -> new ItemStack(item.value())).toArray(ItemStack[]::new)).toList()
        );
    }

    public static boolean same(ItemStack left, ItemStack right) {
        return ItemStack.isSameItemSameComponents(left, right);
    }

    public static void sendOverlayMessage(Player player, Component message) {
        player.sendOverlayMessage(message);
    }

    public static Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    public static List<ItemStack> readNestedItems(ItemStack container) {
        if (container.isEmpty()) return null;
        BundleContents bundle = container.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null && !bundle.isEmpty()) {
            return new ArrayList<>(bundle.items().stream().map(ItemStackTemplate::create).toList());
        }
        ItemContainerContents box = container.get(DataComponents.CONTAINER);
        if (box != null && box != ItemContainerContents.EMPTY) {
            NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
            box.copyInto(items);
            return new ArrayList<>(items);
        }
        return null;
    }

    public static void writeNestedItems(ItemStack container, List<ItemStack> items) {
        if (container.isEmpty() || items == null) return;
        if (container.has(DataComponents.BUNDLE_CONTENTS)) {
            var nonEmpty = new ArrayList<ItemStackTemplate>();
            for (var stack : items) {
                if (!stack.isEmpty()) nonEmpty.add(ItemStackTemplate.fromNonEmptyStack(stack));
            }
            container.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(nonEmpty));
            return;
        }
        if (container.has(DataComponents.CONTAINER)) {
            container.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
        }
    }
}
