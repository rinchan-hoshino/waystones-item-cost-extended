package dev.rinchan.waystonesitemcost;

import dev.rinchan.waystonesitemcost.client.WaystonesItemCostConfigScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(MixinGuard.MOD_ID)
public final class WaystonesItemCostNeoForgeMod {
    public WaystonesItemCostNeoForgeMod(ModContainer container) {
        if (isClient()) {
            ClientInit.registerConfigScreen(container);
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

    private static final class ClientInit {
        private static void registerConfigScreen(ModContainer container) {
            container.registerExtensionPoint(
                IConfigScreenFactory.class,
                (modContainer, parent) -> new WaystonesItemCostConfigScreen(parent)
            );
        }
    }
}
