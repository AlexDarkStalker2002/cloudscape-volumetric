package dev.cloudscape.volumetric;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.UnaryOperator;

/** Synthetic updates exercise the compatibility boundary, not nonexistent released pack versions. */
public final class InterfaceCheck {
    private static int checks;
    private static final String COMMON = "/shaders/lib/common.glsl";
    private static final String UNIFORMS = "/shaders/lib/uniforms.glsl";
    private static final String MAIN = ComplementaryContract.TARGETS.get("mainClouds.glsl");
    private static final String REIM = ComplementaryContract.TARGETS.get("reimaginedClouds.glsl");

    private static void require(boolean ok, String description) {
        if (!ok) throw new AssertionError(description);
        checks++; System.out.println("PASS " + description);
    }

    private static void fixture(Path pack, String description, String file, UnaryOperator<String> edit,
        boolean compatible) throws Exception {
        Path directory = Files.createTempDirectory("cloudscape-interface-");
        Path copy = directory.resolve("synthetic-update-without-version-lock.zip");
        try {
            Files.copy(pack, copy);
            if (file != null) try (var fs = FileSystems.newFileSystem(copy, Map.of())) {
                Path target = fs.getPath(file);
                String before = Files.readString(target), after = edit.apply(before);
                require(!before.equals(after), description + " fixture is changed");
                Files.writeString(target, after);
            }
            try (var fs = FileSystems.newFileSystem(copy, Map.of())) {
                ComplementaryContract.Plan plan = ComplementaryContract.inspect(fs);
                require(plan.compatible() == compatible, description + " contract: " + plan.reason());
                boolean allExpected = true;
                for (String target : ComplementaryContract.TARGETS.values()) {
                    Path path = fs.getPath(target);
                    String original = Files.readString(path), adapted = UnboundAdapter.transform(path, original);
                    allExpected &= compatible ? !adapted.equals(original) : adapted == original;
                }
                require(allExpected, description + (compatible ? " adapts all four includes" : " retains all four includes"));
            }
        } finally {
            Files.deleteIfExists(copy); Files.deleteIfExists(directory);
        }
    }

    public static void main(String[] args) throws Exception {
        WorldDensityCache.atlasProbe = requested -> new AtlasSupport.Result(requested,"atlas.ready");
        var field = UnboundAdapter.class.getDeclaredField("config");
        field.setAccessible(true); field.set(null, new CloudConfig());
        for (String arg : args) {
            Path pack = Path.of(arg);
            fixture(pack, "renamed archive with no version check", null, null, true);
            fixture(pack, "common comments and changed speed default", COMMON,
                s -> "// Synthetic future update\n" + s.replace("#define CLOUD_SPEED_MULT 100", "#define CLOUD_SPEED_MULT 120"), true);
            fixture(pack, "cloud algorithm update with unchanged entry", REIM,
                s -> s.replace("const float cloudStretch = 4.2;", "const float cloudStretch = 5.1;")
                    .replace("float stepMult =", "/* new implementation */ float stepMult ="), true);
            fixture(pack, "renamed parameters in replaced cloud function", REIM,
                s -> s.replace("distanceThreshold", "newDistanceThreshold"), true);
            fixture(pack, "sky implementation update with unchanged interface", "/shaders/lib/atmospherics/sky.glsl",
                s -> s + "\nfloat FutureSkyHelper() { return 0.5; }\n", true);
            fixture(pack, "color formula update", "/shaders/lib/colors/cloudColors.glsl",
                s -> s.replace("vec3 cloudAmbientColor = mix(", "vec3 cloudAmbientColor = 0.95 * mix("), true);
            fixture(pack, "wrapper comments and whitespace", MAIN,
                s -> "// float lViewPosM = lViewPos < renderDistance * 1.5 ? lViewPos - 1.0 : 1000000000.0;\n"
                    + s.replace("float lViewPosM =", "float\t lViewPosM /* compatible edit */ =").replace("\n", "\r\n"), true);
            fixture(pack, "cloud entry parameter type changed", REIM,
                s -> s.replace("float distanceThreshold", "vec2 distanceThreshold"), false);
            fixture(pack, "noise sampler removed", UNIFORMS,
                s -> s.replace("uniform sampler2D noisetex;", "// uniform sampler2D noisetex;"), false);
            fixture(pack, "depth sampler type changed", UNIFORMS,
                s -> s.replace("uniform sampler2D dhDepthTex;", "uniform sampler2DArray dhDepthTex;"), false);
            fixture(pack, "dependency hidden behind new condition", UNIFORMS,
                s -> s.replace("uniform sampler2D noisetex;", "#ifdef FUTURE_UNAVAILABLE\nuniform sampler2D noisetex;\n#endif"), false);
            fixture(pack, "sky entry signature changed", "/shaders/lib/atmospherics/sky.glsl",
                s -> s.replace("bool doGround", "int doGround"), false);
            fixture(pack, "changed depth endpoint semantics", MAIN,
                s -> s.replace("renderDistance * 1.5", "renderDistance * 2.0"), false);
            fixture(pack, "changed coordinate interpretation", COMMON,
                s -> s.replace("vec3 cameraPositionBestFract = cameraPositionFract;", "vec3 cameraPositionBestFract = cameraPositionFract * 2.0;"), false);
            fixture(pack, "changed cloud style mapping", COMMON,
                s -> s.replace("#if CLOUD_STYLE == 1", "#if CLOUD_STYLE == 2"), false);
            fixture(pack, "token boundaries are significant", MAIN,
                s -> s.replace("float skyMult0", "floatskyMult0"), false);
        }
        String marker = "float lViewPosM = lViewPos < renderDistance * 1.5 ? lViewPos - 1.0 : 1000000000.0;";
        String commented = "// " + marker + "\n" + marker;
        require(ComplementaryContract.statementEnd(commented, marker) == commented.length(), "endpoint lookup skips a matching comment");
        boolean duplicateRejected = false;
        try { ComplementaryContract.statementEnd(marker + "\n" + marker, marker); }
        catch (IllegalArgumentException expected) { duplicateRejected = true; }
        require(duplicateRejected, "ambiguous endpoint statements are rejected");
        System.out.println("PASS interface cases=" + checks);
    }
}
