package com.winlator.cmod.ui.ingame;

import android.app.Activity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;

import com.winlator.cmod.R;
import com.winlator.cmod.widget.FrameRating;
import com.winlator.cmod.widget.SeekBar;
import com.winlator.cmod.widget.WinlatorHUD;

/**
 * YurNLator base, part 12: the sidebar's HUD panel: on / off, style (YurNLator / MangoHud, the
 * same switch as the quick menu's), our HUD's items, scale, opacity and reset.
 */
public final class SidebarHudPanel {
    public interface Host {
        Activity activity();
        WinlatorHUD modernHud();
        FrameRating classicHud();
        /** Saved mode: 0 off, 2 YurNLator, 3 MangoHud, 4 Bannerlator. */
        int savedMode();
        /** Switches the HUD (shows / hides, saves): the quick menu's switch. */
        void apply(int mode);
        ArrayAdapter<String> spinnerAdapter(String[] items);
    }

    private SidebarHudPanel() {}

    private static final class Items {
        CheckBox fps, gpu, cpuRam, ram, battTemp, graph, renderer;
    }

    public static void setup(Host host) {
        Activity a = host.activity();
        Switch swMaster = a.findViewById(R.id.SWHudMaster);
        Spinner spStyle = a.findViewById(R.id.SPHudStyle);
        LinearLayout styleRow = a.findViewById(R.id.LLHudStyleRow);
        LinearLayout modernOptions = a.findViewById(R.id.LLModernHudOptions);
        Items it = new Items();
        it.fps = a.findViewById(R.id.CBHudFps);
        it.gpu = a.findViewById(R.id.CBHudGpu);
        it.cpuRam = a.findViewById(R.id.CBHudCpuRam);
        it.ram = a.findViewById(R.id.CBHudRam);
        it.battTemp = a.findViewById(R.id.CBHudBattTemp);
        it.graph = a.findViewById(R.id.CBHudGraph);
        it.renderer = a.findViewById(R.id.CBHudRenderer);
        SeekBar sbScale = a.findViewById(R.id.SBHudScale);
        SeekBar sbAlpha = a.findViewById(R.id.SBHudAlpha);
        View btReset = a.findViewById(R.id.BTResetHud);

        int saved = host.savedMode();
        final int[] current = {saved == 3 || saved == 4 ? saved : saved != 0 ? 2 : 0};
        boolean on = current[0] != 0;

        if (spStyle != null) {
            ArrayAdapter<String> adapter = host.spinnerAdapter(new String[]{"YurNLator", "Bannerlator", "MangoHud"});
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spStyle.setAdapter(adapter);
            spStyle.setSelection(posOf(current[0]), false);
        }
        visible(styleRow, on);
        visible(modernOptions, current[0] == 2);
        if (host.modernHud() != null) syncItems(host, it);

        if (sbScale != null) sbScale.setOnValueChangeListener((sb, v) -> {
            if (host.modernHud() != null) host.modernHud().setHudScale(1f + (v - 50f) / 50f);
        });
        if (sbAlpha != null) sbAlpha.setOnValueChangeListener((sb, v) -> {
            if (host.modernHud() != null) host.modernHud().setHudAlpha(v / 100f);
        });
        if (btReset != null) btReset.setOnClickListener(v -> {
            if (host.modernHud() != null) host.modernHud().forceReset();
        });

        if (swMaster != null) {
            swMaster.setChecked(on);
            swMaster.setOnCheckedChangeListener((btn, checked) -> {
                int style = checked ? selectedStyle(spStyle) : 0;
                current[0] = style;
                host.apply(style);
                visible(styleRow, checked);
                visible(modernOptions, style == 2);
                if (style == 2 && host.modernHud() != null) syncItems(host, it);
            });
        }

        if (spStyle != null) {
            // The spinner reports its initial selection too (sometimes after this post): only a
            // real change of style counts, or reopening the sidebar would switch the HUD itself.
            spStyle.post(() -> spStyle.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                    if (swMaster == null || !swMaster.isChecked()) return;
                    int style = styleAt(pos);
                    if (style == current[0]) return;
                    current[0] = style;
                    host.apply(style);
                    visible(modernOptions, style == 2);
                    if (style == 2 && host.modernHud() != null) syncItems(host, it);
                }
                @Override public void onNothingSelected(AdapterView<?> p) {}
            }));
        }
    }

    private static int selectedStyle(Spinner spStyle) {
        return spStyle != null ? styleAt(spStyle.getSelectedItemPosition()) : 2;
    }

    private static int styleAt(int pos) { return pos == 1 ? 4 : pos == 2 ? 3 : 2; }

    private static int posOf(int style) { return style == 4 ? 1 : style == 3 ? 2 : 0; }

    private static void visible(View v, boolean on) {
        if (v != null) v.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    /** The checkboxes show the modern HUD's items and switch them. */
    private static void syncItems(Host host, Items it) {
        WinlatorHUD hud = host.modernHud();
        hud.syncCheckboxes(it.fps, it.gpu, it.cpuRam, it.battTemp, it.graph, it.renderer);
        if (it.ram != null) it.ram.setChecked(true);
        bind(it.fps, hud, 0);
        bind(it.gpu, hud, 2);
        bind(it.cpuRam, hud, 3);
        bind(it.ram, hud, 7);
        bind(it.battTemp, hud, 15);
        bind(it.renderer, hud, 6);
    }

    private static void bind(CheckBox box, WinlatorHUD hud, int element) {
        if (box != null) box.setOnCheckedChangeListener((b, v) -> hud.toggleElement(element, v));
    }
}
