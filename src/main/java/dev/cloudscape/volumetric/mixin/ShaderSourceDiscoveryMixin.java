package dev.cloudscape.volumetric.mixin;

import com.google.common.collect.ImmutableList;
import dev.cloudscape.volumetric.WorldDensityCache;
import java.nio.file.Path;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.irisshaders.iris.shaderpack.include.ShaderPackSourceNames", remap = false)
public abstract class ShaderSourceDiscoveryMixin {
    @Inject(method = "findPresentSources", at = @At("RETURN"), cancellable = true, remap = false)
    private static void cloudscape$discover(ImmutableList.Builder<AbsolutePackPath> starts, Path root,
        AbsolutePackPath directory, ImmutableList<String> candidates, CallbackInfoReturnable<Boolean> callback) {
        if (!directory.getPathString().equals("/world0") || !WorldDensityCache.enabled(root)) return;
        for (String source : WorldDensityCache.stageSources(root))
            starts.add(AbsolutePackPath.fromAbsolutePath(source));
        callback.setReturnValue(true);
    }
}
