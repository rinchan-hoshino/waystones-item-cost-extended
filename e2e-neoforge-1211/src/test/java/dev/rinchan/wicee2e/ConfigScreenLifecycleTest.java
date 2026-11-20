package dev.rinchan.wicee2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import org.junit.jupiter.api.Test;

/**
 * Headless client-screen lifecycle black-box verification against the real packaged 1.21.1-neoforge JAR.
 */
final class ConfigScreenLifecycleTest {
    @Test
    void configScreenInitializesAndClosesWithoutException() throws Exception {
        Class<?> screenClass = Class.forName("dev.rinchan.waystonesitemcost.client.WaystonesItemCostConfigScreen");
        var codeSource = screenClass.getProtectionDomain().getCodeSource();
        assertNotNull(codeSource, "WaystonesItemCostConfigScreen must have a CodeSource");
        assertTrue(codeSource.getLocation().toString().endsWith(".jar"),
            "ConfigScreen must be loaded from the real packaged release .jar; got: " + codeSource.getLocation());

        Screen screen = (Screen) screenClass.getConstructor(Screen.class).newInstance((Screen) null);
        screen.width = 427;
        screen.height = 240;

        Method initMethod = screenClass.getDeclaredMethod("init");
        initMethod.setAccessible(true);
        initMethod.invoke(screen);

        List<?> widgets = screen.children();
        assertEquals(7, widgets.size(),
            "Expected 7 widgets (4 CycleButtons + Copy Example + Done + Cancel) in WaystonesItemCostConfigScreen");

        screen.onClose();
    }
}
