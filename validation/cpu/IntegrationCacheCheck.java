package dev.cloudscape.volumetric;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;

/** Regression for requested 4096 with a physically smaller supported GPU atlas. */
public final class IntegrationCacheCheck {
    static int checks;
    static void require(boolean okay,String label){checks++;if(!okay)throw new AssertionError(label);}
    static void caseCheck(Path pack,int effective)throws Exception {
        CloudConfig config=new CloudConfig();config.densityAtlasSize=4096;
        UnboundAdapter.replaceSettings(config);
        WorldDensityCache.atlasProbe=requested->new AtlasSupport.Result(effective,effective==4096?"atlas.ready":effective==0?"atlas.unsupported":"atlas.smaller");
        WorldDensityCache.computeProbe=()->new WorldDensityCache.ComputeHardware(true,32768,8,16,1024,1024,1024,65535,65535);
        try(var fs=FileSystems.newFileSystem(pack)) {
            Path root=fs.getPath("/shaders");
            if(effective==0){
                require(!WorldDensityCache.enabled(root),"unsupported format must bypass cache");
                require(WorldDensityCache.virtualSource(fs.getPath("/shaders/world0/prepare.csh"))==null,"unsupported cache registered compute");
                require(WorldDensityCache.properties(root,"shaders.properties","original").equals("original"),"unsupported cache allocated attachment");
                return;
            }
            require(WorldDensityCache.enabled(root),"cache unavailable");
            require(WorldDensityCache.atlasSize(root)==effective,"effective size disagrees");
            require(WorldDensityCache.fullPeriod(root)==(effective==4096),"small atlas falsely marked a full period");
            require(WorldDensityCache.defines(root).contains("CS_DENSITY_ATLAS_SIZE "+effective),"GLSL uses requested rather than effective size");
            require(UnboundAdapter.settings().densityAtlasSize==4096,"hardware clamp overwrote user's request");
            require(WorldDensityCache.properties(root,"shaders.properties","").contains("size.buffer.colortex20="+(effective*8)+" "+effective),"allocation dimensions disagree");
            boolean compute=effective>=1536;
            require(WorldDensityCache.computeEnabled(root)==compute,"compute selected for unsupported resident radius");
            if(compute) {
                String source=WorldDensityCache.virtualSource(fs.getPath("/shaders/world0/prepare.csh"));
                int groups=(effective+7)/8;
                require(source.contains("workGroups = ivec3("+groups+", "+groups+", 1)"),"dispatch uses requested rather than physical size");
                require(source.contains("cloudscapeDensityEpochProof")==(effective==4096),"partial-period atlas incorrectly eligible for epoch reuse");
                String metadata=WorldDensityCache.attachmentMetadata(fs.getPath("/shaders/world0/deferred1.fsh"),"#version 130\nvoid main(){}\n");
                String unbound=JcppProcessor.glslPreprocessSource(metadata,List.of());
                String reimagined=JcppProcessor.glslPreprocessSource(metadata,List.of(new StringPair("CLOUDS_REIMAGINED","1")));
                require(!unbound.contains("colortex20Clear"),"Unbound retains target metadata");
                require(reimagined.contains("colortex20Clear"),"Reimagined lost target metadata");
            } else {
                require(WorldDensityCache.virtualSource(fs.getPath("/shaders/world0/prepare.csh"))==null,"smaller nonresident layout keeps compute source");
                require(WorldDensityCache.virtualSource(fs.getPath("/shaders/world0/prepare.fsh")).contains("CS_DENSITY_ATLAS_SIZE "+effective),"fragment fallback dimensions disagree");
            }
        }
    }
    public static void main(String[] args)throws Exception {
        for(int effective:new int[]{4096,1536,1024,0})caseCheck(Path.of(args[0]),effective);
        require(AtlasSupport.select(4096,32768,(w,h)->true).size()==4096,"full-period capable GPU downgraded");
        require(AtlasSupport.select(4096,16384,(w,h)->true).size()==1536,"16384 GPU did not retain exact smaller cache");
        require(AtlasSupport.select(4096,8192,(w,h)->true).size()==1024,"8192 GPU exceeded width limit");
        System.out.println("PASS "+checks+" integrated compute/size/style/fallback assertions; CPU only.");
    }
}
