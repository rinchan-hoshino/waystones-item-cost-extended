package dev.rinchan.waystonesitemcost.minecraft;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

public final class RecipeAccess {
    private RecipeAccess() {}

    public static boolean isLegacy1201() {
        return false;
    }

    public static String configExampleSnippet() {
        return "warpRequirements = [\n    \"add_item_cost(minecraft:emerald, 1)\"\n]";
    }

    public static Collection<RecipeHolder<?>> all(Player player) {
        return player.level().getRecipeManager().getRecipes();
    }

    public static RecipeCurrencies.Recipe read(RecipeHolder<?> holder, Player player) {
        var recipe = holder.value();
        if (recipe.getClass() != ShapedRecipe.class && recipe.getClass() != ShapelessRecipe.class) return null;
        return new RecipeCurrencies.Recipe(
            recipe.getResultItem(player.level().registryAccess()),
            recipe.getIngredients().stream().map(ingredient -> ingredient.getItems()).toList()
        );
    }

    public static boolean same(ItemStack left, ItemStack right) {
        return ItemStack.isSameItemSameComponents(left, right);
    }

    public static void sendOverlayMessage(Player player, Component message) {
        player.displayClientMessage(message, true);
    }

    public static Item item(String id) {
        var key = ResourceLocation.tryParse(id);
        return key == null ? net.minecraft.world.item.Items.AIR : BuiltInRegistries.ITEM.get(key);
    }

    public static List<ItemStack> readNestedItems(ItemStack container) {
        if (container.isEmpty()) return null;
        BundleContents bundle = container.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null && !bundle.isEmpty()) {
            var out = new ArrayList<ItemStack>();
            for (var stack : bundle.itemsCopy()) {
                out.add(stack);
            }
            return out;
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
            var nonEmpty = new ArrayList<ItemStack>();
            for (var stack : items) {
                if (!stack.isEmpty()) nonEmpty.add(stack);
            }
            container.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(nonEmpty));
            return;
        }
        if (container.has(DataComponents.CONTAINER)) {
            container.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
        }
    }
}
