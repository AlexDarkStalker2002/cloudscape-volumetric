package dev.cloudscape.volumetric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Reading never writes. The old bytes survive until a successful atomic commit. */
final class ConfigStore {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().setStrictness(Strictness.STRICT).create();
    static final int SCHEMA = 2;
    final Path file;
    private byte[] observed;
    private JsonObject extras = new JsonObject();
    private boolean corrupt, future;
    private String notice = "";

    ConfigStore(Path file) { this.file = file.toAbsolutePath(); }
    String notice() { return notice; }

    CloudConfig load() {
        CloudConfig defaults = new CloudConfig();
        try {
            observed = Files.exists(file) ? Files.readAllBytes(file) : null;
            if (observed == null) { notice = "config.missing"; return defaults; }
            JsonObject json = GSON.fromJson(new String(observed, StandardCharsets.UTF_8), JsonObject.class);
            if (json == null) throw new IllegalArgumentException("Null config");
            if (json.has("schemaVersion")) {
                requireType(json, "schemaVersion", int.class);
                if (json.get("schemaVersion").getAsInt() > SCHEMA) {
                    future = true;
                    throw new IllegalArgumentException("Newer config schema");
                }
            }
            for (Field field : CloudConfig.class.getFields()) requireType(json, field.getName(), field.getType());
            CloudConfig parsed = GSON.fromJson(json, CloudConfig.class);
            if (!json.has("schemaVersion") || json.get("schemaVersion").getAsInt() < SCHEMA) {
                notice = "config.migrated";
                if (!json.has("schemaVersion") && !json.has("reimaginedVoxelSize") && parsed.reimaginedThickness == 64f)
                    parsed.reimaginedThickness = 128f;
            }
            parsed.validate();
            extras = json.deepCopy();
            return parsed;
        } catch (IOException | RuntimeException error) {
            corrupt = true;
            notice = future ? "config.future" : "config.corrupt";
            UnboundAdapter.LOGGER.error("Cannot read {}. Original bytes preserved; no defaults written.", file, error);
            return defaults;
        }
    }

    private static void requireType(JsonObject json, String key, Class<?> type) {
        if (!json.has(key)) return;
        var value = json.get(key);
        if (!value.isJsonPrimitive()) throw new IllegalArgumentException("Invalid type: " + key);
        var primitive = value.getAsJsonPrimitive();
        if (type == boolean.class ? !primitive.isBoolean() : !primitive.isNumber())
            throw new IllegalArgumentException("Invalid type: " + key);
        if (type != boolean.class && (!Double.isFinite(primitive.getAsDouble())
            || type == int.class && primitive.getAsBigDecimal().stripTrailingZeros().scale() > 0))
            throw new IllegalArgumentException("Invalid number: " + key);
        if (type == int.class) primitive.getAsBigDecimal().intValueExact();
    }

    Staged stage(CloudConfig value) throws IOException {
        if (future) throw new IOException("Newer config schema; use that mod version to edit it");
        checkUnchanged();
        JsonObject json = extras.deepCopy();
        GSON.toJsonTree(value).getAsJsonObject().entrySet().forEach(e -> json.add(e.getKey(), e.getValue()));
        json.addProperty("schemaVersion", SCHEMA);
        byte[] bytes = (GSON.toJson(json) + "\n").getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), file.getFileName() + ".", ".pending");
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        } catch (IOException error) { Files.deleteIfExists(temp); throw error; }
        return new Staged(temp, bytes, json);
    }

    private void checkUnchanged() throws IOException {
        byte[] now = Files.exists(file) ? Files.readAllBytes(file) : null;
        if (!Arrays.equals(now, observed)) throw new IOException("Config changed externally; reopen the screen after restarting Minecraft");
    }

    final class Staged implements AutoCloseable {
        private final Path temp;
        private final byte[] bytes;
        private final JsonObject json;
        Staged(Path temp, byte[] bytes, JsonObject json) { this.temp = temp; this.bytes = bytes; this.json = json; }
        void commit() throws IOException {
            checkUnchanged();
            if (observed != null) {
                // Unique backup, including exact corrupt/legacy bytes; never overwrite a prior recovery file.
                Path backup = Files.createTempFile(file.getParent(), file.getFileName() + (corrupt ? ".corrupt-" : ".previous-"), ".json");
                Files.write(backup, observed);
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            observed = bytes;
            extras = json;
            corrupt = false;
            notice = "";
        }
        @Override public void close() throws IOException { Files.deleteIfExists(temp); }
    }
}
