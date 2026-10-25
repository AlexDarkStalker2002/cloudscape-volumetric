package dev.cloudscape.volumetric;

import java.lang.reflect.Field;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.HashMap;
import net.irisshaders.iris.shaderpack.parsing.ConstDirectiveParser;

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
                Files.writeString(changed, Files.readString(changed) + "\n// incompatible pack edit\n");
            }
            try (var zip = FileSystems.newFileSystem(modifiedPack, Map.of())) {
                boolean allOriginal = true;
                for (String file : new String[]{"/shaders/lib/atmospherics/clouds/reimaginedClouds.glsl", "/shaders/lib/atmospherics/clouds/unboundClouds.glsl", "/shaders/lib/lighting/cloudShadows.glsl",
                    "/shaders/lib/antialiasing/taa.glsl", "/shaders/program/composite6.glsl", "/shaders/lib/pipelineSettings.glsl", "/shaders/lib/atmospherics/rainbow.glsl", "/shaders/lib/atmospherics/volumetricLight/volumetricLight.glsl", "/shaders/program/deferred1.glsl"}) {
                    Path path = zip.getPath(file);
                    String code = Files.readString(path);
                    allOriginal &= UnboundAdapter.transform(path, code) == code;
                }
                require(allOriginal, "whole-pack fallback when " + changedFile + " changes");
                require(UnboundAdapter.virtualSource(zip.getPath("/shaders/world0/deferred.fsh"))==null, "virtual pass is absent when "+changedFile+" changes");
            }
        } finally {
            Files.deleteIfExists(modifiedPack);
            Files.deleteIfExists(temp);
        }
    }

    private static void requireReservedFallback(Path originalPack,String addedFile,String contents) throws Exception {
        Path temp=Files.createTempDirectory("cloudscape-reservation-");
        Path modifiedPack=temp.resolve("ComplementaryReimagined_r5.9.3.zip");
        try {
            Files.copy(originalPack,modifiedPack);
            try(var zip=FileSystems.newFileSystem(modifiedPack,Map.of())) {
                Path collision=zip.getPath(addedFile);Files.createDirectories(collision.getParent());Files.writeString(collision,contents);
            }
            try(var zip=FileSystems.newFileSystem(modifiedPack,Map.of())) {
                Path clouds=zip.getPath("/shaders/lib/atmospherics/clouds/reimaginedClouds.glsl");String original=Files.readString(clouds);
                require(UnboundAdapter.transform(clouds,original)==original && UnboundAdapter.virtualSource(zip.getPath("/shaders/world0/deferred.fsh"))==null,"reserved pass/buffer collision retains original pack: "+addedFile);
            }
        } finally {Files.deleteIfExists(modifiedPack);Files.deleteIfExists(temp);}
    }

    public static void main(String[] args) throws Exception {
        CloudConfig config = new CloudConfig();
        config.reimaginedClassicLook=true;
        require(config.defines().contains("#define CS_CLASSIC_LOOK 1")
            && config.defines().contains("#define CS_INTERIOR_DETAIL 0.000000"),
            "classic appearance keeps solid 0.5 voxels independent of the modern interior setting");
        config.reimaginedClassicLook=false;
        config.reimaginedClassicLook=true;config.reimaginedVariedShapes=true;
        require(config.defines().contains("#define CS_CLASSIC_LOOK 1") && config.defines().contains("#define CS_VARIED_SHAPES 1")
            && config.defines().contains("#define CS_INTERIOR_DETAIL 0.000000") && config.defines().contains("#define CS_LARGE_CLOUDS_ENABLED 1"),
            "varied and large shapes are independent of Classic lighting and solid interiors");
        config.reimaginedClassicLook=false;config.reimaginedVariedShapes=false;
        Map<String,String> parsedFormats=new HashMap<>();
        for(String resource:new String[]{"voxel_field_pass.glsl","halfres.glsl"}) {
            try(var input=AdapterCheck.class.getResourceAsStream("/assets/cloudscape_volumetric/"+resource)) {
                for(var directive:ConstDirectiveParser.findDirectives(new String(input.readAllBytes(),StandardCharsets.UTF_8)))
                    parsedFormats.put(directive.getKey(),directive.getValue());
            }
        }
        for(var expected:Map.of("colortex14Format","RGBA16F","colortex15Format","RGBA32F",
            "colortex20Format","RGBA16F","colortex21Format","RG16F","colortex22Format","RGBA16F").entrySet())
            require(expected.getValue().equals(parsedFormats.get(expected.getKey())),
                "Iris directive parser recognises "+expected.getKey()+"="+expected.getValue());
        Field configField = UnboundAdapter.class.getDeclaredField("config");
        configField.setAccessible(true);
        configField.set(null, config);
        for (String pack : args) try (var zip = FileSystems.newFileSystem(Path.of(pack), Map.of())) {
            boolean reimagined = pack.contains("Reimagined");
            Path clouds = zip.getPath("/shaders/lib/atmospherics/clouds/" + (reimagined ? "reimaginedClouds.glsl" : "unboundClouds.glsl"));
            String original = Files.readString(clouds);
            String replacement = UnboundAdapter.transform(clouds, original);
            require(replacement.contains("CSCloudDensity") && replacement.contains("GetVolumetricClouds"), "supported cloud source is adapted");
            Path relativeClouds = zip.getPath(clouds.toString().substring(1));
            require(UnboundAdapter.transform(relativeClouds, original).equals(replacement), "Iris relative cloud path matches the absolute archive path");
            require(replacement.contains("CS_REFLECTION_STEPS"), "reflection budget is separate");
            require(replacement.contains("CSVoxelWeight") && replacement.contains("CS_STYLE_REIMAGINED"), "3D voxel style is selected by the shader's cloud style");
            Path mainClouds=zip.getPath("/shaders/lib/atmospherics/clouds/mainClouds.glsl");
            require(UnboundAdapter.transform(mainClouds,Files.readString(mainClouds)).contains("lViewPosM = max(0.0, lViewPos - 1.0)"),
                "DH opaque terrain beyond the native radius remains a finite cloud endpoint");
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
            Path taa=zip.getPath("/shaders/lib/antialiasing/taa.glsl");
            String nativeTaa=Files.readString(taa), adaptedTaa=UnboundAdapter.transform(taa,nativeTaa);
            require(UnboundAdapter.transform(zip.getPath("shaders/lib/antialiasing/taa.glsl"), nativeTaa).equals(adaptedTaa), "Iris relative TAA path is adapted");
            require(adaptedTaa.contains("CSCloudReproject(prvCoord") && adaptedTaa.contains("min(blendFactor, csHistoryCap)"), "native TAA consumes cloud motion and limits history");
            require(adaptedTaa.contains("CSCloudHistoryDepthAccept") && adaptedTaa.contains("previousCameraPosition"), "cloud history rejects invalid depth and camera changes");
            Path formats=zip.getPath("/shaders/lib/pipelineSettings.glsl");
            String adaptedFormats=UnboundAdapter.transform(formats,Files.readString(formats));
            require(adaptedFormats.contains("colortex2Format = RGBA16F") && adaptedFormats.contains("colortex5Format = RGBA16F"), "existing native buffers preserve far cloud depth and history metadata");
            Path composite=zip.getPath("/shaders/program/composite6.glsl");
            require(UnboundAdapter.transform(composite,Files.readString(composite)).contains("CSCloudHistoryMetadata(z1)"), "native history output retains cloud metadata");
            Path rainbow=zip.getPath("/shaders/lib/atmospherics/rainbow.glsl");
            require(UnboundAdapter.transform(rainbow,Files.readString(rainbow)).contains("min(cloudLinearDepth, 1.0)"), "float cloud depth preserves rainbow distance contract");
            config.enabled=false;
            require(UnboundAdapter.transform(taa,nativeTaa)==nativeTaa, "disabled mod also restores native temporal rendering");
            config.enabled=true;
            Path virtualPath=zip.getPath("shaders/world0/deferred.fsh");
            String virtual=UnboundAdapter.virtualSource(virtualPath);
            require(reimagined?virtual!=null&&virtual.contains("CS_CLOUD_CACHE_PASS"):virtual==null,"virtual half-resolution pass is restricted to Reimagined");
            String adaptedDeferred=UnboundAdapter.transform(zip.getPath("/shaders/program/deferred1.glsl"),Files.readString(zip.getPath("/shaders/program/deferred1.glsl")));
            require(!reimagined || adaptedDeferred.contains("CSCloudViewPosition(texCoord, texelCoord, z)") && adaptedDeferred.contains("cloudSky ? -1.0 : length(v.xyz)"),
                "fast cache uses native/DH projection and real opaque-distance metadata");
            require(!Files.exists(virtualPath),"virtual shader source is not written into the archive");
            Path propertiesRoot=zip.getPath("shaders");
            String properties=Files.readString(propertiesRoot.resolve("shaders.properties"),StandardCharsets.ISO_8859_1);
            String adaptedProperties=UnboundAdapter.propertiesSource(propertiesRoot,"shaders.properties",properties);
            require(reimagined?adaptedProperties.contains("size.buffer.colortex14=0.5 0.5")&&adaptedProperties.contains("size.buffer.colortex15=0.5 0.5"):adaptedProperties==properties,"half-resolution attachment sizes are scoped to the active adapter");
            config.reimaginedHalfResolution=false;
            require(UnboundAdapter.virtualSource(virtualPath)==null&&UnboundAdapter.propertiesSource(propertiesRoot,"shaders.properties",properties)==properties,"full-resolution option disables virtual sources and attachment resizing");
            config.reimaginedHalfResolution=true;
            if (reimagined) {
                config.reimaginedUpperLayer=false;
                config.reimaginedVoxelCache=true;
                config.reimaginedResolutionDivisor=4;
                require(UnboundAdapter.virtualSource(zip.getPath("shaders/world0/prepare.fsh")).contains("CS_FIELD_DENSITY_PASS")
                    && UnboundAdapter.virtualSource(zip.getPath("shaders/world0/prepare1.fsh")).contains("CS_FIELD_LIGHT_PASS"),
                    "density and light field passes are exposed together");
                require(UnboundAdapter.virtualSource(zip.getPath("shaders/world0/prepare2.fsh")).contains("CS_REFLECTION_CACHE_PASS"),
                    "reflection cache is prepared before translucent geometry");
                config.reimaginedCachedReflections=false;
                require(UnboundAdapter.virtualSource(zip.getPath("shaders/world0/prepare2.fsh"))==null,
                    "reflection approximation can be disabled independently of the world field");
                config.reimaginedCachedReflections=true;
                String fieldProperties=UnboundAdapter.propertiesSource(propertiesRoot,"shaders.properties",properties);
                require(fieldProperties.contains("size.buffer.colortex14=0.25 0.25")
                    && fieldProperties.contains("size.buffer.colortex20=2048 256")
                    && fieldProperties.contains("size.buffer.colortex21=2048 256"),
                    "world atlas has fixed dimensions and separate quarter-resolution view targets");
                config.enabled=false;
                require(UnboundAdapter.virtualSource(zip.getPath("shaders/world0/prepare.fsh"))==null,
                    "disabled mod removes world-field passes");
                config.enabled=true;
                config.reimaginedVoxelSize=8;
                require(UnboundAdapter.virtualSource(zip.getPath("shaders/world0/prepare.fsh"))==null
                    && config.defines().contains("#define CS_VOXEL_CACHE 0"),
                    "unsupported voxel layout retains procedural rendering without a mismatched atlas");
                config.reimaginedVoxelSize=16;
                config.reimaginedUpperLayer=true;
                config.reimaginedVoxelCache=false;
                config.reimaginedResolutionDivisor=2;
            }
            Path impostor=zip.getPath("/shaders/other/taa.glsl");
            require(UnboundAdapter.transform(impostor,nativeTaa)==nativeTaa, "same-named files outside pinned paths are retained");
            require(UnboundAdapter.transform(zip.getPath("shaders/other/taa.glsl"),nativeTaa)==nativeTaa, "same-named relative files outside pinned paths are retained");
        }
        requireWholePackFallback(Path.of(args[1]), "/shaders/lib/common.glsl");
        requireWholePackFallback(Path.of(args[1]), "/shaders/lib/lighting/cloudShadows.glsl");
        requireWholePackFallback(Path.of(args[1]), "/shaders/lib/antialiasing/taa.glsl");
        requireWholePackFallback(Path.of(args[1]), "/shaders/program/composite6.glsl");
        requireWholePackFallback(Path.of(args[1]), "/shaders/lib/pipelineSettings.glsl");
        requireWholePackFallback(Path.of(args[1]), "/shaders/lib/atmospherics/rainbow.glsl");
        requireWholePackFallback(Path.of(args[1]), "/shaders/program/deferred1.glsl");
        requireWholePackFallback(Path.of(args[1]), "/shaders/lib/uniforms.glsl");
        requireWholePackFallback(Path.of(args[1]), "/shaders/lib/misc/lensFlare.glsl");
        requireWholePackFallback(Path.of(args[1]), "/shaders/program/gbuffers_water.glsl");
        requireWholePackFallback(Path.of(args[1]), "/shaders/program/gbuffers_textured.glsl");
        requireWholePackFallback(Path.of(args[1]), "/shaders/program/dh_water.glsl");
        requireWholePackFallback(Path.of(args[1]), "/shaders/program/composite.glsl");
        requireWholePackFallback(Path.of(args[1]), "/shaders/lib/atmospherics/volumetricLight/volumetricLight.glsl");
        requireWholePackFallback(Path.of(args[1]), "/shaders/shaders.properties");
        requireReservedFallback(Path.of(args[1]),"/shaders/world0/deferred.fsh","#version 130\nvoid main(){}\n");
        requireReservedFallback(Path.of(args[1]),"/shaders/lib/foreign-cache.glsl","uniform sampler2D colortex14;\n");
        requireReservedFallback(Path.of(args[1]),"/shaders/world0/prepare1.fsh","#version 130\nvoid main(){}\n");
        requireReservedFallback(Path.of(args[1]),"/shaders/lib/foreign-field.glsl","uniform sampler2D colortex20;\n");
        requireReservedFallback(Path.of(args[1]),"/shaders/world0/prepare2.fsh","#version 130\nvoid main(){}\n");
        requireReservedFallback(Path.of(args[1]),"/shaders/lib/foreign-reflection.glsl","uniform sampler2D colortex22;\n");
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
        config.reimaginedInteriorDetail=Float.NaN;
        config.reimaginedWeatherStrength=10.0f;
        config.reimaginedWeatherSpeed=-2.0f;
        config.reimaginedUpperThickness=Float.POSITIVE_INFINITY;
        config.reimaginedLayerGap=1000;
        config.reimaginedWindShear=-1;
        config.reimaginedFullLightingDistance=Float.NaN;
        config.validate();
        require(Float.isFinite(config.coverage) && Float.isFinite(config.density) && config.viewSteps == 192, "invalid rendering settings are bounded");
        require(config.reimaginedThickness == 128 && config.reimaginedEdgeSoftness == 0.75f, "stylised settings retain finite dimensions");
        require(config.reimaginedClusterSize == 512 && config.reimaginedTowerStrength == 0.85f && config.reimaginedLightContrast == 1.0f, "cluster and lighting settings remain bounded");
        require(config.reimaginedVoxelSize == 8 && config.reimaginedMorphSpeed == 1.0f && config.reimaginedTransmission == 0.3f, "voxel, morph and transmission settings remain bounded");
        require(config.reimaginedInteriorDetail==.65f && config.reimaginedWeatherStrength==1 && config.reimaginedWeatherSpeed==0, "interior and weather settings remain finite and bounded");
        require(config.reimaginedUpperThickness==64 && config.reimaginedLayerGap==256 && config.reimaginedWindShear==0, "upper layer and shear dimensions remain finite and bounded");
        require(config.reimaginedFullLightingDistance==1024 && config.defines().contains("#undef DOUBLE_REIM_CLOUDS"), "distant lighting and managed layer contract remain bounded");
    }
}
