package com.winlator.cmod.inputcontrols;

import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ExternalControllerBinding {
    public static final byte AXIS_X_NEGATIVE = -1;
    public static final byte AXIS_X_POSITIVE = -2;
    public static final byte AXIS_Y_NEGATIVE = -3;
    public static final byte AXIS_Y_POSITIVE = -4;
    public static final byte AXIS_Z_NEGATIVE = -5;
    public static final byte AXIS_Z_POSITIVE = -6;
    public static final byte AXIS_RZ_NEGATIVE = -7;
    public static final byte AXIS_RZ_POSITIVE = -8;
    private short keyCode;
    private Binding binding = Binding.NONE;
    /** A chord: this binding fires when [keyCode] is pressed while this button is held (0 = none). */
    private short modifier;

    public int getModifier() {
        return modifier;
    }

    public void setModifier(int modifier) {
        this.modifier = (short) modifier;
    }
    /** More keys pressed together with [binding] (a key combination such as Ctrl + S); up to [MAX_EXTRAS]. */
    private final ArrayList<Binding> extras = new ArrayList<>();
    public static final int MAX_EXTRAS = 3;

    public List<Binding> getExtras() {
        return extras;
    }

    public void addExtra(Binding b) {
        if (b == null || b == Binding.NONE || b == binding || extras.contains(b) || extras.size() >= MAX_EXTRAS) return;
        extras.add(b);
    }

    public void clearExtras() {
        extras.clear();
    }

    private static boolean isModifier(Binding b) {
        switch (b) {
            case KEY_CTRL_L: case KEY_CTRL_R: case KEY_SHIFT_L: case KEY_SHIFT_R: case KEY_ALT_L: case KEY_ALT_R:
                return true;
            default:
                return false;
        }
    }

    /** Everything this button presses, Ctrl / Shift / Alt first (released in the reverse order). */
    public Binding[] pressOrder() {
        ArrayList<Binding> all = new ArrayList<>();
        all.add(binding);
        all.addAll(extras);
        ArrayList<Binding> out = new ArrayList<>();
        for (Binding b : all) if (isModifier(b)) out.add(b);
        for (Binding b : all) if (!isModifier(b) && b != Binding.NONE) out.add(b);
        return out.toArray(new Binding[0]);
    }

    /** "Ctrl L + S" for a combination, empty for a single key. */
    public String comboLabel() {
        if (extras.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Binding b : pressOrder()) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(b.toString());
        }
        return sb.toString();
    }

    public int getKeyCode() {
        return keyCode;
    }

    public void setKeyCode(int keyCode) {
        this.keyCode = (short)keyCode;
    }

    public Binding getBinding() {
        return binding;
    }

    public void setBinding(Binding binding) {
        this.binding = binding;
    }

    public JSONObject toJSONObject() {
        try {
            JSONObject controllerBindingJSONObject = new JSONObject();
            controllerBindingJSONObject.put("keyCode", keyCode);
            controllerBindingJSONObject.put("binding", binding.name());
            if (modifier != 0) controllerBindingJSONObject.put("modifier", (int) modifier);
            if (!extras.isEmpty()) {
                JSONArray a = new JSONArray();
                for (Binding b : extras) a.put(b.name());
                controllerBindingJSONObject.put("extras", a);
            }
            return controllerBindingJSONObject;
        }
        catch (JSONException e) {
            return null;
        }
    }

    @NonNull
    @Override
    public String toString() {
        String own = keyLabel(keyCode);
        return modifier != 0 ? keyLabel(modifier) + " + " + own : own;
    }

    public static String keyLabel(int keyCode) {
        switch (keyCode) {
            case AXIS_X_NEGATIVE:
                return "AXIS X-";
            case AXIS_X_POSITIVE:
                return "AXIS X+";
            case AXIS_Y_NEGATIVE:
                return "AXIS Y-";
            case AXIS_Y_POSITIVE:
                return "AXIS Y+";
            case AXIS_Z_NEGATIVE:
                return "AXIS Z-";
            case AXIS_Z_POSITIVE:
                return "AXIS Z+";
            case AXIS_RZ_NEGATIVE:
                return "AXIS RZ-";
            case AXIS_RZ_POSITIVE:
                return "AXIS RZ+";
            default:
                return KeyEvent.keyCodeToString(keyCode).replace("KEYCODE_", "").replace("_", " ");
        }
    }


    public static int getKeyCodeForAxis(int axis, byte sign) {
        switch (axis) {
            case MotionEvent.AXIS_X:
                return sign > 0 ? AXIS_X_POSITIVE : AXIS_X_NEGATIVE;
            case MotionEvent.AXIS_Y:
                return sign > 0 ? AXIS_Y_POSITIVE : AXIS_Y_NEGATIVE;
            case MotionEvent.AXIS_Z:
                return sign > 0 ? AXIS_Z_POSITIVE : AXIS_Z_NEGATIVE;
            case MotionEvent.AXIS_RZ:
                return sign > 0 ? AXIS_RZ_POSITIVE : AXIS_RZ_NEGATIVE;
            case MotionEvent.AXIS_HAT_X:
                return sign > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
            case MotionEvent.AXIS_HAT_Y:
                return sign > 0 ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP;
            default:
                return KeyEvent.KEYCODE_UNKNOWN;
        }
    }

}
