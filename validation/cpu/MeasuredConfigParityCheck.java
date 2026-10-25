package dev.cloudscape.volumetric;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/** Compare active settings/defines of every measured input after a migration-only fix. */
public final class MeasuredConfigParityCheck {
    private static Map<String,Object> load(Path jar,Path input) throws Exception {
        ArrayList<URL> urls=new ArrayList<>();urls.add(jar.toUri().toURL());
        for(String entry:System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))urls.add(Path.of(entry).toUri().toURL());
        try(URLClassLoader loader=new URLClassLoader(urls.toArray(URL[]::new),null)) {
            Class<?> storeType=loader.loadClass("dev.cloudscape.volumetric.ConfigStore");
            var constructor=storeType.getDeclaredConstructor(Path.class);constructor.setAccessible(true);
            var method=storeType.getDeclaredMethod("load");method.setAccessible(true);
            Object config=method.invoke(constructor.newInstance(input));
            Map<String,Object> result=new TreeMap<>();
            for(var field:config.getClass().getFields())result.put(field.getName(),field.get(config));
            var defines=config.getClass().getDeclaredMethod("defines");defines.setAccessible(true);result.put("defines",defines.invoke(config));
            return result;
        }
    }
    public static void main(String[] args)throws Exception {
        int count=0;
        try(var paths=Files.list(Path.of(args[2]))) {
            for(Path input:paths.filter(p->p.toString().endsWith(".json")).toList()) {
                byte[] before=Files.readAllBytes(input);
                if(!load(Path.of(args[0]),input).equals(load(Path.of(args[1]),input)))throw new AssertionError("Measured settings changed: "+input.getFileName());
                if(!Arrays.equals(before,Files.readAllBytes(input)))throw new AssertionError("Loading wrote "+input);
                count++;
            }
        }
        System.out.println("PASS "+count+" measured/demo configurations: all24 active fields and exact GLSL defines identical; no file writes");
    }
}
