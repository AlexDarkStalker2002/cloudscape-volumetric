package dev.cloudscape.volumetric;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import static org.lwjgl.opengl.GL11.*;

/** Isolated release acceptance only; never packaged with Cloudscape. */
public final class ReleaseSoakScenario {
    static long boot=System.nanoTime(), start, lastHeartbeat=-1;
    static boolean opening, rejoining, leaving, done;
    static long leaveAt;
    static int lastScene=-1, reloads, rejoins;
    static final Set<Integer> events=new HashSet<>();
    static final List<Map<String,Object>> samples=new ArrayList<>();
    public static void tick(Minecraft mc) {
        try { run(mc); } catch(Exception error){throw new AssertionError("Release soak failed",error);}
    }
    static void run(Minecraft mc)throws Exception {
        if(done)return;
        if(!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith("validation-game"))throw new AssertionError("Isolated directory required");
        if(start==0 && (System.nanoTime()-boot)>600_000_000_000L)throw new AssertionError("Soak failed to enter its world");
        mc.options.pauseOnLostFocus=false;
        if(leaving){
            if(mc.level==null && (System.nanoTime()-leaveAt)>8_000_000_000L){
                if(Iris.getPipelineManager().getPipelineNullable()!=null)throw new AssertionError("Pipeline retained after world leave");
                mc.createWorldOpenFlows().openWorld("Cloudscape-Validation",()->{});
                leaving=false;rejoining=true;opening=true;
            }return;
        }
        if(mc.level==null || mc.player==null){
            if(!opening && mc.gui.screen() instanceof TitleScreen && mc.gui.overlay()==null){
                if(!Files.exists(mc.gameDirectory.toPath().resolve("saves/Cloudscape-Validation/level.dat")))throw new AssertionError("Frozen fixture missing");
                opening=true;mc.createWorldOpenFlows().openWorld("Cloudscape-Validation",()->{});
            }return;
        }
        if(mc.gui.overlay()!=null)return;
        if(mc.gui.screen()!=null)mc.gui.setScreen(null);
        if(!mc.gui.hud.isHidden())mc.gui.hud.toggle();
        mc.mouseHandler.releaseMouse();net.minecraft.client.KeyMapping.releaseAll();
        mc.player.getAbilities().flying=true;mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        if(start==0){
            start=System.nanoTime();
            command(mc,"gamemode creative CloudscapeTest");command(mc,"tick rate 20");
            command(mc,"gamerule minecraft:advance_time true");command(mc,"gamerule minecraft:advance_weather false");
            command(mc,"weather clear");command(mc,"time set 6000");
            command(mc,"tp CloudscapeTest 96 155 32 -45 -22");
            System.out.println("CLOUDSCAPE SOAK START minutes="+Integer.getInteger("cloudscape.validation.soakMinutes",60));
        }
        if(rejoining){rejoining=false;rejoins++;lastScene=-1;System.out.println("CLOUDSCAPE SOAK REJOIN PASS");}
        long seconds=(System.nanoTime()-start)/1_000_000_000L;
        int[] reloadAt={180,780,1380,2280,2880,3420};
        for(int event:reloadAt)if(seconds>=event && events.add(event)){
            Iris.reload();healthy();reloads++;System.out.println("CLOUDSCAPE SOAK RELOAD "+reloads+" seconds="+seconds);
        }
        if(seconds>=600 && events.add(600))command(mc,"execute in minecraft:the_nether run tp CloudscapeTest 0 140 0 0 -10");
        if(seconds>=660 && events.add(660)){requireDimension(mc,"minecraft:the_nether");command(mc,"execute in minecraft:the_end run tp CloudscapeTest 0 160 0 0 -10");}
        if(seconds>=720 && events.add(720)){requireDimension(mc,"minecraft:the_end");command(mc,"execute in minecraft:overworld run tp CloudscapeTest 96 155 32 -45 -22");}
        if(seconds>=760 && events.add(760))requireDimension(mc,"minecraft:overworld");
        if(seconds>=1800 && events.add(1800)){
            mc.disconnectWithSavingScreen();leaving=true;leaveAt=System.nanoTime();return;
        }
        if(seconds<600 || seconds>=760){
            int scene=(int)(seconds/120)%7;
            if(scene!=lastScene){
                lastScene=scene;
                command(mc,"time set "+(scene==1?12500:scene==5?18000:6000));
                command(mc,"weather "+(scene==2||scene==3?"thunder":"clear"));
                double y=scene==3?310:scene==4?440:155;
                command(mc,"tp CloudscapeTest 96 "+y+" 32 -45 "+(scene==6?-90:-22));
                System.out.println("CLOUDSCAPE SOAK SCENE "+scene+" seconds="+seconds);
            }
        }
        if(seconds/30!=lastHeartbeat){
            lastHeartbeat=seconds/30;healthy();
            CloudConfig c=UnboundAdapter.settings();
            if(c.shapeMode!=1 || !c.weatherVariability || c.viewSteps!=192 || c.reflectionSteps!=64 || c.lightSteps!=8 || c.densityAtlasSize!=4096)throw new AssertionError("Reference quality/config changed");
            Map<String,Object> row=new LinkedHashMap<>();row.put("seconds",seconds);row.put("dimension",mc.level.dimension().identifier().toString());
            row.put("heapUsedBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
            if(org.lwjgl.opengl.GL.getCapabilities().GL_NVX_gpu_memory_info)row.put("availableVramKiB",glGetInteger(org.lwjgl.opengl.NVXGPUMemoryInfo.GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX));
            row.put("weather",WeatherRuntime.diagnostic());row.put("reloads",reloads);row.put("rejoins",rejoins);samples.add(row);
            System.out.println("CLOUDSCAPE SOAK HEARTBEAT "+ConfigStore.GSON.toJson(row));
            write(mc,false,seconds);
        }
        if(seconds>=Integer.getInteger("cloudscape.validation.soakMinutes",60)*60L){
            if(reloads!=6 || rejoins!=1 || !events.contains(760))throw new AssertionError("Incomplete lifecycle coverage");
            write(mc,true,seconds);done=true;System.out.println("CLOUDSCAPE SOAK PASS seconds="+seconds+" reloads="+reloads+" rejoins="+rejoins);
            System.out.println("CLOUDSCAPE VALIDATION COMPLETE");mc.stop();
        }
    }
    static void healthy(){if(Iris.getStoredError().isPresent()||Iris.isFallback()||!(Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline))throw new AssertionError("Iris pipeline failed");}
    static void requireDimension(Minecraft mc,String id){if(!mc.level.dimension().identifier().toString().equals(id))throw new AssertionError("Dimension transition failed: "+id);}
    static void command(Minecraft mc,String command){var server=mc.getSingleplayerServer();server.execute(()->server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),command));}
    static void write(Minecraft mc,boolean passed,long seconds)throws Exception{
        Path out=mc.gameDirectory.toPath().resolve("soak-result.json");
        Files.writeString(out,ConfigStore.GSON.toJson(Map.of("passed",passed,"elapsedSeconds",seconds,"reloads",reloads,"rejoins",rejoins,"samples",samples)));
    }
}
