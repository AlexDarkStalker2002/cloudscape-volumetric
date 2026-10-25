#ifndef INCLUDE_CLOUD_SHADOWS
#define INCLUDE_CLOUD_SHADOWS

// The atlas is built in prepare, after Iris shadow-map rendering. This explicit
// stage list keeps the texture-only helper out of early and unknown consumers.
#if defined DEFERRED1 || defined COMPOSITE || defined GBUFFERS_BASIC || defined GBUFFERS_BLOCK || defined GBUFFERS_ENTITIES || defined GBUFFERS_HAND || defined GBUFFERS_LIGHTNING || defined GBUFFERS_TERRAIN || defined GBUFFERS_TEXTURED || defined GBUFFERS_WATER || defined DH_TERRAIN || defined DH_WATER
    #define CS_SHADOW_POST_PREPARE 1
#endif
#ifndef CS_SHADOW_ATLAS_PERIODIC
    #ifdef CS_DENSITY_FULL_PERIOD
        #define CS_SHADOW_ATLAS_PERIODIC CS_DENSITY_FULL_PERIOD
    #else
        #define CS_SHADOW_ATLAS_PERIODIC 0
    #endif
#endif
#ifndef CS_SHADOW_ATLAS_BATCH
    #define CS_SHADOW_ATLAS_BATCH 1
#endif
#if defined OVERWORLD && defined CS_STYLE_REIMAGINED && defined CS_DENSITY_RESIDENT_PRIMARY && defined CS_SHADOW_POST_PREPARE && !defined CS_DENSITY_FIELD_BUILD && !defined SHADOW && !defined DH_SHADOW && !defined SHADOWCOMP && !defined BEGIN && !defined SETUP && !defined PREPARE
    #define CS_SHADOW_TEXTURE_AVAILABLE 1

    float CSShadowAtlasDensity(vec3 worldPos, float base, vec3 wind, ivec2 origin) {
        // Same subtraction, height rejection and voxel selection as the original
        // CSCloudDensity. Only repeated residency/config/time checks disappear.
        vec3 p = worldPos - wind;
        p.y -= base;
        if (p.y <= 0.0 || p.y >= CS_THICKNESS) return 0.0;
        ivec3 cell = ivec3(floor(p / CS_VOXEL_SIZE));
        ivec2 local = (cell.xz - origin) & ivec2(4095);
        return texelFetch(colortex20, ivec2(local.x + cell.y * CS_DENSITY_ATLAS_SIZE, local.y), 0).r;
    }

    bool CSShadowAtlasColumnAvailable(vec3 worldPos, vec3 direction, float base,
        int steps, vec3 wind, ivec2 origin) {
        // All altitude samples lie on this segment. Keep the final cell in the
        // SAME unwrapped atlas window as the first, rather than accepting two
        // unrelated periodic aliases around a hole in a smaller atlas.
        float altitude0 = base + CS_THICKNESS * 0.5 / float(steps);
        float altitude1 = base + CS_THICKNESS * (float(steps - 1) + 0.5) / float(steps);
        vec2 firstCell = floor(((worldPos + direction * ((altitude0 - worldPos.y) / direction.y)) - wind).xz / CS_VOXEL_SIZE);
        vec2 lastCell = floor(((worldPos + direction * ((altitude1 - worldPos.y) / direction.y)) - wind).xz / CS_VOXEL_SIZE);
        if (any(isnan(firstCell)) || any(isinf(firstCell)) || any(isnan(lastCell)) || any(isinf(lastCell))) return false;
        // The integer address expression must stay defined even for arbitrary
        // experimental callers. Normal Minecraft positions are far below this.
        if (any(greaterThan(abs(firstCell), vec2(100000000.0))) || any(greaterThan(abs(lastCell), vec2(100000000.0)))) return false;
        ivec2 first = ivec2(firstCell), last = ivec2(lastCell);
        ivec2 local0 = (first - origin) & ivec2(4095);
        ivec2 local1 = local0 + (last - first);
        return all(lessThan(local0, ivec2(CS_DENSITY_ATLAS_SIZE)))
            && all(greaterThanEqual(local1, ivec2(0))) && all(lessThan(local1, ivec2(CS_DENSITY_ATLAS_SIZE)));
    }
#endif

float GetCloudShadow(vec3 playerPos) {
    #ifndef OVERWORLD
        return 1.0;
    #else
        vec3 worldPos = playerPos + cameraPosition;
        vec3 direction = normalize(mat3(gbufferModelViewInverse) * lightVec);
        float base = float(cloudAlt1i) + CS_BASE_OFFSET;
        if (direction.y < 0.04 || worldPos.y >= base + CS_THICKNESS) return 1.0;
        float opticalDepth = 0.0;
        #ifdef CS_STYLE_REIMAGINED
            const int shadowSteps = int(ceil(CS_THICKNESS / CS_VOXEL_SIZE));
        #else
            const int shadowSteps = 3;
        #endif
        #ifdef CS_SHADOW_TEXTURE_AVAILABLE
            vec3 csShadowWind = CSWind();
            ivec2 csShadowOrigin = ivec2(CSDensityAtlasOrigin());
            bool csTexturePath = false;
            if (CS_VOXEL_SIZE == 16.0 && CS_THICKNESS <= 128.0) {
                #if CS_DENSITY_ATLAS_SIZE == 4096 && CS_SHADOW_ATLAS_PERIODIC == 1
                    // A complete 4096x4096 horizontal period has no off-atlas
                    // cells. Java enables this only for the verified full layout.
                    // No altitude, terrain height, DH radius or ray-range bound
                    // is used to justify this specialization.
                    csTexturePath = true;
                #elif CS_SHADOW_ATLAS_BATCH == 1
                    csTexturePath = CSShadowAtlasColumnAvailable(worldPos, direction, base,
                        shadowSteps, csShadowWind, csShadowOrigin);
                #endif
            }
            if (csTexturePath) {
                for (int j = 0; j < shadowSteps; ++j) {
                    float altitude = base + CS_THICKNESS * (float(j) + 0.5) / float(shadowSteps);
                    float distance = (altitude - worldPos.y) / direction.y;
                    if (distance > 0.0) opticalDepth += CSShadowAtlasDensity(worldPos + direction * distance,
                        base, csShadowWind, csShadowOrigin);
                }
            } else
        #endif
        {
            // Original fallback, including original plane locations, evaluation
            // order, uncapped range and non-Reimagined behavior.
            for (int j = 0; j < shadowSteps; ++j) {
                float altitude = base + CS_THICKNESS * (float(j) + 0.5) / float(shadowSteps);
                float distance = (altitude - worldPos.y) / direction.y;
                if (distance > 0.0) opticalDepth += CSCloudDensity(worldPos + direction * distance, base, false);
            }
        }
        float transmittance = exp(-opticalDepth * CS_THICKNESS / (float(shadowSteps) * max(direction.y, 0.15)) * CS_EXTINCTION * CS_DENSITY);
        return mix(0.28, 1.0, transmittance);
    #endif
}
#endif
