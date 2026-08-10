package com.example.vrviewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.sqrt



class HandTracker(
    private val context: Context,
    private val onHands: (left: HandPose, right: HandPose) -> Unit,
    private val onError: (String) -> Unit
) {
    private var handLandmarker: HandLandmarker? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private val xLo = -0.7f; private val xHi = 0.7f
    private val yLo = -0.3f; private val yHi = 0.7f
    private val zBase = -0.5f
    private val zScale = 1.5f

    private var lastTimestamp = 0L


    private val leftSmoother  = HandPoseSmoother()
    private val rightSmoother = HandPoseSmoother()

    // Un detector de click por mano: cada uno guarda su propio estado
    // "¿estaba pinchando el frame anterior?", así que no se pueden
    // compartir entre mano izquierda y derecha.
    private val leftClickDetector  = PinchClickDetector()
    private val rightClickDetector = PinchClickDetector()

    fun start(lifecycleOwner: LifecycleOwner) {
        try { setupLandmarker() }
        catch (e: Exception) { onError("Error cargando hand_landmarker.task: ${e.message}"); return }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraExecutor) { imageProxy -> processFrame(imageProxy) }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, analysis
                )
            } catch (e: Exception) { onError("Error iniciando cámara: ${e.message}") }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun setupLandmarker() {
        val baseOptions = BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(2)
            .setMinHandDetectionConfidence(0.45f)
            .setMinTrackingConfidence(0.35f)
            .setMinHandPresenceConfidence(0.40f)
            .setResultListener(::onResult)
            .setErrorListener { e -> onError("MediaPipe: ${e.message}") }
            .build()
        handLandmarker = HandLandmarker.createFromOptions(context, options)
    }

    private fun processFrame(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxy.toBitmapNV21()
            if (bitmap != null) {
                val mpImage: MPImage = BitmapImageBuilder(bitmap).build()
                val ts = System.currentTimeMillis()
                if (ts > lastTimestamp) { handLandmarker?.detectAsync(mpImage, ts); lastTimestamp = ts }
            }
        } catch (e: Exception) { Log.e("HandTracker", "Error procesando frame: ${e.message}") }
        finally { imageProxy.close() }
    }

    private fun onResult(result: HandLandmarkerResult, @Suppress("UNUSED_PARAMETER") input: MPImage) {
        var left  = HandPose(-0.35f, 0.1f, -0.5f, tracked = false)
        var right = HandPose( 0.35f, 0.1f, -0.5f, tracked = false)
        var sawLeft = false
        var sawRight = false

        val ts = System.currentTimeMillis()

        for (i in result.landmarks().indices) {
            val landmarks = result.landmarks()[i]
            val label     = result.handedness()[i][0].categoryName()

            val wrist   = landmarks[0]
            val midTip  = landmarks[12]
            val size    = hypot((midTip.x()-wrist.x()).toDouble(), (midTip.y()-wrist.y()).toDouble()).toFloat()

            val vx = mapRange(wrist.x(), 0f, 1f, xLo, xHi)
            val vy = mapRange(wrist.y(), 1f, 0f, yLo, yHi)
            val vz = zBase + (size - 0.20f) * zScale

            // Calcular orientación
            fun lmVR(idx: Int): FloatArray {
                val lm = landmarks[idx]
                return floatArrayOf(
                    mapRange(lm.x(), 0f, 1f, xLo, xHi),
                    mapRange(lm.y(), 1f, 0f, yLo, yHi),
                    lm.z() * zScale
                )
            }
            val p0 = lmVR(0); val p9 = lmVR(9); val p5 = lmVR(5); val p17 = lmVR(17)
            val fwd  = normalize3(p9[0]-p0[0], p9[1]-p0[1], p9[2]-p0[2])
            val side = normalize3(p5[0]-p17[0], p5[1]-p17[1], p5[2]-p17[2])
            val upN  = normalize3(cross3(fwd, side))
            val sideRaw = cross3(upN, fwd)
            val sideN   = normalize3(sideRaw)

            val xAxis = sideN
            val yAxis = upN
            val zAxis = floatArrayOf(-fwd[0], -fwd[1], -fwd[2])
            val m = floatArrayOf(
                xAxis[0], yAxis[0], zAxis[0],
                xAxis[1], yAxis[1], zAxis[1],
                xAxis[2], yAxis[2], zAxis[2]
            )
            val q = matrixToQuat(m)


            val (grip, pinch) = HandGesture.compute(landmarks)

            val curls = HandGestureCurl.computeCurls(landmarks)

            val rawPose = HandPose(
                vx, vy, vz, tracked = true,
                qx = q[0], qy = q[1], qz = q[2], qw = q[3],
                grip = grip, pinch = pinch,
                curlThumb = curls[0], curlIndex = curls[1], curlMiddle = curls[2],
                curlRing = curls[3], curlPinky = curls[4]
            )

            val isRight = label == "Right"

            val (calibrated, justRecalibrated) = HandOrientationCalibration.correct(isRight, rawPose)
            if (justRecalibrated) {
                if (isRight) rightSmoother.reset() else leftSmoother.reset()
            }


            if (isRight) {
                right = rightSmoother.smooth(calibrated, ts)
                if (rightClickDetector.update(right.pinch)) {
                    right = right.copy(clicked = true)
                    // performLeftClick()  // pinch de la mano derecha: conecta acá tu función real de click
                }
                sawRight = true
            } else {
                left = leftSmoother.smooth(calibrated, ts)
                if (leftClickDetector.update(left.pinch)) {
                    left = left.copy(clicked = true)
                    // performLeftClick()  // pinch de la mano izquierda: conecta acá tu función real de click
                }
                sawLeft = true
            }
        }


        if (!sawLeft)  { leftSmoother.reset(); leftClickDetector.reset() }
        if (!sawRight) { rightSmoother.reset(); rightClickDetector.reset() }

        onHands(left, right)
    }

    private fun normalize3(x: Float, y: Float, z: Float): FloatArray {
        val len = sqrt((x*x+y*y+z*z).toDouble()).toFloat()
        return if (len < 1e-6f) floatArrayOf(0f,1f,0f) else floatArrayOf(x/len, y/len, z/len)
    }
    private fun normalize3(arr: FloatArray) = normalize3(arr[0], arr[1], arr[2])
    private fun cross3(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]
    )

    private fun matrixToQuat(m: FloatArray): FloatArray {

        val m00=m[0];val m01=m[1];val m02=m[2]
        val m10=m[3];val m11=m[4];val m12=m[5]
        val m20=m[6];val m21=m[7];val m22=m[8]
        val trace=m00+m11+m22; val q=FloatArray(4)
        if (trace>0f){val s=0.5f/sqrt((trace+1f).toDouble()).toFloat();q[3]=0.25f/s;q[0]=(m21-m12)*s;q[1]=(m02-m20)*s;q[2]=(m10-m01)*s}
        else if(m00>m11&&m00>m22){val s=2f*sqrt((1f+m00-m11-m22).toDouble()).toFloat();q[3]=(m21-m12)/s;q[0]=0.25f*s;q[1]=(m01+m10)/s;q[2]=(m02+m20)/s}
        else if(m11>m22){val s=2f*sqrt((1f+m11-m00-m22).toDouble()).toFloat();q[3]=(m02-m20)/s;q[0]=(m01+m10)/s;q[1]=0.25f*s;q[2]=(m12+m21)/s}
        else{val s=2f*sqrt((1f+m22-m00-m11).toDouble()).toFloat();q[3]=(m10-m01)/s;q[0]=(m02+m20)/s;q[1]=(m12+m21)/s;q[2]=0.25f*s}
        val len=sqrt((q[0]*q[0]+q[1]*q[1]+q[2]*q[2]+q[3]*q[3]).toDouble()).toFloat()
        return if(len<1e-6f) floatArrayOf(0f,0f,0f,1f) else floatArrayOf(q[0]/len,q[1]/len,q[2]/len,q[3]/len)
    }

    private fun mapRange(v: Float, i0: Float, i1: Float, o0: Float, o1: Float): Float {
        val c = v.coerceIn(minOf(i0, i1), maxOf(i0, i1))
        return o0 + (c - i0) / (i1 - i0) * (o1 - o0)
    }

    fun stop() { handLandmarker?.close(); cameraExecutor.shutdown() }
}

fun ImageProxy.toBitmapNV21(): Bitmap? {
    if (format != ImageFormat.YUV_420_888) return null
    val yBuffer = planes[0].buffer; val uBuffer = planes[1].buffer; val vBuffer = planes[2].buffer
    val ySize = yBuffer.remaining(); val uSize = uBuffer.remaining(); val vSize = vBuffer.remaining()
    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize); vBuffer.get(nv21, ySize, vSize); uBuffer.get(nv21, ySize + vSize, uSize)
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 80, out)
    val jpegBytes = out.toByteArray()
    return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
}