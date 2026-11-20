package dev.rinchan.waystonesitemcost.client;

import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

public final class NeoForgeClientInit {
    private NeoForgeClientInit() {
    }

    public static void registerConfigScreen(ModContainer container) {
        container.registerExtensionPoint(
            IConfigScreenFactory.class,
            (modContainer, parent) -> new WaystonesItemCostConfigScreen(parent)
        );
    }
}
