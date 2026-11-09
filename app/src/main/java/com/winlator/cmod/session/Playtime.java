package com.winlator.cmod.session;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

/**
 * Play time and launch count per game (shared preferences "playtime_stats", keys
 * "<name>_playtime" in ms and "<name>_play_count"), saved every second while the game screen is
 * in the foreground. Moved out of the game screen (base migration).
 */
public final class Playtime {
    private static final long SAVE_INTERVAL_MS = 1000;

    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() { save(); handler.postDelayed(this, SAVE_INTERVAL_MS); }
    };
    private String name;
    private long since = System.currentTimeMillis();

    public Playtime(Context context) {
        prefs = context.getSharedPreferences("playtime_stats", Context.MODE_PRIVATE);
    }

    /** The game the time counts for (its shortcut's name). */
    public void setName(String name) { this.name = name; }

    /** Counting (again): from now, saved every second. */
    public void resume() {
        since = System.currentTimeMillis();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, SAVE_INTERVAL_MS);
    }

    /** Saves what has run since the last save and stops counting. */
    public void pause() {
        save();
        handler.removeCallbacks(tick);
    }

    public void save() {
        long now = System.currentTimeMillis();
        long played = Math.max(0, now - since);
        since = now;
        String key = name + "_playtime";
        prefs.edit().putLong(key, prefs.getLong(key, 0) + played).apply();
    }

    /** "Played 3 h 12 min · 14 launches" for the game's page; "" before its first launch. */
    public static String describe(Context context, String name) {
        SharedPreferences p = context.getSharedPreferences("playtime_stats", Context.MODE_PRIVATE);
        long ms = p.getLong(name + "_playtime", 0);
        int launches = p.getInt(name + "_play_count", 0);
        if (ms <= 0 && launches <= 0) return "";
        long min = ms / 60000;
        String time = min >= 60 ? (min / 60) + " h " + (min % 60) + " min" : min > 0 ? min + " min" : "under a minute";
        return "Played " + time + " · " + launches + (launches == 1 ? " launch" : " launches");
    }

    public void countLaunch() {
        String key = name + "_play_count";
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply();
    }
}
