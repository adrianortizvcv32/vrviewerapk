package com.example.vrviewer

import android.content.Context
import android.graphics.PointF
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraPreviewManager(
    private val context: Context,
    private val previewView: PreviewView,
    private val overlayView: SkeletonOverlayView
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var executor: ExecutorService = Executors.newSingleThreadExecutor()


    private val TARGET_ANALYSIS_SIZE = Size(640, 480)

    fun startWithHandTracker(
        owner:    LifecycleOwner,
        onHands:  (HandPose, HandPose) -> Unit,
        onError:  (String) -> Unit,
        onReady:  (() -> Unit)? = null
    ) {
        stopInternal()
        executor = Executors.newSingleThreadExecutor()

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cameraProvider = future.get()

                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                // CAMBIO 3 aplicado aquí:
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(TARGET_ANALYSIS_SIZE)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                val tracker = HandTrackerWithOverlay(
                    context    = context,
                    onHands    = onHands,
                    onSkeleton = { left, right ->
                        overlayView.post { overlayView.updateHandLandmarks(left, right) }
                    },
                    onError    = { msg -> onError(msg) }
                )

                try {
                    tracker.setupAndAnalyze(analysis, executor)
                } catch (e: Exception) {
                    onError("Error cargando hand_landmarker.task: ${e.message}")
                    return@addListener
                }

                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    owner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )

                onReady?.invoke()

            } catch (e: Exception) {
                onError("CameraPreviewManager (hand): ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun startWithColorTracker(
        owner:   LifecycleOwner,
        onHands: (HandPose, HandPose) -> Unit,
        onError: (String) -> Unit,
        onReady: (() -> Unit)? = null
    ) {
        stopInternal()
        executor = Executors.newSingleThreadExecutor()

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cameraProvider = future.get()

                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                // CAMBIO 3 aplicado también al color tracker:
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(TARGET_ANALYSIS_SIZE)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                val tracker = ColorTrackerWithOverlay(
                    context = context,
                    onHands = onHands,
                    onBlobs = { l, r ->
                        overlayView.post { overlayView.updateBlobs(l, r) }
                    },
                    onError = onError
                )
                tracker.setupAndAnalyze(analysis, executor)

                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    owner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )

                onReady?.invoke()

            } catch (e: Exception) {
                onError("CameraPreviewManager (color): ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        stopInternal()
        overlayView.post { overlayView.clear() }
    }

    private fun stopInternal() {
        try { cameraProvider?.unbindAll(); cameraProvider = null } catch (_: Exception) {}
        try { executor.shutdownNow() } catch (_: Exception) {}
    }
}