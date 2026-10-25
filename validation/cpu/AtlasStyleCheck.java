package dev.cloudscape.volumetric;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.preprocessor.PropertiesPreprocessor;

/** Executes Iris's real properties preprocessor, not a copy of its conditions. */
public final class AtlasStyleCheck {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("--export")) {
            try (var fs = FileSystems.newFileSystem(Path.of(args[1]))) {
                System.out.println("fingerprint=" + ComplementaryContract.atlasStyleFingerprint(Files.readString(fs.getPath("/shaders/lib/common.glsl"))));
            }
            return;
        }
        var config = UnboundAdapter.class.getDeclaredField("config"); config.setAccessible(true); config.set(null, new CloudConfig());
        WorldDensityCache.atlasProbe = requested -> new AtlasSupport.Result(requested,"atlas.ready");
        int checks = 0;
        for (String pack : args) try (var fs = FileSystems.newFileSystem(Path.of(pack))) {
            if (!WorldDensityCache.available(fs)) throw new AssertionError("Actual pack style contract failed"); checks++;
            String properties = WorldDensityCache.properties(fs.getPath("/shaders"),"shaders.properties", "");
            for (int shaderStyle : new int[]{1,4}) for (int cloudStyle : new int[]{-1,0,1,3,50}) for (int quality : new int[]{0,3}) {
                boolean wanted = quality > 0 && (cloudStyle == 1 || cloudStyle == -1 && shaderStyle == 1);
                String processed = PropertiesPreprocessor.preprocessSource(properties,List.of(
                    new StringPair("CLOUD_QUALITY",Integer.toString(quality)),
                    new StringPair("CLOUD_STYLE_DEFINE",Integer.toString(cloudStyle)),
                    new StringPair("SHADER_STYLE",Integer.toString(shaderStyle))));
                Properties actual = new Properties(); actual.load(new java.io.StringReader(processed));
                if (wanted != actual.containsKey("size.buffer.colortex20")) throw new AssertionError("Atlas allocation gating: " + processed);
                if (!wanted && !"false".equals(actual.getProperty("program.world0/prepare.enabled"))) throw new AssertionError("Unused producer enabled");
                checks += 2;
            }
        }
        System.out.println("PASS " + checks + " actual Iris style/quality preprocessor assertions; CPU only.");
    }
}
