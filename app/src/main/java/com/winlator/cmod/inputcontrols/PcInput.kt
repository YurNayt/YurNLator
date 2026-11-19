package com.winlator.cmod.inputcontrols

import android.view.InputDevice
import android.view.KeyEvent

/**
 * "PC" gamepad mode: for games that only know keyboard and mouse. A physical gamepad is turned
 * into keys and the mouse with the layout of the built-in "PC Gamepad" controls, without any
 * on-screen controls and without setting anything up. The game sees no gamepad at all.
 *
 *  - left stick: W A S D, right stick: the mouse, D-pad: the arrow keys;
 *  - A: Space, B: Left Ctrl, X: F, Y: X;
 *  - LB: Q, RB: E, LT: right mouse button, RT: left mouse button;
 *  - Start: Esc, Select: V, L3: Left Shift, R3: Y.
 *
 * A controls profile that has its own bindings for the pad ("External controllers") wins over this.
 * The same layout fills such a profile as a starting point ([fill]) for those who want to change it.
 */
object PcInput {
    private val LAYOUT: List<Pair<Int, String>> = listOf(
        ExternalControllerBinding.AXIS_Y_NEGATIVE.toInt() to "KEY_W",
        ExternalControllerBinding.AXIS_X_NEGATIVE.toInt() to "KEY_A",
        ExternalControllerBinding.AXIS_Y_POSITIVE.toInt() to "KEY_S",
        ExternalControllerBinding.AXIS_X_POSITIVE.toInt() to "KEY_D",
        ExternalControllerBinding.AXIS_RZ_NEGATIVE.toInt() to "MOUSE_MOVE_UP",
        ExternalControllerBinding.AXIS_RZ_POSITIVE.toInt() to "MOUSE_MOVE_DOWN",
        ExternalControllerBinding.AXIS_Z_NEGATIVE.toInt() to "MOUSE_MOVE_LEFT",
        ExternalControllerBinding.AXIS_Z_POSITIVE.toInt() to "MOUSE_MOVE_RIGHT",
        KeyEvent.KEYCODE_DPAD_UP to "KEY_UP",
        KeyEvent.KEYCODE_DPAD_DOWN to "KEY_DOWN",
        KeyEvent.KEYCODE_DPAD_LEFT to "KEY_LEFT",
        KeyEvent.KEYCODE_DPAD_RIGHT to "KEY_RIGHT",
        KeyEvent.KEYCODE_BUTTON_A to "KEY_SPACE",
        KeyEvent.KEYCODE_BUTTON_B to "KEY_CTRL_L",
        KeyEvent.KEYCODE_BUTTON_X to "KEY_F",
        KeyEvent.KEYCODE_BUTTON_Y to "KEY_X",
        KeyEvent.KEYCODE_BUTTON_L1 to "KEY_Q",
        KeyEvent.KEYCODE_BUTTON_R1 to "KEY_E",
        KeyEvent.KEYCODE_BUTTON_L2 to "MOUSE_RIGHT_BUTTON",
        KeyEvent.KEYCODE_BUTTON_R2 to "MOUSE_LEFT_BUTTON",
        KeyEvent.KEYCODE_BUTTON_START to "KEY_ESC",
        KeyEvent.KEYCODE_BUTTON_SELECT to "KEY_V",
        KeyEvent.KEYCODE_BUTTON_THUMBL to "KEY_SHIFT_L",
        KeyEvent.KEYCODE_BUTTON_THUMBR to "KEY_Y",
    )

    /** A controller for [deviceId] with the PC layout; null when it is not a gamepad. */
    @JvmStatic
    fun controllerFor(deviceId: Int): ExternalController? {
        val device = InputDevice.getDevice(deviceId) ?: return null
        if (!ExternalController.isGameController(device)) return null
        val c = ExternalController()
        c.setId(device.descriptor)
        c.setName(ExternalController.displayName(device.name))
        c.setMouseSensitivity(2.0f)
        fill(c)
        return c
    }

    /** Replaces [controller]'s bindings with the PC layout. */
    @JvmStatic
    fun fill(controller: ExternalController) {
        while (controller.getControllerBindingCount() > 0) controller.removeControllerBinding(controller.getControllerBindingAt(0))
        for ((code, name) in LAYOUT) {
            val b = ExternalControllerBinding()
            b.setKeyCode(code)
            b.setBinding(Binding.fromString(name))
            controller.addControllerBinding(b)
        }
    }
}
