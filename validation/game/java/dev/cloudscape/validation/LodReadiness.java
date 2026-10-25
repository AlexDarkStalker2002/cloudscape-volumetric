package dev.cloudscape.validation;

/** Records DH's own queue/render diagnostics without changing its configuration. */
final class LodReadiness {
    static void capture(String phase) {
        if(!Boolean.getBoolean("cloudscape.validation.distantHorizons"))return;
        try {
            Class<?> shared=Class.forName("com.seibel.distanthorizons.core.api.internal.SharedApi");
            Object instance=shared.getField("INSTANCE").get(null);
            Object diagnostics=shared.getMethod("getDebugMenuString").invoke(instance);
            System.out.println("CLOUDSCAPE DH READINESS phase="+phase+" diagnostics="+diagnostics);
        }catch(ReflectiveOperationException error){throw new AssertionError("DH readiness diagnostic unavailable",error);}
    }
}
