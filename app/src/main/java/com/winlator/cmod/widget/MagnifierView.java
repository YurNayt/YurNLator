package com.winlator.cmod.widget;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.UnitUtils;
import com.winlator.cmod.math.Mathf;

public class MagnifierView extends FrameLayout {
    private final SharedPreferences preferences;
    private boolean restoreSavedPosition = true;
    private short lastX = 0;
    private short lastY = 0;
    private Callback<Float> zoomButtonCallback;
    private Runnable hideButtonCallback;
    private androidx.compose.runtime.MutableFloatState zoom;

    public MagnifierView(Context context) {
        this(context, null);
    }

    public MagnifierView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MagnifierView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public MagnifierView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        setLayoutParams(new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // The bar in the quick menu's style (ui/ingame/MagnifierPanel); it used to be the old
        // vertical dialog-style panel.
        zoom = com.winlator.cmod.ui.ingame.MagnifierPanel.zoomState(1f);
        View contentView = com.winlator.cmod.ui.ingame.MagnifierPanel.create(context, zoom,
                new com.winlator.cmod.ui.ingame.MagnifierPanel.Actions() {
            @Override public void zoom(float step) { if (zoomButtonCallback != null) zoomButtonCallback.call(step); }
            @Override public void close() { if (hideButtonCallback != null) hideButtonCallback.run(); }
            @Override public void drag(float dx, float dy) { movePanel(getX() + dx, getY() + dy); }
            @Override public void dragEnd() {
                if (lastX > 0 && lastY > 0) preferences.edit().putString("magnifier_view", lastX + "|" + lastY).apply();
                lastX = 0;
                lastY = 0;
            }
        });
        addView(contentView);
    }

    public void setZoomValue(float value) {
        zoom.setFloatValue(value);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        if (restoreSavedPosition) {
            float x = 1e6f;
            float y = 1e6f;

            String config = preferences.getString("magnifier_view", null);
            if (config != null) {
                try {
                    String[] parts = config.split("\\|");
                    x = Short.parseShort(parts[0]);
                    y = Short.parseShort(parts[1]);
                }
                catch (NumberFormatException e) {}
            }

            movePanel(x, y);
            restoreSavedPosition = false;
        }
    }

    private void movePanel(float x, float y) {
        final int padding = (int)UnitUtils.dpToPx(8);
        ViewGroup parent = (ViewGroup)getParent();
        int width = getWidth();
        int height = getHeight();
        int parentWidth = parent.getWidth();
        int parentHeight = parent.getHeight();
        x = Mathf.clamp(x, padding, parentWidth - padding - width);
        y = Mathf.clamp(y, padding, parentHeight - padding - height);
        setX(x);
        setY(y);
        lastX = (short)x;
        lastY = (short)y;
    }

    public Callback<Float> getZoomButtonCallback() {
        return zoomButtonCallback;
    }

    public void setZoomButtonCallback(Callback<Float> zoomButtonCallback) {
        this.zoomButtonCallback = zoomButtonCallback;
    }

    public Runnable getHideButtonCallback() {
        return hideButtonCallback;
    }

    public void setHideButtonCallback(Runnable hideButtonCallback) {
        this.hideButtonCallback = hideButtonCallback;
    }
}