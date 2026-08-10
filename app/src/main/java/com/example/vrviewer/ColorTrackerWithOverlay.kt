package com.example.vrviewer

import android.content.Context
import android.graphics.PointF
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.ExecutorService
import kotlin.math.sqrt

class ColorTrackerWithOverlay(
    private val context: Context,
    private val onHands: (HandPose, HandPose) -> Unit,
    private val onBlobs: (PointF?, PointF?) -> Unit,
    private val onError: (String) -> Unit
) {
    private val MIN_BLOB = 80
    private val STEP     = 2

    private val xLo = -0.7f; private val xHi = 0.7f
    private val yLo = -0.3f; private val yHi = 0.7f


    private val BLOB_NEAR  = 0.010f   // fracción del área total → Z cercana
    private val BLOB_FAR   = 0.001f   // fracción del área total → Z lejana
    private val Z_NEAR     = -0.25f   // metros en espacio VR (cerca)
    private val Z_FAR      = -0.75f   // metros en espacio VR (lejos)

    fun setupAndAnalyze(analysis: ImageAnalysis, executor: ExecutorService) {
        analysis.setAnalyzer(executor) { proxy -> processFrame(proxy) }
    }

    private fun processFrame(proxy: ImageProxy) {
        try {
            val bmp = proxy.toBitmapFastHalf() ?: return

            val green = detectBlob(bmp, "green")
            val blue  = detectBlob(bmp, "blue")

            val left  = blobToPose(green, -0.35f)
            val right = blobToPose(blue,   0.35f)
            onHands(left, right)


            val leftPt  = green?.let { PointF(it[0], it[1]) }
            val rightPt = blue?.let  { PointF(it[0], it[1]) }
            onBlobs(leftPt, rightPt)
        } catch (e: Exception) {
            onError("ColorTracker frame: ${e.message}")
        } finally {
            proxy.close()
        }
    }


    private fun detectBlob(bmp: android.graphics.Bitmap, color: String): FloatArray? {
        val w = bmp.width
        val h = bmp.height
        val totalPixels = (w * h).toFloat()
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        var sx = 0f; var sy = 0f; var n = 0; var i = 0
        val size = pixels.size
        while (i < size) {
            val px = pixels[i]
            val r = (px shr 16) and 0xFF
            val g = (px shr  8) and 0xFF
            val b =  px         and 0xFF

            val match = when (color) {
                "blue"  -> b > 90 && b > r * 1.8f && b > g * 1.3f
                "green" -> g > 90 && g > r * 1.8f && g > b * 1.3f
                else    -> false
            }
            if (match) { sx += i % w; sy += i / w; n++ }
            i += STEP
        }

        if (n < MIN_BLOB) return null

        val cx = sx / n / w
        val cy = sy / n / h
        // n es el conteo con STEP=2, así que el conteo real de píxeles ≈ n * STEP
        val areaFraction = (n.toFloat() * STEP) / totalPixels

        return floatArrayOf(cx, cy, areaFraction)
    }

    private fun blobToPose(blob: FloatArray?, defaultX: Float): HandPose {
        if (blob == null) return HandPose(defaultX, 0.1f, -0.5f, tracked = false)

        val cx = blob[0]
        val cy = blob[1]
        val area = blob[2]


        val vx = mapRange(cx, 0f, 1f, xLo, xHi)
        val vy = mapRange(cy, 1f, 0f, yLo, yHi)


        val vz = mapRange(area, BLOB_FAR, BLOB_NEAR, Z_FAR, Z_NEAR)
            .coerceIn(Z_FAR, Z_NEAR)

        return HandPose(vx, vy, vz, tracked = true)
    }

    private fun mapRange(v: Float, i0: Float, i1: Float, o0: Float, o1: Float): Float {
        val c = v.coerceIn(minOf(i0, i1), maxOf(i0, i1))
        return o0 + (c - i0) / (i1 - i0) * (o1 - o0)
    }
}