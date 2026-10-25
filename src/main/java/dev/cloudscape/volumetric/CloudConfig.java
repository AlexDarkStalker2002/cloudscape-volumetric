package dev.cloudscape.volumetric;

import java.util.Locale;

public final class CloudConfig {
    public boolean enabled = true;
    // Opt-in: existing and new installations retain the accepted field.
    public int shapeMode = 0;
    public boolean weatherVariability = true;
    public int transitionSeconds = 120;
    public int viewSteps = 96;
    public int reflectionSteps = 24;
    public int lightSteps = 5;
    public float coverage = 0.50f;
    public float density = 1.0f;
    public float thickness = 180.0f;
    public float reimaginedThickness = 128.0f;
    public float reimaginedEdgeSoftness = 0.75f;
    public int reimaginedClusterSize = 256;
    public float reimaginedTowerStrength = 0.85f;
    public float reimaginedLightContrast = 0.75f;
    public int reimaginedVoxelSize = 16;
    public float reimaginedMorphSpeed = 1.0f;
    public float reimaginedTransmission = 1.0f;
    public float baseOffset = 0.0f;
    public float detail = 0.34f;
    public float windSpeed = 0.65f;
    public float maxDistance = 6000.0f;
    public boolean reimaginedDensityCache = true;
    public int densityAtlasSize = 1536;

    static CloudConfig load() {
        return CloudSettings.initialize().copy();
    }

    CloudConfig copy() {
        return ConfigStore.GSON.fromJson(ConfigStore.GSON.toJson(this), CloudConfig.class);
    }

    private static float valid(float value, float low, float high, float fallback) {
        return Float.isFinite(value) ? Math.clamp(value, low, high) : fallback;
    }

    void validate() {
        shapeMode = shapeMode == 1 ? 1 : 0;
        transitionSeconds = Math.clamp(transitionSeconds, 10, 600);
        viewSteps = Math.clamp(viewSteps, 24, 192);
        reflectionSteps = Math.clamp(reflectionSteps, 8, 64);
        lightSteps = Math.clamp(lightSteps, 2, 8);
        coverage = valid(coverage, 0.1f, 0.95f, 0.50f);
        density = valid(density, 0.15f, 3.0f, 1.0f);
        thickness = valid(thickness, 60.0f, 360.0f, 180.0f);
        reimaginedThickness = valid(reimaginedThickness, 24.0f, 128.0f, 128.0f);
        // Power-of-two cells keep the density exactly periodic at the camera wrap.
        reimaginedEdgeSoftness = valid(reimaginedEdgeSoftness, 0.5f, 3.0f, 0.75f);
        reimaginedClusterSize = reimaginedClusterSize <= 128 ? 128 : reimaginedClusterSize <= 256 ? 256 : 512;
        reimaginedTowerStrength = valid(reimaginedTowerStrength, 0.0f, 1.0f, 0.85f);
        reimaginedLightContrast = valid(reimaginedLightContrast, 0.0f, 1.0f, 0.75f);
        reimaginedVoxelSize = reimaginedVoxelSize <= 4 ? 4 : reimaginedVoxelSize <= 8 ? 8 : 16;
        reimaginedMorphSpeed = valid(reimaginedMorphSpeed, 0.0f, 4.0f, 1.0f);
        reimaginedTransmission = valid(reimaginedTransmission, 0.3f, 2.0f, 1.0f);
        baseOffset = valid(baseOffset, -128.0f, 512.0f, 0.0f);
        detail = valid(detail, 0.0f, 0.5f, 0.34f);
        windSpeed = valid(windSpeed, 0.0f, 4.0f, 0.65f);
        maxDistance = valid(maxDistance, 1500.0f, 10000.0f, 6000.0f);
        densityAtlasSize = densityAtlasSize <= 256 ? 256 : densityAtlasSize <= 512 ? 512
            : densityAtlasSize <= 768 ? 768 : densityAtlasSize <= 1024 ? 1024 : densityAtlasSize <= 1536 ? 1536 : 4096;
    }

    String defines() {
        String artistic = shapeMode == 1 ? "#define CS_SHAPE_MODE 1\n#define CS_SHAPE_FAMILY "
            + Math.clamp(Integer.getInteger("cloudscape.validation.family", -1), -1, 3) + "\n" : "";
        return artistic + String.format(Locale.ROOT, """
            #ifndef CLOUDSCAPE_CONFIG_INCLUDED
            #define CLOUDSCAPE_CONFIG_INCLUDED
            #define CS_VIEW_STEPS %d
            #define CS_REFLECTION_STEPS %d
            #define CS_LIGHT_STEPS %d
            #define CS_COVERAGE %.6f
            #define CS_DENSITY %.6f
            #ifdef CLOUDS_REIMAGINED
                #define CS_STYLE_REIMAGINED
                #define CS_THICKNESS %.6f
                #define CS_EDGE_SOFTNESS %.6f
                #define CS_CLUSTER_SIZE %.6f
                #define CS_TOWER_STRENGTH %.6f
                #define CS_LIGHT_CONTRAST %.6f
                #define CS_VOXEL_SIZE %.6f
                #define CS_MORPH_SPEED %.6f
                #define CS_EXTINCTION %.6f
            #else
                #define CS_THICKNESS %.6f
                #define CS_EXTINCTION 0.035
            #endif
            #define CS_BASE_OFFSET %.6f
            #define CS_DETAIL %.6f
            #define CS_WIND_SPEED %.6f
            #define CS_MAX_DISTANCE %.6f
            #endif
            """, viewSteps, reflectionSteps, lightSteps, coverage, density,
            reimaginedThickness, reimaginedEdgeSoftness,
            (float) reimaginedClusterSize, reimaginedTowerStrength, reimaginedLightContrast,
            (float) reimaginedVoxelSize, reimaginedMorphSpeed, 0.075f / reimaginedTransmission,
            thickness, baseOffset, detail, windSpeed, maxDistance);
    }
}
