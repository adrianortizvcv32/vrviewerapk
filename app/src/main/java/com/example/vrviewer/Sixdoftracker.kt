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
                        Log.w("SixDofTracker", "ARCore Depth API NO soportado en este dispositivo: fallback a ancho de palma")
                    }
                } else {
                    depthEnabled = false
                    if ((handMode == SixDofHandMode.MEDIAPIPE || handMode == SixDofHandMode.MEDIAPIPE_JOYCONS) && !useDepthForHands) {
                        Log.i("SixDofTracker", "Depth API desactivada por configuración (useDepthForHands=false): usando fallback de ancho de palma")
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

                .setMinHandDetectionConfidence(0.45f)
                .setMinTrackingConfidence(0.35f)
                .setMinHandPresenceConfidence(0.40f)
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


    fun recenter() { hasOrigin = false }

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

        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        var i = 0
        while (i < pixels.size) {
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

    private fun processMediaPipeResult(result: HandLandmarkerResult, depthImage: Image?) {
        var leftPose  = HandPose(-0.35f, 0.1f, -0.5f, tracked = false)
        var rightPose = HandPose( 0.35f, 0.1f, -0.5f, tracked = false)
        var sawLeft = false
        var sawRight = false

        val ts = System.currentTimeMillis()

        for (i in result.landmarks().indices) {
            val landmarks = result.landmarks()[i]

            val label = result.handedness().getOrNull(i)?.getOrNull(0)?.categoryName() ?: continue
            val isRight = label == "Right"

            val wrist = landmarks[0]



            val idxMcp   = landmarks[5]
            val pinkyMcp = landmarks[17]
            val size = hypot(
                (idxMcp.x() - pinkyMcp.x()).toDouble(),
                (idxMcp.y() - pinkyMcp.y()).toDouble()
            ).toFloat()

            val vx = mapRange(wrist.x(), 0f, 1f, LED_X_LO, LED_X_HI)
            val vy = mapRange(wrist.y(), 1f, 0f, LED_Y_LO, LED_Y_HI)


            val palmNx = (wrist.x() + idxMcp.x() + pinkyMcp.x()) / 3f
            val palmNy = (wrist.y() + idxMcp.y() + pinkyMcp.y()) / 3f
            val realDepthM = sampleDepthMeters(depthImage, palmNx, palmNy)


            val vz = if (realDepthM != null) {
                (-realDepthM).coerceIn(MEDIAPIPE_Z_FAR, MEDIAPIPE_Z_NEAR)
            } else {
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
            val q = buildQuatFromAxes(sideAxis, up, fwdAxis)


            val (grip, pinch) = HandGesture.compute(landmarks)

            val curls = HandGestureCurl.computeCurls(landmarks)

            val rawPose = HandPose(
                vx, vy, vz, tracked = true,
                qx = q[0], qy = q[1], qz = q[2], qw = q[3],
                grip = grip, pinch = pinch,
                curlThumb = curls[0], curlIndex = curls[1], curlMiddle = curls[2],
                curlRing = curls[3], curlPinky = curls[4]
            )


            val (calibrated, justRecalibrated) = HandOrientationCalibration.correct(isRight, rawPose)
            if (justRecalibrated) {
                if (isRight) rightHandSmoother.reset() else leftHandSmoother.reset()
            }


            if (isRight) {
                rightPose = rightHandSmoother.smooth(calibrated, ts)
                if (rightClickDetector.update(rightPose.pinch)) {
                    rightPose = rightPose.copy(clicked = true)
                }
                sawRight = true
            } else {
                leftPose = leftHandSmoother.smooth(calibrated, ts)
                if (leftClickDetector.update(leftPose.pinch)) {
                    leftPose = leftPose.copy(clicked = true)
                }
                sawLeft = true
            }
        }

        if (sawLeft) {
            missedLeftMp = 0
            lastLeftPoseMp = leftPose
        } else {
            missedLeftMp++
            if (missedLeftMp <= MEDIAPIPE_MISS_TOLERANCE && lastLeftPoseMp.tracked) {
                leftPose = lastLeftPoseMp.copy(clicked = false)
            } else {
                leftHandSmoother.reset()
                leftClickDetector.reset()
            }
        }

        if (sawRight) {
            missedRightMp = 0
            lastRightPoseMp = rightPose
        } else {
            missedRightMp++
            if (missedRightMp <= MEDIAPIPE_MISS_TOLERANCE && lastRightPoseMp.tracked) {
                rightPose = lastRightPoseMp.copy(clicked = false)
            } else {
                rightHandSmoother.reset()
                rightClickDetector.reset()
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


        private const val MEDIAPIPE_Z_NEAR = -0.15f
        private const val MEDIAPIPE_Z_FAR  = -0.9f



        private const val MEDIAPIPE_MISS_TOLERANCE = 2
    }
}