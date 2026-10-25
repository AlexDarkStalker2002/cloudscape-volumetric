package dev.cloudscape.volumetric.mixin;

import dev.cloudscape.volumetric.UnboundAdapter;
import dev.cloudscape.volumetric.WorldDensityCache;
import java.nio.file.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Patch before Iris builds its include graph, so reflections and the main view share the same volume. */
@Mixin(targets = "net.irisshaders.iris.shaderpack.include.IncludeGraph", remap = false)
public abstract class IncludeGraphMixin {
    @Inject(method = "readFile(Ljava/nio/file/Path;)Ljava/lang/String;", at = @At("HEAD"), cancellable = true, remap = false)
    private static void cloudscape$virtual(Path path, CallbackInfoReturnable<String> callback) {
        String source = WorldDensityCache.virtualSource(path);
        if (source != null) callback.setReturnValue(source);
    }

    @Inject(method = "readFile(Ljava/nio/file/Path;)Ljava/lang/String;", at = @At("RETURN"), cancellable = true, remap = false)
    private static void cloudscape$read(Path path, CallbackInfoReturnable<String> callback) {
        String original = callback.getReturnValue();
        String replacement = UnboundAdapter.transform(path, WorldDensityCache.attachmentMetadata(path, original));
        if (replacement != original) callback.setReturnValue(replacement);
    }
}
