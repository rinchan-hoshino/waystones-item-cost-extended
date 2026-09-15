package dev.rinchan.rinlibprobe;

import java.util.ArrayList;
import java.util.List;
import net.blay09.mods.balm.api.Balm;
import net.blay09.mods.waystones.api.*;
import net.blay09.mods.waystones.block.ModBlocks;
import net.blay09.mods.waystones.block.entity.WaystoneBlockEntityBase;
import net.blay09.mods.waystones.config.WaystonesConfigData;
import net.blay09.mods.waystones.core.PlayerWaystoneManager;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

@Mod("itemcost_probe")
public final class ProbeEntrypoint {
    private final List<Waystone> destinations = new ArrayList<>();
    public ProbeEntrypoint() {
        NeoForge.EVENT_BUS.addListener(this::login);
        NeoForge.EVENT_BUS.addListener(this::commands);
    }
    private void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) player.server.execute(() -> setup(player, "combo"));
    }
    private void commands(RegisterCommandsEvent event) {
        var root = Commands.literal("capture");
        for (var phase : List.of("combo", "nuggets", "blocks")) {
            root.then(Commands.literal(phase).executes(context -> {
                setup(context.getSource().getPlayerOrException(), phase);
                return 1;
            }));
        }
        event.getDispatcher().register(root);
    }
    private void setup(ServerPlayer player, String phase) {
        var level = player.serverLevel();
        if (destinations.isEmpty()) {
            var names = List.of("Home", "Village", "Forest", "Quarry");
            for (int i = 0; i < names.size(); i++) {
                var pos = new BlockPos(i * 32, -60, 0);
                for (int x=-5; x<=5; x++) for (int z=-5; z<=5; z++)
                    level.setBlock(pos.offset(x,-1,z), Blocks.STONE_BRICKS.defaultBlockState(), 3);
                var state = ModBlocks.waystone.defaultBlockState();
                level.setBlock(pos, state, 3);
                level.setBlock(pos.above(), state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), 3);
                var base = (WaystoneBlockEntityBase) level.getBlockEntity(pos);
                base.initializeWaystone(level, player, WaystoneOrigin.PLAYER);
                ((MutableWaystone) base.getWaystone()).setName(Component.literal(names.get(i)));
                ((WaystoneBlockEntityBase) level.getBlockEntity(pos.above())).initializeFromBase(base);
                destinations.add(base.getWaystone());
            }
        }
        for (var waystone : destinations) PlayerWaystoneManager.activateWaystone(player, waystone);
        var rules = new ArrayList<String>();
        if (phase.equals("combo")) {
            for (int i=0; i<destinations.size(); i++) {
                var name = destinations.get(i).getName().getString();
                rules.add("[target_name_equals(" + name + ")] add_item_cost(minecraft:emerald, " + (i+1)*3 + ")");
                rules.add("[target_name_equals(" + name + ")] add_item_cost(minecraft:oak_log, " + (i+1)*2 + ")");
            }
        } else rules.add("add_item_cost(minecraft:iron_ingot, 3)");
        Balm.getConfig().updateConfig(WaystonesConfigData.class, config -> config.teleports.warpRequirements = rules);
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        if (phase.equals("combo")) {
            player.getInventory().setItem(0, new ItemStack(Items.EMERALD,64));
            player.getInventory().setItem(1, new ItemStack(Items.OAK_LOG,64));
        } else player.getInventory().setItem(0, new ItemStack(phase.equals("nuggets") ? Items.IRON_NUGGET : Items.IRON_BLOCK,64));
        player.teleportTo(level, 0.5,-60,3.5,180,0);
        level.setDayTime(6000);
        player.containerMenu.broadcastChanges();
        System.out.println("ITEM_COST_CAPTURE_READY " + phase);
    }
}
