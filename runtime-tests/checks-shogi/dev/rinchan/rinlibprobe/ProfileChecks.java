package dev.rinchan.rinlibprobe;
import com.google.gson.JsonPrimitive;
import net.blay09.mods.shogi.common.effect.cost.ItemCost;
import net.blay09.mods.shogi.context.internal.ShogiContextImpl;
import net.blay09.mods.shogi.context.executor.internal.DeferredEffectExecutorImpl;
import net.blay09.mods.shogi.effect.ConstantEffect;
import net.minecraft.core.HolderSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import static dev.rinchan.rinlibprobe.RuntimeProbe.require;
public final class ProfileChecks {
    private static ItemCost cost(Item item, int count) {
        return new ItemCost(HolderSet.direct(item.builtInRegistryHolder()),new ConstantEffect(new JsonPrimitive(count)));
    }
    private static int count(Player player, Item item) {
        int count=0;
        for(int slot=0;slot<player.getInventory().getContainerSize();slot++) {
            var stack=player.getInventory().getItem(slot);
            if(stack.is(item))count+=stack.getCount();
        }
        return count;
    }
    public static void run(MinecraftServer server) {
        var player=ProbePlayers.create(server,"ItemCosts",101);
        var target=new net.blay09.mods.waystones.core.WaystoneImpl(
            net.minecraft.resources.Identifier.parse("waystones:waystone"),new java.util.UUID(0,102),server.overworld().dimension(),
            new net.minecraft.core.BlockPos(32,81,0),net.blay09.mods.waystones.api.WaystoneOrigin.PLAYER,null);
        player.getInventory().setItem(0,new ItemStack(Items.EMERALD,64));
        player.getInventory().setItem(1,new ItemStack(Items.OAK_LOG,64));
        var executor=new DeferredEffectExecutorImpl();
        var context=new ShogiContextImpl(executor).withEntity(player).withVariable("target_waystone",target);
        require(cost(Items.EMERALD,3).apply(context).left().isPresent(),"Shogi accepts emerald cost");
        require(cost(Items.OAK_LOG,2).apply(context).left().isPresent(),"Shogi accepts log cost");
        require(count(player,Items.EMERALD)==64 && count(player,Items.OAK_LOG)==64,"Shogi preview does not mutate inventory");
        executor.execute();
        require(count(player,Items.EMERALD)==61 && count(player,Items.OAK_LOG)==62,"Shogi commits both currencies together");
        player.getInventory().clearContent();
        player.getInventory().setItem(0,new ItemStack(Items.IRON_NUGGET,64));
        executor=new DeferredEffectExecutorImpl();context=new ShogiContextImpl(executor).withEntity(player).withVariable("target_waystone",target);
        require(cost(Items.IRON_INGOT,3).apply(context).left().isPresent(),"Shogi accepts nuggets for ingots");
        executor.execute();require(count(player,Items.IRON_NUGGET)==37,"Shogi charges exactly 27 nuggets");
        player.getInventory().clearContent();player.getInventory().setItem(0,new ItemStack(Items.IRON_BLOCK,64));
        executor=new DeferredEffectExecutorImpl();context=new ShogiContextImpl(executor).withEntity(player).withVariable("target_waystone",target);
        require(cost(Items.IRON_INGOT,3).apply(context).left().isPresent(),"Shogi accepts blocks for ingots");
        executor.execute();require(count(player,Items.IRON_BLOCK)==63 && count(player,Items.IRON_INGOT)==6,"Shogi returns six ingots in change");
        player.getInventory().clearContent();player.getInventory().setItem(0,new ItemStack(Items.IRON_INGOT,1));
        executor=new DeferredEffectExecutorImpl();context=new ShogiContextImpl(executor).withEntity(player).withVariable("target_waystone",target);
        require(cost(Items.IRON_INGOT,1).apply(context).left().isPresent(),"first denomination reserves value");
        require(cost(Items.IRON_NUGGET,1).apply(context).right().isPresent(),"second denomination cannot reuse the same ingot");
        require(count(player,Items.IRON_INGOT)==1,"failed combination has not charged inventory");
        player.getInventory().clearContent();
        player.getInventory().setItem(0,new ItemStack(Items.EMERALD,64));
        player.getInventory().setItem(1,new ItemStack(Items.OAK_LOG,64));
        var level=server.overworld();
        var position=new net.minecraft.core.BlockPos(32,81,0);
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)
            level.setBlock(position.offset(dx,-1,dz),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),3);
        var state=net.blay09.mods.waystones.api.WaystoneTypes.ANDESITE.block().defaultBlockState();
        level.setBlock(position,state,3);
        level.setBlock(position.above(),state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF,
            net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER),3);
        var block=(net.blay09.mods.waystones.block.entity.WaystoneBlockEntityBase)level.getBlockEntity(position);
        block.initializeWaystone(level,player,net.blay09.mods.waystones.api.WaystoneOrigin.PLAYER);
        var teleport=new net.blay09.mods.waystones.core.WaystoneTeleportContextImpl(player,block.getWaystone());
        var infos=new java.util.ArrayList<Object>();
        for(var line:java.util.List.of("item_cost('minecraft:emerald', 3)","item_cost('minecraft:oak_log', 2)")) {
            var parsed=net.blay09.mods.shogi.common.parse.ShogiRuleParser.parse(
                net.blay09.mods.waystones.config.WaystonesRules.scope,
                net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE,level.registryAccess()),line).getOrThrow();
            var result=parsed.apply(teleport);
            require(result.left().isPresent(),"native Shogi parser accepts " + line);
            infos.add(result.left().orElseThrow());
        }
        teleport.setRequirements(com.mojang.datafixers.util.Either.left(infos));
        teleport.setPlaysEffect(false).setPlaysSound(false);
        net.blay09.mods.waystones.core.WaystoneTeleportManager.tryTeleport(teleport);
        require(player.getX()>30 && player.getX()<35,"Shogi teleport moves to physical destination");
        require(count(player,Items.EMERALD)==61 && count(player,Items.OAK_LOG)==62,"Shogi teleport charges both configured currencies");

    }
}
