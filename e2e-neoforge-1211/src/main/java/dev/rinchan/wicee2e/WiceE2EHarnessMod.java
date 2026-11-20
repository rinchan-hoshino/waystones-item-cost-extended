package dev.rinchan.wicee2e;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

@Mod(WiceE2EHarnessMod.MOD_ID)
public final class WiceE2EHarnessMod {
    public static final String MOD_ID = "wice_e2e_harness";

    public WiceE2EHarnessMod(IEventBus modBus) {
        modBus.addListener(this::registerGameTests);
    }

    private void registerGameTests(RegisterGameTestsEvent event) {
        event.register(WicePackagedJarGameTests.class);
    }
}
