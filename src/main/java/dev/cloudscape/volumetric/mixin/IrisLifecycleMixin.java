package dev.cloudscape.volumetric.mixin;

import dev.cloudscape.volumetric.UnboundAdapter;
import net.irisshaders.iris.Iris;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Iris owns/deletes both atlas storage objects; release our validation epoch too. */
@Mixin(targets = "net.irisshaders.iris.Iris", remap = false)
public abstract class IrisLifecycleMixin {
    @Inject(method = "destroyEverything()V", at = @At("HEAD"), remap = false)
    private static void cloudscape$reset(CallbackInfo callback) { UnboundAdapter.resetPipeline(); }

    @Inject(method = "loadShaderpack()V", at = @At("RETURN"), remap = false)
    private static void cloudscape$packStatus(CallbackInfo callback) {
        UnboundAdapter.finishPackLoad(Iris.getCurrentPack().isPresent(), Iris.getCurrentPackName());
    }
}
