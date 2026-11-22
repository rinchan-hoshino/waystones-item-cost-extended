package dev.rinchan.waystonesitemcost;

import dev.rinchan.waystonesitemcost.network.PhysicsPosSync;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

public final class WaystonesItemCostFabricMod implements ModInitializer {
    @Override
    public void onInitialize() {
        try {
            ServerTickEvents.END_SERVER_TICK.register(PhysicsPosSync::onServerTick);
        } catch (Throwable t) {
            MixinGuard.logOnce("WaystonesItemCostFabricMod.registerServerTick", t);
        }
    }
}
