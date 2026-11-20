package dev.rinchan.waystonesitemcost.mixin.client;

import dev.rinchan.waystonesitemcost.MixinGuard;
import dev.rinchan.waystonesitemcost.config.PaymentConfig;
import dev.rinchan.waystonesitemcost.physics.PhysicsPosResolver;
import net.blay09.mods.waystones.api.Waystone;
import net.blay09.mods.waystones.client.gui.widget.AbstractWaystoneButton;
import net.blay09.mods.waystones.client.gui.widget.WaystoneButton;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(WaystoneButton.class)
public abstract class PhysicsWaystoneButtonMixin extends AbstractWaystoneButton {
    protected PhysicsWaystoneButtonMixin(int x, int y, int width, Waystone waystone, OnPress pressable) {
        super(x, y, width, waystone, pressable);
    }

    @Redirect(
        method = "renderDistance",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;distanceTo(Lnet/minecraft/world/phys/Vec3;)D")
    )
    private double itemCostExtended$resolveButtonDistance(Vec3 origin, Vec3 rawTarget) {
        try {
            var player = Minecraft.getInstance().player;
            if (PaymentConfig.fixPhysicsShipDistance() && player != null && this.waystone != null) {
                return PhysicsPosResolver.resolveDistance(
                    player,
                    this.waystone.getWaystoneUid(),
                    this.waystone.getDimension(),
                    this.waystone.getPos()
                );
            }
        } catch (Throwable t) {
            MixinGuard.logOnce("WaystoneButton.renderDistance", t);
        }
        return origin.distanceTo(rawTarget);
    }
}
