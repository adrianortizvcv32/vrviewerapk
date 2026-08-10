package com.example.vrviewer

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

class OneEuroFilter(
    private val minCutoff: Float = 1.0f,
    private val beta: Float = 0.03f,
    private val dCutoff: Float = 1.0f
) {
    private var xPrev: Float? = null
    private var dxPrev: Float = 0f
    private var tPrev: Long = 0L

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }

    /**
     * @param x valor crudo de este frame
     * @param timestampMs timestamp del frame en ms (System.currentTimeMillis())
     */
    fun filter(x: Float, timestampMs: Long): Float {
        val prev = xPrev
        if (prev == null) {
            xPrev = x
            tPrev = timestampMs
            return x
        }

        val dtMs = (timestampMs - tPrev).coerceAtLeast(1)
        val dt = dtMs / 1000f
        tPrev = timestampMs


        val dx = (x - prev) / dt
        val aD = alpha(dCutoff, dt)
        val dxHat = aD * dx + (1 - aD) * dxPrev
        dxPrev = dxHat


        val cutoff = minCutoff + beta * abs(dxHat)
        val a = alpha(cutoff, dt)
        val xHat = a * x + (1 - a) * prev
        xPrev = xHat
        return xHat
    }


    fun reset() {
        xPrev = null
        dxPrev = 0f
        tPrev = 0L
    }
}