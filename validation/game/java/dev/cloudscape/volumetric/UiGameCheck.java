package dev.cloudscape.volumetric;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.targets.RenderTargets;
import static org.lwjgl.opengl.GL45C.*;

/** Development mod only: actual screens, mouse dispatch, Iris reloads and GPU targets. */
public final class UiGameCheck {
    private static int stage, wait = 100;
    private static CloudConfig original;
    private static byte[] originalBytes;
    private static boolean done;
    private static int checks;
    private static String originalStyle;
    private static Path configFile;
    private static int reimaginedLive;
    private static long renderedFrames, captureAfterFrame;
    private static String pendingCapture;
    private static int totalTicks, worldLive;

    public static void frameFinished() { renderedFrames++; }

    private static void require(boolean okay, String label) {
        if (!okay) throw new AssertionError(label);
        checks++; System.out.println("CLOUDSCAPE UI PASS " + label);
    }

    public static boolean tick(Minecraft mc) {
        if(++totalTicks>6000)throw new AssertionError("UI/lifetime test timeout");
        if(pendingCapture!=null && renderedFrames>captureAfterFrame) {
            String name=pendingCapture;pendingCapture=null;
            Screenshot.grab(mc.gameDirectory,name,mc.gameRenderer.mainRenderTarget(),1,m->System.out.println("CLOUDSCAPE UI CAPTURE "+name));
        }
        if (done) return false;
        if(stage<20 && (mc.level == null || mc.player == null)) return false;
        if(stage==21 && mc.level!=null || stage==22 && (mc.level==null || mc.player==null))return true;
        if (wait-- > 0) return true;
        try {
            switch (stage++) {
                case 0 -> {
                    configFile = mc.gameDirectory.toPath().resolve("config/cloudscape-volumetric.json");
                    originalBytes = Files.exists(configFile) ? Files.readAllBytes(configFile) : null;
                    original = UnboundAdapter.settings().copy();
                    if(Boolean.getBoolean("cloudscape.validation.uiWorldLifecycle")){
                        healthy();atlas(true);worldLive=ResourceProbe.live();stage=20;return true;
                    }
                    String expectedNotice=System.getProperty("cloudscape.validation.expectedConfigNotice");
                    if(expectedNotice!=null) require(CloudSettings.notice().equals(expectedNotice),"startup config notice="+expectedNotice);
                    if(Boolean.getBoolean("cloudscape.validation.uiRestartCheck"))
                        require(original.coverage==.67f && original.windSpeed==1.25f,"chosen settings survive a real game restart");
                    if(Boolean.getBoolean("cloudscape.validation.uiLegacyCheck"))
                        require(original.viewSteps==117 && original.windSpeed==1.25f && original.reimaginedThickness==128,"legacy chosen values and explicit old-default migration");
                    originalStyle = Iris.getCurrentPack().orElseThrow().getShaderPackOptions().getOptionValues().getStringValueOrDefault("CLOUD_STYLE_DEFINE");
                    mc.gui.setScreen(new OptionsScreen(null,mc.options));
                    capture(mc,"ui-entry-options.png");
                }
                case 1 -> {
                    click(mc,"Cloudscape");
                    require(mc.gui.screen() instanceof CloudSettingsScreen,"Options → Cloudscape opens actual screen");
                    bounds(mc.gui.screen()); capture(mc,"ui-main.png");
                }
                case 2 -> {
                    slider(mc,"coverage",.8);
                    require(draft(mc).coverage != original.coverage,"mouse input edits detached draft");
                    click(mc,CloudSettingsScreen.text("cancel").getString());
                    require(UnboundAdapter.settings().defines().equals(original.defines()),"Cancel preserves active values");
                    require(Arrays.equals(originalBytes, Files.exists(configFile) ? Files.readAllBytes(configFile) : null),"Cancel does not write config");
                    if(Boolean.getBoolean("cloudscape.validation.uiRestartCheck")){stage=11;return true;}
                    if(Boolean.getBoolean("cloudscape.validation.uiConfigCheck")) {
                        click(mc,"Cloudscape");click(mc,CloudSettingsScreen.text("reset").getString());click(mc,CloudSettingsScreen.text("apply").getString());
                        require(Files.exists(configFile)&&new ConfigStore(configFile).load().defines().equals(new CloudConfig().defines()),"explicit reset/apply creates a valid config");
                        if(originalBytes!=null)try(var backups=Files.list(configFile.getParent())) {
                            require(backups.filter(p->p.getFileName().toString().startsWith("cloudscape-volumetric.json.")).anyMatch(p->{
                                try{return Arrays.equals(originalBytes,Files.readAllBytes(p));}catch(java.io.IOException error){throw new RuntimeException(error);}
                            }),"exact original config bytes backed up in real game");
                        }
                        healthy();atlas(true);capture(mc,"ui-config-recovered.png");stage=11;
                    }
                }
                case 3 -> {
                    click(mc,"Cloudscape");
                    click(mc,CloudSettingsScreen.text("page.1").getString());
                    bounds(mc.gui.screen()); capture(mc,"ui-quality.png");
                }
                case 4 -> {
                    clickPrefix(mc,CloudSettingsScreen.text("quality").getString()+":");
                    require(CloudQuality.of(draft(mc)) != CloudQuality.of(original),"Quality preset changes budgets");
                    require(draft(mc).coverage == original.coverage && draft(mc).maxDistance == original.maxDistance,"Quality leaves shape and distance alone");
                    click(mc,CloudSettingsScreen.text("page.2").getString());
                    bounds(mc.gui.screen()); capture(mc,"ui-advanced.png");
                }
                case 5 -> {
                    click(mc,CloudSettingsScreen.text("page.3").getString());
                    bounds(mc.gui.screen());
                    int view=draft(mc).viewSteps, reflection=draft(mc).reflectionSteps, light=draft(mc).lightSteps;
                    clickPrefix(mc,CloudSettingsScreen.text("shapeMode").getString()+":");
                    require(draft(mc).shapeMode==1 && UnboundAdapter.settings().shapeMode==0,"Varied mode is a detached opt-in draft");
                    for(var child:mc.gui.screen().children())if(child instanceof Button button && button.getMessage().getString().startsWith(CloudSettingsScreen.text("shapeMode").getString()+":")) require(mc.font.width(button.getMessage())<=button.getWidth()-8,"artistic mode label fits at current resolution");
                    clickPrefix(mc,CloudSettingsScreen.text("weatherVariability").getString()+":");
                    slider(mc,"transitionSeconds",.3);
                    require(draft(mc).viewSteps==view && draft(mc).reflectionSteps==reflection && draft(mc).lightSteps==light,"artistic changes preserve quality budgets");
                    click(mc,CloudSettingsScreen.text("apply").getString());
                    healthy();atlas(true);
                    require(UnboundAdapter.settings().shapeMode==1 && !UnboundAdapter.settings().weatherVariability,"Apply installs varied forms and weather draft after shader reload");
                    require(new ConfigStore(configFile).load().shapeMode==1,"Varied mode persisted");
                    capture(mc,"ui-weather-applied.png");
                    stage=50;
                }
                case 50 -> {
                    click(mc,CloudSettingsScreen.text("reset").getString());
                    require(draft(mc).defines().equals(new CloudConfig().defines()),"Reset changes draft to original defaults");
                    require(UnboundAdapter.settings().shapeMode==1,"Reset waits for Apply");
                    require(UnboundAdapter.settings().shapeMode==1 && draft(mc).shapeMode==0,"Reset waits before changing active artistic mode");
                    mc.gui.screen().onClose();click(mc,"Cloudscape");
                    require(draft(mc).shapeMode==1,"closing the reset draft discards it");
                    click(mc,CloudSettingsScreen.text("reset").getString());
                    slider(mc,"coverage",.7);
                    click(mc,CloudSettingsScreen.text("apply").getString());
                    require(UnboundAdapter.settings().coverage == draft(mc).coverage,"Apply installs all values");
                    require(new ConfigStore(configFile).load().defines().equals(UnboundAdapter.settings().defines()),"Saved config matches active values");
                    healthy(); capture(mc,"ui-applied.png"); atlas(true);
                    reimaginedLive=ResourceProbe.live();stage=6;
                }
                case 6 -> {
                    Iris.getShaderPackOptionQueue().put("CLOUD_STYLE_DEFINE","3"); Iris.reload(); healthy(); atlas(false);
                    capture(mc,"ui-unbound-no-atlas.png");
                }
                case 7 -> {
                    Iris.getShaderPackOptionQueue().put("CLOUD_STYLE_DEFINE","1"); Iris.reload(); healthy(); atlas(true);
                }
                case 8 -> {
                    Iris.reload(); healthy(); atlas(true);
                    require(ResourceProbe.live()==reimaginedLive,"same-style reload does not accumulate targets");
                }
                case 9 -> {
                    clickPrefix(mc,CloudSettingsScreen.text("enabled").getString()+":");
                    click(mc,CloudSettingsScreen.text("apply").getString());
                    require(!UnboundAdapter.settings().enabled,"Disable applies"); healthy(); atlas(false);
                    capture(mc,"ui-disabled.png");
                }
                case 10 -> {
                    CloudConfig restored=original.copy();
                    if(Boolean.getBoolean("cloudscape.validation.uiPersistEdits")){restored.coverage=.67f;restored.windSpeed=1.25f;}
                    CloudSettings.apply(restored);
                    Iris.getShaderPackOptionQueue().put("CLOUD_STYLE_DEFINE",originalStyle); Iris.reload(); healthy();
                    require(UnboundAdapter.settings().defines().equals(restored.defines()),"Expected applied values installed after UI checks");
                    mc.gui.setScreen(new CloudSettingsScreen(null));
                    require(draft(mc).defines().equals(restored.defines()),"Reopening preserves applied values");
                    capture(mc,"ui-restored.png");
                }
                case 20 -> {mc.disconnectWithSavingScreen();wait=120;return true;}
                case 21 -> {
                    ResourceProbe.capture("after-world-leave");
                    require(Iris.getPipelineManager().getPipelineNullable()==null && ResourceProbe.live()==0,"world leave releases all Iris targets including atlas");
                    mc.createWorldOpenFlows().openWorld("Cloudscape-Validation",()->{});wait=120;return true;
                }
                case 22 -> {
                    healthy();atlas(true);
                    require(ResourceProbe.live()==worldLive,"world reopen creates a fresh bounded target set");
                    stage=11;
                }
                default -> {
                    mc.gui.setScreen(null);
                    System.out.println("CLOUDSCAPE UI COMPLETE checks="+checks);
                    ResourceProbe.capture("ui-complete");
                    done = true;
                    if(Boolean.getBoolean("cloudscape.validation.uiOnly")) {System.out.println("CLOUDSCAPE VALIDATION COMPLETE");mc.stop();}
                }
            }
            wait = 35;
            return true;
        } catch (Exception error) { throw new AssertionError("UI stage " + (stage - 1),error); }
    }

    private static CloudConfig draft(Minecraft mc) throws Exception {
        var field = CloudSettingsScreen.class.getDeclaredField("draft");field.setAccessible(true);
        return (CloudConfig)field.get(mc.gui.screen());
    }
    private static void bounds(Screen screen) {
        for(var child:screen.children()) if(child instanceof AbstractWidget widget)
            require(widget.getX() >= 0 && widget.getRight() <= screen.width && widget.getY() >= 0 && widget.getBottom() <= screen.height,
                "widget in bounds: "+widget.getMessage().getString());
    }
    private static void click(Minecraft mc,String label) { clickButton(mc,label,false); }
    private static void clickPrefix(Minecraft mc,String label) { clickButton(mc,label,true); }
    private static void clickButton(Minecraft mc,String label,boolean prefix) {
        Screen screen=mc.gui.screen();
        for(var child:screen.children())if(child instanceof Button button && button.active
            && (prefix ? button.getMessage().getString().startsWith(label) : button.getMessage().getString().equals(label))) {
            var event = new MouseButtonEvent(button.getX()+button.getWidth()/2.0,button.getY()+10,new MouseButtonInfo(1,0));
            System.out.println("CLOUDSCAPE UI CLICK label="+label+" screen="+screen.getClass().getName()+" bounds="+button.getX()+","+button.getY()+","+button.getWidth()+","+button.getHeight());
            for(var candidate:screen.children())if(candidate instanceof AbstractWidget widget && widget.isMouseOver(event.x(),event.y()))
                System.out.println("CLOUDSCAPE UI HIT "+widget.getClass().getName()+" label="+widget.getMessage().getString());
            require(screen.mouseClicked(event,false),"mouse click handled: "+label);screen.mouseReleased(event);return;
        }
        throw new AssertionError("Missing active button: "+label);
    }
    private static void slider(Minecraft mc,String key,double fraction) {
        Screen screen=mc.gui.screen();String label=CloudSettingsScreen.text(key).getString()+":";
        for(var child:screen.children())if(child instanceof AbstractSliderButton slider && slider.getMessage().getString().startsWith(label)) {
            var event=new MouseButtonEvent(slider.getX()+4+fraction*(slider.getWidth()-8),slider.getY()+10,new MouseButtonInfo(1,0));
            require(screen.mouseClicked(event,false),"slider click handled: "+key);screen.mouseReleased(event);return;
        }
        throw new AssertionError("Missing slider: "+key);
    }
    private static void healthy() {
        require(Iris.getStoredError().isEmpty() && !Iris.isFallback(),"Iris compile succeeds without fallback");
        require(Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline,"actual Iris pipeline active");
    }
    private static void atlas(boolean expected) throws Exception {
        var pipeline=(IrisRenderingPipeline)Iris.getPipelineManager().getPipelineNullable();
        var field=IrisRenderingPipeline.class.getDeclaredField("renderTargets");field.setAccessible(true);
        var targets=(RenderTargets)field.get(pipeline);
        var target=targets.getRenderTargetCount()>20?targets.get(20):null;
        require((target!=null)==expected,"actual atlas allocation expected="+expected);
        if(target!=null) {
            int requested=UnboundAdapter.settings().densityAtlasSize;
            require(target.getWidth()==requested*8 && target.getHeight()==requested,"requested atlas dimensions retained");
            for(int texture:new int[]{target.getMainTexture(),target.getAltTexture()})
                require(glGetTextureLevelParameteri(texture,0,GL_TEXTURE_INTERNAL_FORMAT)==GL_R32F,"actual GPU R32F storage");
        }
        System.out.println("CLOUDSCAPE UI RESOURCE atlas="+expected+" liveTargets="+targets.getRenderTargetCount());
        ResourceProbe.capture("atlas-"+expected);
    }
    private static void capture(Minecraft mc,String name) {
        pendingCapture=name;captureAfterFrame=renderedFrames;
    }
}
