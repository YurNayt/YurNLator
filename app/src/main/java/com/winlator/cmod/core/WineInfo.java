package com.winlator.cmod.core;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.NonNull;

import com.winlator.cmod.R;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WineInfo implements Parcelable {
    public static final WineInfo MAIN_WINE_VERSION = new WineInfo("proton", "9.0", "arm64ec");
    private static final Pattern pattern = Pattern.compile("^(wine|proton)\\-([0-9\\.]+)\\-?([0-9\\.]+)?\\-(x86|x86_64|arm64ec)$");
    public final String version;
    public final String type;
    public String subversion;
    public final String path;
    private String arch;

    public WineInfo(String type, String version, String arch) {
        this.type = type;
        this.version = version;
        this.subversion = null;
        this.arch = arch;
        this.path = null;
    }

    public WineInfo(String type, String version, String subversion, String arch, String path) {
        this.type = type;
        this.version = version;
        this.subversion = subversion != null && !subversion.isEmpty() ? subversion : null;
        this.arch = arch;
        this.path = path;
    }

    public WineInfo(String type, String version, String arch, String path) {
        this.type = type;
        this.version = version;
        this.arch = arch;
        this.path = path;
    }

    private WineInfo(Parcel in) {
        type = in.readString();
        version = in.readString();
        subversion = in.readString();
        arch = in.readString();
        path = in.readString();
    }

    public String getArch() {
        return arch;
    }

    public void setArch(String arch) {
        this.arch = arch;
    }

    public boolean isWin64() {
        return arch.equals("x86_64") || arch.equals("arm64ec");
    }

    public boolean isArm64EC() { return arch.equals("arm64ec"); }

    public String identifier() {
        if (type.equals("proton"))
            return "proton-" + fullVersion() + "-"+ arch;
        else
            return "wine-" + fullVersion() + "-" + arch;
    }

    public String fullVersion() {
        return version+(subversion != null ? "-"+subversion : "");
    }

    @NonNull
    @Override
    public String toString() {
        if (type.equals("proton"))
            return "Proton "+fullVersion()+(this == MAIN_WINE_VERSION ? " (Custom)" : "");
        else
            return "Wine "+fullVersion()+(this == MAIN_WINE_VERSION ? " (Custom)" : "");
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<WineInfo> CREATOR = new Parcelable.Creator<WineInfo>() {
        public WineInfo createFromParcel(Parcel in) {
            return new WineInfo(in);
        }

        public WineInfo[] newArray(int size) {
            return new WineInfo[size];
        }
    };

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(type);
        dest.writeString(version);
        dest.writeString(subversion);
        dest.writeString(arch);
        dest.writeString(path);
    }

    @NonNull
    public static WineInfo fromIdentifier(Context context, ContentsManager contentsManager, String identifier) {
        ImageFs imageFs = ImageFs.find(context);
        String path = "";

        Log.d("WineInfo", "Creating WineInfo from identifier " + identifier);

        if (identifier.equals(MAIN_WINE_VERSION.identifier())) return new WineInfo(MAIN_WINE_VERSION.type, MAIN_WINE_VERSION.version, MAIN_WINE_VERSION.arch, imageFs.getRootDir().getPath() + "/opt/" + MAIN_WINE_VERSION.identifier());

        ContentProfile wineProfile = contentsManager.getProfileByEntryName(identifier);

        if (wineProfile != null && (wineProfile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE || wineProfile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON)) {
            // Entry names are "<Type>-<verName>-<verCode>": drop the verCode (any length).
            int lastDash = identifier.lastIndexOf('-');
            identifier = (lastDash > 0 ? identifier.substring(0, lastDash) : identifier).toLowerCase();
            // YurNLator: names from other catalogs ("Proton-11.0-2-arm64ec (v16)", "GE-Proton-11.0-9-…")
            // don't match the strict pattern below; before, they silently fell back to the
            // built-in Proton 9. An installed package always runs from its own folder.
            if (!pattern.matcher(identifier).find()) {
                return fromInstalledProfile(wineProfile, contentsManager.getInstallDir(context, wineProfile).getPath());
            }
        }

        Matcher matcher = pattern.matcher(identifier);

        if (matcher.find()) {
            String[] wineVersions = context.getResources().getStringArray(R.array.wine_entries);
            for (String wineVersion : wineVersions) {
                if (wineVersion.contains(identifier)) {
                    path = imageFs.getRootDir().getPath() + "/opt/" + identifier;
                    break;
                }
            }
            if (path.isEmpty() && ProtonPackageManager.isInstalled(context, identifier)) path = imageFs.getRootDir().getPath() + "/opt/" + identifier;

            if (wineProfile != null && (wineProfile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE || wineProfile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON))
                path = contentsManager.getInstallDir(context, wineProfile).getPath();

            return new WineInfo(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4), path);
        }
        else return new WineInfo(MAIN_WINE_VERSION.type, MAIN_WINE_VERSION.version, MAIN_WINE_VERSION.arch, imageFs.getRootDir().getPath() + "/opt/" + MAIN_WINE_VERSION.identifier());
    }

    private static final Pattern loosePattern = Pattern.compile("(\\d+(?:\\.\\d+)+|\\d+)(?:-(\\d+(?:\\.\\d+)*))?");

    /** Wine / Proton version, sub version and architecture read from a package's free-form name. */
    private static WineInfo fromInstalledProfile(ContentProfile profile, String path) {
        String name = profile.verName != null ? profile.verName.toLowerCase() : "";
        String type = profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON ? "proton" : "wine";
        String arch;
        if (name.contains("arm64ec")) arch = "arm64ec";
        else if (name.contains("x86_64") || name.contains("x86-64")) arch = "x86_64";
        else if (name.contains("x86")) arch = "x86";
        else arch = "arm64ec";
        String cleaned = name.replace("x86_64", " ").replace("x86-64", " ").replace("arm64ec", " ")
                .replaceAll("\\(v\\d+\\)", " ");
        Matcher m = loosePattern.matcher(cleaned);
        String version = "0";
        String subversion = null;
        if (m.find()) {
            version = m.group(1);
            subversion = m.group(2);
        }
        return new WineInfo(type, version, subversion, arch, path);
    }

    public static boolean isMainWineVersion(String wineVersion) {
        return wineVersion == null ||wineVersion.equals(MAIN_WINE_VERSION.identifier());
    }
}
