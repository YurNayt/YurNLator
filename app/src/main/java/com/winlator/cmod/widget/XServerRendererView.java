package com.winlator.cmod.widget;

import android.content.Context;
import android.view.SurfaceView;

import com.winlator.cmod.renderer.GPUImage;
import com.winlator.cmod.renderer.ViewTransformation;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XServer;

public abstract class XServerRendererView extends SurfaceView {
    /** Called for every frame a window presents directly (the HUD uses it to find the game window). */
    protected volatile java.util.function.Consumer<com.winlator.cmod.xserver.Window> directFrameListener;

    public void setDirectFrameListener(java.util.function.Consumer<com.winlator.cmod.xserver.Window> listener) {
        directFrameListener = listener;
    }

    protected void notifyDirectFrame(com.winlator.cmod.xserver.Window window) {
        java.util.function.Consumer<com.winlator.cmod.xserver.Window> l = directFrameListener;
        if (l != null) l.accept(window);
    }


    public final ViewTransformation viewTransformation = new ViewTransformation();
    protected final XServer xServer;

    protected XServerRendererView(Context context, XServer xServer) {
        super(context);
        this.xServer = xServer;
    }

    public abstract void onPause();
    public abstract void onResume();
    public abstract void onDestroy();

    public abstract void toggleFullscreen();
    public abstract boolean isFullscreen();

    public abstract void setCursorVisible(boolean visible);
    public abstract void setScreenOffsetYRelativeToCursor(boolean relative);
    public abstract void setMagnifierZoom(float zoom);
    public abstract float getMagnifierZoom();
    public abstract void setUnviewableWMClasses(String... classes);

    public abstract void nativeAddDirectContent(int windowId, Drawable drawable, GPUImage gpuImage);
    public abstract void nativeRemoveDirectContent(int windowId, int pixmapId);

    public abstract void onUpdateWindowContentDirect(Window window, Drawable drawable);
    public abstract void onUpdateWindowContentDirect(Window window, Drawable drawable, short xOff, short yOff);
    public abstract void requestRender();
    public abstract void queueEvent(Runnable action);
    public abstract void onSurfaceChanged(int width, int height);
    public abstract void forceCleanup();
    public abstract void setFpsWindowId(int windowId);
    public abstract void setPipMode(boolean pipMode);
    public abstract void setFrameRating(Object frameRating);

    public abstract void setFpsLimit(int fps);
    public abstract int getFpsLimit();
}
