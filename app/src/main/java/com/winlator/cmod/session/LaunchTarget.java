package com.winlator.cmod.session;

import android.content.Intent;
import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;

import java.io.File;

/**
 * YurNLator base, part 10: what the game screen was opened for, from its intent: the container
 * (its id, else the one the game's .desktop file names), the game's shortcut and its name.
 * Moved out of the game screen's onCreate.
 */
public final class LaunchTarget {
    private static final String TAG = "LaunchTarget";

    public final Container container;
    /** Null when the container's desktop was opened without a game. */
    public final Shortcut shortcut;
    public final String shortcutName;

    private LaunchTarget(Container container, Shortcut shortcut, String shortcutName) {
        this.container = container;
        this.shortcut = shortcut;
        this.shortcutName = shortcutName;
    }

    /** Null when no such container exists (the screen then closes). */
    public static LaunchTarget resolve(Intent intent, ContainerManager containerManager) {
        String shortcutPath = intent.getStringExtra("shortcut_path");
        boolean hasShortcut = shortcutPath != null && !shortcutPath.isEmpty();

        int containerId = intent.getIntExtra("container_id", 0);
        if (containerId == 0 && hasShortcut) containerId = DesktopFile.containerId(new File(shortcutPath));

        String name = intent.getStringExtra("shortcut_name");
        if (hasShortcut && (name == null || name.isEmpty())) name = DesktopFile.name(new File(shortcutPath));

        Container container = containerManager.getContainerById(containerId);
        Log.d(TAG, "container " + containerId + (container == null ? " not found" : "") + ", shortcut " + shortcutPath);
        if (container == null) return null;
        Shortcut shortcut = hasShortcut ? new Shortcut(container, new File(shortcutPath)) : null;
        return new LaunchTarget(container, shortcut, name);
    }
}
