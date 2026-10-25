package dev.cloudscape.validation.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Isolated benchmark only: retain the requested cap when its window is inactive. */
@Mixin(value=FramerateLimitTracker.class,remap=false)
public abstract class BenchmarkThrottleMixin {
    private static boolean cloudscape$benchmark() {
        return Boolean.getBoolean("cloudscape.validation") && Boolean.getBoolean("cloudscape.validation.performance");
    }
    @Inject(method="getFramerateLimit",at=@At("HEAD"),cancellable=true,remap=false)
    private void cloudscape$fixedCap(CallbackInfoReturnable<Integer> callback) {
        if(cloudscape$benchmark())callback.setReturnValue(Minecraft.getInstance().options.framerateLimit().get());
    }
    @Inject(method="isHeavilyThrottled",at=@At("HEAD"),cancellable=true,remap=false)
    private void cloudscape$noAfkSleep(CallbackInfoReturnable<Boolean> callback) {
        if(cloudscape$benchmark())callback.setReturnValue(false);
    }
}
