package dev.rinchan.rinlibprobe;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
public final class ProbeEntrypoint implements ModInitializer {
    public void onInitialize() { ServerLifecycleEvents.SERVER_STARTED.register(server -> new RuntimeProbe().run(server)); }
}
