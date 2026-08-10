package com.example.vrviewer

import kotlin.math.sqrt


class MadgwickAHRS(
    private var sampleFreqHz: Float = 200f,

    var beta: Float = 0.08f
) {

    private var q0 = 1f
    private var q1 = 0f
    private var q2 = 0f
    private var q3 = 0f

    fun setSampleFrequency(hz: Float) {
        if (hz > 0f) sampleFreqHz = hz
    }

    fun reset() {
        q0 = 1f; q1 = 0f; q2 = 0f; q3 = 0f
    }


    fun quaternion(): FloatArray = floatArrayOf(q0, q1, q2, q3)


    fun update(gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float) {
        var qDot1 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz)
        var qDot2 = 0.5f * (q0 * gx + q2 * gz - q3 * gy)
        var qDot3 = 0.5f * (q0 * gy - q1 * gz + q3 * gx)
        var qDot4 = 0.5f * (q0 * gz + q1 * gy - q2 * gx)


        val accNormSq = ax * ax + ay * ay + az * az
        if (accNormSq > 1e-12f) {
            val recipNorm = 1f / sqrt(accNormSq)
            val nax = ax * recipNorm
            val nay = ay * recipNorm
            val naz = az * recipNorm

            val q0q0 = q0 * q0; val q0q1 = q0 * q1; val q0q2 = q0 * q2; val q0q3 = q0 * q3
            val q1q1 = q1 * q1; val q1q2 = q1 * q2; val q1q3 = q1 * q3
            val q2q2 = q2 * q2; val q2q3 = q2 * q3
            val q3q3 = q3 * q3


            val f1 = 2f * (q1q3 - q0q2) - nax
            val f2 = 2f * (q0q1 + q2q3) - nay
            val f3 = 2f * (0.5f - q1q1 - q2q2) - naz


            val s0 = (-2f * q2) * f1 + (2f * q1) * f2

            val s1 =  (2f * q3) * f1 + (2f * q0) * f2 + (-4f * q1) * f3
            val s2 = (-2f * q0) * f1 + (2f * q3) * f2 + (-4f * q2) * f3
            val s3 =  (2f * q1) * f1 + (2f * q2) * f2 + 0f * f3

            var normS = sqrt(s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3)
            if (normS < 1e-9f) normS = 1e-9f
            val recipNormS = 1f / normS

            qDot1 -= beta * (s0 * recipNormS)
            qDot2 -= beta * (s1 * recipNormS)
            qDot3 -= beta * (s2 * recipNormS)
            qDot4 -= beta * (s3 * recipNormS)
        }

        val dt = 1f / sampleFreqHz
        q0 += qDot1 * dt
        q1 += qDot2 * dt
        q2 += qDot3 * dt
        q3 += qDot4 * dt

        var recipNormQ = sqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3)
        if (recipNormQ < 1e-9f) recipNormQ = 1e-9f
        recipNormQ = 1f / recipNormQ
        q0 *= recipNormQ
        q1 *= recipNormQ
        q2 *= recipNormQ
        q3 *= recipNormQ
    }
}