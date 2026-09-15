package dev.rinchan.waystoneemeralds.network;

import dev.rinchan.waystoneemeralds.minecraft.RecipeCurrencies;
import dev.rinchan.waystoneemeralds.payment.CraftingDenominations;
import java.util.ArrayList;
import java.util.List;
import net.blay09.mods.balm.Balm;
import net.blay09.mods.balm.platform.event.callback.ServerPlayerCallback;
import net.blay09.mods.balm.platform.event.callback.ServerLifecycleCallback;
import net.blay09.mods.balm.network.BalmNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;

public record RecipeSync(List<CraftingDenominations.Recipe> recipes) implements CustomPacketPayload {
    public static final Type<RecipeSync> TYPE = new Type<>(Identifier.tryParse("waystone_emeralds:recipes"));
    public static void register(BalmNetworking networking) {
        networking.registerClientboundPacket(TYPE, RecipeSync.class, net.minecraft.network.codec.StreamCodec.of(RecipeSync::encode, RecipeSync::decode), RecipeSync::handle);
        ServerPlayerCallback.Join.EVENT.register(player ->
            Balm.networking().sendTo(player, new RecipeSync(RecipeCurrencies.recipes(player))));
        ServerLifecycleCallback.Reloaded.EVENT.register(server -> {
            for (var player : server.getPlayerList().getPlayers())
                Balm.networking().sendTo(player, new RecipeSync(RecipeCurrencies.recipes(player)));
        });
    }

    public static void encode(RegistryFriendlyByteBuf buffer, RecipeSync packet) {
        buffer.writeVarInt(packet.recipes().size());
        for (var recipe : packet.recipes()) {
            buffer.writeUtf(recipe.ingredient());
            buffer.writeVarInt(recipe.inputCount());
            buffer.writeUtf(recipe.result());
            buffer.writeVarInt(recipe.outputCount());
        }
    }
    public static RecipeSync decode(RegistryFriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > 16384) throw new IllegalArgumentException("Invalid recipe count");
        var recipes = new ArrayList<CraftingDenominations.Recipe>();
        for (int index=0; index<size; index++) {
            recipes.add(new CraftingDenominations.Recipe(buffer.readUtf(), buffer.readVarInt(), buffer.readUtf(), buffer.readVarInt()));
        }
        return new RecipeSync(List.copyOf(recipes));
    }
    public static void handle(Player player, RecipeSync packet) { RecipeCurrencies.receive(player, packet.recipes()); }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
