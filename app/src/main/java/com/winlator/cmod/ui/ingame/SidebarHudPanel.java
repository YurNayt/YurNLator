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
 * YurNLator base, part 12: the sidebar's HUD panel: on / off, style (classic / modern), the
 * modern HUD's items, scale, opacity and reset.
 */
public final class SidebarHudPanel {
    public interface Host {
        Activity activity();
        WinlatorHUD modernHud();
        FrameRating classicHud();
        /** Saved mode: 0 off, 1 classic, 2 modern. */
        int savedMode();
        /** Creates (once) and shows the HUD of [style]. */
        void enable(int style);
        void saveMode(int mode);
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

        int mode = host.modernHud() != null ? 2 : host.classicHud() != null ? 1 : host.savedMode();
        boolean on = mode != 0, modern = mode == 2;

        if (spStyle != null) {
            ArrayAdapter<String> adapter = host.spinnerAdapter(new String[]{"Classic", "Modern"});
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spStyle.setAdapter(adapter);
            spStyle.setSelection(modern ? 1 : 0, false);
        }
        visible(styleRow, on);
        visible(modernOptions, modern);
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
                int style = selectedStyle(spStyle);
                if (checked) {
                    host.enable(style);
                    visible(styleRow, true);
                    visible(modernOptions, style == 2);
                    if (style == 2 && host.modernHud() != null) syncItems(host, it);
                    host.saveMode(style);
                } else {
                    if (host.classicHud() != null) host.classicHud().disableByUser();
                    if (host.modernHud() != null) host.modernHud().disableByUser();
                    visible(styleRow, false);
                    visible(modernOptions, false);
                    host.saveMode(0);
                }
            });
        }

        if (spStyle != null) {
            // Posted: the spinner's own first callback (its initial selection) isn't a choice.
            spStyle.post(() -> spStyle.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                    if (swMaster == null || !swMaster.isChecked()) return;
                    int style = pos == 1 ? 2 : 1;
                    if (host.classicHud() != null) host.classicHud().disableByUser(false);
                    if (host.modernHud() != null) host.modernHud().disableByUser(false);
                    host.enable(style);
                    visible(modernOptions, style == 2);
                    if (style == 2 && host.modernHud() != null) syncItems(host, it);
                    host.saveMode(style);
                }
                @Override public void onNothingSelected(AdapterView<?> p) {}
            }));
        }
    }

    private static int selectedStyle(Spinner spStyle) {
        return spStyle != null && spStyle.getSelectedItemPosition() == 1 ? 2 : 1;
    }

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
        bind(it.battTemp, hud, 4);
        bind(it.renderer, hud, 6);
    }

    private static void bind(CheckBox box, WinlatorHUD hud, int element) {
        if (box != null) box.setOnCheckedChangeListener((b, v) -> hud.toggleElement(element, v));
    }
}
