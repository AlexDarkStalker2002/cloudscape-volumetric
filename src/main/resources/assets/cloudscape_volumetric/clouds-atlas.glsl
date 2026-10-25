// BEGIN VARIED OPTIMIZATION
#if CS_SHAPE_MODE == 1
// Both Complementary styles use this interface but wrap camera coordinates differently.
#ifdef CS_STYLE_REIMAGINED
    const float cloudNarrowness = 256.0 / 65536.0;
#else
    const float cloudNarrowness = 1.0 / 65536.0;
#endif
const float cloudStretch = CS_THICKNESS * 0.5;
const float cloudTallness = CS_THICKNESS;

// Java verifies the complete pack sources and layout. The primary caller uses
// its proven finite range; verified reflection stages require a complete period.
// GLSL float config literals cannot be compared in preprocessor expressions.
#if defined CS_STYLE_REIMAGINED && !defined CS_DENSITY_FIELD_BUILD && !defined SHADOW && !defined DH_SHADOW && !defined SHADOWCOMP && !defined BEGIN && !defined SETUP && !defined PREPARE
    #if defined DEFERRED1 && CS_DENSITY_RESIDENT_PRIMARY == 1
        #define CS_CLOUD_RESIDENT_CALL 1
    #elif CS_DENSITY_ATLAS_SIZE == 4096 && CS_DENSITY_FULL_PERIOD == 1 && (defined COMPOSITE || defined GBUFFERS_WATER || defined DH_WATER)
        // A complete horizontal period also covers reflected camera offsets.
        // These are the verified r5.9.3 post-prepare reflection callers. Keep
        // unknown stages and every smaller layout on the generic call tree.
        #define CS_CLOUD_RESIDENT_CALL 1
    #endif
#endif
#ifdef CS_CLOUD_RESIDENT_CALL
    #define CSVoxelWeight CSResidentVoxelWeight
    #define CSVoxelSkyAccess CSResidentVoxelSkyAccess
    #define CSLightTransmittance CSResidentLightTransmittance
    #define CSVoxelLightDepth CSResidentVoxelLightDepth
    #define CSCloudDensity CSResidentCloudDensity
#endif
vec4 GetVolumetricClouds(int cloudAltitude, float distanceThreshold, inout float cloudLinearDepth,
    float skyFade, float skyMult0, vec3 cameraPos, vec3 ray, float sceneDistance,
    float VdotS, float VdotU, float dither) {

    float base = float(cloudAltitude) + CS_BASE_OFFSET;
    float top = base + CS_THICKNESS;
    float maxDistance = CS_MAX_DISTANCE;
    // Stop the volume at actual opaque geometry, including mountain silhouettes.
    if (sceneDistance < 10000000.0) maxDistance = min(maxDistance, max(0.0, sceneDistance));
    float enter = 0.0;
    float leave = maxDistance;
    if (abs(ray.y) < 0.00001) {
        if (cameraPos.y <= base || cameraPos.y >= top) return vec4(0.0);
    } else {
        float t0 = (base - cameraPos.y) / ray.y;
        float t1 = (top - cameraPos.y) / ray.y;
        enter = max(0.0, min(t0, t1));
        leave = min(maxDistance, max(t0, t1));
    }
    if (leave <= enter) return vec4(0.0);

    #ifdef DEFERRED1
        #ifdef CS_STYLE_REIMAGINED
            const int exactSteps = CS_VIEW_STEPS * 2;
            const int maxSteps = exactSteps + 16;
        #else
            const int maxSteps = CS_VIEW_STEPS;
        #endif
    #else
        #ifdef CS_STYLE_REIMAGINED
            const int exactSteps = CS_REFLECTION_STEPS * 2;
            const int maxSteps = exactSteps + 16;
        #else
            const int maxSteps = CS_REFLECTION_STEPS;
        #endif
    #endif
    #ifdef CS_STYLE_REIMAGINED
        const float targetStride = 2.0;
    #else
        const float targetStride = 18.0;
    #endif
    int count = int(clamp(ceil((leave - enter) / targetStride), 8.0, float(maxSteps)));
    float stride = (leave - enter) / float(count);
    float t = enter + stride * clamp(dither, 0.001, 0.999);
    vec3 lightDirection = normalize(mat3(gbufferModelViewInverse) * lightVec);
    float mu = dot(ray, lightDirection);
    #ifdef CS_STYLE_REIMAGINED
        float phase = 0.78 * CSPhase(mu, 0.65) + 0.22 * CSPhase(mu, -0.20);
        float boundedPhase = phase / (1.0 + 0.18 * phase);
    #else
        float phase = 0.68 * CSPhase(mu, 0.58) + 0.32 * CSPhase(mu, -0.22);
    #endif
    vec3 skyColor = GetSky(VdotU, VdotS, dither, isEyeInWater == 0, false);
    #ifdef ATM_COLOR_MULTS
        skyColor *= sqrtAtmColorMult;
    #endif
    vec3 radiance = vec3(0.0);
    float transmittance = 1.0;
    float firstHit = leave;
    bool hasHit = false;
    #ifdef CS_STYLE_REIMAGINED
        float faceLight = 1.0;
        float previousDensity = 0.0;
        float morph = CSMorphTime();
        CSVoxelRay voxelRay = CSStartVoxelRay(cameraPos, ray, base, enter);
        float voxelT = enter;
    #endif
    for (int i = 0; i < maxSteps; ++i) {
        #ifdef CS_STYLE_REIMAGINED
            if (voxelT >= leave || transmittance < 0.012) break;
            // Traverse exact cube faces nearby. A bounded far-field tail keeps
            // horizontal views and reflections continuous beyond the DDA budget.
            float end = i < exactSteps ? min(leave, CSNextVoxel(voxelRay))
                : voxelT + (leave - voxelT) / float(maxSteps - i);
            stride = max(0.0, end - voxelT);
            t = voxelT + stride * 0.5;
        #else
            if (i >= count || transmittance < 0.012) break;
        #endif
        vec3 p = cameraPos + ray * t;
        #ifdef CS_STYLE_REIMAGINED
            float density = i < exactSteps ? CSVoxelWeight(voxelRay.cell, morph) : CSCloudDensity(p, base, false);
        #else
            float density = CSCloudDensity(p, base, true);
        #endif
        if (density > 0.001) {
            if (!hasHit) {
                firstHit = t; hasHit = true;
            }
            #ifdef CS_STYLE_REIMAGINED
                // Delay normals until an occupied cell actually needs shading.
                float exposed = clamp((density - previousDensity) / max(density, 0.001), 0.0, 1.0);
                vec3 faceNormal = i < exactSteps ? voxelRay.normal : vec3(0.0);
                faceNormal /= max(length(faceNormal), 0.00001);
                float diffuse = 0.48 + 0.52 * max(dot(faceNormal, lightDirection), 0.0);
                float orientation = 0.88 + 0.12 * max(faceNormal.y, 0.0);
                faceLight = mix(1.0, diffuse * orientation, CS_LIGHT_CONTRAST * exposed * min(1.0, length(faceNormal)));
                // Keep the same integral and exponents; avoid taking logarithms
                // of the transmission that was just formed by an exponential.
                float lightTau = CSVoxelLightDepth(p, lightDirection, base) * CS_EXTINCTION * CS_DENSITY;
                float lightT = exp(-lightTau);
            #else
                float lightT = CSLightTransmittance(p, lightDirection, base);
            #endif
            float height = clamp((p.y - base) / CS_THICKNESS, 0.0, 1.0);
            float powder = 1.0 - exp(-density * 2.0);
            // Direct single scattering plus a cheap, bounded multiple-scattering approximation.
            #ifdef CS_STYLE_REIMAGINED
                vec3 cell = i < exactSteps ? voxelRay.cell : floor((p - CSWind() - vec3(0.0, base, 0.0)) / CS_VOXEL_SIZE);
                float skyAccess = CSVoxelSkyAccess(cell, density, morph);
                float ambientGain = mix(0.72, 0.38 + 0.68 * sqrt(skyAccess), CS_LIGHT_CONTRAST);
                vec3 ambient = cloudAmbientColor * ambientGain;
                // Forward scattering follows the actual path to the sun: thin
                // edges transmit light while deeper stacks darken exponentially.
                vec3 direct = cloudLightColor * lightT * (0.44 + 0.22 * boundedPhase) * faceLight;
                // Two bounded broad-scattering terms retain soft interior light
                // without making the complete cloud as bright as its thin rim.
                float broadT = exp(-lightTau * 0.35);
                float widerT = exp(-lightTau * 0.12);
                vec3 multiple = cloudLightColor * (0.20 * broadT + 0.09 * widerT) * (1.0 - lightT);
            #else
                vec3 ambient = cloudAmbientColor * mix(0.46, 1.05, height);
                vec3 direct = cloudLightColor * lightT * (0.55 + 0.50 * phase);
                vec3 multiple = cloudLightColor * (1.0 - lightT) * (0.17 + 0.15 * powder);
            #endif
            vec3 sampleColor = ambient + direct + multiple;
            #ifdef CS_STYLE_REIMAGINED
                // Keep nearby cube faces readable; atmospheric blending grows
                // with distance and still converges towards the pack's own sky.
                float haze = 1.0 - exp(-max(0.0, t - 160.0) * 0.00015 * (1.0 + rainFactor));
            #else
                float haze = 1.0 - exp(-t * 0.00018 * (1.0 + rainFactor));
            #endif
            sampleColor = mix(sampleColor, skyColor, min(haze, 0.82));
            sampleColor *= pow2(1.0 - maxBlindnessDarkness);
            float alpha = 1.0 - exp(-density * CS_DENSITY * CS_EXTINCTION * stride);
            radiance += transmittance * alpha * sampleColor;
            transmittance *= 1.0 - alpha;
        }
        #ifdef CS_STYLE_REIMAGINED
            previousDensity = density;
            voxelT = end;
            if (i < exactSteps) {
                vec3 crossed = vec3(lessThanEqual(voxelRay.next, vec3(CSNextVoxel(voxelRay) + 0.0001)));
                voxelRay.cell += crossed * voxelRay.direction;
                voxelRay.next += crossed * voxelRay.delta;
                voxelRay.normal = -crossed * voxelRay.direction;
            }
        #else
            t += stride;
        #endif
    }
    float alpha = 1.0 - transmittance;
    if (alpha < 0.0001) return vec4(0.0);
    // Complementary expects straight RGB + opacity; native TAA and exposure remain in control.
    vec3 color = radiance / alpha;
    float horizonFade = 1.0 - smoothstep(CS_MAX_DISTANCE * 0.76, CS_MAX_DISTANCE, firstHit);
    alpha *= horizonFade;
    if (alpha > 0.5) cloudLinearDepth = sqrt(max(0.0, firstHit) / renderDistance);
    return vec4(color, clamp(alpha, 0.0, 1.0));
}

// Keep shadow/general density paths independent of the primary-only aliases.
#ifdef CS_CLOUD_RESIDENT_CALL
    #undef CSVoxelWeight
    #undef CSVoxelSkyAccess
    #undef CSLightTransmittance
    #undef CSVoxelLightDepth
    #undef CSCloudDensity
    #undef CS_CLOUD_RESIDENT_CALL
#endif
// CLASSIC ORIGINAL
#else
// Both Complementary styles use this interface but wrap camera coordinates differently.
#ifdef CS_STYLE_REIMAGINED
    const float cloudNarrowness = 256.0 / 65536.0;
#else
    const float cloudNarrowness = 1.0 / 65536.0;
#endif
const float cloudStretch = CS_THICKNESS * 0.5;
const float cloudTallness = CS_THICKNESS;

// Java verifies the complete pack sources and layout. The primary caller uses
// its proven finite range; verified reflection stages require a complete period.
// GLSL float config literals cannot be compared in preprocessor expressions.
#if defined CS_STYLE_REIMAGINED && !defined CS_DENSITY_FIELD_BUILD && !defined SHADOW && !defined DH_SHADOW && !defined SHADOWCOMP && !defined BEGIN && !defined SETUP && !defined PREPARE
    #if defined DEFERRED1 && CS_DENSITY_RESIDENT_PRIMARY == 1
        #define CS_CLOUD_RESIDENT_CALL 1
    #elif CS_DENSITY_ATLAS_SIZE == 4096 && CS_DENSITY_FULL_PERIOD == 1 && (defined COMPOSITE || defined GBUFFERS_WATER || defined DH_WATER)
        // A complete horizontal period also covers reflected camera offsets.
        // These are the verified r5.9.3 post-prepare reflection callers. Keep
        // unknown stages and every smaller layout on the generic call tree.
        #define CS_CLOUD_RESIDENT_CALL 1
    #endif
#endif
#ifdef CS_CLOUD_RESIDENT_CALL
    #define CSVoxelWeight CSResidentVoxelWeight
    #define CSVoxelSkyAccess CSResidentVoxelSkyAccess
    #define CSLightTransmittance CSResidentLightTransmittance
    #define CSCloudDensity CSResidentCloudDensity
#endif
vec4 GetVolumetricClouds(int cloudAltitude, float distanceThreshold, inout float cloudLinearDepth,
    float skyFade, float skyMult0, vec3 cameraPos, vec3 ray, float sceneDistance,
    float VdotS, float VdotU, float dither) {

    float base = float(cloudAltitude) + CS_BASE_OFFSET;
    float top = base + CS_THICKNESS;
    float maxDistance = CS_MAX_DISTANCE;
    // Stop the volume at actual opaque geometry, including mountain silhouettes.
    if (sceneDistance < 10000000.0) maxDistance = min(maxDistance, max(0.0, sceneDistance));
    float enter = 0.0;
    float leave = maxDistance;
    if (abs(ray.y) < 0.00001) {
        if (cameraPos.y <= base || cameraPos.y >= top) return vec4(0.0);
    } else {
        float t0 = (base - cameraPos.y) / ray.y;
        float t1 = (top - cameraPos.y) / ray.y;
        enter = max(0.0, min(t0, t1));
        leave = min(maxDistance, max(t0, t1));
    }
    if (leave <= enter) return vec4(0.0);

    #ifdef DEFERRED1
        #ifdef CS_STYLE_REIMAGINED
            const int exactSteps = CS_VIEW_STEPS * 2;
            const int maxSteps = exactSteps + 16;
        #else
            const int maxSteps = CS_VIEW_STEPS;
        #endif
    #else
        #ifdef CS_STYLE_REIMAGINED
            const int exactSteps = CS_REFLECTION_STEPS * 2;
            const int maxSteps = exactSteps + 16;
        #else
            const int maxSteps = CS_REFLECTION_STEPS;
        #endif
    #endif
    #ifdef CS_STYLE_REIMAGINED
        const float targetStride = 2.0;
    #else
        const float targetStride = 18.0;
    #endif
    int count = int(clamp(ceil((leave - enter) / targetStride), 8.0, float(maxSteps)));
    float stride = (leave - enter) / float(count);
    float t = enter + stride * clamp(dither, 0.001, 0.999);
    vec3 lightDirection = normalize(mat3(gbufferModelViewInverse) * lightVec);
    float mu = dot(ray, lightDirection);
    #ifdef CS_STYLE_REIMAGINED
        float phase = 0.78 * CSPhase(mu, 0.65) + 0.22 * CSPhase(mu, -0.20);
        float boundedPhase = phase / (1.0 + 0.18 * phase);
    #else
        float phase = 0.68 * CSPhase(mu, 0.58) + 0.32 * CSPhase(mu, -0.22);
    #endif
    vec3 skyColor = GetSky(VdotU, VdotS, dither, isEyeInWater == 0, false);
    #ifdef ATM_COLOR_MULTS
        skyColor *= sqrtAtmColorMult;
    #endif
    vec3 radiance = vec3(0.0);
    float transmittance = 1.0;
    float firstHit = leave;
    bool hasHit = false;
    #ifdef CS_STYLE_REIMAGINED
        float faceLight = 1.0;
        float previousDensity = 0.0;
        float morph = CSMorphTime();
        CSVoxelRay voxelRay = CSStartVoxelRay(cameraPos, ray, base, enter);
        float voxelT = enter;
    #endif
    for (int i = 0; i < maxSteps; ++i) {
        #ifdef CS_STYLE_REIMAGINED
            if (voxelT >= leave || transmittance < 0.012) break;
            // Traverse exact cube faces nearby. A bounded far-field tail keeps
            // horizontal views and reflections continuous beyond the DDA budget.
            float end = i < exactSteps ? min(leave, CSNextVoxel(voxelRay))
                : voxelT + (leave - voxelT) / float(maxSteps - i);
            stride = max(0.0, end - voxelT);
            t = voxelT + stride * 0.5;
        #else
            if (i >= count || transmittance < 0.012) break;
        #endif
        vec3 p = cameraPos + ray * t;
        #ifdef CS_STYLE_REIMAGINED
            float density = i < exactSteps ? CSVoxelWeight(voxelRay.cell, morph) : CSCloudDensity(p, base, false);
            // Shade the exposed density jump at each true cube face. A faint
            // outer cell must not choose one normal for an entire cloud mass.
            // Equal-density neighbours have no internal surface to darken.
            float exposed = clamp((density - previousDensity) / max(density, 0.001), 0.0, 1.0);
            vec3 faceNormal = i < exactSteps ? voxelRay.normal : vec3(0.0);
            float diffuse = 0.48 + 0.52 * max(dot(faceNormal, lightDirection), 0.0);
            float orientation = 0.88 + 0.12 * max(faceNormal.y, 0.0);
            faceLight = mix(1.0, diffuse * orientation, CS_LIGHT_CONTRAST * exposed * min(1.0, length(faceNormal)));
        #else
            float density = CSCloudDensity(p, base, true);
        #endif
        if (density > 0.001) {
            if (!hasHit) {
                firstHit = t; hasHit = true;
            }
            float lightT = CSLightTransmittance(p, lightDirection, base);
            float height = clamp((p.y - base) / CS_THICKNESS, 0.0, 1.0);
            float powder = 1.0 - exp(-density * 2.0);
            // Direct single scattering plus a cheap, bounded multiple-scattering approximation.
            #ifdef CS_STYLE_REIMAGINED
                vec3 cell = i < exactSteps ? voxelRay.cell : floor((p - CSWind() - vec3(0.0, base, 0.0)) / CS_VOXEL_SIZE);
                float skyAccess = CSVoxelSkyAccess(cell, density, morph);
                float ambientGain = mix(0.72, 0.38 + 0.68 * sqrt(skyAccess), CS_LIGHT_CONTRAST);
                vec3 ambient = cloudAmbientColor * ambientGain;
                // Forward scattering follows the actual path to the sun: thin
                // edges transmit light while deeper stacks darken exponentially.
                vec3 direct = cloudLightColor * lightT * (0.44 + 0.22 * boundedPhase) * faceLight;
                // Two bounded broad-scattering terms retain soft interior light
                // without making the complete cloud as bright as its thin rim.
                float broadT = pow(lightT, 0.35);
                float widerT = pow(lightT, 0.12);
                vec3 multiple = cloudLightColor * (0.20 * broadT + 0.09 * widerT) * (1.0 - lightT);
            #else
                vec3 ambient = cloudAmbientColor * mix(0.46, 1.05, height);
                vec3 direct = cloudLightColor * lightT * (0.55 + 0.50 * phase);
                vec3 multiple = cloudLightColor * (1.0 - lightT) * (0.17 + 0.15 * powder);
            #endif
            vec3 sampleColor = ambient + direct + multiple;
            #ifdef CS_STYLE_REIMAGINED
                // Keep nearby cube faces readable; atmospheric blending grows
                // with distance and still converges towards the pack's own sky.
                float haze = 1.0 - exp(-max(0.0, t - 160.0) * 0.00015 * (1.0 + rainFactor));
            #else
                float haze = 1.0 - exp(-t * 0.00018 * (1.0 + rainFactor));
            #endif
            sampleColor = mix(sampleColor, skyColor, min(haze, 0.82));
            sampleColor *= pow2(1.0 - maxBlindnessDarkness);
            float alpha = 1.0 - exp(-density * CS_DENSITY * CS_EXTINCTION * stride);
            radiance += transmittance * alpha * sampleColor;
            transmittance *= 1.0 - alpha;
        }
        #ifdef CS_STYLE_REIMAGINED
            previousDensity = density;
            voxelT = end;
            if (i < exactSteps) CSAdvanceVoxel(voxelRay);
        #else
            t += stride;
        #endif
    }
    float alpha = 1.0 - transmittance;
    if (alpha < 0.0001) return vec4(0.0);
    // Complementary expects straight RGB + opacity; native TAA and exposure remain in control.
    vec3 color = radiance / alpha;
    float horizonFade = 1.0 - smoothstep(CS_MAX_DISTANCE * 0.76, CS_MAX_DISTANCE, firstHit);
    alpha *= horizonFade;
    if (alpha > 0.5) cloudLinearDepth = sqrt(max(0.0, firstHit) / renderDistance);
    return vec4(color, clamp(alpha, 0.0, 1.0));
}

// Keep shadow/general density paths independent of the primary-only aliases.
#ifdef CS_CLOUD_RESIDENT_CALL
    #undef CSVoxelWeight
    #undef CSVoxelSkyAccess
    #undef CSLightTransmittance
    #undef CSCloudDensity
    #undef CS_CLOUD_RESIDENT_CALL
#endif
#endif
// END VARIED OPTIMIZATION
