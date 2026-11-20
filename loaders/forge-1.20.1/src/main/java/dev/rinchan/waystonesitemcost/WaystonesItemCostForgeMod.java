package dev.rinchan.waystonesitemcost;

import dev.rinchan.waystonesitemcost.client.ForgeClientInit;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;

@Mod(MixinGuard.MOD_ID)
public final class WaystonesItemCostForgeMod {
    public WaystonesItemCostForgeMod() {
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> ForgeClientInit::registerConfigScreen);
    }
}
