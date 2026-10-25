package dev.cloudscape.volumetric.mixin;

import dev.cloudscape.volumetric.DensityEpochReuse;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderTargets.class, remap = false)
public abstract class RenderTargetsReuseMixin {
    @Inject(method = "onFullClear()V", at = @At("HEAD"), remap = false)
    private void cloudscape$clear(CallbackInfo callback) {
        DensityEpochReuse.onFullClear((RenderTargets) (Object) this);
    }
}
