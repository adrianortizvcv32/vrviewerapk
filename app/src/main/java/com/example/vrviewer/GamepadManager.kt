package com.example.vrviewer

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

class GamepadManager(
    private val onLeft:  (GamepadState) -> Unit,
    private val onRight: (GamepadState) -> Unit
) {
    data class GamepadState(
        val trigger:  Float = 0f,
        val grip:     Float = 0f,
        val joyX:     Float = 0f,
        val joyY:     Float = 0f,
        val sysBtn:   Boolean = false,
        val appBtn:   Boolean = false,
        val clickBtn: Boolean = false,

        val qx: Float = 0f,
        val qy: Float = 0f,
        val qz: Float = 0f,
        val qw: Float = 1f,
        val hasGyro: Boolean = false,

        val joyConConnected: Boolean = false
    )

    private var leftState  = GamepadState()
    private var rightState = GamepadState()


    fun applyJoyConState(side: JoyConHidManager.Side, s: JoyConHidManager.JoyConState) {
        if (side == JoyConHidManager.Side.LEFT) {
            leftState = leftState.copy(
                trigger = s.trigger, grip = s.grip,
                joyX = s.joyX, joyY = s.joyY,
                sysBtn = s.sysBtn, appBtn = s.appBtn, clickBtn = s.clickBtn,
                qx = s.qx, qy = s.qy, qz = s.qz, qw = s.qw,
                hasGyro = s.hasGyro,
                joyConConnected = s.connected
            )
            onLeft(leftState)
        } else {
            rightState = rightState.copy(
                trigger = s.trigger, grip = s.grip,
                joyX = s.joyX, joyY = s.joyY,
                sysBtn = s.sysBtn, appBtn = s.appBtn, clickBtn = s.clickBtn,
                qx = s.qx, qy = s.qy, qz = s.qz, qw = s.qw,
                hasGyro = s.hasGyro,
                joyConConnected = s.connected
            )
            onRight(rightState)
        }
    }


    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!isGamepad(event.device)) return false


        if (isNamedJoyCon(event.device)) return true


        val lx = getCenteredAxis(event, MotionEvent.AXIS_X)
        val ly = -getCenteredAxis(event, MotionEvent.AXIS_Y)

        val rx = getCenteredAxis(event, MotionEvent.AXIS_Z)
        val ry = getCenteredAxis(event, MotionEvent.AXIS_RZ)

        val lt = maxOf(
            event.getAxisValue(MotionEvent.AXIS_BRAKE),
            event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
        ).coerceIn(0f, 1f)

        val rt = maxOf(
            event.getAxisValue(MotionEvent.AXIS_GAS),
            event.getAxisValue(MotionEvent.AXIS_RTRIGGER)
        ).coerceIn(0f, 1f)

        leftState = leftState.copy(joyX = lx, joyY = ly, trigger = lt)
        onLeft(leftState)

        rightState = rightState.copy(joyX = rx, joyY = ry, trigger = rt)
        onRight(rightState)

        return true
    }


    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isGamepad(event.device)) return false
        if (isNamedJoyCon(event.device)) return true // ver nota en onGenericMotionEvent
        return handleKey(keyCode, pressed = true, device = event.device)
    }

    fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!isGamepad(event.device)) return false
        if (isNamedJoyCon(event.device)) return true
        return handleKey(keyCode, pressed = false, device = event.device)
    }

    private fun handleKey(keyCode: Int, pressed: Boolean, device: InputDevice): Boolean {
        when (keyCode) {


            KeyEvent.KEYCODE_BUTTON_L1 -> {
                leftState = leftState.copy(grip = if (pressed) 1f else 0f)
                onLeft(leftState); return true
            }


            KeyEvent.KEYCODE_BUTTON_R1 -> {
                rightState = rightState.copy(grip = if (pressed) 1f else 0f)
                onRight(rightState); return true
            }


            KeyEvent.KEYCODE_BUTTON_THUMBL -> {
                leftState = leftState.copy(clickBtn = pressed)
                onLeft(leftState); return true
            }


            KeyEvent.KEYCODE_BUTTON_THUMBR -> {
                rightState = rightState.copy(clickBtn = pressed)
                onRight(rightState); return true
            }


            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_MENU -> {
                rightState = rightState.copy(appBtn = pressed)
                onRight(rightState); return true
            }


            KeyEvent.KEYCODE_BUTTON_SELECT -> {
                leftState = leftState.copy(sysBtn = pressed)
                onLeft(leftState); return true
            }


            KeyEvent.KEYCODE_BUTTON_A -> {
                rightState = rightState.copy(trigger = if (pressed) 1f else 0f)
                onRight(rightState); return true
            }


            KeyEvent.KEYCODE_BUTTON_X -> {
                leftState = leftState.copy(trigger = if (pressed) 1f else 0f)
                onLeft(leftState); return true
            }
        }
        return false
    }


    fun recenterLeft(hidManager: JoyConHidManager)  = hidManager.recenter(JoyConHidManager.Side.LEFT)
    fun recenterRight(hidManager: JoyConHidManager) = hidManager.recenter(JoyConHidManager.Side.RIGHT)
    fun recenterBoth(hidManager: JoyConHidManager)  = hidManager.recenterBoth()



    private fun getCenteredAxis(event: MotionEvent, axis: Int): Float {
        val v = event.getAxisValue(axis)
        return if (abs(v) > 0.1f) v else 0f
    }

    private fun isGamepad(device: InputDevice?): Boolean {
        if (device == null) return false
        val s = device.sources
        return (s and InputDevice.SOURCE_GAMEPAD)  == InputDevice.SOURCE_GAMEPAD ||
                (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }


    private fun isNamedJoyCon(device: InputDevice?): Boolean {
        if (device == null) return false
        val name = device.name.lowercase()
        return name.contains("joy-con") || name.contains("joycon")
    }

    companion object {
        fun connectedGamepads(): List<String> {
            return InputDevice.getDeviceIds()
                .toList()
                .mapNotNull { id -> InputDevice.getDevice(id) }
                .filter { dev ->
                    val s = dev.sources
                    (s and InputDevice.SOURCE_GAMEPAD)  == InputDevice.SOURCE_GAMEPAD ||
                            (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                }
                .map { dev -> dev.name }
        }
    }
}