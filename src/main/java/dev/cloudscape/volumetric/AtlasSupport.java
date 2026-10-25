package dev.cloudscape.volumetric;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

/** Probe a proxy image: asks the driver about R32F without allocating GPU storage. */
final class AtlasSupport {
    record Result(int size, String reason) {}
    interface Probe { boolean supports(int width, int height); }

    static Result select(int requested, int maxTextureSize, Probe probe) {
        for (int size : new int[]{4096, 1536, 1024, 768, 512, 256}) {
            if (size > requested || size * 8 > maxTextureSize || size > maxTextureSize) continue;
            if (probe.supports(size * 8, size))
                return new Result(size, size == requested ? "atlas.ready" : "atlas.smaller");
        }
        return new Result(0, "atlas.unsupported");
    }

    static Result query(int requested) {
        try {
            var capabilities = GL.getCapabilities();
            if (!capabilities.OpenGL30 || !(capabilities.OpenGL43 || capabilities.GL_ARB_internalformat_query2))
                return new Result(0, "atlas.unsupported");
            if (GL42.glGetInternalformati(GL11.GL_TEXTURE_2D, GL30.GL_R32F, GL43.GL_FRAMEBUFFER_RENDERABLE) != GL43.GL_FULL_SUPPORT)
                return new Result(0, "atlas.unsupported");
            int maximum = Math.min(GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE),GL11.glGetInteger(GL30.GL_MAX_RENDERBUFFER_SIZE));
            return select(requested, maximum, (width, height) -> {
                GL11.glTexImage2D(GL11.GL_PROXY_TEXTURE_2D, 0, GL30.GL_R32F,
                    width, height, 0, GL11.GL_RED, GL11.GL_FLOAT, 0L);
                return GL11.glGetTexLevelParameteri(GL11.GL_PROXY_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH) == width
                    && GL11.glGetTexLevelParameteri(GL11.GL_PROXY_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT) == height
                    && GL11.glGetTexLevelParameteri(GL11.GL_PROXY_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT) == GL30.GL_R32F;
            });
        } catch (IllegalStateException | LinkageError unavailable) {
            return new Result(0, "atlas.noContext");
        }
    }
}
