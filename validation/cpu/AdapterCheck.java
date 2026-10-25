package dev.cloudscape.volumetric;

import java.lang.reflect.Field;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class AdapterCheck {
    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        System.out.println("PASS " + description);
    }

    private static void requireWholePackFallback(Path originalPack, String changedFile) throws Exception {
        Path temp = Files.createTempDirectory("cloudscape-compat-");
        Path modifiedPack = temp.resolve("ComplementaryReimagined_r5.9.3.zip");
        try {
            Files.copy(originalPack, modifiedPack);
            try (var zip = FileSystems.newFileSystem(modifiedPack, Map.of())) {
                Path changed = zip.getPath(changedFile);
                String source = Files.readString(changed);
                String broken = changedFile.endsWith("common.glsl") ? source.replace("float syncedTime =", "vec2 syncedTime =")
                    : changedFile.endsWith("cloudShadows.glsl") ? source.replace("GetCloudShadow(vec3 playerPos)", "GetCloudShadow(vec2 playerPos)")
                    : source.replace("renderDistance * 1.5", "renderDistance * 1.8");
                require(!broken.equals(source), "incompatible fixture actually changes " + changedFile);
                Files.writeString(changed, broken);
            }
            try (var zip = FileSystems.newFileSystem(modifiedPack, Map.of())) {
                boolean allOriginal = true;
                for (String file : new String[]{"/shaders/lib/atmospherics/clouds/reimaginedClouds.glsl", "/shaders/lib/atmospherics/clouds/unboundClouds.glsl", "/shaders/lib/lighting/cloudShadows.glsl", "/shaders/lib/atmospherics/clouds/mainClouds.glsl"}) {
                    Path path = zip.getPath(file);
                    String code = Files.readString(path);
                    allOriginal &= UnboundAdapter.transform(path, code) == code;
                }
                require(allOriginal, "whole-pack fallback when " + changedFile + " changes");
            }
        } finally {
            Files.deleteIfExists(modifiedPack);
            Files.deleteIfExists(temp);
        }
    }

    public static void main(String[] args) throws Exception {
        WorldDensityCache.atlasProbe = requested -> new AtlasSupport.Result(requested,"atlas.ready");
        CloudConfig config = new CloudConfig();
        Field configField = UnboundAdapter.class.getDeclaredField("config");
        configField.setAccessible(true);
        configField.set(null, config);
        for (String pack : args) try (var zip = FileSystems.newFileSystem(Path.of(pack), Map.of())) {
            boolean reimagined = pack.contains("Reimagined");
            Path clouds = zip.getPath("/shaders/lib/atmospherics/clouds/" + (reimagined ? "reimaginedClouds.glsl" : "unboundClouds.glsl"));
            String original = Files.readString(clouds);
            String replacement = UnboundAdapter.transform(clouds, original);
            require(replacement.contains("CSCloudDensity") && replacement.contains("GetVolumetricClouds"), "supported cloud source is adapted");
            Path relativeClouds = zip.getPath("shaders/lib/atmospherics/clouds/" + clouds.getFileName());
            require(UnboundAdapter.transform(relativeClouds, original).equals(replacement), "Iris relative ZIP paths use the same adapter");
            require(replacement.contains("CS_REFLECTION_STEPS"), "reflection budget is separate");
            require(replacement.contains("CSVoxelWeight") && replacement.contains("CS_STYLE_REIMAGINED"), "3D voxel style is selected by the shader's cloud style");
            String modified = original + "\n// unexpected pack update\n";
            require(UnboundAdapter.transform(clouds, modified) == modified, "unexpected shader changes are retained");
            Path unrelated = zip.getPath("/shaders/lib/common.glsl");
            String common = Files.readString(unrelated);
            require(UnboundAdapter.transform(unrelated, common) == common, "unrelated shader code is retained");
            require(UnboundAdapter.transform(Path.of("other-pack/shaders/lib/atmospherics/clouds/unboundClouds.glsl"), original) == original, "other shader packs are not modified");
            config.enabled = false;
            require(UnboundAdapter.transform(clouds, original) == original, "disabled mod restores original rendering");
            config.enabled = true;
            Path shadows = zip.getPath("/shaders/lib/lighting/cloudShadows.glsl");
            require(UnboundAdapter.transform(shadows, Files.readString(shadows)).contains("CSCloudDensity"), "ground shadows use the volume's density field");
            require(Files.readString(clouds).equals(original), "original shader ZIP is unchanged");
            Path mainClouds = zip.getPath("/shaders/lib/atmospherics/clouds/mainClouds.glsl");
            String originalMain = Files.readString(mainClouds);
            String adaptedMain = UnboundAdapter.transform(mainClouds, originalMain);
            require(adaptedMain.contains("#ifdef DEFERRED1"), "depth classification is limited to the primary cloud view");
            require(adaptedMain.contains("csDhDepth > 0.0 && csDhDepth < 1.0"), "sky pixels keep the original unbounded path");
            require(adaptedMain.contains("csHasGeometry ? max(0.0, lViewPos - 1.0) : 1000000000.0"), "native far-plane sky cannot become an opaque endpoint under the enlarged DH radius");
            config.enabled = false;
            require(UnboundAdapter.transform(mainClouds, originalMain) == originalMain, "disabled mod restores the native DH cloud wrapper");
            config.enabled = true;
        }
        requireWholePackFallback(Path.of(args[1]), "/shaders/lib/common.glsl");
        requireWholePackFallback(Path.of(args[1]), "/shaders/lib/lighting/cloudShadows.glsl");
        requireWholePackFallback(Path.of(args[1]), "/shaders/lib/atmospherics/clouds/mainClouds.glsl");
        config.coverage = Float.NaN;
        config.density = Float.POSITIVE_INFINITY;
        config.viewSteps = Integer.MAX_VALUE;
        config.reimaginedThickness = Float.NaN;
        config.reimaginedEdgeSoftness = Float.POSITIVE_INFINITY;
        config.reimaginedClusterSize = 300;
        config.reimaginedTowerStrength = Float.NaN;
        config.reimaginedLightContrast = 10.0f;
        config.reimaginedVoxelSize = 7;
        config.reimaginedMorphSpeed = Float.NaN;
        config.reimaginedTransmission = -4.0f;
        config.validate();
        require(Float.isFinite(config.coverage) && Float.isFinite(config.density) && config.viewSteps == 192, "invalid rendering settings are bounded");
        require(config.reimaginedThickness == 128 && config.reimaginedEdgeSoftness == 0.75f, "stylised settings retain finite dimensions");
        require(config.reimaginedClusterSize == 512 && config.reimaginedTowerStrength == 0.85f && config.reimaginedLightContrast == 1.0f, "cluster and lighting settings remain bounded");
        require(config.reimaginedVoxelSize == 8 && config.reimaginedMorphSpeed == 1.0f && config.reimaginedTransmission == 0.3f, "voxel, morph and transmission settings remain bounded");
    }
}
