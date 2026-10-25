package dev.cloudscape.volumetric;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Behavioral risks: interruption, fixed ticks, checkpoint, legacy opt-in. */
public final class WeatherCheck {
    private static int checks;
    private static void require(boolean condition,String message) {
        checks++;if(!condition)throw new AssertionError(message);
    }
    private static String serialize(WeatherState state){return ConfigStore.GSON.toJson(state);}
    public static void main(String[] args) throws Exception {
        CloudConfig config=new CloudConfig();config.shapeMode=1;config.weatherVariability=false;
        WeatherState state=WeatherState.start(0,.317,config);
        double previous=0;
        for(long tick=1;tick<=2400;tick++) {
            state.tick(tick,1,1,config);
            require(state.valid(.317),"nonfinite/unbounded transition");
            require(state.value[1]>=previous,"step response overshoot/reversal");
            previous=state.value[1];
        }
        require(state.value[1]>.949 && state.value[1]<.951,"120 s must reach approximately 95%");
        double prior=state.value[1],velocity=state.value[1]-previous;
        state.tick(2401,0,0,config);
        require(Math.abs(state.value[1]-prior)<.001,"retarget jump");
        WeatherState a=WeatherState.start(100,.317,config), b=WeatherState.start(100,.317,config);
        for(long tick=101;tick<10100;tick++) {
            float rain=tick%3000<1500?1:0;
            a.tick(tick,rain,rain*.5f,config);
            // Extra render-like reads and duplicate tick requests cannot mutate state.
            for(int read=0;read<(tick%7);read++){b.cloudiness();b.tick(tick-1,rain,rain*.5f,config);}
            b.tick(tick,rain,rain*.5f,config);
            require(serialize(a).equals(serialize(b)),"request frequency changes simulation");
            if(tick%137==0)b=ConfigStore.GSON.fromJson(serialize(b),WeatherState.class);
        }
        double x=a.windX,z=a.windZ,morph=a.morph;
        config.windSpeed=0;
        for(long t=10100;t<10500;t++)a.tick(t,0,0,config);
        require(a.windX==x && a.windZ==z && a.morph>morph,"wind=0 must stop advection and retain evolution");
        String before=serialize(a);a.tick(a.lastTick,1,1,config);
        require(before.equals(serialize(a)),"paused tick changed state");
        x=a.windX;z=a.windZ;morph=a.morph;
        a.tick(300000,1,1,config);
        require(a.windX==x && a.windZ==z && a.morph==morph && a.discontinuities==1,"gap invented weather history");
        a.tick(5,1,1,config);require(a.discontinuities==2 && a.valid(.317),"backward gameTime");
        WeatherState corrupt=ConfigStore.GSON.fromJson(serialize(a),WeatherState.class);
        corrupt.windX=1e308;require(!corrupt.valid(.317),"finite double may overflow a shader float");
        corrupt=ConfigStore.GSON.fromJson(serialize(a),WeatherState.class);corrupt.seconds=1e308;
        require(!corrupt.valid(.317),"checkpoint age outside the gameTime domain");
        corrupt=ConfigStore.GSON.fromJson(serialize(a),WeatherState.class);corrupt.value[0]=Double.NaN;
        require(!corrupt.valid(.317),"checkpoint NaN accepted");
        Path dir=Path.of(args[0]);Files.createDirectories(dir);
        Path old=dir.resolve("schema1.json");byte[] bytes="{\"schemaVersion\":1,\"coverage\":0.67,\"futureUserField\":42}".getBytes();Files.write(old,bytes);
        ConfigStore store=new ConfigStore(old);CloudConfig migrated=store.load();
        require(migrated.shapeMode==0 && migrated.transitionSeconds==120 && migrated.weatherVariability,"unsafe migration");
        require(Arrays.equals(bytes,Files.readAllBytes(old)),"loading rewrites legacy config");
        require(migrated.coverage==.67f,"legacy art changed");
        Path chosen=dir.resolve("schema1-chosen-thickness.json");
        byte[] chosenBytes="{\"schemaVersion\":1,\"reimaginedThickness\":64,\"coverage\":0.6}".getBytes();Files.write(chosen,chosenBytes);
        CloudConfig chosenConfig=new ConfigStore(chosen).load();
        require(chosenConfig.reimaginedThickness==64f,"schema1 chosen thickness must not use schema-less legacy migration");
        require(Arrays.equals(chosenBytes,Files.readAllBytes(chosen)),"schema1 migration writes on load");
        migrated.shapeMode=1;try(var staged=store.stage(migrated)){staged.commit();}
        require(Files.readString(old).contains("futureUserField"),"unknown field lost");
        CloudConfig roundtrip=new ConfigStore(old).load();require(roundtrip.shapeMode==1,"new mode not persisted");
        roundtrip.transitionSeconds=-1;roundtrip.shapeMode=9;roundtrip.validate();
        require(roundtrip.transitionSeconds==10 && roundtrip.shapeMode==0,"invalid art config not bounded");
        require(new CloudConfig().densityAtlasSize==1536 && new CloudConfig().viewSteps==96
            && new CloudConfig().reflectionSteps==24 && new CloudConfig().lightSteps==5,"quality defaults changed");
        System.out.println("PASS WeatherCheck "+checks+" assertions: ticks/checkpoint/retarget/zero-wind/gaps/schema migration");
    }
}
