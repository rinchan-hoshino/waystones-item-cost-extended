package dev.rinchan.rinlibprobe;
import net.minecraft.server.MinecraftServer;
import java.nio.file.Files;
import java.nio.file.Path;
public final class RuntimeProbe {
    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
        System.out.println("ITEM_COST_ASSERT_PASS " + message);
    }
    public void run(MinecraftServer server) {
        server.execute(() -> {
            try {
                ProfileChecks.run(server);
                Files.writeString(Path.of("item-cost-runtime.pass"), "pass\n");
                System.out.println("ITEM_COST_RUNTIME_PASS");
            } catch (Throwable error) {
                error.printStackTrace();
                System.out.println("ITEM_COST_RUNTIME_FAIL");
            } finally { server.halt(false); }
        });
    }
}
