package dev.cloudscape.volumetric;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL33C.*;

/** Tests the actual shipped GLSL on the GPU, independently of the Minecraft/SDL startup path. */
public final class VolumeGpuCheck {
    private static final boolean BENCHMARK_ONLY = Boolean.getBoolean("cloudscape.benchmark");
    private static final int WIDTH = BENCHMARK_ONLY ? Integer.getInteger("cloudscape.width", 2560) : 640;
    private static final int HEIGHT = BENCHMARK_ONLY ? Integer.getInteger("cloudscape.height", 1440) : 360;

    private static int fieldProgram(String vertex, String fragment) {
        int vs=shader(GL_VERTEX_SHADER,vertex),fs=shader(GL_FRAGMENT_SHADER,fragment),program=glCreateProgram();
        glAttachShader(program,vs);glAttachShader(program,fs);glLinkProgram(program);
        if(glGetProgrami(program,GL_LINK_STATUS)==GL_FALSE)throw new AssertionError(glGetProgramInfoLog(program));
        glDeleteShader(vs);glDeleteShader(fs);
        return program;
    }

    private static void checkVoxelField(Path resources,String vertex,String header,int originalColor) throws Exception {
        checkClassicLook(resources, vertex, header);
        checkVariedCloudSizes(resources, vertex, header);
        checkDHSceneDepth(resources, vertex);
        checkVoxelField(resources, vertex, header, originalColor, false, false);
        checkVoxelField(resources, vertex, header, originalColor, true, false);
        checkVoxelField(resources, vertex, header, originalColor, true, true);
    }

    private static void checkDHSceneDepth(Path resources,String vertex) throws Exception {
        String header="""
            uniform sampler2D dhDepthTex;
            const mat4 gbufferProjectionInverse=mat4(2,0,0,0, 0,3,0,0, 0,0,100,0, 0,0,0,1);
            const mat4 dhProjectionInverse=mat4(5,0,0,0, 0,7,0,0, 0,0,1000,0, 0,0,0,1);
            """;
        String footer="""
            out vec4 result;
            void main(){
                int i=int(gl_FragCoord.x);
                float nativeDepth=i==0?.25:i==4?.9:1.0;
                vec4 p=CSCloudViewPosition(vec2(.5),ivec2(i,0),nativeDepth);
                result=vec4(CSCloudPixelIsSky(ivec2(i,0),nativeDepth)?1:0,p.xyz);
            }
            """;
        int texture=glGenTextures();glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,texture);
        glTexImage2D(GL_TEXTURE_2D,0,GL_R32F,6,1,0,GL_RED,GL_FLOAT,new float[]{.75f,.75f,1,0,.25f,.25f});
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        for(boolean dh:new boolean[]{false,true}) {
            int program=fieldProgram(vertex,"#version 330 core\n"+(dh?"#define DISTANT_HORIZONS\n":"")+header
                +Files.readString(resources.resolve("scene_depth.glsl"))+footer);
            glUseProgram(program);glUniform1i(glGetUniformLocation(program,"dhDepthTex"),1);
            glViewport(0,0,6,1);glDrawArrays(GL_TRIANGLES,0,3);
            FloatBuffer pixels=BufferUtils.createFloatBuffer(24);glReadPixels(0,0,6,1,GL_RGBA,GL_FLOAT,pixels);
            float[] sky=dh?new float[]{0,0,1,1,0,0}:new float[]{0,1,1,1,0,1};
            float[] z=dh?new float[]{-50,500,100,100,80,-500}:new float[]{-50,100,100,100,80,100};
            for(int i=0;i<6;++i)if(pixels.get(i*4)!=sky[i]||Math.abs(pixels.get(i*4+3)-z[i])>.001)
                throw new AssertionError("Wrong native/DH depth projection or sky classification: DH="+dh+" case="+i);
            glDeleteProgram(program);
        }
        glDeleteTextures(texture);glActiveTexture(GL_TEXTURE0);glViewport(0,0,WIDTH,HEIGHT);
        System.out.println("PASS 12 native/DH scene-depth cases: independent projection, native priority, LOD terrain, invalid depth and sky");
    }

    private static void checkVariedCloudSizes(Path resources,String vertex,String header) throws Exception {
        String footer="""
            out vec4 result;
            void main(){
                vec3 cell=vec3(floor(gl_FragCoord.x)-320.0,0.0,floor(gl_FragCoord.y)-180.0);
                float column=0.0;
                for(int y=0;y<8;++y){cell.y=float(y);column=max(column,CSVoxelWeight(cell,CSMorphTime()));}
                cell.y=3.0;
                float d=CSVoxelWeight(cell,CSMorphTime());
                float wrap=max(abs(d-CSVoxelWeight(cell+vec3(4096,0,0),CSMorphTime())),abs(d-CSVoxelWeight(cell+vec3(0,0,-4096),CSMorphTime())));
                result=vec4(column,wrap,0,1);
            }
            """;
        int[] largeCounts=new int[2],covered=new int[2];
        for(int variant=0;variant<2;++variant) {
            CloudConfig config=new CloudConfig();config.reimaginedClassicLook=true;config.reimaginedVariedShapes=true;
            config.reimaginedUpperLayer=false;config.reimaginedLargeClouds=variant==0?0:.24f;
            int program=fieldProgram(vertex,"#version 330 core\n#define CLOUDS_REIMAGINED\n"+config.defines()+header
                +Files.readString(resources.resolve("density.glsl"))+footer);
            glUseProgram(program);glUniform1i(glGetUniformLocation(program,"noisetex"),0);glUniform1f(glGetUniformLocation(program,"syncedTime"),70);
            glDrawArrays(GL_TRIANGLES,0,3);
            FloatBuffer pixels=BufferUtils.createFloatBuffer(WIDTH*HEIGHT*4);glReadPixels(0,0,WIDTH,HEIGHT,GL_RGBA,GL_FLOAT,pixels);
            boolean[] occupied=new boolean[WIDTH*HEIGHT],seen=new boolean[WIDTH*HEIGHT];int[] stack=new int[WIDTH*HEIGHT];
            for(int i=0;i<occupied.length;++i){
                float density=pixels.get(i*4),wrap=pixels.get(i*4+1);
                if(!Float.isFinite(density)||density<0||density>1||!Float.isFinite(wrap)||wrap>.0001)
                    throw new AssertionError("Invalid mixed-size density/wrap at "+i);
                occupied[i]=density>.5f;if(occupied[i])++covered[variant];
            }
            int widest=0;
            for(int i=0;i<occupied.length;++i)if(occupied[i]&&!seen[i]){
                int count=0,top=0,left=WIDTH,right=0,bottom=HEIGHT,up=0;boolean edge=false;
                stack[top++]=i;seen[i]=true;
                while(top>0){int p=stack[--top],x=p%WIDTH,y=p/WIDTH;++count;left=Math.min(left,x);right=Math.max(right,x);bottom=Math.min(bottom,y);up=Math.max(up,y);
                    edge|=x==0||x==WIDTH-1||y==0||y==HEIGHT-1;
                    for(int n:new int[]{x>0?p-1:-1,x<WIDTH-1?p+1:-1,y>0?p-WIDTH:-1,y<HEIGHT-1?p+WIDTH:-1})
                        if(n>=0&&occupied[n]&&!seen[n]){seen[n]=true;stack[top++]=n;}
                }
                if(!edge){int span=Math.max(right-left+1,up-bottom+1)*16;widest=Math.max(widest,span);if(span>=320&&count>=100)++largeCounts[variant];}
            }
            System.out.printf(java.util.Locale.ROOT,"Mixed-size population large=%s: covered=%d, large connected clouds=%d, widest=%d blocks%n",variant==1,covered[variant],largeCounts[variant],widest);
            glDeleteProgram(program);
        }
        if(largeCounts[1]<=largeCounts[0]+5||covered[1]<covered[0]*1.15||covered[1]>covered[0]*3.5)
            throw new AssertionError("Large clouds do not add a bounded, visibly different size population");
        System.out.println("PASS mixed-size actual voxel field: five small/medium families plus distinct large connected clouds; wrap, bounds and coverage");
    }

    private static void checkClassicLook(Path resources,String vertex,String header) throws Exception {
        CloudConfig config=new CloudConfig();
        config.reimaginedClassicLook=true;config.reimaginedUpperLayer=false;
        String legacy=Files.readString(resources.resolve("../../../../../validation/fixtures/density-0.5.glsl").normalize())
            .replace("CLOUDSCAPE_DENSITY_INCLUDED","CLOUDSCAPE_05_REFERENCE_INCLUDED")
            .replaceAll("\\bCS(?!_)([A-Z][A-Za-z0-9_]*)\\b","CS05$1");
        String probe="""
            out vec4 result;
            void main(){
                vec3 cell=vec3(floor(gl_FragCoord.x)-320.0,mod(floor(gl_FragCoord.y),8.0),floor(gl_FragCoord.y/8.0)*9.0-200.0);
                cell.xz+=vec2(float(testScene-2)*4096.0);
                vec3 p=(cell+.5)*CS_VOXEL_SIZE+CSWind();
                float old=CS05VoxelWeight(cell,CS05MorphTime());
                float now=CSProceduralVoxelWeight(cell,CSMorphTime());
                float detailed=CSCloudDensity(p,0.0,true);
                float sky=CSCloudSkyAccess(p,0.0,now,now);
                float oldSky=CS05VoxelSkyAccess(cell,old,CS05MorphTime());
                result=vec4(abs(old-now),abs(old-detailed),abs(oldSky-sky),old);
            }
            """;
        int program=fieldProgram(vertex,"#version 330 core\n#define CLOUDS_REIMAGINED\n"+config.defines()+header
            +Files.readString(resources.resolve("density.glsl"))+legacy+probe);
        glUseProgram(program);glUniform1i(glGetUniformLocation(program,"noisetex"),0);
        double worst=0;int occupied=0;
        for(int phase=0;phase<4;++phase)for(int rain=0;rain<2;++rain){
            glUniform1i(glGetUniformLocation(program,"testScene"),phase);
            glUniform1f(glGetUniformLocation(program,"syncedTime"),phase*530.0f);
            glUniform1f(glGetUniformLocation(program,"rainFactor"),rain);
            glDrawArrays(GL_TRIANGLES,0,3);
            FloatBuffer values=BufferUtils.createFloatBuffer(WIDTH*HEIGHT*4);
            glReadPixels(0,0,WIDTH,HEIGHT,GL_RGBA,GL_FLOAT,values);
            for(int i=0;i<WIDTH*HEIGHT;++i){
                for(int c=0;c<3;++c){
                    float value=values.get(i*4+c);
                    if(!Float.isFinite(value))throw new AssertionError("Non-finite classic/reference comparison");
                    worst=Math.max(worst,value);
                }
                if(values.get(i*4+3)>.01)++occupied;
            }
        }
        if(worst>.0001||occupied<1000)throw new AssertionError("Classic look differs from archived 0.5 density/sky: "+worst+", occupied="+occupied);
        System.out.printf(java.util.Locale.ROOT,"PASS classic 0.5 archived density/sky parity: %d probes, max error %.8f%n",WIDTH*HEIGHT*8,worst);
        glDeleteProgram(program);
    }

    private static void checkVoxelField(Path resources,String vertex,String header,int originalColor,boolean classic,boolean varied) throws Exception {
        glActiveTexture(GL_TEXTURE0);
        int noiseTexture=glGetInteger(GL_TEXTURE_BINDING_2D);
        CloudConfig config=new CloudConfig();
        config.reimaginedClassicLook=classic;
        config.reimaginedVariedShapes=varied;
        config.reimaginedUpperLayer=false;config.reimaginedVoxelCache=true;
        config.lightSteps=2;config.maxDistance=1800;config.viewSteps=48;
        String common="#version 330 core\n#define CLOUDS_REIMAGINED\n"+config.defines()+header+"\n#define CLOUD_ALT1 320\nconst float timeAngle=0.25;\n#define GetSunVector() lightVec\n";
        String density=Files.readString(resources.resolve("density.glsl"));
        String pass=Files.readString(resources.resolve("voxel_field_pass.glsl")).replace("gl_FragData[0]","fieldResult");
        int field=glGenTextures(),light=glGenTextures();
        int[] textures={field,light};
        for(int texture:textures){
            glBindTexture(GL_TEXTURE_2D,texture);
            glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA16F,2048,256,0,GL_RGBA,GL_FLOAT,(ByteBuffer)null);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        }
        glBindTexture(GL_TEXTURE_2D,noiseTexture);
        glViewport(0,0,2048,256);
        for(int stage=0;stage<2;++stage){
            String define=stage==0?"CS_FIELD_DENSITY_PASS":"CS_FIELD_LIGHT_PASS";
            int program=fieldProgram(vertex,common+"\n#define CS_FIELD_BUILD 1\n#define "+define+" 1\n"+density+"\nout vec4 fieldResult;\n"+pass);
            glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,textures[stage],0);
            glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,field);
            glUseProgram(program);
            glUniform1i(glGetUniformLocation(program,"noisetex"),0);
            glUniform1i(glGetUniformLocation(program,"colortex20"),1);
            glUniform1f(glGetUniformLocation(program,"syncedTime"),70);
            glUniform1f(glGetUniformLocation(program,"frameTimeCounter"),70);
            glUniform1f(glGetUniformLocation(program,"rainFactor"),0);
            for(int i=0;i<4;++i)glDrawArrays(GL_TRIANGLES,0,3);
            int timer=glGenQueries();
            glBeginQuery(GL_TIME_ELAPSED,timer);for(int i=0;i<8;++i)glDrawArrays(GL_TRIANGLES,0,3);glEndQuery(GL_TIME_ELAPSED);
            System.out.printf(java.util.Locale.ROOT,"World atlas stage %d GPU %.3f ms%n",stage,glGetQueryObjectui64(timer,GL_QUERY_RESULT)/8e6);
            glDeleteQueries(timer);glDeleteProgram(program);
        }
        int probeTexture=glGenTextures();glBindTexture(GL_TEXTURE_2D,probeTexture);
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA32F,2048,256,0,GL_RGBA,GL_FLOAT,(ByteBuffer)null);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,probeTexture,0);
        String probe="""
            out vec4 fieldResult;
            void main(){
                ivec2 pixel=ivec2(gl_FragCoord.xy);
                vec3 cell=vec3(CSFieldOrigin().x+float(pixel.x%256),float(pixel.x/256),CSFieldOrigin().y+float(pixel.y));
                vec3 p=(cell+.5)*CS_VOXEL_SIZE+CSWind()+vec3(0,320,0);
                vec4 field=texelFetch(colortex20,pixel,0);
                float occupancy=CSProceduralVoxelWeight(cell,CSMorphTime());
                float mean=CSMeanCloudDensity(p,320.0);
                float reference=occupancy>0.0?CSVoxelLightDepth(p,lightVec,320.0):0.0;
                float cached=texelFetch(colortex21,pixel,0).r;
                fieldResult=vec4(abs(field.r-occupancy),abs(field.a-mean),abs(cached-reference)/(1.0+reference),occupancy);
            }
            """;
        // The reference deliberately disables atlas reads inside the procedural
        // oracle, including its per-cell noise cache, while comparing real data.
        int program=fieldProgram(vertex,common+"\n#define CS_FIELD_BUILD 1\n#define CS_FIELD_DENSITY_PASS 1\n"+density+probe);
        glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,field);
        glActiveTexture(GL_TEXTURE2);glBindTexture(GL_TEXTURE_2D,light);
        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program,"noisetex"),0);
        glUniform1i(glGetUniformLocation(program,"colortex20"),1);
        glUniform1i(glGetUniformLocation(program,"colortex21"),2);
        glUniform1f(glGetUniformLocation(program,"syncedTime"),70);
        glUniform1f(glGetUniformLocation(program,"frameTimeCounter"),70);
        glDrawArrays(GL_TRIANGLES,0,3);
        FloatBuffer values=BufferUtils.createFloatBuffer(2048*256*4);
        glReadPixels(0,0,2048,256,GL_RGBA,GL_FLOAT,values);
        double worstOccupancy=0,worstMean=0,worstLight=0;int occupied=0;
        for(int i=0;i<2048*256;++i){
            for(int c=0;c<4;++c)if(!Float.isFinite(values.get(i*4+c)))throw new AssertionError("Non-finite voxel atlas texel "+i);
            worstOccupancy=Math.max(worstOccupancy,values.get(i*4));
            worstMean=Math.max(worstMean,values.get(i*4+1));
            worstLight=Math.max(worstLight,values.get(i*4+2));
            if(values.get(i*4+3)>.001)++occupied;
        }
        if(occupied<1000||worstOccupancy>.001||worstMean>.001||worstLight>.025)
            throw new AssertionError("World atlas disagrees with procedural field: occupied="+occupied+" errors="+worstOccupancy+"/"+worstMean+"/"+worstLight);
        System.out.printf(java.util.Locale.ROOT,"PASS 524288 world-atlas voxels against procedural oracle (classic=%s,varied=%s): occupied=%d, occupancy/mean/light errors=%.6f/%.6f/%.6f%n",classic,varied,occupied,worstOccupancy,worstMean,worstLight);
        glDeleteProgram(program);
        String viewProbe="""
            out vec4 fieldResult;
            void main(){
                ivec2 pixel=ivec2(gl_FragCoord.xy);
                vec3 cell=vec3(CSFieldOrigin().x+float(pixel.x%256),float(pixel.x/256),CSFieldOrigin().y+float(pixel.y));
                float error=abs(CSVoxelWeight(cell,CSMorphTime())-texelFetch(colortex20,pixel,0).r);
                float angle=gl_FragCoord.x/2048.0*6.2831853;
                float radius=gl_FragCoord.y/256.0*CS_MAX_DISTANCE;
                vec3 endpoint=cameraPosition-CSWind()+vec3(cos(angle)*radius,0,sin(angle)*radius);
                vec3 endpointCell=floor(endpoint/CS_VOXEL_SIZE);endpointCell.y=float(pixel.x%8);
                ivec2 address;
                fieldResult=vec4(error,CSFieldAddress(endpointCell,address)?0.0:1.0,0,1);
            }
            """;
        program=fieldProgram(vertex,common+density+viewProbe);
        glUseProgram(program);glUniform1i(glGetUniformLocation(program,"noisetex"),0);glUniform1i(glGetUniformLocation(program,"colortex20"),1);
        glUniform1f(glGetUniformLocation(program,"syncedTime"),70);glUniform1f(glGetUniformLocation(program,"frameTimeCounter"),70);
        glDrawArrays(GL_TRIANGLES,0,3);glReadPixels(0,0,2048,256,GL_RGBA,GL_FLOAT,values);
        for(int i=0;i<2048*256;++i)if(values.get(i*4)>.000001||values.get(i*4+1)!=0)
            throw new AssertionError("Bounded view differs from atlas or leaves its horizontal support at "+i);
        System.out.printf("PASS 524288 bounded view lookups and ray endpoints (classic=%s,varied=%s)%n",classic,varied);
        glDeleteProgram(program);glDeleteTextures(field);glDeleteTextures(light);glDeleteTextures(probeTexture);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,originalColor,0);
        glViewport(0,0,WIDTH,HEIGHT);glActiveTexture(GL_TEXTURE0);
    }

    private static void benchmark(int program) {
        glUniform1i(glGetUniformLocation(program,"testScene"),5);
        for(boolean rainy:new boolean[]{false,true}) {
            glUniform1f(glGetUniformLocation(program,"rainFactor"),rainy?1:0);
            for(int frame=0;frame<8;++frame) glDrawArrays(GL_TRIANGLES,0,3);
            glFinish();
            double[] batches=new double[5];
            int query=glGenQueries();
            for(int batch=0;batch<batches.length;++batch) {
                glBeginQuery(GL_TIME_ELAPSED,query);
                for(int frame=0;frame<8;++frame) glDrawArrays(GL_TRIANGLES,0,3);
                glEndQuery(GL_TIME_ELAPSED);
                batches[batch]=glGetQueryObjectui64(query,GL_QUERY_RESULT)/8e6;
            }
            glDeleteQueries(query);
            java.util.Arrays.sort(batches);
            System.out.printf(java.util.Locale.ROOT,"CLOUD_GPU_MS %s median=%.3f min=%.3f max=%.3f at %dx%d (GPU timer, standalone cloud pass, not Minecraft FPS)%n",
                rainy?"rain":"clear",batches[2],batches[0],batches[4],WIDTH,HEIGHT);
        }
    }
    private static int shader(int kind, String text) {
        int shader = glCreateShader(kind);
        glShaderSource(shader, text);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) throw new AssertionError(glGetShaderInfoLog(shader));
        return shader;
    }

    private static boolean[] normalisedSilhouette(FloatBuffer pixels, int column, int row) {
        int tileWidth=WIDTH/6;
        int left=tileWidth, right=-1, bottom=120, top=-1;
        for(int y=0;y<120;++y)for(int x=0;x<tileWidth;++x){
            if(pixels.get(((row*120+y)*WIDTH+column*tileWidth+x)*4)>.5f){
                left=Math.min(left,x);right=Math.max(right,x);bottom=Math.min(bottom,y);top=Math.max(top,y);
            }
        }
        if(right<left)throw new AssertionError("Empty family silhouette "+column+" view "+row);
        boolean[] mask=new boolean[64*64];
        for(int y=0;y<64;++y)for(int x=0;x<64;++x){
            int sx=left+Math.min(right-left,(int)((x+.5)*(right-left+1)/64));
            int sy=bottom+Math.min(top-bottom,(int)((y+.5)*(top-bottom+1)/64));
            mask[y*64+x]=pixels.get(((row*120+sy)*WIDTH+column*tileWidth+sx)*4)>.5f;
        }
        return mask;
    }

    private static double silhouetteIoU(boolean[] a,boolean[] b){
        int intersection=0,union=0;
        for(int i=0;i<a.length;++i){if(a[i]&&b[i])++intersection;if(a[i]||b[i])++union;}
        return (double)intersection/Math.max(1,union);
    }

    public static void main(String[] args) throws Exception {
        if (!GLFW.glfwInit()) throw new AssertionError("GLFW failed");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(WIDTH, HEIGHT, "Cloudscape GLSL check", 0, 0);
        if (window == 0) throw new AssertionError("OpenGL context failed");
        try {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            System.out.println("GPU " + glGetString(GL_RENDERER));
            int noiseTexture = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, noiseTexture);
            try (ZipFile pack = new ZipFile(args[0])) {
                BufferedImage image = ImageIO.read(pack.getInputStream(pack.getEntry("shaders/lib/textures/noise.png")));
                ByteBuffer pixels = BufferUtils.createByteBuffer(image.getWidth() * image.getHeight() * 4);
                for (int y = 0; y < image.getHeight(); ++y) for (int x = 0; x < image.getWidth(); ++x) {
                    int rgba = image.getRGB(x, y);
                    pixels.put((byte)(rgba >> 16)).put((byte)(rgba >> 8)).put((byte)rgba).put((byte)255);
                }
                pixels.flip();
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, image.getWidth(), image.getHeight(), 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            }
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
            int fbo = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            int color = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, color);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, WIDTH, HEIGHT, 0, GL_RGBA, GL_FLOAT, (ByteBuffer)null);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) throw new AssertionError("FBO incomplete");
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, noiseTexture);
            glBindVertexArray(glGenVertexArrays());
            glViewport(0, 0, WIDTH, HEIGHT);
            Path resources = Path.of(args[1]);
            String density = Files.readString(resources.resolve("density.glsl"));
            String clouds = Files.readString(resources.resolve("clouds.glsl"));
            String shadows = Files.readString(resources.resolve("shadows.glsl"));
            String vertex = "#version 330 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2); gl_Position=vec4(p*2.0-1.0,0,1);}";
            String header = """
                #define OVERWORLD
                #define CLOUD_SPEED_MULT 100
                #define texture2DLod textureLod
                #define pow2(x) ((x)*(x))
                #ifndef CS_VOLUME_HEIGHT
                #define CS_VOLUME_HEIGHT CS_THICKNESS
                #endif
                uniform sampler2D noisetex;
                uniform int testScene;
                uniform float rainFactor;
                uniform float syncedTime, frameTimeCounter;
                const float renderDistance=384.0, maxBlindnessDarkness=0.0;
                const int isEyeInWater=0, cloudAlt1i=320;
                const mat4 gbufferModelViewInverse=mat4(1.0);
                const vec3 cameraPosition=vec3(0.0,145.0,0.0);
                vec3 lightVec=normalize(vec3(0.4,0.8,0.2));
                vec3 cloudAmbientColor=vec3(0.28,0.34,0.45), cloudLightColor=vec3(1.4,1.3,1.2);
                vec3 GetSky(float a,float b,float d,bool e,bool f){return mix(vec3(.65,.76,.9),vec3(.18,.4,.78),clamp(a,0.0,1.0));}
                """;
            if(BENCHMARK_ONLY && Boolean.getBoolean("cloudscape.lowSun")) header=header.replace("vec3(0.4,0.8,0.2)","vec3(0.9,0.08,0.2)");
            String footer = """
                out vec4 result;
                void main(){
                    vec2 uv=(gl_FragCoord.xy/vec2(640,360)*2.0-1.0);
                    vec3 ray=normalize(vec3(uv.x*1.4,uv.y*.8+.45,1));
                    vec3 origin=vec3(0,145,0);
                    if(testScene==1) origin.y=320.0+CS_THICKNESS*.25;
                    if(testScene==2){origin.y=320.0+CS_THICKNESS*.25;ray=normalize(vec3(uv.x,0,1));}
                    if(testScene==3){origin.y=320.0+CS_VOLUME_HEIGHT+64.0;ray.y=-abs(ray.y);}
                    float depth=1.0;
                    float distance=testScene==4?0.0:1000000000.0;
                    vec4 c=GetVolumetricClouds(320,6000,depth,1,1,origin,ray,distance,dot(ray,lightVec),ray.y,.5);
                    result=vec4(mix(GetSky(ray.y,0,0,true,false),c.rgb,c.a),c.a);
                    if(testScene==5) return;
                    float shadow=GetCloudShadow(vec3(0));
                    if(shadow<0.28||shadow>1.0) result.r=uintBitsToFloat(0x7fc00000u);
                    // The shader wraps camera XZ at 65,536; views and shadows must agree there.
                    vec3 probe=vec3(floor(gl_FragCoord.x)*3.0,336.0,floor(gl_FragCoord.y)*3.0);
                    float sample0=CSCloudDensity(probe,320.0,true);
                    float wrapX=CSCloudDensity(probe+vec3(65536,0,0),320.0,true);
                    float wrapZ=CSCloudDensity(probe+vec3(0,0,65536),320.0,true);
                    if(max(abs(sample0-wrapX),abs(sample0-wrapZ))>0.0001) result.r=uintBitsToFloat(0x7fc00000u);
                }
                """;
            if(Boolean.getBoolean("cloudscape.voxelFieldOnly")) {
                checkVoxelField(resources,vertex,header,color);
                return;
            }
            if(BENCHMARK_ONLY) footer=footer.replace("vec2(640,360)","vec2("+WIDTH+","+HEIGHT+")");
            Files.createDirectories(Path.of(args[2]));
            int variants = 0;
            for (boolean reimagined : new boolean[]{false,true}) for (boolean mainPass : new boolean[]{true,false}) {
                if(BENCHMARK_ONLY && (!reimagined || mainPass==Boolean.getBoolean("cloudscape.reflection"))) continue;
                String style = reimagined ? "reimagined" : "unbound";
                CloudConfig renderConfig=new CloudConfig();
                if(BENCHMARK_ONLY) for(String[] option:new String[][]{{"cloudscape.noSkipping","reimaginedEmptySpaceSkipping"},{"cloudscape.noLightCache","reimaginedInteriorLightCache"},{"cloudscape.noUpper","reimaginedUpperLayer"},{"cloudscape.noDistantLighting","reimaginedDistantLighting"}}) {
                    if(Boolean.getBoolean(option[0])) CloudConfig.class.getField(option[1]).setBoolean(renderConfig,false);
                }
                if(BENCHMARK_ONLY) for(String[] option:new String[][]{{"cloudscape.enableSkipping","reimaginedEmptySpaceSkipping"},{"cloudscape.enableLightCache","reimaginedInteriorLightCache"}}) {
                    if(Boolean.getBoolean(option[0])) CloudConfig.class.getField(option[1]).setBoolean(renderConfig,true);
                }
                String fragment = "#version 330 core\n" + (mainPass?"#define DEFERRED1\n":"") + (reimagined ? "#define CLOUDS_REIMAGINED\n" : "") + renderConfig.defines() + header + density + clouds + shadows + footer;
                int vs = shader(GL_VERTEX_SHADER, vertex), fs = shader(GL_FRAGMENT_SHADER, fragment);
                int program = glCreateProgram();
                glAttachShader(program, vs); glAttachShader(program, fs); glLinkProgram(program);
                if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) throw new AssertionError(glGetProgramInfoLog(program));
                glUseProgram(program);
                glUniform1i(glGetUniformLocation(program,"noisetex"),0);
                if(BENCHMARK_ONLY) {
                    benchmark(program);
                    glDeleteProgram(program); glDeleteShader(vs); glDeleteShader(fs);
                    continue;
                }
                for (boolean rainy : new boolean[]{false,true}) for (int scene = 0; scene < 5; ++scene) {
                    glUniform1f(glGetUniformLocation(program,"rainFactor"),rainy?1.0f:0.0f);
                    glUniform1i(glGetUniformLocation(program,"testScene"),scene);
                    glDrawArrays(GL_TRIANGLES,0,3);
                    FloatBuffer values=BufferUtils.createFloatBuffer(WIDTH*HEIGHT*4);
                    glReadPixels(0,0,WIDTH,HEIGHT,GL_RGBA,GL_FLOAT,values);
                    BufferedImage preview=new BufferedImage(WIDTH,HEIGHT,BufferedImage.TYPE_INT_RGB);
                    int visiblePixels=0;
                    for(int y=0;y<HEIGHT;++y) for(int x=0;x<WIDTH;++x) {
                        int index=(y*WIDTH+x)*4;
                        for(int k=0;k<4;++k) if(!Float.isFinite(values.get(index+k))) throw new AssertionError("Non-finite volume result");
                        float alpha=values.get(index+3);
                        if(alpha<0||alpha>1) throw new AssertionError("Opacity out of range");
                        if(alpha>0.05) ++visiblePixels;
                        if(scene==4 && alpha!=0) throw new AssertionError("Opaque foreground did not occlude clouds");
                        int pixel=0;
                        for(int k=0;k<3;++k) {
                            float v=Math.max(0,values.get(index+k));
                            int channel=(int)(255*Math.pow(v/(1+v),1/2.2));
                            pixel|=Math.clamp(channel,0,255)<<(16-8*k);
                        }
                        preview.setRGB(x,HEIGHT-1-y,pixel);
                    }
                    if(scene<4 && visiblePixels==0) throw new AssertionError("Cloud volume was not visible");
                    if(mainPass) ImageIO.write(preview,"PNG",Path.of(args[2],style+(rainy?"-rain":"")+"-case-"+scene+".png").toFile());
                    System.out.println("PASS "+style+" "+(mainPass?"main":"reflection")+" "+(rainy?"rain":"clear")+" scene="+scene+" visiblePixels="+visiblePixels);
                    ++variants;
                }
                if (reimagined && mainPass) for (boolean rainy : new boolean[]{false,true}) {
                    glUniform1f(glGetUniformLocation(program,"rainFactor"),rainy?1:0);
                    glUniform1i(glGetUniformLocation(program,"testScene"),5);
                    for(int warmup=0;warmup<4;++warmup)glDrawArrays(GL_TRIANGLES,0,3);
                    glFinish();
                    long start=System.nanoTime();
                    for(int frame=0;frame<16;++frame)glDrawArrays(GL_TRIANGLES,0,3);
                    glFinish();
                    double milliseconds=(System.nanoTime()-start)/16e6;
                    System.out.printf(java.util.Locale.ROOT,"CLOUD_RENDER_MS %s %.3f at %dx%d (standalone cloud pass, not Minecraft FPS)%n",rainy?"rain":"clear",milliseconds,WIDTH,HEIGHT);
                }
                glDeleteProgram(program); glDeleteShader(vs); glDeleteShader(fs);
            }
            if(BENCHMARK_ONLY) return;
            // Fixed world positions with wind disabled distinguish shape change
            // from translation. Compare exact light traversal to a separate fine
            // numerical integral of the shipped density function.
            String probeFooter = """
                out vec4 result;
                void main(){
                    vec2 column=floor(gl_FragCoord.xy)-vec2(320,180);
                    vec3 p=vec3((column.x+.5)*CS_VOXEL_SIZE,320.0005,(column.y+.5)*CS_VOXEL_SIZE);
                    float mean=0.0, continuity=0.0, wrapError=0.0;
                    int runs=0; bool wasSolid=false;
                    for(int y=0;y<int(ceil(CS_VOLUME_HEIGHT/CS_VOXEL_SIZE));++y){
                        vec3 cell=vec3(column.x,float(y),column.y);
                        float w=CSVoxelWeight(cell,CSMorphTime());
                        float wrapX=CSVoxelWeight(cell+vec3(65536.0/CS_VOXEL_SIZE,0,0),CSMorphTime());
                        float wrapZ=CSVoxelWeight(cell+vec3(0,0,65536.0/CS_VOXEL_SIZE),CSMorphTime());
                        wrapError=max(wrapError,max(abs(w-wrapX),abs(w-wrapZ)));
                        // A sunlight query may replace the fragment's group
                        // cache before the view ray returns to its own region.
                        float distant=CSVoxelWeight(cell+vec3(512,0,-511),CSMorphTime());
                        float repeated=CSVoxelWeight(cell,CSMorphTime());
                        wrapError=max(wrapError,abs(w-repeated));
                        if(distant<0.0||distant>1.0)wrapError=100.0;
                        mean+=w*CS_VOXEL_SIZE/CS_VOLUME_HEIGHT;
                        bool solid=w>.5;
                        if(solid&&!wasSolid) ++runs;
                        wasSolid=solid;
                        continuity=max(continuity,abs(w-CSVoxelWeight(cell,CSMorphTime()+.0001)));
                    }
                    float reference=0.0;
                    for(int j=0;j<256;++j) {
                        vec3 q=vec3(p.x,320.0+CS_VOLUME_HEIGHT*(float(j)+.5)/256.0,p.z);
                        reference+=CSCloudDensity(q,320.0,true)*CS_VOLUME_HEIGHT/256.0;
                    }
                    float exact=CSVoxelLightDepth(p,vec3(0,1,0),320.0);
                    // The light traversal stops once optical depth exceeds 8.
                    // Compare transmission, the observable result, allowing at
                    // most exp(-8) error from that deliberate early exit.
                    float error=abs(exp(-exact*CS_EXTINCTION*CS_DENSITY)-exp(-reference*CS_EXTINCTION*CS_DENSITY));
                    // Moving upwards removes absorbing material. It must never
                    // reduce transmission towards the same overhead light.
                    float full=CSLightTransmittance(p,vec3(0,1,0),320.0);
                    float half=CSLightTransmittance(p+vec3(0,CS_VOLUME_HEIGHT*.5,0),vec3(0,1,0),320.0);
                    if(full>half+.0001 || full<0.0 || half>1.0 || wrapError>.0001) error=100.0;
                    result=vec4(mean,runs>1?1:0,error,continuity);
                }
                """;
            for (boolean frozen : new boolean[]{false,true}) {
                CloudConfig settings=new CloudConfig();
                settings.windSpeed=0;
                settings.reimaginedMorphSpeed=frozen?0:1;
                String fragment="#version 330 core\n#define CLOUDS_REIMAGINED\n"+settings.defines()+header+density+probeFooter;
                int vs=shader(GL_VERTEX_SHADER,vertex), fs=shader(GL_FRAGMENT_SHADER,fragment);
                int program=glCreateProgram();
                glAttachShader(program,vs);glAttachShader(program,fs);glLinkProgram(program);
                if(glGetProgrami(program,GL_LINK_STATUS)==GL_FALSE)throw new AssertionError(glGetProgramInfoLog(program));
                glUseProgram(program);
                glUniform1i(glGetUniformLocation(program,"noisetex"),0);
                float[] initial=new float[WIDTH*HEIGHT];
                int changed=0,split=0,occupied=0,empty=0;
                double maxIntegralError=0;
                for(int frame=0;frame<2;++frame){
                    glUniform1f(glGetUniformLocation(program,"syncedTime"),frame*120.0f);
                    glUniform1f(glGetUniformLocation(program,"frameTimeCounter"),frame*120.0f);
                    glDrawArrays(GL_TRIANGLES,0,3);
                    FloatBuffer values=BufferUtils.createFloatBuffer(WIDTH*HEIGHT*4);
                    glReadPixels(0,0,WIDTH,HEIGHT,GL_RGBA,GL_FLOAT,values);
                    for(int i=0;i<WIDTH*HEIGHT;++i){
                        float mean=values.get(i*4),error=values.get(i*4+2),continuity=values.get(i*4+3);
                        if(!Float.isFinite(mean)||!Float.isFinite(error)||error>.01)throw new AssertionError("Voxel light traversal disagrees with fine detailed-density integral: "+error);
                        maxIntegralError=Math.max(maxIntegralError,error);
                        if(!Float.isFinite(continuity)||continuity>.01)throw new AssertionError("Discontinuous shape evolution");
                        if(frame==0){initial[i]=mean;if(mean>.05)++occupied;else ++empty;if(values.get(i*4+1)>.5)++split;}
                        else if(Math.abs(mean-initial[i])>.01)++changed;
                    }
                }
                if(occupied==0||empty==0||split==0)throw new AssertionError("Expected cloud gaps and separated occupied layers: "+occupied+"/"+empty+"/"+split);
                if(frozen?changed!=0:changed<WIDTH*HEIGHT/100)throw new AssertionError("Wind-independent morph/freeze failed: "+changed);
                System.out.println("PASS voxel topology, continuous "+(frozen?"frozen":"evolving")+" shape with wind=0, fine integral and monotonic transmission; changedColumns="+changed+", splitColumns="+split+", maxTransmissionError="+maxIntegralError);
                glDeleteProgram(program);glDeleteShader(vs);glDeleteShader(fs);
            }
            String featureFooter="""
                out vec4 result;
                void main(){
                    vec2 column=floor(gl_FragCoord.xy)-vec2(320,180);
                    float yi=mod(floor(gl_FragCoord.x/40.0),ceil(CS_VOLUME_HEIGHT/CS_VOXEL_SIZE));
                    vec3 corner=vec3(column.x,yi,column.y)*CS_VOXEL_SIZE+vec3(0,320,0);
                    vec3 a=corner+vec3(.211,.337,.419)*CS_VOXEL_SIZE;
                    vec3 b=corner+vec3(.789,.663,.581)*CS_VOXEL_SIZE;
                    float coarse=CSCloudDensity(a,320.0,false);
                    float da=CSCloudDensity(a,320.0,true), db=CSCloudDensity(b,320.0,true);
                    float bad=0.0;
                    if(da<0.0||da>coarse+.00001||db<0.0||db>coarse+.00001)bad=1.0;
                    float wrapX=CSCloudDensity(a+vec3(65536,0,0),320.0,true);
                    float wrapZ=CSCloudDensity(a+vec3(0,0,65536),320.0,true);
                    if(max(abs(da-wrapX),abs(da-wrapZ))>.001)bad=2.0;
                    float upper=0.0;
                    for(int j=0;j<4;++j)upper=max(upper,CSCloudDensity(vec3(a.x,320.0+CS_UPPER_BASE+CS_UPPER_THICKNESS*(float(j)+.5)/4.0,a.z),320.0,true));
                    float gap=CSCloudDensity(vec3(a.x,320.0+CS_THICKNESS+CS_LAYER_GAP*.5,a.z),320.0,true);
                    if(gap!=0.0)bad=3.0;
                    vec3 upperBoundary=vec3(a.x,320.0+CS_UPPER_BASE,a.z);
                    if(CSCloudDensity(upperBoundary,320.0,false)>0.0 &&
                        CSFirstPossibleCloudDistance(upperBoundary,vec3(1,0,0),320.0,0.0,600.0)>0.0001)bad=6.0;
                    vec3 origin=vec3(a.x,320.0+yi*CS_VOXEL_SIZE+1.0,a.z);
                    vec3 ray=normalize(vec3(.71,mod(column.y,3.0)-1.0,.39));
                    float possible=CSFirstPossibleCloudDistance(origin,ray,320.0,0.0,600.0);
                    if(possible<0.0||possible>600.0||isnan(possible)||isinf(possible))bad=4.0;
                    for(int j=0;j<32;++j){
                        float t=possible*(float(j)+.5)/32.0;
                        if(t<possible-.002&&CSCloudDensity(origin+ray*t,320.0,false)>.00001)bad=5.0;
                    }
                    result=vec4(abs(da-db),upper,bad,possible);
                }
                """;
            CloudConfig features=new CloudConfig(); features.windSpeed=0;features.reimaginedEmptySpaceSkipping=true;
            String featureFragment="#version 330 core\n#define CLOUDS_REIMAGINED\n"+features.defines()+header+density+featureFooter;
            int featureVs=shader(GL_VERTEX_SHADER,vertex),featureFs=shader(GL_FRAGMENT_SHADER,featureFragment),featureProgram=glCreateProgram();
            glAttachShader(featureProgram,featureVs);glAttachShader(featureProgram,featureFs);glLinkProgram(featureProgram);
            if(glGetProgrami(featureProgram,GL_LINK_STATUS)==GL_FALSE)throw new AssertionError(glGetProgramInfoLog(featureProgram));
            glUseProgram(featureProgram);glUniform1i(glGetUniformLocation(featureProgram,"noisetex"),0);
            glUniform1f(glGetUniformLocation(featureProgram,"rainFactor"),1.0f);
            glDrawArrays(GL_TRIANGLES,0,3);
            FloatBuffer featurePixels=BufferUtils.createFloatBuffer(WIDTH*HEIGHT*4);
            glReadPixels(0,0,WIDTH,HEIGHT,GL_RGBA,GL_FLOAT,featurePixels);
            int internalVariation=0,upperColumns=0,skippedRays=0;
            for(int i=0;i<WIDTH*HEIGHT;++i){
                for(int c=0;c<4;++c)if(!Float.isFinite(featurePixels.get(i*4+c)))throw new AssertionError("Non-finite density feature probe");
                if(featurePixels.get(i*4+2)!=0)throw new AssertionError("Density/skip invariant failed, code="+featurePixels.get(i*4+2)+" pixel="+i);
                if(featurePixels.get(i*4)>.0001)++internalVariation;
                if(featurePixels.get(i*4+1)>.001)++upperColumns;
                if(featurePixels.get(i*4+3)>8)++skippedRays;
            }
            if(internalVariation<100||upperColumns<100||skippedRays<100)throw new AssertionError("Missing subvoxel detail, upper layer or meaningful spatial skips");
            System.out.println("PASS internal detail/support/wrap, upper layer/gap and conservative empty-space probes: varied="+internalVariation+", upper="+upperColumns+", skipped="+skippedRays);
            glDeleteProgram(featureProgram);glDeleteShader(featureVs);glDeleteShader(featureFs);

            String lodFooter="""
                out vec4 result;
                void main(){
                    vec3 p=vec3((gl_FragCoord.x-16.0)*43.0,328.0+mod(gl_FragCoord.y,16.0)*16.0,(gl_FragCoord.y-16.0)*47.0);
                    vec3 sun=normalize(vec3(.4,.8,.2));
                    float near=CSViewLightTransmittance(p,sun,320.0,CS_FULL_LIGHT_DISTANCE);
                    float exact=CSLightTransmittance(p,sun,320.0);
                    float error=abs(near-exact);
                    float farT=CSViewLightTransmittance(p,sun,320.0,CS_FULL_LIGHT_DISTANCE*2.0);
                    float horizon=CSViewLightTransmittance(p,normalize(vec3(1,.000001,.3)),320.0,CS_FULL_LIGHT_DISTANCE*2.0);
                    float above=CSViewLightTransmittance(vec3(p.x,321.0+CS_VOLUME_HEIGHT,p.z),sun,320.0,CS_FULL_LIGHT_DISTANCE*2.0);
                    float left=CSViewLightTransmittance(p,sun,320.0,CS_FULL_LIGHT_DISTANCE-.01);
                    float right=CSViewLightTransmittance(p,sun,320.0,CS_FULL_LIGHT_DISTANCE+.01);
                    if(abs(left-right)>.0001)error=2.0;
                    if(farT<0.0||farT>1.0||horizon<0.0||horizon>1.0||abs(above-1.0)>.00001)error=3.0;
                    result=vec4(error,farT,horizon,above);
                }
                """;
            String lodFragment="#version 330 core\n#define CLOUDS_REIMAGINED\n"+new CloudConfig().defines()+header+density+lodFooter;
            int lodVs=shader(GL_VERTEX_SHADER,vertex),lodFs=shader(GL_FRAGMENT_SHADER,lodFragment),lodProgram=glCreateProgram();
            glAttachShader(lodProgram,lodVs);glAttachShader(lodProgram,lodFs);glLinkProgram(lodProgram);
            if(glGetProgrami(lodProgram,GL_LINK_STATUS)==GL_FALSE)throw new AssertionError(glGetProgramInfoLog(lodProgram));
            glUseProgram(lodProgram);glUniform1i(glGetUniformLocation(lodProgram,"noisetex"),0);
            glViewport(0,0,32,32);glDrawArrays(GL_TRIANGLES,0,3);
            FloatBuffer lodPixels=BufferUtils.createFloatBuffer(32*32*4);glReadPixels(0,0,32,32,GL_RGBA,GL_FLOAT,lodPixels);
            for(int i=0;i<32*32;++i){
                for(int c=0;c<4;++c)if(!Float.isFinite(lodPixels.get(i*4+c)))throw new AssertionError("Non-finite distant lighting");
                if(lodPixels.get(i*4)>.00001)throw new AssertionError("Distant lighting near-parity/bounds/continuity failed: "+lodPixels.get(i*4));
            }
            System.out.println("PASS distant lighting: exact near range, continuous transition, finite horizontal rays and empty sky");
            glDeleteProgram(lodProgram);glDeleteShader(lodVs);glDeleteShader(lodFs);glViewport(0,0,WIDTH,HEIGHT);

            String cacheHelper=Files.readString(resources.resolve("halfres.glsl"));
            String cacheFragment="""
                #version 330 core
                #define VL_CLOUDS_ACTIVE
                #define CS_STYLE_REIMAGINED
                #define CS_HALF_RESOLUTION 1
                #define CS_CLOUD_CACHE_TEST
                """+cacheHelper+"""
                uniform vec2 queryUV;
                uniform float queryDistance;
                uniform int querySky;
                uniform vec4 expectedCloud;
                out vec4 result;
                void main(){
                    float depth=99.0;vec4 cloud;
                    bool valid=CSReadCloudCache(queryUV,queryDistance,querySky==1,cloud,depth);
                    float error=0.0;
                    if(valid){vec4 e=abs(cloud-expectedCloud);error=max(max(e.x,e.y),max(e.z,e.w));}
                    result=vec4(valid?1.0:0.0,error,depth,1.0);
                }
                """;
            for(boolean fastCache:new boolean[]{false,true}) {
            String cacheSource=fastCache?cacheFragment.replace("#define CS_CLOUD_CACHE_TEST", "#define CS_CLOUD_CACHE_TEST\n#define CS_VOXEL_CACHE 1\n#define texture2DLod textureLod"):cacheFragment;
            int cacheVs=shader(GL_VERTEX_SHADER,vertex),cacheFs=shader(GL_FRAGMENT_SHADER,cacheSource),cacheProgram=glCreateProgram();
            glAttachShader(cacheProgram,cacheVs);glAttachShader(cacheProgram,cacheFs);glLinkProgram(cacheProgram);
            if(glGetProgrami(cacheProgram,GL_LINK_STATUS)==GL_FALSE)throw new AssertionError(glGetProgramInfoLog(cacheProgram));
            int cacheColor=glGenTextures(),cacheDepth=glGenTextures();
            glUseProgram(cacheProgram);
            glUniform1i(glGetUniformLocation(cacheProgram,"colortex14"),1);glUniform1i(glGetUniformLocation(cacheProgram,"colortex15"),2);
            for(int scenario=0;scenario<9;++scenario) {
                FloatBuffer colors=BufferUtils.createFloatBuffer(16*4),metadata=BufferUtils.createFloatBuffer(16*4);
                for(int y=0;y<4;++y)for(int x=0;x<4;++x) {
                    float alpha=scenario==1?.5f:scenario==6?0:.75f;
                    boolean transparent=scenario==1&&x==1&&y==1;
                    float multiplier=fastCache?alpha:1;
                    float transparentColor=fastCache?0:100;
                    colors.put(transparent?transparentColor:.25f*multiplier).put(transparent?transparentColor:.5f*multiplier).put(transparent?transparentColor:.75f*multiplier).put(transparent?0:alpha);
                    float cloudDepth=scenario==3||scenario==4?.25f:scenario==7?0:scenario==5?(x==1&&y==1?.5f:4):2;
                    float scene=scenario==3||scenario==4?100:-1;
                    if((scenario==2||scenario==4)&&x==1&&y==1)scene=scenario==2?100:400;
                    metadata.put(cloudDepth).put(scene).put(1).put(1);
                }
                colors.flip();metadata.flip();
                glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,cacheColor);
                glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA16F,4,4,0,GL_RGBA,GL_FLOAT,colors);
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,fastCache?GL_LINEAR:GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,fastCache?GL_LINEAR:GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
                glActiveTexture(GL_TEXTURE2);glBindTexture(GL_TEXTURE_2D,cacheDepth);
                glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA32F,4,4,0,GL_RGBA,GL_FLOAT,metadata);
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,fastCache?GL_LINEAR:GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,fastCache?GL_LINEAR:GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
                float uv=scenario==5?.4f:scenario==8?0:.5f;
                glUniform2f(glGetUniformLocation(cacheProgram,"queryUV"),uv,uv);
                glUniform1f(glGetUniformLocation(cacheProgram,"queryDistance"),scenario==3||scenario==4?100:0);
                glUniform1i(glGetUniformLocation(cacheProgram,"querySky"),scenario==3||scenario==4?0:1);
                float expectedAlpha=scenario==1?.375f:scenario==6?0:.75f;
                glUniform4f(glGetUniformLocation(cacheProgram,"expectedCloud"),scenario==6?0:.25f,scenario==6?0:.5f,scenario==6?0:.75f,expectedAlpha);
                glViewport(0,0,1,1);glDrawArrays(GL_TRIANGLES,0,3);
                FloatBuffer pixel=BufferUtils.createFloatBuffer(4);glReadPixels(0,0,1,1,GL_RGBA,GL_FLOAT,pixel);
                boolean expectedValid=scenario!=2&&scenario!=4;
                float expectedDepth=scenario==1||scenario==2||scenario==4||scenario==6?99:scenario==3?.25f:scenario==5?.5f:scenario==7?0:2;
                for(int c=0;c<4;++c)if(!Float.isFinite(pixel.get(c)))throw new AssertionError("Non-finite cloud cache case "+scenario);
                if(pixel.get(0)!=(expectedValid?1:0)||pixel.get(1)>.00001||Math.abs(pixel.get(2)-expectedDepth)>.00001)
                    throw new AssertionError("Cloud cache interpolation/geometry/depth failed case="+scenario+" valid="+pixel.get(0)+" colorError="+pixel.get(1)+" depth="+pixel.get(2));
            }
            System.out.println("PASS 9 cloud-cache GPU cases, fast="+fastCache+": premultiplied radiance, geometry/sky fallback, depth, transparent sky, inside volume and screen border");
            glDeleteTextures(cacheColor);glDeleteTextures(cacheDepth);glDeleteProgram(cacheProgram);glDeleteShader(cacheVs);glDeleteShader(cacheFs);
            }
            glActiveTexture(GL_TEXTURE0);glViewport(0,0,WIDTH,HEIGHT);

            String temporalMath=Files.readString(resources.resolve("temporal_math.glsl"));
            String temporalFooter="""
                out vec4 result;
                void main(){
                    float phase=mod(floor(gl_FragCoord.x),512.0);
                    float halfCode=unpackHalf2x16(packHalf2x16(vec2(CSClockEncode(phase),0.0))).x;
                    float error=abs(CSClockDecode(halfCode)-phase);
                    if(!CSClockValid(halfCode)||CSClockValid(0.0)||CSClockValid(-1.0))error=100.0;
                    if(CSWrappedClockDelta(0.0,511.0,512.0)!=1.0||CSWrappedClockDelta(5.0,5.0,512.0)!=0.0)error=101.0;
                    float tick=floor(gl_FragCoord.y)*14000.0+phase;
                    vec3 codes=vec3(CSClockEncode(tick),CSClockEpochLow(tick),CSClockEpochHigh(tick));
                    codes.xy=unpackHalf2x16(packHalf2x16(codes.xy));
                    codes.z=unpackHalf2x16(packHalf2x16(vec2(codes.z,0.0))).x;
                    if(!CSClockComponentsValid(codes.x,codes.y,codes.z))error=102.0;
                    error=max(error,abs(CSClockReconstruct(codes.x,codes.y,codes.z)-tick));
                    float distance=10.0+phase*10.0, range=384.0;
                    float hd=unpackHalf2x16(packHalf2x16(vec2(sqrt(distance/range),0.0))).x;
                    if(!CSCloudHistoryDepthAccept(hd,distance,range,16.0))error=103.0;
                    if(CSCloudHistoryDepthAccept(hd,distance+500.0,range,16.0)||CSCloudHistoryDepthAccept(0.0,distance,range,16.0)||CSCloudHistoryDepthAccept(-1.0,distance,range,16.0))error=104.0;
                    result=vec4(error,0,0,1);
                }
                """;
            String temporalFragment="#version 330 core\n#extension GL_ARB_shading_language_packing : require\n"+temporalMath+temporalFooter;
            int temporalVs=shader(GL_VERTEX_SHADER,vertex),temporalFs=shader(GL_FRAGMENT_SHADER,temporalFragment),temporalProgram=glCreateProgram();
            glAttachShader(temporalProgram,temporalVs);glAttachShader(temporalProgram,temporalFs);glLinkProgram(temporalProgram);
            if(glGetProgrami(temporalProgram,GL_LINK_STATUS)==GL_FALSE)throw new AssertionError(glGetProgramInfoLog(temporalProgram));
            glUseProgram(temporalProgram);glDrawArrays(GL_TRIANGLES,0,3);
            FloatBuffer temporalPixels=BufferUtils.createFloatBuffer(WIDTH*HEIGHT*4);
            glReadPixels(0,0,WIDTH,HEIGHT,GL_RGBA,GL_FLOAT,temporalPixels);
            for(int i=0;i<WIDTH*HEIGHT;++i)if(temporalPixels.get(i*4)!=0)throw new AssertionError("FP16 temporal clock/depth probe failed code="+temporalPixels.get(i*4));
            System.out.println("PASS FP16 temporal clock roundtrip, wrap/freeze and cloud history depth rejection");
            glDeleteProgram(temporalProgram);glDeleteShader(temporalVs);glDeleteShader(temporalFs);
            // Compare scale-normalised projections: merely resizing the old
            // two-lobe cloud would still produce IoU=1 and fail this regression.
            String shapeFooter="""
                uniform float shapeVariation;
                uniform int shapeMode;
                out vec4 result;
                void main(){
                    if(shapeMode==1){
                        CSPrepareGroups(floor(gl_FragCoord.xy),CS_COVERAGE);
                        result=vec4(CSCachedForm[0].z,CSCachedForm[0].xy,CSCachedBias[0]<1.0?1.0:0.0);
                        return;
                    }
                    float family=floor(gl_FragCoord.x/106.0);
                    float yaw=floor(gl_FragCoord.y/120.0)*0.78539816;
                    float x=(mod(gl_FragCoord.x,106.0)/106.0*2.0-1.0)*1.35;
                    float y=mod(gl_FragCoord.y,120.0)/120.0*1.4-.2;
                    float occupied=0.0;
                    for(int j=0;j<64;++j){
                        float z=(float(j)+.5)/64.0*2.8-1.4;
                        vec3 p=vec3(cos(yaw)*x+sin(yaw)*z,y,-sin(yaw)*x+cos(yaw)*z);
                        vec3 scale=vec3(96,128,96);
                        p=(floor(p*scale/CS_VOXEL_SIZE)+.5)*CS_VOXEL_SIZE/scale;
                        float shape=CSCloudShape(p,vec2(.2,-.17),family,shapeVariation);
                        occupied=max(occupied,step(.12,shape));
                    }
                    result=vec4(vec3(occupied),1);
                }
                """;
            CloudConfig shapeConfig=new CloudConfig();shapeConfig.reimaginedClassicLook=true;shapeConfig.reimaginedVariedShapes=true;
            String shapeFragment="#version 330 core\n#define CLOUDS_REIMAGINED\n"+shapeConfig.defines()+header+density+shapeFooter;
            int shapeVs=shader(GL_VERTEX_SHADER,vertex),shapeFs=shader(GL_FRAGMENT_SHADER,shapeFragment);
            int shapeProgram=glCreateProgram();
            glAttachShader(shapeProgram,shapeVs);glAttachShader(shapeProgram,shapeFs);glLinkProgram(shapeProgram);
            if(glGetProgrami(shapeProgram,GL_LINK_STATUS)==GL_FALSE)throw new AssertionError(glGetProgramInfoLog(shapeProgram));
            glUseProgram(shapeProgram);glUniform1i(glGetUniformLocation(shapeProgram,"noisetex"),0);
            boolean[][][][] masks=new boolean[2][6][3][];
            for(int variant=0;variant<2;++variant){
                glUniform1f(glGetUniformLocation(shapeProgram,"shapeVariation"),variant==0?.15f:.85f);
                glUniform1i(glGetUniformLocation(shapeProgram,"shapeMode"),0);
                glDrawArrays(GL_TRIANGLES,0,3);
                FloatBuffer pixels=BufferUtils.createFloatBuffer(WIDTH*HEIGHT*4);
                glReadPixels(0,0,WIDTH,HEIGHT,GL_RGBA,GL_FLOAT,pixels);
                BufferedImage gallery=new BufferedImage(WIDTH,HEIGHT,BufferedImage.TYPE_INT_RGB);
                for(int y=0;y<HEIGHT;++y)for(int x=0;x<WIDTH;++x)
                    gallery.setRGB(x,HEIGHT-1-y,pixels.get((y*WIDTH+x)*4)>.5f?0xffffff:0x172131);
                ImageIO.write(gallery,"PNG",Path.of(args[2],"shape-silhouettes-"+variant+".png").toFile());
                for(int family=0;family<6;++family)for(int view=0;view<3;++view)
                    masks[variant][family][view]=normalisedSilhouette(pixels,family,view);
            }
            double worstPair=0;
            for(int a=0;a<6;++a)for(int b=a+1;b<6;++b){
                double similarity=0;
                for(int view=0;view<3;++view)similarity+=silhouetteIoU(masks[0][a][view],masks[0][b][view])/3;
                worstPair=Math.max(worstPair,similarity);
                if(similarity>.94)throw new AssertionError("Cloud families are almost identical after scale normalisation: "+a+"/"+b+" IoU="+similarity);
            }
            int changingFamilies=0;
            for(int family=0;family<6;++family){
                double similarity=0;
                for(int view=0;view<3;++view)similarity+=silhouetteIoU(masks[0][family][view],masks[1][family][view])/3;
                if(similarity<.96)++changingFamilies;
            }
            if(changingFamilies<3)throw new AssertionError("Insufficient internal shape variation: "+changingFamilies);
            glUniform1i(glGetUniformLocation(shapeProgram,"shapeMode"),1);
            glDrawArrays(GL_TRIANGLES,0,3);
            FloatBuffer distribution=BufferUtils.createFloatBuffer(WIDTH*HEIGHT*4);
            glReadPixels(0,0,WIDTH,HEIGHT,GL_RGBA,GL_FLOAT,distribution);
            int[] familyCounts=new int[5],octants=new int[8];
            for(int i=0;i<WIDTH*HEIGHT;++i)if(distribution.get(i*4+3)>.5){
                int family=Math.round(distribution.get(i*4));
                if(family<0||family>4)throw new AssertionError("Invalid cloud family");
                ++familyCounts[family];
                double angle=Math.atan2(distribution.get(i*4+2),distribution.get(i*4+1));
                int octant=Math.floorMod((int)Math.floor(angle/(Math.PI/4)),8);++octants[octant];
            }
            for(int count:familyCounts)if(count<100)throw new AssertionError("A cloud family is absent from the procedural sky");
            for(int count:octants)if(count<100)throw new AssertionError("Cloud rotations do not cover the full circle");
            System.out.printf(java.util.Locale.ROOT,"PASS silhouette diversity: 15 scale-normalised family pairs over 3 views, max mean IoU=%.3f, internally varied families=%d%n",worstPair,changingFamilies);
            System.out.println("PASS procedural family and rotation distribution: "+java.util.Arrays.toString(familyCounts));
            glDeleteProgram(shapeProgram);glDeleteShader(shapeVs);glDeleteShader(shapeFs);
            checkVoxelField(resources,vertex,header,color);
            if(glGetError()!=GL_NO_ERROR) throw new AssertionError("OpenGL error");
            System.out.println("PASS "+variants+" rendered GPU cases; finite radiance, bounded opacity and opaque-depth occlusion");
        } finally { GLFW.glfwDestroyWindow(window); GLFW.glfwTerminate(); }
    }
}
