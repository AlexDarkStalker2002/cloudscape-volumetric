package dev.cloudscape.volumetric;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL33C.*;

/** Executes both real adapted GetClouds wrappers; replaces only the volume with an endpoint observer. */
public final class SkyDepthGpuCheck {
    private static int shader(int kind,String source) {
        int id=glCreateShader(kind);glShaderSource(id,source);glCompileShader(id);
        if(glGetShaderi(id,GL_COMPILE_STATUS)==GL_FALSE)throw new AssertionError(glGetShaderInfoLog(id));
        return id;
    }
    private static int texture() {
        int id=glGenTextures();glBindTexture(GL_TEXTURE_2D,id);
        glTexImage2D(GL_TEXTURE_2D,0,GL_R32F,1,1,0,GL_RED,GL_FLOAT,(ByteBuffer)null);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        return id;
    }
    private static void depth(int unit,int texture,float value) {
        glActiveTexture(GL_TEXTURE0+unit);glBindTexture(GL_TEXTURE_2D,texture);
        FloatBuffer data=BufferUtils.createFloatBuffer(1);data.put(value).flip();
        glTexSubImage2D(GL_TEXTURE_2D,0,0,0,1,1,GL_RED,GL_FLOAT,data);
    }
    public static void main(String[] args) throws Exception {
        if(!GLFW.glfwInit())throw new AssertionError("GLFW initialization failed");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE,GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window=GLFW.glfwCreateWindow(1,1,"Sky depth regression",0,0);
        if(window==0)throw new AssertionError("GPU context failed");
        try {
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
            int fbo=glGenFramebuffers();glBindFramebuffer(GL_FRAMEBUFFER,fbo);
            int color=glGenTextures();glBindTexture(GL_TEXTURE_2D,color);
            glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA32F,1,1,0,GL_RGBA,GL_FLOAT,(ByteBuffer)null);
            glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,color,0);
            if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new AssertionError("FBO incomplete");
            int nativeTexture=texture(),dhTexture=texture();
            glBindVertexArray(glGenVertexArrays());glViewport(0,0,1,1);
            String vertex="#version 330 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2-1,0,1);}";
            int checks=0,oldSkyFailures=0;
            for(int version=0;version<2;version++) {
                String wrapper=Files.readString(Path.of(args[version]));
                wrapper=wrapper.substring(wrapper.indexOf("vec4 GetClouds("));
                for(boolean dh:new boolean[]{false,true})for(boolean main:new boolean[]{false,true}) {
                    String header="""
                        #version 330 core
                        #define CLOUDS_REIMAGINED
                        #define IRIS_VERSION 0
                        #define CLOUD_R 100
                        #define CLOUD_G 100
                        #define CLOUD_B 100
                        #define pow2(x) ((x)*(x))
                        uniform sampler2D depthtex0,dhDepthTex;
                        uniform float renderDistance,testDistance;
                        ivec2 texelCoord=ivec2(0);
                        const vec3 cameraPosition=vec3(0,117,0);
                        const int cloudAlt1i=256;
                        const float rainFactor=0;
                        vec3 cloudAmbientColor=vec3(1),cloudLightColor=vec3(1);
                        vec4 GetVolumetricClouds(int a,float b,inout float c,float d,float e,vec3 f,vec3 g,float endpoint,float h,float i,float j){
                            return vec4(min(endpoint,6000.0),0,0,1);
                        }
                        """;
                    header+=(dh?"#define DISTANT_HORIZONS\n":"")+(main?"#define DEFERRED1\n":"");
                    String fragment=header+wrapper+"\nout vec4 result;void main(){float z=1;result=GetClouds(z,1,vec3(0),vec3(0,.3,1),testDistance,0,.3,.5,vec3(0),vec3(0));}";
                    int vs=shader(GL_VERTEX_SHADER,vertex),fs=shader(GL_FRAGMENT_SHADER,fragment),program=glCreateProgram();
                    glAttachShader(program,vs);glAttachShader(program,fs);glLinkProgram(program);
                    if(glGetProgrami(program,GL_LINK_STATUS)==GL_FALSE)throw new AssertionError(glGetProgramInfoLog(program));
                    glUseProgram(program);glUniform1i(glGetUniformLocation(program,"depthtex0"),0);glUniform1i(glGetUniformLocation(program,"dhDepthTex"),1);
                    for(float radius:new float[]{256,1024,2048})for(float distance:new float[]{64,389,1024,1400,1600,2400})for(int surface=0;surface<3;surface++) {
                        depth(0,nativeTexture,surface==1?.5f:1);depth(1,dhTexture,surface==2?.8f:1);
                        glUniform1f(glGetUniformLocation(program,"renderDistance"),radius);glUniform1f(glGetUniformLocation(program,"testDistance"),distance);
                        glDrawArrays(GL_TRIANGLES,0,3);
                        FloatBuffer pixel=BufferUtils.createFloatBuffer(4);glReadPixels(0,0,1,1,GL_RGBA,GL_FLOAT,pixel);
                        float actual=pixel.get(0),legacy=distance<radius*1.5f?Math.min(distance-1,6000):6000;
                        float expected=main?(surface==1 || (dh && surface==2)?Math.min(distance-1,6000):6000):legacy;
                        if(version==0){if(main && surface==0 && Math.abs(actual-expected)>.01f)oldSkyFailures++;}
                        else {if(!Float.isFinite(actual)||Math.abs(actual-expected)>.01f)throw new AssertionError("depth case failed dh="+dh+" main="+main+" surface="+surface+" radius="+radius+" distance="+distance+" actual="+actual+" expected="+expected);checks++;}
                    }
                    glDeleteProgram(program);glDeleteShader(vs);glDeleteShader(fs);
                }
            }
            if(oldSkyFailures==0)throw new AssertionError("The old clear-sky cutoff was not reproduced");
            System.out.printf("PASS GPU depth cases=%d oldEmptySkyFailures=%d newEmptySkyFailures=0%n",checks,oldSkyFailures);
        } finally {GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
