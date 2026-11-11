package com.winlator.cmod;

import com.winlator.cmod.ui.quickmenu.QuickMenuHost;
import com.winlator.cmod.winhandler.GyroInput;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.DXVKConfigDialog;
import com.winlator.cmod.contentdialog.DebugDialog;
import com.winlator.cmod.contentdialog.GraphicsDriverConfigDialog;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.session.DxWrapperFiles;
import com.winlator.cmod.core.OnExtractFileListener;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.core.WineRequestHandler;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.InputControlsManager;
import com.winlator.cmod.math.XForm;
import com.winlator.cmod.midi.MidiHandler;
import com.winlator.cmod.widget.FrameRating;
import com.winlator.cmod.widget.WinlatorHUD;
import com.winlator.cmod.widget.InputControlsView;
import com.winlator.cmod.widget.TouchpadView;
import com.winlator.cmod.widget.XServerRendererView;
import com.winlator.cmod.widget.VulkanXServerView;
import com.winlator.cmod.winhandler.MouseEventFlags;
import com.winlator.cmod.winhandler.TaskManagerSidebar;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.cmod.xenvironment.XEnvironment;
import com.winlator.cmod.xenvironment.components.GuestProgramLauncherComponent;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.Property;
import com.winlator.cmod.xserver.ScreenInfo;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.WindowManager;
import com.winlator.cmod.xserver.XServer;

import java.io.File;
import java.util.HashMap;
import java.util.concurrent.Executors;

public class XServerDisplayActivity extends AppCompatActivity {

    public static String NOTIFICATION_CHANNEL_ID = "Winlator";
    public static int NOTIFICATION_ID = -1;
    XServerRendererView xServerView;
    InputControlsView inputControlsView;
    TouchpadView touchpadView;
    XEnvironment environment;
    DrawerLayout drawerLayout;
    private ContainerManager containerManager;
    protected Container container;
    XServer xServer;
    InputControlsManager inputControlsManager;
    ImageFs imageFs;
    /** The performance HUD: style, our HUD and Bannerlator's styles (session/HudController). */
    final com.winlator.cmod.session.HudController hud = new com.winlator.cmod.session.HudController(
            new com.winlator.cmod.session.HudController.Host() {
                @Override public android.app.Activity activity() { return XServerDisplayActivity.this; }
                @Override public Container container() { return container; }
                @Override public Shortcut shortcut() { return shortcut; }
                @Override public FrameLayout root() { return findViewById(R.id.FLXServerDisplay); }
                @Override public XServerRendererView renderer() { return xServerView; }
                @Override public boolean wayland() { return waylandMode; }
                @Override public java.util.function.Supplier<float[]> compositorFrameGen() { return XServerDisplayActivity.this::waylandFrameGenStats; }
                @Override public com.winlator.cmod.session.HudWindowTracker tracker() { return hudTracker; }
                @Override public File imageFsRoot() { return imageFs.getRootDir(); }
                @Override public boolean mangoHudLog() { return preferences.getBoolean("mangohud_log", false); }
                @Override public boolean mangoHudPreload() {
                    // The game's own "Keep MangoHud ready", else the container's (settings of both).
                    String own = shortcut != null ? shortcut.getExtra("mangohudPreload", "") : "";
                    if (!own.isEmpty()) return own.equals("1");
                    return container != null && container.getExtra("mangohudPreload", "0").equals("1");
                }
            });
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
    String dxwrapper = Container.DEFAULT_DXWRAPPER;
    private KeyValueSet dxwrapperConfig;
    WineInfo wineInfo;
    private final EnvVars envVars = new EnvVars();
    private boolean firstTimeBoot = false;
    SharedPreferences preferences;
    private OnExtractFileListener onExtractFileListener;
    WinHandler winHandler;
    /** Performance controls of this game (session/PerfSession). */
    com.winlator.cmod.session.PerfSession perf;
    TaskManagerSidebar taskManagerSidebar;
    WineRequestHandler wineRequestHandler;
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
                @Override public WinlatorHUD modernHud() { return hud.modern(); }
                @Override public FrameRating classicHud() { return null; }
                @Override public XServerRendererView renderer() { return xServerView; }
                @Override public void runOnUi(Runnable r) { runOnUiThread(r); }
            });
    boolean cursorLock;
    private final float[] xform = XForm.getInstance();
    private ContentsManager contentsManager;
    MidiHandler midiHandler;
    private String midiSoundFont = "";
    private String lc_all = "";
    private String vkbasaltConfig = "";
    PreloaderDialog preloaderDialog = null;
    private Runnable configChangedCallback = null;
    boolean isPaused = false;
    boolean isRelativeMouseMovement = false;
    boolean isMouseDisabled = false;
    private boolean simulateTouchScreen = false;

    /** Play time and launch count of the game (session/Playtime). */
    com.winlator.cmod.session.Playtime playtime;

    private Handler timeoutHandler = new Handler(Looper.getMainLooper());
    com.winlator.cmod.ui.ingame.TouchControls touchControls;

    private GuestProgramLauncherComponent guestProgramLauncherComponent;
    private EnvVars overrideEnvVars;

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
        // YurNLator base, part 9: the start of the game screen in named stages.
        setupWindow();
        setupScreenHelpers();
        setupDrawer();
        boolean enableLogs = preferences.getBoolean("enable_wine_debug", false)
                || preferences.getBoolean("enable_box64_logs", false);
        sidebar.wire(enableLogs);
        quickMenuLogs = enableLogs;
        androidx.compose.ui.platform.ComposeView quickMenuView = findViewById(R.id.QuickMenuCompose);
        if (quickMenuView != null) QuickMenuHost.attach(quickMenuView, quickMenuBridge);

        imageFs = ImageFs.find(this);
        File devInputDir = resetInputDevices();
        setupInput(devInputDir);

        if (!resolveContainerAndShortcut()) return;

        // Settings of this launch: the game's own where set, else the container's (session/LaunchConfig).
        launchConfig = com.winlator.cmod.session.LaunchConfig.resolve(container, shortcut);
        final com.winlator.cmod.session.LaunchConfig lc = launchConfig;

        cpuAffinity = com.winlator.cmod.session.CpuAffinity.of(lc, container);
        // Performance controls (quick menu › Performance): big cores go into the affinity now,
        // the rest applies once the game starts; all of it is put back at exit.
        perf = new com.winlator.cmod.session.PerfSession(this, container, shortcut, () -> cpuAffinity, () -> winHandler);
        perf.prepareLaunch();

        resolveWine(lc);
        setupLogCallbacks(enableLogs, lc);
        applyLaunchConfig(lc);
        boolean removeLoadingBarWhenBootingGames = PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("remove_loading_bar_when_booting_games", false);
        if (!removeLoadingBarWhenBootingGames) preloaderDialog.show(R.string.starting_up);

        inputControlsManager = new InputControlsManager(this);
        createXServer(lc.getScreenSize());
        // MIDI sound font and the "running" notification: session/SessionServices.
        com.winlator.cmod.session.SessionServices.loadMidi(this, midiSoundFont, h -> midiHandler = h);
        com.winlator.cmod.session.SessionServices.postRunningNotification(this, NOTIFICATION_CHANNEL_ID, NOTIFICATION_ID);

        String controlsProfile = shortcut != null ? shortcut.getExtra("controlsProfile", "") : "";

        Runnable runnable = () -> {
            setupUI();
            sidebar.setupInput();
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

    /** Stage 1: full screen, screen kept on, the display's highest refresh rate, the layout. */
    private void setupWindow() {
        AppUtils.hideSystemUI(this);
        AppUtils.keepScreenOn(this);

        android.view.WindowManager.LayoutParams params = getWindow().getAttributes();
        params.preferredRefreshRate = com.winlator.cmod.session.RefreshRate.highest(this);
        getWindow().setAttributes(params);

        setContentView(R.layout.xserver_display_activity);
    }

    /** Stage 2: loading screen, preferences, playtime, on-screen controls, installed contents. */
    private void setupScreenHelpers() {
        preloaderDialog = new PreloaderDialog(this);
        preferences = PreferenceManager.getDefaultSharedPreferences(this);

        cursorLock = preferences.getBoolean("cursor_lock", true);

        playtime = new com.winlator.cmod.session.Playtime(this);
        playtime.resume();

        // On-screen controls: ui/ingame/TouchControls (YurNLator base, part 10).
        touchControls = new com.winlator.cmod.ui.ingame.TouchControls(preferences, timeoutHandler,
                () -> inputControlsView, () -> touchpadView, () -> winHandler, () -> globalCursorSpeed);

        contentsManager = new ContentsManager(this);
        contentsManager.syncContents();
    }

    /** Stage 3: the side drawer (quick menu), locked closed until opened by gesture / menu button. */
    private void setupDrawer() {
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
    }

    /** Controller discovery nodes start empty: a pad's event file exists only while it holds a slot. */
    private File resetInputDevices() {
        File devInputDir = new File(imageFs.getRootDir(), "dev/input");
        if (devInputDir.exists() || devInputDir.mkdirs()) {
            for (int i = 0; i < 4; i++) {
                File eventFile = new File(devInputDir, "event" + i);
                if (eventFile.exists())
                    eventFile.delete();
            }
        }
        return devInputDir;
    }

    /** Stage 4: Windows-side input (WinHandler) and the gyroscope. */
    private void setupInput(File devInputDir) {
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
    }

    /** Stage 5: the container and the game's shortcut from the intent (false: no container, closing). */
    private boolean resolveContainerAndShortcut() {
        containerManager = new ContainerManager(this);
        com.winlator.cmod.session.LaunchTarget target =
                com.winlator.cmod.session.LaunchTarget.resolve(getIntent(), containerManager);
        if (target == null) {
            finish();
            return false;
        }
        container = target.container;
        shortcut = target.shortcut;
        playtime.setName(target.shortcutName);
        playtime.countLaunch();
        containerManager.activateContainer(container);

        // Bundle finder run: end the session by itself when YurnBench is done, crashed or hung.
        if (getIntent().getBooleanExtra(com.winlator.cmod.bench.Bench.EXTRA_BENCH, false)) {
            com.winlator.cmod.bench.Watchdog.start(this, container, () -> runOnUiThread(this::exit));
        }
        return true;
    }

    /** Stage 6: the Wine / Proton build and whether this launch runs on Wayland. */
    private void resolveWine(com.winlator.cmod.session.LaunchConfig lc) {
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
    }

    /** Stage 7: what the session log and the HUD learn from Wine's output (session/SessionLog). */
    private void setupLogCallbacks(boolean enableLogs, com.winlator.cmod.session.LaunchConfig lc) {
        debugDialog = com.winlator.cmod.session.SessionLog.install(this, enableLogs, shortcut, lc.getDxwrapperConfig(),
                () -> graphicsDriverConfig != null ? graphicsDriverConfig.get("version") : null,
                new com.winlator.cmod.session.SessionLog.Host() {
                    @Override public void dxvkBuild(String build) { dxvkBuild = build; }
                    @Override public void api(String label) {
                        actualApi = label;
                        WinlatorHUD m = hud.modern();
                        if (m != null) m.setApiLabel(label);
                    }
                    @Override public void driver(String label) {
                        actualDriver = label;
                        WinlatorHUD m = hud.modern();
                        if (m != null) m.setDriverLabel(label);
                    }
                });
    }

    /** Stage 8: the launch's drivers, emulator, wrapper and input options into the screen's fields. */
    private void applyLaunchConfig(com.winlator.cmod.session.LaunchConfig lc) {
        graphicsDriver = lc.getGraphicsDriver();
        String graphicsDriverConfig = lc.getGraphicsDriverConfig();
        audioDriver = lc.getAudioDriver();
        midiSoundFont = container.getMIDISoundFont();
        dxwrapper = lc.getDxwrapper();
        String dxwrapperConfig = lc.getDxwrapperConfig();
        winHandler.setInputType((byte) lc.getInputType());
        lc_all = lc.getLcAll();

        Intent intent = getIntent();
        Log.d("XServerDisplayActivity", "Intent Extras: " + intent.getExtras());

        if (shortcut != null) {
            boolean xinputDisabledFromShortcut = lc.getXinputDisabled();
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
    }

    /** Stage 9: the X server and what its windows tell the screen (first picture, HUD, cores). */
    private void createXServer(String screenSize) {
        xServer = new XServer(new ScreenInfo(screenSize), com.winlator.cmod.session.RendererSetup.xServerFormat(container, shortcut));
        xServer.setWinHandler(winHandler);
        xServer.setRelativeMouseMovement(isRelativeMouseMovement);
        com.winlator.cmod.session.RefreshRate.advertise(this, xServer, wineInfo);
        // The HUD's resolution: the game's (the mode it set inside the container's screen).
        final XServer xs = xServer;
        com.winlator.cmod.widget.banner.HudSession.gameResolution =
                () -> xs.displayMode.getWidth() + "x" + xs.displayMode.getHeight();

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
        lifecycle.resume();
    }

    @Override
    public void onPause() {
        lifecycle.beforePause();
        super.onPause();
        lifecycle.afterPause();
    }

    void enterPipMode() { lifecycle.enterPip(); }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode,
            Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        lifecycle.pipChanged(isInPictureInPictureMode);
    }

    void exit() { lifecycle.exit(); }

    @Override
    protected void onDestroy() {
        lifecycle.destroy();
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (playtime != null) playtime.pause();
    }

    @Override
    public void onBackPressed() { lifecycle.back(); }

    void showVibrationDialog() { com.winlator.cmod.ui.ingame.InputDispatch.showVibrationDialog(this, winHandler); }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        lifecycle.focusChanged(hasFocus);
    }

    /** The compositor's frame-generation rates for the HUD, only while frame generation is on
     *  (its presented rate counts every screen update, so with it off the HUD showed a pair). */
    private float[] waylandFrameGenStats() {
        if (!frameGen().on()) return null;
        try { return com.winlator.cmod.wayland.WaylandCompositor.nativeFrameGenStats(); }
        catch (Throwable t) { return null; }
    }

    /** Gets the prefix ready for this launch: session/PrefixSetup (YurNLator base, part 5). */
    private void setupWineSystemFiles() {
        String wrapperId = DxWrapperFiles.wrapperId(this.dxwrapper, dxwrapperConfig.get("version"),
                dxwrapperConfig.get("vkd3dVersion"), dxwrapperConfig.get("ddrawrapper"));
        new com.winlator.cmod.session.PrefixSetup(this, imageFs, wineInfo, container, launchConfig, xServer.screenInfo,
                waylandMode, firstTimeBoot, onExtractFileListener, dxWrapperFiles()).run(wrapperId, envVars);
    }

    private void setupXEnvironment() throws PackageManager.NameNotFoundException {

        // Environment and command of the launch: session/LaunchEnv (YurNLator base, part 6).
        com.winlator.cmod.session.LaunchEnv.base(envVars, preferences, lc_all, imageFs.wineprefix);

        FileUtils.clear(imageFs.getTmpDir());

        // The game's process and what the launch adds to its environment: session/GuestLaunch.
        java.util.function.Consumer<String> log = line -> { if (debugDialog != null) debugDialog.call(line); };
        guestProgramLauncherComponent = com.winlator.cmod.session.GuestLaunch.create(this, contentsManager, container,
                shortcut, wineInfo, launchConfig, xServer.screenInfo, getOverrideEnvVars(), waylandMode, log);
        com.winlator.cmod.session.GuestLaunch.environment(this, container, shortcut, envVars, graphicsDriver,
                cpuAffinity.wineCpuTopology(), log);
        // Precise sensors (root / ADB, Settings): CPU / GPU load, clocks and temperatures for both HUDs.
        com.winlator.cmod.core.HudSensorFeed.INSTANCE.start(this, imageFs.getRootDir());
        // MangoHud as the HUD: loaded with the game (it can't be added to a running one).
        hud.prepareLaunch(envVars, log);

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
        if (perf != null) perf.start();

        winHandler.start();

        if (wineRequestHandler != null)
            wineRequestHandler.start();

        dxwrapperConfig = null;

    }

    private void setupUI() {
        FrameLayout rootView = findViewById(R.id.FLXServerDisplay);

        // The view that shows the X server's picture (session/RendererSetup).
        xServerView = com.winlator.cmod.session.RendererSetup.create(this, xServer, container, shortcut,
                graphicsDriverConfig != null ? graphicsDriverConfig.get("version") : null);
        final XServerRendererView renderer = xServerView;

        rootView.addView(xServerView);
        if (waylandMode) {
            // The embedded Wayland compositor: session/WaylandLaunch.
            waylandSession = com.winlator.cmod.session.WaylandLaunch.start(this, xServer, rootView, xServerView,
                    graphicsDriverConfig != null ? graphicsDriverConfig.get("version") : null, container, shortcut,
                    launchConfig != null && launchConfig.getFullscreenStretched(), preloaderDialog, hud);
            com.winlator.cmod.session.LoadingScreen.closeWhenGameRuns(this, shortcut, preloaderDialog);
        }

        globalCursorSpeed = preferences.getFloat("cursor_speed", 1.0f);
        com.winlator.cmod.ui.ingame.TouchControls.Views touchViews = touchControls.createViews(this, rootView, xServer,
                globalCursorSpeed, isMouseDisabled,
                () -> { if (!drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.openDrawer(GravityCompat.START); },
                cursorLock ? (view, event) -> { handleCapturedPointer(event); return true; } : null);
        touchpadView = touchViews.touchpad;
        inputControlsView = touchViews.controls;

        boolean isTimeoutEnabled = preferences.getBoolean("touchscreen_timeout_enabled", false);
        if (isTimeoutEnabled) {
            startTouchscreenTimeout();
        }

        // The HUD the game (else the container) saved: ours, Bannerlator's styles next to it, or
        // MangoHud (in the game itself: nothing here).
        hud.createAtLaunch();

        if (launchConfig != null && launchConfig.getFullscreenStretched()) scaling.toggleFullscreen();

        if (shortcut != null && launchConfig != null) {
            int profileId = launchConfig.getControlsProfileId();
            if (profileId > 0) {
                ControlsProfile profile = inputControlsManager.getProfile(profileId);
                if (profile != null) showInputControls(profile);
            }
            touchpadView.setSimTouchScreen(launchConfig.getSimulateTouchScreen());
        }

        AppUtils.observeSoftKeyboardVisibility(drawerLayout, renderer::setScreenOffsetYRelativeToCursor);

        sidebar.setupHud();
        sidebar.setupGraphics();
        quickMenuBridge.applySaved();
        applyFrameGen();
        // Every renderer: direct (DRI3) frames tell the HUD which window is the game's.
        xServerView.setDirectFrameListener(hudTracker::onDirectFrame);
    }

    ActivityResultLauncher<Intent> controlsEditorActivityResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (editInputControlsCallback != null) {
                    editInputControlsCallback.run();
                    editInputControlsCallback = null;
                }
            });

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

    private DxWrapperFiles dxWrapperFiles() {
        return new DxWrapperFiles(this, imageFs, wineInfo, contentsManager, onExtractFileListener);
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
    /** Pause / resume, picture-in-picture, back, focus, exit (GameLifecycle). */
    final GameLifecycle lifecycle = new GameLifecycle(this);
    final XServerSidebar sidebar = new XServerSidebar(this);

}
