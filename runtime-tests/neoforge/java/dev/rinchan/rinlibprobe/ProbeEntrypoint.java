package dev.rinchan.rinlibprobe;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
@Mod("itemcost_probe")
public final class ProbeEntrypoint {
    public ProbeEntrypoint() { NeoForge.EVENT_BUS.addListener(this::started); }
    private void started(ServerStartedEvent event) { new RuntimeProbe().run(event.getServer()); }
}
