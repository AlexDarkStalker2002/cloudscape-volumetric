package dev.cloudscape.volumetric;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.function.Supplier;
import java.util.function.IntFunction;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.ARBComputeShader;
import com.mojang.blaze3d.systems.RenderSystem;

/** One density evaluation per world voxel; no screen-resolution or lighting approximation. */
public final class WorldDensityCache {
    private static final Map<FileSystem, Boolean> AVAILABLE = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<FileSystem, Boolean> PRIMARY = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<FileSystem, ComputeHardware> COMPUTE = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<FileSystem, AtlasSupport.Result> ATLASES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<FileSystem, String> REASONS = Collections.synchronizedMap(new WeakHashMap<>());
    // One hardware snapshot per shader-pack filesystem keeps discovery, include
    // reads and render-target directives on the same producer selection.
    static Supplier<ComputeHardware> computeProbe = WorldDensityCache::probeComputeHardware;
    static IntFunction<AtlasSupport.Result> atlasProbe = AtlasSupport::query;
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    private static final Pattern RESERVED = Pattern.compile("\\b(?:colortex20[A-Za-z0-9_]*|colorimg20)\\b|RENDERTARGETS:[^\\r\\n]*\\b20\\b");
    private static final Pattern PREPARE_PROPERTY = Pattern.compile("(?m)^\\s*[^#\\r\\n=]*\\bprepare\\b[^\\r\\n=]*=");
    private static final String PRIMARY_FORMAT = "all-text-path-normalized-tokens-sha256-v2";
    private static final String PRIMARY_CALL = "clouds = GetClouds(cloudLinearDepth, skyFade, vec3(0.0), playerPos, lViewPos, VdotS, VdotU, dither, auroraBorealis, nightNebula);";
    private static volatile String diagnostic = "atlas.pending";
    private static volatile int effectiveSize;

    static String diagnostic() { return diagnostic; }
    static int effectiveSize() { return effectiveSize; }

    static synchronized void reset() {
        AVAILABLE.clear(); PRIMARY.clear(); COMPUTE.clear(); ATLASES.clear(); REASONS.clear(); REPORTED.clear(); diagnostic = "atlas.pending"; effectiveSize = 0;
    }

    private static AtlasSupport.Result hardware(Path path) {
        synchronized (ATLASES) {
            return ATLASES.computeIfAbsent(path.getFileSystem(), ignored -> {
                AtlasSupport.Result result = atlasProbe.apply(UnboundAdapter.settings().densityAtlasSize);
                effectiveSize = result.size();
                UnboundAdapter.LOGGER.info("Cloudscape atlas capability: {}, requested={}, effective={}",
                    result.reason(), UnboundAdapter.settings().densityAtlasSize, result.size());
                return result;
            });
        }
    }

    static int atlasSize(Path path) { return hardware(path).size(); }

    private WorldDensityCache() {}

    record ComputeHardware(boolean supported, int maxTextureSize, int maxImages, int maxTextures,
        int maxLocalX, int maxLocalY, int maxInvocations, int maxGroupsX, int maxGroupsY) {
        static ComputeHardware unavailable() { return new ComputeHardware(false, 0, 0, 0, 0, 0, 0, 0, 0); }
        boolean accepts(int size) {
            int groups = (size + 7) / 8;
            return supported && size > 0 && size * 8 <= maxTextureSize && maxImages >= 1 && maxTextures >= 1
                && maxLocalX >= 8 && maxLocalY >= 8 && maxInvocations >= 64
                && maxGroupsX >= groups && maxGroupsY >= groups;
        }
    }

    private static ComputeHardware probeComputeHardware() {
        try {
            if (!RenderSystem.isOnRenderThread()) return ComputeHardware.unavailable();
            GLCapabilities caps = GL.getCapabilities();
            boolean compute = caps.OpenGL43 || caps.GL_ARB_compute_shader;
            boolean images = caps.OpenGL42 || caps.GL_ARB_shader_image_load_store;
            if (!compute || !images || caps.glDispatchCompute == 0 || caps.glBindImageTexture == 0 || caps.glMemoryBarrier == 0)
                return ComputeHardware.unavailable();
            return new ComputeHardware(true, GL11C.glGetInteger(GL11C.GL_MAX_TEXTURE_SIZE),
                GL11C.glGetInteger(ARBComputeShader.GL_MAX_COMPUTE_IMAGE_UNIFORMS),
                GL11C.glGetInteger(ARBComputeShader.GL_MAX_COMPUTE_TEXTURE_IMAGE_UNITS),
                GL30C.glGetIntegeri(ARBComputeShader.GL_MAX_COMPUTE_WORK_GROUP_SIZE, 0),
                GL30C.glGetIntegeri(ARBComputeShader.GL_MAX_COMPUTE_WORK_GROUP_SIZE, 1),
                GL11C.glGetInteger(ARBComputeShader.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS),
                GL30C.glGetIntegeri(ARBComputeShader.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 0),
                GL30C.glGetIntegeri(ARBComputeShader.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 1));
        } catch (IllegalStateException | LinkageError unavailable) {
            // Source discovery without a current GL context safely keeps the
            // original fragment producer for this shader-pack load.
            return ComputeHardware.unavailable();
        }
    }

    public static boolean computeEnabled(Path path) {
        if (Boolean.getBoolean("cloudscape.forceFragmentProducer") || !residentPrimary(path)) return false;
        synchronized (COMPUTE) {
            return COMPUTE.computeIfAbsent(path.getFileSystem(), ignored -> computeProbe.get())
                .accepts(atlasSize(path));
        }
    }

    public static List<String> stageSources(Path root) {
        return computeEnabled(root) ? List.of("/world0/prepare.csh")
            : List.of("/world0/prepare.vsh", "/world0/prepare.fsh");
    }

    public static boolean enabled(Path path) {
        if (!path.getFileSystem().provider().getScheme().equals("jar")) return false;
        CloudConfig config = UnboundAdapter.settings();
        if (!cacheLayout(config)) { diagnostic = "atlas.off"; return false; }
        if (!UnboundAdapter.compatible(path)) return false;
        AtlasSupport.Result hardware = hardware(path);
        diagnostic = hardware.reason();
        if (hardware.size() == 0) return false;
        synchronized (AVAILABLE) {
            boolean available = AVAILABLE.computeIfAbsent(path.getFileSystem(), WorldDensityCache::available);
            diagnostic = available ? hardware.reason() : REASONS.getOrDefault(path.getFileSystem(), "atlas.conflict");
            return available;
        }
    }

    static boolean cacheLayout(CloudConfig config) {
        return config.enabled && config.reimaginedDensityCache && config.reimaginedVoxelSize == 16
            && Float.isFinite(config.reimaginedThickness) && config.reimaginedThickness > 0 && config.reimaginedThickness <= 128
            && config.densityAtlasSize > 0 && config.densityAtlasSize <= 4096 && config.densityAtlasSize % 2 == 0;
    }

    static boolean residentLayout(CloudConfig config) {
        return cacheLayout(config) && Float.isFinite(config.maxDistance) && config.maxDistance > 0
            && config.densityAtlasSize * 8.0 >= config.maxDistance * 2.0 + 128.0;
    }

    static boolean available(FileSystem fs) {
        try {
            try (InputStream input = WorldDensityCache.class.getResourceAsStream("/atlas-style-interface.properties")) {
                if (input == null) return false;
                Properties expected = new Properties(); expected.load(input);
                String actual = ComplementaryContract.atlasStyleFingerprint(Files.readString(fs.getPath("/shaders/lib/common.glsl")));
                if (!actual.equals(expected.getProperty("fingerprint"))) {
                    REASONS.put(fs,"atlas.style");
                    if (REPORTED.add("style-interface:" + fs.getPath("/").toUri()))
                        UnboundAdapter.LOGGER.warn("Density atlas disabled: shaderpack style selection changed; procedural Cloudscape retained");
                    return false;
                }
            }
            if (!Files.isRegularFile(fs.getPath("/shaders/shaders.properties"))) return false;
            for (String directory : new String[]{"/shaders", "/shaders/world0"})
                for (String suffix : new String[]{"fsh", "vsh", "gsh", "tcs", "tes", "csh"})
                    if (Files.exists(fs.getPath(directory, "prepare." + suffix))
                        || Files.exists(fs.getPath(directory, "prepare0." + suffix))) return false;
            try (var files = Files.walk(fs.getPath("/shaders"))) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String name = file.getFileName().toString();
                    if (!(name.endsWith(".glsl") || name.endsWith(".fsh") || name.endsWith(".vsh")
                        || name.endsWith(".gsh") || name.endsWith(".csh") || name.endsWith(".properties")
                        || name.endsWith(".tcs") || name.endsWith(".tes"))) continue;
                    String source = Files.readString(file, StandardCharsets.UTF_8);
                    if (RESERVED.matcher(source).find()
                        || name.endsWith(".properties") && PREPARE_PROPERTY.matcher(source).find()) return false;
                }
            }
            return true;
        } catch (IOException | IllegalArgumentException error) {
            return false;
        }
    }

    public static boolean residentPrimary(Path path) {
        CloudConfig config = UnboundAdapter.settings();
        if (!enabled(path)) return false;
        CloudConfig effective = config.copy();
        effective.densityAtlasSize = atlasSize(path);
        if (!residentLayout(effective)) return false;
        synchronized (PRIMARY) {
            return PRIMARY.computeIfAbsent(path.getFileSystem(), WorldDensityCache::knownPrimary);
        }
    }

    /** Complete text dependency set: unrelated source edits only disable resident specialization. */
    static Map<String, String> primarySources(FileSystem fs) throws IOException {
        Map<String, String> selected = new TreeMap<>();
        try (var paths = Files.walk(fs.getPath("/shaders"))) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String source;
                try {
                    // Includes need not have a conventional extension. Inspect all UTF-8 text
                    // under shaders; binary assets normally fail decoding and are skipped.
                    source = Files.readString(path, StandardCharsets.UTF_8);
                } catch (CharacterCodingException error) {
                    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (name.matches(".*\\.(?:glsl|fsh|vsh|gsh|csh|tcs|tes|inc|properties)$")) throw error;
                    continue;
                }
                String name = path.toAbsolutePath().normalize().toString().replace('\\', '/');
                // Properties have executable pipeline metadata and significant line/escape
                // semantics which a GLSL lexer cannot normalize safely. Keep their entire
                // text, normalizing only line endings. GLSL text keeps the full token body,
                // including preprocessor line boundaries. Empty/comment files retain a path.
                String normalized;
                if (name.toLowerCase(Locale.ROOT).endsWith(".properties"))
                    normalized = source.replace("\r\n", "\n").replace('\r', '\n');
                else {
                    List<ComplementaryContract.Token> tokens = ComplementaryContract.tokens(source);
                    normalized = String.join(" ", tokens.stream().map(ComplementaryContract.Token::text).toList());
                }
                selected.put(name, normalized);
            }
        }
        return selected;
    }

    static String primaryFingerprint(FileSystem fs) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            fingerprintPart(digest, PRIMARY_FORMAT);
            for (var entry : primarySources(fs).entrySet()) {
                fingerprintPart(digest, entry.getKey());
                fingerprintPart(digest, entry.getValue());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void fingerprintPart(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    static boolean knownPrimary(FileSystem fs) {
        try (InputStream input = WorldDensityCache.class.getResourceAsStream("/resident-primary-interfaces.properties")) {
            if (input == null) return false;
            Properties expected = new Properties(); expected.load(input);
            if (!PRIMARY_FORMAT.equals(expected.getProperty("format"))) return false;
            String source = Files.readString(fs.getPath("/shaders/program/deferred1.glsl"));
            long calls = ComplementaryContract.tokens(source).stream().filter(t -> t.text().equals("GetClouds")).count();
            if (calls != 1) return false;
            ComplementaryContract.statementEnd(source, PRIMARY_CALL);
            String fingerprint = primaryFingerprint(fs);
            boolean known = expected.stringPropertyNames().stream()
                .filter(key -> key.startsWith("fingerprint."))
                .anyMatch(key -> fingerprint.equals(expected.getProperty(key)));
            if (!known && REPORTED.add("resident-interface:" + fs.getPath("/").toUri()))
                UnboundAdapter.LOGGER.info("Resident density path disabled: cloud caller/stage sources changed; generic density atlas retained");
            return known;
        } catch (IOException | IllegalArgumentException error) {
            return false;
        }
    }

    public static String defines(Path path) {
        return "\n#define CS_DENSITY_ATLAS_SIZE " + atlasSize(path)
            + "\n#define CS_DENSITY_RESIDENT_PRIMARY " + (residentPrimary(path) ? "1\n" : "0\n")
            + "#define CS_DENSITY_FULL_PERIOD " + (fullPeriod(path) ? "1\n" : "0\n");
    }

    // Whole-period shadow lookup is spatially independent of altitude, minY,
    // native/DH distance and reflection camera offsets. Keep its separate stage
    // guards in shadows.glsl: shadow-map rendering itself precedes prepare.
    static boolean fullPeriod(Path path) {
        return atlasSize(path) == 4096 && residentPrimary(path);
    }

    public static String virtualSource(Path path) {
        String file = path.toAbsolutePath().normalize().toString().replace('\\', '/');
        if (file.equals("/shaders/lib/cloudscapeDensityOptions.glsl") && fullPeriod(path))
            return producerOptions(path);
        if (!(file.equals("/shaders/world0/prepare.fsh") || file.equals("/shaders/world0/prepare.vsh")
            || file.equals("/shaders/world0/prepare.csh")) || !enabled(path)) return null;
        boolean compute = computeEnabled(path);
        if (compute != file.endsWith(".csh")) return null;
        if (REPORTED.add("stage:" + path.toUri())) UnboundAdapter.LOGGER.info("Registered Reimagined density atlas source: {}", path);
        if (compute) {
            int groups = (atlasSize(path) + 7) / 8;
            return "#version 330 core\n#extension GL_ARB_compute_shader : require\n#extension GL_ARB_shader_image_load_store : require\n"
                + "#define COMPUTE_SHADER\n#define OVERWORLD\n#define CS_DENSITY_FIELD_BUILD 1\n"
                + "const ivec3 workGroups = ivec3(" + groups + ", " + groups + ", 1);\n"
                + producerHeader(path) + "\n#define texture2DLod textureLod\n"
                + UnboundAdapter.settings().defines() + defines(path)
                + (fullPeriod(path) ? "const int cloudscapeDensityEpochProof = 92924096;\n" : "")
                + UnboundAdapter.resource("density-atlas.glsl")
                + UnboundAdapter.resource("density-atlas-build.comp.glsl").replace("csDensityOutput", "colorimg20");
        }
        if (file.endsWith(".vsh")) return "#version 130\nvoid main(){gl_Position=ftransform();}\n";
        return "#version 130\n#define FRAGMENT_SHADER\n#define OVERWORLD\n#define CS_DENSITY_FIELD_BUILD 1\n"
            + "#include \"/lib/common.glsl\"\n#ifdef CLOUDS_REIMAGINED\n" + UnboundAdapter.settings().defines() + defines(path)
            + UnboundAdapter.resource("density-atlas.glsl") + UnboundAdapter.resource("density-atlas-build.glsl")
            + "\n#else\nvoid main(){discard;}\n#endif\n";
    }

    static String producerHeader(Path path) {
        return fullPeriod(path)
            ? "#include \"/lib/cloudscapeDensityOptions.glsl\"\n" + UnboundAdapter.resource("density-producer-header.glsl")
            : "#include \"/lib/common.glsl\"\n";
    }

    static String producerOptions(Path path) {
        try {
            String common = Files.readString(path.getFileSystem().getPath("/shaders/lib/common.glsl"));
            String marker = "//Very Common Stuff//";
            int split = common.indexOf(marker);
            if (split < 0 || common.indexOf(marker, split + marker.length()) >= 0)
                throw new IllegalArgumentException("Ambiguous supported common option boundary");
            String prefix = common.substring(0, split).replace("\r\r\n", "\n").replace("\r\n", "\n");
            if (Pattern.compile("(?m)^\\s*uniform\\b").matcher(prefix).find())
                throw new IllegalArgumentException("Unexpected uniform in supported option prefix");
            return prefix;
        } catch (IOException error) {
            throw new IllegalArgumentException("Cannot read verified producer options", error);
        }
    }

    public static String attachmentMetadata(Path path, String original) {
        if (original == null || !path.toAbsolutePath().normalize().toString().replace('\\', '/')
            .equals("/shaders/world0/deferred1.fsh") || !computeEnabled(path)) return original;
        // Iris1.11.7 collects target format/clear directives from fragment text,
        // while compute text contributes only dispatch directives. This real,
        // fingerprint-verified fragment supplies metadata without a prepare quad
        // or any buffer flip. The original shader body and outputs are retained.
        return original + "\n#ifdef CLOUDS_REIMAGINED\n/* Cloudscape column-compute target metadata\nconst int colortex20Format = R32F;\n*/\n"
            + "const bool colortex20Clear = false;\n#endif\n";
    }

    public static String properties(Path root, String filename, String original) {
        if (original == null || !filename.equals("shaders.properties")
            || !root.toAbsolutePath().normalize().toString().replace('\\', '/').equals("/shaders") || !enabled(root)) return original;
        int size = atlasSize(root);
        if (REPORTED.add("attachment:" + root.toUri())) UnboundAdapter.LOGGER.info(
            "Configured Reimagined density atlas: {}x{}, R32F; primary specialization available={}, original range={} blocks",
            size * 8, size, residentPrimary(root), UnboundAdapter.settings().maxDistance);
        // These are real pack options, supplied to Iris's properties preprocessor.
        // Only Reimagined uses the field. Suppressing the entire producer keeps
        // target20 absent from ProgramSet and avoids Iris's two storage objects.
        return original + "\n# Cloudscape world density; full-resolution primary view\n"
            + "#if CLOUD_QUALITY > 0 && (CLOUD_STYLE_DEFINE == 1 || (CLOUD_STYLE_DEFINE == -1 && SHADER_STYLE == 1))\n"
            + "size.buffer.colortex20=" + size * 8 + " " + size + "\n"
            + "#else\nprogram.world0/prepare.enabled=false\n#endif\n";
    }
}
