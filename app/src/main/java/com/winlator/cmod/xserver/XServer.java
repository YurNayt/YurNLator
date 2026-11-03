package com.winlator.cmod.xserver;

import android.graphics.Rect;
import android.util.SparseArray;

import com.winlator.cmod.core.CursorLocker;
import com.winlator.cmod.widget.XServerRendererView;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.xserver.extensions.BigReqExtension;
import com.winlator.cmod.xserver.extensions.DRI3Extension;
import com.winlator.cmod.xserver.extensions.Extension;
import com.winlator.cmod.xserver.extensions.GLXExtension;
import com.winlator.cmod.xserver.extensions.MITSHMExtension;
import com.winlator.cmod.xserver.extensions.PresentExtension;
import com.winlator.cmod.xserver.extensions.RandrExtension;
import com.winlator.cmod.xserver.extensions.SyncExtension;
import com.winlator.cmod.xserver.extensions.XCompositeExtension;
import com.winlator.cmod.xserver.extensions.XInput2Extension;

import java.nio.charset.Charset;
import java.util.EnumMap;
import java.util.concurrent.locks.ReentrantLock;

public class XServer {
    public enum Lockable {WINDOW_MANAGER, PIXMAP_MANAGER, DRAWABLE_MANAGER, GRAPHIC_CONTEXT_MANAGER, INPUT_DEVICE, CURSOR_MANAGER, SHMSEGMENT_MANAGER}
    public static final short VERSION = 11;
    public static final String VENDOR_NAME = "Elbrus Technologies, LLC";
    public static final Charset LATIN1_CHARSET = Charset.forName("latin1");
    public final SparseArray<Extension> extensions = new SparseArray<>();
    public final ScreenInfo screenInfo;
    public final PixmapManager pixmapManager;
    public final ResourceIDs resourceIDs = new ResourceIDs(128);
    public final GraphicsContextManager graphicsContextManager = new GraphicsContextManager();
    public final SelectionManager selectionManager;
    public final DrawableManager drawableManager;
    public final WindowManager windowManager;
    public final CursorManager cursorManager;
    public final Keyboard keyboard = Keyboard.createKeyboard(this);
    public final Pointer pointer = new Pointer(this);
    public final InputDeviceManager inputDeviceManager;
    public final GrabManager grabManager;
    public final CursorLocker cursorLocker;
    private SHMSegmentManager shmSegmentManager;
    private XServerRendererView xServerView;
    private WinHandler winHandler;
    private final EnumMap<Lockable, ReentrantLock> locks = new EnumMap<>(Lockable.class);
    private boolean relativeMouseMovement = false;
    private boolean simulateTouchScreen = false;
    private boolean isGrabbed = false;
    private XClient grabbingClient = null;
    private final int surfaceFormat;

    public XServer(ScreenInfo screenInfo) {
        this(screenInfo, Drawable.HAL_PIXEL_FORMAT_BGRA_8888);
    }

    public XServer(ScreenInfo screenInfo, int surfaceFormat) {
        this.screenInfo = screenInfo;
        this.surfaceFormat = surfaceFormat;
        cursorLocker = new CursorLocker(this);
        for (Lockable lockable : Lockable.values()) locks.put(lockable, new ReentrantLock());

        pixmapManager = new PixmapManager();
        drawableManager = new DrawableManager(this);
        cursorManager = new CursorManager(drawableManager);
        windowManager = new WindowManager(screenInfo, drawableManager);
        selectionManager = new SelectionManager(windowManager);
        inputDeviceManager = new InputDeviceManager(this);
        grabManager = new GrabManager(this);
        displayMode = new DisplayModeTracker(screenInfo, windowManager);

        DesktopHelper.attachTo(this);
        setupExtensions();
    }

    /** The game's display mode inside the virtual desktop (auto-fit scaling). */
    public DisplayModeTracker displayMode;

    public int getSurfaceFormat() {
        return surfaceFormat;
    }

    public boolean isRelativeMouseMovement() {
        return relativeMouseMovement;
    }

    public void setRelativeMouseMovement(boolean relativeMouseMovement) {
        cursorLocker.setEnabled(!relativeMouseMovement);
        this.relativeMouseMovement = relativeMouseMovement;
    }

    public boolean isSimulateTouchScreen() { return simulateTouchScreen; }

    public void setSimulateTouchScreen(boolean simulateTouchScreen) {
        this.simulateTouchScreen = simulateTouchScreen;
    }

    public XServerRendererView getXServerView() {
        return xServerView;
    }

    public void setXServerView(XServerRendererView xServerView) {
        this.xServerView = xServerView;
    }

    public void setRenderingEnabled(boolean enabled) {
        windowManager.setRenderingEnabled(enabled);
    }

    public WinHandler getWinHandler() {
        return winHandler;
    }

    public void setWinHandler(WinHandler winHandler) {
        this.winHandler = winHandler;
    }

    public SHMSegmentManager getSHMSegmentManager() {
        return shmSegmentManager;
    }

    public void setSHMSegmentManager(SHMSegmentManager shmSegmentManager) {
        this.shmSegmentManager = shmSegmentManager;
    }

    private class SingleXLock implements XLock {
        private final ReentrantLock lock;

        private SingleXLock(Lockable lockable) {
            this.lock = locks.get(lockable);
            lock.lock();
        }

        @Override
        public void close() {
            lock.unlock();
        }
    }

    private class MultiXLock implements XLock {
        private final Lockable[] lockables;

        private MultiXLock(Lockable[] lockables) {
            this.lockables = lockables;
            for (Lockable lockable : lockables) locks.get(lockable).lock();
        }

        @Override
        public void close() {
            for (int i = lockables.length - 1; i >= 0; i--) {
                locks.get(lockables[i]).unlock();
            }
        }
    }

    public XLock lock(Lockable lockable) {
        return new SingleXLock(lockable);
    }

    public XLock lock(Lockable... lockables) {
        return new MultiXLock(lockables);
    }

    public XLock lockAll() {
        return new MultiXLock(Lockable.values());
    }

    public Extension getExtensionByName(String name) {
        for (int i = 0; i < extensions.size(); i++) {
            Extension extension = extensions.valueAt(i);
            if (extension.getName().equals(name)) return extension;
        }
        return null;
    }

    /**
     * Wayland display backend: the X server has no client then, so every injected input (touchpad,
     * on-screen controls, keyboard, mouse) is also handed to the embedded compositor.
     */
    public interface InputSink {
        /** Absolute pointer position in screen (= compositor scene) pixels. */
        void onPointerMove(int x, int y);
        /** Relative motion (relative mouse / pointer lock). */
        void onPointerDelta(int dx, int dy);
        void onPointerButton(Pointer.Button button, boolean pressed);
        /** Linux evdev key code. */
        void onKey(int evdev, boolean pressed);
    }

    private volatile InputSink inputSink;

    public void setInputSink(InputSink sink) {
        inputSink = sink;
    }

    public void injectPointerMove(int x, int y) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setPosition(x, y);
        }
        InputSink sink = inputSink;
        if (sink != null) sink.onPointerMove(pointer.getX(), pointer.getY());
    }

    public void injectPointerMoveDelta(int dx, int dy) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            Rect confinement = grabManager.getConfinementBounds();
            if (confinement != null) {
                int minX = Math.max(0, confinement.left);
                int minY = Math.max(0, confinement.top);
                int maxX = Math.min(screenInfo.width, confinement.right) - 1;
                int maxY = Math.min(screenInfo.height, confinement.bottom) - 1;
                int nextX = Math.max(minX, Math.min(maxX, pointer.getX() + dx));
                int nextY = Math.max(minY, Math.min(maxY, pointer.getY() + dy));
                pointer.setPosition(nextX, nextY);
            }
            else {
                pointer.setPosition(pointer.getX() + dx, pointer.getY() + dy);
            }

            XInput2Extension xInput2 = getExtension(XInput2Extension.MAJOR_OPCODE);
            if (xInput2 != null) xInput2.emitRawMotion(2, dx, dy);
        }
        InputSink sink = inputSink;
        if (sink != null) sink.onPointerDelta(dx, dy);
    }

    public void injectPointerButtonPress(Pointer.Button buttonCode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            boolean wasPressed = pointer.isButtonPressed(buttonCode);
            pointer.setButton(buttonCode, true);

            XInput2Extension xInput2 = getExtension(XInput2Extension.MAJOR_OPCODE);
            if (!wasPressed && xInput2 != null) {
                xInput2.emitRawButton(2, buttonCode.code(), true);
            }
        }
        InputSink sink = inputSink;
        if (sink != null) sink.onPointerButton(buttonCode, true);
    }

    public void injectPointerButtonRelease(Pointer.Button buttonCode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            boolean wasPressed = pointer.isButtonPressed(buttonCode);
            pointer.setButton(buttonCode, false);

            XInput2Extension xInput2 = getExtension(XInput2Extension.MAJOR_OPCODE);
            if (wasPressed && xInput2 != null) {
                xInput2.emitRawButton(2, buttonCode.code(), false);
            }
        }
        InputSink sink = inputSink;
        if (sink != null) sink.onPointerButton(buttonCode, false);
    }

    public void injectKeyPress(XKeycode xKeycode) {
        injectKeyPress(xKeycode, 0);
    }

    public void injectKeyPress(XKeycode xKeycode, int keysym) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            keyboard.setKeyPress(xKeycode.id, keysym);
        }
        InputSink sink = inputSink;
        if (sink != null) sink.onKey((xKeycode.id & 0xff) - 8, true); // X keycode = evdev + 8
    }

    public void injectKeyRelease(XKeycode xKeycode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            keyboard.setKeyRelease(xKeycode.id);
        }
        InputSink sink = inputSink;
        if (sink != null) sink.onKey((xKeycode.id & 0xff) - 8, false);
    }

    private void setupExtensions() {
        extensions.put(BigReqExtension.MAJOR_OPCODE, new BigReqExtension());
        extensions.put(MITSHMExtension.MAJOR_OPCODE, new MITSHMExtension());
        extensions.put(DRI3Extension.MAJOR_OPCODE, new DRI3Extension(this));
        extensions.put(PresentExtension.MAJOR_OPCODE, new PresentExtension(this));
        extensions.put(RandrExtension.MAJOR_OPCODE, new RandrExtension(screenInfo));
        extensions.put(SyncExtension.MAJOR_OPCODE, new SyncExtension(this));
        extensions.put(GLXExtension.MAJOR_OPCODE, new GLXExtension(this));
        extensions.put(XInput2Extension.MAJOR_OPCODE, new XInput2Extension());
        extensions.put(XCompositeExtension.MAJOR_OPCODE, new XCompositeExtension(this));
    }

    public <T extends Extension> T getExtension(int opcode) {
        return (T)extensions.get(opcode);
    }

    public synchronized void setGrabbed(boolean grabbed, XClient client) {
        this.isGrabbed = grabbed;
        this.grabbingClient = client;
    }

    public synchronized boolean isGrabbedBy(XClient client) {
        return isGrabbed && grabbingClient == client;
    }
}
