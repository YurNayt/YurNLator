package com.winlator.cmod.session;

import android.view.MotionEvent;

import com.winlator.cmod.math.XForm;
import com.winlator.cmod.winhandler.MouseEventFlags;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.XServer;

/**
 * YurNLator base, part 7: a captured (physical) mouse. Buttons, movement and the wheel go to the
 * game either as relative input through winhandler (games that read raw mouse deltas) or as X
 * pointer events.
 */
public final class CapturedMouse {
    private CapturedMouse() {}

    public static void handle(MotionEvent event, XServer xServer, WinHandler winHandler, float[] xform) {
        if (xServer == null) return;
        boolean relative = xServer.isRelativeMouseMovement() && winHandler != null;
        switch (event.getAction()) {
            case MotionEvent.ACTION_BUTTON_PRESS:
            case MotionEvent.ACTION_BUTTON_RELEASE: {
                boolean press = event.getAction() == MotionEvent.ACTION_BUTTON_PRESS;
                int b = event.getActionButton();
                Pointer.Button xButton;
                int down, up;
                if (b == MotionEvent.BUTTON_PRIMARY) {
                    xButton = Pointer.Button.BUTTON_LEFT; down = MouseEventFlags.LEFTDOWN; up = MouseEventFlags.LEFTUP;
                } else if (b == MotionEvent.BUTTON_SECONDARY) {
                    xButton = Pointer.Button.BUTTON_RIGHT; down = MouseEventFlags.RIGHTDOWN; up = MouseEventFlags.RIGHTUP;
                } else if (b == MotionEvent.BUTTON_TERTIARY) {
                    xButton = Pointer.Button.BUTTON_MIDDLE; down = MouseEventFlags.MIDDLEDOWN; up = MouseEventFlags.MIDDLEUP;
                } else return;
                if (relative) winHandler.mouseEvent(press ? down : up, 0, 0, 0);
                else if (press) xServer.injectPointerButtonPress(xButton);
                else xServer.injectPointerButtonRelease(xButton);
                break;
            }
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_HOVER_MOVE: {
                float[] p = XForm.transformPoint(xform, event.getX(), event.getY());
                int dx = (int) p[0], dy = (int) p[1];
                if (relative) winHandler.mouseEvent(MouseEventFlags.MOVE, dx, dy, 0);
                else xServer.injectPointerMoveDelta(dx, dy);
                break;
            }
            case MotionEvent.ACTION_SCROLL: {
                float scrollY = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (scrollY > -1.0f && scrollY < 1.0f) return;
                if (relative) {
                    winHandler.mouseEvent(MouseEventFlags.WHEEL, 0, 0, (int) scrollY * 270);
                } else {
                    Pointer.Button wheel = scrollY <= -1.0f ? Pointer.Button.BUTTON_SCROLL_DOWN : Pointer.Button.BUTTON_SCROLL_UP;
                    xServer.injectPointerButtonPress(wheel);
                    xServer.injectPointerButtonRelease(wheel);
                }
                break;
            }
        }
    }
}
