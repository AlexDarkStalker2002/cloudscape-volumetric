package dev.cloudscape.volumetric.mixin;

import dev.cloudscape.volumetric.DensityEpochReuse;
import net.irisshaders.iris.gl.program.ComputeProgram;
import org.joml.Vector3i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Preserve ComputeProgram's internal barrier and all CompositeRenderer cleanup. */
@Mixin(value = ComputeProgram.class, remap = false)
public abstract class ComputeDispatchReuseMixin {
    @Redirect(method = "dispatch(FF)V", at = @At(value = "INVOKE",
        target = "Lnet/irisshaders/iris/gl/IrisRenderSystem;dispatchCompute(Lorg/joml/Vector3i;)V"), remap = false)
    private void cloudscape$dispatch(Vector3i groups) {
        DensityEpochReuse.dispatch((ComputeProgram) (Object) this, groups);
    }
}
