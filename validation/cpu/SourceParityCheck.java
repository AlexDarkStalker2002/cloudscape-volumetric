package dev.cloudscape.volumetric;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipFile;
import java.net.URL;
import java.net.URLClassLoader;
import java.io.File;

/** Original defaults and all six GLSL assets compared to the actual 0.5.4 JAR. */
public final class SourceParityCheck {
    public static void main(String[] args) throws Exception {
        int assets = 0;
        try (ZipFile baseline = new ZipFile(args[0])) {
            for (var file : baseline.stream().filter(e -> e.getName().startsWith("assets/cloudscape_volumetric/") && e.getName().endsWith(".glsl")).toList()) {
                String entry = file.getName(); assets++;
                try (var old = baseline.getInputStream(baseline.getEntry(entry)); var current = SourceParityCheck.class.getResourceAsStream("/" + entry)) {
                    if(current==null)throw new AssertionError("Missing "+entry);
                    String before=new String(old.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).replace("\r\n","\n");
                    String after=new String(current.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).replace("\r\n","\n");
                    // The new mode has its own implementation. Its explicit
                    // Classic branch must still restore the exact old asset.
                    if(after.contains("// BEGIN VARIED OPTIMIZATION\n")) {
                        var branch=java.util.regex.Pattern.compile("(?s)// BEGIN VARIED OPTIMIZATION\\n#if CS_SHAPE_MODE == 1\\n.*?// CLASSIC ORIGINAL\\n#else\\n(.*?)#endif\\n// END VARIED OPTIMIZATION\\n");
                        if(branch.matcher(after).results().count()!=1)throw new AssertionError("Ambiguous Classic guard: "+entry);
                        after=branch.matcher(after).replaceAll("$1");
                    }
                    if(entry.endsWith("/density.glsl") || entry.endsWith("/density-atlas.glsl")) {
                        after=after.replaceAll("(?s)// BEGIN GENERATED SHAPES: tools/sync-field.py\n.*?// END GENERATED SHAPES\n\n", "");
                        after=after.replace("    #if CS_SHAPE_MODE == 1 && defined CS_STYLE_REIMAGINED\n        return vec3(csWind.x, 0.0, csWind.y);\n    #else\n", "");
                        after=after.replace("    #if CS_SHAPE_MODE == 1\n        return csMorph;\n    #else\n", "");
                        after=after.replace("    #if CS_SHAPE_MODE == 1\n        return CSVariedVoxelWeight(cell, time);\n    #else\n", "");
                        after=after.replace("return vec3(t * 0.72, 0.0, -t * 0.33) * CS_WIND_SPEED;\n    #endif", "return vec3(t * 0.72, 0.0, -t * 0.33) * CS_WIND_SPEED;");
                        after=after.replace("return frameTimeCounter * float(CLOUD_SPEED_MULT) * 0.00008 * CS_MORPH_SPEED;\n    #endif\n    #endif", "return frameTimeCounter * float(CLOUD_SPEED_MULT) * 0.00008 * CS_MORPH_SPEED;\n    #endif");
                        after=after.replace("return smoothstep(-band, band, envelope + sculpt - 0.12);\n    #endif", "return smoothstep(-band, band, envelope + sculpt - 0.12);");
                    }
                    if(!before.equals(after))throw new AssertionError("Classic field/optics changed: "+entry);
                }
            }
        }
        var paths = System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator));
        URL[] urls = new URL[paths.length + 1]; urls[0] = Path.of(args[0]).toUri().toURL();
        for (int i = 0; i < paths.length; i++) urls[i+1] = Path.of(paths[i]).toUri().toURL();
        try (var loader = new URLClassLoader(urls,null)) {
            var oldType = loader.loadClass("dev.cloudscape.volumetric.CloudConfig");
            var old = oldType.getConstructor().newInstance();
            var defines = oldType.getDeclaredMethod("defines"); defines.setAccessible(true);
            if (!defines.invoke(old).equals(new CloudConfig().defines())) throw new AssertionError("Original defaults/defines changed");
            for (var field : oldType.getFields())
                if (!CloudConfig.class.getField(field.getName()).get(new CloudConfig()).equals(field.get(old))) throw new AssertionError("Default changed: " + field.getName());
        }
        System.out.println("PASS " + assets + " shader assets (Classic branch restored verbatim), every accepted default and exact Classic defines match baseline JAR; CPU only.");
    }
}
