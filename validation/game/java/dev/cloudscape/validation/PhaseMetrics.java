package dev.cloudscape.validation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import static org.lwjgl.opengl.GL33C.*;

/** Timestamp pairs coexist with the outer elapsed-time frame query. */
public final class PhaseMetrics {
    private record Sample(String phase,int start,int end) {}
    private static final ArrayDeque<Sample> pending=new ArrayDeque<>();
    private static final LinkedHashMap<String,ArrayList<Double>> values=new LinkedHashMap<>();
    private static int start;
    public static void begin(){
        if(!Boolean.getBoolean("cloudscape.validation.performance")||!ValidationRunner.measuring())return;
        while(!pending.isEmpty()&&glGetQueryObjecti(pending.peek().end,GL_QUERY_RESULT_AVAILABLE)!=0){
            Sample sample=pending.remove();
            double ms=(glGetQueryObjectui64(sample.end,GL_QUERY_RESULT)-glGetQueryObjectui64(sample.start,GL_QUERY_RESULT))/1e6;
            values.computeIfAbsent(sample.phase,k->new ArrayList<>()).add(ms);
            glDeleteQueries(sample.start);glDeleteQueries(sample.end);
        }
        if(pending.size()>64)return;
        start=glGenQueries();glQueryCounter(start,GL_TIMESTAMP);
    }
    public static void end(String phase){
        if(start==0)return;
        int end=glGenQueries();glQueryCounter(end,GL_TIMESTAMP);
        pending.add(new Sample(phase,start,end));start=0;
    }
    public static void capture(String scene){
        for(var entry:values.entrySet()){
            entry.getValue().sort(Double::compare);
            System.out.printf(Locale.ROOT,"CLOUDSCAPE PHASE %s %s medianMs=%.3f samples=%d%n",scene,entry.getKey(),entry.getValue().get(entry.getValue().size()/2),entry.getValue().size());
        }
        values.clear();
        while(!pending.isEmpty()){Sample sample=pending.remove();glDeleteQueries(sample.start);glDeleteQueries(sample.end);}
    }
}
