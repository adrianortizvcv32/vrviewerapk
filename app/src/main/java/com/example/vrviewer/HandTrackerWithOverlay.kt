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
import kotlin.math.abs
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

    // NUEVO: filtros de posición/rotación por mano — antes acá no había
    // NINGÚN suavizado de posición (solo un slerp casero para rotación),
    // así que cada micro-temblor de MediaPipe se mandaba directo a
    // SteamVR. Mismo mecanismo que ya usa SixDofTracker.
    private val leftSmoother  = HandPoseSmoother()
    private val rightSmoother = HandPoseSmoother()

    // NUEVO: botones tipo Quest (pulgar+medio=A/X, pulgar+anular=B/Y,
    // pulgar+meñique=System), mismo patrón que en SixDofTracker — se
    // necesita UNA instancia por botón y por mano, porque cada una
    // guarda su propio estado de histéresis press/release.
    private val leftButtonAClickDetector = PinchClickDetector()
    private val leftButtonBClickDetector = PinchClickDetector()
    private val leftButtonSystemClickDetector = PinchClickDetector(pressThreshold = 0.85f, releaseThreshold = 0.65f)
    private val rightButtonAClickDetector = PinchClickDetector()
    private val rightButtonBClickDetector = PinchClickDetector()
    private val rightButtonSystemClickDetector = PinchClickDetector(pressThreshold = 0.85f, releaseThreshold = 0.65f)

    // NUEVO (avance simplificado): estado suavizado del "caminar" — solo
    // se usa el eje Y (adelante) de la mano IZQUIERDA. Se reemplazó el
    // viejo joystick del pulgar (bugueado en las 4 direcciones) por algo
    // simple y robusto: doblar el índice de la mano izquierda avanza
    // SIEMPRE derecho, sin componente lateral. La mano derecha nunca
    // mueve al jugador (rightStickX/Y quedan siempre en 0).
    private var leftStickX = 0f
    private var leftStickY = 0f
    private var rightStickX = 0f
    private var rightStickY = 0f

    // NUEVO: guarda de anti-swap de handedness (mismo mecanismo que
    // SixDofTracker.correctHandedness()). Sin esto, MediaPipe puede
    // invertir el label "Left"/"Right" en poses ambiguas (manos
    // cruzadas, una ocluyendo a la otra) y la mano "teletransporta" al
    // lado opuesto de un frame a otro.
    private var lastLeftVx: Float = -0.35f
    private var lastRightVx: Float = 0.35f

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
                // AJUSTADO: bajados respecto a la versión anterior
                // (0.55/0.5/0.5) para que MediaPipe "enganche" la mano
                // más rápido y la pierda con menos frecuencia — el costo
                // es algún falso positivo ocasional, que ya se filtra
                // aparte con MIN_HANDEDNESS_CONFIDENCE y el guard
                // anti-swap. Si notás demasiados falsos positivos (manos
                // fantasma), subilos de a 0.05. Mismos valores que
                // SixDofTracker para que las dos formas de tracking de
                // manos se sientan igual.
                .setMinHandDetectionConfidence(0.45f)
                .setMinTrackingConfidence(0.4f)
                .setMinHandPresenceConfidence(0.4f)
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

        // NUEVO: si una mano no aparece en este resultado, hay que
        // resetear sus detectores de botón y su "caminar" (si no, un
        // pellizco o el avance "quedan pegados" en true/no-cero).
        var sawLeft = false
        var sawRight = false

        val leftPts  = mutableListOf<PointF>()
        val rightPts = mutableListOf<PointF>()

        val ts = System.currentTimeMillis()

        for (i in result.landmarks().indices) {
            val landmarks = result.landmarks()[i]

            // NUEVO: gating por confianza de handedness — mismo criterio
            // que SixDofTracker. Aceptar un label de baja confianza es
            // peor que perder el frame, porque puede causar swaps
            // espurios entre manos.
            val handednessCategory = result.handedness().getOrNull(i)?.getOrNull(0) ?: continue
            if (handednessCategory.score() < MIN_HANDEDNESS_CONFIDENCE) continue
            val reportedIsRight = handednessCategory.categoryName() == "Right"

            val wrist  = landmarks[0]
            val midTip = landmarks[12]

            val size = hypot(
                (midTip.x() - wrist.x()).toDouble(),
                (midTip.y() - wrist.y()).toDouble()
            ).toFloat()

            val vx = mapRange(wrist.x(), 0f, 1f, xLo, xHi)
            val vy = mapRange(wrist.y(), 1f, 0f, yLo, yHi)
            val vz = zBase + (size - 0.20f) * zScale

            // NUEVO: guarda anti-swap por continuidad espacial — si la
            // posición nueva está mucho más cerca de la última posición
            // conocida de LA OTRA mano, asumimos que el label de
            // MediaPipe está invertido y lo corregimos.
            val isRight = correctHandedness(reportedIsRight, vx)

            fun lmVR(idx: Int): FloatArray {
                val lm = landmarks[idx]
                return floatArrayOf(
                    mapRange(lm.x(), 0f, 1f, xLo, xHi),
                    mapRange(lm.y(), 1f, 0f, yLo, yHi),
                    -lm.z() * zScale
                )
            }

            val p0  = lmVR(0); val p9  = lmVR(9)
            val p5  = lmVR(5); val p17 = lmVR(17)

            val fwd  = normalize3(p9[0]-p0[0], p9[1]-p0[1], p9[2]-p0[2])
            val side = if (isRight)
                normalize3(p5[0]-p17[0], p5[1]-p17[1], p5[2]-p17[2])
            else
                normalize3(p17[0]-p5[0], p17[1]-p5[1], p17[2]-p5[2])

            val upRaw   = cross3(fwd, side)
            val up      = normalize3(upRaw[0], upRaw[1], upRaw[2])
            val sideRaw = cross3(fwd, up)
            val sideN   = normalize3(sideRaw[0], sideRaw[1], sideRaw[2])

            val sideAxis = sideN
            val upAxis   = up
            val fwdAxis  = floatArrayOf(-fwd[0], -fwd[1], -fwd[2])

            val qRaw = buildQuatFromAxes(sideAxis, upAxis, fwdAxis)

            // NUEVO (yaw fix): mismo offset de yaw que en SixDofTracker.
            // Antes acá también se hacía un slerp manual contra el frame
            // anterior (leftQuat/rightQuat) — eso se sacó porque ahora el
            // HandPoseSmoother de abajo ya hace ese slerp (y además
            // filtra posición, cosa que el código viejo no hacía).
            val yawCorrectionDeg = if (isRight) HAND_YAW_CORRECTION_RIGHT_DEG else HAND_YAW_CORRECTION_LEFT_DEG
            val qCorrected = quatMultiply(yawOffsetQuat(yawCorrectionDeg), qRaw)

            val (grip, pinch) = HandGesture.compute(landmarks)

            val curls = HandGestureCurl.computeCurls(landmarks)

            // ══════════════════════════════════════════════════════════
            // AVANCE SIMPLIFICADO: se reemplazó el joystick del pulgar
            // (bugueado en las 4 direcciones) por algo simple y robusto:
            // doblar el índice de la mano IZQUIERDA avanza SIEMPRE
            // derecho (sin lateral). La mano derecha nunca camina.
            // curls[1] es el curl del índice, ya calculado arriba.
            // ══════════════════════════════════════════════════════════
            val stickTargetX = 0f
            val stickTargetY = if (!isRight)
                stickAxisValue(curls[1], INDEX_WALK_DEAD_ZONE, INDEX_WALK_MAX_CURL)
            else 0f

            val extraPinches = HandGesture.computeExtraPinches(landmarks)

            val buttonAHeld: Boolean
            val buttonBHeld: Boolean
            val buttonSystemHeld: Boolean
            if (isRight) {
                rightStickX = 0f
                rightStickY = 0f
                rightButtonAClickDetector.update(extraPinches[0])
                rightButtonBClickDetector.update(extraPinches[1])
                rightButtonSystemClickDetector.update(extraPinches[2])
                buttonAHeld = rightButtonAClickDetector.isHeld()
                buttonBHeld = rightButtonBClickDetector.isHeld()
                buttonSystemHeld = rightButtonSystemClickDetector.isHeld()
                sawRight = true
                lastRightVx = vx
            } else {
                leftStickX += (stickTargetX - leftStickX) * STICK_SMOOTHING_ALPHA
                leftStickY += (stickTargetY - leftStickY) * STICK_SMOOTHING_ALPHA
                leftButtonAClickDetector.update(extraPinches[0])
                leftButtonBClickDetector.update(extraPinches[1])
                leftButtonSystemClickDetector.update(extraPinches[2])
                buttonAHeld = leftButtonAClickDetector.isHeld()
                buttonBHeld = leftButtonBClickDetector.isHeld()
                buttonSystemHeld = leftButtonSystemClickDetector.isHeld()
                sawLeft = true
                lastLeftVx = vx
            }

            val rawPose = HandPose(
                x = vx, y = vy, z = vz, tracked = true,
                qx = qCorrected[0], qy = qCorrected[1], qz = qCorrected[2], qw = qCorrected[3],
                grip = grip, pinch = pinch,
                curlThumb = curls[0], curlIndex = curls[1], curlMiddle = curls[2],
                curlRing = curls[3], curlPinky = curls[4],
                pinchMiddle = extraPinches[0],
                pinchRing = extraPinches[1],
                pinchPinky = extraPinches[2],
                buttonAPressed = buttonAHeld,
                buttonBPressed = buttonBHeld,
                buttonSystemPressed = buttonSystemHeld,
                joyX = if (isRight) rightStickX else leftStickX,
                joyY = if (isRight) rightStickY else leftStickY
            )
            val pts = landmarks.map { PointF(it.x(), it.y()) }

            if (isRight) { right = rawPose; rightPts.addAll(pts) }
            else         { left  = rawPose; leftPts.addAll(pts)  }
        }

        // NUEVO: mano no vista este frame -> apaga sus botones, su
        // avance y resetea su filtro de posición (si no, quedarían
        // "pegados" en el último valor o el smoother arrastraría un
        // estado viejo al reaparecer la mano).
        if (!sawLeft) {
            leftButtonAClickDetector.reset()
            leftButtonBClickDetector.reset()
            leftButtonSystemClickDetector.reset()
            leftStickX = 0f; leftStickY = 0f
            leftSmoother.reset()
        }
        if (!sawRight) {
            rightButtonAClickDetector.reset()
            rightButtonBClickDetector.reset()
            rightButtonSystemClickDetector.reset()
            rightStickX = 0f; rightStickY = 0f
            rightSmoother.reset()
        }

        // NUEVO: filtra posición + rotación (One-Euro + compensación de
        // latencia + modo precisión) — antes esto se mandaba crudo, cada
        // micro-temblor de MediaPipe llegaba directo a SteamVR. El
        // precisionMode se activa apenas empezás a pellizcar (pre-click),
        // justo cuando más necesitás que la mano no tiemble y menos
        // necesitás velocidad de reacción (esto es lo que más debería
        // ayudarte a clickear bien los botones del menú).
        if (sawLeft)  left  = leftSmoother.smooth(left,  ts, precisionMode = left.pinch  > 0.35f)
        if (sawRight) right = rightSmoother.smooth(right, ts, precisionMode = right.pinch > 0.35f)

        onHands(left, right)

        val now = System.currentTimeMillis()
        if (now - lastOverlayMs >= OVERLAY_INTERVAL_MS) {
            lastOverlayMs = now
            onSkeleton(leftPts, rightPts)
        }
    }

    /**
     * NUEVO: guard anti-swap de handedness. MediaPipe a veces invierte el
     * label "Left"/"Right" en poses ambiguas (manos cruzadas, una mano
     * ocluyendo a la otra, etc). Un flip instantáneo del label produce un
     * teletransporte visual de la mano de un lado al otro. Corregimos por
     * continuidad espacial: si la posición nueva está mucho más cerca de
     * la última posición conocida de LA OTRA mano, asumimos que el label
     * está invertido y lo corregimos. Mismo criterio que SixDofTracker.
     */
    private fun correctHandedness(reportedRight: Boolean, vx: Float): Boolean {
        val distToOwnLast = if (reportedRight) abs(vx - lastRightVx) else abs(vx - lastLeftVx)
        val distToOtherLast = if (reportedRight) abs(vx - lastLeftVx) else abs(vx - lastRightVx)
        return if (distToOtherLast < distToOwnLast - 0.15f) !reportedRight else reportedRight
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

    // NUEVO (yaw fix): cuaternión de rotación pura alrededor del eje Y,
    // igual que en SixDofTracker.
    private fun yawOffsetQuat(degrees: Float): FloatArray {
        val half = Math.toRadians(degrees.toDouble()).toFloat() / 2f
        return floatArrayOf(0f, kotlin.math.sin(half), 0f, kotlin.math.cos(half))
    }

    private fun quatMultiply(a: FloatArray, b: FloatArray): FloatArray {
        val ax = a[0]; val ay = a[1]; val az = a[2]; val aw = a[3]
        val bx = b[0]; val by = b[1]; val bz = b[2]; val bw = b[3]
        return floatArrayOf(
            aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw,
            aw * bw - ax * bx - ay * by - az * bz
        )
    }

    // NUEVO (avance simplificado): mapea el curl del índice (0f..1f) a un
    // valor de "acelerador" 0f..1f con zona muerta y saturación, igual
    // que en SixDofTracker.
    private fun stickAxisValue(v: Float, deadZone: Float, maxRange: Float): Float {
        val absV = abs(v)
        if (absV < deadZone) return 0f
        val sign = if (v < 0f) -1f else 1f
        val scaled = ((absV - deadZone) / (maxRange - deadZone)).coerceIn(0f, 1f)
        return sign * scaled
    }

    private fun normalize3(x: Float, y: Float, z: Float): FloatArray {
        val len = sqrt((x*x+y*y+z*z).toDouble()).toFloat()
        return if (len<1e-6f) floatArrayOf(0f,1f,0f) else floatArrayOf(x/len,y/len,z/len)
    }
    // FIX build: faltaba este overload — cross3() devuelve FloatArray y
    // rawUp/rawRight lo pasan directo a normalize3() sin desarmarlo.
    private fun normalize3(arr: FloatArray) = normalize3(arr[0], arr[1], arr[2])

    private fun cross3(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]
    )

    private fun mapRange(v: Float, i0: Float, i1: Float, o0: Float, o1: Float): Float {
        val c = v.coerceIn(minOf(i0,i1), maxOf(i0,i1))
        return o0 + (c-i0)/(i1-i0)*(o1-o0)
    }

    companion object {
        // NUEVO: confianza mínima de handedness para aceptar una
        // detección — mismo criterio que SixDofTracker.
        private const val MIN_HANDEDNESS_CONFIDENCE = 0.65f

        // NUEVO (yaw fix): mismos valores que en SixDofTracker — si
        // calibraste otros números allá, replicalos acá para que las
        // dos formas de tracking de manos se sientan igual.
        private const val HAND_YAW_CORRECTION_RIGHT_DEG = -20f
        private const val HAND_YAW_CORRECTION_LEFT_DEG  = 20f

        // NUEVO (avance simplificado): mismos valores que SixDofTracker
        // — doblar el índice de la mano izquierda por encima de
        // INDEX_WALK_DEAD_ZONE empieza a avanzar; al llegar a
        // INDEX_WALK_MAX_CURL satura en velocidad máxima.
        private const val INDEX_WALK_DEAD_ZONE = 0.15f
        private const val INDEX_WALK_MAX_CURL  = 0.85f
        private const val STICK_SMOOTHING_ALPHA = 0.35f
    }
}
