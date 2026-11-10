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

public class XServerDisplayActivity extends AppCompatActivity {


    public static String NOTIFICATION_CHANNEL_ID = "Winlator";
    public static int NOTIFICATION_ID = -1;
    XServerRendererView xServerView;
    InputControlsView inputControlsView;
    TouchpadView touchpadView;
    private XEnvironment environment;
    DrawerLayout drawerLayout;
    private ContainerManager containerManager;
    protected Container container;
    XServer xServer;
    InputControlsManager inputControlsManager;
    private ImageFs imageFs;
    FrameRating classicHud = null;
    WinlatorHUD modernHud = null;
    Runnable editInputControlsCallback;
    Shortcut shortcut;
    /** Resolved launch settings (shortcut over container), see session/LaunchConfig. */
    com.winlator.cmod.session.LaunchConfig launchConfig;
    /** Wayland display backend for this session (session/WaylandSession). */
    boolean waylandMode = false;
    com.winlator.cmod.session.WaylandSession waylandSession;
    /** The Vulkan driver the game reported at start (DriverSniffer); null until then. */
    volatile String actualDriver;
    /** From the game's log: "VKD3D 3.1 · DX12" (HUD + session info). */
    volatile String actualApi;
    /** Full DXVK build from its banner: "3.1.1-1-gplasync-arm64ec". */
    volatile String dxvkBuild;
    private String graphicsDriver = Container.DEFAULT_GRAPHICS_DRIVER;
    HashMap<String, String> graphicsDriverConfig;
    private String audioDriver = Container.DEFAULT_AUDIO_DRIVER;
    private String emulator = Container.DEFAULT_EMULATOR;
    String dxwrapper = Container.DEFAULT_DXWRAPPER;
    private KeyValueSet dxwrapperConfig;
    private String startupSelection;
    WineInfo wineInfo;
    private final EnvVars envVars = new EnvVars();
    private boolean firstTimeBoot = false;
    SharedPreferences preferences;
    private OnExtractFileListener onExtractFileListener;
    WinHandler winHandler;
    TaskManagerSidebar taskManagerSidebar;
    private WineRequestHandler wineRequestHandler;
    float globalCursorSpeed = 1.0f;
    final com.winlator.cmod.ui.ingame.Magnifier magnifier = new com.winlator.cmod.ui.ingame.Magnifier();
    /** Fit / fullscreen / soft stretch (session/ScreenScaling). */
    final com.winlator.cmod.session.ScreenScaling scaling = new com.winlator.cmod.session.ScreenScaling(
            () -> xServerView, () -> touchpadView, () -> waylandSession);
    DebugDialog debugDialog;
    /** CPU cores of the game's processes (session/CpuAffinity). */
    com.winlator.cmod.session.CpuAffinity cpuAffinity = com.winlator.cmod.session.CpuAffinity.none();
    /** Which X window the HUD counts frames of (session/HudWindowTracker). */
    final com.winlator.cmod.session.HudWindowTracker hudTracker =
            new com.winlator.cmod.session.HudWindowTracker(new com.winlator.cmod.session.HudWindowTracker.Host() {
                @Override public WinlatorHUD modernHud() { return modernHud; }
                @Override public FrameRating classicHud() { return classicHud; }
                @Override public XServerRendererView renderer() { return xServerView; }
                @Override public void runOnUi(Runnable r) { runOnUiThread(r); }
            });
    private boolean cursorLock;
    private final float[] xform = XForm.getInstance();
    private ContentsManager contentsManager;
    private boolean navigationFocused = false;
    private MidiHandler midiHandler;
    private String midiSoundFont = "";
    private String lc_all = "";
    private String vkbasaltConfig = "";
    PreloaderDialog preloaderDialog = null;
    private Runnable configChangedCallback = null;
    boolean isPaused = false;
    boolean isRelativeMouseMovement = false;
    boolean isMouseDisabled = false;
    private boolean simulateTouchScreen = false;

    private SensorManager sensorManager;

    /** Play time and launch count of the game (session/Playtime). */
    private com.winlator.cmod.session.Playtime playtime;
    private String shortcutName;
    private Handler handler;

    private Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable hideControlsRunnable;
    com.winlator.cmod.ui.ingame.TouchControls touchControls;

    private boolean isDarkMode;

    private String screenEffectProfile;

    private GuestProgramLauncherComponent guestProgramLauncherComponent;
    private EnvVars overrideEnvVars;

    private void createNotifcationChannel() {
        String name = "YurNLator";
        String description = "YurNLator X server messages";
        int importance = NotificationManager.IMPORTANCE_HIGH;
        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance);
        channel.setDescription(description);
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (configChangedCallback != null) {
            configChangedCallback.run();
            configChangedCallback = null;
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppUtils.hideSystemUI(this);
        AppUtils.keepScreenOn(this);

        android.view.WindowManager.LayoutParams params = getWindow().getAttributes();
        params.preferredRefreshRate = com.winlator.cmod.session.RefreshRate.highest(this);
        getWindow().setAttributes(params);

        setContentView(R.layout.xserver_display_activity);

        preloaderDialog = new PreloaderDialog(this);
        preferences = PreferenceManager.getDefaultSharedPreferences(this);

        cursorLock = preferences.getBoolean("cursor_lock", true);

        isDarkMode = preferences.getBoolean("dark_mode", false);

        boolean isOpenWithAndroidBrowser = preferences.getBoolean("open_with_android_browser", false);
        boolean isShareAndroidClipboard = preferences.getBoolean("share_android_clipboard", false);

        boolean xinputDisabledFromShortcut = false;

        handler = new Handler(Looper.getMainLooper());
        playtime = new com.winlator.cmod.session.Playtime(this);
        playtime.resume();

        // On-screen controls: ui/ingame/TouchControls (YurNLator base, part 10).
        touchControls = new com.winlator.cmod.ui.ingame.TouchControls(preferences, timeoutHandler,
                () -> inputControlsView, () -> touchpadView, () -> winHandler, () -> globalCursorSpeed);
        hideControlsRunnable = touchControls.hideRunnable();

        contentsManager = new ContentsManager(this);
        contentsManager.syncContents();

        drawerLayout = findViewById(R.id.DrawerLayout);
        drawerLayout.setOnApplyWindowInsetsListener(
                (view, windowInsets) -> windowInsets.replaceSystemWindowInsets(0, 0, 0, 0));
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View drawerView) {
                QuickMenuHost.onOpened();
                sidebar.reopen();
            }

            @Override
            public void onDrawerClosed(View drawerView) {
                quickMenuBridge.stopTasks();
                sidebar.hideAllPanels();
            }
        });

        boolean enableLogs = preferences.getBoolean("enable_wine_debug", false)
                || preferences.getBoolean("enable_box64_logs", false);

        sidebar.wire(enableLogs);
        quickMenuLogs = enableLogs;
        androidx.compose.ui.platform.ComposeView quickMenuView = findViewById(R.id.QuickMenuCompose);
        if (quickMenuView != null) QuickMenuHost.attach(quickMenuView, quickMenuBridge);

        imageFs = ImageFs.find(this);

        File devInputDir = new File(imageFs.getRootDir(), "dev/input");
        if (devInputDir.exists() || devInputDir.mkdirs()) {
            for (int i = 0; i < 4; i++) {
                File eventFile = new File(devInputDir, "event" + i);
                if (eventFile.exists())
                    eventFile.delete();
            }
        }

        winHandler = new WinHandler(this);
        gyroInput = new GyroInput(this, new GyroInput.Output() {
            @Override public void moveMouse(int dx, int dy) {
                if (xServer == null) return;
                // In a game the camera reads Windows input (raw input / DirectInput, or the cursor
                // Wine moves itself); a moved X pointer is ignored once the game has grabbed it.
                // So: Windows input (like Relative mouse) while the game holds the mouse.
                int mode = gyroInput != null ? gyroInput.mouseMode : GyroInput.MOUSE_AUTO;
                boolean game = mode == GyroInput.MOUSE_GAME || (mode == GyroInput.MOUSE_AUTO
                        && (xServer.isRelativeMouseMovement() || xServer.grabManager.getWindow() != null));
                if (game && winHandler != null) winHandler.mouseEvent(MouseEventFlags.MOVE, dx, dy, 0);
                else xServer.injectPointerMoveDelta(dx, dy);
            }
            @Override public void stickChanged() { if (winHandler != null) winHandler.resendForGyro(); }
            @Override public boolean isButtonPressed(int index) {
                if (index >= GyroInput.BUTTON_KEY_BASE)
                    return xServer != null && xServer.keyboard.isKeyPressed((byte) (index - GyroInput.BUTTON_KEY_BASE));
                if (index >= GyroInput.BUTTON_MOUSE_BASE) {
                    if (xServer == null) return false;
                    Pointer.Button b = index == GyroInput.BUTTON_MOUSE_BASE ? Pointer.Button.BUTTON_LEFT
                            : index == GyroInput.BUTTON_MOUSE_BASE + 1 ? Pointer.Button.BUTTON_RIGHT : Pointer.Button.BUTTON_MIDDLE;
                    return xServer.pointer.isButtonPressed(b);
                }
                return winHandler != null && winHandler.isGamepadButtonPressed(index);
            }
        });
        winHandler.setGyro(gyroInput);
        winHandler.setFakeInputPath(devInputDir.getAbsolutePath());

        String screenSize = Container.DEFAULT_SCREEN_SIZE;
        containerManager = new ContainerManager(this);
        container = containerManager.getContainerById(getIntent().getIntExtra("container_id", 0));

        String shortcutPath = getIntent().getStringExtra("shortcut_path");
        Log.d("XServerDisplayActivity", "Shortcut Path: " + shortcutPath);

        int containerId = getIntent().getIntExtra("container_id", 0);
        Log.d("XServerDisplayActivity", "Container ID from Intent: " + containerId);
        if (containerId == 0) {
            Log.d("XServerDisplayActivity", "Container ID is 0, attempting to parse from .desktop file");

        }

        if (containerId == 0 && shortcutPath != null && !shortcutPath.isEmpty()) {
            File shortcutFile = new File(shortcutPath);
            containerId = com.winlator.cmod.session.DesktopFile.containerId(shortcutFile);
            Log.d("XServerDisplayActivity", "Parsed Container ID from .desktop file: " + containerId);
        }

        shortcutName = getIntent().getStringExtra("shortcut_name");

        if (shortcutPath != null && !shortcutPath.isEmpty()) {
            if (shortcutName == null || shortcutName.isEmpty()) {
                shortcutName = com.winlator.cmod.session.DesktopFile.name(new File(shortcutPath));
                Log.d("XServerDisplayActivity", "Parsed Shortcut Name from .desktop file: " + shortcutName);
            }
        } else {
            Log.d("XServerDisplayActivity", "No shortcut path provided, skipping shortcut parsing.");
        }

        playtime.setName(shortcutName);
        playtime.countLaunch();

        Log.d("XServerDisplayActivity", "Final Container ID: " + containerId);

        container = containerManager.getContainerById(containerId);

        if (container == null) {
            Log.e("XServerDisplayActivity", "Failed to retrieve container with ID: " + containerId);
            finish();
            return;
        }

        containerManager.activateContainer(container);

        if (shortcutPath != null && !shortcutPath.isEmpty()) {
            shortcut = new Shortcut(container, new File(shortcutPath));
        }

        // Bundle finder run: end the session by itself when YurnBench is done, crashed or hung.
        if (getIntent().getBooleanExtra(com.winlator.cmod.bench.Bench.EXTRA_BENCH, false)) {
            com.winlator.cmod.bench.Watchdog.start(this, container, () -> runOnUiThread(this::exit));
        }

        // Settings of this launch: the game's own where set, else the container's (session/LaunchConfig).
        launchConfig = com.winlator.cmod.session.LaunchConfig.resolve(container, shortcut);
        final com.winlator.cmod.session.LaunchConfig lc = launchConfig;

        cpuAffinity = com.winlator.cmod.session.CpuAffinity.of(lc, container);

        String wmClass = lc.getWmClass();
        Log.d("XServerDisplayActivity", "Startup wmClass: " + wmClass);

        firstTimeBoot = container.getExtra("imgVersion").isEmpty();

        String wineVersion = container.getWineVersion();
        wineInfo = WineInfo.fromIdentifier(this, contentsManager, wineVersion);

        imageFs.setWinePath(wineInfo.path);

        // Wayland display backend (experimental): needs a layer with winewayland.so.
        waylandMode = launchConfig.getWayland() && com.winlator.cmod.session.WaylandSupport.isLayerCapable(wineInfo.path);
        if (launchConfig.getWayland() && !waylandMode) {
            Log.w("XServerDisplayActivity", "wayland: layer " + wineInfo.path + " has no winewayland.so, launching on X11");
            runOnUiThread(() -> Toast.makeText(this, "This Proton has no Wayland driver: use a Bannerlator layer, e.g. Proton-11.0-2-arm64ec (v16) or GE-Proton-11.0-9 (v16). Starting on X11.", Toast.LENGTH_LONG).show());
        }

        ProcessHelper.removeAllDebugCallbacks();
        if (enableLogs) {
            LogView.setFilename(getExecutable());
            ProcessHelper.addDebugCallback(debugDialog = new DebugDialog(this));
            // Time marks in the log (it has no timestamps): where a slow start loses its time.
            final long logStart = android.os.SystemClock.elapsedRealtime();
            final long[] lastMark = {logStart};
            final DebugDialog log = debugDialog;
            ProcessHelper.addDebugCallback(line -> {
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - lastMark[0] < 5000) return;
                lastMark[0] = now;
                log.call("[YurNLator +" + ((now - logStart) / 1000) + "s]");
                String xo = com.winlator.cmod.xserver.OffscreenStats.drain();
                if (xo != null) log.call(xo);
            });
        }
        // Which API and layer the game uses (VKD3D · DX12, DXVK · DX11…): the HUD's first item.
        ProcessHelper.addDebugCallback(line -> {
            if (dxvkBuild == null && line != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("^info:\\s+DXVK:\\s+v?(\\S+)").matcher(line.trim());
                if (m.find()) dxvkBuild = m.group(1);
            }
        });
        ProcessHelper.addDebugCallback(new com.winlator.cmod.core.ApiSniffer(lc.getDxwrapperConfig(), label -> {
            actualApi = label;
            if (modernHud != null) modernHud.setApiLabel(label);
            if (classicHud != null) runOnUiThread(() -> classicHud.setApiLabel(label));
            return kotlin.Unit.INSTANCE;
        }));
        // Which driver the game really runs on (a wrapper may load its own): HUD + session info.
        ProcessHelper.addDebugCallback(new com.winlator.cmod.core.DriverSniffer(found -> {
            // Only a version number ("driver 26.99.99" from DXVK): name the configured driver.
            String label = found;
            String configured = graphicsDriverConfig != null ? graphicsDriverConfig.get("version") : null;
            if (found.startsWith("driver ") && configured != null && !configured.isEmpty() && !"System".equalsIgnoreCase(configured))
                label = configured + " (" + found + ")";
            actualDriver = label;
            if (modernHud != null) modernHud.setDriverLabel(label);
            return kotlin.Unit.INSTANCE;
        }));

        graphicsDriver = lc.getGraphicsDriver();
        String graphicsDriverConfig = lc.getGraphicsDriverConfig();
        audioDriver = lc.getAudioDriver();
        emulator = lc.getEmulator();
        midiSoundFont = container.getMIDISoundFont();
        dxwrapper = lc.getDxwrapper();
        String dxwrapperConfig = lc.getDxwrapperConfig();
        screenSize = lc.getScreenSize();
        winHandler.setInputType((byte) lc.getInputType());
        lc_all = lc.getLcAll();

        Intent intent = getIntent();
        Log.d("XServerDisplayActivity", "Intent Extras: " + intent.getExtras());

        if (shortcut != null) {
            xinputDisabledFromShortcut = lc.getXinputDisabled();
            winHandler.setXInputDisabled(xinputDisabledFromShortcut);
            if (!lc.getVkbasaltConfig().isEmpty()) vkbasaltConfig = lc.getVkbasaltConfig();
            Log.d("XServerDisplayActivity", "XInput Disabled from Shortcut: " + xinputDisabledFromShortcut);
            simulateTouchScreen = lc.getSimulateTouchScreen();
            isRelativeMouseMovement = lc.getRelativeMouse();
            isMouseDisabled = lc.getMouseDisabled();
        }

        this.graphicsDriverConfig = GraphicsDriverConfigDialog.parseGraphicsDriverConfig(graphicsDriverConfig);
        this.dxwrapperConfig = DXVKConfigDialog.parseConfig(dxwrapperConfig);

        if (!wineInfo.isWin64()) {
            onExtractFileListener = (file, size) -> {
                String path = file.getPath();
                if (path.contains("system32/"))
                    return null;
                return new File(path.replace("syswow64/", "system32/"));
            };
        }

        boolean removeLoadingBarWhenBootingGames = PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("remove_loading_bar_when_booting_games", false);
        if (!removeLoadingBarWhenBootingGames) preloaderDialog.show(R.string.starting_up);

        inputControlsManager = new InputControlsManager(this);
        boolean useDisplayX = isDisplayXEnabled();
        int surfaceFormat = useDisplayX && "rgba8".equals(getSelectedSurfaceFormat())
                ? Drawable.HAL_PIXEL_FORMAT_RGBA_8888
                : Drawable.HAL_PIXEL_FORMAT_BGRA_8888;
        xServer = new XServer(new ScreenInfo(screenSize), surfaceFormat);
        xServer.setWinHandler(winHandler);
        xServer.setRelativeMouseMovement(isRelativeMouseMovement);
        com.winlator.cmod.session.RefreshRate.advertise(this, xServer);

        boolean[] winStarted = { false };

        xServer.windowManager.addOnWindowModificationListener(new WindowManager.OnWindowModificationListener() {
            @Override
            public void onUpdateWindowContent(Window window) {
                if (!winStarted[0] && window.isApplicationWindow()) {
                    if (!simulateTouchScreen) {
                        xServerView.setCursorVisible(true);
                    }
                    preloaderDialog.closeOnUiThread();
                    winStarted[0] = true;
                }

                hudTracker.onWindowContent(window);
            }

            @Override
            public void onMapWindow(Window window) {
                cpuAffinity.apply(window, winHandler);
            }

            @Override
            public void onModifyWindowProperty(Window window, Property property) {
                hudTracker.onWindowProperty(window, property);
            }

            @Override
            public void onDestroyWindow(Window window) {
                hudTracker.onWindowProperty(window, null);
            }
        });

        if (!midiSoundFont.equals("")) {
            try {
                final InputStream in;
                if (midiSoundFont.equals(MidiManager.DEFAULT_SF2_FILE)) {
                    in = getAssets().open(MidiManager.SF2_ASSETS_DIR + "/" + midiSoundFont);
                } else {
                    in = null;
                }
                MidiManager.OnMidiLoadedCallback callback = new MidiManager.OnMidiLoadedCallback() {
                    @Override
                    public void onSuccess(SF2Soundbank soundbank) {
                        midiHandler = new MidiHandler();
                        midiHandler.setSoundBank(soundbank);
                        midiHandler.start();
                    }

                    @Override
                    public void onFailed(Exception e) {
                        if (in != null) {
                            try {
                                in.close();
                            } catch (Exception e2) {
                            }
                        }
                    }
                };
                if (midiSoundFont.equals(MidiManager.DEFAULT_SF2_FILE)) {
                    MidiManager.load(in, callback);
                } else {
                    MidiManager.load(new File(MidiManager.getSoundFontDir(this), midiSoundFont), callback);
                }
            } catch (Exception e) {
            }
        }

        String controlsProfile = shortcut != null ? shortcut.getExtra("controlsProfile", "") : "";

        createNotifcationChannel();

        Intent notificationIntent = new Intent(this, XServerDisplayActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE);
        // YurNLator: one notification only. When the app's keep-alive service already shows
        // its notification, the game screen doesn't post a second one.
        if (!com.winlator.cmod.services.NotificationService.isRunning()) {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_ab_gear_0011)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText(getString(R.string.app_name) + " is running, don't close this notification")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(false);

            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, builder.build());
        }

        Runnable runnable = () -> {
            setupUI();
            setupSidebarInputControls();
            if (controlsProfile.isEmpty()) {

                touchControls.restoreSaved(inputControlsManager.getProfiles());
            }
            Executors.newSingleThreadExecutor().execute(() -> {
                setupWineSystemFiles();
                extractGraphicsDriverFiles();
                changeWineAudioDriver();
                com.winlator.cmod.session.MediaDecoder.applyRegistry(
                        new File(imageFs.getRootDir(), ImageFs.WINEPREFIX + "/user.reg"), container, shortcut);
                com.winlator.cmod.session.RefreshRate.applyWineModeEmulation(imageFs, wineInfo);
                try {
                    setupXEnvironment();
                } catch (PackageManager.NameNotFoundException e) {
                    throw new RuntimeException(e);
                }
            });
        };

        if (xServer.screenInfo.height > xServer.screenInfo.width) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            configChangedCallback = runnable;
        } else
            runnable.run();
    }


    private boolean parseBoolean(String value) {

        if ("true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value)) {
            return true;
        }

        return false;
    }

    private void handleCapturedPointer(MotionEvent event) {
        com.winlator.cmod.session.CapturedMouse.handle(event, xServer, winHandler, xform);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MainActivity.EDIT_INPUT_CONTROLS_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (editInputControlsCallback != null) {
                editInputControlsCallback.run();
                editInputControlsCallback = null;
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        if (environment != null) {
            xServerView.onResume();
            environment.onResume();
        }
        if (playtime != null) playtime.resume();
        if (gyroInput != null) gyroInput.apply();
        if (!isInPictureInPictureMode())
            ProcessHelper.resumeAllWineProcesses();
    }

    @Override
    public void onPause() {
        if (taskManagerSidebar != null) taskManagerSidebar.stop();
        if (gyroInput != null) gyroInput.stop();
        quickMenuBridge.stopTasks();
        super.onPause();

        if (!isInPictureInPictureMode()) {
            if (environment != null) {
                environment.onPause();
                xServerView.onPause();
            }

            ProcessHelper.pauseAllWineProcesses();
        }

        if (playtime != null) playtime.pause();
    }

    void enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        int w = xServer.screenInfo.width;
        int h = xServer.screenInfo.height;
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(w, h));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && xServerView != null) {
            int[] loc = new int[2];
            xServerView.getLocationOnScreen(loc);
            Rect hint = new Rect(loc[0], loc[1],
                    loc[0] + xServerView.getWidth(), loc[1] + xServerView.getHeight());
            builder.setSourceRectHint(hint);
        }
        enterPictureInPictureMode(builder.build());
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode,
            Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        if (xServerView == null) return;
        xServerView.setPipMode(isInPictureInPictureMode);
        if (!isInPictureInPictureMode) {
            xServerView.post(() -> {
                if (xServerView == null) return;
                int w = xServerView.getWidth();
                int h = xServerView.getHeight();
                if (w > 0 && h > 0) xServerView.onSurfaceChanged(w, h);
            });
            // With the touch controls on, the SurfaceView's surface sometimes stays where the PiP
            // window animation left it: the picture shows shifted right and cut, the HUD and the
            // controls (normal views) are fine. Hiding and showing the view makes Android place
            // the surface again (the renderer re-attaches it as after a pause).
            xServerView.postDelayed(() -> {
                if (xServerView == null || isInPictureInPictureMode()) return;
                xServerView.setVisibility(View.INVISIBLE);
                xServerView.post(() -> {
                    if (xServerView == null) return;
                    xServerView.setVisibility(View.VISIBLE);
                    xServerView.requestLayout();
                });
            }, 400);
        }
    }



    void exit() {
        com.winlator.cmod.core.RendererDriverGuard.disarm(this);
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID);
        boolean removeLoadingBar = PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("remove_loading_bar_when_booting_games", false);
        if (!removeLoadingBar) preloaderDialog.showOnUiThread(R.string.shutdown);

        if (xServerView != null) {
            xServerView.forceCleanup();
            xServerView.setVisibility(View.GONE);
        }

        if (playtime != null) playtime.pause();

        if (midiHandler != null)
            midiHandler.stop();

        if (environment != null)
            environment.stopEnvironmentComponents();
        if (winHandler != null)
            winHandler.stop();
        if (wineRequestHandler != null)
            wineRequestHandler.stop();

        com.winlator.cmod.session.WineShutdown.terminateAll(() -> {
            preloaderDialog.closeOnUiThread();
            runOnUiThread(this::finish);
        });
    }

    @Override
    protected void onDestroy() {
        com.winlator.cmod.core.RendererDriverGuard.disarm(this);
        if (waylandSession != null) { waylandSession.stop(); waylandSession = null; }
        if (taskManagerSidebar != null) taskManagerSidebar.stop();
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (playtime != null) playtime.pause();
    }

    @Override
    public void onBackPressed() {
        if (environment != null) {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.openDrawer(GravityCompat.START);
            } else
                drawerLayout.closeDrawers();
        }
    }

    void showVibrationDialog() { com.winlator.cmod.ui.ingame.InputDispatch.showVibrationDialog(this, winHandler); }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus && cursorLock)
            touchpadView.requestPointerCapture();
        else if (!hasFocus)
            touchpadView.releasePointerCapture();
    }

    /**
     * Wayland: the compositor goes right above the X server's view. It imports the game's frames with
     * the container's Mesa driver (Turnip / PanVK); System works on Mali only (experimental).
     */
    /**
     * Wayland: a window shows on the compositor only once it presents its first frame, so the
     * loading screen stayed up through the game's own loading (34.8 s vs 5 s on X11 for NFS
     * Payback). X11 closes it when the game's window first gets content, which is right after the
     * game starts: here it closes when the game's process is running.
     */
    /** The compositor's frame-generation rates for the HUD, only while frame generation is on
     *  (its presented rate counts every screen update, so with it off the HUD showed a pair). */
    private float[] waylandFrameGenStats() {
        if (!frameGen().on()) return null;
        try { return com.winlator.cmod.wayland.WaylandCompositor.nativeFrameGenStats(); }
        catch (Throwable t) { return null; }
    }


    private void startWaylandSession(FrameLayout rootView) {
        // The compositor's driver and switches: session/WaylandLaunch.
        com.winlator.cmod.session.WaylandLaunch.Driver driver = com.winlator.cmod.session.WaylandLaunch.driver(
                this, graphicsDriverConfig != null ? graphicsDriverConfig.get("version") : null);
        com.winlator.cmod.session.WaylandLaunch.applySwitches(container, shortcut);
        waylandSession = new com.winlator.cmod.session.WaylandSession(this, xServer);
        waylandSession.start(rootView, rootView.indexOfChild(xServerView) + 1, driver.path, driver.library,
                launchConfig != null && launchConfig.getFullscreenStretched(),
                shortcut != null,
                new com.winlator.cmod.session.WaylandSession.Listener() {
            @Override public void onFirstFrame() { preloaderDialog.closeOnUiThread(); }
            private boolean shown = false;
            @Override public void onGameFrame() {
                // Also a game frame closes the loading screen (in case the compositor's first-frame signal is missed).
                if (!shown) { shown = true; preloaderDialog.closeOnUiThread(); }
                if (modernHud != null) modernHud.onFrame();
                if (classicHud != null) classicHud.update();
            }
            @Override public void onGameSurface(String window, String gpuName) {
                // The game's window is up: close the loading screen as X11 does (at the window's first
                // content), not only at its first rendered frame, which kept it up through the
                // game's own loading.
                if (window != null) preloaderDialog.closeOnUiThread();
                if (window != null && gpuName != null && modernHud != null) {
                    modernHud.setGpuName(gpuName);
                    runOnUiThread(() -> modernHud.onRendererDetected("Wayland"));
                }
            }
        });
    }

    /** Gets the prefix ready for this launch: session/PrefixSetup (YurNLator base, part 5). */
    private void setupWineSystemFiles() {
        startupSelection = launchConfig.getStartupSelection();
        String wrapperId = DxWrapperFiles.wrapperId(this.dxwrapper, dxwrapperConfig.get("version"),
                dxwrapperConfig.get("vkd3dVersion"), dxwrapperConfig.get("ddrawrapper"));
        new com.winlator.cmod.session.PrefixSetup(this, imageFs, wineInfo, container, launchConfig, xServer.screenInfo,
                waylandMode, firstTimeBoot, onExtractFileListener, dxWrapperFiles()).run(wrapperId, envVars);
    }

    private void setupXEnvironment() throws PackageManager.NameNotFoundException {

        // Environment and command of the launch: session/LaunchEnv (YurNLator base, part 6).
        com.winlator.cmod.session.LaunchEnv.base(envVars, preferences, lc_all, imageFs.wineprefix);

        FileUtils.clear(imageFs.getTmpDir());

        guestProgramLauncherComponent = new GuestProgramLauncherComponent(
                contentsManager,
                contentsManager.getProfileByEntryName(container.getWineVersion()),
                shortcut);

        if (container != null) {
            guestProgramLauncherComponent.setContainer(this.container);
            guestProgramLauncherComponent.setWineInfo(this.wineInfo);

            String guestExecutable = com.winlator.cmod.session.LaunchEnv.guestExecutable(xServer.screenInfo,
                    com.winlator.cmod.session.LaunchEnv.startCommand(shortcut, getOverrideEnvVars()));

            guestProgramLauncherComponent.setGuestExecutable(guestExecutable);
            // First line of the log: what was launched (and the shortcut's path), so a game
            // that never starts shows why (wrong folder, mangled path...).
            if (debugDialog != null) {
                String ver = "";
                try { ver = " " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
                debugDialog.call("[YurNLator" + ver + "] launch: " + guestExecutable);
                if (shortcut != null) debugDialog.call("[YurNLator] shortcut path: " + shortcut.path);
            }

            // Game-specific fixes in the game's own files (TLOU's address-space reservation).
            com.winlator.cmod.session.GameFixes.apply(this, container, shortcut, envVars,
                    line -> { if (debugDialog != null) debugDialog.call(line); });
            com.winlator.cmod.session.LaunchEnv.user(envVars, container, shortcut);
            com.winlator.cmod.session.OpenGLEnv.apply(envVars, graphicsDriver, container, shortcut);
            com.winlator.cmod.session.LaunchEnv.defaults(envVars, container, shortcut, cpuAffinity.wineCpuTopology());

            ArrayList<String> bindingPaths = new ArrayList<>();
            for (String[] drive : container.drivesIterator()) {
                bindingPaths.add(drive[1]);
            }

            guestProgramLauncherComponent.setBindingPaths(bindingPaths.toArray(new String[0]));

            guestProgramLauncherComponent.setBox64Preset(launchConfig.getBox64Preset());

            guestProgramLauncherComponent.setFEXCorePreset(launchConfig.getFexcorePreset());
            guestProgramLauncherComponent.setWaylandRuntimeDir(waylandMode
                    ? com.winlator.cmod.session.WaylandSession.runtimeDir(this).getPath() : null);
        }

        if (overrideEnvVars != null) {
            envVars.putAll(overrideEnvVars);
            overrideEnvVars.clear();
        }

        // SysV shm, X server and audio servers: session/XEnvironmentSetup.
        environment = com.winlator.cmod.session.XEnvironmentSetup.create(this, imageFs, xServer, audioDriver, envVars);

        guestProgramLauncherComponent.setEnvVars(envVars);
        guestProgramLauncherComponent.setTerminationCallback((status) -> runOnUiThread(this::exit));

        environment.addComponent(guestProgramLauncherComponent);

        new File(imageFs.getRootDir(), "dev/input").mkdirs();

        environment.startEnvironmentComponents();

        winHandler.start();

        if (wineRequestHandler != null)
            wineRequestHandler.start();

        dxwrapperConfig = null;

    }

    private void createWrapperScript(String path, String content) {
        File scriptFile = new File(path);
        FileUtils.writeString(scriptFile, content);
        scriptFile.setExecutable(true);
    }

    private boolean isDisplayXEnabled() {
        if (shortcut != null) return shortcut.getUseDisplayX();
        return container != null && container.getUseDisplayX();
    }

    private String getSelectedSurfaceFormat() {
        if (shortcut != null) return shortcut.getSurfaceFormat();
        return container != null ? container.getSurfaceFormat() : "rgba8";
    }

    private void setupUI() {
        FrameLayout rootView = findViewById(R.id.FLXServerDisplay);

        boolean useDisplayX = isDisplayXEnabled();

        boolean useEGL = !useDisplayX && (shortcut != null ? shortcut.getRendererNative()
                : (container != null && container.getRendererNative()));

        if (useDisplayX) {
            DisplayXServerView.loadNativeLibrary();
            boolean performanceMode = shortcut != null
                    ? shortcut.getDisplayXPerformanceMode()
                    : container == null || container.getDisplayXPerformanceMode();
            boolean presentAtRefreshRate = shortcut != null
                    ? shortcut.getDisplayXPresentAtRefreshRate()
                    : container == null || container.getDisplayXPresentAtRefreshRate();
            xServerView = new DisplayXServerView(
                    this,
                    xServer,
                    performanceMode,
                    presentAtRefreshRate);
        } else if (useEGL) {
            EGLXServerView.loadNativeLibrary();
            xServerView = new EGLXServerView(this, xServer);
        } else {
            VulkanXServerView.loadNativeLibrary();
            xServerView = new VulkanXServerView(this, xServer);
        }
        final XServerRendererView renderer = xServerView;
        renderer.setCursorVisible(false);

        if (renderer instanceof VulkanXServerView) {
            VulkanXServerView vkRenderer = (VulkanXServerView) renderer;

            // The driver the renderer loads into the app: session/RendererDriver.
            com.winlator.cmod.session.RendererDriver.apply(this, vkRenderer, container, shortcut,
                    graphicsDriverConfig != null ? graphicsDriverConfig.get("version") : null);

            String presentMode = shortcut != null ? shortcut.getRendererPresentMode()
                    : (container != null ? container.getRendererPresentMode() : "fifo");
            vkRenderer.setVkPresentMode(com.winlator.cmod.contentdialog.RendererOptionsDialog.toVkPresentMode(presentMode));
            vkRenderer.setFilterMode(shortcut != null ? shortcut.getRendererFilterMode()
                    : (container != null ? container.getRendererFilterMode() : 0));
        } else if (renderer instanceof EGLXServerView) {
            EGLXServerView eglRenderer = (EGLXServerView) renderer;
            eglRenderer.setFilterMode(shortcut != null ? shortcut.getRendererFilterMode()
                    : (container != null ? container.getRendererFilterMode() : 0));
        }

        if (shortcut != null) {
            renderer.setUnviewableWMClasses("explorer.exe");
        }

        rootView.addView(xServerView);
        if (waylandMode) {
            startWaylandSession(rootView);
            com.winlator.cmod.session.LoadingScreen.closeWhenGameRuns(this, shortcut, preloaderDialog);
        }

        globalCursorSpeed = preferences.getFloat("cursor_speed", 1.0f);
        touchpadView = new TouchpadView(this, xServer, timeoutHandler, hideControlsRunnable);
        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setMouseEnabled(!isMouseDisabled);
        touchpadView.setFourFingersTapCallback(() -> {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START))
                drawerLayout.openDrawer(GravityCompat.START);
        });
        View.OnCapturedPointerListener capturedPointerListener = new View.OnCapturedPointerListener() {
            @Override
            public boolean onCapturedPointer(View view, MotionEvent event) {
                handleCapturedPointer(event);
                return true;
            }
        };
        touchpadView.setOnCapturedPointerListener(cursorLock ? capturedPointerListener : null);
        touchpadView.setFocusable(true);
        touchpadView.setFocusableInTouchMode(true);
        rootView.addView(touchpadView);

        inputControlsView = new InputControlsView(this, timeoutHandler, hideControlsRunnable);
        inputControlsView
                .setOverlayOpacity(preferences.getFloat("overlay_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY));
        inputControlsView.setTouchpadView(touchpadView);
        inputControlsView.setXServer(xServer);
        inputControlsView.setVisibility(View.GONE);
        rootView.addView(inputControlsView);

        boolean isTimeoutEnabled = preferences.getBoolean("touchscreen_timeout_enabled", false);
        if (isTimeoutEnabled) {
            startTouchscreenTimeout();
        }

        if (container != null) {
            String hudModeExtra = container.getExtra("hudMode");
            int hudMode = !hudModeExtra.isEmpty()
                    ? Integer.parseInt(hudModeExtra)
                    : (container.isShowFPS() ? 1 : 0);

            if (hudMode == 1) {

                classicHud = new FrameRating(this, graphicsDriverConfig);
                if (xServerView instanceof VulkanXServerView && !waylandMode) classicHud.setPresentCounter(((VulkanXServerView) xServerView)::getPresentStats);
                classicHud.setVisibility(View.GONE);
                rootView.addView(classicHud);
                renderer.setFrameRating(classicHud);
            } else if (hudMode == 2) {

                modernHud = new WinlatorHUD(this);
                if (xServerView instanceof VulkanXServerView && !waylandMode) modernHud.setPresentCounter(((VulkanXServerView) xServerView)::getPresentStats);

                if (waylandMode) modernHud.setCompositorFrameGen(this::waylandFrameGenStats);
                modernHud.setVisibility(View.GONE);
                rootView.addView(modernHud);
                modernHud.enableByUser();
                renderer.setFrameRating(modernHud);
            }
        }

        String shortcutFullscreenStretched = shortcut != null ? shortcut.getExtra("fullscreenStretched") : null;

        boolean shouldStretch = false;

        if (shortcut != null && shortcutFullscreenStretched != null) {

            shouldStretch = shortcutFullscreenStretched.equals("1");
        } else if (container != null && container.isFullscreenStretched()) {

            shouldStretch = true;
        }

        if (shouldStretch) {

            scaling.toggleFullscreen();
        }

        if (shortcut != null) {
            String controlsProfile = shortcut.getExtra("controlsProfile");
            if (!controlsProfile.isEmpty()) {
                ControlsProfile profile = inputControlsManager.getProfile(Integer.parseInt(controlsProfile));
                if (profile != null)
                    showInputControls(profile);
            }

            String simTouchScreen = shortcut.getExtra("simTouchScreen");
            touchpadView.setSimTouchScreen(simTouchScreen.equals("1"));
            if (simulateTouchScreen) {
                renderer.setCursorVisible(false);
            }
        }

        AppUtils.observeSoftKeyboardVisibility(drawerLayout, renderer::setScreenOffsetYRelativeToCursor);

        setupSidebarHudControls();
        setupSidebarGraphicsControls();
        quickMenuBridge.applySaved();
        applyFrameGen();
        // Every renderer: direct (DRI3) frames tell the HUD which window is the game's.
        xServerView.setDirectFrameListener(hudTracker::onDirectFrame);
        // "Swap red/blue channels" (container / game setting) was saved but never applied.
        // "Color order: BGRA" (surface format) or "Swap red/blue": both swap the channels on
        // Vulkan / Native; before, the surface format only changed anything with DisplayX.
        boolean swapRB = container != null && ((shortcut != null ? shortcut.getRendererSwapRB() : container.getRendererSwapRB())
                || (!isDisplayXEnabled() && "bgra8".equals(getSelectedSurfaceFormat())));
        if (xServerView instanceof VulkanXServerView) ((VulkanXServerView) xServerView).setSwapRB(swapRB);
        else if (xServerView instanceof EGLXServerView) ((EGLXServerView) xServerView).setSwapRB(swapRB);
    }

    ActivityResultLauncher<Intent> controlsEditorActivityResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (editInputControlsCallback != null) {
                    editInputControlsCallback.run();
                    editInputControlsCallback = null;
                }
            });



    private ArrayAdapter<String> createSidebarSpinnerAdapter(String[] items) {
        return new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(Color.parseColor("#EEF7FF"));
                view.setTextSize(14);
                view.setSingleLine(true);
                return view;
            }

            @Override
            public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextColor(Color.parseColor("#EEF7FF"));
                view.setBackgroundColor(Color.parseColor("#0E2231"));
                view.setTextSize(14);
                return view;
            }
        };
    }

    private void setupSidebarHudControls() {
        com.winlator.cmod.ui.ingame.SidebarHudPanel.setup(new com.winlator.cmod.ui.ingame.SidebarHudPanel.Host() {
            @Override public android.app.Activity activity() { return XServerDisplayActivity.this; }
            @Override public WinlatorHUD modernHud() { return modernHud; }
            @Override public FrameRating classicHud() { return classicHud; }
            @Override public int savedMode() {
                if (container == null) return 0;
                String extra = container.getExtra("hudMode");
                if (!extra.isEmpty()) try { return Integer.parseInt(extra.trim()); } catch (NumberFormatException ignored) {}
                return container.isShowFPS() ? 1 : 0;
            }
            @Override public void enable(int style) { enableHudLazily(style); }
            @Override public void saveMode(int mode) { saveHudModeToContainer(mode); }
            @Override public ArrayAdapter<String> spinnerAdapter(String[] items) { return createSidebarSpinnerAdapter(items); }
        });
    }

    void enableHudLazily(int style) {
        FrameLayout rootView = findViewById(R.id.FLXServerDisplay);
        if (rootView == null || xServerView == null) return;
        final XServerRendererView renderer = xServerView;


        if (style == 2) {
            if (modernHud == null) {
                modernHud = new WinlatorHUD(this);
                if (xServerView instanceof VulkanXServerView && !waylandMode) modernHud.setPresentCounter(((VulkanXServerView) xServerView)::getPresentStats);

                if (waylandMode) modernHud.setCompositorFrameGen(this::waylandFrameGenStats);
                modernHud.setVisibility(View.GONE);
                rootView.addView(modernHud);
                renderer.setFrameRating(modernHud);
                if (hudTracker.adoptRendererWindow()) modernHud.onRendererDetected(hudTracker.rendererName());
            }
            modernHud.enableByUser();
        } else {
            if (classicHud == null) {
                classicHud = new FrameRating(this, graphicsDriverConfig);
                if (xServerView instanceof VulkanXServerView && !waylandMode) classicHud.setPresentCounter(((VulkanXServerView) xServerView)::getPresentStats);
                classicHud.setVisibility(View.GONE);
                rootView.addView(classicHud);
                renderer.setFrameRating(classicHud);
                if (hudTracker.adoptRendererWindow()) runOnUiThread(() -> classicHud.update());
            }
            classicHud.enableByUser();
        }
    }

    void saveHudModeToContainer(int mode) {
        if (container == null) return;
        container.putExtra("hudMode", String.valueOf(mode));
        container.setShowFPS(mode != 0);
        container.saveData();
    }

    private void setupSidebarGraphicsControls() {
        com.winlator.cmod.ui.ingame.SidebarGraphicsPanel.setup(new com.winlator.cmod.ui.ingame.SidebarGraphicsPanel.Host() {
            @Override public android.app.Activity activity() { return XServerDisplayActivity.this; }
            @Override public XServerRendererView renderer() { return xServerView; }
            @Override public Container container() { return container; }
            @Override public Shortcut shortcut() { return shortcut; }
            @Override public void applyFpsLimit(int fps) { XServerDisplayActivity.this.applyFpsLimit(fps); }
            @Override public ArrayAdapter<String> spinnerAdapter(String[] items) { return createSidebarSpinnerAdapter(items); }
        });
    }

    void setupSidebarInputControls() {
        com.winlator.cmod.ui.ingame.SidebarInputPanel.setup(new com.winlator.cmod.ui.ingame.SidebarInputPanel.Host() {
            @Override public android.app.Activity activity() { return XServerDisplayActivity.this; }
            @Override public InputControlsView controls() { return inputControlsView; }
            @Override public InputControlsManager manager() { return inputControlsManager; }
            @Override public SharedPreferences prefs() { return preferences; }
            @Override public com.winlator.cmod.ui.ingame.TouchControls touch() { return touchControls; }
            @Override public ArrayAdapter<String> spinnerAdapter(String[] items) { return createSidebarSpinnerAdapter(items); }
            @Override public void openEditor(int profileId, Runnable onBack) {
                Intent intent = new Intent(XServerDisplayActivity.this, MainActivity.class);
                intent.putExtra("edit_input_controls", true);
                intent.putExtra("selected_profile_id", profileId);
                editInputControlsCallback = onBack;
                controlsEditorActivityResultLauncher.launch(intent);
            }
            @Override public void clearTouchpadListener() { if (touchpadView != null) touchpadView.setOnTouchListener(null); }
        });
    }



    void startTouchscreenTimeout() { touchControls.startTimeout(); }

    void showInputControls(ControlsProfile profile) { touchControls.show(profile); }

    void hideInputControls() { touchControls.hide(); }

    /** Graphics settings, driver and runtime files of the launch: session/GraphicsSetup. */
    private void extractGraphicsDriverFiles() {
        com.winlator.cmod.session.GraphicsSetup.prepare(this, preferences, imageFs, envVars, container, graphicsDriver,
                graphicsDriverConfig, dxwrapper, dxwrapperConfig, vkbasaltConfig, firstTimeBoot, waylandMode,
                wineInfo.path, xServer.getSurfaceFormat() == Drawable.HAL_PIXEL_FORMAT_RGBA_8888);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        // Gamepads and mice: ui/ingame/InputDispatch.
        boolean ours = com.winlator.cmod.ui.ingame.InputDispatch.motion(event, winHandler, touchpadView);
        return super.dispatchGenericMotionEvent(event) || ours;
    }

    private static final int RECAPTURE_DELAY_MS = 10000;

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Keys: ui/ingame/InputDispatch (null = this activity's own dispatch).
        Boolean r = com.winlator.cmod.ui.ingame.InputDispatch.key(event, drawerLayout, inputControlsView, winHandler, xServer);
        return r != null ? r : super.dispatchKeyEvent(event);
    }

    /** The host FPS limit: X11 paces the game's Present idles, Wayland its buffer releases. */
    public void applyFpsLimit(int fps) {
        if (xServerView != null) xServerView.setFpsLimit(fps);
        if (waylandMode) com.winlator.cmod.session.FpsLimit.applyWayland(fps);
    }

    public Shortcut getShortcut() { return shortcut; }

    public InputControlsView getInputControlsView() {
        return inputControlsView;
    }

    private static final String TAG = "DXWrapperExtraction";

    private DxWrapperFiles dxWrapperFiles() {
        return new DxWrapperFiles(this, imageFs, wineInfo, contentsManager, onExtractFileListener);
    }


    private void restoreOriginalDllFiles(final String... dlls) {
        dxWrapperFiles().restoreOriginal(dlls);
    }


    private String getExecutable() {
        String filename = "wfm.exe";
        if (shortcut != null && shortcut.path != null) {
            String cleanPath = shortcut.path.replace("\"", "");
            int lastSlash = cleanPath.lastIndexOf('/');
            int lastBackslash = cleanPath.lastIndexOf('\\');
            int lastSeparator = Math.max(lastSlash, lastBackslash);
            if (lastSeparator != -1) {
                filename = cleanPath.substring(lastSeparator + 1);
            } else {
                filename = cleanPath;
            }
        }
        return filename;
    }

    public XServer getXServer() {
        return xServer;
    }

    public WinHandler getWinHandler() {
        return winHandler;
    }

    public XServerRendererView getXServerView() {
        return xServerView;
    }

    public Container getContainer() {
        return container;
    }

    public void setDXWrapper(String dxwrapper) {
        this.dxwrapper = dxwrapper;
    }

    public EnvVars getOverrideEnvVars() {
        if (overrideEnvVars == null) {
            overrideEnvVars = new EnvVars();
        }
        return overrideEnvVars;
    }

    private void changeWineAudioDriver() {
        if (!audioDriver.equals(container.getExtra("audioDriver"))) {
            com.winlator.cmod.session.WinComponentFiles.applyAudioDriver(imageFs, audioDriver);
            container.putExtra("audioDriver", audioDriver);
            container.saveData();
        }
    }




    public String getScreenEffectProfile() {
        return screenEffectProfile;
    }

    public void setScreenEffectProfile(String screenEffectProfile) {
        this.screenEffectProfile = screenEffectProfile;
    }


    // ---------------------------------------------------------------------------------------
    // YurNLator quick menu (ui/quickmenu): the Compose side menu reads and changes the session
    // through this bridge.
    // ---------------------------------------------------------------------------------------
    boolean quickMenuLogs = false;

    /** Frame generation setting: the game's own value first, then the container's. */
    private com.winlator.cmod.session.FrameGenSession frameGen() {
        return new com.winlator.cmod.session.FrameGenSession(shortcut, container);
    }

    String frameGenSetting(String key, String fallback) {
        return frameGen().setting(key, fallback);
    }

    /** Frame generation runs in the host Vulkan renderer (win-fg or LSFG), switched live. */
    void applyFrameGen() {
        if (waylandMode) {
            com.winlator.cmod.session.FrameGenSession fg = frameGen();
            fg.applyWayland(this);
            if (fg.on()) com.winlator.cmod.session.FrameGenSession.reportWaylandProblem(this,
                    line -> { if (debugDialog != null) debugDialog.call(line); });
            return;
        }
        if (xServerView instanceof VulkanXServerView) frameGen().apply(this, (VulkanXServerView) xServerView);
    }

    String frameGenEngine() {
        return frameGen().engine(this);
    }

    GyroInput gyroInput;
    final XServerQuickMenuBridge quickMenuBridge = new XServerQuickMenuBridge(this);
    final XServerSidebar sidebar = new XServerSidebar(this);


}
