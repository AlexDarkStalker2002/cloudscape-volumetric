package dev.cloudscape.validation.mixin;

import dev.cloudscape.validation.GpuSpanMetrics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Installed Iris1.11.7 signatures; excludes prepare from the actual shadow span. */
@Mixin(targets="net.irisshaders.iris.pipeline.IrisRenderingPipeline",remap=false)
public abstract class PipelineSpanMetricsMixin {
    private static final String SHADOWS="renderShadows(Lnet/irisshaders/iris/mixin/LevelRendererAccessor;Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V";
    private static final String SHADOW_CALL="Lnet/irisshaders/iris/shadows/ShadowRenderer;renderShadows(Lnet/irisshaders/iris/mixin/LevelRendererAccessor;Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V";
    private static final String COMPOSITE_CALL="Lnet/irisshaders/iris/pipeline/CompositeRenderer;renderAll()V";
    private static final String FINAL_CALL="Lnet/irisshaders/iris/pipeline/FinalPassRenderer;renderFinalPass()V";

    @Inject(method="beginLevelRendering()V",at=@At("HEAD"),remap=false)
    private void cloudscape$frame(CallbackInfo ci){GpuSpanMetrics.beginFrame();}

    @Inject(method=SHADOWS,at=@At(value="INVOKE",target=SHADOW_CALL,shift=At.Shift.BEFORE),remap=false)
    private void cloudscape$shadowBegin(CallbackInfo ci){GpuSpanMetrics.begin("SHADOW_TOTAL");}
    @Inject(method=SHADOWS,at=@At(value="INVOKE",target=SHADOW_CALL,shift=At.Shift.AFTER),remap=false)
    private void cloudscape$shadowEnd(CallbackInfo ci){GpuSpanMetrics.end("SHADOW_TOTAL");}

    // Iris renderShadows() itself runs prepareRenderer after ShadowRenderer.
    @Inject(method=SHADOWS,at=@At("RETURN"),remap=false)
    private void cloudscape$opaqueBegin(CallbackInfo ci){GpuSpanMetrics.begin("GBUFFERS_OPAQUE_INTERVAL");}
    @Inject(method="beginTranslucents()V",at=@At("HEAD"),remap=false)
    private void cloudscape$opaqueEnd(CallbackInfo ci){GpuSpanMetrics.end("GBUFFERS_OPAQUE_INTERVAL");GpuSpanMetrics.begin("PRE_DEFERRED_SETUP");}
    @Inject(method="beginTranslucents()V",at=@At(value="INVOKE",target=COMPOSITE_CALL,shift=At.Shift.BEFORE),remap=false)
    private void cloudscape$deferredSetupEnd(CallbackInfo ci){GpuSpanMetrics.end("PRE_DEFERRED_SETUP");}
    @Inject(method="beginTranslucents()V",at=@At("RETURN"),remap=false)
    private void cloudscape$translucentBegin(CallbackInfo ci){GpuSpanMetrics.begin("GBUFFERS_TRANSLUCENT_INTERVAL");}
    @Inject(method="finalizeLevelRendering()V",at=@At("HEAD"),remap=false)
    private void cloudscape$translucentEnd(CallbackInfo ci){GpuSpanMetrics.end("GBUFFERS_TRANSLUCENT_INTERVAL");}

    @Inject(method="finalizeLevelRendering()V",at=@At(value="INVOKE",target=FINAL_CALL,shift=At.Shift.BEFORE),remap=false)
    private void cloudscape$finalBegin(CallbackInfo ci){GpuSpanMetrics.begin("FINAL_PASS");}
    @Inject(method="finalizeLevelRendering()V",at=@At(value="INVOKE",target=FINAL_CALL,shift=At.Shift.AFTER),remap=false)
    private void cloudscape$finalEnd(CallbackInfo ci){GpuSpanMetrics.end("FINAL_PASS");}
}
