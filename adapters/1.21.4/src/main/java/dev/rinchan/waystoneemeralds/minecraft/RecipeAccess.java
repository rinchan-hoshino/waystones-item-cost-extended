package dev.rinchan.waystoneemeralds.minecraft;

import java.util.Collection;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

public final class RecipeAccess {
    private RecipeAccess() {}
    public static Collection<RecipeHolder<?>> all(Player player) { return player.level().getServer().getRecipeManager().getRecipes(); }
    public static RecipeCurrencies.Recipe read(RecipeHolder<?> holder, Player player) {
        var recipe = holder.value();
        if (recipe.getClass() != ShapedRecipe.class && recipe.getClass() != ShapelessRecipe.class) return null;
        return new RecipeCurrencies.Recipe(recipe instanceof ShapedRecipe shaped ? shaped.assemble(net.minecraft.world.item.crafting.CraftingInput.EMPTY, player.level().registryAccess()) : ((ShapelessRecipe) recipe).assemble(net.minecraft.world.item.crafting.CraftingInput.EMPTY, player.level().registryAccess()),
            recipe.placementInfo().ingredients().stream().map(ingredient -> ingredient.items().map(item -> new ItemStack(item.value())).toArray(ItemStack[]::new)).toList());
    }
    public static boolean same(ItemStack left, ItemStack right) { return ItemStack.isSameItemSameComponents(left, right); }
    public static Item item(String id) { return BuiltInRegistries.ITEM.getValue(net.minecraft.resources.ResourceLocation.parse(id)); }
}
