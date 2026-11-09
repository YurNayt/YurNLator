package com.winlator.cmod.ui.ingame;

import android.content.SharedPreferences;
import android.os.Handler;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.widget.InputControlsView;
import com.winlator.cmod.widget.TouchpadView;
import com.winlator.cmod.winhandler.WinHandler;

import java.util.function.Supplier;

/**
 * YurNLator base, part 10: the on-screen controls of a game session: showing a controls profile,
 * hiding it (the touchpad back to plain mouse), and hiding the controls after 5 s without a touch
 * (Settings › touchscreen timeout; off in this build, the controls stay visible).
 */
public final class TouchControls {
    /** The timeout is off: the controls stay visible (they used to vanish mid-game). */
    public static final boolean DISABLE_AUTO_HIDE = true;
    private static final String TAG = "TouchControls";

    private final SharedPreferences prefs;
    private final Handler handler;
    private final Supplier<InputControlsView> controls;
    private final Supplier<TouchpadView> touchpad;
    private final Supplier<WinHandler> winHandler;
    private final Supplier<Float> cursorSpeed;
    private final Runnable hideRunnable;

    public TouchControls(SharedPreferences prefs, Handler handler, Supplier<InputControlsView> controls,
                         Supplier<TouchpadView> touchpad, Supplier<WinHandler> winHandler, Supplier<Float> cursorSpeed) {
        this.prefs = prefs;
        this.handler = handler;
        this.controls = controls;
        this.touchpad = touchpad;
        this.winHandler = winHandler;
        this.cursorSpeed = cursorSpeed;
        this.hideRunnable = () -> {
            if (DISABLE_AUTO_HIDE) return;
            InputControlsView c = controls.get();
            if (prefs.getBoolean("touchscreen_timeout_enabled", false) && c != null && c.getProfile() != null) {
                c.setVisibility(View.GONE);
                Log.d(TAG, "Touchscreen controls hidden after timeout.");
            }
        };
    }

    /** What the views run when they want the controls hidden (after a timeout). */
    public Runnable hideRunnable() { return hideRunnable; }

    private void showIfProfile(InputControlsView c) {
        if (c != null && c.getProfile() != null) c.setVisibility(View.VISIBLE);
    }

    /** Starts (or stops) hiding the controls after 5 s without a touch, from the setting. */
    public void startTimeout() {
        handler.removeCallbacks(hideRunnable);
        InputControlsView c = controls.get();
        TouchpadView t = touchpad.get();

        if (DISABLE_AUTO_HIDE) {
            if (t != null) t.setOnTouchListener(null);
            showIfProfile(c);
            return;
        }

        showIfProfile(c);
        if (t == null) return;
        if (prefs.getBoolean("touchscreen_timeout_enabled", false)) {
            t.setOnTouchListener((v, event) -> {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                    showIfProfile(controls.get());
                    handler.removeCallbacks(hideRunnable);
                    handler.postDelayed(hideRunnable, 5000);
                }
                return false;
            });
            handler.postDelayed(hideRunnable, 5000);
        } else {
            t.setOnTouchListener(null);
        }
    }

    public void show(ControlsProfile profile) {
        handler.removeCallbacks(hideRunnable);
        InputControlsView c = controls.get();
        TouchpadView t = touchpad.get();
        c.setProfile(profile);
        c.setVisibility(View.VISIBLE);
        c.requestFocus();
        t.setSensitivity(profile.getCursorSpeed() * cursorSpeed.get());
        t.setPointerButtonRightEnabled(false);
        c.invalidate();
        WinHandler w = winHandler.get();
        if (w != null) w.sendGamepadState();
        // Auto-hide on: start it with the profile (a game's own profile is shown after the saved
        // state is restored, and auto-hide only began after toggling it in the menu).
        if (prefs.getBoolean("touchscreen_timeout_enabled", false)) startTimeout();
    }

    public void hide() {
        handler.removeCallbacks(hideRunnable);
        InputControlsView c = controls.get();
        TouchpadView t = touchpad.get();
        c.setShowTouchscreenControls(true);
        c.setVisibility(View.GONE);
        c.setProfile(null);
        t.setSensitivity(cursorSpeed.get());
        t.setPointerButtonLeftEnabled(true);
        t.setPointerButtonRightEnabled(true);
        c.invalidate();
        WinHandler w = winHandler.get();
        if (w != null) w.sendGamepadState();
    }

    /**
     * At the session start: the controls as last confirmed (shown, timeout, profile index from
     * the preferences), without the old in-game dialog that used to be confirmed for this.
     */
    public void restoreSaved(java.util.List<ControlsProfile> profiles) {
        InputControlsView c = controls.get();
        TouchpadView t = touchpad.get();
        if (c == null || t == null) return;
        c.setShowTouchscreenControls(prefs.getBoolean("show_touchscreen_controls_enabled", false));
        int index = prefs.getInt("selected_profile_index", -1);
        if (index >= 0 && index < profiles.size()) show(profiles.get(index));
        else hide();
        if (prefs.getBoolean("touchscreen_timeout_enabled", false) && c.getVisibility() == View.VISIBLE) startTimeout();
        else t.setOnTouchListener(null);
    }
}
