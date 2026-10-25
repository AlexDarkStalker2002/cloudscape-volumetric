package dev.cloudscape.validation;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;

/** The baseline renderer predates the settings API; it never loads the UI driver. */
public final class UiBridge {
    private static Method tick, frame;
    private static void link() throws ReflectiveOperationException {
        if(tick!=null)return;
        Class<?> driver=Class.forName("dev.cloudscape.volumetric.UiGameCheck",true,UiBridge.class.getClassLoader());
        tick=driver.getMethod("tick",Minecraft.class);frame=driver.getMethod("frameFinished");
    }
    public static boolean tick(Minecraft mc) {
        if(!Boolean.getBoolean("cloudscape.validation.ui"))return false;
        try{link();return (Boolean)tick.invoke(null,mc);}
        catch(InvocationTargetException error){throw new AssertionError("UI driver failed",error.getCause());}
        catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }
    public static void frameFinished() {
        if(!Boolean.getBoolean("cloudscape.validation.ui"))return;
        try{link();frame.invoke(null);}
        catch(InvocationTargetException error){throw new AssertionError(error.getCause());}
        catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }
}
