package com.winlator.cmod.core;

/**
 * Frame generation settings. The engine is win-fg, Bannerlator's clean-room frame generation
 * (MIT, https://github.com/The412Banner/win-fg), running inside the Vulkan renderer
 * (cpp/winlator/renderer/vulkan/VulkanRendererFrameGen.cpp).
 *
 * Container / game extras: frameGen = off | on, frameGenMultiplier 2-4,
 * frameGenPreset 0 Quality / 1 Balanced / 2 Performance, frameGenFlowScale 0.25-1.
 */
public final class WinFrameGen {
    public static final String OFF = "off";
    public static final String ON = "on";

    private WinFrameGen() {}
}
