// Only the actual density dependencies. Options/define handling are supplied by
// a virtual include extracted from the same fingerprint-verified common.glsl.
uniform sampler2D noisetex;
uniform float rainFactor;

#if CLOUD_SPEED_MULT == 100
    uniform int worldTime;
    uniform int worldDay;
    // Deliberately the same operations/types/order as Complementary common.glsl.
    int modifiedWorldDay = int(mod(worldDay, 100) + 5.0);
    float syncedTime = (worldTime + modifiedWorldDay * 24000) * 0.05;
#else
    uniform float frameTimeCounter;
#endif
