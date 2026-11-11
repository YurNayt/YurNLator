package com.winlator.cmod.session;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.winlator.cmod.R;
import com.winlator.cmod.midi.MidiHandler;
import com.winlator.cmod.midi.MidiManager;

import java.io.File;
import java.io.InputStream;
import java.util.function.Consumer;

import cn.sherlock.com.sun.media.sound.SF2Soundbank;

/**
 * YurNLator base, part 17: small services of a game session, moved out of the game screen —
 * the "running" notification and the container's MIDI sound font.
 */
public final class SessionServices {
    private SessionServices() {}

    /** The "running" notification, only when the app's keep-alive service doesn't already show one. */
    public static void postRunningNotification(Activity activity, String channelId, int notificationId) {
        NotificationChannel channel = new NotificationChannel(channelId, "YurNLator", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("YurNLator X server messages");
        activity.getSystemService(NotificationManager.class).createNotificationChannel(channel);

        if (com.winlator.cmod.services.NotificationService.isRunning()) return;
        PendingIntent pendingIntent = PendingIntent.getActivity(activity, 0,
                new Intent(activity, activity.getClass()), PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(activity, channelId)
                .setSmallIcon(R.drawable.ic_stat_ab_gear_0011)
                .setContentTitle(activity.getString(R.string.app_name))
                .setContentText(activity.getString(R.string.app_name) + " is running, don't close this notification")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(false);
        NotificationManagerCompat.from(activity).notify(notificationId, builder.build());
    }

    /**
     * MIDI: the container's sound font ("" = none), loaded in the background; [started] gets the
     * running MIDI handler (the session stops it at exit).
     */
    public static void loadMidi(Context context, String soundFont, Consumer<MidiHandler> started) {
        if (soundFont == null || soundFont.isEmpty()) return;
        try {
            boolean builtIn = soundFont.equals(MidiManager.DEFAULT_SF2_FILE);
            final InputStream in = builtIn ? context.getAssets().open(MidiManager.SF2_ASSETS_DIR + "/" + soundFont) : null;
            MidiManager.OnMidiLoadedCallback callback = new MidiManager.OnMidiLoadedCallback() {
                @Override
                public void onSuccess(SF2Soundbank soundbank) {
                    MidiHandler handler = new MidiHandler();
                    handler.setSoundBank(soundbank);
                    handler.start();
                    started.accept(handler);
                }

                @Override
                public void onFailed(Exception e) {
                    if (in != null) try { in.close(); } catch (Exception ignored) {}
                }
            };
            if (builtIn) MidiManager.load(in, callback);
            else MidiManager.load(new File(MidiManager.getSoundFontDir(context), soundFont), callback);
        } catch (Exception ignored) {
        }
    }
}
