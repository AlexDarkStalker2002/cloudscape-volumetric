// Cloudscape Volumetric. Original density field shared by the view, reflections and ground shadows.
#ifndef CLOUDSCAPE_DENSITY_INCLUDED
#define CLOUDSCAPE_DENSITY_INCLUDED

// A 65,536-block period matches the camera wrapping in both cloud styles.
// Every horizontal frequency is an integer multiple of 1/65536.
float CSNoise(vec3 p) {
    vec3 cell = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    // Wrap the integer address before adding its fraction; long coordinates must
    // not erase the sub-texel detail when the main view wraps but shadows do not.
    vec2 uv = mod(cell.xy + vec2(23.0, 29.0) * cell.z, 128.0) + f.xy;
    float a = texture2DLod(noisetex, (uv + 0.5) / 128.0, 0.0).r;
    float b = texture2DLod(noisetex, (uv + vec2(23.0, 29.0) + 0.5) / 128.0, 0.0).r;
    return mix(a, b, f.z);
}

vec3 CSWind() {
    #if CLOUD_SPEED_MULT == 100
        float t = syncedTime;
    #else
        float t = frameTimeCounter * (float(CLOUD_SPEED_MULT) * 0.01);
    #endif
    return vec3(t * 0.72, 0.0, -t * 0.33) * CS_WIND_SPEED;
}

#ifdef CS_STYLE_REIMAGINED
// Smooth, periodic weather cells, independent of the pack's blue-channel cloud map.
// Wrapping the integer address first also preserves detail far from world origin.
float CSLayoutNoise(vec2 worldXZ, float scale) {
    vec2 q = worldXZ / scale;
    vec2 f = fract(q);
    f = f * f * (3.0 - 2.0 * f);
    return texture2DLod(noisetex, (mod(floor(q), 128.0) + f + 0.5) / 128.0, 0.0).r;
}

float CSMorphTime() {
    // Shape evolution is independent of wind: windSpeed=0 still allows morphing.
    #if CLOUD_SPEED_MULT == 100
        return syncedTime * CS_MORPH_SPEED * 0.008;
    #else
        return frameTimeCounter * float(CLOUD_SPEED_MULT) * 0.00008 * CS_MORPH_SPEED;
    #endif
}

vec4 CSGroupSeed(vec2 group) {
    // Permute addresses before sampling: nearby groups must not inherit the
    // smooth green-channel correlation. One cached native texture lookup avoids
    // repeating a large integer hash inside every sunlight and view sample.
    vec2 address = mod(mod(group, 128.0) * vec2(17.0, 37.0) + vec2(13.0, 71.0), 128.0);
    vec3 seed = texture2DLod(noisetex, (address + 0.5) / 128.0, 0.0).rgb;
    return vec4(seed.r, seed.b, fract(seed.r + seed.g * 5.13), fract(seed.b + seed.g * 17.73));
}

// Per-fragment cache, never shared across pixels or frames. Rays typically
// traverse many voxels in the same weather neighbourhood; retain the four group
// descriptions for view, overhead and sunlight queries until that key changes.
vec2 CSCachedOrigin = vec2(1e20);
vec4 CSCachedHorizontal[4]; // centre XZ, inverse radii XZ
vec4 CSCachedVertical[4];   // bottom, inverse height, relative crown offset XZ
float CSCachedBias[4];

void CSPrepareGroups(vec2 origin, float coverage) {
    if (all(equal(CSCachedOrigin, origin))) return;
    CSCachedOrigin = origin;
    for (int z = 0; z < 2; ++z) for (int x = 0; x < 2; ++x) {
        int i = z * 2 + x;
        vec2 group = origin + vec2(float(x), float(z));
        vec4 seed = CSGroupSeed(group);
        vec2 centre = (group + 0.5 + (seed.rg - 0.5) * 0.48) * CS_CLUSTER_SIZE;
        CSCachedBias[i] = 4.0;
        if (coverage - 0.75 * seed.b + 0.09 <= -0.08) continue;
        float weather = CSLayoutNoise(centre + vec2(1792.0, 2816.0), min(CS_CLUSTER_SIZE * 2.0, 512.0));
        float presence = smoothstep(-0.08, 0.08, coverage - 0.75 * seed.b + 0.18 * (weather - 0.5));
        if (presence < 0.001) continue;
        float width = CS_CLUSTER_SIZE * min(0.5, mix(0.31, 0.57, coverage) * mix(0.55, 1.10, seed.a)) * mix(0.65, 1.0, presence);
        vec2 radius = width * mix(vec2(0.84, 1.12), vec2(1.16, 0.88), seed.r);
        float bottom = CS_THICKNESS * (0.04 + 0.16 * seed.g);
        float height = CS_THICKNESS * mix(0.32, 1.05, seed.r * CS_TOWER_STRENGTH);
        height = min(height, CS_THICKNESS - bottom - CS_VOXEL_SIZE * 0.25);
        CSCachedHorizontal[i] = vec4(centre, 1.0 / radius);
        CSCachedVertical[i] = vec4(bottom, 1.0 / height, (seed.gb - 0.5) * 0.62);
        CSCachedBias[i] = (1.0 - presence) * 0.55;
    }
}

float CSGroupEnvelope(vec3 p, int i) {
    if (CSCachedBias[i] > 1.0) return -4.0;
    vec4 horizontal = CSCachedHorizontal[i];
    vec4 vertical = CSCachedVertical[i];
    vec2 side = (p.xz - horizontal.xy) * horizontal.zw;
    if (any(greaterThan(abs(side), vec2(1.25)))) return -4.0;
    float y = (p.y - vertical.x) * vertical.y;
    vec3 lower = vec3(side.x, (y - 0.30) / 0.34, side.y);
    vec2 crownSide = (side - vertical.zw) / 0.64;
    vec3 upper = vec3(crownSide.x, (y - 0.65) / 0.39, crownSide.y);
    return max(1.0 - dot(lower, lower), 1.0 - dot(upper, upper)) - CSCachedBias[i];
}

// Full 3D, equal-sided visual voxels. A bounded group envelope avoids the long
// connected sheets produced by thresholding a nearly uniform noise field.
// Within that envelope two independently evolving fields sculpt the outline.
float CSVoxelWeight(vec3 cell, float time) {
    vec3 centre = (cell + 0.5) * CS_VOXEL_SIZE;
    float h = centre.y / CS_THICKNESS;
    if (h <= 0.0 || h >= 1.0) return 0.0;
    centre.xz = mod(centre.xz, 65536.0);
    float coverage = clamp(CS_COVERAGE + 0.20 * rainFactor, 0.1, 0.95);
    vec2 group = floor(centre.xz / CS_CLUSTER_SIZE - 0.5);
    CSPrepareGroups(group, coverage);
    float envelope = -4.0;
    for (int i = 0; i < 4; ++i) envelope = max(envelope, CSGroupEnvelope(centre, i));
    // Width and jitter bounds make the nearest 2x2 seeds sufficient. Omitted
    // centres are >=0.76 cell away; even a sculpted crown reaches <0.73 cell.
    if (envelope < -0.35) return 0.0;
    vec3 scale0 = vec3(CS_CLUSTER_SIZE * 0.5, CS_THICKNESS * 0.625, CS_CLUSTER_SIZE * 0.5);
    vec3 scale1 = vec3(CS_CLUSTER_SIZE * 0.125, CS_THICKNESS * 0.25, CS_CLUSTER_SIZE * 0.125);
    float body = CSNoise(centre / scale0 + vec3(11.0, 7.0 + time * 0.32, 23.0));
    float billow = CSNoise(centre / scale1 + vec3(31.0 + time * 0.21, 19.0, 3.0 - time * 0.17));
    float sculpt = 0.50 * (body - 0.5) + 0.26 * (billow - 0.5);
    float band = 0.035 + CS_EDGE_SOFTNESS * 0.012;
    return smoothstep(-band, band, envelope + sculpt - 0.12);
}

float CSVoxelSkyAccess(vec3 cell, float density, float morph) {
    float opticalDepth = density * CS_VOXEL_SIZE * 0.5;
    // Three strata give a broader sky-occlusion estimate than the old single
    // neighbour sample, while retaining the shared voxel field and fixed cost.
    for (int j = 0; j < 3; ++j)
        opticalDepth += CSVoxelWeight(cell + vec3(0.0, 1.0 + float(j) * 2.0, 0.0), morph) * CS_VOXEL_SIZE * 2.0;
    return exp(-opticalDepth * CS_EXTINCTION * CS_DENSITY * 0.6);
}

float CSCloudDensity(vec3 worldPos, float base, bool detailed) {
    vec3 p = worldPos - CSWind();
    p.y -= base;
    if (p.y <= 0.0 || p.y >= CS_THICKNESS) return 0.0;
    return CSVoxelWeight(floor(p / CS_VOXEL_SIZE), CSMorphTime());
}

struct CSVoxelRay {
    vec3 cell;
    vec3 direction;
    vec3 next;
    vec3 delta;
    vec3 normal;
};

CSVoxelRay CSStartVoxelRay(vec3 origin, vec3 ray, float base, float enter) {
    CSVoxelRay v;
    vec3 p = origin - CSWind();
    p.y -= base;
    v.cell = floor((p + ray * (enter + 0.0005)) / CS_VOXEL_SIZE);
    v.direction = sign(ray);
    vec3 safeRay = mix(vec3(1.0), ray, greaterThan(abs(ray), vec3(0.000001)));
    vec3 boundary = (v.cell + step(vec3(0.0), ray)) * CS_VOXEL_SIZE;
    v.next = mix(vec3(1e20), max(vec3(enter), (boundary - p) / safeRay), greaterThan(abs(ray), vec3(0.000001)));
    v.delta = CS_VOXEL_SIZE / max(abs(ray), vec3(0.000001));
    v.normal = enter > 0.0 ? vec3(0.0, -sign(ray.y), 0.0) : vec3(0.0);
    return v;
}

float CSNextVoxel(CSVoxelRay v) { return min(v.next.x, min(v.next.y, v.next.z)); }

void CSAdvanceVoxel(inout CSVoxelRay v) {
    // Advance all tied axes at edges/corners, including exactly horizontal rays.
    vec3 crossed = vec3(lessThanEqual(v.next, vec3(CSNextVoxel(v) + 0.0001)));
    v.cell += crossed * v.direction;
    v.next += crossed * v.delta;
    v.normal = -crossed * v.direction;
    v.normal /= max(length(v.normal), 0.00001);
}

float CSVoxelLightDepth(vec3 p, vec3 ray, float base) {
    float leave = CS_MAX_DISTANCE;
    if (abs(ray.y) > 0.00001) leave = min(leave, max(0.0, ((ray.y > 0.0 ? base + CS_THICKNESS : base) - p.y) / ray.y));
    CSVoxelRay v = CSStartVoxelRay(p, ray, base, 0.0);
    float t = 0.0, opticalDepth = 0.0;
    float morph = CSMorphTime();
    for (int j = 0; j < CS_LIGHT_STEPS * 8; ++j) {
        float end = min(leave, CSNextVoxel(v));
        opticalDepth += CSVoxelWeight(v.cell, morph) * max(0.0, end - t);
        t = end;
        if (t >= leave || opticalDepth * CS_EXTINCTION * CS_DENSITY > 8.0) break;
        CSAdvanceVoxel(v);
    }
    // Low sun can cross hundreds of cells. Account for the remainder instead of
    // treating the step budget as the end of the cloud and leaking light.
    float stride = max(0.0, leave - t) / 8.0;
    if (stride > 0.0 && opticalDepth * CS_EXTINCTION * CS_DENSITY <= 8.0) {
        for (int j = 0; j < 8; ++j) opticalDepth += CSCloudDensity(p + ray * (t + (float(j) + 0.5) * stride), base, false) * stride;
    }
    return opticalDepth;
}
#else
float CSCloudDensity(vec3 worldPos, float base, bool detailed) {
    float h = (worldPos.y - base) / CS_THICKNESS;
    if (h <= 0.0 || h >= 1.0) return 0.0;

    vec3 p = worldPos - CSWind();
    vec3 q = p / 128.0;
    q.y *= 0.9;
    float weather = texture2DLod(noisetex, (p.xz + vec2(3141.0, 9253.0)) / 32768.0, 0.0).b;
    float coverage = clamp(CS_COVERAGE + 0.20 * rainFactor, 0.1, 0.95);
    float shape = 0.55 * CSNoise(q * 0.5) + 0.30 * CSNoise(q + vec3(13.0, 7.0, 29.0))
                + 0.15 * CSNoise(q * 2.0);
    float threshold = mix(0.73, 0.30, coverage) - (weather - 0.5) * 0.28;
    // Flat condensation bases, bulbous middle sections, thinning and breaking tops.
    threshold += 0.14 * smoothstep(0.28, 1.0, h);
    float body = max(0.0, (shape - threshold) * 4.2);
    if (body <= 0.0) return 0.0;
    if (detailed) {
        float erosion = 0.65 * CSNoise(q * 8.0) + 0.35 * CSNoise(q * 16.0);
        body = max(0.0, body - (1.0 - erosion) * CS_DETAIL);
    }
    float profile = smoothstep(0.0, 0.085, h) * (1.0 - smoothstep(0.62, 1.0, h));
    return min(body, 1.0) * profile;
}
#endif

float CSLightTransmittance(vec3 p, vec3 lightDirection, float base) {
    #ifdef CS_STYLE_REIMAGINED
        float opticalDepth = CSVoxelLightDepth(p, lightDirection, base);
    #else
        float opticalDepth = 0.0;
        float offset = 3.0;
        float stride = 7.0;
        for (int j = 0; j < CS_LIGHT_STEPS; ++j) {
            vec3 samplePos = p + lightDirection * (offset + stride * 0.5);
            opticalDepth += CSCloudDensity(samplePos, base, j < 2) * stride;
            offset += stride;
            stride *= 2.0;
        }
    #endif
    return exp(-opticalDepth * CS_EXTINCTION * CS_DENSITY);
}

float CSPhase(float cosineAngle, float g) {
    float g2 = g * g;
    return (1.0 - g2) / pow(max(0.02, 1.0 + g2 - 2.0 * g * cosineAngle), 1.5);
}
#endif
