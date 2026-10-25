package dev.cloudscape.validation.mixin;

import dev.cloudscape.validation.FrameMetrics;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GameRenderer.class, remap = false)
public abstract class FrameMetricsMixin {
    @Inject(method = "render()V", at = @At("HEAD"), remap = false)
    private void cloudscape$begin(CallbackInfo callback) { FrameMetrics.begin(); }

    @Inject(method = "render()V", at = @At("RETURN"), remap = false)
    private void cloudscape$end(CallbackInfo callback) {
        FrameMetrics.end();
    }
}
