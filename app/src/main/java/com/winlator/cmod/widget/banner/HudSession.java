package com.winlator.cmod.widget.banner;

import java.util.function.Supplier;

/** What the HUD styles learn from the running game session (set by the game screen, cleared at its end). */
public final class HudSession {
    private HudSession() {}

    /** The game's screen ("1280x720": the container's screen or the smaller mode the game set), or null. */
    public static volatile Supplier<String> gameResolution;
}
