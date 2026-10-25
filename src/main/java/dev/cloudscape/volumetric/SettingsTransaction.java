package dev.cloudscape.volumetric;

/** CPU-testable transaction; caller serializes it on the render thread. */
final class SettingsTransaction {
    interface Runtime {
        void install(CloudConfig config);
        void reload() throws Exception;
    }
    static void apply(ConfigStore store, CloudConfig previous, CloudConfig requested, Runtime runtime) throws Exception {
        CloudConfig next = requested.copy();
        next.validate();
        try (var staged = store.stage(next)) {
            runtime.install(next);
            try {
                runtime.reload();
                staged.commit();
            } catch (Exception failure) {
                runtime.install(previous.copy());
                try { runtime.reload(); }
                catch (Exception recovery) { failure.addSuppressed(recovery); }
                throw failure;
            }
        }
    }
}
