package dev.cloudscape.volumetric;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** CPU-only ZIP/interface/layout checks. Original pack archives are never mutated. */
public final class WorldDensityCacheCheck {
    static int checks,fixture;
    static Path output;
    static void require(boolean passed,String message){checks++;if(!passed)throw new AssertionError(message);}
    static String hash(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
    static Map<String,byte[]> read(Path path)throws Exception{
        Map<String,byte[]> files=new LinkedHashMap<>();
        try(ZipFile zip=new ZipFile(path.toFile())){
            for(var entries=zip.entries();entries.hasMoreElements();){var entry=entries.nextElement();if(!entry.isDirectory())files.put(entry.getName(),zip.getInputStream(entry).readAllBytes());}
        }
        return files;
    }
    static String text(Map<String,byte[]> files,String name){return new String(files.get(name),StandardCharsets.UTF_8);}
    static void put(Map<String,byte[]> files,String name,String value){files.put(name,value.getBytes(StandardCharsets.UTF_8));}
    static void change(Map<String,byte[]> files,String name,String from,String to){
        String before=text(files,name);if(!before.contains(from))throw new AssertionError("Fixture marker absent: "+from);
        put(files,name,before.replace(from,to));
    }
    static Path fixture(Map<String,byte[]> source,String label,Consumer<Map<String,byte[]>> mutate)throws Exception{
        Map<String,byte[]> files=new LinkedHashMap<>(source);mutate.accept(files);
        Path path=output.resolve(String.format("fixture-%02d-%s.zip",++fixture,label));
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(path))){
            for(var entry:files.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}
        }
        return path;
    }
    static void config(CloudConfig value)throws Exception{
        Field field=UnboundAdapter.class.getDeclaredField("config");field.setAccessible(true);field.set(null,value);
    }
    static void layoutChecks()throws Exception{
        CloudConfig cfg=new CloudConfig();config(cfg);
        require(WorldDensityCache.cacheLayout(cfg)&&WorldDensityCache.residentLayout(cfg),"Default1536 layout must be resident");
        cfg.densityAtlasSize=1024;require(WorldDensityCache.cacheLayout(cfg)&&!WorldDensityCache.residentLayout(cfg),"1024 keeps generic cache, disables full range resident");
        cfg.densityAtlasSize=512;cfg.maxDistance=1984;require(WorldDensityCache.residentLayout(cfg),"Exact radius margin boundary must pass");
        cfg.maxDistance=Math.nextUp(1984f);require(!WorldDensityCache.residentLayout(cfg),"One float past radius margin must fail");
        cfg.densityAtlasSize=1536;cfg.maxDistance=10000;require(WorldDensityCache.cacheLayout(cfg)&&!WorldDensityCache.residentLayout(cfg),"10000 range retains generic fallback");
        for(int voxel:new int[]{4,8}){cfg.reimaginedVoxelSize=voxel;require(!WorldDensityCache.cacheLayout(cfg)&&!WorldDensityCache.residentLayout(cfg),"Unsupported voxel layout must fail: "+voxel);}
        cfg.reimaginedVoxelSize=16;cfg.maxDistance=6000;cfg.reimaginedThickness=129;require(!WorldDensityCache.cacheLayout(cfg),"Ninth altitude slice must fail");
        cfg.reimaginedThickness=Float.NaN;require(!WorldDensityCache.cacheLayout(cfg),"Nonfinite height must fail");
        cfg.reimaginedThickness=24;require(WorldDensityCache.residentLayout(cfg),"Short supported layer must retain residency");
        cfg.reimaginedThickness=128;cfg.maxDistance=Float.NaN;require(!WorldDensityCache.residentLayout(cfg),"Nonfinite range must fail");
        cfg.maxDistance=Float.POSITIVE_INFINITY;require(!WorldDensityCache.residentLayout(cfg),"Infinite range must fail");
        cfg.maxDistance=-1;require(!WorldDensityCache.residentLayout(cfg),"Negative range must fail");
        cfg.maxDistance=6000;cfg.densityAtlasSize=1535;require(!WorldDensityCache.cacheLayout(cfg),"Odd atlas layout must fail");
        cfg.densityAtlasSize=1536;cfg.reimaginedDensityCache=false;require(!WorldDensityCache.cacheLayout(cfg),"Disabled cache must fail");
        cfg.reimaginedDensityCache=true;cfg.enabled=false;require(!WorldDensityCache.cacheLayout(cfg),"Disabled mod must fail");
        config(new CloudConfig());
    }
    static void caseCheck(Map<String,byte[]> source,String label,Consumer<Map<String,byte[]>> mutate,
                          boolean available,boolean resident)throws Exception{
        Path path=fixture(source,label,mutate);String before=hash(path);
        try(FileSystem fs=FileSystems.newFileSystem(path)){
            require(WorldDensityCache.available(fs)==available,label+": target/pass reservation mismatch");
            require(WorldDensityCache.enabled(fs.getPath("/shaders"))==available,label+": generic cache unexpectedly changed");
            require(WorldDensityCache.residentPrimary(fs.getPath("/shaders"))==resident,label+": resident result mismatch");
            if(available&&!resident){
                require(UnboundAdapter.compatible(fs.getPath("/shaders")),label+": basic adapter should stay compatible");
                require(WorldDensityCache.defines(fs.getPath("/shaders")).contains("CS_DENSITY_RESIDENT_PRIMARY 0"),label+": generic fallback define missing");
            }
        }
        require(before.equals(hash(path)),label+": adapter wrote to a fixture ZIP");
    }
    static void packChecks(Path path)throws Exception{
        String before=hash(path);Map<String,byte[]> source=read(path);String label=path.getFileName().toString().startsWith("ComplementaryUnbound")?"unbound":"reimagined";
        try(FileSystem fs=FileSystems.newFileSystem(path)){
            require(WorldDensityCache.available(fs),label+": actual pack should have an unused slot");
            require(WorldDensityCache.knownPrimary(fs),label+": actual source fingerprint absent from checked-in manifest");
            require(WorldDensityCache.enabled(fs.getPath("/shaders")),label+": actual generic cache must remain enabled");
            require(WorldDensityCache.residentPrimary(fs.getPath("/shaders")),label+": actual primary should be resident");
            require(WorldDensityCache.primarySources(fs).size()>100,label+": fingerprint selected suspiciously few sources");
            System.out.println(label+" selectedFiles="+WorldDensityCache.primarySources(fs).size()+" fingerprint="+WorldDensityCache.primaryFingerprint(fs));
        }
        require(before.equals(hash(path)),label+": original ZIP changed after read-only checks");
        String primary="shaders/program/deferred1.glsl";
        caseCheck(source,label+"-style-default-mapping",files->change(files,"shaders/lib/common.glsl",
            "#define CLOUD_STYLE_DEFAULT 1","#define CLOUD_STYLE_DEFAULT 3"),false,false);
        caseCheck(source,label+"-format-comments",files->{
            put(files,primary,"// formatting-only source change\n"+text(files,primary).replace("clouds = GetClouds","clouds /* spacing */ =\n GetClouds"));
        },true,true);
        caseCheck(source,label+"-changed-offset",files->change(files,primary,
            "GetClouds(cloudLinearDepth, skyFade, vec3(0.0), playerPos","GetClouds(cloudLinearDepth, skyFade, vec3(1.0), playerPos"),true,false);
        caseCheck(source,label+"-changed-body",files->change(files,primary,"float cloudZCheck = 0.56;","float cloudZCheck = 0.57;"),true,false);
        caseCheck(source,label+"-direct-volume",files->put(files,primary,text(files,primary)+"\n#ifdef FRAGMENT_SHADER\n// A new direct volume caller is deliberately outside the old literal count guard.\nvoid AddedCaller(){float depth=1.0;vec4 c=GetVolumetricClouds(256,4000.0,depth,1.0,1.0,vec3(20000,117,0),vec3(1,0,0),1e9,0.0,0.0,0.5);}\n#endif\n"),true,false);
        caseCheck(source,label+"-added-include-caller",files->{
            put(files,"shaders/lib/futureCloudCaller.inc","#ifdef FRAGMENT_SHADER\nvoid FutureCloudCaller(){float depth=1.0;vec4 c=GetVolumetricClouds(256,4000.0,depth,1.0,1.0,vec3(20000,117,0),vec3(1,0,0),1e9,0.0,0.0,0.5);}\n#endif\n");
            put(files,"shaders/program/gbuffers_terrain.glsl",text(files,"shaders/program/gbuffers_terrain.glsl")+"\n#include \"/lib/futureCloudCaller.inc\"\n");
        },true,false);
        caseCheck(source,label+"-added-stage",files->put(files,"shaders/world0/future-stage.fsh","#version 130\n#define FRAGMENT_SHADER\n#define OVERWORLD\n#define DEFERRED1\n#include \"/program/deferred1.glsl\"\n"),true,false);
        caseCheck(source,label+"-unrelated-source",files->put(files,"shaders/lib/unrelated-new-helper.inc","float UnrelatedHelper(float x){return x*x;}\n"),true,false);
        caseCheck(source,label+"-normalize-macro",files->put(files,"shaders/lib/util/commonFunctions.glsl",
            text(files,"shaders/lib/util/commonFunctions.glsl")+"\n#define normalize(x) (x)\n"),true,false);
        caseCheck(source,label+"-new-comment-file",files->put(files,"shaders/notes.txt","// A new text dependency still changes the complete path set.\n"),true,false);
        caseCheck(source,label+"-stage-property",files->put(files,"shaders/shaders.properties",text(files,"shaders/shaders.properties")+"\nprogram.world0.deferred1.enabled=false\n"),true,false);
        caseCheck(source,label+"-property-preprocessor",files->put(files,"shaders/shaders.properties",text(files,"shaders/shaders.properties")+"\n#ifdef NEW_PIPELINE_MODE\nprogram.world0.deferred1.enabled=false\n#endif\n"),true,false);
        caseCheck(source,label+"-property-line-boundary",files->put(files,"shaders/shaders.properties",text(files,"shaders/shaders.properties")+"\nprogram.world0.deferred1.enabled=true program.world0.composite.enabled=false\n"),true,false);
        for(String declaration:new String[]{"uniform sampler2D colortex20;","uniform image2D colorimg20;","const int colortex20Format=R32F;","/* RENDERTARGETS: 0,20 */"})
            caseCheck(source,label+"-slot-"+fixture,files->put(files,"shaders/lib/reserved-attachment.glsl",declaration),false,false);
        for(String slot:new String[]{"shaders/prepare.fsh","shaders/world0/prepare.vsh","shaders/world0/prepare0.csh"})
            caseCheck(source,label+"-pass-"+fixture,files->put(files,slot,"#version 130\nvoid main(){}\n"),false,false);
        for(String directive:new String[]{"program.world0.prepare.enabled=false","scale.prepare=0.5","size.buffer.colortex20=128 128"})
            caseCheck(source,label+"-property-"+fixture,files->put(files,"shaders/shaders.properties",text(files,"shaders/shaders.properties")+"\n"+directive+"\n"),false,false);
        require(before.equals(hash(path)),label+": original ZIP changed after fixture construction/checks");
    }
    static void export(Path target,String[] args)throws Exception{
        StringBuilder manifest=new StringBuilder("# Resident-only references captured from actual tested packs; runtime never learns a new digest.\nformat=all-text-path-normalized-tokens-sha256-v2\n");
        for(int i=2;i<args.length;i++)try(FileSystem fs=FileSystems.newFileSystem(Path.of(args[i]))){
            manifest.append("source.").append(i-1).append('=').append(Path.of(args[i]).getFileName()).append('\n');
            manifest.append("fingerprint.").append(i-1).append('=').append(WorldDensityCache.primaryFingerprint(fs)).append('\n');
            manifest.append("selectedFiles.").append(i-1).append('=').append(WorldDensityCache.primarySources(fs).size()).append('\n');
        }
        Files.writeString(target,manifest);System.out.println(manifest);
    }
    public static void main(String[] args)throws Exception{
        // CPU contract fixtures supply an explicit capable driver. Production
        // performs the proxy/limit probe; hardware selection has separate tests.
        WorldDensityCache.atlasProbe = requested -> new AtlasSupport.Result(requested,"atlas.ready");
        if(args.length>0&&args[0].equals("--export-manifest")){export(Path.of(args[1]),args);return;}
        if(args.length!=3)throw new IllegalArgumentException("WorldDensityCacheCheck output-directory reimagined.zip unbound.zip");
        output=Path.of(args[0]);Files.createDirectories(output);layoutChecks();packChecks(Path.of(args[1]));packChecks(Path.of(args[2]));
        System.out.println("PASS "+checks+" CPU cache/layout/resident checks; original and fixture ZIP SHA256 unchanged by adapter reads; no GPU or Minecraft launched.");
    }
}
