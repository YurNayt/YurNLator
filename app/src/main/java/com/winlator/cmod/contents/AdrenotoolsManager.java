package com.winlator.cmod.contents;

import com.winlator.cmod.core.GpuProfileManager;

import android.content.res.AssetManager;
import android.net.Uri;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.SettingsFragment;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.DownloadProgressDialog;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.GPUInformation;
import com.winlator.cmod.contentdialog.GraphicsDriverConfigDialog;
import com.winlator.cmod.core.OnExtractFileListener;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.xenvironment.ImageFs;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONException;
import org.json.JSONObject;

public class AdrenotoolsManager {
    
    private File adrenotoolsContentDir;
    private Context mContext;
    
    public AdrenotoolsManager(Context context) {
        this.mContext = context;
        this.adrenotoolsContentDir = new File(mContext.getFilesDir(), "contents/adrenotools");
        if (!adrenotoolsContentDir.exists())
            adrenotoolsContentDir.mkdirs();
    }
    
    public String getAssetPath(String adrenotoolsDriverId) {
        return "graphics_driver/adrenotools-" + adrenotoolsDriverId + ".tzst";
    }
        
    public String getLibraryName(String adrenoToolsDriverId) {
        String libraryName = "";
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            JSONObject jsonObject = new JSONObject(FileUtils.readString(metaProfile));
            libraryName = jsonObject.getString("libraryName");
        }
        catch (JSONException e) {
        }
        return libraryName;
    }
    
    public String getDriverName(String adrenoToolsDriverId) {
        String driverName = "";
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            JSONObject jsonObject = new JSONObject(FileUtils.readString(metaProfile));
            driverName = jsonObject.getString("name");
        }
        catch (JSONException e) {
        }
        return driverName;
    }

    public String getDriverVersion(String adrenoToolsDriverId) {
        String driverVersion = "";
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            JSONObject jsonObject = new JSONObject(FileUtils.readString(metaProfile));
            driverVersion = jsonObject.getString("driverVersion");
        }
        catch (JSONException e) {
        }
        return driverVersion;
    }

    public String getDriverPath(String adrenotoolsDriverId) {
        return adrenotoolsContentDir.getAbsolutePath() + "/" + adrenotoolsDriverId + "/";
    }

    private void reloadContainers(String adrenoToolsDriverId) {
        ContainerManager containerManager = new ContainerManager(mContext);
        for (Container container : containerManager.getContainers()) {
            HashMap<String, String> config = GraphicsDriverConfigDialog.parseGraphicsDriverConfig(container.getGraphicsDriverConfig());
            Log.d("AdrenotoolsManager", "Checking if container driver version " + config.get("version") + " matches " + getDriverName(adrenoToolsDriverId));
            if (config.get("version").contains(getDriverName(adrenoToolsDriverId))) {
                Log.d("AdrenotoolsManager", "Found a match for container " + container.getName());
                config.put("version", GpuProfileManager.defaultGraphicsDriverVersion(mContext));
                container.setGraphicsDriverConfig(GraphicsDriverConfigDialog.toGraphicsDriverConfig(config));
                container.saveData();
            }     
        }
        for (Shortcut shortcut : containerManager.loadShortcuts()) {
            HashMap<String, String> config = GraphicsDriverConfigDialog.parseGraphicsDriverConfig(shortcut.getExtra("graphicsDriverConfig", shortcut.container.getGraphicsDriverConfig()));
            Log.d("AdrenotoolsManager", "Checking if shortcut driver version " + config.get("version") + " matches " + getDriverName(adrenoToolsDriverId));
            if (config.get("version").contains(getDriverName(adrenoToolsDriverId))) {
                Log.d("AdrenotoolsManager", "Found a match for shortcut " + shortcut.name);
                config.put("version", GpuProfileManager.defaultGraphicsDriverVersion(mContext));
                shortcut.putExtra("graphicsDriverConfig", GraphicsDriverConfigDialog.toGraphicsDriverConfig(config));
                shortcut.saveData();
            }
        }
    }
    
    public void removeDriver(String adrenoToolsDriverId) {
        Log.d("AdrenotoolsManager", "Removing driver " + adrenoToolsDriverId);
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        reloadContainers(adrenoToolsDriverId);
        FileUtils.delete(driverPath);
    }

    public ArrayList<String> enumarateInstalledDrivers() {
        ArrayList<String> driversList = new ArrayList<>();
        
        for (File f : adrenotoolsContentDir.listFiles()) {
            boolean fromResources = isFromResources(f.getName());
            if (!fromResources && new File(f, "meta.json").exists())
                driversList.add(f.getName());
        }
        return driversList;
    }
    
    public boolean isFromResources(String adrenotoolsDriverId) {
        String driver = "graphics_driver/adrenotools-" + adrenotoolsDriverId + ".tzst";
        AssetManager am = mContext.getResources().getAssets();
        InputStream is = null;
        boolean isFromResources = true;
        
        try {
            is = am.open(driver);
            is.close();
        }
        catch (IOException e) {
            isFromResources = false;
        }
        
        return isFromResources;
    }
        
    public boolean extractDriverFromResources(String adrenotoolsDriverId) {
        String src = "graphics_driver/adrenotools-" + adrenotoolsDriverId + ".tzst";
        boolean hasExtracted;

        File dst = new File(adrenotoolsContentDir, adrenotoolsDriverId);
        if (dst.exists())
            return true;

        dst.mkdirs();
        Log.d("AdrenotoolsManager", "Extracting " + src + " to " + dst.getAbsolutePath());
        hasExtracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, mContext, src, dst);

        if (!hasExtracted)
            dst.delete();

        return hasExtracted;
    }
    
    public boolean extractDriverFromResources(String adrenotoolsDriverId, OnExtractFileListener listener) {
        String src = "graphics_driver/adrenotools-" + adrenotoolsDriverId + ".tzst";
        boolean hasExtracted;

        File dst = new File(adrenotoolsContentDir, adrenotoolsDriverId);
        if (dst.exists())
            return true;

        dst.mkdirs();
        Log.d("AdrenotoolsManager", "Extracting " + src + " to " + dst.getAbsolutePath());
        hasExtracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, mContext, src, dst, listener);

        if (!hasExtracted)
            dst.delete();

        return hasExtracted;
    }
    
    /**
     * Installs an adrenotools driver archive (.zip with meta.json) from any Uri:
     * a local file, a content:// pick from the file manager, or a downloaded file.
     * Works offline. Returns the installed driver id, or "" when the archive is not a driver.
     *
     * YurNLator changes vs. upstream: the temp folder is fully cleaned first (a failed
     * install no longer leaks files into the next one), archives with the driver inside
     * a subfolder are accepted, entries can't escape the temp folder (zip-slip), and
     * installing a driver that is already present returns its id instead of failing.
     */
    public String installDriver(Uri driverUri) {
        File tmpDir = new File(adrenotoolsContentDir, "tmp");
        if (tmpDir.exists()) FileUtils.delete(tmpDir);
        tmpDir.mkdirs();
        String name = "";

        try (InputStream is = mContext.getContentResolver().openInputStream(driverUri)) {
            if (is == null) throw new IOException("Cannot open " + driverUri);
            String tmpRoot = tmpDir.getCanonicalPath() + File.separator;
            try (ZipInputStream zis = new ZipInputStream(is)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    File dstFile = new File(tmpDir, entry.getName());
                    if (!dstFile.getCanonicalPath().startsWith(tmpRoot)) continue; // zip-slip
                    if (entry.isDirectory()) {
                        dstFile.mkdirs();
                        continue;
                    }
                    File parent = dstFile.getParentFile();
                    if (parent != null) parent.mkdirs();
                    Files.copy(zis, dstFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }

            File driverRoot = findDriverRoot(tmpDir);
            if (driverRoot == null) {
                Log.d("AdrenotoolsManager", "Failed to install driver: no meta.json in the archive");
                return "";
            }

            String driverName = readMetaName(driverRoot);
            if (driverName.isEmpty() || driverName.contains("/") || driverName.equals("tmp")) {
                Log.d("AdrenotoolsManager", "Failed to install driver: missing or invalid name in meta.json");
                return "";
            }

            File dst = new File(adrenotoolsContentDir, driverName);
            if (dst.exists()) {
                // Already installed: just report it so the caller can select it.
                name = driverName;
            } else if (driverRoot.renameTo(dst)) {
                name = driverName;
            }
        }
        catch (IOException e) {
            Log.d("AdrenotoolsManager", "Failed to install driver: " + e.getMessage());
        }
        finally {
            if (tmpDir.exists()) FileUtils.delete(tmpDir);
        }

        return name;
    }

    /** The folder holding meta.json: the archive root, or a single top-level subfolder. */
    private static File findDriverRoot(File dir) {
        if (new File(dir, "meta.json").isFile()) return dir;
        File[] children = dir.listFiles();
        if (children == null) return null;
        File found = null;
        for (File child : children) {
            if (child.isDirectory() && new File(child, "meta.json").isFile()) {
                if (found != null) return null; // ambiguous: several drivers in one archive
                found = child;
            }
        }
        return found;
    }

    private static String readMetaName(File driverRoot) {
        try {
            JSONObject meta = new JSONObject(FileUtils.readString(new File(driverRoot, "meta.json")));
            return meta.optString("name", "").trim();
        } catch (JSONException e) {
            return "";
        }
    }
    
    public void setDriverById(EnvVars envVars, ImageFs imagefs, String adrenotoolsDriverId) {
        boolean isFromResources = isFromResources(adrenotoolsDriverId);

        if (isFromResources || enumarateInstalledDrivers().contains(adrenotoolsDriverId)) {
            String driverPath = getDriverPath(adrenotoolsDriverId);

            if (!getLibraryName(adrenotoolsDriverId).equals("")) {
                envVars.put("ADRENOTOOLS_DRIVER_PATH", driverPath);
                envVars.put("ADRENOTOOLS_HOOKS_PATH", imagefs.getLibDir());
                envVars.put("ADRENOTOOLS_DRIVER_NAME", getLibraryName(adrenotoolsDriverId));

                File winlatorDir = new File(SettingsFragment.DEFAULT_WINLATOR_PATH);
                File qglConfig = new File(winlatorDir, "qgl_config.txt");
                if (qglConfig.exists())
                    envVars.put("ADRENOTOOLS_REDIRECT_DIR", winlatorDir.getAbsolutePath() + "/");
            }
        }
    }
 }
