// Include the unchanged density.glsl with CS_DENSITY_FIELD_BUILD=1.
// One invocation owns one X/Z column and all eight Y output texels.
// The original private CSPrepareGroups cache survives across these calls.
#ifndef CS_COMPUTE_LOCAL_X
    #define CS_COMPUTE_LOCAL_X 8
#endif
#ifndef CS_COMPUTE_LOCAL_Y
    #define CS_COMPUTE_LOCAL_Y 8
#endif
layout(local_size_x = CS_COMPUTE_LOCAL_X, local_size_y = CS_COMPUTE_LOCAL_Y, local_size_z = 1) in;
layout(r32f) writeonly uniform image2D csDensityOutput;

#ifdef CS_STYLE_REIMAGINED
void main() {
    ivec2 column = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(column, ivec2(CS_DENSITY_ATLAS_SIZE)))) return;
    vec2 origin = CSDensityAtlasOrigin();
    float morph = CSMorphTime();
    for (int y = 0; y < 8; ++y) {
        vec3 cell = vec3(origin.x + float(column.x), float(y), origin.y + float(column.y));
        float weight = CSProceduralVoxelWeight(cell, morph);
        imageStore(csDensityOutput, ivec2(column.x + y * CS_DENSITY_ATLAS_SIZE, column.y), vec4(weight, 0.0, 0.0, 1.0));
    }
}
#else
// Match the original fragment producer when the native pack cloud style is
// Unbound or disabled; that style does not consume the Reimagined voxel atlas.
void main() {
    ivec2 column = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(column, ivec2(CS_DENSITY_ATLAS_SIZE)))) return;
    for (int y = 0; y < 8; ++y)
        imageStore(csDensityOutput, ivec2(column.x + y * CS_DENSITY_ATLAS_SIZE, column.y), vec4(0.0, 0.0, 0.0, 1.0));
}
#endif
