package com.winlator.cmod.session;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.OnExtractFileListener;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YurNLator base: the DirectX wrapper DLLs in the prefix (DXVK, VKD3D, d8vk, ddraw wrappers,
 * or Wine's own for WineD3D). Moved out of the game screen; it also checks the files themselves,
 * not only the container's record of what was installed.
 *
 * The wrapper id is "dxvk-&lt;ver&gt;;vkd3d-&lt;ver&gt;;&lt;ddrawrapper&gt;" for DXVK, or "wined3d".
 */
public final class DxWrapperFiles {
    private static final String TAG = "DxWrapperFiles";

    private static final String[] ALL_DLLS = { "d3d10.dll", "d3d10_1.dll", "d3d10core.dll", "d3d11.dll", "d3d12.dll",
            "d3d12core.dll", "d3d8.dll", "d3d9.dll", "dxgi.dll", "ddraw.dll", "d3dimm.dll" };

    private final Context context;
    private final ImageFs imageFs;
    private final WineInfo wineInfo;
    private final ContentsManager contentsManager;
    private final OnExtractFileListener listener;
    private final File windowsDir;

    public DxWrapperFiles(Context context, ImageFs imageFs, WineInfo wineInfo, ContentsManager contentsManager,
                          OnExtractFileListener listener) {
        this.context = context;
        this.imageFs = imageFs;
        this.wineInfo = wineInfo;
        this.contentsManager = contentsManager;
        this.listener = listener;
        this.windowsDir = new File(imageFs.getRootDir(), ImageFs.WINEPREFIX + "/drive_c/windows");
    }

    /** "dxvk-2.4;vkd3d-2.14;none" from the DX wrapper choice and its config, or the choice itself. */
    public static String wrapperId(String dxwrapper, String dxvkVersion, String vkd3dVersion, String ddrawrapper) {
        if (!dxwrapper.contains("dxvk")) return dxwrapper;
        return "dxvk-" + dxvkVersion + ";vkd3d-" + vkd3dVersion + ";" + ddrawrapper;
    }

    /**
     * True when the DLLs are gone although the container says they're applied: switching the Wine /
     * Proton version (or a prefix update) puts Wine's own d3d11 / d3d12 back, and the game silently
     * ran on WineD3D (or Wine's d3d12) with DXVK / VKD3D selected.
     */
    public boolean filesMissing(String wrapperId) {
        if (!wrapperId.startsWith("dxvk")) return false;
        if (isWineBuiltinDll(new File(windowsDir, "system32/d3d11.dll"))) {
            Log.w(TAG, "DXVK selected but system32\\d3d11.dll is Wine's own: re-installing");
            return true;
        }
        String[] parts = wrapperId.split(";");
        boolean vkd3d = parts.length > 1 && !parts[1].contains("None");
        if (vkd3d && isWineBuiltinDll(new File(windowsDir, "system32/d3d12.dll"))) {
            Log.w(TAG, "VKD3D selected but system32\\d3d12.dll is Wine's own: re-installing");
            return true;
        }
        return false;
    }

    /** Wine's builtin PE DLLs carry "Wine builtin DLL" in their DOS stub; DXVK's and VKD3D's don't. */
    public static boolean isWineBuiltinDll(File dll) {
        if (!dll.isFile()) return true;
        try (FileInputStream in = new FileInputStream(dll)) {
            byte[] head = new byte[0x80];
            int n = in.read(head);
            return n > 0 && new String(head, 0, n, StandardCharsets.ISO_8859_1).contains("Wine builtin DLL");
        } catch (Exception e) {
            return false;
        }
    }

    /** Puts the wrapper's DLLs into system32 / syswow64. [envVars] gets what a ddraw wrapper needs. */
    public void install(String wrapperId, EnvVars envVars) {
        if (wrapperId.contains("dxvk")) {
            Log.d(TAG, "Extracting DXVK wrapper files: " + wrapperId);
            String[] parts = wrapperId.split(";");
            String dxvkWrapper = parts[0];
            String vkd3dWrapper = parts.length > 1 ? parts[1] : "None";
            String ddrawrapper = parts.length > 2 ? parts[2] : "None";

            ContentProfile dxvkProfile = contentsManager.getProfileByEntryName(dxvkWrapper);
            if (dxvkProfile != null) {
                Log.d(TAG, "Applying user-defined DXVK content profile: " + dxvkWrapper);
                contentsManager.applyContent(dxvkProfile);
            } else {
                Log.d(TAG, "Extracting fallback DXVK .tzst archive: " + dxvkWrapper);
                extract("dxwrapper/" + dxvkWrapper + ".tzst");
                if (compareVersion(dxvkWrapper, "2.4") < 0) {
                    Log.d(TAG, "Extracting d8vk as part of DXVK version " + dxvkWrapper);
                    extract("dxwrapper/d8vk-" + DefaultVersion.D8VK + ".tzst");
                }
            }

            if (vkd3dWrapper.contains("None")) {
                Log.d(TAG, "No VKD3D selected, restoring original d3d12");
                restoreOriginal("d3d12.dll", "d3d12core.dll");
            } else {
                ContentProfile vkd3dProfile = contentsManager.getProfileByEntryName(vkd3dWrapper);
                if (vkd3dProfile != null) {
                    Log.d(TAG, "Applying user-defined VKD3D content profile: " + vkd3dWrapper);
                    contentsManager.applyContent(vkd3dProfile);
                } else {
                    Log.d(TAG, "Extracting fallback VKD3D .tzst archive: " + vkd3dWrapper);
                    extract("dxwrapper/" + vkd3dWrapper + ".tzst");
                }
            }

            Log.d(TAG, "Extracting nglide wrapper");
            extract("ddrawrapper/nglide.tzst");

            if (ddrawrapper.contains("None")) {
                Log.d(TAG, "No DDraw wrapper selected, restoring original ddraw files");
                restoreOriginal("ddraw.dll", "d3dimm.dll");
            } else {
                if (ddrawrapper.equals("cnc-ddraw"))
                    envVars.put("CNC_DDRAW_CONFIG_FILE", "C:\\windows\\syswow64\\ddraw.ini");
                Log.d(TAG, "Extracting ddrawrapper " + ddrawrapper);
                extract("ddrawrapper/" + ddrawrapper + ".tzst");
            }
            Log.d(TAG, "Finished DXVK wrapper files: " + wrapperId);
        } else if (wrapperId.contains("wined3d")) {
            Log.d(TAG, "Restoring original DLL files for wined3d");
            restoreOriginal(ALL_DLLS);
        }
    }

    private void extract(String asset) {
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, asset, windowsDir, listener);
    }

    /** Copies Wine's own DLLs back into system32 / syswow64. */
    public void restoreOriginal(String... dlls) {
        File system32dlls = new File(imageFs.getWinePath() + (wineInfo.isArm64EC()
                ? "/lib/wine/aarch64-windows" : "/lib/wine/x86_64-windows"));
        File syswow64dlls = new File(imageFs.getWinePath() + "/lib/wine/i386-windows");
        for (String dll : dlls) {
            FileUtils.copy(new File(system32dlls, dll), new File(windowsDir, "system32/" + dll));
            FileUtils.copy(new File(syswow64dlls, dll), new File(windowsDir, "syswow64/" + dll));
        }
    }

    private static final Pattern SEMVER_LOOSE = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    static int compareVersion(String varA, String varB) {
        int[] a = parseSemverLoose(varA);
        int[] b = parseSemverLoose(varB);
        if (a[0] != b[0]) return a[0] - b[0];
        if (a[1] != b[1]) return a[1] - b[1];
        return a[2] - b[2];
    }

    private static int[] parseSemverLoose(String s) {
        if (s == null) return new int[] { 0, 0, 0 };
        Matcher m = SEMVER_LOOSE.matcher(s);
        String g1 = null, g2 = null, g3 = null;
        while (m.find()) { g1 = m.group(1); g2 = m.group(2); g3 = m.group(3); }
        if (g1 == null || g2 == null) return new int[] { 0, 0, 0 };
        return new int[] { safeParseInt(g1), safeParseInt(g2), safeParseInt(g3) };
    }

    private static int safeParseInt(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Integer.parseInt(s); } catch (NumberFormatException ignored) { return 0; }
    }
}
