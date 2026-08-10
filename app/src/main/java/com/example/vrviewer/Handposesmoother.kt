package com.example.vrviewer

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class HandPoseSmoother(
    posMinCutoff: Float = 1.2f,
    posBeta: Float = 0.4f,
    private val quatSlerpFactor: Float = 0.45f,
    private val gripPinchAlpha: Float = 0.5f
) {
    private val fx = OneEuroFilter(posMinCutoff, posBeta)
    private val fy = OneEuroFilter(posMinCutoff, posBeta)
    private val fz = OneEuroFilter(posMinCutoff, posBeta)

    private var qx = 0f; private var qy = 0f; private var qz = 0f; private var qw = 1f
    private var grip = 0f
    private var pinch = 0f
    private val curls = FloatArray(5)

    private var hasState = false
    private var wasTracked = false

    /** @param timestampMs System.currentTimeMillis() del frame actual */
    fun smooth(raw: HandPose, timestampMs: Long): HandPose {
        if (!raw.tracked) {
            wasTracked = false
            return raw
        }

        if (!hasState || !wasTracked) {
            // Primera muestra tracked, o la mano recién "reaparece":
            // arrancar el estado exactamente en el valor crudo.
            reset()
            fx.filter(raw.x, timestampMs)
            fy.filter(raw.y, timestampMs)
            fz.filter(raw.z, timestampMs)
            qx = raw.qx; qy = raw.qy; qz = raw.qz; qw = raw.qw
            grip = raw.grip; pinch = raw.pinch
            raw.curlArray().copyInto(curls)
            hasState = true
            wasTracked = true
            return raw
        }

        val sx = fx.filter(raw.x, timestampMs)
        val sy = fy.filter(raw.y, timestampMs)
        val sz = fz.filter(raw.z, timestampMs)

        slerpTowards(raw.qx, raw.qy, raw.qz, raw.qw, quatSlerpFactor)

        grip += (raw.grip - grip) * gripPinchAlpha
        pinch += (raw.pinch - pinch) * gripPinchAlpha
        val rawCurls = raw.curlArray()
        for (i in curls.indices) curls[i] += (rawCurls[i] - curls[i]) * gripPinchAlpha

        wasTracked = true

        return raw.copy(
            x = sx, y = sy, z = sz,
            qx = qx, qy = qy, qz = qz, qw = qw,
            grip = grip, pinch = pinch,
            curlThumb = curls[0], curlIndex = curls[1], curlMiddle = curls[2],
            curlRing = curls[3], curlPinky = curls[4]
        )
    }


    private fun slerpTowards(tx: Float, ty: Float, tz: Float, tw: Float, t: Float) {
        var dot = qx * tx + qy * ty + qz * tz + qw * tw
        var bx = tx; var by = ty; var bz = tz; var bw = tw

        if (dot < 0f) { bx = -bx; by = -by; bz = -bz; bw = -bw; dot = -dot }

        if (dot > 0.9995f) {

            qx += (bx - qx) * t; qy += (by - qy) * t
            qz += (bz - qz) * t; qw += (bw - qw) * t
        } else {
            val theta0 = acos(dot.coerceIn(-1f, 1f))
            val theta = theta0 * t
            val sinTheta0 = sin(theta0)
            val sinTheta = sin(theta)
            val s0 = cos(theta) - dot * sinTheta / sinTheta0
            val s1 = sinTheta / sinTheta0
            qx = s0 * qx + s1 * bx; qy = s0 * qy + s1 * by
            qz = s0 * qz + s1 * bz; qw = s0 * qw + s1 * bw
        }
        val len = sqrt(qx*qx + qy*qy + qz*qz + qw*qw)
        if (len > 1e-6f) { qx /= len; qy /= len; qz /= len; qw /= len }
    }

    fun reset() {
        fx.reset(); fy.reset(); fz.reset()
        hasState = false
    }
}
