package dev.cloudscape.validation;

import java.nio.FloatBuffer;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.targets.RenderTargets;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL45C.*;

/** Development-only check of actual Iris targets, not a separately allocated oracle texture. */
final class AtlasDiagnostics {
    static void capture() {
        if (Boolean.getBoolean("cloudscape.validation.densityAtlasDiagnostics")) DensityAtlasDiagnostics.capture();
        if (!Boolean.getBoolean("cloudscape.validation.atlasDiagnostics")) return;
        var pipeline=Iris.getPipelineManager().getPipelineNullable();
        if (!(pipeline instanceof IrisRenderingPipeline iris)) throw new AssertionError("Iris pipeline missing");
        int packBuffer=glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        int[] packKeys={GL_PACK_ALIGNMENT,GL_PACK_ROW_LENGTH,GL_PACK_SKIP_PIXELS,GL_PACK_SKIP_ROWS,GL_PACK_IMAGE_HEIGHT,GL_PACK_SKIP_IMAGES};
        int[] packValues=new int[packKeys.length];
        for(int i=0;i<packKeys.length;++i)packValues[i]=glGetInteger(packKeys[i]);
        try {
            // The screenshot backend may leave a pixel-pack buffer and row
            // layout selected. A Java FloatBuffer read requires client memory.
            glBindBuffer(GL_PIXEL_PACK_BUFFER,0);
            for(int key:packKeys)glPixelStorei(key,key==GL_PACK_ALIGNMENT?4:0);
            var field=IrisRenderingPipeline.class.getDeclaredField("renderTargets");
            field.setAccessible(true);
            var targets=(RenderTargets)field.get(iris);
            for(int index:new int[]{20,21,22}) {
                var target=targets.get(index);
                if(target==null) throw new AssertionError("Missing atlas target "+index);
                int expected=index==21?GL_RG16F:GL_RGBA16F;
                FloatBuffer pixels=BufferUtils.createFloatBuffer(target.getWidth()*target.getHeight()*4);
                float max=0;int nonzero=0;
                // Iris swaps the two texture identities across frames. Inspect
                // both storage objects; the compile-time flip set alone does
                // not identify the live storage from a client-tick callback.
                for(int texture:new int[]{target.getMainTexture(),target.getAltTexture()}) {
                    int format=glGetTextureLevelParameteri(texture,0,GL_TEXTURE_INTERNAL_FORMAT);
                    if(format!=expected)throw new AssertionError("Atlas "+index+" actual GPU format "+format+", expected "+expected);
                    glGetTextureImage(texture,0,GL_RGBA,GL_FLOAT,pixels);
                    int error=glGetError();
                    if(error!=GL_NO_ERROR)throw new AssertionError("Atlas readback OpenGL error "+error);
                    for(int i=0;i<pixels.limit();i+=4){
                        float value=pixels.get(i);
                        if(!Float.isFinite(value))throw new AssertionError("Non-finite atlas "+index);
                        max=Math.max(max,value);if(value>0.001f)++nonzero;
                    }
                }
                if(nonzero==0)throw new AssertionError("Empty atlas "+index);
                if(index==21&&max<=1.0f)throw new AssertionError("Sun optical depths lost their floating-point range");
                System.out.printf(java.util.Locale.ROOT,"CLOUDSCAPE ATLAS %d format=%s size=%dx%d maxR=%.4f nonzero=%d%n",
                    index,target.getInternalFormat(),target.getWidth(),target.getHeight(),max,nonzero);
            }
            if(Boolean.getBoolean("cloudscape.validation.distantHorizons")) {
                var compat=iris.getDHCompat();
                if(!net.irisshaders.iris.compat.dh.DHCompat.hasRenderingEnabled() || compat.getInstance()==null)
                    throw new AssertionError("Distant Horizons shader integration is not enabled");
                int texture=compat.getDepthTexNoTranslucent();
                int width=glGetTextureLevelParameteri(texture,0,GL_TEXTURE_WIDTH),height=glGetTextureLevelParameteri(texture,0,GL_TEXTURE_HEIGHT);
                FloatBuffer depths=BufferUtils.createFloatBuffer(width*height);
                glGetTextureImage(texture,0,GL_DEPTH_COMPONENT,GL_FLOAT,depths);
                int error=glGetError();if(error!=GL_NO_ERROR)throw new AssertionError("DH depth read error "+error);
                float near=net.irisshaders.iris.compat.dh.DHCompat.getNearPlane(),far=net.irisshaders.iris.compat.dh.DHCompat.getFarPlane();
                int occupied=0,distant=0;
                int vanilla=net.minecraft.client.Minecraft.getInstance().options.getEffectiveRenderDistance()*16;
                for(int i=0;i<depths.limit();++i) {
                    float d=depths.get(i);
                    if(!Float.isFinite(d))throw new AssertionError("Non-finite DH depth");
                    if(d>0.0f && d<0.999999f){++occupied;float z=2.0f*near*far/(far+near-(d*2.0f-1.0f)*(far-near));if(z>vanilla)++distant;}
                }
                System.out.printf(java.util.Locale.ROOT,"CLOUDSCAPE DH active=true depthPixels=%d distantPixels=%d near=%.3f far=%.3f lodDistance=%d vanillaDistance=%d%n",
                    occupied,distant,near,far,net.irisshaders.iris.compat.dh.DHCompat.getRenderDistance(),vanilla);
                if(Boolean.getBoolean("cloudscape.validation.dhFixtureOnly")) {
                    var metadata=targets.get(15);var color=targets.get(14);
                    int center=((metadata.getHeight()/2)*metadata.getWidth()+metadata.getWidth()/2)*4;
                    FloatBuffer metaPixels=BufferUtils.createFloatBuffer(metadata.getWidth()*metadata.getHeight()*4);
                    FloatBuffer colorPixels=BufferUtils.createFloatBuffer(color.getWidth()*color.getHeight()*4);
                    int[] metaIds={metadata.getMainTexture(),metadata.getAltTexture()},colorIds={color.getMainTexture(),color.getAltTexture()};
                    boolean found=false;
                    for(int i=0;i<2;++i){
                        glGetTextureImage(metaIds[i],0,GL_RGBA,GL_FLOAT,metaPixels);
                        float distance=metaPixels.get(center+1);
                        if(distance>300&&distance<500){
                            glGetTextureImage(colorIds[i],0,GL_RGBA,GL_FLOAT,colorPixels);
                            float alpha=colorPixels.get(center+3);
                            if(alpha>.0001)throw new AssertionError("Cloud density behind the DH wall leaked into its central pixel: "+alpha);
                            System.out.printf(java.util.Locale.ROOT,"CLOUDSCAPE DH WALL opaque=true cachedDistance=%.3f cloudAlpha=%.6f vanillaDistance=%d%n",distance,alpha,vanilla);
                            found=true;
                        }
                    }
                    if(!found)throw new AssertionError("DH wall did not appear in the cloud cache with its real distant endpoint");
                }
            }
        } catch(ReflectiveOperationException error) {throw new AssertionError(error);}
        finally {
            for(int i=0;i<packKeys.length;++i)glPixelStorei(packKeys[i],packValues[i]);
            glBindBuffer(GL_PIXEL_PACK_BUFFER,packBuffer);
        }
    }
}
