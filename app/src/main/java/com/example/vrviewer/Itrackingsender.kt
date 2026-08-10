package com.example.vrviewer

import android.hardware.SensorManager

/**
 * Superficie común entre VrUdpSender (WiFi/RNDIS, UDP) y
 * UsbTrackingSender (cable + adb reverse, TCP). MainActivity habla
 * contra esta interfaz y no le importa cuál de las dos hay detrás.
 *
 * Si agregás un método nuevo a VrUdpSender que MainActivity necesite
 * llamar de forma polimórfica, agregalo acá también y en
 * UsbTrackingSender (aunque sea una implementación vacía / no-op).
 */
interface ITrackingSender {
    fun setHmdEnabled(enabled: Boolean)
    fun setSixDofEnabled(enabled: Boolean)
    fun updateSixDofPose(pose: HmdPose6Dof)
    fun updateHands(left: HandPose, right: HandPose)
    fun updateLeftGamepad(state: GamepadManager.GamepadState)
    fun updateRightGamepad(state: GamepadManager.GamepadState)
    fun setHandJoyconsMode(active: Boolean)
    fun setPlainHandJoyconsMode(active: Boolean)
    fun recenter()
    fun start(sm: SensorManager)
    fun stop()
}