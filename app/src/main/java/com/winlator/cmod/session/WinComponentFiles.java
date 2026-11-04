package com.winlator.cmod.session;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.core.OnExtractFileListener;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.core.WineRegistryEditor;
import com.winlator.cmod.core.WineUtils;
import com.winlator.cmod.xenvironment.ImageFs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * YurNLator base, part 4: Windows components (Builtin = Wine's DLLs, Native = Microsoft's) and the
 * audio driver in the prefix, moved out of the game screen.
 *
 * Components are compared by name with what the prefix had, not position by position: the old code
 * walked both lists in step and threw when the new list had a component the old one didn't.
 */
public final class WinComponentFiles {
    private static final String TAG = "WinComponentFiles";

    private WinComponentFiles() {}

    private static HashMap<String, String> toMap(String list) {
        HashMap<String, String> map = new HashMap<>();
        for (String[] kv : new KeyValueSet(list)) map.put(kv[0], kv[1]);
        return map;
    }

    /**
     * Applies [wanted] ("direct3d=1,directsound=0,…") where it differs from [previous] (all of it
     * when [force]). [restoreOriginal] puts Wine's own DLLs back for the components set to Builtin.
     */
    public static void apply(Context context, ImageFs imageFs, Container container, String wanted, String previous,
                             boolean force, OnExtractFileListener listener, RestoreOriginal restoreOriginal) {
        File rootDir = imageFs.getRootDir();
        File windowsDir = new File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows");
        File systemRegFile = new File(rootDir, ImageFs.WINEPREFIX + "/system.reg");
        try {
            JSONObject dllLists = new JSONObject(FileUtils.readString(context, "wincomponents/wincomponents.json"));
            HashMap<String, String> before = toMap(previous);
            ArrayList<String> builtinDlls = new ArrayList<>();
            for (String[] kv : new KeyValueSet(wanted)) {
                String id = kv[0];
                if (!force && kv[1].equals(before.get(id))) continue;
                boolean useNative = kv[1].equals("1");
                if (useNative) {
                    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "wincomponents/" + id + ".tzst", windowsDir, listener);
                } else {
                    JSONArray names = dllLists.optJSONArray(id);
                    if (names != null) for (int i = 0; i < names.length(); i++) {
                        String n = names.getString(i);
                        builtinDlls.add(n.endsWith(".exe") ? n : n + ".dll");
                    }
                }
                Log.d(TAG, "wincomponent " + id + " -> " + (useNative ? "native" : "builtin"));
                WineUtils.overrideWinComponentDlls(context, container, id, useNative);
                WineUtils.setWinComponentRegistryKeys(systemRegFile, id, useNative, context);
            }
            if (!builtinDlls.isEmpty()) restoreOriginal.restore(builtinDlls.toArray(new String[0]));
        } catch (Exception e) {
            Log.w(TAG, "wincomponents", e);
        }
    }

    public interface RestoreOriginal { void restore(String... dlls); }

    /** Wine's audio driver in the registry: "alsa" or PulseAudio ("pulseaudio" / "pulse-audio-gn"). */
    public static void applyAudioDriver(ImageFs imageFs, String audioDriver) {
        File userRegFile = new File(imageFs.getRootDir(), ImageFs.WINEPREFIX + "/user.reg");
        try (WineRegistryEditor reg = new WineRegistryEditor(userRegFile)) {
            if (audioDriver.equals("alsa")) reg.setStringValue("Software\\Wine\\Drivers", "Audio", "alsa");
            else if (audioDriver.equals("pulseaudio") || audioDriver.equals("pulse-audio-gn"))
                reg.setStringValue("Software\\Wine\\Drivers", "Audio", "pulse");
        }
    }
}
