package dev.cloudscape.validation.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=Minecraft.class,remap=false)
public abstract class ValidationFrameMixin {
    @Inject(method="renderFrame(Z)V",at=@At("RETURN"),remap=false)
    private void cloudscape$frame(CallbackInfo callback) {
        dev.cloudscape.validation.UiBridge.frameFinished();
        dev.cloudscape.validation.MotionRecorder.frame();
        if(Boolean.getBoolean("cloudscape.validation.windMotion"))dev.cloudscape.volumetric.WindMotionScenario.afterFrame();
    }
}
