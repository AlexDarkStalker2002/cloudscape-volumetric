package dev.cloudscape.volumetric;

import java.util.IdentityHashMap;
import net.irisshaders.iris.targets.RenderTarget;
import static org.lwjgl.opengl.GL11.glIsTexture;

/** Counts actual Iris target lifetimes; included only in the isolated validation mod. */
public final class ResourceProbe {
    private static final IdentityHashMap<RenderTarget, int[]> live = new IdentityHashMap<>();
    private static int created, destroyed;
    public static void created(RenderTarget target) {
        if(!Boolean.getBoolean("cloudscape.validation.ui"))return;
        if(live.put(target,new int[]{target.getMainTexture(),target.getAltTexture()})!=null)throw new AssertionError("Target registered twice");
        created++;
    }
    public static int[] beforeDestroy(RenderTarget target) {
        if(!Boolean.getBoolean("cloudscape.validation.ui"))return null;
        int[] ids=live.remove(target);if(ids==null)throw new AssertionError("Untracked/double target destruction");
        destroyed++;return ids;
    }
    public static void afterDestroy(int[] ids) {
        if(ids==null)return;
        for(int id:ids)if(glIsTexture(id))throw new AssertionError("Destroyed Iris target still a GPU texture: "+id);
    }
    public static int live() { return live.size(); }
    public static void capture(String event) {
        if(created-destroyed!=live.size())throw new AssertionError("Target lifetime accounting mismatch");
        System.out.println("CLOUDSCAPE RESOURCE event="+event+" created="+created+" destroyed="+destroyed+" live="+live.size());
    }
}
