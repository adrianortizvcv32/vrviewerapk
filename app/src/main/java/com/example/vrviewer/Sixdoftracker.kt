package com.example.vrviewer

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.os.Process
import android.util.Log
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.UnavailableException
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt


data class HmdPose6Dof(
    val x: Float, val y: Float, val z: Float,
    val qx: Float, val qy: Float, val qz: Float, val qw: Float,
    val tracked: Boolean
)


enum class SixDofHandMode { NONE, LED, MEDIAPIPE, MEDIAPIPE_JOYCONS }


class SixDofTracker(
    private val context: Context,
    private val onPose: (HmdPose6Dof) -> Unit,
    private val onError: (String) -> Unit,
    private val onTrackingStarted: (() -> Unit)? = null,
    private val handMode: SixDofHandMode = SixDofHandMode.NONE,
    private val onHands: ((left: HandPose, right: HandPose) -> Unit)? = null,

    private val useDepthForHands: Boolean = false,
    // NUEVO (Menú Hub): si no es null, se invoca en cada frame de cámara
    // ya convertido para MediaPipe — reusa ese mismo bitmap como fondo de
    // pantalla del modo Hub sin adquirir un segundo frame por separado.
    private val onCameraFrame: ((Bitmap) -> Unit)? = null
) {
    private var session: Session? = null
    private var glContext: OffscreenGlContext? = null
    private val running = AtomicBoolean(false)

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            runnable.run()
        }, "SixDofTracker-ARCoreLoop")
    }



    private val handProcessingExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            runnable.run()
        }, "SixDofTracker-HandProcessing")
    }


    @Volatile private var originX = 0f
    @Volatile private var originY = 0f
    @Volatile private var originZ = 0f
    @Volatile private var hasOrigin = false

    @Volatile private var everTracked = false


    @Volatile var movementSensitivity: Float = 1.0f

    // NUEVO (Menú Hub): pose REAL de la cámara ARCore en coordenadas MUNDO
    // de la sesión (posición en metros + cuaternión), SIN el offset de
    // recenter()/origen relativo que sí aplica onPose(). Objetos anclados
    // en el mundo (como la ventana del Menú Hub) deben proyectarse con
    // esta pose "cruda", porque su ancla también vive en coordenadas
    // absolutas de la sesión ARCore, no relativas al punto de recentrado.
    @Volatile private var lastCamPos: FloatArray = floatArrayOf(0f, 0f, 0f)
    @Volatile private var lastCamQuat: FloatArray = floatArrayOf(0f, 0f, 0f, 1f)

    /** Pose real de cámara en coordenadas MUNDO de la sesión ARCore —
     *  para anclar objetos como la ventana del Menú Hub. Devuelve
     *  (posición en metros, cuaternión xyzw). Thread-safe: se lee desde
     *  el hilo de procesamiento de manos, se escribe desde el loop ARCore. */
    fun currentCameraPose(): Pair<FloatArray, FloatArray> = lastCamPos to lastCamQuat


    private var lastLeftPose:  HandPose = HandPose(-0.35f, 0.1f, -0.5f, false)
    private var lastRightPose: HandPose = HandPose( 0.35f, 0.1f, -0.5f, false)
    private var missedLeft  = 0
    private var missedRight = 0
    private val MISS_TOLERANCE = 6


    private var lastLeftPoseMp:  HandPose = HandPose(-0.35f, 0.1f, -0.5f, false)
    private var lastRightPoseMp: HandPose = HandPose( 0.35f, 0.1f, -0.5f, false)
    private var missedLeftMp  = 0
    private var missedRightMp = 0


    private var loggedImageSize = false


    private var handLandmarker: HandLandmarker? = null
    private var lastHandTimestamp = 0L


    @Volatile private var detectingHands = false


    @Volatile private var processingHandFrame = false


    @Volatile private var depthEnabled = false


    @Volatile private var pendingDepthImage: Image? = null



    private val handFrameBuffer = HandFrameBuffer(maxDim = 384)

    private val leftHandSmoother  = HandPoseSmoother()
    private val rightHandSmoother = HandPoseSmoother()

    private val leftClickDetector  = PinchClickDetector()
    private val rightClickDetector = PinchClickDetector()

    // NUEVO: detectores de click tipo Quest (A/X, B/Y, System) — uno por
    // botón y por mano, mismo patrón que leftClickDetector/rightClickDetector
    // (pulso de un frame en el flanco de subida, con histéresis interna).
    private val leftButtonAClickDetector = PinchClickDetector()
    private val leftButtonBClickDetector = PinchClickDetector()
    private val leftButtonSystemClickDetector = PinchClickDetector(pressThreshold = 0.85f, releaseThreshold = 0.65f)
    private val rightButtonAClickDetector = PinchClickDetector()
    private val rightButtonBClickDetector = PinchClickDetector()
    private val rightButtonSystemClickDetector = PinchClickDetector(pressThreshold = 0.85f, releaseThreshold = 0.65f)

    // NUEVO: buffer reusado para detectColorBlob (modo LED) — evita
    // asignar un IntArray(w*h) nuevo en cada frame, lo que generaba
    // presión de GC en el hilo de captura ARCore (thread de prioridad
    // URGENT_DISPLAY, muy sensible a pausas de GC).
    private var blobPixelBuffer: IntArray = IntArray(0)

    // NUEVO: guarda la última posición X conocida (en espacio VR, tras
    // mapRange) de cada mano MediaPipe para el guard anti-swap de
    // handedness — ver detectHandSwap().
    @Volatile private var lastLeftVx: Float = -0.35f
    @Volatile private var lastRightVx: Float = 0.35f

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

    fun start() {
        if (running.getAndSet(true)) return
        executor.execute {
            try {
                val gl = OffscreenGlContext()
                if (!gl.setup()) {
                    running.set(false)
                    Log.e("SixDofTracker", "OffscreenGlContext.setup() devolvió false — revisa el log de OffscreenGlContext arriba")

                    try { gl.release() } catch (_: Exception) {}
                    onError("6DoF: no se pudo crear el contexto gráfico necesario")
                    return@execute
                }
                glContext = gl


                val s = Session(context)
                session = s


                try {
                    val filter = CameraConfigFilter(s)
                        .setFacingDirection(CameraConfig.FacingDirection.BACK)
                    val configs = s.getSupportedCameraConfigs(filter)
                    if (configs.isNotEmpty()) {
                        val smallest = configs.minByOrNull { it.imageSize.width * it.imageSize.height }
                        smallest?.let {
                            s.cameraConfig = it
                            Log.i(
                                "SixDofTracker",
                                "CameraConfig elegido: ${it.imageSize.width}x${it.imageSize.height} " +
                                        "(de ${configs.size} opciones disponibles)"
                            )
                        }
                    } else {
                        Log.w("SixDofTracker", "getSupportedCameraConfigs() devolvió vacío, usando config por defecto")
                    }
                } catch (e: Exception) {
                    Log.w("SixDofTracker", "No se pudo forzar CameraConfig de baja resolución: ${e.message}")
                }

                val config = Config(s).apply {
                    planeFindingMode    = Config.PlaneFindingMode.DISABLED
                    lightEstimationMode = Config.LightEstimationMode.DISABLED
                    updateMode          = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    focusMode           = Config.FocusMode.AUTO
                }


                if ((handMode == SixDofHandMode.MEDIAPIPE || handMode == SixDofHandMode.MEDIAPIPE_JOYCONS) && useDepthForHands) {
                    depthEnabled = try {
                        s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                    } catch (e: Exception) {
                        false
                    }
                    if (depthEnabled) {
                        config.depthMode = Config.DepthMode.AUTOMATIC
                        Log.i("SixDofTracker", "ARCore Depth API soportado: usando profundidad real de mano")
                    } else {
                        Log.w("SixDofTracker", "ARCore Depth API NO soportado en este dispositivo: fallback a world landmarks / ancho de palma")
                    }
                } else {
                    depthEnabled = false
                    if ((handMode == SixDofHandMode.MEDIAPIPE || handMode == SixDofHandMode.MEDIAPIPE_JOYCONS) && !useDepthForHands) {
                        Log.i("SixDofTracker", "Depth API desactivada por configuración (useDepthForHands=false): usando fallback de world landmarks / ancho de palma")
                    }
                }

                s.configure(config)

                s.setCameraTextureName(gl.cameraTextureId)
                s.resume()
                hasOrigin = false
                everTracked = false
                missedLeft = 0
                missedRight = 0
                missedLeftMp = 0
                missedRightMp = 0
                loggedImageSize = false
                detectingHands = false
                processingHandFrame = false
                pendingDepthImage?.close()
                pendingDepthImage = null
                lastHandTimestamp = 0L
                leftHandSmoother.reset()
                rightHandSmoother.reset()
                leftClickDetector.reset()
                rightClickDetector.reset()
                leftButtonAClickDetector.reset()
                leftButtonBClickDetector.reset()
                leftButtonSystemClickDetector.reset()
                rightButtonAClickDetector.reset()
                rightButtonBClickDetector.reset()
                rightButtonSystemClickDetector.reset()
                lastLeftVx = -0.35f
                lastRightVx = 0.35f
                leftStickX = 0f; leftStickY = 0f
                rightStickX = 0f; rightStickY = 0f

                if (handMode == SixDofHandMode.MEDIAPIPE || handMode == SixDofHandMode.MEDIAPIPE_JOYCONS) {
                    setupMediaPipe()
                }

                loop()
            } catch (e: UnavailableException) {
                running.set(false)
                Log.e("SixDofTracker", "UnavailableException al iniciar: ${e.javaClass.simpleName}: ${e.message}", e)
                onError("ARCore no disponible: ${e.javaClass.simpleName}: ${e.message}")

                cleanupSessionAndGl()
            } catch (e: Exception) {
                running.set(false)
                Log.e("SixDofTracker", "Error iniciando 6DoF: ${e.javaClass.simpleName}: ${e.message}", e)
                onError("Error iniciando 6DoF: ${e.javaClass.simpleName}: ${e.message}")
                cleanupSessionAndGl()
            }
        }
    }


    private fun setupMediaPipe() {

        handLandmarker = createHandLandmarker(Delegate.GPU)
            ?: createHandLandmarker(Delegate.CPU)

        if (handLandmarker == null) {
            onError("6DoF+Manos: no se pudo cargar hand_landmarker.task (ni GPU ni CPU)")
        }
    }

    private fun createHandLandmarker(delegate: Delegate): HandLandmarker? {
        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .setDelegate(delegate)
                .build()
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(2)

                // AJUSTADO: bajados respecto a la versión anterior
                // (0.55/0.5/0.5) para que MediaPipe "enganche" la mano más
                // rápido y la pierda con menos frecuencia — el costo es
                // algún falso positivo ocasional, que ya se filtra aparte
                // con MIN_HANDEDNESS_CONFIDENCE y el guard anti-swap. Si
                // notás demasiados falsos positivos (manos fantasma),
                // subilos de a 0.05.
                .setMinHandDetectionConfidence(0.45f)
                .setMinTrackingConfidence(0.4f)
                .setMinHandPresenceConfidence(0.4f)
                .setResultListener(::onMediaPipeResult)
                .setErrorListener { e -> detectingHands = false; onError("6DoF+Manos (MediaPipe): ${e.message}") }
                .build()
            HandLandmarker.createFromOptions(context, options).also {
                Log.i("SixDofTracker", "HandLandmarker inicializado con delegate=$delegate")
            }
        } catch (e: Exception) {
            Log.w("SixDofTracker", "Delegate $delegate falló al cargar hand_landmarker.task (${e.message}), probando fallback")
            null
        }
    }

    private fun loop() {
        var framesWithoutTracking = 0
        val WARN_AFTER_FRAMES = 150

        while (running.get()) {
            try {
                val s = session ?: break
                val frame = s.update()
                val camera = frame.camera


                if (handMode != SixDofHandMode.NONE) {
                    when (handMode) {
                        SixDofHandMode.LED -> {

                            try {
                                val image = frame.acquireCameraImage()
                                try {
                                    if (!loggedImageSize) {
                                        loggedImageSize = true
                                        Log.d(
                                            "SixDofTracker",
                                            "Frame de cámara ARCore: ${image.width}x${image.height} " +
                                                    "(modo manos: $handMode)"
                                        )
                                    }
                                    val (left, right) = detectHandsFromImage(image)
                                    onHands?.invoke(left, right)
                                } finally {
                                    image.close()
                                }
                            } catch (e: NotYetAvailableException) {
                                // Normal: todavía no hay un frame de cámara nuevo listo.
                            } catch (e: Exception) {
                                Log.e("SixDofTracker", "Error detectando manos desde frame 6DoF: ${e.message}")
                            }
                        }


                        SixDofHandMode.MEDIAPIPE, SixDofHandMode.MEDIAPIPE_JOYCONS -> {
                            if (!processingHandFrame && !detectingHands) {
                                var image: Image? = null
                                var depthImage: Image? = null
                                try {
                                    image = frame.acquireCameraImage()


                                    if (depthEnabled) {
                                        depthImage = try {
                                            frame.acquireDepthImage16Bits()
                                        } catch (e: Exception) {
                                            null
                                        }
                                    }

                                    if (!loggedImageSize) {
                                        loggedImageSize = true
                                        Log.d(
                                            "SixDofTracker",
                                            "Frame de cámara ARCore: ${image.width}x${image.height} " +
                                                    "(modo manos: $handMode, depth: " +
                                                    (depthImage?.let { "${it.width}x${it.height}" } ?: "no disponible") + ")"
                                        )
                                    }

                                    processingHandFrame = true
                                    val imgToProcess = image
                                    val depthToProcess = depthImage
                                    handProcessingExecutor.execute {
                                        try {
                                            feedMediaPipe(imgToProcess, depthToProcess)
                                        } catch (e: Exception) {
                                            Log.e("SixDofTracker", "Error procesando frame de manos en background: ${e.message}")
                                            depthToProcess?.close()
                                        } finally {
                                            imgToProcess.close()
                                            processingHandFrame = false
                                        }
                                    }
                                } catch (e: NotYetAvailableException) {
                                    // Normal: todavía no hay un frame de cámara nuevo listo.
                                    depthImage?.close()
                                } catch (e: Exception) {
                                    Log.e("SixDofTracker", "Error adquiriendo frame para manos: ${e.message}")
                                    image?.close()
                                    depthImage?.close()
                                }
                            }
                        }

                        else -> {}
                    }
                }

                if (camera.trackingState == TrackingState.TRACKING) {
                    if (!everTracked) {
                        everTracked = true
                        onTrackingStarted?.invoke()
                    }
                    framesWithoutTracking = 0

                    val pose = camera.pose

                    if (!hasOrigin) {
                        originX = pose.tx(); originY = pose.ty(); originZ = pose.tz()
                        hasOrigin = true
                    }

                    val relX = (pose.tx() - originX) * movementSensitivity
                    val relY = (pose.ty() - originY) * movementSensitivity
                    val relZ = (pose.tz() - originZ) * movementSensitivity
                    val q = pose.rotationQuaternion // [x, y, z, w]

                    // NUEVO (Menú Hub): guarda la pose CRUDA de cámara (sin
                    // el offset de origen/recenter que sí lleva onPose) para
                    // que objetos anclados en el mundo (la ventana del Hub)
                    // se proyecten en coordenadas absolutas de la sesión.
                    lastCamPos = floatArrayOf(pose.tx(), pose.ty(), pose.tz())
                    lastCamQuat = floatArrayOf(q[0], q[1], q[2], q[3])

                    onPose(
                        HmdPose6Dof(
                            x = relX, y = 1.6f + relY, z = relZ,
                            qx = q[0], qy = q[1], qz = q[2], qw = q[3],
                            tracked = true
                        )
                    )
                } else {
                    framesWithoutTracking++
                    if (framesWithoutTracking == WARN_AFTER_FRAMES) {
                        onError(
                            if (!everTracked)
                                "6DoF sin señal: mueve el teléfono despacio y apunta a una zona con buena luz y detalle visual"
                            else
                                "6DoF perdió el tracking — recupera visibilidad de la escena"
                        )
                    }
                    onPose(HmdPose6Dof(0f, 1.6f, 0f, 0f, 0f, 0f, 1f, tracked = false))
                }
            } catch (e: CameraNotAvailableException) {
                onError("Cámara ocupada — no se pudo iniciar 6DoF (¿tracking de manos activo?)")
                break
            } catch (e: Exception) {
                Log.e("SixDofTracker", "Error en loop: ${e.message}")
            }
        }
        cleanupSessionAndGl()
    }


    fun recenter() {
        hasOrigin = false
    }

    fun stop() {
        running.set(false)

        executor.execute {
            if (session == null && glContext == null && handLandmarker == null) return@execute
            cleanupSessionAndGl()
        }
    }

    private fun cleanupSessionAndGl() {
        try { session?.pause(); session?.close() } catch (_: Exception) {}
        session = null

        if (handLandmarker != null) {
            awaitPendingHandFrame()
        }
        try { handLandmarker?.close() } catch (_: Exception) {}
        handLandmarker = null
        detectingHands = false
        processingHandFrame = false // ★
        pendingDepthImage?.close()
        pendingDepthImage = null
        cleanupGl()
    }


    private fun awaitPendingHandFrame(timeoutMs: Long = 300L) {
        try {
            handProcessingExecutor.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.w("SixDofTracker", "awaitPendingHandFrame: no se pudo esperar el frame de manos pendiente (${e.message})")
        }
    }

    private fun cleanupGl() {
        try { glContext?.release() } catch (_: Exception) {}
        glContext = null
    }


    fun destroy() {
        stop()
        executor.execute { handProcessingExecutor.shutdown() }
        executor.shutdown()
    }


    private fun detectHandsFromImage(image: Image): Pair<HandPose, HandPose> {
        val bmp = image.toBitmapFastHalf()
        if (bmp == null) {
            missedLeft++; missedRight++
            return Pair(
                resolveHandPose(null, lastLeftPose,  defaultX = -0.35f, missed = missedLeft),
                resolveHandPose(null, lastRightPose, defaultX =  0.35f, missed = missedRight)
            )
        }

        val greenBlob = detectColorBlob(bmp, "green") // → mano izquierda
        val blueBlob  = detectColorBlob(bmp, "blue")  // → mano derecha

        if (greenBlob != null) missedLeft = 0 else missedLeft++
        if (blueBlob  != null) missedRight = 0 else missedRight++

        val left  = resolveHandPose(greenBlob, lastLeftPose,  defaultX = -0.35f, missed = missedLeft)
        val right = resolveHandPose(blueBlob,  lastRightPose, defaultX =  0.35f, missed = missedRight)

        lastLeftPose  = left
        lastRightPose = right

        return Pair(left, right)
    }

    private fun resolveHandPose(
        blob: FloatArray?,
        lastPose: HandPose,
        defaultX: Float,
        missed: Int
    ): HandPose {
        if (blob != null) {
            return blobToHandPose(blob, defaultX)
        }
        return if (missed <= MISS_TOLERANCE && lastPose.tracked) {
            lastPose
        } else {
            HandPose(defaultX, 0.1f, -0.5f, false)
        }
    }

    private fun detectColorBlob(bmp: Bitmap, color: String): FloatArray? {
        val w = bmp.width
        val h = bmp.height
        val totalPixels = (w * h).toFloat()
        var sumX = 0f; var sumY = 0f; var count = 0

        // NUEVO: reusa el buffer de pixeles entre frames en vez de
        // asignar un IntArray(w*h) nuevo cada vez (evita presión de GC
        // en el hilo de captura ARCore, que corre con prioridad URGENT_DISPLAY).
        val needed = w * h
        if (blobPixelBuffer.size < needed) {
            blobPixelBuffer = IntArray(needed)
        }
        val pixels = blobPixelBuffer
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        var i = 0
        while (i < needed) {
            val px = pixels[i]
            val r = (px shr 16) and 0xFF
            val g = (px shr 8)  and 0xFF
            val b =  px         and 0xFF

            val match = when (color) {
                "blue"  -> b > 100 && b > r * 2.0f && b > g * 1.4f
                "green" -> g > 100 && g > r * 2.0f && g > b * 1.4f
                else    -> false
            }
            if (match) { sumX += i % w; sumY += i / w; count++ }
            i += LED_SAMPLE_STEP
        }

        val areaFraction = (count.toFloat() * LED_SAMPLE_STEP) / totalPixels
        if (areaFraction < LED_MIN_AREA_FRACTION) return null

        val cx = sumX / count / w
        val cy = sumY / count / h

        return floatArrayOf(cx, cy, areaFraction)
    }

    private fun blobToHandPose(blob: FloatArray, defaultX: Float): HandPose {
        val vx = mapRange(blob[0], 0f, 1f, LED_X_LO, LED_X_HI)
        val vy = mapRange(blob[1], 1f, 0f, LED_Y_LO, LED_Y_HI)

        val area = blob[2]
        val vz = mapRange(area, LED_BLOB_FAR, LED_BLOB_NEAR, LED_Z_FAR, LED_Z_NEAR)
            .coerceIn(LED_Z_FAR, LED_Z_NEAR)

        return HandPose(vx, vy, vz, true)
    }


    private fun feedMediaPipe(image: Image, depthImage: Image?) {

        if (detectingHands) {
            depthImage?.close()
            return
        }

        val bmp = handFrameBuffer.convert(image)
        if (bmp == null) {
            depthImage?.close()
            return
        }

        // NUEVO (Menú Hub): reenvía el mismo bitmap ya convertido para que se
        // use como fondo de cámara en el renderer SBS, sin costo extra.
        onCameraFrame?.invoke(bmp)

        val mpImage = BitmapImageBuilder(bmp).build()

        val ts = maxOf(System.currentTimeMillis(), lastHandTimestamp + 1)

        pendingDepthImage?.close()
        pendingDepthImage = depthImage

        detectingHands = true
        try {
            handLandmarker?.detectAsync(mpImage, ts)
        } catch (e: Exception) {

            detectingHands = false
            pendingDepthImage?.close()
            pendingDepthImage = null
            Log.w("SixDofTracker", "detectAsync() falló: ${e.message}")
        }
        lastHandTimestamp = ts
    }


    private fun onMediaPipeResult(
        result: HandLandmarkerResult,
        @Suppress("UNUSED_PARAMETER") input: MPImage
    ) {

        detectingHands = false


        val depthImage = pendingDepthImage
        pendingDepthImage = null


        try {
            processMediaPipeResult(result, depthImage)
        } catch (e: Exception) {
            Log.e("SixDofTracker", "Error procesando resultado de MediaPipe: ${e.message}", e)
            onError("6DoF+Manos: error procesando landmarks (${e.message})")
            // NUEVO: si la excepción ocurrió a mitad de actualizar los
            // smoothers/detectores de click, su estado interno puede haber
            // quedado a medio escribir. Reseteamos ambas manos para no
            // arrastrar un estado corrupto al siguiente frame válido.
            leftHandSmoother.reset()
            rightHandSmoother.reset()
            leftClickDetector.reset()
            rightClickDetector.reset()
            leftButtonAClickDetector.reset()
            leftButtonBClickDetector.reset()
            leftButtonSystemClickDetector.reset()
            rightButtonAClickDetector.reset()
            rightButtonBClickDetector.reset()
            rightButtonSystemClickDetector.reset()
            leftStickX = 0f; leftStickY = 0f
            rightStickX = 0f; rightStickY = 0f
            missedLeftMp = MEDIAPIPE_MISS_TOLERANCE + 1
            missedRightMp = MEDIAPIPE_MISS_TOLERANCE + 1
        } finally {
            depthImage?.close()
        }
    }


    private fun sampleDepthMeters(depthImage: Image?, nx: Float, ny: Float): Float? {
        if (depthImage == null) return null
        return try {
            val plane = depthImage.planes[0]
            val buffer = plane.buffer.duplicate()
            val w = depthImage.width
            val h = depthImage.height
            val px = (nx.coerceIn(0f, 1f) * (w - 1)).toInt()
            val py = (ny.coerceIn(0f, 1f) * (h - 1)).toInt()
            val offset = py * plane.rowStride + px * plane.pixelStride
            if (offset < 0 || offset + 1 >= buffer.capacity()) return null

            val lo = buffer.get(offset).toInt() and 0xFF
            val hi = buffer.get(offset + 1).toInt() and 0xFF
            val raw = lo or (hi shl 8)
            val mm = raw and 0x1FFF
            if (mm <= 0) null else mm / 1000f
        } catch (e: Exception) {
            null
        }
    }

    /**
     * NUEVO: guard anti-swap de handedness. MediaPipe a veces invierte el
     * label "Left"/"Right" en poses ambiguas (manos cruzadas, una mano
     * ocluyendo a la otra, etc). Un flip instantáneo del label produce un
     * teletransporte visual de la mano de un lado al otro. Corregimos por
     * continuidad espacial: si la posición nueva está mucho más cerca de
     * la última posición conocida de LA OTRA mano, asumimos que el label
     * está invertido y lo corregimos.
     */
    private fun correctHandedness(reportedRight: Boolean, vx: Float): Boolean {
        val distToOwnLast = if (reportedRight) abs(vx - lastRightVx) else abs(vx - lastLeftVx)
        val distToOtherLast = if (reportedRight) abs(vx - lastLeftVx) else abs(vx - lastRightVx)
        // Solo corrige si la evidencia es clara (margen de 0.15 en espacio
        // VR, ~15% del rango horizontal) para no pelear con MediaPipe en
        // casos genuinamente ambiguos (manos cerca del centro).
        return if (distToOtherLast < distToOwnLast - 0.15f) !reportedRight else reportedRight
    }

    // NUEVO (yaw fix): construye el cuaternión unitario que representa una
    // rotación pura alrededor del eje vertical (Y) del espacio VR, para
    // corregir el yaw de orientación que reporta MediaPipe. Positivo =
    // gira hacia la derecha, negativo = gira hacia la izquierda.
    private fun yawOffsetQuat(degrees: Float): FloatArray {
        val half = Math.toRadians(degrees.toDouble()).toFloat() / 2f
        return floatArrayOf(0f, kotlin.math.sin(half), 0f, kotlin.math.cos(half))
    }

    // NUEVO (yaw fix): multiplicación de cuaterniones (x, y, z, w), en
    // orden a*b (a se aplica "después" de b si se interpreta como
    // rotación de mundo). Se usa para anteponer el offset de yaw fijo a
    // la orientación cruda que calcula buildQuatFromAxes().
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

    private fun processMediaPipeResult(result: HandLandmarkerResult, depthImage: Image?) {
        var leftPose  = HandPose(-0.35f, 0.1f, -0.5f, tracked = false)
        var rightPose = HandPose( 0.35f, 0.1f, -0.5f, tracked = false)
        var sawLeft = false
        var sawRight = false

        val ts = System.currentTimeMillis()

        for (i in result.landmarks().indices) {
            val landmarks = result.landmarks()[i]

            // NUEVO: gating por confianza de handedness. Descarta
            // detecciones donde MediaPipe no está seguro de qué mano es —
            // aceptar un label de baja confianza es peor que perder el
            // frame, porque puede causar swaps espurios entre manos.
            val handednessCategory = result.handedness().getOrNull(i)?.getOrNull(0) ?: continue
            if (handednessCategory.score() < MIN_HANDEDNESS_CONFIDENCE) continue
            val reportedIsRight = handednessCategory.categoryName() == "Right"

            val wrist = landmarks[0]

            val idxMcp   = landmarks[5]
            val pinkyMcp = landmarks[17]
            val size = hypot(
                (idxMcp.x() - pinkyMcp.x()).toDouble(),
                (idxMcp.y() - pinkyMcp.y()).toDouble()
            ).toFloat()

            val vx = mapRange(wrist.x(), 0f, 1f, LED_X_LO, LED_X_HI)
            val vy = mapRange(wrist.y(), 1f, 0f, LED_Y_LO, LED_Y_HI)

            // NUEVO: aplica el guard anti-swap antes de decidir a qué
            // mano pertenece esta detección.
            val isRight = correctHandedness(reportedIsRight, vx)

            val palmNx = (wrist.x() + idxMcp.x() + pinkyMcp.x()) / 3f
            val palmNy = (wrist.y() + idxMcp.y() + pinkyMcp.y()) / 3f
            val realDepthM = sampleDepthMeters(depthImage, palmNx, palmNy)

            // NUEVO: cadena de prioridad de profundidad de 3 niveles.
            //   1) Depth API real de ARCore (la más precisa, cuando está disponible)
            //   2) World landmarks de MediaPipe (metros reales relativos a la
            //      muñeca — mucho más estable que el tamaño 2D aparente de
            //      la palma, porque no cambia al cerrar el puño)
            //   3) Heurístico de tamaño de palma en pantalla (último recurso)
            val worldLandmarks = result.worldLandmarks().getOrNull(i)
            val vz = when {
                realDepthM != null ->
                    (-realDepthM).coerceIn(MEDIAPIPE_Z_FAR, MEDIAPIPE_Z_NEAR)
                worldLandmarks != null && worldLandmarks.isNotEmpty() -> {
                    // La Z de los world landmarks es relativa a la muñeca,
                    // en metros, y no depende de la pose de los dedos.
                    // La anclamos a una distancia base estimada.
                    val wristWorldZ = worldLandmarks[0].z()
                    (-0.5f + wristWorldZ * WORLD_Z_SCALE)
                        .coerceIn(MEDIAPIPE_Z_FAR, MEDIAPIPE_Z_NEAR)
                }
                else ->
                    (-0.5f + (size - PALM_SIZE_BASE) * PALM_DEPTH_SCALE)
                        .coerceIn(MEDIAPIPE_Z_FAR, MEDIAPIPE_Z_NEAR)
            }

            fun lmVR(idx: Int): FloatArray {
                val lm = landmarks[idx]
                return floatArrayOf(
                    mapRange(lm.x(), 0f, 1f, LED_X_LO, LED_X_HI),
                    mapRange(lm.y(), 1f, 0f, LED_Y_LO, LED_Y_HI),

                    -lm.z() * 1.5f
                )
            }
            val p0 = lmVR(0); val p9 = lmVR(9); val p5 = lmVR(5); val p17 = lmVR(17)
            val fwd  = normalize3(p9[0]-p0[0], p9[1]-p0[1], p9[2]-p0[2])


            val side = if (isRight)
                normalize3(p5[0]-p17[0], p5[1]-p17[1], p5[2]-p17[2])
            else
                normalize3(p17[0]-p5[0], p17[1]-p5[1], p17[2]-p5[2])

            val up   = normalize3(cross3(fwd, side))

            val sideRaw = cross3(fwd, up)
            val sideN   = normalize3(sideRaw)

            val sideAxis = sideN
            val fwdAxis  = floatArrayOf(-fwd[0], -fwd[1], -fwd[2])
            val qRaw = buildQuatFromAxes(sideAxis, up, fwdAxis)

            // NUEVO (yaw fix): la orientación cruda de MediaPipe aparecía
            // muy volteada hacia el usuario en horizontal. Se le antepone
            // una rotación fija de yaw (alrededor del eje Y) para
            // corregirla — con constante independiente por mano, ver
            // comentario junto a HAND_YAW_CORRECTION_RIGHT_DEG/LEFT_DEG.
            val yawCorrectionDeg = if (isRight) HAND_YAW_CORRECTION_RIGHT_DEG else HAND_YAW_CORRECTION_LEFT_DEG
            val q = quatMultiply(yawOffsetQuat(yawCorrectionDeg), qRaw)

            val (grip, pinch) = HandGesture.compute(landmarks)

            val curls = HandGestureCurl.computeCurls(landmarks)

            // ══════════════════════════════════════════════════════════
            // AVANCE SIMPLIFICADO: se reemplazó el joystick del pulgar
            // (bugueado en las 4 direcciones — izquierda/derecha/adelante/
            // atrás, con calibración de origen y deriva) por un mecanismo
            // simple y robusto: doblar el índice de la mano IZQUIERDA
            // avanza SIEMPRE derecho (sin ninguna componente lateral). La
            // mano derecha nunca camina. curls[1] es el curl del índice
            // (0f = extendido, 1f = totalmente doblado), calculado arriba
            // por HandGestureCurl. Ajustar INDEX_WALK_DEAD_ZONE/MAX_CURL
            // en el companion object si hace falta más o menos recorrido.
            // ══════════════════════════════════════════════════════════
            val stickTargetX = 0f
            val stickTargetY = if (!isRight)
                stickAxisValue(curls[1], INDEX_WALK_DEAD_ZONE, INDEX_WALK_MAX_CURL)
            else 0f

            if (isRight) {
                rightStickX = 0f
                rightStickY = 0f
            } else {
                leftStickX += (stickTargetX - leftStickX) * STICK_SMOOTHING_ALPHA
                leftStickY += (stickTargetY - leftStickY) * STICK_SMOOTHING_ALPHA
            }

            val extraPinches = HandGesture.computeExtraPinches(landmarks)

            val rawPose = HandPose(
                vx, vy, vz, tracked = true,
                qx = q[0], qy = q[1], qz = q[2], qw = q[3],
                grip = grip, pinch = pinch,
                curlThumb = curls[0], curlIndex = curls[1], curlMiddle = curls[2],
                curlRing = curls[3], curlPinky = curls[4],
                pinchMiddle = extraPinches[0],
                pinchRing = extraPinches[1],
                pinchPinky = extraPinches[2],
                joyX = if (isRight) rightStickX else leftStickX,
                joyY = if (isRight) rightStickY else leftStickY
            )


            val (calibrated, justRecalibrated) = HandOrientationCalibration.correct(isRight, rawPose)
            if (justRecalibrated) {
                if (isRight) rightHandSmoother.reset() else leftHandSmoother.reset()
            }


            if (isRight) {
                // NUEVO: precisionMode se activa apenas empezás a
                // pellizcar (pre-click) — desactiva la extrapolación de
                // latencia y aplica un suavizado extra, justo cuando
                // más importa que la mano no tiemble para acertar un
                // click en el menú.
                rightPose = rightHandSmoother.smooth(calibrated, ts, precisionMode = calibrated.pinch > 0.35f)
                if (rightClickDetector.update(rightPose.pinch)) {
                    rightPose = rightPose.copy(clicked = true)
                }
                // NUEVO: botones A/B/System de la mano derecha. Se
                // llama a .update() para que el detector avance su
                // estado interno (histéresis press/release), pero el
                // valor que se envía es el SOSTENIDO (.isHeld()) — así
                // el botón queda "apretado" mientras sigas pellizcando,
                // no solo un instante, igual que ya funciona
                // trigger/grip.
                rightButtonAClickDetector.update(rightPose.pinchMiddle)
                rightButtonBClickDetector.update(rightPose.pinchRing)
                rightButtonSystemClickDetector.update(rightPose.pinchPinky)
                rightPose = rightPose.copy(
                    buttonAPressed = rightButtonAClickDetector.isHeld(),
                    buttonBPressed = rightButtonBClickDetector.isHeld(),
                    buttonSystemPressed = rightButtonSystemClickDetector.isHeld()
                )
                sawRight = true
                lastRightVx = vx
            } else {
                // NUEVO: idem para la mano izquierda.
                leftPose = leftHandSmoother.smooth(calibrated, ts, precisionMode = calibrated.pinch > 0.35f)
                if (leftClickDetector.update(leftPose.pinch)) {
                    leftPose = leftPose.copy(clicked = true)
                }
                // NUEVO: botones X/Y/System de la mano izquierda (mismo
                // gesto que A/B en la derecha; el nombre físico del botón
                // en el visor depende de qué mano sea, no del gesto).
                // Igual que en la derecha: se envía el estado SOSTENIDO,
                // no un pulso de un solo frame.
                leftButtonAClickDetector.update(leftPose.pinchMiddle)
                leftButtonBClickDetector.update(leftPose.pinchRing)
                leftButtonSystemClickDetector.update(leftPose.pinchPinky)
                leftPose = leftPose.copy(
                    buttonAPressed = leftButtonAClickDetector.isHeld(),
                    buttonBPressed = leftButtonBClickDetector.isHeld(),
                    buttonSystemPressed = leftButtonSystemClickDetector.isHeld()
                )
                sawLeft = true
                lastLeftVx = vx
            }
        }

        if (sawLeft) {
            missedLeftMp = 0
            lastLeftPoseMp = leftPose
        } else {
            missedLeftMp++
            if (missedLeftMp <= MEDIAPIPE_MISS_TOLERANCE && lastLeftPoseMp.tracked) {
                leftPose = lastLeftPoseMp.copy(
                    clicked = false,
                    buttonAPressed = false, buttonBPressed = false, buttonSystemPressed = false,
                    joyX = 0f, joyY = 0f
                )
            } else {
                leftHandSmoother.reset()
                leftClickDetector.reset()
                leftButtonAClickDetector.reset()
                leftButtonBClickDetector.reset()
                leftButtonSystemClickDetector.reset()
                leftStickX = 0f; leftStickY = 0f
            }
        }

        if (sawRight) {
            missedRightMp = 0
            lastRightPoseMp = rightPose
        } else {
            missedRightMp++
            if (missedRightMp <= MEDIAPIPE_MISS_TOLERANCE && lastRightPoseMp.tracked) {
                rightPose = lastRightPoseMp.copy(
                    clicked = false,
                    buttonAPressed = false, buttonBPressed = false, buttonSystemPressed = false,
                    joyX = 0f, joyY = 0f
                )
            } else {
                rightHandSmoother.reset()
                rightClickDetector.reset()
                rightButtonAClickDetector.reset()
                rightButtonBClickDetector.reset()
                rightButtonSystemClickDetector.reset()
                rightStickX = 0f; rightStickY = 0f
            }
        }

        onHands?.invoke(leftPose, rightPose)
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

    private fun normalize3(x: Float, y: Float, z: Float): FloatArray {
        val len = sqrt((x*x+y*y+z*z).toDouble()).toFloat()
        return if (len < 1e-6f) floatArrayOf(0f,1f,0f) else floatArrayOf(x/len, y/len, z/len)
    }
    private fun normalize3(arr: FloatArray) = normalize3(arr[0], arr[1], arr[2])
    private fun cross3(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]
    )

    // NUEVO (avance simplificado): mapea el curl de índice (0f..1f) a un
    // valor de "acelerador" 0f..1f, con zona muerta (para que un dedo
    // apenas curvado no te haga caminar) y saturación al llegar a
    // maxCurl (para no exigir doblar el dedo del todo).
    private fun stickAxisValue(v: Float, deadZone: Float, maxRange: Float): Float {
        val absV = abs(v)
        if (absV < deadZone) return 0f
        val sign = if (v < 0f) -1f else 1f
        val scaled = ((absV - deadZone) / (maxRange - deadZone)).coerceIn(0f, 1f)
        return sign * scaled
    }

    private fun mapRange(v: Float, i0: Float, i1: Float, o0: Float, o1: Float): Float {
        val c = v.coerceIn(minOf(i0, i1), maxOf(i0, i1))
        return o0 + (c - i0) / (i1 - i0) * (o1 - o0)
    }

    companion object {
        private const val LED_MIN_AREA_FRACTION = 0.00035f
        private const val LED_SAMPLE_STEP = 3
        private const val LED_X_LO = -0.7f
        private const val LED_X_HI =  0.7f
        private const val LED_Y_LO = -0.3f
        private const val LED_Y_HI =  0.7f

        private const val LED_BLOB_NEAR = 0.010f
        private const val LED_BLOB_FAR  = 0.001f
        private const val LED_Z_NEAR    = -0.25f
        private const val LED_Z_FAR     = -0.75f


        private const val PALM_SIZE_BASE   = 0.09f
        private const val PALM_DEPTH_SCALE = 3.0f

        // NUEVO: escala aplicada a la Z de los world landmarks de
        // MediaPipe (relativa a la muñeca, en metros) para mapearla al
        // rango de profundidad usado en la escena VR. Ajustable si notás
        // que la mano se siente "plana" o "exagerada" en profundidad.
        private const val WORLD_Z_SCALE = 2.0f

        // NUEVO: confianza mínima de handedness para aceptar una
        // detección. Por debajo de esto, MediaPipe está adivinando —
        // preferimos usar la última pose conocida (vía missed/tolerance)
        // antes que aceptar un label potencialmente incorrecto.
        private const val MIN_HANDEDNESS_CONFIDENCE = 0.65f

        private const val MEDIAPIPE_Z_NEAR = -0.15f
        private const val MEDIAPIPE_Z_FAR  = -0.9f



        // AJUSTADO: de 2 a 5 — más margen antes de "soltar" la mano si
        // hay un frame malo de detección, para que el tracking se sienta
        // más continuo (menos parpadeo de la mano apareciendo/desapareciendo).
        private const val MEDIAPIPE_MISS_TOLERANCE = 5

        // NUEVO (yaw fix): corrección manual de yaw para el tracking de
        // manos en modo 6DoF (MediaPipe). Si la mano "mirando hacia
        // adelante" aparecía muy volteada hacia el usuario, este offset
        // la rota alrededor del eje vertical (Y) del espacio VR.
        // Signo: positivo = gira hacia la derecha, negativo = gira hacia
        // la izquierda.
        //
        // Van SEPARADAS por mano porque el eje "side" de la izquierda se
        // arma invertido respecto al de la derecha (ver `side` en
        // processMediaPipeResult: p5-p17 para derecha, p17-p5 para
        // izquierda), así que la misma corrección de yaw no las alinea
        // igual a las dos. Ajustá cada una de forma independiente
        // probando en SteamVR.
        private const val HAND_YAW_CORRECTION_RIGHT_DEG = -20f
        private const val HAND_YAW_CORRECTION_LEFT_DEG  = 20f

        // NUEVO (avance simplificado): doblar el índice de la mano
        // izquierda por encima de INDEX_WALK_DEAD_ZONE empieza a mover
        // hacia adelante; al llegar a INDEX_WALK_MAX_CURL satura en
        // velocidad máxima (joyY = 1.0). Subí el dead zone si notás que
        // camina solo con la mano semi-relajada; bajá el máximo si hace
        // falta cerrar el dedo del todo para llegar a velocidad máxima.
        private const val INDEX_WALK_DEAD_ZONE = 0.15f
        private const val INDEX_WALK_MAX_CURL  = 0.85f

        // Suavizado exponencial (0f..1f) del valor de avance — más alto
        // = más responsive/tembloroso, más bajo = más suave pero con
        // más retardo al empezar/parar de caminar.
        private const val STICK_SMOOTHING_ALPHA = 0.35f
    }
}
