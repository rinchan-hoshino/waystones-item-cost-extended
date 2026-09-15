package dev.rinchan.rinlibprobe;
import dev.rinchan.waystoneemeralds.payment.ItemPayment;
import net.blay09.mods.waystones.api.requirement.WarpRequirement;
import net.blay09.mods.waystones.requirement.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import static dev.rinchan.rinlibprobe.RuntimeProbe.require;
public final class ProfileChecks {
    private static WarpRequirement rules(String... lines) {
        var context = new WarpRequirementsContextImpl(null);
        for (var line : lines) {
            var parsed = RequirementModifierParser.parse(line);
            require(!parsed.isEmpty(), "native parser accepts " + line);
            for (var modifier : parsed.stream().toList()) context.apply(modifier);
        }
        return context.resolve();
    }
    private static int count(Player player, Item item) {
        int count = 0;
        for (int slot=0; slot<player.getInventory().getContainerSize(); slot++) {
            var stack=player.getInventory().getItem(slot);
            if (stack.is(item)) count+=stack.getCount();
        }
        return count;
    }
    public static void run(MinecraftServer server) {
        var player=ProbePlayers.create(server,"ItemCosts",101);
        player.getInventory().setItem(0,new ItemStack(Items.EMERALD,64));
        player.getInventory().setItem(1,new ItemStack(Items.OAK_LOG,64));
        var mixed=rules("add_item_cost(minecraft:emerald, 3)","add_item_cost(minecraft:oak_log, 2)");
        require(mixed.canAfford(player),"combined native currencies are affordable");
        Actions.consume(mixed, player);
        require(count(player,Items.EMERALD)==61 && count(player,Items.OAK_LOG)==62,"both native currency costs are charged");
        Actions.rollback(mixed, player);
        Actions.rollback(mixed, player);
        require(count(player,Items.EMERALD)==64 && count(player,Items.OAK_LOG)==64,"rollback restores exact inventory once");
        player.getInventory().clearContent();
        player.getInventory().setItem(0,new ItemStack(Items.IRON_NUGGET,64));
        var iron=rules("add_item_cost(minecraft:iron_ingot, 3)");
        require(iron.canAfford(player),"iron nuggets cover ingot price");
        Actions.consume(iron, player);
        require(count(player,Items.IRON_NUGGET)==37,"three ingots cost 27 nuggets");
        player.getInventory().clearContent();
        player.getInventory().setItem(0,new ItemStack(Items.IRON_BLOCK,64));
        Actions.consume(iron, player);
        require(count(player,Items.IRON_BLOCK)==63 && count(player,Items.IRON_INGOT)==6,"one block pays three ingots with exact change");
        player.getInventory().clearContent();
        player.getInventory().setItem(0,new ItemStack(Items.IRON_INGOT,1));
        var shared=rules("add_item_cost(minecraft:iron_ingot, 1)","add_item_cost(minecraft:iron_nugget, 1)");
        require(!shared.canAfford(player),"combined denominations cannot spend the same ingot twice");
        require(count(player,Items.IRON_INGOT)==1,"rejected preview leaves inventory unchanged");
        player.getAbilities().instabuild=true;
        require(shared.canAfford(player),"creative player can afford mixed costs");
        Actions.consume(shared, player);
        require(count(player,Items.IRON_INGOT)==1,"creative payment preserves inventory");
        player.getAbilities().instabuild=false;
        player.getInventory().clearContent();
        player.getInventory().setItem(0,new ItemStack(Items.EMERALD,64));
        player.getInventory().setItem(1,new ItemStack(Items.OAK_LOG,64));
        var level=server.overworld();
        var position=new net.minecraft.core.BlockPos(32,81,0);
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)
            level.setBlock(position.offset(dx,-1,dz),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),3);
        var state=net.blay09.mods.waystones.block.ModBlocks.waystone.defaultBlockState();
        level.setBlock(position,state,3);
        level.setBlock(position.above(),state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF,
            net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER),3);
        var block=(net.blay09.mods.waystones.block.entity.WaystoneBlockEntityBase)level.getBlockEntity(position);
        block.initializeWaystone(level,player,net.blay09.mods.waystones.api.WaystoneOrigin.PLAYER);
        var teleport=new net.blay09.mods.waystones.core.WaystoneTeleportContextImpl(player,block.getWaystone());
        teleport.setRequirements(rules("add_item_cost(minecraft:emerald, 3)","add_item_cost(minecraft:oak_log, 2)"));
        teleport.setPlaysEffect(false).setPlaysSound(false);
        net.blay09.mods.waystones.core.WaystoneTeleportManager.tryTeleport(teleport);
        require(player.getX()>30 && player.getX()<35,"player moves to the physical destination waystone");
        require(count(player,Items.EMERALD)==61 && count(player,Items.OAK_LOG)==62,"completed teleport charges both currencies exactly");
    }
}
