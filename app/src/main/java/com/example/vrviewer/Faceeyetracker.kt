package com.example.vrviewer

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.Executors

class FaceEyeTracker(
    private val context: Context,
    private val pcIp: String,
    private val oscPort: Int = 9000,
    private val onError: (String) -> Unit,
    private val onReady: (() -> Unit)? = null
) {
    private var faceLandmarker: FaceLandmarker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var lastTimestamp = 0L


    private var lastSendMs = 0L
    private val SEND_INTERVAL_MS = 25L   // ~40 Hz


    private var smoothPitch = 0f
    private var smoothYaw   = 0f
    private var smoothBlink = 0f
    private val SMOOTH_FACTOR = 0.35f   // más alto = responde más rápido


    private val MAX_YAW_DEG   = 30f
    private val MAX_PITCH_DEG = 25f

    fun start(owner: LifecycleOwner) {
        try {
            setupLandmarker()
        } catch (e: Exception) {
            onError("Error cargando face_landmarker.task: ${e.message}")
            return
        }

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cameraProvider = future.get()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy -> processFrame(proxy) }

                cameraProvider?.bindToLifecycle(
                    owner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    analysis
                )
                onReady?.invoke()
            } catch (e: Exception) {
                onError("FaceEyeTracker: error iniciando cámara frontal: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun setupLandmarker() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task")
            .build()

        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(true)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setResultListener(::onResult)
            .setErrorListener { e -> onError("FaceLandmarker: ${e.message}") }
            .build()

        faceLandmarker = FaceLandmarker.createFromOptions(context, options)
    }

    private fun processFrame(proxy: ImageProxy) {
        try {
            val bmp = proxy.toBitmapFast() ?: return
            val mpImage = BitmapImageBuilder(bmp).build()
            val ts = System.currentTimeMillis()
            if (ts > lastTimestamp) {
                faceLandmarker?.detectAsync(mpImage, ts)
                lastTimestamp = ts
            }
        } catch (e: Exception) {
            onError("FaceEyeTracker frame: ${e.message}")
        } finally {
            proxy.close()
        }
    }

    private fun onResult(
        result: FaceLandmarkerResult,
        @Suppress("UNUSED_PARAMETER") input: com.google.mediapipe.framework.image.MPImage
    ) {
        if (result.faceBlendshapes().isEmpty || result.faceBlendshapes().get().isEmpty()) return

        val categories = result.faceBlendshapes().get()[0]
        fun score(name: String): Float =
            categories.firstOrNull { it.categoryName() == name }?.score() ?: 0f

        val lookInLeft   = score("eyeLookInLeft")
        val lookOutLeft  = score("eyeLookOutLeft")
        val lookUpLeft   = score("eyeLookUpLeft")
        val lookDownLeft = score("eyeLookDownLeft")

        val lookInRight   = score("eyeLookInRight")
        val lookOutRight  = score("eyeLookOutRight")
        val lookUpRight   = score("eyeLookUpRight")
        val lookDownRight = score("eyeLookDownRight")

        val blinkLeft  = score("eyeBlinkLeft")
        val blinkRight = score("eyeBlinkRight")


        val yawRaw = (
                (lookOutRight - lookInRight) + (lookInLeft - lookOutLeft)
                ) / 2f


        val pitchRaw = (
                (lookDownLeft - lookUpLeft) + (lookDownRight - lookUpRight)
                ) / 2f

        val blinkRaw = (blinkLeft + blinkRight) / 2f


        smoothPitch += (pitchRaw - smoothPitch) * SMOOTH_FACTOR
        smoothYaw   += (yawRaw   - smoothYaw)   * SMOOTH_FACTOR
        smoothBlink += (blinkRaw - smoothBlink) * SMOOTH_FACTOR

        val now = System.currentTimeMillis()
        if (now - lastSendMs >= SEND_INTERVAL_MS) {
            lastSendMs = now

            val pitchDeg = (smoothPitch * MAX_PITCH_DEG).coerceIn(-MAX_PITCH_DEG, MAX_PITCH_DEG)
            val yawDeg   = (smoothYaw   * MAX_YAW_DEG).coerceIn(-MAX_YAW_DEG, MAX_YAW_DEG)

            OscSender.sendFloats(pcIp, oscPort, "/tracking/eye/CenterPitchYaw", pitchDeg, yawDeg)
            OscSender.sendFloat(pcIp, oscPort, "/tracking/eye/EyesClosedAmount", smoothBlink.coerceIn(0f, 1f))
        }
    }

    fun stop() {
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        try { faceLandmarker?.close() } catch (_: Exception) {}
        faceLandmarker = null
        executor.shutdown()
    }
}