// Include after common.glsl, CloudConfig.defines() and atlas-variant/density.glsl.
// Define CS_DENSITY_FIELD_BUILD=1 before density.glsl, so this program cannot
// sample its own output. The parent owns the virtual prepare stage and sizing.
/* RENDERTARGETS: 20 */
/*
const int colortex20Format = R32F;
*/
const bool colortex20Clear = false;

#ifndef CS_DENSITY_ATLAS_BUILDER_BOUNDS
    #define CS_DENSITY_ATLAS_BUILDER_BOUNDS 0
#endif

#if defined CS_STYLE_REIMAGINED && CS_DENSITY_ATLAS_BUILDER_BOUNDS == 1
// Optional producer-only exact saturation bounds. The ordinary default producer
// calls the unmodified original procedural function instead of this experiment.
float CSDensityAtlasBuildWeightBounds(vec3 cell, float time) {
    vec3 centre = (cell + 0.5) * CS_VOXEL_SIZE;
    float h = centre.y / CS_THICKNESS;
    if (h <= 0.0 || h >= 1.0) return 0.0;
    centre.xz = mod(centre.xz, 65536.0);
    float coverage = clamp(CS_COVERAGE + 0.20 * rainFactor, 0.1, 0.95);
    vec2 group = floor(centre.xz / CS_CLUSTER_SIZE - 0.5);
    CSPrepareGroups(group, coverage);
    float envelope = -4.0;
    for (int i = 0; i < 4; ++i) envelope = max(envelope, CSGroupEnvelope(centre, i));
    float band = 0.035 + CS_EDGE_SOFTNESS * 0.012;
    if (envelope < -0.26 - band - 0.000001) return 0.0;
    if (envelope > 0.50 + band + 0.000001) return 1.0;
    vec3 scale0 = vec3(CS_CLUSTER_SIZE * 0.5, CS_THICKNESS * 0.625, CS_CLUSTER_SIZE * 0.5);
    vec3 scale1 = vec3(CS_CLUSTER_SIZE * 0.125, CS_THICKNESS * 0.25, CS_CLUSTER_SIZE * 0.125);
    float body = CSNoise(centre / scale0 + vec3(11.0, 7.0 + time * 0.32, 23.0));
    float billow = CSNoise(centre / scale1 + vec3(31.0 + time * 0.21, 19.0, 3.0 - time * 0.17));
    float sculpt = 0.50 * (body - 0.5) + 0.26 * (billow - 0.5);
    return smoothstep(-band, band, envelope + sculpt - 0.12);
}
#endif

void main() {
    #ifdef CS_STYLE_REIMAGINED
        ivec2 pixel = ivec2(gl_FragCoord.xy);
        vec3 cell = vec3(CSDensityAtlasOrigin().x + float(pixel.x % CS_DENSITY_ATLAS_SIZE),
            float(pixel.x / CS_DENSITY_ATLAS_SIZE), CSDensityAtlasOrigin().y + float(pixel.y));
        #if CS_DENSITY_ATLAS_BUILDER_BOUNDS == 1
            float weight = CSDensityAtlasBuildWeightBounds(cell, CSMorphTime());
        #else
            float weight = CSProceduralVoxelWeight(cell, CSMorphTime());
        #endif
        gl_FragData[0] = vec4(weight, 0.0, 0.0, 1.0);
    #else
        gl_FragData[0] = vec4(0.0, 0.0, 0.0, 1.0);
    #endif
}
