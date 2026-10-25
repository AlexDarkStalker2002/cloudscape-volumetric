package dev.cloudscape.volumetric;
import java.nio.file.*;
import java.util.*;
/** Observable coverage episodes, continuity, saved state and vanilla response. */
public final class WeatherEpisodeCheck {
  public static void main(String[] args) throws Exception {
    Path out=Path.of(args[0]);Files.createDirectories(out);
    CloudConfig c=new CloudConfig();c.shapeMode=1;c.weatherVariability=true;
    int assertions=0;
    List<Map<String,Object>> summaries=new ArrayList<>();
    for(double seed:new double[]{0.0,.317,.5,1.0}) {
      WeatherState s=WeatherState.start(0,seed,c);
      double min=1,max=0,maxDelta=0,previous=s.cloudiness();
      int clear=0,heavy=0;
      try(var csv=Files.newBufferedWriter(out.resolve("seed-"+seed+".csv"))) {
        csv.write("tick,seconds,cloudiness,rain,thunder,windX,windZ,morph\n");
        for(long tick=1;tick<=576000;tick++) {
          s.tick(tick,0,0,c);
          if(!s.valid(seed))throw new AssertionError("Invalid episode state");
          double cloud=s.cloudiness();min=Math.min(min,cloud);max=Math.max(max,cloud);
          maxDelta=Math.max(maxDelta,Math.abs(cloud-previous));previous=cloud;
          if(cloud<.10)clear++;if(cloud>.90)heavy++;
          if(tick%400==0 && seed==.317)
            csv.write(tick+","+s.seconds+","+cloud+",0,0,"+s.windX+","+s.windZ+","+s.morph+"\n");
          if(tick%70001==0) {
            String saved=ConfigStore.GSON.toJson(s);
            WeatherState copy=ConfigStore.GSON.fromJson(saved,WeatherState.class);
            for(long next=tick+1;next<=tick+5;next++) {
              float r=next%2;
              WeatherState left=ConfigStore.GSON.fromJson(saved,WeatherState.class);
              WeatherState right=ConfigStore.GSON.fromJson(saved,WeatherState.class);
              left.tick(next,r,r,c);right.cloudiness();right.tick(next,r,r,c);
              if(!ConfigStore.GSON.toJson(left).equals(ConfigStore.GSON.toJson(right)))throw new AssertionError("Read count affects episodes");
              assertions++;
            }
            if(!copy.valid(seed))throw new AssertionError("Saved episode state invalid");
          }
        }
      }
      if(min>.08 || max<.92 || clear<2400 || heavy<2400)throw new AssertionError("Missing sustained clear/heavy episode seed="+seed+" min="+min+" max="+max);
      if(maxDelta>.0003)throw new AssertionError("Episode boundary pop "+maxDelta);
      summaries.add(Map.of("seed",seed,"simulationSeconds",28800,"min",min,"max",max,"clearSeconds",clear*.05,"heavySeconds",heavy*.05,"maxPerTickDelta",maxDelta));
      assertions+=4;
    }
    c.weatherVariability=false;
    WeatherState disabled=WeatherState.start(0,.317,c);
    for(long tick=1;tick<=24000;tick++) {
      disabled.tick(tick,0,0,c);
      if(disabled.cloudiness()!=.5f)throw new AssertionError("Disabled variation changes chosen coverage");
    }
    for(long tick=24001;tick<=26400;tick++)disabled.tick(tick,1,1,c);
    if(disabled.cloudiness()<.97f)throw new AssertionError("Vanilla storm did not override dry amount");
    assertions+=2;
    Files.writeString(out.resolve("episodes.json"),ConfigStore.GSON.toJson(Map.of("passed",true,"assertions",assertions,"seeds",summaries)));
    System.out.println("PASS WeatherEpisodeCheck "+assertions+" assertions; 8h x4 seeds, sustained clear/heavy, tick continuity, checkpoint and vanilla storm");
  }
}

