package dev.cloudscape.volumetric.mixin;

import dev.cloudscape.volumetric.CloudSettingsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.gui.screens.options.OptionsScreen", remap = false)
public abstract class OptionsScreenMixin extends Screen {
    protected OptionsScreenMixin(Component title) { super(title); }
    @Inject(method = "init()V", at = @At("TAIL"), remap = false)
    private void cloudscape$settings(CallbackInfo callback) {
        addRenderableWidget(Button.builder(Component.literal("Cloudscape"), b -> minecraft.gui.setScreen(new CloudSettingsScreen(this)))
            .bounds(width - 104, 4, 100, 20).build());
    }
}
