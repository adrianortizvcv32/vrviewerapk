package com.example.vrviewer

data class HandPose(
    val x: Float,
    val y: Float,
    val z: Float,
    val tracked: Boolean,
    val qx: Float = 0f,
    val qy: Float = 0f,
    val qz: Float = 0f,
    val qw: Float = 1f,
    val grip: Float = 0f,
    val pinch: Float = 0f,
    val curlThumb: Float = 0f,
    val curlIndex: Float = 0f,
    val curlMiddle: Float = 0f,
    val curlRing: Float = 0f,
    val curlPinky: Float = 0f,
    // true solo en el frame en que el pinch (pulgar+índice) acaba de
    // empezar -- ver PinchClickDetector. Es un pulso de un frame, no
    // un estado sostenido mientras se mantiene el pinch.
    val clicked: Boolean = false
) {

    fun curlArray(): FloatArray = floatArrayOf(curlThumb, curlIndex, curlMiddle, curlRing, curlPinky)

    companion object {

        fun untracked(defaultX: Float): HandPose = HandPose(defaultX, 0.1f, -0.5f, tracked = false)
    }
}