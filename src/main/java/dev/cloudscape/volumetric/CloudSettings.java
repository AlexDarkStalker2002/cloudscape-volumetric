package dev.cloudscape.volumetric;

import java.io.IOException;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.Iris;

final class CloudSettings {
    private static ConfigStore store;
    private static CloudConfig initial;
    static synchronized CloudConfig initialize() {
        if (store == null) {
            store = new ConfigStore(FabricLoader.getInstance().getConfigDir().resolve("cloudscape-volumetric.json"));
            initial = store.load();
        }
        return initial;
    }
    static String notice() { initialize(); return store.notice(); }

    static void apply(CloudConfig requested) throws Exception {
        initialize();
        SettingsTransaction.apply(store, UnboundAdapter.settings().copy(), requested, new SettingsTransaction.Runtime() {
            @Override public void install(CloudConfig config) { UnboundAdapter.replaceSettings(config); }
            @Override public void reload() throws IOException {
                Iris.reload();
                if (Iris.getStoredError().isPresent()) throw new IOException("Shader compilation failed", Iris.getStoredError().get());
                if (Iris.isFallback()) throw new IOException("Iris selected its fallback pipeline");
            }
        });
    }
}
