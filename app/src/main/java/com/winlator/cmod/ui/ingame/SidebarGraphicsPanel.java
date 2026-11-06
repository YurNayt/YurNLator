package com.winlator.cmod.ui.ingame;

import android.app.Activity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.Toast;

import com.winlator.cmod.R;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.session.FpsLimit;
import com.winlator.cmod.widget.DisplayXServerView;
import com.winlator.cmod.widget.EGLXServerView;
import com.winlator.cmod.widget.SeekBar;
import com.winlator.cmod.widget.VulkanXServerView;
import com.winlator.cmod.widget.XServerRendererView;

/**
 * YurNLator base, part 13: the sidebar's rendering panel: FPS limit, upscaler (SGSR / FSR),
 * sharpness, post effects, and saving them as the container's graphics preset.
 */
public final class SidebarGraphicsPanel {
    public interface Host {
        Activity activity();
        XServerRendererView renderer();
        Container container();
        Shortcut shortcut();
        void applyFpsLimit(int fps);
        ArrayAdapter<String> spinnerAdapter(String[] items);
    }

    private SidebarGraphicsPanel() {}

    public static void setup(Host host) {
        final Activity a = host.activity();
        final XServerRendererView renderer = host.renderer();
        final Container container = host.container();
        final Shortcut shortcut = host.shortcut();

        final boolean isVulkanRenderer = renderer instanceof VulkanXServerView;

        View reshadePanel = a.findViewById(R.id.LLSubReshade);
        View postFxDivider = a.findViewById(R.id.VPostFXDivider);
        View postFxOptions = a.findViewById(R.id.LLPostFXOptions);

        if (reshadePanel != null) {
            reshadePanel.setVisibility(isVulkanRenderer ? View.VISIBLE : View.GONE);
        }
        if (postFxDivider != null) {
            postFxDivider.setVisibility(isVulkanRenderer ? View.VISIBLE : View.GONE);
        }
        if (postFxOptions != null) {
            postFxOptions.setVisibility(isVulkanRenderer ? View.VISIBLE : View.GONE);
        }

        final VulkanXServerView  vkRenderer = renderer instanceof VulkanXServerView ? (VulkanXServerView) renderer : null;
        final EGLXServerView     eglRenderer = renderer instanceof EGLXServerView ? (EGLXServerView) renderer : null;
        final DisplayXServerView displayXRenderer = renderer instanceof DisplayXServerView ? (DisplayXServerView) renderer : null;

        Spinner spNativeFPS        = a.findViewById(R.id.SPNativeFPS);
        View    llStandardOptions  = a.findViewById(R.id.LLStandardOptions);
        Switch  swEnableFSR        = a.findViewById(R.id.SWEnableFSR);
        Spinner spUpscalerMode     = a.findViewById(R.id.SPUpscalerMode);
        View    lblSharpnessHeader = a.findViewById(R.id.LBLSharpnessHeader);
        SeekBar sbSharpness        = a.findViewById(R.id.SBSharpness);
        Spinner spPostFXMode       = a.findViewById(R.id.SPPostFXMode);
        Spinner spColorMode        = a.findViewById(R.id.SPColorMode);
        View    btSaveGraphicsPreset = a.findViewById(R.id.BTSaveGraphicsPreset);
        View    llFrameGenOptions  = a.findViewById(R.id.LLFrameGenOptions);
        Spinner spFrameGenFPS      = a.findViewById(R.id.SPFrameGenFPS);

        if (llFrameGenOptions != null) llFrameGenOptions.setVisibility(View.GONE);
        if (spFrameGenFPS  != null) spFrameGenFPS.setVisibility(View.GONE);
        if (spColorMode    != null) spColorMode.setVisibility(View.GONE);
        if (llStandardOptions != null) llStandardOptions.setVisibility(isVulkanRenderer ? View.VISIBLE : View.GONE);
        if (btSaveGraphicsPreset != null) btSaveGraphicsPreset.setVisibility(isVulkanRenderer ? View.VISIBLE : View.GONE);
        if (eglRenderer != null) {
            if (swEnableFSR        != null) swEnableFSR.setVisibility(View.GONE);
            if (spUpscalerMode     != null) spUpscalerMode.setVisibility(View.GONE);
            if (lblSharpnessHeader != null) lblSharpnessHeader.setVisibility(View.GONE);
            if (sbSharpness        != null) sbSharpness.setVisibility(View.GONE);
            if (spPostFXMode       != null) spPostFXMode.setVisibility(View.GONE);
        }

        final int[]    fpsValues = {0, 30, 60, 90, 120};
        final String[] fpsLabels = {"Off", "30 FPS", "60 FPS", "90 FPS", "120 FPS"};

        if (spNativeFPS != null) {
            ArrayAdapter<String> ad = host.spinnerAdapter(fpsLabels);
            ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spNativeFPS.setAdapter(ad);
            int savedFpsPos = FpsLimit.legacyIndex(FpsLimit.saved(container, shortcut));
            if (savedFpsPos < 0 || savedFpsPos >= fpsLabels.length) savedFpsPos = 0;
            spNativeFPS.setSelection(savedFpsPos);
            host.applyFpsLimit(FpsLimit.saved(container, shortcut));
            final int initialPos = savedFpsPos;
            spNativeFPS.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                // A spinner reports its initial selection once laid out: that isn't a choice, and it
                // used to replace a limit saved from the quick menu (e.g. 45) with the legacy one.
                boolean first = true;
                @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                    if (first) { first = false; if (pos == initialPos) return; }
                    if (llStandardOptions != null) llStandardOptions.setVisibility(isVulkanRenderer ? View.VISIBLE : View.GONE);
                    if (llFrameGenOptions != null) llFrameGenOptions.setVisibility(View.GONE);
                    int fpsLimit = pos < fpsValues.length ? fpsValues[pos] : 0;
                    host.applyFpsLimit(fpsLimit);
                    FpsLimit.save(container, shortcut, fpsLimit);
                }
                @Override public void onNothingSelected(AdapterView<?> p) {}
            });
        }

        if (btSaveGraphicsPreset != null) {
            btSaveGraphicsPreset.setOnClickListener(v -> {
                if (container == null) return;
                container.putExtra("graphicsFpsPreset",
                    String.valueOf(spNativeFPS != null ? spNativeFPS.getSelectedItemPosition() : 0));
                if (vkRenderer != null) {
                    container.putExtra("graphicsFilterMode",
                        String.valueOf(swEnableFSR != null && swEnableFSR.isChecked()
                            ? (spUpscalerMode != null ? spUpscalerMode.getSelectedItemPosition() + 2 : 2) : 0));
                    container.putExtra("graphicsSharpness",
                        String.valueOf(sbSharpness != null ? sbSharpness.getValue() : 50f));
                    container.putExtra("graphicsPostFXMode",
                        String.valueOf(spPostFXMode != null ? spPostFXMode.getSelectedItemPosition() : 0));
                    container.putExtra("graphicsColorMode", "0");
                }
                container.saveData();
                Toast.makeText(a, "Preset saved", Toast.LENGTH_SHORT).show();
            });
        }

        if (vkRenderer == null) return;

        final String[] upscalerLabels = {"SGSR", "FSR"};
        if (spUpscalerMode != null) {
            ArrayAdapter<String> ad = host.spinnerAdapter(upscalerLabels);
            ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spUpscalerMode.setAdapter(ad);
            spUpscalerMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                    if (swEnableFSR != null && swEnableFSR.isChecked())
                        vkRenderer.setFilterMode(pos + 2);
                }
                @Override public void onNothingSelected(AdapterView<?> p) {}
            });
        }

        String savedSharp = container != null ? container.getExtra("graphicsSharpness") : "";
        float  initSharp  = savedSharp.isEmpty() ? 50f : Float.parseFloat(savedSharp);
        if (sbSharpness != null) {
            sbSharpness.setValue(initSharp);
            vkRenderer.setSharpness(initSharp / 100f);
            sbSharpness.setOnValueChangeListener((sb, v) -> vkRenderer.setSharpness(v / 100f));
        }

        Runnable updateSharpnessVis = () -> {
            boolean fsrOn = swEnableFSR  != null && swEnableFSR.isChecked();
            boolean dlsOn = spPostFXMode != null && spPostFXMode.getSelectedItemPosition() == 1;
            int vis = (fsrOn || dlsOn) ? View.VISIBLE : View.GONE;
            if (lblSharpnessHeader != null) lblSharpnessHeader.setVisibility(vis);
            if (sbSharpness        != null) sbSharpness.setVisibility(vis);
        };

        String savedFilter = container != null ? container.getExtra("graphicsFilterMode") : "";
        boolean fsrOn = !savedFilter.isEmpty() && Integer.parseInt(savedFilter) > 0;
        if (swEnableFSR != null) {
            swEnableFSR.setChecked(fsrOn);
            if (spUpscalerMode != null)
                spUpscalerMode.setVisibility(fsrOn ? View.VISIBLE : View.GONE);
            if (fsrOn)
                vkRenderer.setFilterMode(spUpscalerMode != null
                    ? spUpscalerMode.getSelectedItemPosition() + 2 : 2);
            swEnableFSR.setOnCheckedChangeListener((btn, checked) -> {
                if (spUpscalerMode != null)
                    spUpscalerMode.setVisibility(checked ? View.VISIBLE : View.GONE);
                vkRenderer.setFilterMode(checked
                    ? (spUpscalerMode != null ? spUpscalerMode.getSelectedItemPosition() + 2 : 2)
                    : (container != null ? container.getRendererFilterMode() : 0));
                updateSharpnessVis.run();
            });
        }

        final String[] pfxLabels = {"None", "DLS", "CRT", "HDR", "Natural"};
        if (spPostFXMode != null) {
            ArrayAdapter<String> ad = host.spinnerAdapter(pfxLabels);
            ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spPostFXMode.setAdapter(ad);

            String savedPFX = container != null ? container.getExtra("graphicsPostFXMode") : "";
            int initPFX = savedPFX.isEmpty() ? 0 : Integer.parseInt(savedPFX);
            if (initPFX < 0 || initPFX >= pfxLabels.length) initPFX = 0;

            spPostFXMode.setSelection(initPFX, false);
            if (initPFX > 0) vkRenderer.setPostFXMode(initPFX);

            spPostFXMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                    vkRenderer.setPostFXMode(pos);
                    updateSharpnessVis.run();
                }
                @Override public void onNothingSelected(AdapterView<?> p) {}
            });
        }

        String savedPFXInit = container != null ? container.getExtra("graphicsPostFXMode") : "";
        boolean dlsRestored = !savedPFXInit.isEmpty() && Integer.parseInt(savedPFXInit) == 1;
        int sharpVis = (fsrOn || dlsRestored) ? View.VISIBLE : View.GONE;
        if (lblSharpnessHeader != null) lblSharpnessHeader.setVisibility(sharpVis);
        if (sbSharpness        != null) sbSharpness.setVisibility(sharpVis);

        final String[] frameGenLabels = {"2x Interpolation", "Always On"};
        if (spFrameGenFPS != null) {
            ArrayAdapter<String> ad = host.spinnerAdapter(frameGenLabels);
            ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spFrameGenFPS.setAdapter(ad);
        }
    }
}
