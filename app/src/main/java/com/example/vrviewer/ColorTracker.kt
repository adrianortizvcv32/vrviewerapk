package com.example.vrviewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.graphics.BitmapFactory
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors


enum class TrackingMode { NONE, HAND, LED_BLUE, LED_GREEN, HAND_JOYCONS }


class ColorTracker(
    private val context: Context,
    private val mode: TrackingMode,         // LED_BLUE o LED_GREEN (ambos activan el mismo tracker)
    private val onHands: (left: HandPose, right: HandPose) -> Unit,
    private val onError: (String) -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null

    private val xLo = -0.7f; private val xHi = 0.7f
    private val yLo = -0.3f; private val yHi = 0.7f

    companion object {
        private const val TAG             = "ColorTracker"
        private const val MIN_BLOB_PIXELS = 150
        private const val SAMPLE_STEP     = 3
    }

    fun start(owner: LifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cameraProvider = future.get()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { processFrame(it) }
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            } catch (e: Exception) {
                onError("ColorTracker: error iniciando cámara: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun processFrame(proxy: ImageProxy) {
        try {
            val bmp = proxy.toBitmapNV21() ?: return

            // Verde → mano izquierda / Azul → mano derecha
            val greenBlob = detectSingleColor(bmp, color = "green")  // → mano izquierda
            val blueBlob  = detectSingleColor(bmp, color = "blue")   // → mano derecha

            val left  = blobToPose(greenBlob, defaultX = -0.35f)
            val right = blobToPose(blueBlob,  defaultX =  0.35f)

            onHands(left, right)
        } catch (e: Exception) {
            Log.e(TAG, "Error en frame: ${e.message}")
        } finally {
            proxy.close()
        }
    }


    private fun detectSingleColor(bmp: Bitmap, color: String): FloatArray? {
        val w = bmp.width
        val h = bmp.height

        var sumX = 0f; var sumY = 0f; var count = 0

        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        var i = 0
        while (i < pixels.size) {
            val px = pixels[i]
            val r  = (px shr 16) and 0xFF
            val g  = (px shr 8)  and 0xFF
            val b  =  px         and 0xFF

            val match = when (color) {
                // LED azul: canal B dominante sobre R y G
                "blue"  -> b > 100 && b > r * 2.0f && b > g * 1.4f
                // LED verde: canal G dominante sobre R y B
                "green" -> g > 100 && g > r * 2.0f && g > b * 1.4f
                else    -> false
            }

            if (match) {
                sumX += i % w
                sumY += i / w
                count++
            }
            i += SAMPLE_STEP
        }

        return if (count >= MIN_BLOB_PIXELS)
            floatArrayOf(sumX / count / w, sumY / count / h)
        else
            null
    }


    private fun blobToPose(blob: FloatArray?, defaultX: Float): HandPose {
        if (blob == null) return HandPose(defaultX, 0.1f, -0.5f, false)

        val vx = mapRange(1f - blob[0], 0f, 1f, xLo, xHi)
        val vy = mapRange(blob[1],      1f, 0f, yLo, yHi)
        val vz = -0.45f

        return HandPose(vx, vy, vz, true)
    }

    private fun mapRange(v: Float, i0: Float, i1: Float, o0: Float, o1: Float): Float {
        val c = v.coerceIn(minOf(i0, i1), maxOf(i0, i1))
        return o0 + (c - i0) / (i1 - i0) * (o1 - o0)
    }

    fun stop() {
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        executor.shutdown()
    }
}