package com.winlator.cmod.session;

import android.content.Context;

import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.cmod.xenvironment.XEnvironment;
import com.winlator.cmod.xenvironment.components.ALSAServerComponent;
import com.winlator.cmod.xenvironment.components.PulseAudioComponent;
import com.winlator.cmod.xenvironment.components.SysVSharedMemoryComponent;
import com.winlator.cmod.xenvironment.components.XServerComponent;
import com.winlator.cmod.xserver.XServer;

/**
 * The servers a launch runs next to the game: SysV shared memory, the X server's socket and the
 * audio server (ALSA, PulseAudio or GameNative's PulseAudio), with the environment variables that
 * point the guest at them. The game's own launcher component is added by the caller. Moved out
 * of the game screen (base migration).
 */
public final class XEnvironmentSetup {
    private XEnvironmentSetup() {}

    public static XEnvironment create(Context context, ImageFs imageFs, XServer xServer, String audioDriver, EnvVars envVars) {
        String root = imageFs.getRootDir().getPath();
        XEnvironment environment = new XEnvironment(context, imageFs);
        environment.addComponent(new SysVSharedMemoryComponent(xServer,
                UnixSocketConfig.createSocket(root, UnixSocketConfig.SYSVSHM_SERVER_PATH)));
        environment.addComponent(new XServerComponent(xServer,
                UnixSocketConfig.createSocket(root, UnixSocketConfig.XSERVER_PATH)));
        if ("alsa".equals(audioDriver)) {
            envVars.put("ANDROID_ALSA_SERVER", root + UnixSocketConfig.ALSA_SERVER_PATH);
            envVars.put("ANDROID_ASERVER_USE_SHM", "true");
            environment.addComponent(new ALSAServerComponent(
                    UnixSocketConfig.createSocket(root, UnixSocketConfig.ALSA_SERVER_PATH)));
        } else if ("pulseaudio".equals(audioDriver) || "pulse-audio-gn".equals(audioDriver)) {
            envVars.put("PULSE_SERVER", root + UnixSocketConfig.PULSE_SERVER_PATH);
            environment.addComponent(new PulseAudioComponent(
                    UnixSocketConfig.createSocket(root, UnixSocketConfig.PULSE_SERVER_PATH),
                    "pulse-audio-gn".equals(audioDriver)));
        }
        return environment;
    }
}
