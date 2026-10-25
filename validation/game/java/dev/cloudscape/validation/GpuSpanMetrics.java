package dev.cloudscape.validation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import static org.lwjgl.opengl.GL33C.*;

/** Independent timestamp pairs; nested/overlapping tags never use GL_TIME_ELAPSED. */
public final class GpuSpanMetrics {
    private record Sample(String tag,int start,int end) {}
    private static final int MAX_PENDING=512;
    private static final ArrayDeque<Sample> pending=new ArrayDeque<>();
    private static final ArrayDeque<Integer> freeQueries=new ArrayDeque<>();
    private static final Map<String,ArrayDeque<Integer>> starts=new LinkedHashMap<>();
    private static final Map<String,ArrayList<Double>> values=new LinkedHashMap<>();
    private static int abandoned,skipped,unmatched;
    private static boolean described;

    private GpuSpanMetrics() {}
    private static boolean enabled(){
        return Boolean.getBoolean("cloudscape.validation.performance")
            && !System.getProperty("cloudscape.validation.gpuSpans","true").equalsIgnoreCase("false");
    }
    private static int query(){return freeQueries.isEmpty()?glGenQueries():freeQueries.removeFirst();}

    /** Once per world frame; remove unfinished old spans instead of spanning frames. */
    public static void beginFrame(){
        if(!enabled())return;
        drain();
        for(ArrayDeque<Integer> stack:starts.values())while(!stack.isEmpty()){
            int q=stack.pop();if(q!=0){freeQueries.add(q);abandoned++;}
        }
        starts.clear();
        if(!described){
            described=true;
            System.out.println("CLOUDSCAPE SPAN_DEFINITIONS SHADOW_TOTAL=ShadowRenderer.renderShadows invocation excluding prepare and earlier frame clears; "
                +"GBUFFERS_OPAQUE_INTERVAL=after prepare to beginTranslucents entry; "
                +"PRE_DEFERRED_SETUP=beginTranslucents entry to deferred invocation (includes depth copy); "
                +"GBUFFERS_TRANSLUCENT_INTERVAL=after deferred to finalizeLevelRendering entry; "
                +"FINAL_PASS=FinalPassRenderer.renderFinalPass invocation; "
                +"DH_*_TERRAIN=only terrain renderer call, children of geometry or shadow intervals; "
                +"GPU timestamp intervals include possible GPU idle between submitted work; nested spans MUST NOT be summed");
        }
    }

    public static void begin(String tag){
        if(!enabled())return;
        // Zero sentinels balance unmeasured recursive calls without closing an outer span.
        int q=0;
        if(ValidationRunner.measuring()){
            if(pending.size()<MAX_PENDING){q=query();glQueryCounter(q,GL_TIMESTAMP);}
            else skipped++;
        }
        starts.computeIfAbsent(tag,k->new ArrayDeque<>()).push(q);
    }

    public static void end(String tag){
        if(!enabled())return;
        ArrayDeque<Integer> stack=starts.get(tag);
        if(stack==null||stack.isEmpty()){unmatched++;return;}
        int start=stack.pop();if(stack.isEmpty())starts.remove(tag);
        if(start==0)return;
        int end=query();glQueryCounter(end,GL_TIMESTAMP);pending.addLast(new Sample(tag,start,end));
    }

    /** Read only completed pairs; never stall the render thread for measurements. */
    private static void drain(){
        while(!pending.isEmpty()&&glGetQueryObjecti(pending.peekFirst().end(),GL_QUERY_RESULT_AVAILABLE)!=0){
            Sample s=pending.removeFirst();
            long from=glGetQueryObjectui64(s.start(),GL_QUERY_RESULT),to=glGetQueryObjectui64(s.end(),GL_QUERY_RESULT);
            double ms=(to-from)/1e6;
            if(Double.isFinite(ms)&&ms>=0)values.computeIfAbsent(s.tag(),k->new ArrayList<>()).add(ms);
            else skipped++;
            freeQueries.add(s.start());freeQueries.add(s.end());
        }
    }

    public static void capture(String scene){
        if(!enabled())return;
        drain();
        for(var entry:values.entrySet()){
            ArrayList<Double> samples=entry.getValue();samples.sort(Double::compare);
            double average=samples.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            System.out.printf(Locale.ROOT,"CLOUDSCAPE SPAN %s %s medianMs=%.3f p95Ms=%.3f meanMs=%.3f minMs=%.3f maxMs=%.3f samples=%d%n",
                scene,entry.getKey(),percentile(samples,.5),percentile(samples,.95),average,samples.getFirst(),samples.getLast(),samples.size());
        }
        int unready=pending.size();
        while(!pending.isEmpty()){Sample s=pending.removeFirst();glDeleteQueries(s.start());glDeleteQueries(s.end());}
        for(ArrayDeque<Integer> stack:starts.values())while(!stack.isEmpty()){
            int q=stack.pop();if(q!=0){glDeleteQueries(q);abandoned++;}
        }
        starts.clear();values.clear();
        while(!freeQueries.isEmpty())glDeleteQueries(freeQueries.removeFirst());
        System.out.printf(Locale.ROOT,"CLOUDSCAPE SPAN_STATE %s unreadyPairsDropped=%d abandonedSpans=%d skippedBeginsOrInvalid=%d unmatchedEnds=%d%n",scene,unready,abandoned,skipped,unmatched);
        abandoned=0;skipped=0;unmatched=0;
    }
    private static double percentile(ArrayList<Double> a,double q){return a.get(Math.clamp((int)Math.ceil(a.size()*q)-1,0,a.size()-1));}
}
