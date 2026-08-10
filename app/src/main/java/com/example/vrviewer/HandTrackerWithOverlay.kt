package com.example.vrviewer

import android.content.Context
import android.graphics.PointF
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker.HandLandmarkerOptions
import java.util.concurrent.ExecutorService
import android.util.Log
import kotlin.math.sqrt
import kotlin.math.hypot

class HandTrackerWithOverlay(
    private val context: Context,
    private val onHands:    (HandPose, HandPose) -> Unit,
    private val onSkeleton: (List<PointF>, List<PointF>) -> Unit,
    private val onError:    (String) -> Unit
) {
    private var handLandmarker: HandLandmarker? = null
    private var lastTimestamp = 0L


    @Volatile private var detecting = false


    private var lastOverlayMs = 0L
    private val OVERLAY_INTERVAL_MS = 50L


    private val frameBuffer = HandFrameBuffer(maxDim = 224)

    private val xLo = -0.7f; private val xHi = 0.7f
    private val yLo = -0.3f; private val yHi = 0.7f
    private val zBase = -0.5f; private val zScale = 1.5f

    private var leftQuat:  FloatArray? = null
    private var rightQuat: FloatArray? = null
    private val ROT_SMOOTH = 0.35f

    fun setupAndAnalyze(analysis: ImageAnalysis, executor: ExecutorService) {

        handLandmarker = createLandmarker(Delegate.GPU)
            ?: createLandmarker(Delegate.CPU).also {
                if (it == null) onError("MediaPipe: no se pudo inicializar HandLandmarker (ni GPU ni CPU)")
            }

        analysis.setAnalyzer(executor) { proxy -> processFrame(proxy) }
    }

    private fun createLandmarker(delegate: Delegate): HandLandmarker? {
        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .setDelegate(delegate)
                .build()

            val options = HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setResultListener(::onResult)
                .setErrorListener { e -> detecting = false; onError("MediaPipe: ${e.message}") }
                .build()

            HandLandmarker.createFromOptions(context, options).also {
                Log.i("HandTrackerWithOverlay", "HandLandmarker inicializado con delegate=$delegate")
            }
        } catch (e: Exception) {
            Log.w("HandTrackerWithOverlay", "Delegate $delegate falló (${e.message}), probando fallback")
            null
        }
    }

    private fun processFrame(proxy: ImageProxy) {
        try {

            if (detecting) return


            val bmp = frameBuffer.convert(proxy) ?: return
            val mpImage = BitmapImageBuilder(bmp).build()
            val ts = System.currentTimeMillis()
            if (ts > lastTimestamp) {
                detecting = true
                handLandmarker?.detectAsync(mpImage, ts)
                lastTimestamp = ts
            }
        } catch (e: Exception) {
            detecting = false
            onError("HandTrackerWithOverlay frame: ${e.message}")
        } finally {
            proxy.close()
        }
    }

    private fun onResult(
        result: HandLandmarkerResult,
        input: com.google.mediapipe.framework.image.MPImage
    ) {

        detecting = false

        var left  = HandPose(-0.35f, 0.1f, -0.5f, tracked = false)
        var right = HandPose( 0.35f, 0.1f, -0.5f, tracked = false)

        val leftPts  = mutableListOf<PointF>()
        val rightPts = mutableListOf<PointF>()

        for (i in result.landmarks().indices) {
            val landmarks = result.landmarks()[i]
            val label     = result.handedness()[i][0].categoryName()

            val wrist  = landmarks[0]
            val midTip = landmarks[12]

            val size = hypot(
                (midTip.x() - wrist.x()).toDouble(),
                (midTip.y() - wrist.y()).toDouble()
            ).toFloat()

            val vx = mapRange(wrist.x(), 0f, 1f, xLo, xHi)
            val vy = mapRange(wrist.y(), 1f, 0f, yLo, yHi)
            val vz = zBase + (size - 0.20f) * zScale

            fun lmVR(idx: Int): FloatArray {
                val lm = landmarks[idx]
                return floatArrayOf(
                    mapRange(lm.x(), 0f, 1f, xLo, xHi),
                    mapRange(lm.y(), 1f, 0f, yLo, yHi),
                    lm.z() * zScale
                )
            }

            val p0  = lmVR(0); val p9  = lmVR(9)
            val p5  = lmVR(5); val p17 = lmVR(17)

            val fwd        = normalize3(p9[0]-p0[0], p9[1]-p0[1], p9[2]-p0[2])
            val palmAcross = normalize3(p5[0]-p17[0], p5[1]-p17[1], p5[2]-p17[2])
            val upRaw      = cross3(fwd, palmAcross)
            val up         = normalize3(upRaw[0], upRaw[1], upRaw[2])
            val sideRaw    = cross3(up, fwd)
            val side       = normalize3(sideRaw[0], sideRaw[1], sideRaw[2])

            val isRight  = label == "Right"
            val signSide = if (isRight) 1f else -1f

            val sideAxis = floatArrayOf(side[0]*signSide, side[1]*signSide, side[2]*signSide)
            val upAxis   = up
            val fwdAxis  = floatArrayOf(-fwd[0], -fwd[1], -fwd[2])

            val qRaw = buildQuatFromAxes(sideAxis, upAxis, fwdAxis)

            val prev = if (isRight) rightQuat else leftQuat
            val q = if (prev != null) slerp(prev, qRaw, 1f - ROT_SMOOTH) else qRaw
            if (isRight) rightQuat = q else leftQuat = q


            val (grip, pinch) = HandGesture.compute(landmarks)

            val curls = HandGestureCurl.computeCurls(landmarks)

            val pose = HandPose(
                x = vx, y = vy, z = vz, tracked = true,
                qx = q[0], qy = q[1], qz = q[2], qw = q[3],
                grip = grip, pinch = pinch,
                curlThumb = curls[0], curlIndex = curls[1], curlMiddle = curls[2],
                curlRing = curls[3], curlPinky = curls[4]
            )
            val pts = landmarks.map { PointF(it.x(), it.y()) }

            if (isRight) { right = pose; rightPts.addAll(pts) }
            else         { left  = pose; leftPts.addAll(pts)  }
        }


        onHands(left, right)


        val now = System.currentTimeMillis()
        if (now - lastOverlayMs >= OVERLAY_INTERVAL_MS) {
            lastOverlayMs = now
            onSkeleton(leftPts, rightPts)
        }
    }

    private fun buildQuatFromAxes(xAxis: FloatArray, yAxis: FloatArray, zAxis: FloatArray): FloatArray {
        val m00 = xAxis[0]; val m01 = yAxis[0]; val m02 = zAxis[0]
        val m10 = xAxis[1]; val m11 = yAxis[1]; val m12 = zAxis[1]
        val m20 = xAxis[2]; val m21 = yAxis[2]; val m22 = zAxis[2]

        val trace = m00 + m11 + m22
        val q = FloatArray(4)

        if (trace > 0f) {
            val s = 0.5f / sqrt((trace + 1f).toDouble()).toFloat()
            q[3]=0.25f/s; q[0]=(m21-m12)*s; q[1]=(m02-m20)*s; q[2]=(m10-m01)*s
        } else if (m00 > m11 && m00 > m22) {
            val s = 2f * sqrt((1f + m00 - m11 - m22).toDouble()).toFloat()
            q[3]=(m21-m12)/s; q[0]=0.25f*s; q[1]=(m01+m10)/s; q[2]=(m02+m20)/s
        } else if (m11 > m22) {
            val s = 2f * sqrt((1f + m11 - m00 - m22).toDouble()).toFloat()
            q[3]=(m02-m20)/s; q[0]=(m01+m10)/s; q[1]=0.25f*s; q[2]=(m12+m21)/s
        } else {
            val s = 2f * sqrt((1f + m22 - m00 - m11).toDouble()).toFloat()
            q[3]=(m10-m01)/s; q[0]=(m02+m20)/s; q[1]=(m12+m21)/s; q[2]=0.25f*s
        }

        val len = sqrt((q[0]*q[0]+q[1]*q[1]+q[2]*q[2]+q[3]*q[3]).toDouble()).toFloat()
        return if (len < 1e-6f) floatArrayOf(0f,0f,0f,1f)
        else floatArrayOf(q[0]/len, q[1]/len, q[2]/len, q[3]/len)
    }

    private fun slerp(a: FloatArray, b: FloatArray, t: Float): FloatArray {
        var bx=b[0]; var by=b[1]; var bz=b[2]; var bw=b[3]
        var dot = a[0]*bx + a[1]*by + a[2]*bz + a[3]*bw
        if (dot < 0f) { bx=-bx; by=-by; bz=-bz; bw=-bw; dot=-dot }
        if (dot > 0.9995f) {
            val x=a[0]+(bx-a[0])*t; val y=a[1]+(by-a[1])*t
            val z=a[2]+(bz-a[2])*t; val w=a[3]+(bw-a[3])*t
            val len=sqrt((x*x+y*y+z*z+w*w).toDouble()).toFloat()
            return if (len<1e-6f) b else floatArrayOf(x/len,y/len,z/len,w/len)
        }
        val theta0=kotlin.math.acos(dot.toDouble()).toFloat()
        val theta=theta0*t
        val sinTheta0=kotlin.math.sin(theta0.toDouble()).toFloat()
        val sinTheta=kotlin.math.sin(theta.toDouble()).toFloat()
        val s0=kotlin.math.cos(theta.toDouble()).toFloat() - dot*sinTheta/sinTheta0
        val s1=sinTheta/sinTheta0
        return floatArrayOf(s0*a[0]+s1*bx, s0*a[1]+s1*by, s0*a[2]+s1*bz, s0*a[3]+s1*bw)
    }

    private fun normalize3(x: Float, y: Float, z: Float): FloatArray {
        val len = sqrt((x*x+y*y+z*z).toDouble()).toFloat()
        return if (len<1e-6f) floatArrayOf(0f,1f,0f) else floatArrayOf(x/len,y/len,z/len)
    }

    private fun cross3(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]
    )

    private fun mapRange(v: Float, i0: Float, i1: Float, o0: Float, o1: Float): Float {
        val c = v.coerceIn(minOf(i0,i1), maxOf(i0,i1))
        return o0 + (c-i0)/(i1-i0)*(o1-o0)
    }
}