package com.winlator.cmod.session;

import android.app.Activity;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.RendererOptionsDialog;
import com.winlator.cmod.widget.DisplayXServerView;
import com.winlator.cmod.widget.EGLXServerView;
import com.winlator.cmod.widget.VulkanXServerView;
import com.winlator.cmod.widget.XServerRendererView;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.XServer;

/**
 * YurNLator base, part 8: which view puts the X server's picture on screen, made and configured
 * from the game's settings (else the container's): DisplayX ("Bypass X11"), the EGL renderer
 * ("Native"), or the Vulkan renderer with its driver, present mode and filter. Moved out of the
 * game screen's setupUI.
 */
public final class RendererSetup {
    private RendererSetup() {}

    /** "Bypass X11": the game's buffers go to the screen without the X renderer. */
    public static boolean displayX(Container container, Shortcut shortcut) {
        if (shortcut != null) return shortcut.getUseDisplayX();
        return container != null && container.getUseDisplayX();
    }

    /** "rgba8" / "bgra8". */
    public static String surfaceFormat(Container container, Shortcut shortcut) {
        if (shortcut != null) return shortcut.getSurfaceFormat();
        return container != null ? container.getSurfaceFormat() : "rgba8";
    }

    /** The X server's pixel format: RGBA only for DisplayX with an RGBA surface. */
    public static int xServerFormat(Container container, Shortcut shortcut) {
        return displayX(container, shortcut) && "rgba8".equals(surfaceFormat(container, shortcut))
                ? Drawable.HAL_PIXEL_FORMAT_RGBA_8888
                : Drawable.HAL_PIXEL_FORMAT_BGRA_8888;
    }

    /**
     * The renderer view for this launch, configured and with the cursor hidden.
     * [driverVersion] is the graphics driver the container / game picked (the Vulkan renderer loads it too).
     */
    public static XServerRendererView create(Activity activity, XServer xServer, Container container,
                                             Shortcut shortcut, String driverVersion) {
        boolean useDisplayX = displayX(container, shortcut);
        boolean useEGL = !useDisplayX && (shortcut != null ? shortcut.getRendererNative()
                : (container != null && container.getRendererNative()));
        int filter = shortcut != null ? shortcut.getRendererFilterMode()
                : (container != null ? container.getRendererFilterMode() : 0);

        XServerRendererView renderer;
        if (useDisplayX) {
            DisplayXServerView.loadNativeLibrary();
            boolean performanceMode = shortcut != null ? shortcut.getDisplayXPerformanceMode()
                    : container == null || container.getDisplayXPerformanceMode();
            boolean presentAtRefreshRate = shortcut != null ? shortcut.getDisplayXPresentAtRefreshRate()
                    : container == null || container.getDisplayXPresentAtRefreshRate();
            renderer = new DisplayXServerView(activity, xServer, performanceMode, presentAtRefreshRate);
        } else if (useEGL) {
            EGLXServerView.loadNativeLibrary();
            EGLXServerView egl = new EGLXServerView(activity, xServer);
            egl.setFilterMode(filter);
            renderer = egl;
        } else {
            VulkanXServerView.loadNativeLibrary();
            VulkanXServerView vk = new VulkanXServerView(activity, xServer);
            // The driver the renderer loads into the app: session/RendererDriver.
            RendererDriver.apply(activity, vk, container, shortcut, driverVersion);
            String presentMode = shortcut != null ? shortcut.getRendererPresentMode()
                    : (container != null ? container.getRendererPresentMode() : "fifo");
            vk.setVkPresentMode(RendererOptionsDialog.toVkPresentMode(presentMode));
            vk.setFilterMode(filter);
            renderer = vk;
        }
        renderer.setCursorVisible(false);
        // A game's own shortcut: the desktop (explorer) is never the picture.
        if (shortcut != null) renderer.setUnviewableWMClasses("explorer.exe");

        // "Swap red/blue channels" or "Color order: BGRA" (surface format): both swap the channels on
        // Vulkan / Native; DisplayX takes the surface format itself.
        boolean swapRB = container != null && ((shortcut != null ? shortcut.getRendererSwapRB() : container.getRendererSwapRB())
                || (!useDisplayX && "bgra8".equals(surfaceFormat(container, shortcut))));
        if (renderer instanceof VulkanXServerView) ((VulkanXServerView) renderer).setSwapRB(swapRB);
        else if (renderer instanceof EGLXServerView) ((EGLXServerView) renderer).setSwapRB(swapRB);
        return renderer;
    }
}
