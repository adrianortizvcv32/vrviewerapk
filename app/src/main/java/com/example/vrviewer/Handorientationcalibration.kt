package com.example.vrviewer

object HandOrientationCalibration {


    private val IDENTITY = floatArrayOf(0f, 0f, 0f, 1f)

    @Volatile private var leftOffset: FloatArray = IDENTITY.copyOf()
    @Volatile private var rightOffset: FloatArray = IDENTITY.copyOf()

    @Volatile private var pendingLeft = false
    @Volatile private var pendingRight = false


    fun recenterLeft() { pendingLeft = true }


    fun recenterRight() { pendingRight = true }

    fun recenterBoth() { recenterLeft(); recenterRight() }


    fun clearLeft()  { leftOffset = IDENTITY.copyOf() }
    fun clearRight() { rightOffset = IDENTITY.copyOf() }

    fun correct(isRight: Boolean, raw: HandPose): Pair<HandPose, Boolean> {
        if (!raw.tracked) return raw to false

        var justRecalibrated = false

        if (isRight) {
            if (pendingRight) {
                rightOffset = conjugate(floatArrayOf(raw.qx, raw.qy, raw.qz, raw.qw))
                pendingRight = false
                justRecalibrated = true
            }
        } else {
            if (pendingLeft) {
                leftOffset = conjugate(floatArrayOf(raw.qx, raw.qy, raw.qz, raw.qw))
                pendingLeft = false
                justRecalibrated = true
            }
        }

        val offset = if (isRight) rightOffset else leftOffset

        val q = multiply(floatArrayOf(raw.qx, raw.qy, raw.qz, raw.qw), offset)

        val corrected = raw.copy(qx = q[0], qy = q[1], qz = q[2], qw = q[3])
        return corrected to justRecalibrated
    }


    private fun conjugate(q: FloatArray): FloatArray =
        floatArrayOf(-q[0], -q[1], -q[2], q[3])


    private fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val ax = a[0]; val ay = a[1]; val az = a[2]; val aw = a[3]
        val bx = b[0]; val by = b[1]; val bz = b[2]; val bw = b[3]

        val w = aw*bw - ax*bx - ay*by - az*bz
        val x = aw*bx + ax*bw + ay*bz - az*by
        val y = aw*by - ax*bz + ay*bw + az*bx
        val z = aw*bz + ax*by - ay*bx + az*bw

        return floatArrayOf(x, y, z, w)
    }
}