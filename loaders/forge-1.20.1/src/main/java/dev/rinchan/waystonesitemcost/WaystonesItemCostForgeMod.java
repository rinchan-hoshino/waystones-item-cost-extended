package dev.rinchan.waystonesitemcost;

import dev.rinchan.waystonesitemcost.client.WaystonesItemCostConfigScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;

@Mod(MixinGuard.MOD_ID)
public final class WaystonesItemCostForgeMod {
    public WaystonesItemCostForgeMod() {
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> ClientInit::registerConfigScreen);
    }

    private static final class ClientInit {
        private static void registerConfigScreen() {
            ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new WaystonesItemCostConfigScreen(parent))
            );
        }
    }
}
