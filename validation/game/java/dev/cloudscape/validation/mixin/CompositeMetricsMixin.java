package dev.cloudscape.validation.mixin;

import dev.cloudscape.validation.PhaseMetrics;
import net.irisshaders.iris.pipeline.CompositePass;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets="net.irisshaders.iris.pipeline.CompositeRenderer",remap=false)
public abstract class CompositeMetricsMixin {
    @Shadow @Final private CompositePass compositePass;
    @Inject(method="renderAll",at=@At("HEAD"),remap=false)
    private void cloudscape$begin(CallbackInfo callback){PhaseMetrics.begin();}
    @Inject(method="renderAll",at=@At("RETURN"),remap=false)
    private void cloudscape$end(CallbackInfo callback){PhaseMetrics.end(compositePass.toString());}
}
