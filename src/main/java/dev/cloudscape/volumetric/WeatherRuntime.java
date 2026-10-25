package dev.cloudscape.volumetric;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.joml.Vector4f;
import org.joml.Vector2f;
import net.irisshaders.iris.gl.uniform.FloatSupplier;

/** Tick-owned simulation, read-only uniforms and retained wind snapshots. */
public final class WeatherRuntime {
    private static ClientLevel owner;
    private static WeatherState state;
    private static Path checkpoint;
    private static final Vector4f weather = new Vector4f(.5f,0,0,0), phase = new Vector4f();
    // Iris Vector2Uniform retains the supplied reference as its cached value.
    // Never mutate a wind snapshot after it has been handed to a shader.
    private static Vector2f wind = new Vector2f();
    private static int sinceSave;
    private static boolean saveFailed;

    public static void tick(ClientLevel level) {
        CloudConfig config = UnboundAdapter.settings();
        if (config.shapeMode != 1 || !config.enabled) return;
        if (owner != level) enter(level,config);
        state.observedTick(level.getGameTime(),level.getRainLevel(1f),level.getThunderLevel(1f),config);
        publish();
        // Persist on world departure, outside rendering/measurement windows.
    }

    private static void enter(ClientLevel level, CloudConfig config) {
        leave();
        owner=level;
        Minecraft mc=Minecraft.getInstance();
        // Local world path or server address; never request an unavailable seed.
        String identity=mc.getSingleplayerServer()!=null
            ? "local:"+mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
            : "server:"+(mc.getCurrentServer()!=null ? mc.getCurrentServer().ip : "local-connection");
        identity += ":"+level.dimension().identifier();
        String hash=UnboundAdapter.sha256(identity);
        double seed=Long.parseUnsignedLong(hash.substring(0,12),16)/(double)0x1000000000000L;
        if (Boolean.getBoolean("cloudscape.validation"))
            seed=Double.parseDouble(System.getProperty("cloudscape.validation.weatherSeed",Double.toString(seed)));
        checkpoint=FabricLoader.getInstance().getConfigDir().resolve("cloudscape-weather").resolve(hash+".json");
        try {
            if (Files.isRegularFile(checkpoint)) {
                WeatherState saved=ConfigStore.GSON.fromJson(Files.readString(checkpoint),WeatherState.class);
                if (saved!=null && saved.valid(seed)) state=saved;
                else UnboundAdapter.LOGGER.warn("Cloudscape weather checkpoint invalid; deterministic initial state used");
            }
        } catch(Exception error) { UnboundAdapter.LOGGER.warn("Cannot read weather checkpoint; initial state used",error); }
        if(state==null)state=WeatherState.start(level.getGameTime(),seed,config);
        // Do not integrate a missing history over re-entry or dimension changes.
        if(state.lastTick!=level.getGameTime())state.discontinuities++;
        state.lastTick=level.getGameTime();
        sinceSave=0;saveFailed=false;
        UnboundAdapter.LOGGER.info("Cloudscape weather enter id={} simulationTick={} seed={} discontinuities={}",
            hash.substring(0,12),state.lastTick,seed,state.discontinuities);
        publish();
    }

    private static void publish() {
        weather.set(state.cloudiness(),(float)state.value[1],(float)state.value[2],(float)state.seed);
        phase.set((float)state.windX,(float)state.windZ,(float)state.morph,0f);
        wind = new Vector2f(phase.x,phase.y);
    }

    public static void register(UniformHolder uniforms) {
        uniforms.uniform4f(UniformUpdateFrequency.PER_FRAME,"csWeather",()->weather);
        uniforms.uniform2f(UniformUpdateFrequency.PER_FRAME,"csWind",()->wind);
        uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME,"csMorph",(FloatSupplier)()->phase.z);
    }

    public static String diagnostic() { return state==null ? "inactive" : ConfigStore.GSON.toJson(state).replace("\n",""); }

    private static void save() {
        sinceSave=0;
        if(state==null || checkpoint==null)return;
        Path pending=null;
        try {
            Files.createDirectories(checkpoint.getParent());
            pending=Files.createTempFile(checkpoint.getParent(),"weather-",".pending");
            Files.writeString(pending,ConfigStore.GSON.toJson(state)+"\n");
            Files.move(pending,checkpoint,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } catch(Exception error) {
            if(!saveFailed)UnboundAdapter.LOGGER.warn("Cannot save weather phase; resume continuity unavailable",error);
            saveFailed=true;
        } finally { if(pending!=null)try{Files.deleteIfExists(pending);}catch(Exception ignored){} }
    }

    public static void leave() {
        save();owner=null;state=null;checkpoint=null;
    }
}
