package com.winlator.cmod;

import android.graphics.Color;
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
                if (a.xServerView != null) {
                    a.xServerView.toggleFullscreen();
                    if (a.touchpadView != null)
                        a.touchpadView.toggleFullscreen();
                }
                a.drawerLayout.closeDrawers();
            });
        }

        View btItemMagnifier = a.findViewById(R.id.BTItemMagnifier);
        if (btItemMagnifier != null) {
            btItemMagnifier.setOnClickListener(v -> {
                if (a.xServerView instanceof DisplayXServerView) {
                    AppUtils.showToast(a, R.string.magnifier_not_available);
                    a.drawerLayout.closeDrawers();
                    return;
                }
                if (a.xServerView != null) {
                    final XServerRendererView renderer = a.xServerView;
                    if (a.magnifierView == null) {
                        FrameLayout flContainer = a.findViewById(R.id.FLXServerDisplay);
                        a.magnifierView = new MagnifierView(a);
                        a.magnifierView.setZoomButtonCallback(value -> {
                            renderer.setMagnifierZoom(Mathf.clamp(renderer.getMagnifierZoom() + value, 1.0f, 3.0f));
                            a.magnifierView.setZoomValue(renderer.getMagnifierZoom());
                        });
                        a.magnifierView.setZoomValue(renderer.getMagnifierZoom());
                        a.magnifierView.setHideButtonCallback(() -> {
                            flContainer.removeView(a.magnifierView);
                            a.magnifierView = null;
                        });
                        flContainer.addView(a.magnifierView);
                    }
                }
                a.drawerLayout.closeDrawers();
            });
        }

        View btItemSoftStretch = a.findViewById(R.id.BTItemSoftStretch);
        if (btItemSoftStretch != null) {
            btItemSoftStretch.setOnClickListener(v -> {
                if (a.xServerView != null) {
                    a.softStretchEnabled = !a.softStretchEnabled;
                    XServerRendererView rendererRef = a.xServerView;

                    if (a.softStretchEnabled && !rendererRef.isFullscreen()) {
                        rendererRef.toggleFullscreen();
                        if (a.touchpadView != null) a.touchpadView.toggleFullscreen();
                    }
                    if (rendererRef instanceof VulkanXServerView) {
                        ((VulkanXServerView) rendererRef).setStretchMode(a.softStretchEnabled ? 1 : 0);
                    }
                    btItemSoftStretch.setSelected(a.softStretchEnabled);
                }
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
}
