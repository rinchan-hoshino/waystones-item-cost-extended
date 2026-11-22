package dev.rinchan.waystonesitemcost;

import dev.rinchan.waystonesitemcost.client.ForgeClientInit;
import dev.rinchan.waystonesitemcost.network.PhysicsPosSync;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;

@Mod(MixinGuard.MOD_ID)
public final class WaystonesItemCostForgeMod {
    public WaystonesItemCostForgeMod() {
        try {
            MinecraftForge.EVENT_BUS.addListener((TickEvent.ServerTickEvent event) -> {
                if (event.phase == TickEvent.Phase.END) {
                    PhysicsPosSync.onServerTick(event.getServer());
                }
            });
        } catch (Throwable t) {
            MixinGuard.logOnce("WaystonesItemCostForgeMod.registerServerTick", t);
        }
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> ForgeClientInit::registerConfigScreen);
    }
}
