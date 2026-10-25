package dev.cloudscape.volumetric.mixin;

import dev.cloudscape.volumetric.WeatherRuntime;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.shaderpack.IdMap;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=CommonUniforms.class,remap=false)
public abstract class WeatherUniformMixin {
    @Inject(method="addNonDynamicUniforms",at=@At("TAIL"),remap=false)
    private static void cloudscape$uniforms(UniformHolder uniforms,IdMap ids,PackDirectives directives,
        FrameUpdateNotifier notifier,CallbackInfo callback) {
        WeatherRuntime.register(uniforms);
    }
}
