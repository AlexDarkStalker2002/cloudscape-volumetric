package dev.cloudscape.validation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/** Separate visual run: recording must never contaminate FPS measurements. */
public final class MotionRecorder {
    private static int previousPhase=-1,frame,saved;
    private static boolean initialized;
    private static long lastSimulationTick=Long.MIN_VALUE;
    public static void frame() {
        if(!Boolean.getBoolean("cloudscape.validation.recordMotion"))return;
        if(Boolean.getBoolean("cloudscape.validation.performance"))throw new IllegalStateException("Recording and performance runs must be separate");
        Minecraft mc=Minecraft.getInstance();int phase=ValidationRunner.sequencePhase();
        if(mc.level==null||mc.player==null||!(phase>=11&&phase<=13 || phase>=600&&phase<=606))return;
        if(phase!=previousPhase){previousPhase=phase;frame=0;saved=0;lastSimulationTick=Long.MIN_VALUE;}
        int current=frame++;
        boolean extended=phase==13 || phase>=600;
        long simulationTick=mc.level.getGameTime();
        if(extended) {
            if(saved>=280 || lastSimulationTick!=Long.MIN_VALUE && simulationTick-lastSimulationTick<10)return;
            lastSimulationTick=simulationTick;
        } else if(saved>=120 || current>=30 && current%6!=0)return;
        try {
            Path root=mc.gameDirectory.toPath().toAbsolutePath().normalize();
            if(!root.endsWith("validation-game"))throw new IllegalStateException("Recording outside isolated game");
            Path manifest=root.resolve("motion-frames.tsv");
            if(!initialized){
                if(Files.getFileStore(root).getUsableSpace()<4L*1024*1024*1024)throw new IllegalStateException("Less than 4 GiB free for bounded motion evidence");
                Files.writeString(manifest,"phase\tindex\tframeInPhase\tnanoTime\tworldTime\tx\ty\tz\tyaw\tpitch\train\tfile\n",StandardOpenOption.CREATE_NEW);
                initialized=true;
            }
            String name=String.format(Locale.ROOT,"motion-%02d-%04d.png",phase,saved++);
            Files.writeString(manifest,String.format(Locale.ROOT,"%d\t%d\t%d\t%d\t%d\t%.6f\t%.6f\t%.6f\t%.5f\t%.5f\t%.5f\t%s%n",
                phase,saved-1,current,System.nanoTime(),mc.level.getOverworldClockTime(),mc.player.getX(),mc.player.getY(),mc.player.getZ(),mc.player.getYRot(),mc.player.getXRot(),mc.level.getRainLevel(1),name),StandardOpenOption.APPEND);
            String weather="null";
            try {weather=String.valueOf(Class.forName("dev.cloudscape.volumetric.WeatherRuntime").getMethod("diagnostic").invoke(null));}catch(ReflectiveOperationException ignored){}
            Files.writeString(root.resolve("weather-inputs.jsonl"),String.format(Locale.ROOT,
                "{\"phase\":%d,\"index\":%d,\"gameTime\":%d,\"rain\":%.7f,\"thunder\":%.7f,\"file\":\"%s\",\"state\":%s}\n",
                phase,saved-1,simulationTick,mc.level.getRainLevel(1),mc.level.getThunderLevel(1),name,weather),StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            Screenshot.grab(mc.gameDirectory,name,mc.gameRenderer.mainRenderTarget(),1,m->{});
        } catch(java.io.IOException error){throw new IllegalStateException("Cannot record motion evidence",error);}
    }
}
