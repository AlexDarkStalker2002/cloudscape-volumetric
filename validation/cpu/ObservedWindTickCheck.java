package dev.cloudscape.volumetric;
public final class ObservedWindTickCheck {
    public static void main(String[] args){
        int assertions=0;
        for(float speed:new float[]{0,1,4})for(long jump:new long[]{1,2,5,6,100,2000,1000000}){
            CloudConfig c=new CloudConfig();c.windSpeed=speed;
            WeatherState s=WeatherState.start(0,.317,c);double seconds=s.seconds;
            for(int i=1;i<=200;i++){
                double before=s.seconds;s.observedTick(i*jump,0,0,c);
                double expected=(jump>5?1:jump)*.05;
                if(Math.abs(s.seconds-before-expected)>1e-8)throw new AssertionError("Missing/unbounded live step: "+jump);
                assertions++;
            }
            if(speed>0&&s.windX<=0)throw new AssertionError("Wind stalled at live clock jump "+jump);
            if(speed==0&&(s.windX!=0||s.windZ!=0))throw new AssertionError("Stopped wind moved");
            double frozen=s.seconds;s.observedTick(s.lastTick,1,1,c);
            if(s.seconds!=frozen)throw new AssertionError("Repeated clock advanced paused simulation");
            s.observedTick(1,0,0,c);
            if(s.seconds<=frozen||s.seconds-frozen>.051)throw new AssertionError("Clock reset froze/teleported live wind");
            assertions+=3;
        }
        System.out.println("PASS ObservedWindTickCheck "+assertions+" assertions: normal/accelerated/reset/paused clocks, bounded live updates, zero wind");
    }
}
