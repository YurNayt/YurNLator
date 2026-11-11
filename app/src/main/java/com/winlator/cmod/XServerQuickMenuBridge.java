package com.winlator.cmod;

import com.winlator.cmod.winhandler.ProcessInfo;
import com.winlator.cmod.winhandler.OnGetProcessInfoListener;
import com.winlator.cmod.core.CPUStatus;
import com.winlator.cmod.widget.CPUListView;
import com.winlator.cmod.ui.quickmenu.QuickMenuBridge;
import com.winlator.cmod.ui.quickmenu.QuickMenuHost;
import com.winlator.cmod.ui.quickmenu.QmInfo;
import com.winlator.cmod.ui.quickmenu.QmGyro;
import com.winlator.cmod.ui.quickmenu.QmFrameGen;
import com.winlator.cmod.core.WinFrameGen;
import com.winlator.cmod.winhandler.GyroInput;
import com.winlator.cmod.ui.quickmenu.QmOption;
import com.winlator.cmod.ui.quickmenu.QmProcess;
import com.winlator.cmod.ui.quickmenu.QmTasks;
import com.winlator.cmod.contents.WrapperManager;
import static com.winlator.cmod.core.AppUtils.showToast;
import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.graphics.Rect;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.Rational;
import android.util.TypedValue;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.preference.PreferenceManager;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contentdialog.DXVKConfigDialog;
import com.winlator.cmod.contentdialog.DebugDialog;
import com.winlator.cmod.contentdialog.GraphicsDriverConfigDialog;
import com.winlator.cmod.contentdialog.WineD3DConfigDialog;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.MaliCompat;
import com.winlator.cmod.core.GPUInformation;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.session.DxWrapperFiles;
import com.winlator.cmod.core.OnExtractFileListener;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.core.ProcessHelper;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.core.WineRegistryEditor;
import com.winlator.cmod.core.WineRequestHandler;
import com.winlator.cmod.core.WineStartMenuCreator;
import com.winlator.cmod.core.WineThemeManager;
import com.winlator.cmod.core.WineUtils;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.ExternalController;
import com.winlator.cmod.inputcontrols.InputControlsManager;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.math.XForm;
import com.winlator.cmod.midi.MidiHandler;
import com.winlator.cmod.midi.MidiManager;
import com.winlator.cmod.widget.FrameRating;
import com.winlator.cmod.widget.SeekBar;
import com.winlator.cmod.widget.WinlatorHUD;
import com.winlator.cmod.widget.InputControlsView;
import com.winlator.cmod.widget.LogView;
import com.winlator.cmod.widget.MagnifierView;
import com.winlator.cmod.widget.TouchpadView;
import com.winlator.cmod.widget.XServerRendererView;
import com.winlator.cmod.widget.VulkanXServerView;
import com.winlator.cmod.widget.EGLXServerView;
import com.winlator.cmod.widget.DisplayXServerView;
import com.winlator.cmod.winhandler.MouseEventFlags;
import com.winlator.cmod.winhandler.TaskManagerSidebar;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.cmod.xenvironment.XEnvironment;
import com.winlator.cmod.xenvironment.components.ALSAServerComponent;
import com.winlator.cmod.xenvironment.components.GuestProgramLauncherComponent;
import com.winlator.cmod.xenvironment.components.PulseAudioComponent;
import com.winlator.cmod.xenvironment.components.SysVSharedMemoryComponent;
import com.winlator.cmod.xenvironment.components.XServerComponent;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.Property;
import com.winlator.cmod.xserver.ScreenInfo;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.WindowManager;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.extensions.RandrExtension;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import cn.sherlock.com.sun.media.sound.SF2Soundbank;

/**
 * YurNLator base, part 15: what the in-game quick menu reads and changes (screen, HUD, controls,
 * frame generation, tasks, session info), for the game screen.
 */
final class XServerQuickMenuBridge implements QuickMenuBridge, OnGetProcessInfoListener {
    private final XServerDisplayActivity a;

    XServerQuickMenuBridge(XServerDisplayActivity activity) { this.a = activity; }

    private final int[] LEGACY_FPS = {0, 30, 60, 90, 120};
    private int upscalerMode = -1;
    private int shaderId = 0;
    private int sharp = 50;
    private int colorBrightness = 0;
    private int colorContrast = 0;
    private float colorGamma = 1f;
    private java.util.Timer tasksTimer;
    private kotlin.jvm.functions.Function1<? super QmTasks, kotlin.Unit> tasksListener;
    private final ArrayList<ProcessInfo> pending = new ArrayList<>();
    private volatile java.util.List<ProcessInfo> processes = new ArrayList<>();
    private volatile int cpuPercent = 0;
    private volatile String memoryText = "…";

    /** Wayland: the compositor draws the game, so the picture settings go to it too. */
    private void syncWayland() {
        if (a.waylandMode) com.winlator.cmod.session.WaylandEffects.apply(upscalerMode, sharp, shaderId,
                colorBrightness, colorContrast, colorGamma);
    }

    private VulkanXServerView vk() {
        return a.xServerView instanceof VulkanXServerView ? (VulkanXServerView) a.xServerView : null;
    }

    private String extra(String key) {
        return a.container != null ? a.container.getExtra(key) : "";
    }

    private void putExtra(String key, String value) {
        if (a.container == null) return;
        a.container.putExtra(key, value);
        a.container.saveData();
    }

    private int parseInt(String v, int def) {
        try { return v == null || v.isEmpty() ? def : (int) Float.parseFloat(v); } catch (Exception e) { return def; }
    }

    /** Re-applies what the menu saved last time (after the old sidebar applied its own). */
    void applySaved() {
        applyFps(com.winlator.cmod.session.FpsLimit.saved(a.container, a.shortcut));
        int filter = parseInt(extra("graphicsFilterMode"), 0);
        upscalerMode = filter >= 2 ? filter - 2 : -1;
        sharp = parseInt(extra("graphicsSharpness"), 50);
        String savedShader = extra("graphicsShader");
        shaderId = !savedShader.isEmpty() ? parseInt(savedShader, 0) : parseInt(extra("graphicsPostFXMode"), 0);
        colorBrightness = parseInt(extra("screenBrightness"), 0);
        colorContrast = parseInt(extra("screenContrast"), 0);
        try { colorGamma = extra("screenGamma").isEmpty() ? 1f : Float.parseFloat(extra("screenGamma")); } catch (Exception e) { colorGamma = 1f; }
        if (shaderId >= 10) upscalerMode = -1;
        syncWayland();
        VulkanXServerView vk = vk();
        if (vk == null) return;
        applyColor(vk);
        vk.setSharpness(sharp / 100f);
        if (shaderId >= 10) {
            upscalerMode = -1;
            vk.setFilterMode(0);
            vk.setPostFXMode(shaderId);
        } else {
            if (upscalerMode >= 0) vk.setFilterMode(upscalerMode + 2);
            vk.setPostFXMode(shaderId);
        }
    }

    // ---- HUD ----
    @Override public int hudMode() { return a.hud.savedMode(); }

    @Override public boolean mangoHudRunning() { return a.hud.mangoHudLaunched(); }
    @Override public boolean mangoHudLog() { return a.preferences.getBoolean("mangohud_log", false); }
    @Override public void setMangoHudLog(boolean on) {
        a.preferences.edit().putBoolean("mangohud_log", on).apply();
        if (a.hud.mangoHudLaunched()) com.winlator.cmod.session.MangoHud.setLogging(a.imageFs.getRootDir(), on);
    }

    @Override public void setHudMode(int mode) { a.hud.apply(mode); }
    @Override public com.winlator.cmod.session.PerfSession performance() { return a.perf; }

    @Override public boolean hudElement(int index) { return a.hud.modern() == null || a.hud.modern().isElementOn(index); }
    @Override public void setHudElement(int index, boolean on) { { WinlatorHUD m = a.hud.modern(); if (m != null) m.toggleElement(index, on); } }
    @Override public float hudScale() { return a.hud.modern() != null ? a.hud.modern().getHudScale() : 1f; }
    @Override public void setHudScale(float scale) { { WinlatorHUD m = a.hud.modern(); if (m != null) m.setHudScale(scale); } }
    @Override public float hudAlpha() { return a.hud.modern() != null ? a.hud.modern().getHudAlpha() : 1f; }
    @Override public void setHudAlpha(float alpha) { { WinlatorHUD m = a.hud.modern(); if (m != null) m.setHudAlpha(alpha); } }
    @Override public void resetHud() { { WinlatorHUD m = a.hud.modern(); if (m != null) m.forceReset(); } }

    private void applyFps(int fps) {
        a.applyFpsLimit(fps);
    }

    @Override public int fpsLimit() {
        return com.winlator.cmod.session.FpsLimit.saved(a.container, a.shortcut);
    }

    @Override public void setFpsLimit(int fps) {
        applyFps(fps);
        com.winlator.cmod.session.FpsLimit.save(a.container, a.shortcut, fps);
    }

    // ---- Screen ----
    @Override public boolean isVulkanRenderer() { return vk() != null; }
    @Override public int upscaler() { return upscalerMode; }

    @Override public int zeroCopy() {
        if (!a.waylandMode) return -1;
        return com.winlator.cmod.session.WaylandZeroCopy.enabled(a.container, a.shortcut) ? 1 : 0;
    }

    @Override public void setZeroCopy(boolean on) {
        if (!a.waylandMode) return;
        com.winlator.cmod.session.WaylandZeroCopy.save(a.container, a.shortcut, on);
        try { com.winlator.cmod.wayland.WaylandCompositor.nativeSetZeroCopy(on); } catch (Throwable ignored) {}
    }

    @Override public void setUpscaler(int mode) {
        VulkanXServerView vk = vk();
        if (vk == null) return;
        upscalerMode = mode;
        if (mode >= 0 && shaderId >= 10) {
            shaderId = 0;
            vk.setPostFXMode(0);
            putExtra("graphicsShader", "0");
        }
        vk.setFilterMode(mode >= 0 ? mode + 2 : (a.container != null ? a.container.getRendererFilterMode() : 0));
        vk.setSharpness(sharp / 100f);
        putExtra("graphicsFilterMode", String.valueOf(mode >= 0 ? mode + 2 : 0));
        syncWayland();
    }

    @Override public int sharpness() { return sharp; }

    @Override public void setSharpness(int value, boolean save) {
        sharp = Math.max(0, Math.min(100, value));
        VulkanXServerView vk = vk();
        if (vk != null) vk.setSharpness(sharp / 100f);
        syncWayland();
        if (save) putExtra("graphicsSharpness", String.valueOf(sharp));
    }

    @Override public int shader() { return shaderId; }

    @Override public void setShader(int id) {
        VulkanXServerView vk = vk();
        if (vk == null) return;
        shaderId = id;
        if (id >= 10) {
            // Effect shaders use the single post pass instead of the upscaler.
            upscalerMode = -1;
            vk.setFilterMode(0);
            if (a.container != null) a.container.putExtra("graphicsFilterMode", "0");
        }
        vk.setSharpness(sharp / 100f);
        vk.setPostFXMode(id);
        if (a.container != null) a.container.putExtra("graphicsPostFXMode", String.valueOf(id <= 4 ? id : 0));
        putExtra("graphicsShader", String.valueOf(id));
        syncWayland();
    }

    private void applyColor(VulkanXServerView vk) {
        vk.setColorAdjust(colorBrightness / 200f, 1f + colorContrast / 100f, colorGamma);
    }

    @Override public int brightness() { return colorBrightness; }
    @Override public int contrast() { return colorContrast; }
    @Override public float gamma() { return colorGamma; }

    @Override public void setColorAdjust(int brightness, int contrast, float gamma, boolean save) {
        colorBrightness = Math.max(-100, Math.min(100, brightness));
        colorContrast = Math.max(-100, Math.min(100, contrast));
        colorGamma = Math.max(0.5f, Math.min(2.5f, gamma));
        VulkanXServerView vk = vk();
        if (vk != null) applyColor(vk);
        syncWayland();
        if (save && a.container != null) {
            a.container.putExtra("screenBrightness", String.valueOf(colorBrightness));
            a.container.putExtra("screenContrast", String.valueOf(colorContrast));
            a.container.putExtra("screenGamma", String.format(java.util.Locale.US, "%.2f", colorGamma));
            a.container.saveData();
        }
    }

    @Override public QmFrameGen frameGen() {
        int mult = parseInt(a.frameGenSetting("frameGenMultiplier", "2"), 2);
        int preset = parseInt(a.frameGenSetting("frameGenPreset", "1"), 1);
        float flow;
        try { flow = Float.parseFloat(a.frameGenSetting("frameGenFlowScale", "0.6")); } catch (Exception e) { flow = 0.6f; }
        return new QmFrameGen(a.xServerView instanceof VulkanXServerView, WinFrameGen.ON.equals(a.frameGenSetting("frameGen", WinFrameGen.OFF)), mult, preset, flow,
                a.frameGenEngine(), com.winlator.cmod.core.LsfgNative.isDllAvailable(a),
                parseInt(a.frameGenSetting("frameGenTarget", "0"), 0));
    }

    @Override public void setFrameGen(QmFrameGen fg) {
        if (a.container == null) return;
        // Saved where the setting came from: the game if it has its own, else the container.
        boolean perGame = a.shortcut != null && !a.shortcut.getExtra("frameGen", "").isEmpty();
        String state = fg.getOn() ? WinFrameGen.ON : WinFrameGen.OFF;
        String[][] values = {
            {"frameGen", state},
            {"frameGenMultiplier", String.valueOf(fg.getMultiplier())},
            {"frameGenPreset", String.valueOf(fg.getPreset())},
            {"frameGenFlowScale", String.format(java.util.Locale.US, "%.2f", fg.getFlowScale())},
            {"frameGenEngine", fg.getEngine()},
            {"frameGenTarget", String.valueOf(fg.getTarget())}
        };
        for (String[] kv : values) {
            if (perGame) a.shortcut.putExtra(kv[0], kv[1]);
            else a.container.putExtra(kv[0], kv[1]);
        }
        if (perGame) a.shortcut.saveData(); else a.container.saveData();
        a.applyFrameGen();
    }

    @Override public int scaling() { return a.scaling.mode(); }

    @Override public void setScaling(int mode) { a.scaling.setMode(mode); }

    @Override public void openMagnifier() { a.magnifier.open(a, a.xServerView); }

    @Override public void enterPip() { a.enterPipMode(); }

    // ---- Controls ----
    @Override public java.util.List<QmOption> controlProfiles() {
        ArrayList<QmOption> out = new ArrayList<>();
        if (a.inputControlsManager == null) return out;
        for (ControlsProfile p : a.inputControlsManager.getProfiles(true)) out.add(new QmOption(p.id, p.getName()));
        return out;
    }

    @Override public int controlProfile() {
        if (a.inputControlsView == null || a.inputControlsView.getProfile() == null) return 0;
        return a.inputControlsView.getVisibility() == View.VISIBLE || a.inputControlsView.isShowTouchscreenControls()
                ? a.inputControlsView.getProfile().id : 0;
    }

    @Override public void setControlProfile(int id) {
        if (a.inputControlsManager == null || a.inputControlsView == null) return;
        ArrayList<ControlsProfile> profiles = a.inputControlsManager.getProfiles();
        int index = -1;
        for (int i = 0; i < profiles.size(); i++) if (profiles.get(i).id == id) index = i;
        a.preferences.edit().putInt("selected_profile_index", index).apply();
        if (index >= 0) a.showInputControls(profiles.get(index));
        else a.hideInputControls();
        applyTouchTimeout();
    }

    private void applyTouchTimeout() {
        if (a.inputControlsView == null) return;
        // On: fade after 5 s without a touch; off: back to full and no timer (startTimeout does both).
        a.startTouchscreenTimeout();
    }

    @Override public void editControls() {
        if (a.inputControlsManager == null) return;
        final int current = controlProfile();
        Intent intent = new Intent(a, MainActivity.class);
        intent.putExtra("edit_input_controls", true);
        intent.putExtra("selected_profile_id", current);
        a.editInputControlsCallback = () -> {
            a.hideInputControls();
            a.inputControlsManager.loadProfiles(true);
            if (current != 0) setControlProfile(current);
        };
        a.drawerLayout.closeDrawers();
        a.controlsEditorActivityResultLauncher.launch(intent);
    }

    @Override public boolean touchControls() { return a.inputControlsView != null && a.inputControlsView.isShowTouchscreenControls(); }

    @Override public void setTouchControls(boolean on) { a.touchControls.setShown(on); }

    @Override public boolean touchTimeout() { return a.preferences.getBoolean("touchscreen_timeout_enabled", false); }

    @Override public void setTouchTimeout(boolean on) {
        a.preferences.edit().putBoolean("touchscreen_timeout_enabled", on).apply();
        applyTouchTimeout();
    }

    @Override public boolean touchHaptics() { return a.preferences.getBoolean("touchscreen_haptics_enabled", false); }
    @Override public void setTouchHaptics(boolean on) { a.preferences.edit().putBoolean("touchscreen_haptics_enabled", on).apply(); }

    @Override public float controlsOpacity() { return a.preferences.getFloat("overlay_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY); }

    @Override public void setControlsOpacity(float value) {
        a.preferences.edit().putFloat("overlay_opacity", value).apply();
        if (a.inputControlsView != null) {
            a.inputControlsView.setOverlayOpacity(value);
            a.inputControlsView.invalidate();
        }
    }

    @Override public float mouseSpeed() { return a.globalCursorSpeed; }

    @Override public void setMouseSpeed(float speed) {
        a.globalCursorSpeed = Math.max(0.1f, Math.min(5f, speed));
        a.preferences.edit().putFloat("cursor_speed", a.globalCursorSpeed).apply();
        ControlsProfile p = a.inputControlsView != null ? a.inputControlsView.getProfile() : null;
        if (a.touchpadView != null) a.touchpadView.setSensitivity(a.globalCursorSpeed * (p != null ? p.getCursorSpeed() : 1f));
    }

    @Override public boolean relativeMouse() { return a.isRelativeMouseMovement; }

    @Override public void setRelativeMouse(boolean on) {
        a.isRelativeMouseMovement = on;
        if (a.xServer != null) a.xServer.setRelativeMouseMovement(on);
    }

    @Override public boolean mouseDisabled() { return a.isMouseDisabled; }

    @Override public void setMouseDisabled(boolean on) {
        a.isMouseDisabled = on;
        if (a.touchpadView != null) a.touchpadView.setMouseEnabled(!on);
    }

    @Override public QmGyro gyro() {
        GyroInput g = a.gyroInput;
        if (g == null) return new QmGyro(false, false, 0, 1f, false, false, false, 0, 4, 0);
        return new QmGyro(g.isAvailable(), g.enabled, g.target, g.sensitivity, g.invertX, g.invertY, g.fast, g.activation, g.button, g.mouseMode);
    }

    @Override public void setGyro(QmGyro s) {
        GyroInput g = a.gyroInput;
        if (g == null) return;
        boolean restart = g.enabled != s.getEnabled() || g.fast != s.getFast() || g.target != s.getTarget() || g.activation != s.getActivation();
        g.enabled = s.getEnabled();
        g.target = s.getTarget();
        g.sensitivity = s.getSensitivity();
        g.invertX = s.getInvertX();
        g.invertY = s.getInvertY();
        g.fast = s.getFast();
        g.activation = s.getActivation();
        g.button = s.getButton();
        g.mouseMode = s.getMouseMode();
        g.save();
        if (restart) g.apply();
    }

    @Override public void showKeyboard() { AppUtils.showKeyboard(a); }
    @Override public void vibrationSettings() { a.showVibrationDialog(); }

    /** "DXVK 3.1.1-1-gplasync-arm64ec + VKD3D 3.1-arm64ec": names and full versions, not just "DXVK+VKD3D". */
    private String dxWrapperFullName() {
        if (a.dxwrapper == null) return "";
        String cfg = a.launchConfig != null ? a.launchConfig.getDxwrapperConfig() : "";
        String dxvk = a.dxvkBuild != null ? a.dxvkBuild : cfgValue(cfg, "version");
        String vkd3d = cfgValue(cfg, "vkd3dVersion");
        String w = a.dxwrapper.toLowerCase(java.util.Locale.ENGLISH);
        // Once the game showed which layer it uses: that one, in full, with the API.
        String api = a.actualApi;
        if (api != null && api.contains(" · ")) {
            String apiName = api.substring(api.lastIndexOf(" · ") + 3);
            if (api.startsWith("VKD3D")) return "VKD3D" + (vkd3d.isEmpty() ? "" : " " + vkd3d) + " · " + apiName;
            if (api.startsWith("DXVK")) return "DXVK" + (dxvk.isEmpty() ? "" : " " + dxvk) + " · " + apiName;
        }
        ArrayList<String> parts = new ArrayList<>();
        if (w.contains("dxvk")) parts.add("DXVK" + (dxvk.isEmpty() ? "" : " " + dxvk));
        if (w.contains("vkd3d")) parts.add("VKD3D" + (vkd3d.isEmpty() ? "" : " " + vkd3d));
        if (w.contains("wined3d")) parts.add("WineD3D");
        return parts.isEmpty() ? a.dxwrapper : android.text.TextUtils.join(" + ", parts);
    }

    private String cfgValue(String cfg, String key) {
        if (cfg == null) return "";
        for (String kv : cfg.split(",")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).trim().equals(key)) return kv.substring(i + 1).trim();
        }
        return "";
    }

    // ---- Tasks ----
    @Override public java.util.List<QmInfo> sessionInfo() {
        ArrayList<QmInfo> out = new ArrayList<>();
        out.add(new QmInfo("Wine", a.wineInfo != null ? a.wineInfo.toString() : (a.container != null ? a.container.getWineVersion() : "")));
        out.add(new QmInfo("Display backend", a.waylandMode ? "Wayland (compositor)" : "X11"
                + (a.launchConfig != null && a.launchConfig.getWayland() ? " (Wayland not available with this Proton)" : "")));
        out.add(new QmInfo("DX wrapper", dxWrapperFullName()));
        String renderer = a.xServerView instanceof VulkanXServerView ? "Vulkan"
                : a.xServerView instanceof EGLXServerView ? "OpenGL (EGL)" : "Native display";
        out.add(new QmInfo("Renderer", renderer));
        if (a.graphicsDriverConfig != null) {
            String driver = a.graphicsDriverConfig.get("version");
            if (driver != null && !driver.isEmpty()) out.add(new QmInfo("Graphics driver", driver));
            // Only when it says something the configured driver's name doesn't ("Turnip" vs "Mesa Turnip v27…").
            if (a.actualDriver != null && (driver == null || !driver.toLowerCase(java.util.Locale.ENGLISH)
                    .contains(a.actualDriver.toLowerCase(java.util.Locale.ENGLISH))))
                out.add(new QmInfo("Running on", a.actualDriver));
            String wrapper = a.graphicsDriverConfig.get("wrapper");
            if (wrapper != null && !wrapper.isEmpty()) out.add(new QmInfo("Vulkan wrapper", wrapper));
        }
        if (a.xServer != null) out.add(new QmInfo("Resolution", a.xServer.screenInfo.toString()
                + (a.xServer.displayMode != null && a.xServer.displayMode.isActive()
                    ? " · game " + a.xServer.displayMode.getWidth() + "x" + a.xServer.displayMode.getHeight() + " (scaled to fit)" : "")));
        String soc = Build.VERSION.SDK_INT >= 31 ? " · " + Build.SOC_MODEL : "";
        int vaBits = com.winlator.cmod.session.GameFixes.userAddressBits();
        if (a.launchConfig != null) {
            String cpus = a.launchConfig.getCpuList();
            out.add(new QmInfo("CPU cores", cpus == null || cpus.isEmpty() ? "all" : cpus.replace(",", ", ")
                    + (a.launchConfig.getCpuListFromShortcut() ? " (this game)" : " (container)")));
        }
        if (vaBits > 0) out.add(new QmInfo("Address space", vaBits + "-bit" + (vaBits <= 39 ? " (512 GiB: some games need fixes)" : "")));
        out.add(new QmInfo("Device", Build.MANUFACTURER.toUpperCase(java.util.Locale.ENGLISH) + " " + Build.MODEL + soc
                + " · " + Runtime.getRuntime().availableProcessors() + "c · Android " + Build.VERSION.RELEASE));
        return out;
    }

    private String hdrInfo() {
        try {
            android.view.Display display = a.getWindowManager().getDefaultDisplay();
            android.view.Display.HdrCapabilities caps = display.getHdrCapabilities();
            if (caps == null || caps.getSupportedHdrTypes().length == 0) return "Not supported";
            ArrayList<String> names = new ArrayList<>();
            for (int t : caps.getSupportedHdrTypes()) {
                switch (t) {
                    case 1: names.add("Dolby Vision"); break;
                    case 2: names.add("HDR10"); break;
                    case 3: names.add("HLG"); break;
                    case 4: names.add("HDR10+"); break;
                }
            }
            float nits = caps.getDesiredMaxLuminance();
            return String.join(", ", names) + (nits > 0 ? " · " + Math.round(nits) + " nits" : "");
        } catch (Throwable e) {
            return "Unknown";
        }
    }

    @Override public void startTasks(kotlin.jvm.functions.Function1<? super QmTasks, kotlin.Unit> listener) {
        stopTasks();
        if (a.winHandler == null) return;
        tasksListener = listener;
        a.winHandler.setOnGetProcessInfoListener(this);
        tasksTimer = new java.util.Timer();
        tasksTimer.schedule(new java.util.TimerTask() {
            @Override public void run() {
                a.winHandler.listProcesses();
                updateCpuAndMemory();
                publishTasks();
            }
        }, 0, 1000);
    }

    @Override public void stopTasks() {
        if (tasksTimer != null) {
            tasksTimer.cancel();
            tasksTimer = null;
            if (a.winHandler != null) a.winHandler.setOnGetProcessInfoListener(null);
        }
        tasksListener = null;
    }

    private void publishTasks() {
        a.runOnUiThread(() -> {
            kotlin.jvm.functions.Function1<? super QmTasks, kotlin.Unit> l = tasksListener;
            if (l == null) return;
            ArrayList<QmProcess> rows = new ArrayList<>();
            for (ProcessInfo p : processes) rows.add(new QmProcess(p.pid, p.name, p.getFormattedMemoryUsage(), p.wow64Process, coreList(a.cpuAffinity.shownMask(p.pid, p.affinityMask))));
            l.invoke(new QmTasks(cpuPercent, memoryText, rows));
        });
    }

    @Override public void onGetProcessInfo(int index, int numProcesses, ProcessInfo processInfo) {
        synchronized (pending) {
            if (numProcesses == 0) {
                pending.clear();
                processes = new ArrayList<>();
                publishTasks();
                return;
            }
            if (index == 0) pending.clear();
            if (processInfo != null) pending.add(processInfo);
            if (index == numProcesses - 1) {
                processes = new ArrayList<>(pending);
                publishTasks();
            }
        }
    }

    private void updateCpuAndMemory() {
        short[] clocks = CPUStatus.getCurrentClockSpeeds();
        if (clocks.length > 0) {
            int total = 0;
            short max = 0;
            for (int i = 0; i < clocks.length; i++) {
                total += clocks[i];
                max = (short) Math.max(max, CPUStatus.getMaxClockSpeed(i));
            }
            cpuPercent = max == 0 ? 0 : (int) (((float) (total / clocks.length) / max) * 100f);
        }
        android.app.ActivityManager am = (android.app.ActivityManager) a.getSystemService(android.content.Context.ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        double gib = 1024.0 * 1024.0 * 1024.0;
        memoryText = String.format(java.util.Locale.US, "%.1f / %.1f GB", (mi.totalMem - mi.availMem) / gib, mi.totalMem / gib);
    }

    @Override public void bringToFront(String name) { if (a.winHandler != null) a.winHandler.bringToFront(name); }
    @Override public void endProcess(String name) { if (a.winHandler != null) a.winHandler.killProcess(name); }

    @Override public void processAffinity(int pid) {
        ProcessInfo info = null;
        for (ProcessInfo p : processes) if (p.pid == pid) info = p;
        if (info == null || a.winHandler == null) return;
        final ProcessInfo processInfo = info;
        ContentDialog dialog = new ContentDialog(a, R.layout.cpu_list_dialog);
        dialog.setTitle(processInfo.name);
        dialog.setIcon(R.drawable.icon_cpu);
        final CPUListView cpuListView = dialog.findViewById(R.id.CPUListView);
        // What the row shows (the user's last choice, else Wine's report), not /proc of an unrelated
        // Linux pid: the dialog used to open with other cores than the row said.
        int shown = a.cpuAffinity.shownMask(processInfo.pid, processInfo.affinityMask);
        cpuListView.setCheckedCPUList(new ProcessInfo(processInfo.pid, "", 0, shown, false).getCPUList());
        dialog.setOnConfirmCallback(() -> {
            a.cpuAffinity.setUserMask(processInfo.pid, ProcessHelper.getAffinityMask(cpuListView.getCheckedCPUList()), a.winHandler);
            publishTasks();
        });
        dialog.show();
    }

    @Override public int processCores(int pid) {
        for (ProcessInfo p : processes) if (p.pid == pid) return a.cpuAffinity.shownMask(pid, p.affinityMask);
        return 0;
    }

    @Override public void setProcessCores(int pid, int mask) {
        a.cpuAffinity.setUserMask(pid, mask, a.winHandler);
        publishTasks();
    }

    @Override public int cpuCount() { return Runtime.getRuntime().availableProcessors(); }

    @Override public void runTask(String command) { if (a.winHandler != null) a.winHandler.exec(command); }

    @Override public void newTask() {
        if (a.winHandler == null) return;
        ContentDialog.prompt(a, R.string.new_task, "taskmgr.exe", (command) -> a.winHandler.exec(command));
    }

    @Override public boolean logsAvailable() { return a.quickMenuLogs && a.debugDialog != null; }
    @Override public void showLogs() { if (a.debugDialog != null) a.debugDialog.show(); }

    // ---- Session ----
    @Override public boolean isPaused() { return a.isPaused; }

    @Override public void setPaused(boolean paused) {
        if (paused == a.isPaused) return;
        if (paused) ProcessHelper.pauseAllWineProcesses();
        else ProcessHelper.resumeAllWineProcesses();
        a.isPaused = paused;
        View pause = a.findViewById(R.id.BTItemPause);
        if (pause instanceof ViewGroup && ((ViewGroup) pause).getChildAt(0) instanceof ImageView) {
            ((ImageView) ((ViewGroup) pause).getChildAt(0)).setImageResource(paused ? R.drawable.icon_play : R.drawable.icon_pause);
        }
    }

    @Override public void exitGame() {
        a.drawerLayout.closeDrawers();
        a.exit();
    }

    @Override public void close() { a.drawerLayout.closeDrawers(); }

    /** "0-3, 6" from an affinity bit mask; "" when unknown or every core. */
    static String coreList(int mask) {
        int n = Runtime.getRuntime().availableProcessors();
        int all = n >= 31 ? -1 : (1 << n) - 1;
        if (mask == 0 || (mask & all) == all) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 32; i++) {
            if ((mask & (1 << i)) == 0) continue;
            int j = i;
            while (j + 1 < 32 && (mask & (1 << (j + 1))) != 0) j++;
            if (sb.length() > 0) sb.append(", ");
            sb.append(i == j ? String.valueOf(i) : i + "-" + j);
            i = j;
        }
        return sb.toString();
    }
}
