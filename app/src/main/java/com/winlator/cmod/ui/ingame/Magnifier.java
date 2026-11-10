package com.winlator.cmod.ui.ingame;

import android.app.Activity;
import android.widget.FrameLayout;

import com.winlator.cmod.R;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.widget.DisplayXServerView;
import com.winlator.cmod.widget.MagnifierView;
import com.winlator.cmod.widget.XServerRendererView;

/**
 * The magnifier: a small panel with zoom buttons (1x-3x around the pointer) over the game.
 * Not with the Android display renderer. Moved out of the game screen's drawer (base migration).
 */
public final class Magnifier {
    private MagnifierView view;

    public void open(Activity activity, XServerRendererView renderer) {
        if (renderer instanceof DisplayXServerView) {
            AppUtils.showToast(activity, R.string.magnifier_not_available);
            return;
        }
        if (renderer == null || view != null) return;
        FrameLayout container = activity.findViewById(R.id.FLXServerDisplay);
        if (container == null) return;
        view = new MagnifierView(activity);
        view.setZoomButtonCallback(value -> {
            renderer.setMagnifierZoom(Mathf.clamp(renderer.getMagnifierZoom() + value, 1.0f, 3.0f));
            if (view != null) view.setZoomValue(renderer.getMagnifierZoom());
        });
        view.setZoomValue(renderer.getMagnifierZoom());
        view.setHideButtonCallback(() -> {
            container.removeView(view);
            view = null;
        });
        container.addView(view);
    }
}
