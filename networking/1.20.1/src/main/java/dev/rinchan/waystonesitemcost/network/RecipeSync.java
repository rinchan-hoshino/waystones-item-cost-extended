package dev.rinchan.waystonesitemcost.network;

import dev.rinchan.waystonesitemcost.MixinGuard;
import dev.rinchan.waystonesitemcost.minecraft.RecipeCurrencies;
import dev.rinchan.waystonesitemcost.payment.CraftingDenominations;
import java.util.ArrayList;
import java.util.List;
import net.blay09.mods.balm.api.Balm;
import net.blay09.mods.balm.api.event.PlayerLoginEvent;
import net.blay09.mods.balm.api.event.server.ServerReloadedEvent;
import net.blay09.mods.balm.api.network.BalmNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

public record RecipeSync(List<CraftingDenominations.Recipe> recipes) {
    public static final ResourceLocation ID = new ResourceLocation(MixinGuard.MOD_ID, "recipes");

    public static void register(BalmNetworking networking) {
        networking.registerClientboundPacket(ID, RecipeSync.class, RecipeSync::encode, RecipeSync::decode, RecipeSync::handle);
        Balm.getEvents().onEvent(PlayerLoginEvent.class, event ->
            Balm.getNetworking().sendTo(event.getPlayer(), new RecipeSync(RecipeCurrencies.recipes(event.getPlayer()))));
        Balm.getEvents().onEvent(ServerReloadedEvent.class, event -> {
            var server = event.getServer();
            if (server != null) server.execute(() -> {
                for (var player : server.getPlayerList().getPlayers())
                    Balm.getNetworking().sendTo(player, new RecipeSync(RecipeCurrencies.recipes(player)));
            });
        });
    }

    public static void encode(RecipeSync packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.recipes().size());
        for (var recipe : packet.recipes()) {
            buffer.writeUtf(recipe.ingredient());
            buffer.writeVarInt(recipe.inputCount());
            buffer.writeUtf(recipe.result());
            buffer.writeVarInt(recipe.outputCount());
        }
    }

    public static RecipeSync decode(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > 16384) throw new IllegalArgumentException("Invalid recipe count");
        var recipes = new ArrayList<CraftingDenominations.Recipe>();
        for (int index = 0; index < size; index++) {
            recipes.add(new CraftingDenominations.Recipe(buffer.readUtf(), buffer.readVarInt(), buffer.readUtf(), buffer.readVarInt()));
        }
        return new RecipeSync(List.copyOf(recipes));
    }

    public static void handle(Player player, RecipeSync packet) {
        RecipeCurrencies.receive(player, packet.recipes());
    }
}
