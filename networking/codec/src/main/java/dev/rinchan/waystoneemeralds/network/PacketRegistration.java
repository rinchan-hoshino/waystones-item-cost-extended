package dev.rinchan.waystoneemeralds.network;

import net.blay09.mods.balm.api.network.BalmNetworking;
import net.minecraft.network.codec.StreamCodec;

final class PacketRegistration {
    private PacketRegistration() {}
    static void register(BalmNetworking networking) {
        networking.registerClientboundPacket(RecipeSync.TYPE, RecipeSync.class, StreamCodec.of(RecipeSync::encode, RecipeSync::decode), RecipeSync::handle);
    }
}
