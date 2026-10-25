package dev.cloudscape.volumetric.mixin;

import dev.cloudscape.volumetric.WorldDensityCache;
import java.nio.file.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.irisshaders.iris.shaderpack.ShaderPack", remap = false)
public abstract class ShaderPropertiesMixin {
    @Inject(method = "readProperties(Ljava/nio/file/Path;Ljava/lang/String;)Ljava/lang/String;", at = @At("RETURN"), cancellable = true, remap = false)
    private static void cloudscape$properties(Path root, String filename, CallbackInfoReturnable<String> callback) {
        String original = callback.getReturnValue(), replacement = WorldDensityCache.properties(root, filename, original);
        if (replacement != original) callback.setReturnValue(replacement);
    }
}
