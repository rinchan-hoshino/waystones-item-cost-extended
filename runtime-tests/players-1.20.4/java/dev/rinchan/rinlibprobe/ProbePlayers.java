package dev.rinchan.rinlibprobe;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import java.util.UUID;
public final class ProbePlayers {
    public static ServerPlayer create(MinecraftServer server, String name, long id) {
        var profile = new GameProfile(new UUID(0L, id), name);
        var player = new ServerPlayer(server, server.overworld(), profile, ClientInformation.createDefault());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new io.netty.channel.embedded.EmbeddedChannel(connection);
        channel.attr(Connection.ATTRIBUTE_SERVERBOUND_PROTOCOL).set(net.minecraft.network.ConnectionProtocol.PLAY.codec(PacketFlow.SERVERBOUND));
        channel.attr(Connection.ATTRIBUTE_CLIENTBOUND_PROTOCOL).set(net.minecraft.network.ConnectionProtocol.PLAY.codec(PacketFlow.CLIENTBOUND));
        player.connection = new ServerGamePacketListenerImpl(server, connection, player,
                CommonListenerCookie.createInitial(profile));
        player.setPos(0.5, 81, 1.5);
        return player;
    }
}
