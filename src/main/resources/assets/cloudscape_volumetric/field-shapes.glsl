// Authoritative new-field implementation. Embedded identically in both assets
// by tools/sync-field.py; no second field or additional render pass.
#ifndef CS_SHAPE_MODE
    #define CS_SHAPE_MODE 0
#endif
#if CS_SHAPE_MODE == 1 && defined CS_STYLE_REIMAGINED
#ifndef CS_WEATHER_UNIFORMS
#define CS_WEATHER_UNIFORMS
uniform vec4 csWeather; // dry cloudiness, filtered vanilla rain/thunder, seed
uniform vec2 csWind;    // integrated phase; inactive in full-period producer
uniform float csMorph;  // independent evolution; no unused simulation-age key
#endif

float CSShapeMap(vec2 p, vec2 scale) {
    vec2 q = p / scale;
    vec2 f = fract(q);
    f = f * f * (3.0 - 2.0 * f);
    return texture2DLod(noisetex, (mod(floor(q), 128.0) + f + 0.5) / 128.0, 0.0).r;
}

// Reuse horizontal composition across all eight slices in a compute column.
// Every cache dependency is uniform within an invocation; never across frames.
vec2 CSShapeColumn = vec2(1e20);
vec4 CSShapeProfile = vec4(0.0); // base, crown, horizontal envelope, edge cut
float CSShapeCoverage = 0.5;
void CSPrepareShape(vec2 xz) {
    if (all(equal(xz, CSShapeColumn))) return;
    CSShapeColumn = xz;
    vec2 p = mod(xz + floor(csWeather.w * 127.0) * 512.0, 65536.0);
    float scale = CS_CLUSTER_SIZE;
    float broad = CSShapeMap(p + vec2(1536.0, 4096.0), vec2(min(scale * 2.0, 512.0)));
    float lobes = CSShapeMap(p + vec2(8192.0, 1024.0), vec2(scale * 0.5));
    // Periodic low-frequency warp bends ridges without another noise lookup.
    // Shorter 4:1 segments replace the straight 8:1 strips; power-of-two
    // frequencies and the periodic warp preserve the 65536-block torus.
    vec2 ridgePosition = vec2(p.x + p.y, p.y - p.x)
        + (vec2(broad, lobes) - 0.5) * scale * 0.75;
    float ridge = CSShapeMap(ridgePosition + vec2(512.0, 2560.0), vec2(min(scale, 512.0), scale * 0.25));
    float region = CSShapeMap(p + vec2(7168.0, 1280.0), vec2(512.0));
    // Regional contrast fades at the endpoints so a clear episode can actually
    // clear the whole local sky rather than retaining the same islands forever.
    float regionalContrast = 1.0 - abs(csWeather.x * 2.0 - 1.0);
    float front = clamp(csWeather.x + (region - 0.5) * 0.30 * regionalContrast, 0.0, 1.0);
    float wet = max(csWeather.y, csWeather.z);
    // Anchor the whole range: clear reaches zero even at a high user setting,
    // while neutral weather retains the chosen amount instead of adding a bias.
    float clearBlend = 1.0 - smoothstep(0.02, 0.50, front);
    float cloudyBlend = smoothstep(0.50, 0.98, front);
    float amount = mix(CS_COVERAGE, 0.0, clearBlend);
    CSShapeCoverage = clamp(mix(amount, 0.98, cloudyBlend) + wet * 0.16, 0.0, 0.98);
    if (CSShapeCoverage <= 0.001) { CSShapeProfile = vec4(0.0); return; }
    vec4 weights = vec4((1.0-front)*0.9, front*0.8, 0.10+(1.0-front)*region*0.28, wet*1.8);
    weights.xyz *= 1.0 - wet * 0.75;
    vec4 shapeMix = weights / max(dot(weights, vec4(1.0)), 0.0001);
    #if CS_SHAPE_FAMILY >= 0
        shapeMix = vec4(0.0);
        shapeMix[CS_SHAPE_FAMILY] = 1.0;
    #endif
    vec4 n = vec4(broad, lobes, ridge, region);
    // A: separated lobes, B: connected masses, C: short curved ridges,
    // D: deeper storm masses. Lower lobes stay coherent, rather than using
    // independent per-voxel noise that would produce dangling speckles.
    vec4 horizontal = vec4(n.y * 0.78 + n.x * 0.22,
        n.x * 0.66 + n.y * 0.34,
        n.z * 0.74 + n.y * 0.26 - max(0.0, 0.48-n.y)*0.85,
        n.x * 0.65 + n.y * 0.35);
    // Existing lobe maps vary the base over several voxel levels. The centre
    // hangs lower while weaker shoulders rise, breaking a uniform flat slab.
    vec4 bottom = vec4(0.075 + (1.0-n.y)*0.22 + (0.5-n.x)*0.06,
        0.075 + (1.0-n.y)*0.26 + (0.5-n.w)*0.08,
        0.10 + (1.0-n.y)*0.20 + (0.5-n.x)*0.04,
        0.035 + (1.0-n.y)*0.14 + (0.5-n.w)*0.04);
    vec4 top = vec4(0.60 + n.y*0.32, 0.68 + n.y*0.13, 0.53 + n.y*0.20, 0.91 + n.y*0.08);
    float crown = dot(top, shapeMix);
    // Combine existing lobe and warped ridge maps; no fifth texture lookup.
    float baseRelief = (n.y * 0.55 + n.z * 0.45 - 0.5) * mix(0.48, 0.24, shapeMix.w);
    float base = clamp(dot(bottom, shapeMix) + baseRelief, 0.025, crown-0.22);
    float threshold = 0.51 + (0.5 - CSShapeCoverage) * 0.70;
    float envelope = (dot(horizontal, shapeMix) - threshold) * 3.2;
    CSShapeProfile = vec4(base, crown, envelope, mix(0.32, 0.18, shapeMix.w));
}

float CSVariedVoxelWeight(vec3 cell, float time) {
    vec3 p = (cell + 0.5) * CS_VOXEL_SIZE;
    float h = p.y / CS_THICKNESS;
    if (h <= 0.0 || h >= 1.0) return 0.0;
    p.xz = mod(p.xz, 65536.0);
    CSPrepareShape(p.xz);
    if (CSShapeCoverage <= 0.001) return 0.0;
    float base = CSShapeProfile.x, crown = CSShapeProfile.y;
    if (h <= base || h >= crown) return 0.0;
    float y = (h - base) / (crown - base);
    float profile = smoothstep(0.0, 0.24, y) * (1.0 - smoothstep(0.55, 1.0, y));
    // Retain cubic faces and the same optical model; detail only sculpts voxels.
    // Frequencies on horizontal axes divide 65536 exactly at all config scales.
    vec3 scale = vec3(CS_CLUSTER_SIZE * 0.125, CS_THICKNESS * 0.25, CS_CLUSTER_SIZE * 0.125);
    float detail = CSNoise(p / scale + vec3(31.0 + time*0.21, 19.0, 3.0-time*0.17));
    float crownCut = (1.0-profile) * CSShapeProfile.w;
    float band = 0.035 + CS_EDGE_SOFTNESS * 0.012;
    float edge = (detail-0.5)*0.22;
    float weight = smoothstep(-band, band, CSShapeProfile.z - crownCut + edge);
    // Preserve the current occupied-voxel mask and all upper faces. Only make
    // existing lower-face voxels less translucent; never create extra cells.
    if (weight <= 0.0 || weight >= 1.0 || y >= 0.24) return weight;
    // Smooth bounded contrast: f(0)=0, f(1)=1 and weight <= f <= 4*weight.
    // Reusing the existing profile avoids a second density ramp. A weak face
    // cannot jump to opaque when its original density first becomes positive.
    return weight / (1.0 - 0.75 * (1.0-profile) * (1.0-weight));
}
#endif

