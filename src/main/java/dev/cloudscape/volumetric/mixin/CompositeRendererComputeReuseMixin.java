package dev.cloudscape.volumetric.mixin;

import com.google.common.collect.ImmutableSet;
import dev.cloudscape.volumetric.DensityEpochReuse;
import java.util.function.Supplier;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.gl.program.ComputeProgram;
import net.irisshaders.iris.gl.texture.TextureAccess;
import net.irisshaders.iris.pipeline.CompositePass;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CompositeRenderer.class, remap = false)
public abstract class CompositeRendererComputeReuseMixin {
    @Shadow @Final private RenderTargets renderTargets;
    @Shadow @Final private TextureAccess noiseTexture;
    @Shadow @Final private CompositePass compositePass;
    @Unique private DensityEpochReuse cloudscape$reuse;

    @Inject(method = "createComputes", at = @At("RETURN"), remap = false)
    private void cloudscape$register(ComputeSource[] sources, ImmutableSet<Integer> reads,
            ImmutableSet<Integer> flipped, Supplier<ShadowRenderTargets> shadows, ShaderStorageBufferHolder buffers,
            CallbackInfoReturnable<ComputeProgram[]> callback) {
        if (compositePass != CompositePass.PREPARE) return;
        if (cloudscape$reuse == null) cloudscape$reuse = new DensityEpochReuse(renderTargets, noiseTexture);
        ComputeProgram[] programs = callback.getReturnValue();
        if (programs == null || programs.length != sources.length) return;
        for (int i = 0; i < sources.length; i++)
            if (sources[i] != null && programs[i] != null) cloudscape$reuse.register(sources[i], programs[i]);
    }

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void cloudscape$constructed(CallbackInfo callback) {
        if (cloudscape$reuse != null) cloudscape$reuse.invalidate("constructor", false);
    }

    @Inject(method = "recalculateSizes()V", at = @At("HEAD"), remap = false)
    private void cloudscape$resized(CallbackInfo callback) {
        if (cloudscape$reuse != null) cloudscape$reuse.invalidate("recalculateSizes", false);
    }

    @Inject(method = "destroy()V", at = @At("HEAD"), remap = false)
    private void cloudscape$destroyed(CallbackInfo callback) {
        if (cloudscape$reuse != null) cloudscape$reuse.invalidate("destroy", true);
    }
}
