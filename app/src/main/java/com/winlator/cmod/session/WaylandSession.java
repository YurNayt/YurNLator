package com.winlator.cmod.session;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.Log;
import android.view.Choreographer;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.wayland.WaylandClipboardSync;
import com.winlator.cmod.wayland.WaylandCompositor;
import com.winlator.cmod.wayland.WaylandTextInput;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.XServer;

import java.io.File;

/**
 * YurNLator base: the Wayland display backend of a game session (Bannerlator's embedded compositor,
 * GPL-3.0). The game draws through Wine's winewayland.drv into the compositor, which presents on its
 * own SurfaceView above the X server's view.
 *
 * Input keeps its usual path: touchpad, on-screen controls, keyboard and mouse still inject into the
 * X server, whose {@link XServer.InputSink} hands everything to the compositor (absolute moves,
 * relative deltas, buttons, keys). The X server itself has no client in this mode.
 */
public final class WaylandSession {
    private static final String TAG = "WaylandSession";

    public interface Listener {
        /** First presented frame of the game (dismiss the loading screen). */
        void onFirstFrame();
        /** Every frame of the game's window (HUD FPS). */
        void onGameFrame();
        /** The game's surface appeared (gpuName from the compositor's device) or went away (null). */
        void onGameSurface(String window, String gpuName);
    }

    private final Activity activity;
    private final XServer xServer;
    private WaylandTextInput.SurfaceInputView surfaceView;
    private ImageView cursorView;
    private WaylandClipboardSync clipboard;
    private WaylandTextInput textInput;
    private volatile boolean pointerLocked = false;
    /** Picture fills the screen (aspect ignored) instead of letterboxed. */
    private volatile boolean stretch = false;
    /** The guest's own cursor (wl_pointer.set_cursor), polled while the session runs. */
    private final int[] cursorBuf = new int[WaylandCompositor.CURSOR_BUF_INTS];
    private int cursorSerial = -1;
    private boolean guestHidesCursor = false;
    private int hotX = 0, hotY = 0;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable cursorPoll = new Runnable() {
        @Override public void run() {
            if (!vsyncRunning) return;
            syncGuestCursor();
            ui.postDelayed(this, 150);
        }
    };
    private boolean vsyncRunning = false;

    private final Choreographer.FrameCallback vsync = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            if (!vsyncRunning) return;
            WaylandCompositor.nativeVsync(frameTimeNanos);
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public WaylandSession(Activity activity, XServer xServer) {
        this.activity = activity;
        this.xServer = xServer;
    }

    /** The compositor's socket directory (also the guest's XDG_RUNTIME_DIR). Never cleared. */
    public static File runtimeDir(Activity activity) {
        File dir = new File(activity.getFilesDir(), ".wayland-rt");
        dir.mkdirs();
        return dir;
    }

    /**
     * Starts the compositor on a new SurfaceView put right above the X server's view (index
     * [aboveIndex]), so touch overlays, HUD and on-screen controls stay on top.
     */
    public void start(FrameLayout rootView, int aboveIndex, String driverPath, String libraryName, boolean stretch,
                      boolean hideShell, Listener listener) {
        this.stretch = stretch;
        // Game shortcuts: don't draw explorer's desktop / taskbar (the X11 view hides them the same way).
        try { WaylandCompositor.nativeSetHideShell(hideShell); } catch (Throwable t) { Log.w(TAG, "hide shell", t); }
        File rt = runtimeDir(activity);
        // The guest needs the keymap to read our evdev codes.
        try { FileUtils.copy(activity, "wayland/keymap.xkb", new File(rt, "keymap.xkb")); }
        catch (Exception e) { Log.e(TAG, "keymap extract failed", e); }

        WaylandCompositor.setFirstFrameListener(() -> activity.runOnUiThread(listener::onFirstFrame));
        WaylandCompositor.setGameListener(new WaylandCompositor.GameListener() {
            @Override public void onGameSurface(String window, String gpuName) { listener.onGameSurface(window, gpuName); }
            @Override public void onGameFrame() { listener.onGameFrame(); }
            @Override public void onGameProgram(int pid, String program) {}
        });
        WaylandCompositor.setPointerLockListener((locked, x, y) -> activity.runOnUiThread(() -> {
            pointerLocked = locked;
            if (cursorView != null) cursorView.setVisibility(locked ? View.GONE : View.VISIBLE);
            if (!locked) xServer.injectPointerMove(x, y);
        }));

        int sw = xServer.screenInfo.width, sh = xServer.screenInfo.height;
        try {
            WaylandCompositor.nativeSetOutputSize(sw, sh);
            float hz = activity.getWindowManager().getDefaultDisplay().getRefreshRate();
            if (hz > 0) WaylandCompositor.nativeSetOutputRefreshRate(hz);
        } catch (Throwable t) {
            Log.w(TAG, "output setup", t);
        }

        xServer.setInputSink(new XServer.InputSink() {
            @Override public void onPointerMove(int x, int y) {
                if (!pointerLocked) WaylandCompositor.nativeSendSceneInput(2, x, y);
                activity.runOnUiThread(() -> placeCursor(x, y));
            }
            @Override public void onPointerDelta(int dx, int dy) {
                WaylandCompositor.sendPointerDelta(dx, dy);
                // Relative mouse without a pointer lock: the compositor moved its pointer by the
                // delta, the overlay follows the X pointer that moved the same way.
                if (!pointerLocked) activity.runOnUiThread(() -> placeCursor(xServer.pointer.getX(), xServer.pointer.getY()));
            }
            @Override public void onPointerButton(Pointer.Button button, boolean pressed) {
                switch (button) {
                    case BUTTON_LEFT: WaylandCompositor.nativeSendSceneInput(3, 0x110, pressed ? 1 : 0); break;
                    case BUTTON_RIGHT: WaylandCompositor.nativeSendSceneInput(3, 0x111, pressed ? 1 : 0); break;
                    case BUTTON_MIDDLE: WaylandCompositor.nativeSendSceneInput(3, 0x112, pressed ? 1 : 0); break;
                    case BUTTON_SCROLL_UP: if (pressed) WaylandCompositor.nativeSendSceneInput(4, -1, 0); break;
                    case BUTTON_SCROLL_DOWN: if (pressed) WaylandCompositor.nativeSendSceneInput(4, 1, 0); break;
                    default: break;
                }
            }
            @Override public void onKey(int evdev, boolean pressed) {
                if (evdev > 0) WaylandCompositor.nativeSendKey(evdev, pressed ? 1 : 0);
            }
        });

        surfaceView = new WaylandTextInput.SurfaceInputView(activity);
        surfaceView.setZOrderMediaOverlay(true); // above the X server's SurfaceView, below normal views
        surfaceView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        final String nativeLibDir = activity.getApplicationInfo().nativeLibraryDir;
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            boolean started = false;
            @Override public void surfaceCreated(SurfaceHolder h) {
                if (!started) {
                    started = true;
                    WaylandCompositor.nativeStartWithSurface(h.getSurface(), rt.getPath(), driverPath, libraryName, nativeLibDir);
                    vsyncRunning = true;
                    Choreographer.getInstance().postFrameCallback(vsync);
                    ui.post(cursorPoll);
                    setStretch(stretch);
                    Log.i(TAG, "compositor started (driver " + (libraryName != null ? libraryName : "system") + ")");
                } else {
                    WaylandCompositor.nativeSetSurface(h.getSurface());
                }
            }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) {}
            @Override public void surfaceDestroyed(SurfaceHolder h) { WaylandCompositor.nativeSetSurface(null); }
        });

        cursorView = new ImageView(activity);
        cursorView.setImageBitmap(arrowCursor());
        cursorView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        cursorView.setVisibility(View.GONE);

        rootView.addView(surfaceView, Math.min(aboveIndex, rootView.getChildCount()));
        rootView.addView(cursorView, Math.min(aboveIndex + 1, rootView.getChildCount()));
        // Touches go to the views above (touchpad, controls); the surface only displays.
        surfaceView.setFocusable(false);
        surfaceView.setClickable(false);

        clipboard = new WaylandClipboardSync(activity);
        clipboard.start();
        textInput = new WaylandTextInput(activity, surfaceView);
        textInput.start();
    }

    /** Letterboxed (fit) or filling the screen (stretch); also live from the quick menu. */
    public void setStretch(boolean on) {
        stretch = on;
        try { WaylandCompositor.nativeSetScaleMode(on ? 2 /* STRETCH */ : 1 /* FIT */, 0 /* center */); }
        catch (Throwable t) { Log.w(TAG, "scale mode", t); }
    }

    /** Scene pixel -> view pixel for the overlay arrow, the same mapping the compositor draws with. */
    private void placeCursor(int x, int y) {
        if (surfaceView == null || cursorView == null || pointerLocked) return;
        int vw = surfaceView.getWidth(), vh = surfaceView.getHeight();
        int sw = xServer.screenInfo.width, sh = xServer.screenInfo.height;
        if (vw <= 0 || vh <= 0 || sw <= 0 || sh <= 0) return;
        float px, py;
        if (stretch) {
            px = x * (float) vw / sw;
            py = y * (float) vh / sh;
        } else {
            float scale = Math.min((float) vw / sw, (float) vh / sh);
            px = (vw - sw * scale) / 2f + x * scale;
            py = (vh - sh * scale) / 2f + y * scale;
        }
        cursorView.setX(px - hotX);
        cursorView.setY(py - hotY);
        int want = guestHidesCursor ? View.GONE : View.VISIBLE;
        if (cursorView.getVisibility() != want) cursorView.setVisibility(want);
    }

    /** The guest's cursor image, and hidden when the game hides it (mouse-look). Main thread. */
    private void syncGuestCursor() {
        if (cursorView == null) return;
        int n;
        try { n = WaylandCompositor.cursorSnapshot(cursorBuf); } catch (Throwable t) { return; }
        if (n < 6) return;
        int serial = cursorBuf[0];
        if (serial == 0 || serial == cursorSerial) return;
        cursorSerial = serial;
        guestHidesCursor = cursorBuf[1] != 0;
        if (guestHidesCursor) { cursorView.setVisibility(View.GONE); return; }
        int w = cursorBuf[2], h = cursorBuf[3];
        if (w <= 0 || h <= 0 || n < 6 + w * h) return;
        hotX = cursorBuf[4];
        hotY = cursorBuf[5];
        try { cursorView.setImageBitmap(Bitmap.createBitmap(cursorBuf, 6, w, w, h, Bitmap.Config.ARGB_8888)); }
        catch (Exception e) { Log.e(TAG, "cursor bitmap", e); }
    }

    public void stop() {
        vsyncRunning = false;
        ui.removeCallbacks(cursorPoll);
        xServer.setInputSink(null);
        WaylandCompositor.setFirstFrameListener(null);
        WaylandCompositor.setGameListener(null);
        WaylandCompositor.setPointerLockListener(null);
    }

    private static Bitmap arrowCursor() {
        int w = 22, h = 34;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Path p = new Path();
        p.moveTo(1, 1); p.lineTo(1, 25); p.lineTo(7, 19); p.lineTo(11, 28);
        p.lineTo(15, 26); p.lineTo(11, 17); p.lineTo(19, 17); p.close();
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        cv.drawPath(p, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f);
        paint.setColor(0xFF202020);
        cv.drawPath(p, paint);
        return bmp;
    }
}
