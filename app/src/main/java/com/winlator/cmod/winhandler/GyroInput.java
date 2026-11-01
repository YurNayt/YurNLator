package com.winlator.cmod.winhandler;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Surface;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.inputcontrols.GamepadState;

/**
 * Gyroscope aiming (YurNLator): turning the phone moves the mouse or a gamepad stick.
 *
 *  target      mouse | right stick | left stick
 *  activation  always | while a button is held | toggled by a button press
 *  button      gamepad button index (ExternalController.IDX_*), read from a real controller or
 *              the on-screen virtual gamepad
 *  fast        SENSOR_DELAY_FASTEST (higher polling rate) instead of SENSOR_DELAY_GAME
 *
 * Settings are global (preferences "gyro_*"), changed from the in-game quick menu.
 */
public class GyroInput implements SensorEventListener {
    public static final int TARGET_MOUSE = 0;
    public static final int TARGET_RIGHT_STICK = 1;
    public static final int TARGET_LEFT_STICK = 2;
    public static final int ACTIVATION_ALWAYS = 0;
    public static final int ACTIVATION_HOLD = 1;
    public static final int ACTIVATION_TOGGLE = 2;

    public interface Output {
        /** Relative mouse movement in screen pixels. */
        void moveMouse(int dx, int dy);
        /** The stick values changed: send the gamepad state again. */
        void stickChanged();
        /** Whether gamepad button [index] is pressed right now (real or on-screen gamepad). */
        boolean isButtonPressed(int index);
    }

    private final Activity activity;
    private final SensorManager sensorManager;
    private final Sensor gyroscope;
    private final Output output;
    private boolean registered = false;

    public boolean enabled;
    public int target;
    public float sensitivity;
    public boolean invertX;
    public boolean invertY;
    public boolean fast;
    public int activation;
    public int button;

    private boolean toggledOn = false;
    private boolean lastButton = false;
    private long lastTimestamp = 0;
    private float remainderX = 0, remainderY = 0;
    private volatile float stickX = 0, stickY = 0;

    public GyroInput(Activity activity, Output output) {
        this.activity = activity;
        this.output = output;
        sensorManager = (SensorManager) activity.getSystemService(Context.SENSOR_SERVICE);
        gyroscope = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) : null;
        load();
    }

    public boolean isAvailable() {
        return gyroscope != null;
    }

    public void load() {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(activity);
        enabled = p.getBoolean("gyro_enabled", false);
        target = p.getInt("gyro_target", TARGET_MOUSE);
        sensitivity = p.getFloat("gyro_sensitivity", 1.0f);
        invertX = p.getBoolean("gyro_invert_x", false);
        invertY = p.getBoolean("gyro_invert_y", false);
        fast = p.getBoolean("gyro_fast", false);
        activation = p.getInt("gyro_activation", ACTIVATION_ALWAYS);
        button = p.getInt("gyro_button", 4);
    }

    public void save() {
        PreferenceManager.getDefaultSharedPreferences(activity).edit()
                .putBoolean("gyro_enabled", enabled)
                .putInt("gyro_target", target)
                .putFloat("gyro_sensitivity", sensitivity)
                .putBoolean("gyro_invert_x", invertX)
                .putBoolean("gyro_invert_y", invertY)
                .putBoolean("gyro_fast", fast)
                .putInt("gyro_activation", activation)
                .putInt("gyro_button", button)
                .apply();
    }

    /** Starts or stops listening to match the settings (call after a change and in onResume). */
    public void apply() {
        stop();
        toggledOn = false;
        if (enabled && gyroscope != null) {
            registered = sensorManager.registerListener(this, gyroscope,
                    fast ? SensorManager.SENSOR_DELAY_FASTEST : SensorManager.SENSOR_DELAY_GAME);
        }
    }

    public void stop() {
        if (registered) sensorManager.unregisterListener(this);
        registered = false;
        lastTimestamp = 0;
        resetStick();
    }

    private void resetStick() {
        if (stickX != 0 || stickY != 0) {
            stickX = 0;
            stickY = 0;
            if (target != TARGET_MOUSE) output.stickChanged();
        }
    }

    /** Adds the gyro to an outgoing gamepad state (for the stick targets). */
    public void addTo(GamepadState state) {
        if (!enabled || target == TARGET_MOUSE) return;
        float x = stickX, y = stickY;
        if (x == 0 && y == 0) return;
        if (target == TARGET_RIGHT_STICK) {
            state.thumbRX = clamp(state.thumbRX + x);
            state.thumbRY = clamp(state.thumbRY + y);
        } else {
            state.thumbLX = clamp(state.thumbLX + x);
            state.thumbLY = clamp(state.thumbLY + y);
        }
    }

    public boolean drivesStick() {
        return enabled && target != TARGET_MOUSE && registered;
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }

    private boolean isActive() {
        if (activation == ACTIVATION_ALWAYS) return true;
        boolean pressed = output.isButtonPressed(button);
        if (activation == ACTIVATION_HOLD) {
            lastButton = pressed;
            return pressed;
        }
        if (pressed && !lastButton) toggledOn = !toggledOn;
        lastButton = pressed;
        return toggledOn;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        long ts = event.timestamp;
        float dt = lastTimestamp == 0 ? 0f : (ts - lastTimestamp) / 1e9f;
        lastTimestamp = ts;
        if (dt <= 0f || dt > 0.1f) return;

        if (!isActive()) {
            remainderX = remainderY = 0;
            resetStick();
            return;
        }

        // Angular speed (rad/s) around the axes as the screen is held: horizontal turn and vertical tilt.
        float h, v;
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        switch (rotation) {
            case Surface.ROTATION_90:  h = -event.values[0]; v = -event.values[1]; break;
            case Surface.ROTATION_270: h = event.values[0];  v = event.values[1];  break;
            case Surface.ROTATION_180: h = -event.values[1]; v = event.values[0];  break;
            default:                   h = event.values[1];  v = -event.values[0]; break;
        }
        if (invertX) h = -h;
        if (invertY) v = -v;

        if (target == TARGET_MOUSE) {
            // About 900 px per radian at sensitivity 1.
            float dx = h * dt * 900f * sensitivity + remainderX;
            float dy = v * dt * 900f * sensitivity + remainderY;
            int ix = (int) dx, iy = (int) dy;
            remainderX = dx - ix;
            remainderY = dy - iy;
            if (ix != 0 || iy != 0) output.moveMouse(ix, iy);
        } else {
            // Turning speed -> stick deflection, with a small dead zone against hand tremor.
            float x = h * 0.35f * sensitivity;
            float y = v * 0.35f * sensitivity;
            if (Math.abs(x) < 0.02f) x = 0;
            if (Math.abs(y) < 0.02f) y = 0;
            stickX = clamp(x);
            stickY = clamp(y);
            output.stickChanged();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
