package dev.cloudscape.validation;

import java.nio.FloatBuffer;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.targets.RenderTargets;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL45C.*;

/** Development-only readback of the real Iris R32F density target. */
final class DensityAtlasDiagnostics {
    static void capture() {
        var pipeline=Iris.getPipelineManager().getPipelineNullable();
        if (!(pipeline instanceof IrisRenderingPipeline iris)) throw new AssertionError("Iris pipeline missing");
        int packBuffer=glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        int[] keys={GL_PACK_ALIGNMENT,GL_PACK_ROW_LENGTH,GL_PACK_SKIP_PIXELS,GL_PACK_SKIP_ROWS,GL_PACK_IMAGE_HEIGHT,GL_PACK_SKIP_IMAGES};
        int[] values=new int[keys.length];
        for(int i=0;i<keys.length;i++)values[i]=glGetInteger(keys[i]);
        try {
            glBindBuffer(GL_PIXEL_PACK_BUFFER,0);
            for(int key:keys)glPixelStorei(key,key==GL_PACK_ALIGNMENT?4:0);
            var field=IrisRenderingPipeline.class.getDeclaredField("renderTargets");field.setAccessible(true);
            var target=((RenderTargets)field.get(iris)).get(20);
            if(target==null)throw new AssertionError("Missing density target20");
            int size=Integer.getInteger("cloudscape.validation.densityAtlasSize",1536);
            if(target.getWidth()!=size*8 || target.getHeight()!=size)throw new AssertionError("Wrong density atlas dimensions: "+target.getWidth()+"x"+target.getHeight());
            int nonzeroTotal=0;
            boolean sparse=Boolean.getBoolean("cloudscape.validation.densityAtlasSparse");
            FloatBuffer pixels=BufferUtils.createFloatBuffer(sparse?target.getWidth():target.getWidth()*target.getHeight());
            for(int texture:new int[]{target.getMainTexture(),target.getAltTexture()}) {
                int format=glGetTextureLevelParameteri(texture,0,GL_TEXTURE_INTERNAL_FORMAT);
                if(format!=GL_R32F)throw new AssertionError("Actual density format="+format+", expected R32F="+GL_R32F);
                if(glGetTextureLevelParameteri(texture,0,GL_TEXTURE_WIDTH)!=size*8
                        ||glGetTextureLevelParameteri(texture,0,GL_TEXTURE_HEIGHT)!=size)
                    throw new AssertionError("Actual density storage dimensions differ from Iris metadata");
                int nonzero=0;float max=0;long hash=0xcbf29ce484222325L;
                int[] rows=sparse?new int[]{0,size/5,size/2,size-1}:new int[]{-1};
                for(int row:rows) {
                    if(sparse)glGetTextureSubImage(texture,0,0,row,0,size*8,1,1,GL_RED,GL_FLOAT,pixels);
                    else glGetTextureImage(texture,0,GL_RED,GL_FLOAT,pixels);
                    int error=glGetError();if(error!=GL_NO_ERROR)throw new AssertionError("Density readback GL error="+error);
                    for(int i=0;i<pixels.limit();i++) {
                        float v=pixels.get(i);
                        if(!Float.isFinite(v)||v<0||v>1)throw new AssertionError("Invalid density="+v);
                        if(v>0)nonzero++;max=Math.max(max,v);
                        hash=(hash^Float.floatToIntBits(v))*0x100000001b3L;
                    }
                }
                nonzeroTotal+=nonzero;
                System.out.printf(java.util.Locale.ROOT,"CLOUDSCAPE DENSITY ATLAS texture=%d format=R32F size=%dx%d sparse=%s sampledValues=%d nonzero=%d max=%.5f hash=%016x%n",texture,target.getWidth(),target.getHeight(),sparse,pixels.limit()*rows.length,nonzero,max,hash);
            }
            if(nonzeroTotal==0)throw new AssertionError("Density atlas never populated");
        } catch(ReflectiveOperationException e){throw new AssertionError(e);}
        finally {
            for(int i=0;i<keys.length;i++)glPixelStorei(keys[i],values[i]);
            glBindBuffer(GL_PIXEL_PACK_BUFFER,packBuffer);
        }
    }
}
