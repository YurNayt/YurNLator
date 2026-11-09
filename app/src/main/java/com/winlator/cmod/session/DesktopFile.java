package com.winlator.cmod.session;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/** The two things the game screen reads from a game's .desktop file before the Shortcut loads. */
public final class DesktopFile {
    private static final String TAG = "DesktopFile";

    private DesktopFile() {}

    /** "container_id:N", 0 when missing. */
    public static int containerId(File desktopFile) {
        String v = value(desktopFile, "container_id:", ":");
        try { return v != null ? Integer.parseInt(v) : 0; } catch (NumberFormatException e) { return 0; }
    }

    /** "Name=…", "" when missing. */
    public static String name(File desktopFile) {
        String v = value(desktopFile, "Name=", "=");
        return v != null ? v : "";
    }

    private static String value(File file, String prefix, String sep) {
        if (!file.exists()) return null;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null)
                if (line.startsWith(prefix)) return line.split(sep)[1].trim();
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "reading " + prefix + " from " + file, e);
        }
        return null;
    }
}
