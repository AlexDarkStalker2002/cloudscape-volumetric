package dev.cloudscape.validation.mixin;

import dev.cloudscape.validation.ValidationRunner;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Minecraft.class, remap = false)
public abstract class ValidationMixin {
    @Inject(method = "tick()V", at = @At("RETURN"), remap = false)
    private void cloudscape$test(CallbackInfo callback) {
        if (dev.cloudscape.validation.UiBridge.tick(Minecraft.getInstance())) return;
        ValidationRunner.tick(Minecraft.getInstance());
    }
}
