package com.winlator.cmod.widget;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.winlator.cmod.R;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.GPUInformation;
import com.winlator.cmod.core.StringUtils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Locale;

public class FrameRating extends FrameLayout implements Runnable {
    private Context context;
    private long lastTime = 0;
    private int frameCount = 0;
    private float lastFPS = 0;
    private String totalRAM = null;
    private final TextView tvFPS;
    private final TextView tvRenderer;
    private final TextView tvGPU;
    private final TextView tvRAM;
    private HashMap graphicsDriverConfig;
    private static final String PREFS = "winlator_hud";
    private static final String KEY_VIS = "hud_vis";
    private final SharedPreferences prefs;
    private boolean userEnabled = false;

    private String lastKnownRenderer = null;

    public FrameRating(Context context, HashMap graphicsDriverConfig) {
        this(context, graphicsDriverConfig ,null);
    }

    public FrameRating(Context context, HashMap graphicsDriverConfig, AttributeSet attrs) {
        this(context, graphicsDriverConfig, attrs, 0);
    }

    public FrameRating(Context context, HashMap graphicsDriverConfig, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        this.context = context;
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        View view = LayoutInflater.from(context).inflate(R.layout.frame_rating, this, false);
        tvFPS = view.findViewById(R.id.TVFPS);
        tvRenderer = view.findViewById(R.id.TVRenderer);
        tvRenderer.setText("Vulkan");
        tvGPU = view.findViewById(R.id.TVGPU);
        tvGPU.setText(GPUInformation.getRenderer(graphicsDriverConfig.get("version").toString(), context));
        tvRAM = view.findViewById(R.id.TVRAM);
        totalRAM = getTotalRAM();
        this.graphicsDriverConfig = graphicsDriverConfig;
        addView(view);
    }

    private String getTotalRAM() {
        long[] mem = readMeminfo();
        return mem[0] > 0 ? StringUtils.formatBytes(mem[0] * 1024L) : "N/A";
    }

    private long[] readMeminfo() {
        long total = -1, avail = -1;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemTotal:"))          total = parseMeminfoKb(line);
                else if (line.startsWith("MemAvailable:")) { avail = parseMeminfoKb(line); break; }
            }
        } catch (Exception ignored) {}
        return new long[]{total, avail};
    }

    private long parseMeminfoKb(String line) {
        try { return Long.parseLong(line.trim().split("\\s+")[1]); }
        catch (Exception e) { return -1; }
    }

    private String getAvailableRAM() {
        long[] mem = readMeminfo();
        if (mem[0] <= 0 || mem[1] < 0) return "N/A";
        long usedKb = mem[0] - mem[1];
        return StringUtils.formatBytes(usedKb * 1024L, false);
    }

    private String apiLabel = null;

    public void setRenderer(String renderer) {
        if (apiLabel != null) renderer = apiLabel;
        lastKnownRenderer = renderer;
        tvRenderer.setText(renderer);
    }

    /** API and layer from the game's log ("VKD3D 3.1 · DX12"): wins over the engine name. */
    public void setApiLabel(String label) {
        apiLabel = label;
        setRenderer(label);
    }

    public void setGpuName (String gpuName) {
        tvGPU.setText(gpuName);
    }

    public void reset() {

        tvRenderer.setText(lastKnownRenderer != null ? lastKnownRenderer : "Vulkan");
        tvGPU.setText(GPUInformation.getRenderer(graphicsDriverConfig.get("version").toString(), context));
    }

    public boolean hasSavedPref() {
        return prefs.contains(KEY_VIS);
    }

    public boolean isSavedVisible() {
        return prefs.getBoolean(KEY_VIS, false);
    }

    public void enableByUser() {
        userEnabled = true;
        prefs.edit().putBoolean(KEY_VIS, true).apply();

        post(() -> setVisibility(View.VISIBLE));
    }

    public void disableByUser() {
        disableByUser(true);
    }

    public void disableByUser(boolean savePrefs) {
        userEnabled = false;
        if (savePrefs) prefs.edit().putBoolean(KEY_VIS, false).apply();
        setVisibility(View.GONE);
    }

    public boolean isUserEnabled() {
        return userEnabled;
    }

    /** The renderer's count of the game's presented frames (Vulkan renderer), as the modern HUD uses. */
    private volatile java.util.function.Supplier<long[]> presentCounter;
    private long lastPresents = -1;

    public void setPresentCounter(java.util.function.Supplier<long[]> counter) {
        presentCounter = counter;
        lastPresents = -1;
    }

    /** Called for each frame of the HUD's window. The window's content updates came both from X
     *  drawing and from the game's direct presents, so counting calls read wrong (often double):
     *  with the renderer's present counter the FPS is the game's own. */
    public void update() {
        if (!userEnabled) return;
        frameCount++;
        long time = SystemClock.elapsedRealtime();
        if (lastTime == 0) lastTime = time;
        if (time < lastTime + 500) return;
        long[] stats = null;
        java.util.function.Supplier<long[]> counter = presentCounter;
        if (counter != null) try { stats = counter.get(); } catch (Throwable ignored) {}
        if (stats != null && stats.length >= 1 && stats[0] > 0) {
            if (lastPresents >= 0 && stats[0] >= lastPresents)
                lastFPS = (stats[0] - lastPresents) * 1000f / (time - lastTime);
            lastPresents = stats[0];
        } else {
            lastFPS = (frameCount * 1000f) / (time - lastTime);
        }
        post(this);
        lastTime = time;
        frameCount = 0;
    }

    @Override
    public void run() {
        if (!userEnabled) return;
        if (getVisibility() == GONE) setVisibility(View.VISIBLE);
        tvFPS.setText(String.format(Locale.ENGLISH, "%.1f", lastFPS));
        tvRAM.setText(getAvailableRAM() + " GB Used / " + totalRAM + " Total");
    }
}
