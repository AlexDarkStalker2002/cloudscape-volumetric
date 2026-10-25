package dev.cloudscape.volumetric;

/** Reads the actual loaded mod's common API; compiled against either tested JAR. */
public final class RuntimeConfigDiagnostics {
    public static void stopWind() {
        UnboundAdapter.settings().windSpeed=0f;
        System.out.println("CLOUDSCAPE MOTION FIXTURE wind=0 in isolated runtime; morph retained");
    }
    public static void capture() {
        var options=net.minecraft.client.Minecraft.getInstance().options;
        int actualCap=options.framerateLimit().get();
        System.out.println("CLOUDSCAPE ACTUAL DISPLAY fpsCap="+actualCap+" vsync="+options.enableVsync().get());
        int expectedCap=Integer.getInteger("cloudscape.validation.expectedFpsCap",actualCap);
        if(actualCap!=expectedCap)throw new AssertionError("Game rejected requested FPS cap "+expectedCap+" actual="+actualCap);
        CloudConfig config=UnboundAdapter.settings();
        System.out.printf(java.util.Locale.ROOT,"CLOUDSCAPE ACTUAL CONFIG enabled=%s view=%d reflection=%d light=%d range=%.1f coverage=%.6f density=%.6f wind=%.6f voxel=%d thickness=%.1f atlasRequested=%d%n",
            config.enabled,config.viewSteps,config.reflectionSteps,config.lightSteps,config.maxDistance,config.coverage,config.density,config.windSpeed,config.reimaginedVoxelSize,config.reimaginedThickness,config.densityAtlasSize);
        if(config.viewSteps!=Integer.getInteger("cloudscape.validation.expectedViewSteps",96)
            ||config.reflectionSteps!=Integer.getInteger("cloudscape.validation.expectedReflectionSteps",24)
            ||config.lightSteps!=Integer.getInteger("cloudscape.validation.expectedLightSteps",5)
            ||config.maxDistance!=6000)
            throw new AssertionError("Comparison changed original sampling/range budgets");
        System.out.println("CLOUDSCAPE ACTUAL ART shapeMode="+config.shapeMode+" weatherVariability="+config.weatherVariability);
        if(Boolean.getBoolean("cloudscape.validation.noAtlas")) {
            try {
                var pipeline=net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable();
                if(!(pipeline instanceof net.irisshaders.iris.pipeline.IrisRenderingPipeline iris))throw new AssertionError("Shader pipeline missing");
                var field=iris.getClass().getDeclaredField("renderTargets");field.setAccessible(true);
                if(((net.irisshaders.iris.targets.RenderTargets)field.get(iris)).get(20)!=null)throw new AssertionError("Unused atlas allocated");
                System.out.println("CLOUDSCAPE NO_ATLAS PASS actual Iris target20 absent");
            }catch(ReflectiveOperationException error){throw new AssertionError(error);}
        }
        if(Boolean.getBoolean("cloudscape.validation.unsupportedFixture")) {
            try {
                var diagnostic=UnboundAdapter.class.getDeclaredMethod("diagnostic");diagnostic.setAccessible(true);
                if(!diagnostic.invoke(null).equals("diagnostic.unsupported"))throw new AssertionError("Unsupported status missing");
                var pipeline=net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable();
                if(!(pipeline instanceof net.irisshaders.iris.pipeline.IrisRenderingPipeline iris))throw new AssertionError("Original shader pipeline missing");
                var field=iris.getClass().getDeclaredField("renderTargets");field.setAccessible(true);
                if(((net.irisshaders.iris.targets.RenderTargets)field.get(iris)).get(20)!=null)throw new AssertionError("Unsupported pack allocated density atlas");
                if(net.irisshaders.iris.Iris.getStoredError().isPresent())throw new AssertionError("Shaderpack fell back after compile error");
                System.out.println("CLOUDSCAPE UNSUPPORTED PASS original pack active without atlas");
            }catch(ReflectiveOperationException error){throw new AssertionError(error);}
        }
    }
}
