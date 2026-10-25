package dev.cloudscape.volumetric;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Runs unchanged against both the old and new adapter classes in separate JVMs. */
public final class ExportShaderSet {
    public static void main(String[] args) throws Exception {
        var field = UnboundAdapter.class.getDeclaredField("config");
        field.setAccessible(true); field.set(null, new CloudConfig());
        Path output = Path.of(args[1]); Files.createDirectories(output);
        try (var fs = FileSystems.newFileSystem(Path.of(args[0]), Map.of())) {
            for (String file : List.of("atmospherics/clouds/unboundClouds.glsl", "atmospherics/clouds/reimaginedClouds.glsl",
                "lighting/cloudShadows.glsl", "atmospherics/clouds/mainClouds.glsl")) {
                Path path = fs.getPath("/shaders/lib/" + file);
                Files.writeString(output.resolve(path.getFileName().toString()),
                    UnboundAdapter.transform(path, Files.readString(path)));
            }
        }
    }
}
