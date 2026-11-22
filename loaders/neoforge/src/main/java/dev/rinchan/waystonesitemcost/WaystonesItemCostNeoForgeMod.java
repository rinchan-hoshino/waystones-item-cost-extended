package dev.rinchan.waystonesitemcost;

import dev.rinchan.waystonesitemcost.client.NeoForgeClientInit;
import dev.rinchan.waystonesitemcost.network.PhysicsPosSync;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod(MixinGuard.MOD_ID)
public final class WaystonesItemCostNeoForgeMod {
    public WaystonesItemCostNeoForgeMod(ModContainer container) {
        try {
            NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> PhysicsPosSync.onServerTick(event.getServer()));
        } catch (Throwable t) {
            MixinGuard.logOnce("WaystonesItemCostNeoForgeMod.registerServerTick", t);
        }
        if (isClient()) {
            NeoForgeClientInit.registerConfigScreen(container);
        }
    }

    private static boolean isClient() {
        try {
            return FMLEnvironment.class.getMethod("getDist").invoke(null) == Dist.CLIENT;
        } catch (ReflectiveOperationException ignored) {
            try {
                return FMLEnvironment.class.getField("dist").get(null) == Dist.CLIENT;
            } catch (ReflectiveOperationException e) {
                return false;
            }
        }
    }
}
