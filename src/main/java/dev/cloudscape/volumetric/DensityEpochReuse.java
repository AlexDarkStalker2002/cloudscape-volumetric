package dev.cloudscape.volumetric;

import java.lang.ref.WeakReference;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.program.ComputeProgram;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.TextureAccess;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.targets.RenderTargets;
import org.joml.Vector3i;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryStack;

/** Exact compute-field reuse. No pixel readback, time-rate cap or missing barriers. */
public final class DensityEpochReuse {
    private static final String PREFIX = "cloudscape.densityReuse.prepare.";
    private static final Set<String> ALLOWED = Set.of("worldTime", "worldDay", "rainFactor", "frameTimeCounter", "noisetex", "colorimg20", "csWeather", "csMorph");
    private static final Map<RenderTargets, Long> CLEARS = new WeakHashMap<>();
    // Both directions use weak references globally; a program can otherwise
    // retain RenderTargets indirectly through its sampler suppliers.
    private static final Map<ComputeProgram, WeakReference<DensityEpochReuse>> OWNERS = new WeakHashMap<>();
    private final RenderTargets targets;
    private final TextureAccess expectedNoise;
    private final Map<ComputeProgram, Plan> plans = new IdentityHashMap<>();
    private final Map<Integer, Epoch> written = new HashMap<>();
    private final Set<String> reported = new HashSet<>();
    private long builds, reuses, fallbacks, invalidations;
    private Epoch latest;
    private String reason = "not-used";

    private record Plan(int time, int day, int rain, int frame, int noise, int image, int weather, int phase,
                        GLCapabilities caps, int maxImages, int maxSamplers, String sourceHash) {}
    private record Output(int texture, int unit, int level, int layered, int layer, int access, int format) {}
    private record Epoch(ComputeProgram program, int time, int day, int rainBits, int frameBits,
                         int noiseTexture, int noiseSampler, Output output, long clearGeneration, List<Integer> fieldBits) {}

    public DensityEpochReuse(RenderTargets targets, TextureAccess expectedNoise) {
        this.targets = targets;
        this.expectedNoise = expectedNoise;
    }

    public static synchronized void onFullClear(RenderTargets targets) {
        CLEARS.put(targets, CLEARS.getOrDefault(targets, 0L) + 1L);
    }

    private static synchronized long generation(RenderTargets targets) { return CLEARS.getOrDefault(targets, 0L); }
    private static synchronized void own(ComputeProgram program, DensityEpochReuse owner) { OWNERS.put(program, new WeakReference<>(owner)); }
    private static synchronized DensityEpochReuse owner(ComputeProgram program) {
        WeakReference<DensityEpochReuse> ref = OWNERS.get(program);
        return ref == null ? null : ref.get();
    }

    public void register(ComputeSource source, ComputeProgram program) {
        if (program == null || !source.getName().equals("prepare")) return;
        String text = source.getSource().orElse("");
        if (!text.matches("(?s).*\\bcloudscapeDensityEpochProof\\s*=\\s*92924096\\s*;.*")
                || !text.contains("CSProceduralVoxelWeight") || !text.contains("colorimg20")) return;
        plans.put(program, null); own(program, this);
        try {
            if (!eligible()) throw new IllegalStateException("not-full-period-layout");
            GLCapabilities caps = GL.getCapabilities();
            // Cache capability/function-pointer proof, without vendor assumptions.
            // ARB extensions on a3.3 context are sufficient on AMD and NVIDIA.
            if (!(caps.OpenGL43 || caps.GL_ARB_compute_shader) || !(caps.OpenGL42 || caps.GL_ARB_shader_image_load_store)
                    || caps.glDispatchCompute == 0 || caps.glBindImageTexture == 0 || caps.glMemoryBarrier == 0
                    || caps.glGetIntegeri_v == 0 || caps.glGetTexLevelParameteriv == 0)
                throw new IllegalStateException("missing-compute-image-or-query-function-pointer");
            int id = program.getProgramId();
            Map<String, Integer> locations = new HashMap<>();
            List<String> details = new ArrayList<>(), violations = new ArrayList<>();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer size = stack.mallocInt(1), type = stack.mallocInt(1);
                for (int i = 0, count = GL20.glGetProgrami(id, GL20.GL_ACTIVE_UNIFORMS); i < count; i++) {
                    String name = GL20.glGetActiveUniform(id, i, 256, size, type);
                    int expected = name.equals("colorimg20") ? GL42.GL_IMAGE_2D : name.equals("noisetex") ? GL20.GL_SAMPLER_2D
                        : name.equals("worldTime") || name.equals("worldDay") ? GL11.GL_INT
                        : name.equals("csWeather") ? GL20.GL_FLOAT_VEC4 : GL11.GL_FLOAT;
                    details.add(name + ":type=" + type.get(0) + ":size=" + size.get(0));
                    if (!ALLOWED.contains(name) || size.get(0) != 1 || type.get(0) != expected) violations.add("unexpected-active:" + name);
                    int location = GL20.glGetUniformLocation(id, name);
                    if (location < 0) violations.add("missing-location:" + name);
                    locations.put(name, location);
                }
                IntBuffer local = stack.mallocInt(3);
                GL20.glGetProgramiv(id, GL43.GL_COMPUTE_WORK_GROUP_SIZE, local);
                if (local.get(0) != 8 || local.get(1) != 8 || local.get(2) != 1) violations.add("local-size-not-8x8x1");
            }
            UnboundAdapter.LOGGER.info("CLOUDSCAPE DENSITY_REUSE linked-compute program={} uniforms={}", id, details);
            System.setProperty(PREFIX + "linkedUniforms", String.join(",", details));
            if (!violations.isEmpty()) throw new IllegalStateException(String.join(";", violations));
            int time = locations.getOrDefault("worldTime", -1), day = locations.getOrDefault("worldDay", -1);
            int frame = locations.getOrDefault("frameTimeCounter", -1);
            if ((time >= 0) != (day >= 0) || time >= 0 && frame >= 0
                    || time < 0 && frame < 0 && UnboundAdapter.settings().shapeMode == 0 && UnboundAdapter.settings().reimaginedMorphSpeed != 0.0f
                    || UnboundAdapter.settings().shapeMode == 0 && !locations.containsKey("rainFactor")
                    || UnboundAdapter.settings().shapeMode == 1 && (!locations.containsKey("csWeather") || !locations.containsKey("csMorph"))
                    || !locations.containsKey("noisetex")
                    || !locations.containsKey("colorimg20") || program.getActiveImages() != 1)
                throw new IllegalStateException("incomplete-or-ambiguous-inputs-or-images");
            Plan plan = new Plan(time, day, locations.getOrDefault("rainFactor",-1), frame, locations.get("noisetex"), locations.get("colorimg20"),
                locations.getOrDefault("csWeather",-1),locations.getOrDefault("csMorph",-1),
                caps, GL11.glGetInteger(GL42.GL_MAX_IMAGE_UNITS), GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS), UnboundAdapter.sha256(text));
            if (plan.maxImages < 1 || plan.maxSamplers < 1) throw new IllegalStateException("no-image-or-sampler-units");
            plans.put(program, plan);
            UnboundAdapter.LOGGER.info("CLOUDSCAPE DENSITY_REUSE register-compute program={} source={} caps={} ARB={}/{}",
                id, plan.sourceHash, caps.OpenGL43, caps.GL_ARB_compute_shader, caps.GL_ARB_shader_image_load_store);
            System.setProperty(PREFIX + "sourceHash", plan.sourceHash);
            publish("registered-compute");
        } catch (RuntimeException | LinkageError error) {
            reject("register:" + error.getMessage()); publish("registration-fallback");
        }
    }

    public void invalidate(String why, boolean destroy) {
        written.clear(); invalidations++;
        if (!plans.isEmpty()) {
            UnboundAdapter.LOGGER.info("CLOUDSCAPE DENSITY_REUSE invalidate={} builds={} reuses={} fallback={}", why, builds, reuses, fallbacks);
            publish(why);
        }
        if (destroy) {
            synchronized (DensityEpochReuse.class) { for (ComputeProgram program : plans.keySet()) OWNERS.remove(program); }
            plans.clear();
        }
    }

    /** Invoked at ComputeProgram's innermost DIRECT dispatch, after its barrier. */
    public static void dispatch(ComputeProgram program, Vector3i groups) {
        DensityEpochReuse state = owner(program);
        if (state == null) { IrisRenderSystem.dispatchCompute(groups); return; }
        state.dispatchActual(program, groups);
    }

    private void dispatchActual(ComputeProgram program, Vector3i groups) {
        Epoch input = null;
        try {
            Plan plan = plans.get(program);
            if (!eligible() || plan == null || GL.getCapabilities() != plan.caps)
                throw new IllegalStateException("no-proven-plan-or-context-changed");
            RenderTarget atlas = targets.get(20);
            if (atlas == null || atlas.getWidth() != 32768 || atlas.getHeight() != 4096 || atlas.getInternalFormat() != InternalTextureFormat.R32F
                    || groups == null || groups.x != 512 || groups.y != 512 || groups.z != 1
                    || GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) != program.getProgramId())
                throw new IllegalStateException("unexpected-program-target-or-dispatch-layout");
            int id = program.getProgramId(), unit = GL20.glGetUniformi(id, plan.image);
            if (unit < 0 || unit >= plan.maxImages) throw new IllegalStateException("invalid-image-unit");
            Output output = new Output(GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_NAME, unit), unit,
                GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LEVEL, unit), GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LAYERED, unit),
                GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LAYER, unit), GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_ACCESS, unit),
                GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_FORMAT, unit));
            // Iris ImageBinding.update binds level0/layered=true/layer0/READ_WRITE.
            // The image2D shader still writes only this single2D texture level.
            if (output.texture <= 0 || output.texture != atlas.getMainTexture() || output.texture == atlas.getAltTexture()
                    || output.level != 0 || output.layered != GL11.GL_TRUE || output.layer != 0
                    || output.access != GL15.GL_READ_WRITE || output.format != GL30.GL_R32F)
                throw new IllegalStateException("unexpected-actual-image-binding:" + output);
            int time = plan.time < 0 ? 0 : GL20.glGetUniformi(id, plan.time);
            int day = plan.day < 0 ? 0 : GL20.glGetUniformi(id, plan.day);
            float rain = plan.rain < 0 ? 0 : GL20.glGetUniformf(id, plan.rain), frame = plan.frame < 0 ? 0 : GL20.glGetUniformf(id, plan.frame);
            if (!Float.isFinite(rain) || !Float.isFinite(frame)) throw new IllegalStateException("nonfinite-input");
            int noiseUnit = GL20.glGetUniformi(id, plan.noise);
            if (noiseUnit < 0 || noiseUnit >= plan.maxSamplers) throw new IllegalStateException("invalid-noise-unit");
            int previousActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE), noise, sampler;
            try {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + noiseUnit);
                noise = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                sampler = GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, noiseUnit);
                if (noise <= 0 || noise == output.texture || noise != expectedNoise.getTextureId().getAsInt()
                        || sampler != GlSampler.LINEAR_REPEAT.getId()
                        || GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH) != 128
                        || GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT) != 128)
                    throw new IllegalStateException("unexpected-noise-binding-or-storage");
                // Read only metadata of the actual output storage. The original
                // binding is restored without disturbing RenderPearl's cache.
                try {
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, output.texture);
                    if (GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH) != 32768
                            || GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT) != 4096
                            || GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT) != GL30.GL_R32F)
                        throw new IllegalStateException("unexpected-actual-output-storage");
                } finally { GL11.glBindTexture(GL11.GL_TEXTURE_2D, noise); }
            } finally { GL13.glActiveTexture(previousActive); }
            List<Integer> fieldBits = new ArrayList<>(8);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                java.nio.FloatBuffer values = stack.mallocFloat(4);
                for (int location : new int[]{plan.weather,plan.phase}) if(location>=0) {
                    GL20.glGetUniformfv(id,location,values);
                    for(int component=0;component<(location==plan.phase?1:4);component++) {
                        float value=values.get(component);
                        if(!Float.isFinite(value))throw new IllegalStateException("nonfinite-field-uniform");
                        fieldBits.add(Float.floatToRawIntBits(value));
                    }
                }
            }
            input = new Epoch(program, time, day, Float.floatToRawIntBits(rain), Float.floatToRawIntBits(frame), noise, sampler, output, generation(targets),List.copyOf(fieldBits));
        } catch (RuntimeException | LinkageError error) {
            written.clear(); fallbacks++; reject(error.getMessage());
        }
        boolean allowReuse = !System.getProperty("cloudscape.densityEpochReuse", "true").equalsIgnoreCase("false");
        if (allowReuse && input != null && input.equals(written.get(input.output.texture))) {
            latest = input; reuses++;
            if (reuses == 1) UnboundAdapter.LOGGER.info("CLOUDSCAPE DENSITY_REUSE first-compute-skip output={} worldTime={}", input.output, input.time);
            publish("reuse-compute"); return;
        }
        IrisRenderSystem.dispatchCompute(groups);
        if (input != null) {
            written.put(input.output.texture, input); latest = input; builds++;
            if (builds == 1) UnboundAdapter.LOGGER.info("CLOUDSCAPE DENSITY_REUSE first-compute-build output={} worldTime={}", input.output, input.time);
            publish("build-compute");
        } else publish("fallback:" + reason);
    }

    private static boolean eligible() {
        CloudConfig config = UnboundAdapter.settings();
        return config.densityAtlasSize == 4096 && WorldDensityCache.cacheLayout(config);
    }

    private void reject(String why) {
        reason = why;
        if (reported.add(why)) UnboundAdapter.LOGGER.warn("CLOUDSCAPE DENSITY_REUSE fallback={} (ordinary compute dispatch retained)", why);
    }

    private void publish(String mode) {
        System.setProperty(PREFIX + "builds", Long.toString(builds));
        System.setProperty(PREFIX + "reuses", Long.toString(reuses));
        System.setProperty(PREFIX + "fallbacks", Long.toString(fallbacks));
        System.setProperty(PREFIX + "invalidations", Long.toString(invalidations));
        System.setProperty(PREFIX + "textures", Integer.toString(written.size()));
        System.setProperty(PREFIX + "mode", mode); System.setProperty(PREFIX + "reason", reason);
        if (latest != null) {
            System.setProperty(PREFIX + "worldTime", Integer.toString(latest.time));
            System.setProperty(PREFIX + "worldDay", Integer.toString(latest.day));
            System.setProperty(PREFIX + "rainBits", Integer.toUnsignedString(latest.rainBits));
            System.setProperty(PREFIX + "frameBits", Integer.toUnsignedString(latest.frameBits));
            System.setProperty(PREFIX + "lastTexture", Integer.toString(latest.output.texture));
            System.setProperty(PREFIX + "noiseTexture", Integer.toString(latest.noiseTexture));
            System.setProperty(PREFIX + "imageBinding", latest.output.toString());
            System.setProperty(PREFIX + "clearGeneration", Long.toString(latest.clearGeneration));
        }
    }
}
