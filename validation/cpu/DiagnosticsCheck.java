package dev.cloudscape.volumetric;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DiagnosticsCheck {
    private static int checks;
    private static void require(boolean okay,String label){checks++;if(!okay)throw new AssertionError(label);}
    private static void hardware(int size)throws Exception {
        WorldDensityCache.reset();
        WorldDensityCache.atlasProbe = requested -> new AtlasSupport.Result(size,size==0?"atlas.unsupported":"atlas.smaller");
    }
    public static void main(String[] args)throws Exception {
        var field=UnboundAdapter.class.getDeclaredField("config");field.setAccessible(true);field.set(null,new CloudConfig());
        UnboundAdapter.resetPipeline();UnboundAdapter.finishPackLoad(false,"none");
        require(UnboundAdapter.diagnostic().equals("diagnostic.pending"),"No pack must not be labeled supported");
        UnboundAdapter.finishPackLoad(true,"unrelated-pack.zip");
        require(UnboundAdapter.diagnostic().equals("diagnostic.unsupported"),"Unrelated pack left waiting forever");
        var disabled=new CloudConfig();disabled.enabled=false;UnboundAdapter.replaceSettings(disabled);
        UnboundAdapter.finishPackLoad(true,"unsupported.zip");
        require(UnboundAdapter.diagnostic().equals("diagnostic.disabled"),"Disabled state lost");
        UnboundAdapter.replaceSettings(new CloudConfig());
        try(var fs=FileSystems.newFileSystem(Path.of(args[0]))) {
            Path root=fs.getPath("/shaders"),clouds=fs.getPath(ComplementaryContract.TARGETS.get("reimaginedClouds.glsl"));
            hardware(0);
            require(!WorldDensityCache.enabled(root),"Unsupported GPU retained atlas");
            require(WorldDensityCache.properties(root,"shaders.properties","untouched").equals("untouched"),"Unsupported GPU allocated target");
            require(WorldDensityCache.virtualSource(fs.getPath("/shaders/world0/prepare.fsh"))==null,"Unsupported GPU retained producer");
            String fallback=UnboundAdapter.transform(clouds,Files.readString(clouds));
            require(fallback.contains("CSCloudDensity")&&!fallback.contains("CS_DENSITY_ATLAS_SIZE"),"Unsupported GPU lost actual procedural Cloudscape");
            UnboundAdapter.finishPackLoad(true,"actual-pack.zip");
            require(UnboundAdapter.diagnostic().equals("diagnostic.active"),"Accepted interface mislabeled unsupported");
            hardware(1024);
            require(WorldDensityCache.enabled(root)&&!WorldDensityCache.residentPrimary(root),"Smaller hardware atlas did not retain generic full-range path");
            require(WorldDensityCache.defines(root).contains("CS_DENSITY_ATLAS_SIZE 1024"),"Effective hardware size not used by GLSL");
            String adapted=UnboundAdapter.transform(clouds,Files.readString(clouds));
            require(adapted.contains("CS_MAX_DISTANCE 6000.000000")&&adapted.contains("CS_VIEW_STEPS 96"),"GPU fallback silently reduced range or budgets");
            UnboundAdapter.resetPipeline();
            for(String name:new String[]{"AVAILABLE","PRIMARY","REASONS","ATLASES","COMPUTE"}) {
                var map=WorldDensityCache.class.getDeclaredField(name);map.setAccessible(true);
                require(((java.util.Map<?,?>)map.get(null)).isEmpty(),"Lifecycle retained stale "+name);
            }
            require(UnboundAdapter.diagnostic().equals("diagnostic.pending"),"Reload retained old accepted state");
        }
        System.out.println("PASS "+checks+" pack diagnostics, hardware fallback and reload-epoch assertions; CPU only.");
    }
}
