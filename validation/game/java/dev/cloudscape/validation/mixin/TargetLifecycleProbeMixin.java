package dev.cloudscape.validation.mixin;

import dev.cloudscape.volumetric.ResourceProbe;
import net.irisshaders.iris.targets.RenderTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=RenderTarget.class,remap=false)
public abstract class TargetLifecycleProbeMixin {
    @Unique private int[] cloudscape$destroyedIds;
    @Inject(method="<init>",at=@At("RETURN"),remap=false)
    private void cloudscape$created(CallbackInfo callback){ResourceProbe.created((RenderTarget)(Object)this);}
    @Inject(method="destroy()V",at=@At("HEAD"),remap=false)
    private void cloudscape$beforeDestroy(CallbackInfo callback){cloudscape$destroyedIds=ResourceProbe.beforeDestroy((RenderTarget)(Object)this);}
    @Inject(method="destroy()V",at=@At("RETURN"),remap=false)
    private void cloudscape$afterDestroy(CallbackInfo callback){ResourceProbe.afterDestroy(cloudscape$destroyedIds);}
}
