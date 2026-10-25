package dev.cloudscape.volumetric;

import net.fabricmc.api.ClientModInitializer;

public final class CloudscapeClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        UnboundAdapter.initialize();
    }
}
