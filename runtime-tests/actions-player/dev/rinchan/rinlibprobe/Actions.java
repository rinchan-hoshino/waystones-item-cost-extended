package dev.rinchan.rinlibprobe;
import net.blay09.mods.waystones.api.requirement.WarpRequirement;
import net.minecraft.world.entity.player.Player;
final class Actions {
    static void consume(WarpRequirement requirement, Player player) { requirement.consume(player); }
    static void rollback(WarpRequirement requirement, Player player) { requirement.rollback(player); }
}
