package com.winlator.cmod.contents;

import android.content.Context;
import android.net.Uri;

import com.winlator.cmod.contentdialog.DriverRepo;
import com.winlator.cmod.contentdialog.RepositoryManagerDialog;
import com.winlator.cmod.core.RemoteFetch;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class RemoteDriverCatalog {
    private RemoteDriverCatalog() {}

    public static final class Entry {
        public final String repository;
        public final String name;
        public final String url;

        Entry(String repository, String name, String url) {
            this.repository = repository;
            this.name = name;
            this.url = url;
        }
    }

    /** Downloadable drivers; refreshes the saved list from the network when allowed. */
    public static List<Entry> load(Context context) {
        return load(context, true);
    }

    /**
     * Downloadable drivers from the configured repositories.
     * allowNetwork=false: saved list only (instant, offline). Otherwise RemoteFetch decides
     * (online and not fetched in the last minutes -> download, else the saved copy).
     * Installing an entry still needs internet; already installed drivers never do.
     */
    public static List<Entry> load(Context context, boolean allowNetwork) {
        ArrayList<Entry> result = new ArrayList<>();
        for (DriverRepo repo : RepositoryManagerDialog.loadDriverRepos(context, 0)) {
            if (repo.apiUrl == null || repo.apiUrl.isEmpty()) continue;
            String body = allowNetwork ? RemoteFetch.get(context, repo.apiUrl) : RemoteFetch.cached(context, repo.apiUrl);
            if (body != null) parseRepo(repo, body, result);
        }
        return result;
    }

    private static void parseRepo(DriverRepo repo, String body, List<Entry> result) {
        try {
            JSONArray releases = new JSONArray(body);
            int accepted = 0;
            for (int i = 0; i < releases.length() && accepted < 40; i++) {
                JSONObject release = releases.optJSONObject(i);
                if (release == null) continue;
                JSONArray assets = release.optJSONArray("assets");
                if (assets == null) continue;

                String releaseName = release.optString("name", release.optString("tag_name", "")).trim();
                ArrayList<JSONObject> zipAssets = new ArrayList<>();
                for (int j = 0; j < assets.length(); j++) {
                    JSONObject asset = assets.optJSONObject(j);
                    if (asset == null) continue;
                    String url = asset.optString("browser_download_url", "");
                    String assetName = asset.optString("name", "");
                    if (!url.isEmpty() && assetName.toLowerCase(Locale.ENGLISH).endsWith(".zip")) {
                        zipAssets.add(asset);
                    }
                }

                for (JSONObject asset : zipAssets) {
                    if (accepted >= 40) break;
                    String url = asset.optString("browser_download_url", "");
                    String assetName = asset.optString("name", "");
                    String assetLabel = assetName.replaceFirst("(?i)\\.zip$", "").trim();

                    String name;
                    if (zipAssets.size() > 1) {
                        name = assetLabel.isEmpty() ? releaseName : assetLabel;
                    } else {
                        name = releaseName.isEmpty() ? assetLabel : releaseName;
                    }
                    if (name.isEmpty()) continue;

                    result.add(new Entry(repo.name, name, url));
                    accepted++;
                }
            }
        } catch (Exception ignored) {
        }
    }

    public static String install(Context context, String url) {
        File archive = new File(context.getCacheDir(), "winz-driver-" + System.nanoTime() + ".zip");
        try (Response response = new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build().newCall(new Request.Builder().url(url).build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) return "";
            try (InputStream input = response.body().byteStream(); FileOutputStream output = new FileOutputStream(archive)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            }
            return new AdrenotoolsManager(context).installDriver(Uri.fromFile(archive));
        } catch (Exception ignored) {
            return "";
        } finally {
            archive.delete();
        }
    }
}
