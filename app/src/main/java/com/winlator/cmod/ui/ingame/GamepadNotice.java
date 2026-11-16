package com.winlator.cmod.ui.ingame;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.winlator.cmod.R;
import com.winlator.cmod.ui.theme.WinlatorLegacyTheme;

/**
 * A short notice at the top of the game screen when a physical gamepad starts or stops being used:
 * a rounded card in the app theme's colours (surface, outline, accent icon) that slides in, stays
 * ~3 s and fades away. Taps go through to the game (the card isn't clickable). One at a time.
 */
public final class GamepadNotice {
    private static final String TAG = "gamepad_notice";
    private static final long SHOWN_MS = 3000;

    private GamepadNotice() {}

    /** [subtitle] may be null (title only). Call on the UI thread. */
    public static void show(FrameLayout root, String title, String subtitle) {
        if (root == null) return;
        Context ctx = root.getContext();
        View old = root.findViewWithTag(TAG);
        if (old != null) { old.animate().cancel(); root.removeView(old); }

        int surface = WinlatorLegacyTheme.surface(ctx);
        int onSurface = WinlatorLegacyTheme.onSurface(ctx);
        int onVariant = WinlatorLegacyTheme.onSurfaceVariant(ctx);
        int outline = WinlatorLegacyTheme.outlineVariant(ctx);
        int accent = WinlatorLegacyTheme.primary(ctx);

        LinearLayout card = new LinearLayout(ctx);
        card.setTag(TAG);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 18), dp(ctx, 10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor((surface & 0x00FFFFFF) | 0xF2000000);
        bg.setStroke(dp(ctx, 1), outline);
        bg.setCornerRadius(dp(ctx, 14));
        card.setBackground(bg);
        card.setElevation(dp(ctx, 6));
        card.setClickable(false);
        card.setFocusable(false);

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(R.drawable.ic_gamepad_notice);
        icon.setColorFilter(accent);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(ctx, 24), dp(ctx, 24));
        ip.setMarginEnd(dp(ctx, 12));
        card.addView(icon, ip);

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextColor(onSurface);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = new TextView(ctx);
            s.setText(subtitle);
            s.setTextColor(onVariant);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            s.setMaxLines(1);
            s.setMaxWidth(dp(ctx, 360));
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(s);
        }
        card.addView(texts, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = dp(ctx, 16);
        root.addView(card, lp);

        card.setAlpha(0f);
        card.setTranslationY(-dp(ctx, 24));
        card.animate().alpha(1f).translationY(0f).setDuration(220).withEndAction(() ->
                card.animate().alpha(0f).translationY(-dp(ctx, 12)).setStartDelay(SHOWN_MS).setDuration(300)
                        .withEndAction(() -> root.removeView(card)).start()
        ).start();
    }

    private static int dp(Context ctx, float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
