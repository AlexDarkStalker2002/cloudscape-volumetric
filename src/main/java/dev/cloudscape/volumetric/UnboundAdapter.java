package dev.cloudscape.volumetric;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.FileSystem;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Collections;
import java.util.WeakHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class UnboundAdapter {
    public static final Logger LOGGER = LoggerFactory.getLogger("Cloudscape Volumetric");
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    private static final Map<FileSystem, ComplementaryContract.Plan> SUPPORTED = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile CloudConfig config;
    private static volatile String diagnostic = "diagnostic.pending";

    private UnboundAdapter() {}

    public static synchronized void initialize() {
        if (config == null) {
            config = CloudConfig.load();
            LOGGER.info("Cloudscape 0.5.7-shapes.1: opt-in shapes/weather, column compute and exact density epoch reuse, {} view steps, {} lighting steps", config.viewSteps, config.lightSteps);
        }
    }

    public static String transform(Path path, String original) {
        // Iris also reads includes as relative ZIP paths during source discovery.
        String file = path.toAbsolutePath().normalize().toString().replace('\\', '/');
        String name = file.substring(file.lastIndexOf('/') + 1);
        if ((!name.equals("unboundClouds.glsl") && !name.equals("reimaginedClouds.glsl")
            && !name.equals("cloudShadows.glsl") && !name.equals("mainClouds.glsl")) || original == null) return original;
        if (!file.equals(ComplementaryContract.TARGETS.get(name))
            || !path.getFileSystem().provider().getScheme().equals("jar")) return original;
        String location = path.toUri().toString();
        initialize();
        if (!config.enabled) return original;
        ComplementaryContract.Plan plan = plan(path);
        if (!plan.compatible()) {
            diagnostic = "diagnostic.unsupported";
            if (REPORTED.add("unsupported-pack:" + location))
                LOGGER.warn("Original clouds retained: {} ({})", path, plan.reason());
            return original;
        }
        if (!original.equals(plan.sources().get(file))) {
            diagnostic = "diagnostic.changed";
            if (REPORTED.add("unsupported:" + location)) LOGGER.warn("Original clouds retained: source changed after interface validation: {}", path);
            return original;
        }
        boolean cache = WorldDensityCache.enabled(path);
        String density = config.defines() + (cache ? WorldDensityCache.defines(path) : "")
            + resource(cache ? "density-atlas.glsl" : "density.glsl");
        String replacement = switch (name) {
            case "unboundClouds.glsl", "reimaginedClouds.glsl" -> density + resource(cache ? "clouds-atlas.glsl" : "clouds.glsl");
            case "cloudShadows.glsl" -> density + resource("shadows.glsl");
            case "mainClouds.glsl" -> distantHorizonsDepth(original);
            default -> original;
        };
        if (REPORTED.add("patched:" + location)) LOGGER.info("Active ray-marched volume adapter: {}", path);
        diagnostic = "diagnostic.active";
        return replacement;
    }

    static CloudConfig settings() { if (config == null) initialize(); return config; }

    static String diagnostic() { return settings().enabled ? diagnostic : "diagnostic.disabled"; }

    public static boolean retainsWorldCache() {
        CloudConfig current = config;
        return current != null && current.enabled && current.reimaginedDensityCache && diagnostic.equals("diagnostic.active");
    }

    public static void finishPackLoad(boolean loaded, String name) {
        if (loaded && settings().enabled && diagnostic.equals("diagnostic.pending")) {
            diagnostic = "diagnostic.unsupported";
            LOGGER.warn("Cloudscape inactive: no supported ZIP source interface in shaderpack {}. Shaderpack clouds retained.", name);
        }
    }

    static synchronized void replaceSettings(CloudConfig next) {
        config = next.copy();
        resetPipeline();
    }

    public static synchronized void resetPipeline() {
        diagnostic = "diagnostic.pending";
        SUPPORTED.clear();
        REPORTED.clear();
        WorldDensityCache.reset();
    }

    private static ComplementaryContract.Plan plan(Path path) {
        synchronized (SUPPORTED) {
            return SUPPORTED.computeIfAbsent(path.getFileSystem(), ComplementaryContract::inspect);
        }
    }

    static boolean compatible(Path path) { return plan(path).compatible(); }

    private static String distantHorizonsDepth(String original) {
        String marker = "float lViewPosM = lViewPos < renderDistance * 1.5 ? lViewPos - 1.0 : 1000000000.0;";
        int end = ComplementaryContract.statementEnd(original, marker);
        // With DH, renderDistance is the LOD radius, while an empty native
        // depth pixel still reconstructs on the native far plane. Comparing
        // their lengths clips central sky rays and leaves peripheral rays
        // unbounded. Classify real surfaces by their depth samples instead.
        return original.substring(0, end) + "\n"
            + "    #ifdef DEFERRED1\n"
            + "        float csNativeDepth = texelFetch(depthtex0, texelCoord, 0).r;\n"
            + "        bool csHasGeometry = csNativeDepth >= 0.0 && csNativeDepth < 1.0;\n"
            + "        #ifdef DISTANT_HORIZONS\n"
            + "            float csDhDepth = texelFetch(dhDepthTex, texelCoord, 0).r;\n"
            + "            csHasGeometry = csHasGeometry || (csDhDepth > 0.0 && csDhDepth < 1.0);\n"
            + "        #endif\n"
            + "        lViewPosM = csHasGeometry ? max(0.0, lViewPos - 1.0) : 1000000000.0;\n"
            + "    #endif\n" + original.substring(end);
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static String resource(String name) {
        try (InputStream input = UnboundAdapter.class.getResourceAsStream("/assets/cloudscape_volumetric/" + name)) {
            if (input == null) throw new IOException("Missing resource " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("Cloudscape shader resources could not be loaded", error);
        }
    }

}
