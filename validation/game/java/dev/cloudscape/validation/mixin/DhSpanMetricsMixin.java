package dev.cloudscape.validation.mixin;

import dev.cloudscape.validation.GpuSpanMetrics;
import net.irisshaders.iris.shadows.ShadowRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional DH3.3.4 terrain-only spans; no hard Java dependency on DH classes. */
@Pseudo
@Mixin(targets="com.seibel.distanthorizons.core.render.renderer.LodRenderer",remap=false)
public abstract class DhSpanMetricsMixin {
    private static final String TERRAIN="renderTerrain(Lcom/seibel/distanthorizons/core/wrapperInterfaces/render/renderPass/IDhTerrainRenderer;Lcom/seibel/distanthorizons/core/render/RenderBufferHandler;Lcom/seibel/distanthorizons/core/render/RenderParams;ZLcom/seibel/distanthorizons/core/wrapperInterfaces/minecraft/IProfilerWrapper;)V";
    @Inject(method=TERRAIN,at=@At("HEAD"),require=0,remap=false)
    private void cloudscape$terrainBegin(@Coerce Object renderer,@Coerce Object buffers,@Coerce Object params,boolean opaque,@Coerce Object profiler,CallbackInfo ci){
        GpuSpanMetrics.begin(tag(opaque));
    }
    @Inject(method=TERRAIN,at=@At("RETURN"),require=0,remap=false)
    private void cloudscape$terrainEnd(@Coerce Object renderer,@Coerce Object buffers,@Coerce Object params,boolean opaque,@Coerce Object profiler,CallbackInfo ci){
        GpuSpanMetrics.end(tag(opaque));
    }
    private static String tag(boolean opaque){return (ShadowRenderer.ACTIVE?"DH_SHADOW_TERRAIN_":"DH_TERRAIN_")+(opaque?"OPAQUE":"TRANSLUCENT");}
}
