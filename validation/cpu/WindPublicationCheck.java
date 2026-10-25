package dev.cloudscape.volumetric;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import org.joml.Vector2f;

/** The consumer may retain a supplier value between updates, as Iris does. */
public final class WindPublicationCheck {
    static int assertions;
    static void check(boolean ok,String message){assertions++;if(!ok)throw new AssertionError(message);}
    @SuppressWarnings("unchecked")
    public static void main(String[] args)throws Exception {
        Map<String,Object> supplied=new HashMap<>();
        UniformHolder holder=(UniformHolder)Proxy.newProxyInstance(UniformHolder.class.getClassLoader(),new Class[]{UniformHolder.class},(proxy,method,a)->{
            if(a!=null&&a.length==3&&a[1] instanceof String name){
                check(a[0]==UniformUpdateFrequency.PER_FRAME,"uniform frequency "+name);
                supplied.put(name,a[2]);
            }
            return proxy;
        });
        WeatherRuntime.register(holder);
        Supplier<Vector2f> wind=(Supplier<Vector2f>)supplied.get("csWind");
        check(wind!=null,"registered wind supplier");
        var stateField=WeatherRuntime.class.getDeclaredField("state");stateField.setAccessible(true);
        var publish=WeatherRuntime.class.getDeclaredMethod("publish");publish.setAccessible(true);
        for(float speed:new float[]{0f,.65f,1f,4f}){
            CloudConfig config=new CloudConfig();config.windSpeed=speed;
            WeatherState state=WeatherState.start(0,.317,config);
            stateField.set(null,state);publish.invoke(null);
            Vector2f retained=wind.get();float x=retained.x,z=retained.y;
            for(int tick=1;tick<=200;tick++){
                state.tick(tick,0,0,config);publish.invoke(null);
                Vector2f current=wind.get();
                check(retained.x==x&&retained.y==z,"retained Iris wind snapshot mutated at speed="+speed+" tick="+tick);
                check(current.x==(float)state.windX&&current.y==(float)state.windZ,"supplier follows simulation");
                check(wind.get()==current,"read-only repeated reads allocate nothing");
                if(speed>0)check(!retained.equals(current),"new wind must be distinguishable from retained value");
                else check(current.x==x&&current.y==z,"wind zero remains stopped");
                retained=current;x=current.x;z=current.y;
            }
        }
        System.out.println("PASS WindPublicationCheck "+assertions+" assertions: retained values, 0/.65/1/4 speeds, unchanged simulation, allocation-free shader reads");
    }
}
