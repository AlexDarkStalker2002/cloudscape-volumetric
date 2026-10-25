package dev.cloudscape.validation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;
import static org.lwjgl.opengl.GL33C.*;

/** Development-only frame measurements. GPU queries are read without waiting. */
public final class FrameMetrics {
    private static final ArrayList<Double> frames = new ArrayList<>();
    private static final ArrayList<Double> gpu = new ArrayList<>();
    private static final ArrayDeque<Integer> pending = new ArrayDeque<>();
    private static int active;
    private static long previous;

    private FrameMetrics() {}

    public static void begin() {
        if (!Boolean.getBoolean("cloudscape.validation.performance")) return;
        while (!pending.isEmpty() && glGetQueryObjecti(pending.peek(), GL_QUERY_RESULT_AVAILABLE) != 0) {
            int query = pending.remove();
            gpu.add(glGetQueryObjectui64(query, GL_QUERY_RESULT) / 1_000_000.0);
            glDeleteQueries(query);
        }
        if (!ValidationRunner.measuring()) { previous = 0; return; }
        long now = System.nanoTime();
        if (previous != 0) frames.add((now - previous) / 1_000_000.0);
        previous = now;
        if (pending.size() >= 16 || glGetQueryi(GL_TIME_ELAPSED, GL_CURRENT_QUERY) != 0) return;
        active = glGenQueries();
        glBeginQuery(GL_TIME_ELAPSED, active);
    }

    public static void end() {
        if (active == 0) return;
        glEndQuery(GL_TIME_ELAPSED);
        pending.add(active);
        active = 0;
    }

    public static void capture(String name) {
        if (!Boolean.getBoolean("cloudscape.validation.performance")) return;
        double average = frames.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double duration = frames.stream().mapToDouble(Double::doubleValue).sum();
        if(Boolean.getBoolean("cloudscape.validation.releaseFps")){
            try{
                var dir=net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve("frame-metrics");
                java.nio.file.Files.createDirectories(dir);
                var rows=new StringBuilder("index,frameMs\n");
                for(int i=0;i<frames.size();i++)rows.append(i).append(',').append(String.format(Locale.ROOT,"%.9f",frames.get(i))).append('\n');
                java.nio.file.Files.writeString(dir.resolve(name+".frames.csv"),rows);
                rows=new StringBuilder("index,gpuMs\n");
                for(int i=0;i<gpu.size();i++)rows.append(i).append(',').append(String.format(Locale.ROOT,"%.9f",gpu.get(i))).append('\n');
                java.nio.file.Files.writeString(dir.resolve(name+".gpu.csv"),rows);
            }catch(java.io.IOException error){throw new AssertionError(error);}
        }
        frames.sort(Double::compare);
        gpu.sort(Double::compare);
        System.out.printf(Locale.ROOT,
            "CLOUDSCAPE PERF %s frames=%d averageFps=%.2f frameP95Ms=%.3f gpuSamples=%d gpuMedianMs=%.3f gpuP95Ms=%.3f durationMs=%.3f frameMedianMs=%.3f%n",
            name, frames.size(), average > 0 ? 1000.0 / average : 0.0,
            percentile(frames, .95), gpu.size(), percentile(gpu, .5), percentile(gpu, .95), duration, percentile(frames,.5));
        if(org.lwjgl.opengl.GL.getCapabilities().GL_NVX_gpu_memory_info)
            System.out.printf(Locale.ROOT,"CLOUDSCAPE MEMORY %s totalKiB=%d availableKiB=%d%n",name,
                glGetInteger(org.lwjgl.opengl.NVXGPUMemoryInfo.GL_GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX),
                glGetInteger(org.lwjgl.opengl.NVXGPUMemoryInfo.GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX));
        if(Boolean.getBoolean("cloudscape.validation.releaseFps"))System.out.printf(Locale.ROOT,"CLOUDSCAPE PERF_TAIL %s frameP99Ms=%.6f gpuP99Ms=%.6f%n",name,percentile(frames,.99),percentile(gpu,.99));
        PhaseMetrics.capture(name);
        GpuSpanMetrics.capture(name);
        frames.clear(); gpu.clear(); previous = 0;
        while (!pending.isEmpty()) glDeleteQueries(pending.remove());
    }

    private static double percentile(ArrayList<Double> values, double fraction) {
        return values.isEmpty() ? 0.0 : values.get(Math.min(values.size() - 1, (int)Math.ceil(values.size() * fraction) - 1));
    }
}
