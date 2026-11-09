package com.winlator.cmod.session;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.EnvVars;

/**
 * OpenGL through Mesa for the launch: Freedreno on Adreno (kgsl, GL 3.3 unless set) or Zink on
 * the Vulkan driver, with each one's defaults; an automatic GL 3.3 override is dropped for Zink
 * unless the user set it. Moved out of the game screen (base migration).
 */
public final class OpenGLEnv {
    private OpenGLEnv() {}

    public static void apply(EnvVars envVars, String graphicsDriver, Container container, Shortcut shortcut) {
        boolean freedreno = "freedreno".equals(GraphicsDriverFiles.openGLDriver(graphicsDriver));
        envVars.put("GALLIUM_DRIVER", freedreno ? "freedreno" : "zink");
        envVars.put("MESA_LOADER_DRIVER_OVERRIDE", freedreno ? "kgsl" : "zink");
        if (freedreno) {
            if (!envVars.has("MESA_GL_VERSION_OVERRIDE")) envVars.put("MESA_GL_VERSION_OVERRIDE", "3.3");
            envVars.remove("ZINK_DESCRIPTORS");
            envVars.remove("ZINK_DEBUG");
            envVars.remove("ZINK_INLINE_UNIFORMS");
            if (!envVars.has("FD_MESA_DEBUG")) envVars.put("FD_MESA_DEBUG", "hiprio");
            if (!envVars.has("vblank_mode")) envVars.put("vblank_mode", "0");
        } else {
            if (!glVersionSetByUser(container, shortcut) && "3.3".equals(envVars.get("MESA_GL_VERSION_OVERRIDE")))
                envVars.remove("MESA_GL_VERSION_OVERRIDE");
            if (!envVars.has("ZINK_DESCRIPTORS")) envVars.put("ZINK_DESCRIPTORS", "lazy");
            if (!envVars.has("ZINK_DEBUG")) envVars.put("ZINK_DEBUG", "compact");
        }
    }

    /** MESA_GL_VERSION_OVERRIDE set by the user, not put there automatically. */
    public static boolean glVersionSetByUser(Container container, Shortcut shortcut) {
        if (shortcut != null && new EnvVars(shortcut.getExtra("envVars", "")).has("MESA_GL_VERSION_OVERRIDE"))
            return !"1".equals(shortcut.getExtra("autoMesaGlVersionOverride", "0"));
        if (container == null) return false;
        return new EnvVars(container.getEnvVars()).has("MESA_GL_VERSION_OVERRIDE")
                && !"1".equals(container.getExtra("autoMesaGlVersionOverride", "0"));
    }
}
