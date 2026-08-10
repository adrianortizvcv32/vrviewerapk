package com.example.vrviewer

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import androidx.camera.core.ImageProxy

class HandFrameBuffer(private val maxDim: Int = 224) {

    private var yBytes: ByteArray? = null
    private var uBytes: ByteArray? = null
    private var vBytes: ByteArray? = null
    private var argb: IntArray? = null
    private var bitmap: Bitmap? = null
    private var outW = 0
    private var outH = 0

    private fun ensureByteArray(current: ByteArray?, size: Int): ByteArray =
        if (current != null && current.size == size) current else ByteArray(size)

    private fun ensureOutputBuffers(w: Int, h: Int) {
        if (w != outW || h != outH || argb == null || bitmap == null) {
            outW = w; outH = h
            argb = IntArray(w * h)

            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
    }

    /** Convierte un frame de CameraX (androidx.camera.core.ImageProxy). */
    fun convert(proxy: ImageProxy): Bitmap? {
        if (proxy.format != ImageFormat.YUV_420_888) return null

        val yPlane = proxy.planes[0]
        val uPlane = proxy.planes[1]
        val vPlane = proxy.planes[2]

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val srcW = proxy.width
        val srcH = proxy.height

        val ySize = yPlane.buffer.remaining()
        val uSize = uPlane.buffer.remaining()
        val vSize = vPlane.buffer.remaining()

        yBytes = ensureByteArray(yBytes, ySize).also { yPlane.buffer.get(it) }
        uBytes = ensureByteArray(uBytes, uSize).also { uPlane.buffer.get(it) }
        vBytes = ensureByteArray(vBytes, vSize).also { vPlane.buffer.get(it) }

        return convertCommon(srcW, srcH, yRowStride, uvRowStride, uvPixelStride)
    }


    fun convert(image: Image): Bitmap? {
        if (image.format != ImageFormat.YUV_420_888) return null

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val srcW = image.width
        val srcH = image.height

        val ySize = yPlane.buffer.remaining()
        val uSize = uPlane.buffer.remaining()
        val vSize = vPlane.buffer.remaining()

        yBytes = ensureByteArray(yBytes, ySize).also { yPlane.buffer.get(it) }
        uBytes = ensureByteArray(uBytes, uSize).also { uPlane.buffer.get(it) }
        vBytes = ensureByteArray(vBytes, vSize).also { vPlane.buffer.get(it) }

        return convertCommon(srcW, srcH, yRowStride, uvRowStride, uvPixelStride)
    }

    private fun convertCommon(
        srcW: Int, srcH: Int,
        yRowStride: Int, uvRowStride: Int, uvPixelStride: Int
    ): Bitmap? {
        val yArr = yBytes ?: return null
        val uArr = uBytes ?: return null
        val vArr = vBytes ?: return null

        val longSide = maxOf(srcW, srcH)
        var sample = (longSide + maxDim - 1) / maxDim
        if (sample < 1) sample = 1
        if (sample % 2 != 0) sample += 1

        val w = srcW / sample
        val h = srcH / sample
        if (w <= 0 || h <= 0) return null

        ensureOutputBuffers(w, h)
        val out = argb ?: return null
        val bmp = bitmap ?: return null

        for (row in 0 until h) {
            val srcRow = row * sample
            val yRowBase = srcRow * yRowStride
            val uvRowBase = (srcRow / 2) * uvRowStride
            val outRowBase = row * w
            for (col in 0 until w) {
                val srcCol = col * sample
                val yIdx = yRowBase + srcCol
                val uvIdx = uvRowBase + (srcCol / 2) * uvPixelStride

                val y = yArr[yIdx].toInt() and 0xFF
                val u = (uArr[uvIdx].toInt() and 0xFF) - 128
                val v = (vArr[uvIdx].toInt() and 0xFF) - 128

                val r = (y + ((COEF_R_V * v) shr 16)).coerceIn(0, 255)
                val g = (y - ((COEF_G_U * u) shr 16) - ((COEF_G_V * v) shr 16)).coerceIn(0, 255)
                val b = (y + ((COEF_B_U * u) shr 16)).coerceIn(0, 255)

                out[outRowBase + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }


        bmp.setPixels(out, 0, w, 0, 0, w, h)
        return bmp
    }

    companion object {
        private const val COEF_R_V = 89858   // 1.370705 * 65536
        private const val COEF_G_U = 22124   // 0.337633 * 65536
        private const val COEF_G_V = 45744   // 0.698001 * 65536
        private const val COEF_B_U = 113563  // 1.732446 * 65536
    }
}