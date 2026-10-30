package com.winlator.cmod.contents;

import android.content.Context;
import android.net.Uri;

import com.winlator.cmod.core.RemoteFetch;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
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

    /** Downloads listed per author, so one huge feed can't bury the others. */
    private static final int MAX_PER_SOURCE = 40;

    /** Downloadable drivers; refreshes the saved list from the network when allowed. */
    public static List<Entry> load(Context context) {
        return load(context, true);
    }

    /**
     * Downloadable drivers from all driver authors (see DriverSources); the download
     * screen filters them by author.
     * allowNetwork=false: saved lists only (instant, offline). Otherwise RemoteFetch decides
     * (online and not fetched in the last minutes -> download, else the saved copy).
     * Entry.repository is the author name. Installing still needs internet;
     * already installed drivers never do.
     */
    public static List<Entry> load(Context context, boolean allowNetwork) {
        ArrayList<Entry> result = new ArrayList<>();
        HashSet<String> seenUrls = new HashSet<>();
        for (DriverSource source : DriverSources.all(context)) {
            ArrayList<Entry> fromSource = new ArrayList<>();
            for (DriverFeed feed : source.getFeeds()) {
                String url = feed instanceof DriverFeed.Github
                        ? ((DriverFeed.Github) feed).getApiUrl()
                        : ((DriverFeed.Json) feed).getUrl();
                String body = allowNetwork ? RemoteFetch.get(context, url) : RemoteFetch.cached(context, url);
                if (body == null) continue;
                if (feed instanceof DriverFeed.Github) parseGithubReleases(source.getName(), body, fromSource);
                else parseJsonFeed(source.getName(), body, fromSource);
            }
            int taken = 0;
            for (Entry e : fromSource) {
                if (taken >= MAX_PER_SOURCE) break;
                if (!seenUrls.add(e.url)) continue;
                result.add(e);
                taken++;
            }
        }
        return result;
    }

    /** Bannerlator / Winlator contents format: [{"verName": "...", "remoteUrl": "...zip"}]. */
    private static void parseJsonFeed(String author, String body, List<Entry> result) {
        try {
            JSONArray array = new JSONArray(body);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) continue;
                String url = o.optString("remoteUrl", "");
                if (url.isEmpty() || !url.toLowerCase(Locale.ENGLISH).endsWith(".zip")) continue;
                String name = o.optString("verName", "").trim();
                if (name.isEmpty()) name = url.substring(url.lastIndexOf('/') + 1).replaceFirst("(?i)\\.zip$", "");
                result.add(new Entry(author, name, url));
            }
        } catch (Exception ignored) {
        }
    }

    /** GitHub releases API response: every .zip asset of a release is one driver. */
    private static void parseGithubReleases(String author, String body, List<Entry> result) {
        try {
            JSONArray releases = new JSONArray(body);
            for (int i = 0; i < releases.length(); i++) {
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
                    String url = asset.optString("browser_download_url", "");
                    String assetLabel = asset.optString("name", "").replaceFirst("(?i)\\.zip$", "").trim();
                    // One asset: the release title reads best. Several: each asset is its own driver.
                    String name = zipAssets.size() > 1
                            ? (assetLabel.isEmpty() ? releaseName : assetLabel)
                            : (releaseName.isEmpty() ? assetLabel : releaseName);
                    if (name.isEmpty()) continue;
                    result.add(new Entry(author, name, url));
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
