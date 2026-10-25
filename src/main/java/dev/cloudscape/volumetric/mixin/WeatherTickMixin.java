package dev.cloudscape.volumetric.mixin;

import java.util.function.BooleanSupplier;
import dev.cloudscape.volumetric.WeatherRuntime;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=ClientLevel.class,remap=false)
public abstract class WeatherTickMixin {
    @Inject(method="tick",at=@At("RETURN"),remap=false)
    private void cloudscape$simulationTick(BooleanSupplier ticking,CallbackInfo callback) {
        WeatherRuntime.tick((ClientLevel)(Object)this);
    }
}
