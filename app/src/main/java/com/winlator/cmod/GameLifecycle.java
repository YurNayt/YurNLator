package com.winlator.cmod;

import android.app.PictureInPictureParams;
import android.graphics.Rect;
import android.os.Build;
import android.util.Rational;
import android.view.View;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.view.GravityCompat;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.core.ProcessHelper;

/**
 * YurNLator base, part 14: the game screen's life: pause and resume (Wine's processes stop while
 * the screen is away, not in picture-in-picture), picture-in-picture, the back button (the menu),
 * pointer capture with the window's focus, exit and the final clean-up. Moved out of the game screen.
 */
final class GameLifecycle {
    private final XServerDisplayActivity a;

    GameLifecycle(XServerDisplayActivity activity) { this.a = activity; }

    void resume() {
        if (a.environment != null) {
            a.xServerView.onResume();
            a.environment.onResume();
        }
        if (a.playtime != null) a.playtime.resume();
        if (a.gyroInput != null) a.gyroInput.apply();
        if (!a.isInPictureInPictureMode()) ProcessHelper.resumeAllWineProcesses();
    }

    /** Before the system pause: what must stop while the screen is still ours. */
    void beforePause() {
        if (a.taskManagerSidebar != null) a.taskManagerSidebar.stop();
        if (a.gyroInput != null) a.gyroInput.stop();
        a.quickMenuBridge.stopTasks();
    }

    /** After the system pause: Wine sleeps unless the game stays visible in picture-in-picture. */
    void afterPause() {
        if (!a.isInPictureInPictureMode()) {
            if (a.environment != null) {
                a.environment.onPause();
                a.xServerView.onPause();
            }
            ProcessHelper.pauseAllWineProcesses();
        }
        if (a.playtime != null) a.playtime.pause();
    }

    void enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        int w = a.xServer.screenInfo.width;
        int h = a.xServer.screenInfo.height;
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(w, h));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && a.xServerView != null) {
            int[] loc = new int[2];
            a.xServerView.getLocationOnScreen(loc);
            builder.setSourceRectHint(new Rect(loc[0], loc[1],
                    loc[0] + a.xServerView.getWidth(), loc[1] + a.xServerView.getHeight()));
        }
        a.enterPictureInPictureMode(builder.build());
    }

    void pipChanged(boolean inPip) {
        if (a.xServerView == null) return;
        a.xServerView.setPipMode(inPip);
        if (inPip) return;
        a.xServerView.post(() -> {
            if (a.xServerView == null) return;
            int w = a.xServerView.getWidth();
            int h = a.xServerView.getHeight();
            if (w > 0 && h > 0) a.xServerView.onSurfaceChanged(w, h);
        });
        // With the touch controls on, the SurfaceView's surface sometimes stays where the PiP window
        // animation left it: the picture shows shifted right and cut, the HUD and the controls
        // (normal views) are fine. Hiding and showing the view makes Android place the surface again
        // (the renderer re-attaches it as after a pause).
        a.xServerView.postDelayed(() -> {
            if (a.xServerView == null || a.isInPictureInPictureMode()) return;
            a.xServerView.setVisibility(View.INVISIBLE);
            a.xServerView.post(() -> {
                if (a.xServerView == null) return;
                a.xServerView.setVisibility(View.VISIBLE);
                a.xServerView.requestLayout();
            });
        }, 400);
    }

    /** Back opens / closes the menu while a game runs. */
    void back() {
        if (a.environment == null) return;
        if (!a.drawerLayout.isDrawerOpen(GravityCompat.START)) a.drawerLayout.openDrawer(GravityCompat.START);
        else a.drawerLayout.closeDrawers();
    }

    /** "Capture external pointer": the mouse is captured while the game has the focus. */
    void focusChanged(boolean hasFocus) {
        if (a.touchpadView == null) return;
        if (hasFocus && a.cursorLock) a.touchpadView.requestPointerCapture();
        else if (!hasFocus) a.touchpadView.releasePointerCapture();
    }

    /** Leaves the game: servers stopped, Wine ended, then the screen closes. */
    void exit() {
        com.winlator.cmod.core.RendererDriverGuard.disarm(a);
        NotificationManagerCompat.from(a).cancel(XServerDisplayActivity.NOTIFICATION_ID);
        boolean removeLoadingBar = PreferenceManager.getDefaultSharedPreferences(a)
                .getBoolean("remove_loading_bar_when_booting_games", false);
        if (!removeLoadingBar) a.preloaderDialog.showOnUiThread(R.string.shutdown);

        if (a.xServerView != null) {
            a.xServerView.forceCleanup();
            a.xServerView.setVisibility(View.GONE);
        }
        if (a.playtime != null) a.playtime.pause();
        if (a.midiHandler != null) a.midiHandler.stop();
        if (a.environment != null) a.environment.stopEnvironmentComponents();
        if (a.winHandler != null) a.winHandler.stop();
        if (a.wineRequestHandler != null) a.wineRequestHandler.stop();

        com.winlator.cmod.session.WineShutdown.terminateAll(() -> {
            a.preloaderDialog.closeOnUiThread();
            a.runOnUiThread(a::finish);
        });
    }

    /** The screen is destroyed: sensors, performance changes, Wayland and the task manager stop. */
    void destroy() {
        com.winlator.cmod.core.RendererDriverGuard.disarm(a);
        com.winlator.cmod.core.HudSensorFeed.INSTANCE.stop();
        if (a.perf != null) a.perf.stop();
        if (a.waylandSession != null) { a.waylandSession.stop(); a.waylandSession = null; }
        if (a.taskManagerSidebar != null) a.taskManagerSidebar.stop();
    }
}
