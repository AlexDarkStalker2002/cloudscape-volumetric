package dev.cloudscape.validation.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets="net.irisshaders.iris.gl.program.ProgramUniforms",remap=false)
public abstract class WindUniformProbeMixin {
    @Inject(method="update",at=@At("TAIL"),remap=false)
    private void cloudscape$windReadback(CallbackInfo ci){
        if(Boolean.getBoolean("cloudscape.validation.windMotion"))dev.cloudscape.volumetric.WindMotionScenario.observeProgram();
    }
}
