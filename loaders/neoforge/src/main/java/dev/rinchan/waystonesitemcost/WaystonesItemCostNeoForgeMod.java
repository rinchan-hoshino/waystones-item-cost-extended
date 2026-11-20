package dev.rinchan.waystonesitemcost;

import dev.rinchan.waystonesitemcost.client.NeoForgeClientInit;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(MixinGuard.MOD_ID)
public final class WaystonesItemCostNeoForgeMod {
    public WaystonesItemCostNeoForgeMod(ModContainer container) {
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
