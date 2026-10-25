package dev.cloudscape.volumetric.mixin;

import dev.cloudscape.volumetric.UnboundAdapter;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Release the large world-only cache at the title screen; Iris owns all deletes. */
@Mixin(value=Minecraft.class,remap=false)
public abstract class WorldLeaveMixin {
    @Inject(method={"clearClientLevel(Lnet/minecraft/client/gui/screens/Screen;)V",
        "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V"},at=@At("HEAD"),remap=false)
    private void cloudscape$saveWeather(CallbackInfo callback) {
        dev.cloudscape.volumetric.WeatherRuntime.leave();
    }

    @Inject(method={"clearClientLevel(Lnet/minecraft/client/gui/screens/Screen;)V",
        "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V"},at=@At("RETURN"),remap=false)
    private void cloudscape$worldLeft(CallbackInfo callback) {
        if(UnboundAdapter.retainsWorldCache() && Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline) {
            Iris.getPipelineManager().destroyPipeline();
            UnboundAdapter.LOGGER.info("Cloudscape world cache released after leaving the world");
        }
    }
}
