package com.winlator.cmod;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.inputcontrols.InputControlsManager;
import com.winlator.cmod.widget.FrameRating;
import com.winlator.cmod.widget.InputControlsView;
import com.winlator.cmod.widget.WinlatorHUD;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Switch;

import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.ProcessHelper;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.widget.DisplayXServerView;
import com.winlator.cmod.widget.MagnifierView;
import com.winlator.cmod.widget.VulkanXServerView;
import com.winlator.cmod.widget.XServerRendererView;
import com.winlator.cmod.winhandler.TaskManagerSidebar;

/**
 * The game screen's side drawer (the classic sidebar): its buttons (pause, keyboard, vibration,
 * mouse switches, picture-in-picture, fullscreen, magnifier, soft stretch, task manager, logs,
 * exit) and which of its panels is open. Moved out of the game screen (base migration); like
 * {@link XServerQuickMenuBridge} it works on the activity's package-private state.
 */
final class XServerSidebar {
    private final XServerDisplayActivity a;

    XServerSidebar(XServerDisplayActivity activity) { this.a = activity; }

    /** Reopens the panel that was open last (the drawer opened again). */
    void reopen() { openSidebarPanel(activeSidebarItemId, activeSidebarPanelId); }

    void wire(boolean enableLogs) {

        View btItemLogs = a.findViewById(R.id.BTItemLogs);
        if (btItemLogs != null)
            btItemLogs.setVisibility(enableLogs ? View.VISIBLE : View.GONE);

        if (XrActivity.isEnabled(a)) {
            View btItemMagnifier = a.findViewById(R.id.BTItemMagnifier);
            if (btItemMagnifier != null)
                btItemMagnifier.setVisibility(View.GONE);
        }

        toggleOnClick(R.id.BTItemInput, R.id.LLSubInput);
        toggleOnClick(R.id.BTItemMouse, R.id.LLSubMouse);
        toggleOnClick(R.id.BTItemFPS, R.id.LLSubFPS);
        toggleOnClick(R.id.BTItemGraphics, R.id.LLSubGraphics);
        toggleOnClick(R.id.BTItemScreen, R.id.LLSubScreen);
        openSidebarPanel(R.id.BTItemFPS, R.id.LLSubFPS);

        ViewGroup btItemPause = (ViewGroup) a.findViewById(R.id.BTItemPause);
        if (btItemPause != null) {
            ImageView pauseIcon = (ImageView) btItemPause.getChildAt(0);
            btItemPause.setOnClickListener(v -> {
                if (a.isPaused) {
                    ProcessHelper.resumeAllWineProcesses();
                    if (pauseIcon != null) pauseIcon.setImageResource(R.drawable.icon_pause);
                } else {
                    ProcessHelper.pauseAllWineProcesses();
                    if (pauseIcon != null) pauseIcon.setImageResource(R.drawable.icon_play);
                }
                a.isPaused = !a.isPaused;
                a.drawerLayout.closeDrawers();
            });
        }

        View btSubKeyboard = a.findViewById(R.id.BTSubKeyboard);
        if (btSubKeyboard != null) {
            btSubKeyboard.setOnClickListener(v -> {
                AppUtils.showKeyboard(a);
                a.drawerLayout.closeDrawers();
            });
        }

        a.setupSidebarInputControls();

        View btSubVibration = a.findViewById(R.id.BTSubVibration);
        if (btSubVibration != null) {
            btSubVibration.setOnClickListener(v -> {
                a.showVibrationDialog();
                a.drawerLayout.closeDrawers();
            });
        }

        Switch swRelativeMouse = a.findViewById(R.id.SWRelativeMouse);
        if (swRelativeMouse != null) {
            swRelativeMouse.setChecked(a.isRelativeMouseMovement);
            swRelativeMouse.setOnCheckedChangeListener((btn, checked) -> {
                a.isRelativeMouseMovement = checked;
                if (a.xServer != null)
                    a.xServer.setRelativeMouseMovement(a.isRelativeMouseMovement);
            });
        }

        Switch swDisableMouse = a.findViewById(R.id.SWDisableMouse);
        if (swDisableMouse != null) {
            swDisableMouse.setChecked(a.isMouseDisabled);
            swDisableMouse.setOnCheckedChangeListener((btn, checked) -> {
                a.isMouseDisabled = checked;
                if (a.touchpadView != null)
                    a.touchpadView.setMouseEnabled(!a.isMouseDisabled);
            });
        }

        View btItemPipMode = a.findViewById(R.id.BTItemPipMode);
        if (btItemPipMode != null) {
            btItemPipMode.setOnClickListener(v -> {
                a.enterPipMode();
                a.drawerLayout.closeDrawers();
            });
        }

        View btItemToggleFullscreen = a.findViewById(R.id.BTItemToggleFullscreen);
        if (btItemToggleFullscreen != null) {
            btItemToggleFullscreen.setOnClickListener(v -> {
                a.scaling.toggleFullscreen();
                a.drawerLayout.closeDrawers();
            });
        }

        View btItemMagnifier = a.findViewById(R.id.BTItemMagnifier);
        if (btItemMagnifier != null) {
            btItemMagnifier.setOnClickListener(v -> {
                a.magnifier.open(a, a.xServerView);
                a.drawerLayout.closeDrawers();
            });
        }

        View btItemSoftStretch = a.findViewById(R.id.BTItemSoftStretch);
        if (btItemSoftStretch != null) {
            btItemSoftStretch.setOnClickListener(v -> {
                btItemSoftStretch.setSelected(a.scaling.toggleSoftStretch());
                a.drawerLayout.closeDrawers();
            });
        }

        View btItemTaskManager = a.findViewById(R.id.BTItemTaskManager);
        if (btItemTaskManager != null) {
            btItemTaskManager.setOnClickListener(v -> {
                openSidebarPanel(R.id.BTItemTaskManager, R.id.LLSubTaskManager);
                View taskPanel = a.findViewById(R.id.LLSubTaskManager);
                if (taskPanel != null) {
                    if (a.taskManagerSidebar == null)
                        a.taskManagerSidebar = new TaskManagerSidebar(a, taskPanel);
                    a.taskManagerSidebar.start();
                }
            });
        }

        if (btItemLogs != null) {
            btItemLogs.setOnClickListener(v -> {
                if (a.debugDialog != null)
                    a.debugDialog.show();
                a.drawerLayout.closeDrawers();
            });
        }

        View btItemExit = a.findViewById(R.id.BTItemExit);
        if (btItemExit != null) {
            btItemExit.setOnClickListener(v -> {
                a.drawerLayout.closeDrawers();
                a.exit();
            });
        }
    }

    private int activeSidebarItemId = R.id.BTItemFPS;
    private int activeSidebarPanelId = R.id.LLSubFPS;

    private final int[] sidebarPanelIds = {
        R.id.LLSubInput,
        R.id.LLSubMouse,
        R.id.LLSubFPS,
        R.id.LLSubGraphics,
        R.id.LLSubScreen,
        R.id.LLSubTaskManager
    };

    private final int[] sidebarItemIds = {
        R.id.BTItemInput,
        R.id.BTItemMouse,
        R.id.BTItemFPS,
        R.id.BTItemGraphics,
        R.id.BTItemScreen,
        R.id.BTItemTaskManager
    };

    void hideAllPanels() {
        if (a.taskManagerSidebar != null) a.taskManagerSidebar.stop();
        for (int panelId : sidebarPanelIds) {
            View panel = a.findViewById(panelId);
            if (panel != null) panel.setVisibility(View.GONE);
        }
    }

    private void setSidebarActiveItem(int activeId) {
        float density = a.getResources().getDisplayMetrics().density;
        for (int itemId : sidebarItemIds) {
            View item = a.findViewById(itemId);
            if (item == null) continue;
            if (itemId == activeId) {
                GradientDrawable background = new GradientDrawable();
                background.setColor(Color.parseColor("#0F2D42"));
                background.setStroke((int) (1.1f * density), Color.parseColor("#0288D1"));
                background.setCornerRadius(density * 15);
                item.setBackground(background);
                item.animate().scaleX(1.025f).scaleY(1.025f).setDuration(105).start();
            } else {
                TypedValue outValue = new TypedValue();
                a.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
                item.setBackgroundResource(outValue.resourceId);
                item.animate().scaleX(1.0f).scaleY(1.0f).setDuration(90).start();
            }
        }
    }

    private void openSidebarPanel(int parentId, int subId) {
        hideAllPanels();
        View sub = a.findViewById(subId);
        if (sub != null) {
            float density = a.getResources().getDisplayMetrics().density;
            sub.setVisibility(View.VISIBLE);
            sub.setAlpha(0.0f);
            sub.setTranslationX(-8.0f * density);
            sub.animate().alpha(1.0f).translationX(0.0f).setDuration(130).start();
        }
        setSidebarActiveItem(parentId);
        if (parentId != R.id.BTItemMouse && parentId != R.id.BTItemPause) {
            activeSidebarItemId = parentId;
            activeSidebarPanelId = subId;
        }
    }

    private void toggleOnClick(int parentId, int subId) {
        View parent = a.findViewById(parentId);
        View sub = a.findViewById(subId);
        if (parent != null && sub != null) {
            parent.setOnClickListener(v -> openSidebarPanel(parentId, subId));
        }
    }

    /** Spinners of the side panels: light text on the panel's dark blue. */
    ArrayAdapter<String> spinnerAdapter(String[] items) {
        return new ArrayAdapter<String>(a, android.R.layout.simple_spinner_item, items) {
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

    /** YurNLator base, part 15: the side panels were set up by the game screen. */
    void setupHud() {
        com.winlator.cmod.ui.ingame.SidebarHudPanel.setup(new com.winlator.cmod.ui.ingame.SidebarHudPanel.Host() {
            @Override public android.app.Activity activity() { return a; }
            @Override public WinlatorHUD modernHud() { return a.hud.modern(); }
            @Override public FrameRating classicHud() { return null; }
            @Override public int savedMode() { return a.hud.savedMode(); }
            @Override public void apply(int mode) { a.hud.apply(mode); }
            @Override public ArrayAdapter<String> spinnerAdapter(String[] items) { return XServerSidebar.this.spinnerAdapter(items); }
        });
    }

    void setupGraphics() {
        com.winlator.cmod.ui.ingame.SidebarGraphicsPanel.setup(new com.winlator.cmod.ui.ingame.SidebarGraphicsPanel.Host() {
            @Override public android.app.Activity activity() { return a; }
            @Override public XServerRendererView renderer() { return a.xServerView; }
            @Override public Container container() { return a.container; }
            @Override public Shortcut shortcut() { return a.shortcut; }
            @Override public void applyFpsLimit(int fps) { a.applyFpsLimit(fps); }
            @Override public ArrayAdapter<String> spinnerAdapter(String[] items) { return XServerSidebar.this.spinnerAdapter(items); }
        });
    }
    void setupInput() {
        com.winlator.cmod.ui.ingame.SidebarInputPanel.setup(new com.winlator.cmod.ui.ingame.SidebarInputPanel.Host() {
            @Override public android.app.Activity activity() { return a; }
            @Override public InputControlsView controls() { return a.inputControlsView; }
            @Override public InputControlsManager manager() { return a.inputControlsManager; }
            @Override public SharedPreferences prefs() { return a.preferences; }
            @Override public com.winlator.cmod.ui.ingame.TouchControls touch() { return a.touchControls; }
            @Override public ArrayAdapter<String> spinnerAdapter(String[] items) { return XServerSidebar.this.spinnerAdapter(items); }
            @Override public void openEditor(int profileId, Runnable onBack) {
                android.content.Intent intent = new android.content.Intent(a, MainActivity.class);
                intent.putExtra("edit_input_controls", true);
                intent.putExtra("selected_profile_id", profileId);
                a.editInputControlsCallback = onBack;
                a.controlsEditorActivityResultLauncher.launch(intent);
            }
            @Override public void clearTouchpadListener() { if (a.touchpadView != null) a.touchpadView.setOnTouchListener(null); }
        });
    }
}
