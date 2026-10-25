package dev.cloudscape.volumetric;

/** Pure simulation state: shader reads and camera position cannot advance it. */
public final class WeatherState {
    public int schema = 1;
    public long lastTick, discontinuities;
    public double seconds, windX, windZ, morph;
    public double velocityX, velocityZ, firstVelocityX, firstVelocityZ;
    public double seed;
    public double[] first = new double[3], value = new double[3];

    public static WeatherState start(long tick, double seed, CloudConfig config) {
        WeatherState state = new WeatherState();
        state.seed = Math.clamp(seed, 0.0, 1.0);
        state.lastTick = tick;
        state.seconds = Math.max(0.0, tick * 0.05);
        state.windX = wrap(state.seconds * 0.72 * config.windSpeed);
        state.windZ = wrap(state.seconds * -0.33 * config.windSpeed);
        state.velocityX = state.firstVelocityX = 0.72 * config.windSpeed;
        state.velocityZ = state.firstVelocityZ = -0.33 * config.windSpeed;
        state.morph = state.seconds * config.reimaginedMorphSpeed * 0.008;
        state.first[0] = state.value[0] = dry(state.seconds, state.seed, config.weatherVariability);
        return state;
    }

    public void tick(long tick, float rain, float thunder, CloudConfig config) {
        long elapsed = tick - lastTick;
        if (elapsed == 0) return;
        lastTick = tick;
        if (elapsed < 0 || elapsed > 5) { discontinuities++; return; }
        // A bounded simulation gap never invents unobserved vanilla weather.
        // Normal updates are one tick. Identical inputs produce identical bits.
        double dt = elapsed * 0.05;
        seconds += dt;
        double rate = 4.743864518 / config.transitionSeconds;
        double decay = Math.exp(-rate * dt);
        double[] target = {dry(seconds, seed, config.weatherVariability), finiteUnit(rain), finiteUnit(thunder)};
        for (int i = 0; i < 3; i++) {
            double oldFirst = first[i];
            first[i] = target[i] + (oldFirst - target[i]) * decay;
            value[i] = target[i] + (value[i] - target[i] + (oldFirst-target[i])*rate*dt) * decay;
            first[i] = Math.clamp(first[i], 0.0, 1.0);
            value[i] = Math.clamp(value[i], 0.0, 1.0);
        }
        double wet = Math.max(value[1], value[2]);
        double angle = -0.429762 + 0.18 * Math.sin(seconds / 2400.0 + seed * Math.PI * 2.0);
        double speed = config.windSpeed * 0.792023 * (1.0 + wet * 0.20);
        double targetX=Math.cos(angle)*speed,targetZ=Math.sin(angle)*speed;
        double oldX=firstVelocityX,oldZ=firstVelocityZ;
        firstVelocityX=targetX+(oldX-targetX)*decay;
        firstVelocityZ=targetZ+(oldZ-targetZ)*decay;
        velocityX=targetX+(velocityX-targetX+(oldX-targetX)*rate*dt)*decay;
        velocityZ=targetZ+(velocityZ-targetZ+(oldZ-targetZ)*rate*dt)*decay;
        // Explicit stop is an immediate user command; changing a nonzero speed
        // or the evolving direction uses the same smooth filter as weather.
        if(config.windSpeed!=0f) {
            windX = wrap(windX + velocityX * dt);
            windZ = wrap(windZ + velocityZ * dt);
        }
        morph += dt * config.reimaginedMorphSpeed * 0.008;
    }

    /** A live client tick was observed even if server clock sync skipped ahead. */
    public void observedTick(long tick, float rain, float thunder, CloudConfig config) {
        long elapsed=tick-lastTick;
        if(elapsed<0 || elapsed>5) {
            // Do not replay unknown weather history or teleport the wind. The
            // current client tick still gets its ordinary bounded update.
            discontinuities++;
            lastTick=tick-1;
        }
        tick(tick,rain,thunder,config);
    }

    public boolean valid(double expectedSeed) {
        if (schema != 1 || first == null || value == null || first.length != 3 || value.length != 3
            || seed != expectedSeed || seed < 0 || seed > 1 || !Double.isFinite(seconds) || seconds < 0
            || seconds > 4.7e17 || windX < 0 || windX >= 65536 || windZ < 0 || windZ >= 65536
            || morph > 1.6e16 || !Double.isFinite(windX)
            || !Double.isFinite(windZ) || !Double.isFinite(morph) || morph < 0
            || !Double.isFinite(velocityX) || !Double.isFinite(velocityZ)
            || !Double.isFinite(firstVelocityX) || !Double.isFinite(firstVelocityZ)
            || Math.abs(velocityX)>8 || Math.abs(velocityZ)>8 || Math.abs(firstVelocityX)>8
            || Math.abs(firstVelocityZ)>8) return false;
        for (int i=0; i<3; i++) if (!Double.isFinite(first[i]) || !Double.isFinite(value[i])
            || first[i]<0 || first[i]>1 || value[i]<0 || value[i]>1) return false;
        return true;
    }

    public float cloudiness() { return (float)(value[0] + (1.0-value[0])*Math.max(value[1],value[2])); }
    private static double finiteUnit(float x) { return Float.isFinite(x) ? Math.clamp(x,0f,1f) : 0.0; }
    private static double wrap(double x) { return x-Math.floor(x/65536.0)*65536.0; }
    private static double dry(double seconds, double seed, boolean enabled) {
        if (!enabled) return 0.5;
        // Persistent episodes rather than a narrowly varying endless sine.
        // Seed and simulation time are the only inputs; an existing checkpoint
        // filters toward the new target without resetting wind or cloud phase.
        double position = seconds / 900.0 + seed * 7.0;
        long episode = (long)Math.floor(position);
        double blend = Math.clamp((position - Math.floor(position) - 0.18) / 0.64, 0.0, 1.0);
        blend = blend * blend * blend * (blend * (blend * 6.0 - 15.0) + 10.0);
        double from = episodeCloudiness(episode, seed);
        double to = episodeCloudiness(episode + 1, seed);
        return from + (to - from) * blend;
    }

    private static double episodeCloudiness(long episode, double seed) {
        long bits = episode ^ Double.doubleToLongBits(seed);
        bits += 0x9E3779B97F4A7C15L;
        bits = (bits ^ (bits >>> 30)) * 0xBF58476D1CE4E5B9L;
        bits = (bits ^ (bits >>> 27)) * 0x94D049BB133111EBL;
        bits ^= bits >>> 31;
        double choice = (bits >>> 11) * 0x1.0p-53;
        if (choice < 0.18) return 0.02;
        if (choice < 0.38) return 0.20;
        if (choice < 0.65) return 0.50;
        if (choice < 0.84) return 0.78;
        return 0.98;
    }
}
