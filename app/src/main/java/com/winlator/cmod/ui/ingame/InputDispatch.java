package com.winlator.cmod.ui.ingame;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.winlator.cmod.R;
import com.winlator.cmod.inputcontrols.ExternalController;
import com.winlator.cmod.widget.InputControlsView;
import com.winlator.cmod.widget.TouchpadView;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.xserver.XServer;

/**
 * Where the game screen's keys and generic motion (gamepads, mice) go: the on-screen controls,
 * the Windows-side handler (gamepads), the touchpad (an external mouse) and the X server's
 * keyboard; the vibration slots dialog. Moved out of the game screen (base migration).
 */
public final class InputDispatch {
    private InputDispatch() {}

    /** Generic motion for the game; the caller ORs the result with its own dispatch. */
    public static boolean motion(MotionEvent event, WinHandler winHandler, TouchpadView touchpad) {
        boolean byWinHandler = winHandler != null && winHandler.onGenericMotionEvent(event);
        boolean byTouchpad = touchpad != null && touchpad.onExternalMouseEvent(event);
        return byWinHandler || byTouchpad;
    }

    /**
     * A key: TRUE / FALSE = the answer, null = let the activity's own dispatch have it.
     * With the quick menu / sidebar open its text fields (custom FPS...) get the keys first, except
     * BACK, MODE, SELECT and gamepads; MODE / HOME / SELECT presses always stay in the game.
     */
    public static Boolean key(KeyEvent event, DrawerLayout drawer, InputControlsView controls, WinHandler winHandler, XServer xServer) {
        int code = event.getKeyCode();
        boolean gamepad = ExternalController.isGameController(event.getDevice());
        if (drawer != null && drawer.isDrawerOpen(GravityCompat.START) && code != KeyEvent.KEYCODE_BACK
                && code != KeyEvent.KEYCODE_BUTTON_MODE && code != KeyEvent.KEYCODE_BUTTON_SELECT && !gamepad)
            return null;
        if (event.getAction() == KeyEvent.ACTION_DOWN && (code == KeyEvent.KEYCODE_BUTTON_MODE
                || code == KeyEvent.KEYCODE_HOME || code == KeyEvent.KEYCODE_BUTTON_SELECT)) {
            if (!controls.onKeyEvent(event) && winHandler != null && winHandler.onKeyEvent(event) && xServer != null)
                xServer.keyboard.onKeyEvent(event);
            return Boolean.TRUE;
        }
        if (!controls.onKeyEvent(event) && !winHandler.onKeyEvent(event) && xServer.keyboard.onKeyEvent(event)) return Boolean.TRUE;
        return gamepad ? Boolean.FALSE : null;
    }

    /** Which gamepad slots vibrate. */
    public static void showVibrationDialog(Activity activity, WinHandler winHandler) {
        if (winHandler == null) return;
        int max = winHandler.getMaxControllers();
        boolean[] checked = new boolean[max];
        String[] items = new String[max];
        for (int i = 0; i < max; i++) {
            items[i] = activity.getString(R.string.vibration_slot, i + 1);
            checked[i] = winHandler.isVibrationEnabledForSlot(i);
        }
        new androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle(R.string.vibration)
                .setMultiChoiceItems(items, checked, (d, which, on) -> winHandler.setVibrationEnabledForSlot(which, on))
                .setPositiveButton(R.string.ok, null)
                .show();
    }
}
