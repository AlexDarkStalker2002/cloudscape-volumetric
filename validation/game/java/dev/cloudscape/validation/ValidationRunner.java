package dev.cloudscape.validation;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** Separate development-only mod. This class is never included in the user's mod JAR. */
public final class ValidationRunner {
    private static boolean started;
    private static int ticks;
    private static int scene;
    private static int wait;
    private static boolean warmupDone;
    private static int lastFpsScene=-1;
    private static final String WORLD = "Cloudscape-Validation";

    public static boolean measuring() {
        if(Boolean.getBoolean("cloudscape.validation.releaseFps"))
            return lastFpsScene==scene && (scene==1||scene==2||scene==6||scene==7||scene==410||scene==420||scene==421||scene==430||scene==431) && wait>20 && wait<=620;
        return scene > 0 && scene != 8 && scene != 15 && scene != 16 && scene != 18 && wait > 20 && wait <= 100;
    }
    public static int sequencePhase() { return scene; }

    public static void tick(Minecraft mc) {
        if (!Boolean.getBoolean("cloudscape.validation")) return;
        if (!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith("validation-game"))
            throw new IllegalStateException("Validation may only run in its isolated game directory");
        if(Boolean.getBoolean("cloudscape.validation.releaseSoak")) {dev.cloudscape.volumetric.ReleaseSoakScenario.tick(mc);return;}
        if (++ticks > (Boolean.getBoolean("cloudscape.validation.windMotion")?200000:12000)) { System.err.println("CLOUDSCAPE VALIDATION TIMEOUT"); mc.stop(); return; }
        mc.options.pauseOnLostFocus = false;
        if (!started && mc.gui.screen() instanceof TitleScreen && mc.gui.overlay() == null) {
            started = true;
            System.out.println("CLOUDSCAPE VALIDATION: opening isolated world");
            if (Files.exists(mc.gameDirectory.toPath().resolve("saves").resolve(WORLD).resolve("level.dat"))) {
                mc.createWorldOpenFlows().openWorld(WORLD, () -> {});
            } else {
                mc.createWorldOpenFlows().createFreshLevel(WORLD,
                    new LevelSettings(WORLD, GameType.CREATIVE,
                        new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, true, false), true,
                        WorldDataConfiguration.DEFAULT),
                    new WorldOptions(4202603L, false, false), WorldPresets::createNormalWorldDimensions,
                    mc.gui.screen());
            }
            return;
        }
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
        if (mc.gui.screen() != null) mc.gui.setScreen(null);
        if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
        if (!mc.player.getAbilities().flying) {
            mc.player.getAbilities().flying = true;
            mc.player.onUpdateAbilities();
        }
        if(Boolean.getBoolean("cloudscape.validation.windMotion")){
            dev.cloudscape.volumetric.WindMotionScenario.tick(mc);return;
        }
        if(scene==0 && !warmupDone) {
            warmupDone=true;
            int warmup=Integer.getInteger("cloudscape.validation.dhWarmupTicks",0);
            if(warmup>0) {
                command(mc,"gamerule minecraft:advance_time false");
                command(mc,"gamerule minecraft:advance_weather false");
                command(mc,"time set 6000");command(mc,"weather clear");
                command(mc,"fill -16 128 64 16 319 64 minecraft:air replace minecraft:stone");
                command(mc,"tp CloudscapeTest 0 145 0 35 -22");
                System.out.println("CLOUDSCAPE WARMUP ticks="+warmup+"; excluded from measurements; scene clock resets afterwards");
                wait=warmup;return;
            }
        }
        if(Boolean.getBoolean("cloudscape.validation.performance")) {
            mc.mouseHandler.releaseMouse();net.minecraft.client.KeyMapping.releaseAll();
            mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            float yaw=35,pitch=-22;double x=.5,y=145,z=.5;
            if(scene==2){yaw=90;pitch=-12;y=155;}
            if(scene==420){pitch=-90;}
            if(scene==421){pitch=-90;y=220;}
            if(scene==430){y=300;pitch=2;}
            if(scene==431){y=330;pitch=-10;}
            if(scene==6){yaw=0;pitch=8;y=131;z=-34.5;}
            if(scene==7){yaw=-45;pitch=-18;x=96.5;y=100;z=32.5;}
            if(scene==1||scene==2||scene==6||scene==7||scene==410||scene==420||scene==421||scene==430||scene==431) {
                mc.player.setPos(x,y,z);mc.player.setYRot(yaw);mc.player.setXRot(pitch);
                mc.player.yRotO=yaw;mc.player.xRotO=pitch;
            }
        }
        if(Boolean.getBoolean("cloudscape.validation.releaseFps") && scene!=lastFpsScene){
            lastFpsScene=scene;
            if(scene==1||scene==2||scene==6||scene==7||scene==420||scene==421||scene==430||scene==431)wait=Math.max(wait,820);
            else if(scene==410)wait+=600;
        }
        if (wait > 0) { --wait; return; }
        if (scene == 0) {
            if(Boolean.getBoolean("cloudscape.validation.boundarySweep")) {
                command(mc,"gamemode creative CloudscapeTest");
                command(mc,"gamerule minecraft:advance_time false");
                command(mc,"gamerule minecraft:advance_weather false");
                command(mc,"time set 6000");command(mc,"weather clear");
                command(mc,"tp CloudscapeTest 175.846 117 184.854 -163.2 -19.7");
                scene=300;wait=300;return;
            }
            if(Boolean.getBoolean("cloudscape.validation.skySweep")) {
                command(mc,"gamemode creative CloudscapeTest");
                command(mc,"gamerule minecraft:advance_time false");
                command(mc,"gamerule minecraft:advance_weather false");
                command(mc,"time set 6000");command(mc,"weather clear");
                command(mc,"fill -16 128 64 16 319 64 minecraft:air replace minecraft:stone");
                command(mc,"tp CloudscapeTest 0 145 400 180 0");
                scene=200;wait=200;return;
            }
            if(Boolean.getBoolean("cloudscape.validation.dhFixtureOnly")) {
                command(mc,"gamemode creative CloudscapeTest");command(mc,"time set 6000");command(mc,"weather clear");
                command(mc,"tp CloudscapeTest 0 145 400 180 0");scene=15;wait=200;return;
            }
            // Remove the previous run's isolated geometry fixture before the
            // fixed day/sunset views, keeping repeated comparisons consistent.
            command(mc, "fill -16 128 64 16 319 64 minecraft:air replace minecraft:stone");
            dev.cloudscape.volumetric.RuntimeConfigDiagnostics.capture();
            LodReadiness.capture("after-warmup");
            boolean liveTime = Boolean.getBoolean("cloudscape.validation.liveTime");
            command(mc, "gamerule minecraft:advance_time " + liveTime);
            System.out.println("CLOUDSCAPE VALIDATION INPUTS: liveTime=" + liveTime);
            command(mc, "gamerule minecraft:advance_weather false");
            command(mc, "time set 6000");
            command(mc, "weather clear");
            command(mc, "gamemode creative CloudscapeTest");
            command(mc, "tp CloudscapeTest 0 145 0 35 -22");
            mc.gui.setScreen(null);
            scene = 1; wait = 220;
        } else if (scene == 300) {
            capture(mc,"boundary-north.png");
            command(mc,"tp CloudscapeTest 175.846 117 184.854 156.1 -55.7");
            scene=301;wait=120;
        } else if (scene == 301) {
            capture(mc,"boundary-west.png");
            command(mc,"tp CloudscapeTest 175.846 117 184.854 42 -47.1");
            scene=302;wait=120;
        } else if (scene == 302) {
            capture(mc,"boundary-south.png");
            scene=8;wait=40;
        } else if (scene == 200) {
            dhWall(mc,true);
            scene=201;wait=200;
        } else if (scene == 201) {
            command(mc,"tp CloudscapeTest 96 155 32 0 -60");
            scene=202;wait=200;
        } else if (scene >= 202 && scene <= 207) {
            capture(mc,"sky-sweep-"+(scene-201)+".png");
            command(mc,"tp CloudscapeTest 96 155 32 "+((scene-201)*60)+" -60");
            scene++;wait=100;
        } else if (scene == 208) {
            command(mc,"tp CloudscapeTest 96 155 32 35 -22");
            scene=209;wait=400;
        } else if (scene == 209) {
            capture(mc,"sky-sweep-final.png");
            scene=8;wait=40;
        } else if (scene == 1) {
            capture(mc, "01-day.png");
            if(Boolean.getBoolean("cloudscape.validation.sharpReview")){
                command(mc,"tp CloudscapeTest 0 145 0 35 -90");scene=420;wait=220;return;
            }
            command(mc, "time set 12500");
            command(mc, "tp CloudscapeTest 0 155 0 90 -12");
            scene = 2; wait = 150;
        } else if(scene==420) {
            capture(mc,"16-under.png");
            command(mc,"tp CloudscapeTest 0 220 0 35 -90");scene=421;wait=220;
        } else if(scene==421) {
            capture(mc,"17-under-close.png");
            if(Boolean.getBoolean("cloudscape.validation.sharpFocus")){scene=8;wait=40;return;}
            command(mc,"time set 12500");command(mc,"tp CloudscapeTest 0 155 0 90 -12");scene=2;wait=150;
        } else if (scene == 2) {
            capture(mc, "02-sunset.png");
            if (Boolean.getBoolean("cloudscape.validation.performance") && !Boolean.getBoolean("cloudscape.validation.motion")) {
                prepareWater(mc); scene = 6; wait = 180; return;
            }
            if (Boolean.getBoolean("cloudscape.validation.quick")) {
                if (Boolean.getBoolean("cloudscape.validation.opaqueEdge")) prepareOpaqueEdge(mc);
                else { scene = 8; wait = 40; }
                return;
            }
            command(mc, "time set 6000");
            command(mc, "weather rain");
            command(mc, "tp CloudscapeTest 0 155 0 35 -20");
            scene = 3; wait = 240;
        } else if (scene == 3) {
            capture(mc, "03-rain.png");
            command(mc, "weather clear");
            command(mc, "tp CloudscapeTest 0 " + System.getProperty("cloudscape.validation.insideY", "410") + " 0 35 2");
            scene = 4; wait = 180;
        } else if (scene == 4) {
            capture(mc, "04-inside.png");
            command(mc, "time set 18000");
            command(mc, "tp CloudscapeTest 0 145 0 35 -22");
            scene = 5; wait = 180;
        } else if (scene == 5) {
            capture(mc, "05-night.png");
            prepareWater(mc);
            scene = 6; wait = 180;
        } else if (scene == 6) {
            capture(mc, "06-water-reflection.png");
            if (!Boolean.getBoolean("cloudscape.validation.landscape")) { scene = 8; wait = 40; return; }
            command(mc, "tp CloudscapeTest 96 100 32 -45 -18");
            scene = 7; wait = 220;
        } else if (scene == 7) {
            capture(mc, "07-landscape.png");
            if (Boolean.getBoolean("cloudscape.validation.performance") && !Boolean.getBoolean("cloudscape.validation.motion")) {
                command(mc,"time set 6000");command(mc,"weather rain");command(mc,"tp CloudscapeTest 0 145 0 35 -22");
                scene=410;wait=2600;return;
            }
            command(mc, "tp CloudscapeTest 96 310 -200 0 -8");
            scene = 9; wait = 180;
        } else if(scene==410) {
            capture(mc,"15-overcast-rain.png");
            if(Boolean.getBoolean("cloudscape.validation.releaseFps")){
                command(mc,"tp CloudscapeTest 0 300 0 35 2");scene=430;wait=220;return;
            }
            scene=8;wait=40;
        } else if(scene==430) {
            capture(mc,"18-inside-dense.png");
            command(mc,"tp CloudscapeTest 0 330 0 35 -10");scene=431;wait=220;
        } else if(scene==431) {
            capture(mc,"19-inside-upper.png");scene=8;wait=40;
        } else if (scene == 9) {
            capture(mc, "08-closeup.png");
            command(mc, "tp CloudscapeTest 96 " + System.getProperty("cloudscape.validation.aboveY", "424") + " 32 -45 20");
            scene = 10; wait = 180;
        } else if (scene == 10) {
            capture(mc, "09-above.png");
            if(Boolean.getBoolean("cloudscape.validation.motion")) {
                command(mc,"gamerule minecraft:advance_time true");
                command(mc,"time set 6000");
                command(mc,"tp CloudscapeTest 96 155 32 -45 -18");
                scene=11;wait=200;
            } else { scene = 8; wait = 40; }
        } else if(scene==11) {
            capture(mc,"10-evolving.png");
            command(mc,"tp CloudscapeTest 112 155 32 -10 -24");
            scene=12;wait=10;
        } else if(scene==12) {
            capture(mc,"11-camera-disocclusion.png");
            command(mc,"time set 12500");
            command(mc,"weather thunder");
            scene=13;wait=Boolean.getBoolean("cloudscape.validation.recordMotion")?2600:180;
        } else if(scene==13) {
            capture(mc,"12-weather-transition.png");
            if(Boolean.getBoolean("cloudscape.validation.recordMotion")) {
                command(mc,"weather clear");scene=600;wait=2600;return;
            }
            prepareOpaqueEdge(mc);
        } else if(scene==600) {
            capture(mc,"16-clearing.png");
            dev.cloudscape.volumetric.RuntimeConfigDiagnostics.stopWind();
            scene=601;wait=200;
        } else if(scene==601) {
            capture(mc,"17-zero-wind.png");
            command(mc,"tp CloudscapeTest 65535.75 155 -65536.25 35 -20");
            scene=602;wait=100;
        } else if(scene==602) {
            capture(mc,"18-wrap-before.png");
            command(mc,"tp CloudscapeTest 65536.25 155 -65535.75 35 -20");
            scene=603;wait=100;
        } else if(scene==603) {
            capture(mc,"19-wrap-after.png");
            command(mc,"time set 18000");
            scene=604;wait=100;
        } else if(scene==604) {
            capture(mc,"20-clock-jump.png");
            command(mc,"execute in minecraft:the_nether run tp CloudscapeTest 0 150 0 0 0");
            scene=605;wait=120;
        } else if(scene==605) {
            if(!mc.level.dimension().identifier().toString().equals("minecraft:the_nether"))throw new IllegalStateException("Nether fixture transition failed");
            capture(mc,"21-dimension-nether.png");
            command(mc,"execute in minecraft:overworld run tp CloudscapeTest 112 155 32 -10 -24");
            scene=606;wait=200;
        } else if(scene==606) {
            if(!mc.level.dimension().identifier().toString().equals("minecraft:overworld"))throw new IllegalStateException("Overworld fixture return failed");
            capture(mc,"22-dimension-return.png");
            prepareOpaqueEdge(mc);
        } else if(scene==14) {
            if (!mc.level.getBlockState(new net.minecraft.core.BlockPos(0, 250, 64)).is(net.minecraft.world.level.block.Blocks.STONE))
                throw new IllegalStateException("Opaque edge fixture was not created");
            capture(mc,"13-opaque-edge.png");
            if(Boolean.getBoolean("cloudscape.validation.distantHorizons")) {
                command(mc,"fill -16 128 64 16 319 64 minecraft:air replace minecraft:stone");
                command(mc,"tp CloudscapeTest 0 145 400 180 0");
                scene=15;wait=200;return;
            }
            scene=8;wait=40;
        } else if(scene==15) {
            dhWall(mc,false);
            System.out.println("CLOUDSCAPE DH FIXTURE: opaque wall at Z=384, outside native eight-chunk radius");
            scene=16;wait=200;
        } else if(scene==16) {
            if(!mc.level.getBlockState(new net.minecraft.core.BlockPos(0,250,384)).is(net.minecraft.world.level.block.Blocks.STONE))
                throw new IllegalStateException("DH wall fixture was not created");
            command(mc,"tp CloudscapeTest 0 145 0 0 -15");
            scene=17;wait=600;
        } else if(scene==17) {
            capture(mc,"14-dh-opaque-edge.png");
            command(mc,"tp CloudscapeTest 0 145 400 180 0");
            scene=18;wait=100;
        } else if(scene==18) {
            dhWall(mc,true);
            scene=8;wait=60;
        } else {
            System.out.println("CLOUDSCAPE VALIDATION COMPLETE");
            mc.stop();
        }
    }

    private static void prepareOpaqueEdge(Minecraft mc) {
        command(mc,"weather clear");
        command(mc,"time set 6000");
        command(mc,"fill -16 128 64 16 319 64 minecraft:stone");
        command(mc,"tp CloudscapeTest 0 145 0 0 -15");
        scene=14;wait=180;
    }

    private static void dhWall(Minecraft mc,boolean remove) {
        // A 16-block-thick wall survives DH horizontal LOD reduction. Every
        // command stays under the native fill limit and below world Y=320.
        for(int x=-48;x<48;x+=16)for(int y=128;y<320;y+=96)
            command(mc,"fill "+x+" "+y+" 376 "+(x+15)+" "+(y+95)+" 391 "+(remove?"minecraft:air replace minecraft:stone":"minecraft:stone"));
    }

    private static void prepareWater(Minecraft mc) {
        command(mc, "time set 6000");
        command(mc, "weather clear");
        command(mc, "fill -48 127 -48 48 127 48 minecraft:stone");
        command(mc, "fill -48 128 -48 48 128 48 minecraft:water");
        command(mc, "tp CloudscapeTest 0 131 -35 0 8");
    }

    private static void command(Minecraft mc, String command) {
        var server = mc.getSingleplayerServer();
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
    }

    private static void capture(Minecraft mc, String name) {
        System.out.println("CLOUDSCAPE WEATHER "+name+" "+dev.cloudscape.volumetric.WeatherRuntime.diagnostic());
        LodReadiness.capture(name);
        System.out.printf(java.util.Locale.ROOT,"CLOUDSCAPE SCENE INPUT %s clock=%d rain=%.6f yaw=%.5f pitch=%.5f position=%s%n",name,
            mc.level.getOverworldClockTime(),mc.level.getRainLevel(1),mc.player.getYRot(),mc.player.getXRot(),mc.player.position());
        try { System.out.println("CLOUDSCAPE WEATHER_STATE "+name+" "+Class.forName("dev.cloudscape.volumetric.WeatherRuntime").getMethod("diagnostic").invoke(null)); } catch(ReflectiveOperationException oldVersion) {}
        if(Boolean.getBoolean("cloudscape.validation.performance"))
            System.out.println("CLOUDSCAPE EFFECTIVE FRAME LIMIT "+mc.getFramerateLimitTracker().getFramerateLimit()+" throttleReason="+mc.getFramerateLimitTracker().getThrottleReason()+" benchmarkOverride=true");
        FrameMetrics.capture(name);
        AtlasDiagnostics.capture();
        DhDiagnostics.capture(name);
        if (System.getProperty("cloudscape.densityReuse.prepare.builds") != null) {
            StringBuilder reuse = new StringBuilder("CLOUDSCAPE DENSITY_REUSE_CAPTURE scene=").append(name);
            for (String key : new String[]{"builds", "reuses", "fallbacks", "invalidations", "textures", "mode", "reason",
                    "lastTexture", "worldTime", "worldDay", "rainBits", "frameBits", "noiseTexture"})
                reuse.append(' ').append(key).append('=').append(System.getProperty("cloudscape.densityReuse.prepare." + key, "missing"));
            System.out.println(reuse);
        }
        System.out.println("CLOUDSCAPE CAPTURE " + name + " FPS=" + mc.getFps() + " position=" + mc.player.position());
        Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1,
            message -> System.out.println("CLOUDSCAPE SCREENSHOT " + message.getString()));
    }
}
