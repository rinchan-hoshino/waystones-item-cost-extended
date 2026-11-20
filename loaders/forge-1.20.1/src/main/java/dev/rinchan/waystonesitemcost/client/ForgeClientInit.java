package dev.rinchan.waystonesitemcost.client;

import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;

public final class ForgeClientInit {
    private ForgeClientInit() {
    }

    public static void registerConfigScreen() {
        ModLoadingContext.get().registerExtensionPoint(
            ConfigScreenHandler.ConfigScreenFactory.class,
            () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new WaystonesItemCostConfigScreen(parent))
        );
    }
}
