package dev.cloudscape.validation;

import java.nio.FloatBuffer;
import java.util.Locale;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.compat.dh.DHCompat;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.targets.RenderTargets;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL45C.*;

/** Development-only readback also works with the original, full-resolution 0.5 renderer. */
final class DhDiagnostics {
    static void capture(String scene) {
        if (!Boolean.getBoolean("cloudscape.validation.dhDiagnostics")) return;
        if (!(Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline iris)
                || !DHCompat.hasRenderingEnabled() || iris.getDHCompat().getInstance() == null)
            throw new AssertionError("DH shader integration is inactive");
        int packBuffer=glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        int[] keys={GL_PACK_ALIGNMENT,GL_PACK_ROW_LENGTH,GL_PACK_SKIP_PIXELS,GL_PACK_SKIP_ROWS,GL_PACK_IMAGE_HEIGHT,GL_PACK_SKIP_IMAGES};
        int[] saved=new int[keys.length];
        for(int i=0;i<keys.length;i++) saved[i]=glGetInteger(keys[i]);
        try {
            glBindBuffer(GL_PIXEL_PACK_BUFFER,0);
            for(int key:keys) glPixelStorei(key,key==GL_PACK_ALIGNMENT?4:0);
            int texture=iris.getDHCompat().getDepthTexNoTranslucent();
            int width=glGetTextureLevelParameteri(texture,0,GL_TEXTURE_WIDTH);
            int height=glGetTextureLevelParameteri(texture,0,GL_TEXTURE_HEIGHT);
            FloatBuffer depth=BufferUtils.createFloatBuffer(width*height);
            glGetTextureImage(texture,0,GL_DEPTH_COMPONENT,GL_FLOAT,depth);
            int occupied=0;
            for(int i=0;i<depth.limit();i++) {
                float d=depth.get(i);
                if(!Float.isFinite(d)) throw new AssertionError("Non-finite DH depth");
                if(d>0 && d<1) occupied++;
            }
            float center=depth.get((height/2)*width+width/2);
            // Client ticks do not necessarily retain the projection used for
            // this texture. Validate occupancy here; inspect occlusion in the
            // real distant-wall render rather than inferring a CPU distance.
            System.out.printf(Locale.ROOT,"CLOUDSCAPE DH05 %s active=true depthPixels=%d centerRawDepth=%.7f lodBlocks=%d%n",
                scene,occupied,center,DHCompat.getRenderDistance());
            if(scene.equals("14-dh-opaque-edge.png")) {
                if(occupied==0) throw new AssertionError("No rendered DH geometry in the distant-wall scene");
                var field=IrisRenderingPipeline.class.getDeclaredField("renderTargets");
                field.setAccessible(true);
                var targets=(RenderTargets)field.get(iris);
                var cloudDepth=targets.get(5);
                for(int colorTexture:new int[]{cloudDepth.getMainTexture(),cloudDepth.getAltTexture()}) {
                    FloatBuffer pixels=BufferUtils.createFloatBuffer(cloudDepth.getWidth()*cloudDepth.getHeight()*4);
                    glGetTextureImage(colorTexture,0,GL_RGBA,GL_FLOAT,pixels);
                    float alpha=pixels.get(((cloudDepth.getHeight()/2)*cloudDepth.getWidth()+cloudDepth.getWidth()/2)*4+3);
                    System.out.printf(Locale.ROOT,"CLOUDSCAPE DH05 WALL cloudDepthTexture=%d centerCloudLinearDepth=%.6f%n",colorTexture,alpha);
                }
            }
            if(glGetError()!=GL_NO_ERROR) throw new AssertionError("DH diagnostic readback failed");
        } catch(ReflectiveOperationException error) { throw new AssertionError(error); }
        finally {
            for(int i=0;i<keys.length;i++) glPixelStorei(keys[i],saved[i]);
            glBindBuffer(GL_PIXEL_PACK_BUFFER,packBuffer);
        }
    }
}
