package dev.cloudscape.volumetric;

import java.nio.file.Files;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import static org.lwjgl.opengl.GL33C.*;

/** Development-only, actual Iris uniform readback; never shipped in the mod. */
public final class WindMotionScenario {
    static int ticks,phase=-1,age;
    static boolean ready,done;
    static final float[] SPEED={0,1,4,0,4,4};
    static final String[] NAME={"zero","normal","fast","stop","resume","accelerated"};
    static long phaseStart,firstTick,firstDiscontinuities;
    static boolean startShot,endShot;
    static final java.lang.reflect.Field STATE;
    static final java.lang.reflect.Method PUBLISH;
    static final Map<Integer,Long> sampled=new HashMap<>();
    static final Map<Integer,Integer> programs=new HashMap<>();
    static final List<Map<String,Object>> rows=new ArrayList<>();
    static final List<Map<String,Object>> uploads=new ArrayList<>();
    static double firstCpuX,firstCpuZ;
    static double[] firstGpu,lastGpu;
    static int probes,mismatches;
    static double maxError;
    static {try{
        STATE=WeatherRuntime.class.getDeclaredField("state");STATE.setAccessible(true);
        PUBLISH=WeatherRuntime.class.getDeclaredMethod("publish");PUBLISH.setAccessible(true);
    }catch(Exception e){throw new ExceptionInInitializerError(e);}}
    static WeatherState state(){try{return (WeatherState)STATE.get(null);}catch(Exception e){throw new IllegalStateException(e);}}
    static void command(Minecraft mc,String text){var s=mc.getSingleplayerServer();s.execute(()->s.getCommands().performPrefixedCommand(s.createCommandSourceStack(),text));}
    static void shot(Minecraft mc,String suffix){Screenshot.grab(mc.gameDirectory,"wind-"+NAME[phase]+"-"+suffix+".png",mc.gameRenderer.mainRenderTarget(),1,m->{});}
    public static void tick(Minecraft mc){
        if(done)return;
        mc.mouseHandler.releaseMouse();net.minecraft.client.KeyMapping.releaseAll();
        mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        mc.player.setPos(.5,145,.5);mc.player.setYRot(35);mc.player.setXRot(-40);mc.player.yRotO=35;mc.player.xRotO=-40;
        if(!ready){
            ready=true;
            command(mc,"gamerule minecraft:advance_time false");command(mc,"gamerule minecraft:advance_weather false");
            command(mc,"time set 6000");command(mc,"weather clear");command(mc,"tp CloudscapeTest 0 145 0 35 -40");
        }
        if(++ticks<=1200)return;
        try{
            if(phase<0||age>=280){
                if(phase>=0)finishPhase(mc);
                if(++phase==SPEED.length){finish(mc);return;}
                age=0;phaseStart=System.nanoTime();startShot=false;endShot=false;probes=0;mismatches=0;maxError=0;firstGpu=null;lastGpu=null;sampled.clear();programs.clear();
                CloudConfig config=UnboundAdapter.settings();config.windSpeed=SPEED[phase];config.weatherVariability=false;config.reimaginedMorphSpeed=0;
                if(phase<3){
                    WeatherState s=WeatherState.start(mc.level.getGameTime(),.317,config);s.windX=1000;s.windZ=2000;s.morph=0;
                    STATE.set(null,s);PUBLISH.invoke(null);
                }
                System.out.println("CLOUDSCAPE WIND PHASE "+NAME[phase]+" speed="+SPEED[phase]);
                if(phase==5)command(mc,"tick rate 2000");
            }
            age=(int)((System.nanoTime()-phaseStart)/50000000L);
            if(age>=40&&!startShot){startShot=true;WeatherState s=state();firstCpuX=s.windX;firstCpuZ=s.windZ;firstTick=s.lastTick;firstDiscontinuities=s.discontinuities;shot(mc,"start");}
            if(age>=279&&!endShot){endShot=true;shot(mc,"end");}
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    public static void observeProgram(){
        if(phase<0||phase>=SPEED.length||age<40)return;
        int program=glGetInteger(GL_CURRENT_PROGRAM);if(program<=0)return;
        if(programs.containsKey(program))return;
        int location=glGetUniformLocation(program,"csWind");if(location>=0)programs.put(program,location);
    }
    public static void afterFrame(){
        if(phase<0||phase>=SPEED.length||age<40)return;
        Minecraft mc=Minecraft.getInstance();if(mc.level==null)return;
        long tick=mc.level.getGameTime();
        for(var entry:programs.entrySet())sample(mc,tick,entry.getKey(),entry.getValue());
    }
    static void sample(Minecraft mc,long tick,int program,int location){
        if(sampled.getOrDefault(program,Long.MIN_VALUE)==tick)return;
        sampled.put(program,tick);
        float[] actual=new float[2];glGetUniformfv(program,location,actual);
        WeatherState s=state();float x=(float)s.windX,z=(float)s.windZ;
        double error=Math.max(Math.abs(actual[0]-x),Math.abs(actual[1]-z));
        probes++;maxError=Math.max(maxError,error);if(error>.001)mismatches++;
        double[] value={actual[0],actual[1]};if(firstGpu==null)firstGpu=value;lastGpu=value;
        if(age%20==0)uploads.add(new LinkedHashMap<>(Map.of("phase",NAME[phase],"tick",tick,"program",program,"cpu",new float[]{x,z},"gpu",actual)));
    }
    static void finishPhase(Minecraft mc){
        WeatherState s=state();
        double cpu=Math.hypot(s.windX-firstCpuX,s.windZ-firstCpuZ);
        double gpu=firstGpu==null?0:Math.hypot(lastGpu[0]-firstGpu[0],lastGpu[1]-firstGpu[1]);
        boolean stopped=SPEED[phase]==0;
        boolean passed=probes>100&&mismatches==0&&(stopped?cpu<.001&&gpu<.001:cpu>2&&gpu>2);
        Map<String,Object> row=new LinkedHashMap<>();row.put("phase",NAME[phase]);row.put("speed",SPEED[phase]);row.put("cpuDisplacement",cpu);row.put("gpuDisplacement",gpu);row.put("serverTicks",s.lastTick-firstTick);row.put("clockDiscontinuities",s.discontinuities-firstDiscontinuities);row.put("gpuProbes",probes);row.put("mismatches",mismatches);row.put("maxUniformError",maxError);row.put("passed",passed);rows.add(row);
        System.out.println("CLOUDSCAPE WIND RESULT "+ConfigStore.GSON.toJson(row).replace("\n",""));
    }
    static void finish(Minecraft mc)throws Exception{
        done=true;
        boolean passed=rows.stream().allMatch(r->Boolean.TRUE.equals(r.get("passed")));
        Map<String,Object> report=new LinkedHashMap<>();report.put("passed",passed);report.put("phases",rows);report.put("gpuUploads",uploads);report.put("scope","Actual Iris ProgramUniforms.update with glGetUniformfv; frozen camera/light/morph/dry weather; isolated runtime speed changes only");
        Files.writeString(mc.gameDirectory.toPath().resolve("wind-motion.json"),ConfigStore.GSON.toJson(report));
        command(mc,"tick rate 20");System.out.println("CLOUDSCAPE WIND MOTION "+(passed?"PASS":"FAIL"));System.out.println("CLOUDSCAPE VALIDATION COMPLETE");mc.stop();
    }
}
