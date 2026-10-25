package dev.cloudscape.volumetric;

enum CloudQuality {
    ORIGINAL(96, 24, 5), BALANCED(64, 16, 4), HIGH(144, 32, 7), CUSTOM(0, 0, 0);
    final int view, reflection, light;
    CloudQuality(int view, int reflection, int light) { this.view = view; this.reflection = reflection; this.light = light; }
    static CloudQuality of(CloudConfig config) {
        for (CloudQuality quality : values())
            if (config.viewSteps == quality.view && config.reflectionSteps == quality.reflection && config.lightSteps == quality.light)
                return quality;
        return CUSTOM;
    }
    void set(CloudConfig config) {
        if (this == CUSTOM) return;
        config.viewSteps = view; config.reflectionSteps = reflection; config.lightSteps = light;
    }
    CloudQuality next() { return this == ORIGINAL ? BALANCED : this == BALANCED ? HIGH : ORIGINAL; }
}
