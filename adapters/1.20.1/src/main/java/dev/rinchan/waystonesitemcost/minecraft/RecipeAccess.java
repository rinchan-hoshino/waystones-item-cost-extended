package dev.rinchan.waystonesitemcost.minecraft;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.block.ShulkerBoxBlock;

public final class RecipeAccess {
    private RecipeAccess() {}

    public static boolean isLegacy1201() {
        return true;
    }

    public static String configExampleSnippet() {
        return "[legacy_1_20_1]\nenableCustomItemCosts = true\nwaiveVanillaXpCost = true\nitemCosts = [\"minecraft:emerald,1,0,0\"]";
    }

    public static Collection<Recipe<?>> all(Player player) {
        return player.level().getRecipeManager().getRecipes();
    }

    public static RecipeCurrencies.Recipe read(Recipe<?> recipe, Player player) {
        if (recipe.getClass() != ShapedRecipe.class && recipe.getClass() != ShapelessRecipe.class) return null;
        return new RecipeCurrencies.Recipe(
            recipe.getResultItem(player.level().registryAccess()),
            recipe.getIngredients().stream().map(ingredient -> ingredient.getItems()).toList()
        );
    }

    public static boolean same(ItemStack left, ItemStack right) {
        return ItemStack.isSameItemSameTags(left, right);
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
        if (container.getItem() instanceof BundleItem) {
            CompoundTag tag = container.getTag();
            if (tag == null || !tag.contains("Items", Tag.TAG_LIST)) return null;
            ListTag list = tag.getList("Items", Tag.TAG_COMPOUND);
            var out = new ArrayList<ItemStack>(list.size());
            for (int i = 0; i < list.size(); i++) {
                out.add(ItemStack.of(list.getCompound(i)));
            }
            return out;
        }
        if (container.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock) {
            CompoundTag beTag = BlockItem.getBlockEntityData(container);
            if (beTag == null || !beTag.contains("Items", Tag.TAG_LIST)) return null;
            NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
            ContainerHelper.loadAllItems(beTag, items);
            return new ArrayList<>(items);
        }
        return null;
    }

    public static void writeNestedItems(ItemStack container, List<ItemStack> items) {
        if (container.isEmpty() || items == null) return;
        if (container.getItem() instanceof BundleItem) {
            CompoundTag tag = container.getOrCreateTag();
            ListTag list = new ListTag();
            for (var stack : items) {
                if (!stack.isEmpty()) {
                    CompoundTag itemTag = new CompoundTag();
                    stack.save(itemTag);
                    list.add(itemTag);
                }
            }
            if (list.isEmpty()) {
                tag.remove("Items");
            } else {
                tag.put("Items", list);
            }
            return;
        }
        if (container.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock) {
            CompoundTag beTag = BlockItem.getBlockEntityData(container);
            if (beTag == null) return;
            NonNullList<ItemStack> list = NonNullList.withSize(27, ItemStack.EMPTY);
            for (int i = 0; i < Math.min(27, items.size()); i++) {
                list.set(i, items.get(i));
            }
            ContainerHelper.saveAllItems(beTag, list, true);
        }
    }
}
