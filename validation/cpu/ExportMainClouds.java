package dev.cloudscape.volumetric;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class ExportMainClouds {
    public static void main(String[] args) throws Exception {
        var field=UnboundAdapter.class.getDeclaredField("config");
        field.setAccessible(true);field.set(null,new CloudConfig());
        try(var pack=FileSystems.newFileSystem(Path.of(args[0]),Map.of())) {
            var path=pack.getPath("/shaders/lib/atmospherics/clouds/mainClouds.glsl");
            Files.writeString(Path.of(args[1]),UnboundAdapter.transform(path,Files.readString(path)));
        }
    }
}
