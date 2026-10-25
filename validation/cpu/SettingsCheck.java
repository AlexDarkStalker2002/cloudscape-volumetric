package dev.cloudscape.volumetric;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import com.google.gson.JsonParser;

/** Disk/reload failure regression tests. No GL context, Fabric runtime or game. */
public final class SettingsCheck {
    static int checks;
    static void require(boolean ok, String label) { checks++; if (!ok) throw new AssertionError(label); }
    static final class Runtime implements SettingsTransaction.Runtime {
        CloudConfig config;
        int reloads, failReload, failRecovery;
        Runnable beforeReload = () -> {};
        Runtime(CloudConfig initial) { config = initial.copy(); }
        public void install(CloudConfig next) { config = next.copy(); }
        public void reload() throws Exception {
            reloads++; beforeReload.run();
            if (reloads == failReload || reloads == failRecovery) throw new IOException("injected compile failure");
        }
    }
    static void expectFailure(RunnableWithException action, String label) throws Exception {
        try { action.run(); } catch (Exception expected) { checks++; return; }
        throw new AssertionError(label);
    }
    interface RunnableWithException { void run() throws Exception; }
    static void noPending(Path dir) throws Exception {
        try (var files = Files.list(dir)) { require(files.noneMatch(p -> p.toString().endsWith(".pending")), "pending file leaked"); }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]); Files.createDirectories(dir);
        Path file = dir.resolve("cloudscape.json");
        ConfigStore store = new ConfigStore(file);
        CloudConfig old = store.load();
        require(!Files.exists(file), "opening missing config must not write defaults");
        require(old.viewSteps == 96 && old.reflectionSteps == 24 && old.lightSteps == 5 && old.maxDistance == 6000, "original rendering budgets changed");
        CloudConfig edited = old.copy(); edited.coverage = .63f; edited.windSpeed = 1.25f;
        Runtime runtime = new Runtime(old);
        SettingsTransaction.apply(store, old, edited, runtime);
        require(runtime.reloads == 1 && runtime.config.coverage == .63f, "apply did not install exactly once");
        require(new ConfigStore(file).load().windSpeed == 1.25f, "restart lost settings");
        require(JsonParser.parseString(Files.readString(file)).getAsJsonObject().get("schemaVersion").getAsInt() == 2, "schema missing");
        byte[] saved = Files.readAllBytes(file);
        edited.density = 2f; runtime.failReload = 2;
        expectFailure(() -> SettingsTransaction.apply(store, runtime.config.copy(), edited, runtime), "shader failure accepted");
        require(Arrays.equals(saved, Files.readAllBytes(file)), "shader failure overwrote last working config");
        require(runtime.config.density == 1f && runtime.reloads == 3, "runtime not restored/reloaded after shader failure");
        noPending(dir);

        ConfigStore failedRecoveryStore = new ConfigStore(file); CloudConfig beforeRecovery = failedRecoveryStore.load();
        Runtime failedRecovery = new Runtime(beforeRecovery); failedRecovery.failReload=1; failedRecovery.failRecovery=2;
        try { SettingsTransaction.apply(failedRecoveryStore,beforeRecovery,edited,failedRecovery); throw new AssertionError("double failure accepted"); }
        catch(IOException expected) { require(expected.getSuppressed().length==1,"recovery failure was hidden"); }
        require(Arrays.equals(saved,Files.readAllBytes(file)) && failedRecovery.config.defines().equals(beforeRecovery.defines()),"double failure lost last working config");
        noPending(dir);

        Path blockedParent=dir.resolve("parent-is-file");Files.writeString(blockedParent,"fixture");
        ConfigStore cannotStage=new ConfigStore(blockedParent.resolve("config.json"));CloudConfig beforeStage=cannotStage.load();Runtime stageRuntime=new Runtime(beforeStage);
        expectFailure(()->SettingsTransaction.apply(cannotStage,beforeStage,edited,stageRuntime),"unwritable stage accepted");
        require(stageRuntime.reloads==0 && stageRuntime.config.defines().equals(beforeStage.defines()),"stage failure changed runtime");

        Path legacy = dir.resolve("legacy.json");
        String original = "{\"reimaginedThickness\":64,\"viewSteps\":117,\"coverage\":0.61,\"enabled\":false,\"futureKey\":{\"value\":42}}";
        Files.writeString(legacy, original);
        ConfigStore legacyStore = new ConfigStore(legacy); CloudConfig migrated = legacyStore.load();
        require(migrated.reimaginedThickness == 128, "0.3 default not migrated");
        require(migrated.viewSteps == 117 && migrated.coverage == .61f && !migrated.enabled, "migration lost chosen values");
        require(Files.readString(legacy).equals(original), "migration wrote during load");
        SettingsTransaction.apply(legacyStore, migrated, migrated, new Runtime(migrated));
        require(JsonParser.parseString(Files.readString(legacy)).getAsJsonObject().getAsJsonObject("futureKey").get("value").getAsInt() == 42, "unknown fields lost");
        try (var files = Files.list(dir)) {
            require(files.filter(p -> p.getFileName().toString().startsWith("legacy.json.previous-")).anyMatch(p -> {
                try { return Files.readString(p).equals(original); } catch (IOException error) { throw new RuntimeException(error); }
            }), "legacy recovery bytes missing");
        }
        for (String json : new String[]{"{\"reimaginedThickness\":64,\"reimaginedVoxelSize\":8}", "{\"reimaginedThickness\":72}"}) {
            Files.writeString(legacy, json); CloudConfig chosen = new ConfigStore(legacy).load();
            require(chosen.reimaginedThickness == (json.contains("72") ? 72 : 64), "explicit legacy height changed");
        }

        int index = 0;
        for (String json : new String[]{"{", "null", "[]", "{\"enabled\":\"false\"}", "{\"density\":null}", "{\"viewSteps\":1.5}", "{\"viewSteps\":2147483648}", "{\"density\":NaN}", "{\"density\":Infinity}", "{\"enabled\":true} trailing"}) {
            Path bad = dir.resolve("bad-" + index++ + ".json"); Files.writeString(bad, json);
            ConfigStore badStore = new ConfigStore(bad); CloudConfig temporary = badStore.load();
            require(badStore.notice().equals("config.corrupt"), "invalid config not diagnosed: " + json);
            require(Files.readString(bad).equals(json), "invalid file changed on load");
            SettingsTransaction.apply(badStore, temporary, edited, new Runtime(temporary));
            try (var files = Files.list(dir)) {
                require(files.filter(p -> p.getFileName().toString().startsWith(bad.getFileName() + ".corrupt-")).anyMatch(p -> {
                    try { return Files.readString(p).equals(json); } catch (IOException error) { throw new RuntimeException(error); }
                }), "corrupt recovery copy missing");
            }
        }

        Path future = dir.resolve("future.json"); Files.writeString(future, "{\"schemaVersion\":99,\"coverage\":0.8}");
        ConfigStore futureStore = new ConfigStore(future); CloudConfig futureConfig = futureStore.load();
        Runtime futureRuntime = new Runtime(futureConfig);
        expectFailure(() -> SettingsTransaction.apply(futureStore, futureConfig, edited, futureRuntime), "newer schema overwritten");
        require(futureRuntime.reloads == 0 && Files.readString(future).contains("99"), "future-schema apply touched runtime/disk");

        ConfigStore external = new ConfigStore(file); CloudConfig beforeExternal = external.load();
        Files.writeString(file, "{\"density\":1.9}"); Runtime externalRuntime = new Runtime(beforeExternal);
        expectFailure(() -> SettingsTransaction.apply(external, beforeExternal, edited, externalRuntime), "external edit lost");
        require(externalRuntime.reloads == 0 && Files.readString(file).contains("1.9"), "external conflict altered runtime/disk");

        ConfigStore commitConflict = new ConfigStore(file); CloudConfig beforeConflict = commitConflict.load(); Runtime conflictRuntime = new Runtime(beforeConflict);
        conflictRuntime.beforeReload = () -> { if (conflictRuntime.reloads == 1) try { Files.writeString(file,"{\"density\":2.1}"); } catch (IOException error) { throw new RuntimeException(error); } };
        expectFailure(() -> SettingsTransaction.apply(commitConflict, beforeConflict, edited, conflictRuntime), "commit conflict accepted");
        require(conflictRuntime.config.density == 1.9f && conflictRuntime.reloads == 2 && Files.readString(file).contains("2.1"), "commit conflict did not preserve external file and restore runtime");
        noPending(dir);

        CloudConfig quality = new CloudConfig(); quality.coverage = .67f; quality.maxDistance = 7200; quality.reimaginedVoxelSize = 8;
        String shape = quality.defines();
        CloudQuality.BALANCED.set(quality);
        require(CloudQuality.of(quality) == CloudQuality.BALANCED && quality.coverage == .67f && quality.maxDistance == 7200 && quality.reimaginedVoxelSize == 8, "quality preset changes style/shape/range");
        CloudQuality.ORIGINAL.set(quality); require(shape.equals(quality.defines()), "original quality does not restore exact old defines");
        quality.reflectionSteps = 29; require(CloudQuality.of(quality) == CloudQuality.CUSTOM, "custom budgets misreported");

        require(AtlasSupport.select(1536,16384,(w,h)->true).size() == 1536, "modern limit lost original size");
        require(AtlasSupport.select(1536,8192,(w,h)->true).size() == 1024, "8192 limit not respected");
        require(AtlasSupport.select(1536,4096,(w,h)->true).size() == 512, "4096 limit not respected");
        require(AtlasSupport.select(1536,2047,(w,h)->true).size() == 0, "impossible width accepted");
        require(AtlasSupport.select(1536,16384,(w,h)->false).size() == 0, "unsupported format accepted");
        require(AtlasSupport.select(1536,16384,(w,h)->w <= 6144).size() == 768, "format/size fallback failed");
        require(AtlasSupport.select(512,16384,(w,h)->true).size() == 512, "user atlas size increased silently");
        System.out.println("PASS " + checks + " settings, migration, atomic rollback, quality and atlas-limit assertions; CPU only.");
    }
}
