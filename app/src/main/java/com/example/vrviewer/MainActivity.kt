    package com.example.vrviewer
    
    import android.Manifest
    import android.content.pm.PackageManager
    import android.graphics.Bitmap
    import android.graphics.BitmapFactory
    import android.graphics.Canvas
    import android.graphics.Paint
    import android.graphics.Rect
    import android.hardware.SensorManager
    import android.media.MediaPlayer
    import android.net.Uri
    import android.opengl.GLSurfaceView
    import android.os.Bundle
    import android.view.GestureDetector
    import android.view.KeyEvent
    import android.view.MotionEvent
    import android.view.View
    import android.view.WindowManager
    import android.widget.Button
    import android.widget.EditText
    import android.widget.FrameLayout
    import android.widget.ImageView
    import android.widget.LinearLayout
    import android.widget.RadioButton
    import android.widget.RadioGroup
    import android.widget.ScrollView
    import android.widget.Switch
    import android.widget.TextView
    import android.widget.VideoView
    import androidx.activity.result.contract.ActivityResultContracts
    import androidx.appcompat.app.AppCompatActivity
    import androidx.appcompat.app.AppCompatDelegate
    import androidx.camera.view.PreviewView
    import androidx.core.content.ContextCompat
    import androidx.core.os.LocaleListCompat
    import androidx.core.view.WindowCompat
    import androidx.core.view.WindowInsetsCompat
    import androidx.core.view.WindowInsetsControllerCompat
    import com.google.ar.core.ArCoreApk
    import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
    import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
    import java.io.File
    import java.io.FileOutputStream
    import kotlin.math.abs
    import android.bluetooth.BluetoothDevice
    import android.os.Build
    
    class MainActivity : AppCompatActivity() {
    
        private var vrSender:       ITrackingSender?      = null
        private var cameraManager:  CameraPreviewManager? = null
        private var gamepadManager: GamepadManager?       = null
    
        private var joyConHid: JoyConHidManager? = null
        private var pcDiscovery:    PcDiscovery?          = null
    
    
        private var joyconDebugCounter = 0
    
    
        private var sixDofTracker: SixDofTracker? = null
        private var arCoreAvailable = false
        private var arCoreInstallRequested = false
        private var supportsConcurrentCameras = false
    
    
        private var sixDofHandMode = SixDofHandMode.NONE
    
    
        private var faceEyeTracker: FaceEyeTracker? = null
    
        private var vrStreamReceiver: VrStreamReceiver? = null
        private var streamActive = false
    
    
        private var vrAudioReceiver: VrAudioReceiver? = null
    
    
        private lateinit var glRenderer: StereoGLRenderer
        private var glInputSurface: android.view.Surface? = null
    
    
        private lateinit var parallax: ParallaxMenuController
    
    
        private lateinit var rootLayout: FrameLayout
    
        private var hubBrowser: HubBrowserView? = null
    
    
        private lateinit var connectPanel:           LinearLayout
        private lateinit var connectBackgroundImage: ImageView
        private lateinit var statusText:             TextView
        private lateinit var languageToggleButton:   Button
    
    
        private lateinit var tutorialButton:     Button
        private lateinit var tutorialOverlay:    FrameLayout
        private lateinit var tutorialVideoView:  VideoView
        private lateinit var skipTutorialButton: Button
    
        // ── Menú Hub (standalone) ──
        private lateinit var hubMenuButton: Button
        private var hubModeActive = false
        private lateinit var hubWindowController: HubWindowController
        private lateinit var hubPointerController: HubPointerController
        private var lastHubGrabbedState = false
        private var pendingHubStartAfterArCoreInstall = false
        private var pendingHubStartAfterSurface = false
        private var screenTouchTracking = false   // ← touch físico de pantalla activo sobre la ventana del Hub
    
        // ── SBS VIDEO: reproductor de video local en modo side-by-side.
        // Reutiliza streamPanel/streamGLSurfaceView y la MISMA Surface OES
        // (glInputSurface) que ya usa el stream de SteamVR, así hereda gratis
        // distorsión de lente, separación y lupa. ──
        private lateinit var sbsVideoButton: Button
        private lateinit var videoPlayPauseButton: Button
        private var localVideoPlayer: MediaPlayer? = null
        private var isPlayingLocalVideo = false
        private var pendingSbsVideoUri: Uri? = null
    
        // Giroscopio del modo Ver videos SBS: mueve el rectángulo del video
        // dentro del campo de visión al girar la cabeza, simulando una
        // pantalla fija en la habitación. Ver GyroVideoWindowTracker.kt.
        private var videoHeadTracker: GyroVideoWindowTracker? = null
    
        private val pickSbsVideoLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                uri?.let { playLocalSbsVideo(it) }
            }
    
        // ── Tab WiFi ──
        private lateinit var tabWifi:         Button
        private lateinit var tabCable:        Button
        private lateinit var wifiTabContent:  LinearLayout
        private lateinit var cableTabContent: LinearLayout
        private lateinit var ipInput:         EditText
        private lateinit var scanButton:      Button
        private lateinit var pcListLayout:    LinearLayout
        private lateinit var scanStatusText:  TextView
    
        // ── Tab Cable ──
        private lateinit var cableIpInput:       EditText
        private lateinit var connectCableButton: Button
        private lateinit var usbStatusText:      TextView
        private lateinit var usbStatusDot:       TextView
        private lateinit var refreshUsbButton:   Button
    
        // ── Vistas: panel de control ──
        private lateinit var controlPanel:         LinearLayout
        private lateinit var controlPanelGrabberZone: FrameLayout
        private lateinit var controlScrollView:    ScrollView
        private lateinit var switchHmd:            Switch
        private lateinit var switchHand:           Switch
        private lateinit var handTrackingSubPanel: LinearLayout
        private lateinit var switchSixDof:         Switch
        private lateinit var sixDofSubPanel:       LinearLayout
        private lateinit var sixDofStatusText:     TextView
        private lateinit var sixDofModeGroup:      RadioGroup
        private lateinit var sixDofModePlain:      RadioButton
        private lateinit var sixDofModeLeds:       RadioButton
        private lateinit var sixDofModeHandTracking: RadioButton
        private lateinit var sixDofModeHandJoycons:  RadioButton
        private lateinit var switchCameraOverlay:  Switch
        private lateinit var switchEyeTracking:    Switch
        private lateinit var eyeTrackingStatusText: TextView
        private lateinit var trackingModeGroup:    RadioGroup
        private lateinit var recenterButton:       Button
        private lateinit var controlStatusText:    TextView
        private lateinit var gamepadStatusText:    TextView
        private lateinit var streamButton:         Button
        private lateinit var connectionTypeBadge:  TextView
    
    
        private lateinit var sensDownButton: Button
        private lateinit var sensUpButton:   Button
        private lateinit var sensLabelText:  TextView
    
        // Control de zoom de la cámara de tracking de manos (modo "manos
        // normal", CameraPreviewManager). Mismo patrón que
        // sensDownButton/sensUpButton/sensLabelText de arriba (que ajustan
        // la sensibilidad 6DoF) pero para el zoom óptico/digital de la
        // cámara.
        private lateinit var handZoomDownButton: Button
        private lateinit var handZoomUpButton:   Button
        private lateinit var handZoomLabelText:  TextView
    
    
        private lateinit var cameraOverlayPanel: FrameLayout
        private lateinit var cameraPreviewView:  PreviewView
        private lateinit var skeletonOverlay:    SkeletonOverlayView
    
    
        private lateinit var streamPanel:             FrameLayout
        private lateinit var streamGLSurfaceView:     GLSurfaceView
        private lateinit var streamLoadingOverlay:    LinearLayout
        private lateinit var streamStatusOverlayText: TextView
        private lateinit var closeStreamButton:       Button
        private lateinit var recenterHmdButton:       Button
    
        private lateinit var qualityToggleButton: Button
        private lateinit var streamQualityPanel:  LinearLayout
        private lateinit var resDownButton:       Button
        private lateinit var resUpButton:         Button
        private lateinit var resLabelText:        TextView
        private lateinit var brDownButton:        Button
        private lateinit var brUpButton:          Button
        private lateinit var brLabelText:         TextView
        private lateinit var fpsDownButton:       Button
        private lateinit var fpsUpButton:         Button
        private lateinit var fpsLabelText:        TextView
    
    
        private lateinit var lensToggleButton: Button
        private lateinit var streamLensPanel:  LinearLayout
        private lateinit var distDownButton:   Button
        private lateinit var distUpButton:     Button
        private lateinit var distLabelText:    TextView
        private lateinit var sepDownButton:    Button
        private lateinit var sepUpButton:      Button
        private lateinit var sepLabelText:     TextView
        private lateinit var zoomDownButton:   Button
        private lateinit var zoomUpButton:     Button
        private lateinit var zoomLabelText:    TextView
        private lateinit var fullscreenVideoButton: Button // ── SBS VIDEO + PANTALLA COMPLETA
        private lateinit var envModeVideoButton: Button // ── SBS VIDEO + ENTORNO CON GIRO
    
        // NUEVO — SBS VIDEO + DISTANCIA: acerca/aleja la "pantalla" del video
        // world-locked escalando videoHalfW/videoHalfH (ver
        // StereoGLRenderer.setVideoWindowScale()). Mismo patrón visual que
        // distDownButton/distUpButton/distLabelText de arriba (que ajustan la
        // distorsión de lente), pero como fila propia (videoDistRow) porque
        // solo tiene sentido mientras se reproduce un video SBS local.
        private lateinit var videoDistRow:        LinearLayout
        private lateinit var videoDistDownButton: Button
        private lateinit var videoDistUpButton:   Button
        private lateinit var videoDistLabelText:  TextView
    
    
        private var currentTrackingMode = TrackingMode.NONE
        private val discoveredPcs       = LinkedHashMap<String, String>()
        private var connectedPcIp: String = ""
        private var connectionType: ConnectionType = ConnectionType.WIFI
    
        private var cableAutoMode: ConnectionType = ConnectionType.CABLE
    
    
        private var connectingInProgress = false
    
        enum class ConnectionType { WIFI, CABLE, USB_ADB }
    
        private data class QualityPreset(val label: String, val w: Int, val h: Int)
        private val RES_PRESETS = listOf(
            QualityPreset("Baja (854×480)", 854, 480),
            QualityPreset("Media (1280×720)", 1280, 720),
            QualityPreset("Alta (1600×900)", 1600, 900),
            QualityPreset("Full HD (1920×1080)", 1920, 1080)
        )
        private var resIndex = 1
    
        private var bitrateMbps = 6.0f
        private val BITRATE_MIN = 2.0f
        private val BITRATE_MAX = 12.0f
        private val BITRATE_STEP = 1.0f
    
        private val FPS_PRESETS = listOf(30, 45, 60)
        private var fpsIndex = 0
    
        private val DIST_STEP = 0.02f
        private val DIST_MIN  = 0.0f
        private val DIST_MAX  = 0.6f
        private val SEP_STEP  = 0.01f
        private val SEP_MIN   = -0.2f
        private val SEP_MAX   = 0.2f
        private val ZOOM_STEP = 0.05f
        private val ZOOM_MIN  = 0.5f
        private val ZOOM_MAX  = 2.0f
        private var lensPanelVisible = false
    
        // NUEVO — SBS VIDEO + DISTANCIA: rango del factor de escala del
        // rectángulo world-locked del video (ver StereoGLRenderer.videoWindowScale).
        // 1.0 = tamaño por defecto; > 1.0 acerca (se ve más grande), < 1.0
        // aleja (se ve más chico).
        private val VIDEO_DIST_STEP = 0.1f
        private val VIDEO_DIST_MIN  = 0.5f
        private val VIDEO_DIST_MAX  = 2.5f
    
        private val SENS_STEP = 0.1f
        private val SENS_MIN  = 0.25f
        private val SENS_MAX  = 3.0f
        private var sixDofSensitivity = 1.0f
    
        // Zoom de la cámara de tracking de manos. 1.0f = sin zoom (campo de
        // visión completo de la lente elegida por CameraPreviewManager — ver
        // pickWidestBackCameraSelector()). El máximo real depende del
        // hardware; se clampea dentro de CameraPreviewManager.setZoomRatio()
        // contra el rango que reporta la cámara, así que acá el tope
        // HAND_ZOOM_MAX es solo un límite de seguridad del lado de la UI (por
        // si el dispositivo soporta mucho más zoom del que tiene sentido para
        // tracking de manos).
        private val HAND_ZOOM_STEP = 0.25f
        private val HAND_ZOOM_MIN  = 1.0f
        private val HAND_ZOOM_MAX  = 4.0f
        private var handCameraZoom = 1.0f
    
        private lateinit var controlPanelGestureDetector: GestureDetector
        private var controlPanelVisible = true
    
        private var qualityPanelVisible = false
    
        private var streamControlsVisible = true
    
    
        private var swipeIgnoredForCurrentGesture = false
        private val swipeHitRect = Rect()
    
        private var pendingCameraAction: (() -> Unit)? = null
    
        private val cameraPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) pendingCameraAction?.invoke()
            else showStatus("Permiso de cámara denegado")
            pendingCameraAction = null
        }
    
    
    
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setContentView(R.layout.activity_main)
            setupFullscreen()
    
            bindViews()
            setupStreamRenderer()
            setupConnectPanel()
            setupTutorialOverlay()
            setupControlListeners()
            setupQualityControls()
            setupLensControls()
            setupSixDofSensitivityControls()
            setupHandZoomControls()
            setupControlPanelSwipe()
            setupGamepad()
            setupSixDofCapabilities()
            setupParallax()
            setupSbsVideo() // ── SBS VIDEO
    
            selectTab(ConnectionType.WIFI)
            startDiscovery()
        }
    
        override fun onDestroy() {
            super.onDestroy()
            stopStreamInternal()
            stopAudioInternal()
            pcDiscovery?.stop()
            stopAllTrackers()
            stopSixDof()
            stopEyeTracking()
            joyConHid?.disconnectAll()
            vrSender?.stop()
            stopLocalVideoIfNeeded() // ── SBS VIDEO
            if (::glRenderer.isInitialized) glRenderer.release()
            if (::tutorialVideoView.isInitialized) tutorialVideoView.stopPlayback()
        }
    
        override fun onWindowFocusChanged(hasFocus: Boolean) {
            super.onWindowFocusChanged(hasFocus)
            if (hasFocus) setupFullscreen()
        }
    
        override fun onResume() {
            super.onResume()
            if (arCoreInstallRequested && switchSixDof.isChecked && sixDofTracker == null) {
                arCoreInstallRequested = false
                ensureArCoreInstalledThenStart()
            }
            if (arCoreInstallRequested && pendingHubStartAfterArCoreInstall) {
                arCoreInstallRequested = false
                pendingHubStartAfterArCoreInstall = false
                ensureArCoreInstalledThenStartHub()
            }
            if (::parallax.isInitialized) parallax.start()
            // ── SBS VIDEO: si el video estaba pausado por onPause(), lo reanudamos
            if (isPlayingLocalVideo) {
                localVideoPlayer?.let { if (!it.isPlaying) it.start() }
                videoHeadTracker?.start()
            }
        }
    
        override fun onPause() {
            super.onPause()
            if (::tutorialVideoView.isInitialized && tutorialVideoView.isPlaying) {
                tutorialVideoView.pause()
            }
            if (::parallax.isInitialized) parallax.stop()
            // ── SBS VIDEO: pausar al salir de la app (evita seguir consumiendo
            // batería/CPU decodificando video que ya no se ve)
            if (isPlayingLocalVideo) {
                localVideoPlayer?.let { if (it.isPlaying) it.pause() }
                videoHeadTracker?.stop()
            }
        }
    
    
    
        private fun bindViews() {
            rootLayout             = findViewById(R.id.rootLayout)
            connectPanel           = findViewById(R.id.connectPanel)
            connectBackgroundImage = findViewById(R.id.connectBackgroundImage)
            statusText             = findViewById(R.id.statusText)
            languageToggleButton   = findViewById(R.id.languageToggleButton)
    
            tutorialButton     = findViewById(R.id.tutorialButton)
            tutorialOverlay    = findViewById(R.id.tutorialOverlay)
            tutorialVideoView  = findViewById(R.id.tutorialVideoView)
            skipTutorialButton = findViewById(R.id.skipTutorialButton)
    
            hubMenuButton = findViewById(R.id.hubMenuButton)
    
            // ── SBS VIDEO
            sbsVideoButton        = findViewById(R.id.sbsVideoButton)
            videoPlayPauseButton  = findViewById(R.id.videoPlayPauseButton)
    
            tabWifi        = findViewById(R.id.tabWifi)
            tabCable       = findViewById(R.id.tabCable)
            wifiTabContent = findViewById(R.id.wifiTabContent)
            cableTabContent= findViewById(R.id.cableTabContent)
            ipInput        = findViewById(R.id.ipInput)
            scanButton     = findViewById(R.id.scanButton)
            pcListLayout   = findViewById(R.id.pcListLayout)
            scanStatusText = findViewById(R.id.scanStatusText)
    
            cableIpInput       = findViewById(R.id.cableIpInput)
            connectCableButton = findViewById(R.id.connectCableButton)
            usbStatusText      = findViewById(R.id.usbStatusText)
            usbStatusDot       = findViewById(R.id.usbStatusDot)
            refreshUsbButton   = findViewById(R.id.refreshUsbButton)
    
            controlPanel            = findViewById(R.id.controlPanel)
            controlPanelGrabberZone = findViewById(R.id.controlPanelGrabberZone)
            controlScrollView       = findViewById(R.id.controlScrollView)
            switchHmd            = findViewById(R.id.switchHmd)
            switchHand           = findViewById(R.id.switchHand)
            handTrackingSubPanel = findViewById(R.id.handTrackingSubPanel)
            switchSixDof         = findViewById(R.id.switchSixDof)
            sixDofSubPanel       = findViewById(R.id.sixDofSubPanel)
            sixDofStatusText     = findViewById(R.id.sixDofStatusText)
            sixDofModeGroup      = findViewById(R.id.sixDofModeGroup)
            sixDofModePlain      = findViewById(R.id.sixDofModePlain)
            sixDofModeLeds       = findViewById(R.id.sixDofModeLeds)
            sixDofModeHandTracking = findViewById(R.id.sixDofModeHandTracking)
            sixDofModeHandJoycons  = findViewById(R.id.sixDofModeHandJoycons)
            switchCameraOverlay  = findViewById(R.id.switchCameraOverlay)
            switchEyeTracking    = findViewById(R.id.switchEyeTracking)
            eyeTrackingStatusText = findViewById(R.id.eyeTrackingStatusText)
            trackingModeGroup    = findViewById(R.id.trackingModeGroup)
            recenterButton       = findViewById(R.id.recenterButton)
            controlStatusText    = findViewById(R.id.controlStatusText)
            gamepadStatusText    = findViewById(R.id.gamepadStatusText)
            streamButton         = findViewById(R.id.streamButton)
            connectionTypeBadge  = findViewById(R.id.connectionTypeBadge)
    
            sensDownButton = findViewById(R.id.sensDownButton)
            sensUpButton   = findViewById(R.id.sensUpButton)
            sensLabelText  = findViewById(R.id.sensLabelText)
    
            // Control de zoom de cámara de manos.
            handZoomDownButton = findViewById(R.id.handZoomDownButton)
            handZoomUpButton   = findViewById(R.id.handZoomUpButton)
            handZoomLabelText  = findViewById(R.id.handZoomLabelText)
    
            cameraOverlayPanel = findViewById(R.id.cameraOverlayPanel)
            cameraPreviewView  = findViewById(R.id.cameraPreviewView)
            skeletonOverlay    = findViewById(R.id.skeletonOverlay)
    
            cameraPreviewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    
            streamPanel             = findViewById(R.id.streamPanel)
            streamGLSurfaceView     = findViewById(R.id.streamGLSurfaceView)
            streamLoadingOverlay    = findViewById(R.id.streamLoadingOverlay)
            streamStatusOverlayText = findViewById(R.id.streamStatusOverlayText)
            closeStreamButton       = findViewById(R.id.closeStreamButton)
            recenterHmdButton       = findViewById(R.id.recenterHmdButton)
    
            qualityToggleButton = findViewById(R.id.qualityToggleButton)
            streamQualityPanel  = findViewById(R.id.streamQualityPanel)
            resDownButton       = findViewById(R.id.resDownButton)
            resUpButton         = findViewById(R.id.resUpButton)
            resLabelText        = findViewById(R.id.resLabelText)
            brDownButton        = findViewById(R.id.brDownButton)
            brUpButton          = findViewById(R.id.brUpButton)
            brLabelText         = findViewById(R.id.brLabelText)
            fpsDownButton       = findViewById(R.id.fpsDownButton)
            fpsUpButton         = findViewById(R.id.fpsUpButton)
            fpsLabelText        = findViewById(R.id.fpsLabelText)
    
            lensToggleButton = findViewById(R.id.lensToggleButton)
            streamLensPanel  = findViewById(R.id.streamLensPanel)
            distDownButton   = findViewById(R.id.distDownButton)
            distUpButton     = findViewById(R.id.distUpButton)
            distLabelText    = findViewById(R.id.distLabelText)
            sepDownButton    = findViewById(R.id.sepDownButton)
            sepUpButton      = findViewById(R.id.sepUpButton)
            sepLabelText     = findViewById(R.id.sepLabelText)
            zoomDownButton   = findViewById(R.id.zoomDownButton)
            zoomUpButton     = findViewById(R.id.zoomUpButton)
            zoomLabelText    = findViewById(R.id.zoomLabelText)
    
            // ── SBS VIDEO + PANTALLA COMPLETA
            fullscreenVideoButton = findViewById(R.id.fullscreenVideoButton)
            // ── SBS VIDEO + ENTORNO CON GIRO
            envModeVideoButton = findViewById(R.id.envModeVideoButton)
    
            // ── SBS VIDEO + DISTANCIA
            videoDistRow        = findViewById(R.id.videoDistRow)
            videoDistDownButton = findViewById(R.id.videoDistDownButton)
            videoDistUpButton   = findViewById(R.id.videoDistUpButton)
            videoDistLabelText  = findViewById(R.id.videoDistLabelText)
        }
    
    
    
        private fun setupParallax() {
            parallax = ParallaxMenuController(
                context = this,
                backgroundView = connectBackgroundImage,
                foregroundView = connectPanel,
                backgroundMaxOffsetPx = 40f,
                foregroundMaxOffsetPx = 8f,
                smoothing = 0.15f
            )
        }
    
    
    
        private fun setupStreamRenderer() {
            val prefs = getSharedPreferences("vr_lens", MODE_PRIVATE)
    
            glRenderer = StereoGLRenderer { surface ->
                glInputSurface = surface
                runOnUiThread {
                    if (streamActive && vrStreamReceiver == null) {
                        launchStreamReceiver(surface)
                    }
                    if (pendingHubStartAfterSurface) {
                        startHubInternal()
                    }
                    // ── SBS VIDEO: si se pidió reproducir un video antes de que
                    // la Surface estuviera lista, la lanzamos ahora que ya existe.
                    pendingSbsVideoUri?.let { uri ->
                        pendingSbsVideoUri = null
                        playLocalSbsVideo(uri)
                    }
                }
            }
            glRenderer.distortionK    = prefs.getFloat("distortionK", 0.22f)
            glRenderer.lensSeparation = prefs.getFloat("lensSeparation", 0.0f)
            glRenderer.lensZoom       = prefs.getFloat("lensZoom", 1.0f)
    
            streamGLSurfaceView.setEGLContextClientVersion(2)
            streamGLSurfaceView.setRenderer(glRenderer)
            streamGLSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            streamGLSurfaceView.preserveEGLContextOnPause = true
        }
    
    
    
        private fun setupConnectPanel() {
            tabWifi.setOnClickListener  { selectTab(ConnectionType.WIFI)  }
            tabCable.setOnClickListener { selectTab(ConnectionType.CABLE) }
    
            languageToggleButton.setOnClickListener { toggleAppLanguage() }
            updateLanguageButtonLabel()
    
            switchHand.isChecked = false
            setRadioGroupEnabled(trackingModeGroup, false)
            switchCameraOverlay.isEnabled = false
            switchSixDof.isEnabled = false
            setRadioGroupEnabled(sixDofModeGroup, false)
            switchEyeTracking.isEnabled = false
    
            findViewById<Button>(R.id.connectButton).setOnClickListener {
                val ip = ipInput.text.toString().trim()
                when {
                    connectingInProgress -> { /* ya hay un intento en curso, ignorar */ }
                    ip.isEmpty() -> statusText.text = "Ingresa una IP válida"
                    !isValidIpAddress(ip) -> statusText.text =
                        "IP inválida. Formato esperado: 192.168.1.X"
                    else -> connect(ip, ConnectionType.WIFI)
                }
            }
            scanButton.setOnClickListener { startDiscovery() }
    
            refreshUsbButton.setOnClickListener { refreshUsbStatus() }
            connectCableButton.setOnClickListener {
                when {
                    connectingInProgress -> { /* ya hay un intento en curso, ignorar */ }
                    cableAutoMode == ConnectionType.USB_ADB -> connect("127.0.0.1", ConnectionType.USB_ADB)
                    else -> {
                        val ip = cableIpInput.text.toString().trim()
                        when {
                            ip.isEmpty() -> statusText.text = "Ingresa o detecta la IP de la PC"
                            !isValidIpAddress(ip) -> statusText.text =
                                "IP inválida. Formato esperado: 192.168.42.1"
                            else -> connect(ip, ConnectionType.CABLE)
                        }
                    }
                }
            }
    
            hubMenuButton.setOnClickListener { startHubRequestingPermission() }
        }
    
    
        private fun isValidIpAddress(ip: String): Boolean {
            val parts = ip.trim().split(".")
            if (parts.size != 4) return false
            return parts.all { part ->
                part.isNotEmpty() && part.all { c -> c.isDigit() } &&
                        (part.toIntOrNull()?.let { it in 0..255 } == true)
            }
        }
    
    
        private fun setupTutorialOverlay() {
            tutorialButton.setOnClickListener {
                android.util.Log.d("Tutorial", "Botón 'Ver tutorial' presionado")
                android.widget.Toast.makeText(
                    this, "Botón presionado, cargando video…", android.widget.Toast.LENGTH_SHORT
                ).show()
                playTutorialVideo()
            }
            skipTutorialButton.setOnClickListener { closeTutorialVideo() }
        }
    
        private fun playTutorialVideo() {
            android.util.Log.d("Tutorial", "playTutorialVideo() llamado")
            tutorialOverlay.visibility = View.VISIBLE
            tutorialOverlay.bringToFront()
            try {
                val cachedFile = copyAssetToCache("girl.mp4")
                val uri = Uri.fromFile(cachedFile)
                android.util.Log.d("Tutorial", "Cargando URI desde caché: $uri")
                tutorialVideoView.setVideoURI(uri)
                tutorialVideoView.setOnPreparedListener { mp ->
                    android.util.Log.d("Tutorial", "Video preparado, iniciando reproducción")
                    android.widget.Toast.makeText(
                        this, "Video listo, reproduciendo…", android.widget.Toast.LENGTH_SHORT
                    ).show()
                    mp.isLooping = false
                    tutorialVideoView.start()
                }
                tutorialVideoView.setOnCompletionListener {
                    android.util.Log.d("Tutorial", "Video terminado")
                    closeTutorialVideo()
                }
                tutorialVideoView.setOnErrorListener { _, what, extra ->
                    android.util.Log.e("Tutorial", "Error de MediaPlayer: what=$what extra=$extra")
                    android.widget.Toast.makeText(
                        this, "ERROR video: what=$what extra=$extra", android.widget.Toast.LENGTH_LONG
                    ).show()
                    showStatus("Error reproduciendo tutorial (código $what/$extra) — revisa que girl.mp4 sea H.264 Baseline + AAC en un contenedor .mp4 válido")
                    closeTutorialVideo()
                    true
                }
                tutorialVideoView.requestFocus()
            } catch (e: Exception) {
                android.util.Log.e("Tutorial", "Excepción cargando el video", e)
                showStatus("No se pudo cargar el tutorial: ${e.message}")
                closeTutorialVideo()
            }
        }
    
        private fun copyAssetToCache(assetName: String): File {
            val outFile = File(cacheDir, assetName)
            assets.open(assetName).use { input ->
                val assetSize = input.available()
                if (!outFile.exists() || outFile.length() != assetSize.toLong()) {
                    assets.open(assetName).use { freshInput ->
                        FileOutputStream(outFile).use { output ->
                            freshInput.copyTo(output)
                        }
                    }
                }
            }
            return outFile
        }
    
        private fun closeTutorialVideo() {
            tutorialVideoView.stopPlayback()
            tutorialOverlay.visibility = View.GONE
            window.decorView.requestFocus()
        }
    
    
    
        private fun toggleAppLanguage() {
            val currentTag = AppCompatDelegate.getApplicationLocales()
                .toLanguageTags()
                .lowercase()
            val nextLang = if (currentTag.startsWith("en")) "es" else "en"
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(nextLang)
            )
        }
    
        private fun updateLanguageButtonLabel() {
            val currentTag = AppCompatDelegate.getApplicationLocales()
                .toLanguageTags()
                .lowercase()
            languageToggleButton.text = if (currentTag.startsWith("en")) "ES" else "EN"
        }
    
    
    
        private fun selectTab(type: ConnectionType) {
            when (type) {
                ConnectionType.WIFI -> {
                    tabWifi.setTextColor(0xFFFFFFFF.toInt())
                    tabWifi.backgroundTintList  =
                        android.content.res.ColorStateList.valueOf(0xFF1E88E5.toInt())
                    tabCable.setTextColor(0xFF888888.toInt())
                    tabCable.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(0xFF1A1A1A.toInt())
                    wifiTabContent.visibility  = View.VISIBLE
                    cableTabContent.visibility = View.GONE
                }
                ConnectionType.CABLE, ConnectionType.USB_ADB -> {
                    tabCable.setTextColor(0xFFFFFFFF.toInt())
                    tabCable.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(0xFFD97706.toInt())
                    tabWifi.setTextColor(0xFF888888.toInt())
                    tabWifi.backgroundTintList  =
                        android.content.res.ColorStateList.valueOf(0xFF1A1A1A.toInt())
                    wifiTabContent.visibility  = View.GONE
                    cableTabContent.visibility = View.VISIBLE
                    refreshUsbStatus()
                }
            }
        }
    
    
    
        private fun refreshUsbStatus() {
            usbStatusDot.setTextColor(0xFFF59E0B.toInt())
            usbStatusText.text = "Comprobando ADB…"
    
            AdbTunnelHelper.checkReachable { reachable ->
                runOnUiThread {
                    if (reachable) {
                        cableAutoMode = ConnectionType.USB_ADB
                        usbStatusDot.setTextColor(0xFF4ADE80.toInt())
                        usbStatusText.text = "Depuración USB detectada (ADB) · listo para conectar"
                        cableIpInput.setText("127.0.0.1 (ADB)")
                        cableIpInput.isEnabled = false
                        return@runOnUiThread
                    }
    
                    val rndisIp = UsbConnectionHelper.getPcIpViaCable()
                    if (rndisIp != null) {
                        cableAutoMode = ConnectionType.CABLE
                        cableIpInput.isEnabled = true
                        usbStatusDot.setTextColor(0xFF4ADE80.toInt())
                        usbStatusText.text = "Cable USB detectado (RNDIS) · PC: $rndisIp"
                        cableIpInput.setText(rndisIp)
                    } else {
                        cableAutoMode = ConnectionType.CABLE
                        usbStatusDot.setTextColor(0xFFEF4444.toInt())
                        usbStatusText.text =
                            "Cable no detectado. Conecta el cable y activa 'Depuración USB' en Opciones de desarrollador."
                        cableIpInput.isEnabled = true
                        if (cableIpInput.text.isBlank() || cableIpInput.text.contains("ADB"))
                            cableIpInput.setText("192.168.42.1")
                    }
                }
            }
        }
    
    
    
        private fun setupSixDofCapabilities() {
            supportsConcurrentCameras = DeviceCapabilities.supportsConcurrentCameras(this)
    
            sixDofStatusText.text = "Comprobando compatibilidad 6DoF…"
            DeviceCapabilities.checkArCoreAvailability(this) { availability ->
                runOnUiThread {
                    arCoreAvailable = availability == DeviceCapabilities.ArAvailability.SUPPORTED
                    sixDofStatusText.text = when {
                        !arCoreAvailable ->
                            "6DoF no disponible: este dispositivo no soporta ARCore"
                        supportsConcurrentCameras ->
                            "6DoF disponible · puede usarse junto con tracking de manos (2 cámaras)"
                        else ->
                            "6DoF disponible · solo 1 cámara: usa '6DoF + LEDs' o '6DoF + Manos' para trackear manos a la vez"
                    }
                    setRadioGroupEnabled(sixDofModeGroup, arCoreAvailable)
                    updateTrackingExclusivity()
                }
            }
    
            sixDofModeGroup.setOnCheckedChangeListener { _, checkedId ->
                sixDofHandMode = when (checkedId) {
                    R.id.sixDofModeLeds         -> SixDofHandMode.LED
                    R.id.sixDofModeHandTracking -> SixDofHandMode.MEDIAPIPE
                    R.id.sixDofModeHandJoycons  -> SixDofHandMode.MEDIAPIPE_JOYCONS
                    else                        -> SixDofHandMode.NONE
                }
                updateHandJoyconsFlag()
    
                if (sixDofHandMode != SixDofHandMode.NONE) {
                    if (switchHand.isChecked) switchHand.isChecked = false
                    showStatus(
                        when (sixDofHandMode) {
                            SixDofHandMode.LED ->
                                "6DoF + LEDs: manos verde=izq 🟢  azul=der 🔵 (cámara compartida con 6DoF)"
                            SixDofHandMode.MEDIAPIPE ->
                                "6DoF + Manos (MediaPipe): tracking real de manos, cámara compartida con 6DoF"
                            SixDofHandMode.MEDIAPIPE_JOYCONS ->
                                "6DoF + Manos + Joycons: posición=cámara, rotación/botones=Joy-Con si hay emparejado"
                            else -> ""
                        }
                    )
                }
                updateTrackingExclusivity()
                refreshGamepadStatus()
    
                if (switchSixDof.isChecked && sixDofTracker != null) {
                    startSixDofInternal()
                }
            }
        }
    
        private fun updateTrackingExclusivity() {
            if (sixDofHandMode != SixDofHandMode.NONE && switchSixDof.isChecked) {
                switchHand.isEnabled   = false
                switchSixDof.isEnabled = arCoreAvailable
                return
            }
            if (supportsConcurrentCameras) {
                switchHand.isEnabled   = true
                switchSixDof.isEnabled = arCoreAvailable
                return
            }
            val sixDofOn = switchSixDof.isChecked
            val handOn   = switchHand.isChecked
            switchHand.isEnabled   = !sixDofOn
            switchSixDof.isEnabled = arCoreAvailable && !handOn
        }
    
    
    
        private fun setupSixDofSensitivityControls() {
            val prefs = getSharedPreferences("vr_sixdof", MODE_PRIVATE)
            sixDofSensitivity = prefs.getFloat("movementSensitivity", 1.0f)
    
            sensDownButton.setOnClickListener {
                sixDofSensitivity = (sixDofSensitivity - SENS_STEP).coerceIn(SENS_MIN, SENS_MAX)
                applySixDofSensitivity(prefs)
            }
            sensUpButton.setOnClickListener {
                sixDofSensitivity = (sixDofSensitivity + SENS_STEP).coerceIn(SENS_MIN, SENS_MAX)
                applySixDofSensitivity(prefs)
            }
            updateSensLabel()
        }
    
        private fun applySixDofSensitivity(prefs: android.content.SharedPreferences) {
            updateSensLabel()
            prefs.edit().putFloat("movementSensitivity", sixDofSensitivity).apply()
            sixDofTracker?.movementSensitivity = sixDofSensitivity
        }
    
        private fun updateSensLabel() {
            sensLabelText.text = "Sensibilidad: %.1f×".format(sixDofSensitivity)
        }
    
    
        // Control de zoom de la cámara usada para tracking de manos (modo
        // "manos normal", vía CameraPreviewManager). Mismo patrón exacto que
        // setupSixDofSensitivityControls() de arriba: persiste en
        // SharedPreferences y aplica el valor apenas cambia. Si la cámara
        // todavía no está iniciada cuando tocás el botón, el valor se guarda
        // igual y se aplica solo en cuanto arranque el tracking (ver
        // requestCameraAndStart() / startTracker()).
        private fun setupHandZoomControls() {
            val prefs = getSharedPreferences("vr_handzoom", MODE_PRIVATE)
            handCameraZoom = prefs.getFloat("cameraZoom", 1.0f)
    
            handZoomDownButton.setOnClickListener {
                handCameraZoom = (handCameraZoom - HAND_ZOOM_STEP).coerceIn(HAND_ZOOM_MIN, HAND_ZOOM_MAX)
                applyHandCameraZoom(prefs)
            }
            handZoomUpButton.setOnClickListener {
                handCameraZoom = (handCameraZoom + HAND_ZOOM_STEP).coerceIn(HAND_ZOOM_MIN, HAND_ZOOM_MAX)
                applyHandCameraZoom(prefs)
            }
            updateHandZoomLabel()
        }
    
        private fun applyHandCameraZoom(prefs: android.content.SharedPreferences) {
            updateHandZoomLabel()
            prefs.edit().putFloat("cameraZoom", handCameraZoom).apply()
            cameraManager?.setZoomRatio(handCameraZoom)
        }
    
        private fun updateHandZoomLabel() {
            handZoomLabelText.text = "Zoom cámara: %.2f×".format(handCameraZoom)
        }
    
    
    
        private fun startSixDofRequestingPermission() {
            pendingCameraAction = { ensureArCoreInstalledThenStart() }
            when {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED -> ensureArCoreInstalledThenStart()
                else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    
        private fun ensureArCoreInstalledThenStart() {
            try {
                val status = ArCoreApk.getInstance().requestInstall(this, !arCoreInstallRequested)
                android.util.Log.d("SixDofTracker", "requestInstall() -> $status")
                when (status) {
                    ArCoreApk.InstallStatus.INSTALLED -> startSixDofInternal()
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        arCoreInstallRequested = true
                        showStatus("Instalando/actualizando Google Play Services para RA…")
                    }
                    else -> {
                        switchSixDof.isChecked = false
                        showStatus("6DoF: estado de instalación inesperado ($status)")
                    }
                }
            } catch (e: UnavailableDeviceNotCompatibleException) {
                android.util.Log.e("SixDofTracker", "UnavailableDeviceNotCompatibleException", e)
                switchSixDof.isChecked = false
                showStatus("Este dispositivo no es compatible con ARCore")
            } catch (e: UnavailableUserDeclinedInstallationException) {
                android.util.Log.e("SixDofTracker", "UnavailableUserDeclinedInstallationException", e)
                switchSixDof.isChecked = false
                showStatus("Instalación de ARCore cancelada por el usuario")
            } catch (e: Exception) {
                android.util.Log.e("SixDofTracker", "Error en ensureArCoreInstalledThenStart: ${e.javaClass.simpleName}", e)
                switchSixDof.isChecked = false
                showStatus("Error preparando 6DoF: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    
        private fun startSixDofInternal() {
            sixDofTracker?.stop()
            sixDofTracker = SixDofTracker(
                context = this,
                onPose  = { pose -> vrSender?.updateSixDofPose(pose) },
                onError = { msg  -> runOnUiThread {
                    showStatus(msg)
                    val esAvisoInformativo = msg.contains("sin señal") || msg.contains("perdió el tracking")
                    if (!esAvisoInformativo) {
                        switchSixDof.isChecked = false
                    }
                } },
                handMode = sixDofHandMode,
                onHands = if (sixDofHandMode != SixDofHandMode.NONE)
                    { l, r -> vrSender?.updateHands(l, r) } else null,
                onTrackingStarted = {
                    if (sixDofHandMode == SixDofHandMode.MEDIAPIPE_JOYCONS) {
                        runOnUiThread { warnIfNoJoyConsConnectedSixDof() }
                    }
                }
            )
            sixDofTracker?.movementSensitivity = sixDofSensitivity
            vrSender?.setSixDofEnabled(true)
            sixDofTracker?.start()
            showStatus(
                when (sixDofHandMode) {
                    SixDofHandMode.LED ->
                        "Tracking 6DoF + LEDs manos iniciado (cámara compartida)"
                    SixDofHandMode.MEDIAPIPE ->
                        "Tracking 6DoF + Manos (MediaPipe) iniciado (cámara compartida)"
                    SixDofHandMode.MEDIAPIPE_JOYCONS ->
                        "Tracking 6DoF + Manos + Joycons iniciado (cámara compartida)"
                    else ->
                        "Tracking 6DoF iniciado (posición real vía cámara)"
                }
            )
        }
    
        private fun stopSixDof() {
            sixDofTracker?.stop()
            sixDofTracker = null
            vrSender?.setSixDofEnabled(false)
            if (sixDofHandMode != SixDofHandMode.NONE) {
                vrSender?.updateHands(
                    HandPose(-0.35f, 0.1f, -0.5f, false),
                    HandPose( 0.35f, 0.1f, -0.5f, false)
                )
            }
        }
    
    
        private fun warnIfNoJoyConsConnectedSixDof() {
            if (sixDofHandMode != SixDofHandMode.MEDIAPIPE_JOYCONS) return
            val pads = GamepadManager.connectedGamepads()
            val hasJoyCon = pads.any { it.lowercase().contains("joy-con") || it.lowercase().contains("joycon") }
            if (!hasJoyCon) {
                showStatus("6DoF+Manos+Joycons: ningún Joy-Con emparejado todavía — rotación en reposo hasta emparejar uno")
            }
        }
    
    
        // ═══════════════════════ MENÚ HUB (standalone) ═══════════════════════
    
        private fun startHubRequestingPermission() {
            if (streamActive || hubModeActive) return
            pendingCameraAction = { ensureArCoreInstalledThenStartHub() }
            when {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED -> ensureArCoreInstalledThenStartHub()
                else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    
        private fun ensureArCoreInstalledThenStartHub() {
            try {
                val status = ArCoreApk.getInstance().requestInstall(this, !arCoreInstallRequested)
                when (status) {
                    ArCoreApk.InstallStatus.INSTALLED -> startHubInternal()
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        arCoreInstallRequested = true
                        pendingHubStartAfterArCoreInstall = true
                        showStatus("Instalando/actualizando Google Play Services para RA…")
                    }
                    else -> showStatus("Menú Hub: estado de instalación de ARCore inesperado ($status)")
                }
            } catch (e: UnavailableDeviceNotCompatibleException) {
                showStatus("Este dispositivo no es compatible con ARCore — el Menú Hub requiere 6DoF")
            } catch (e: UnavailableUserDeclinedInstallationException) {
                showStatus("Instalación de ARCore cancelada — no se pudo iniciar el Menú Hub")
            } catch (e: Exception) {
                showStatus("Error preparando Menú Hub: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    
        private fun startHubInternal() {
            if (hubModeActive) return
    
            // streamGLSurfaceView vive dentro de streamPanel, que arranca GONE.
            // Mientras esté GONE, Android nunca crea su Surface real, así que
            // glInputSurface puede seguir siendo null la primera vez. Por eso
            // mostramos el panel YA (para forzar la creación de la Surface) y,
            // si aún no está lista, reintentamos en cuanto llegue el callback
            // onSurfaceReady() (ver setupStreamRenderer()).
            connectPanel.visibility           = View.GONE
            connectBackgroundImage.visibility = View.GONE
            tutorialButton.visibility         = View.GONE
            hubMenuButton.visibility          = View.GONE
            sbsVideoButton.visibility         = View.GONE // ── SBS VIDEO
    
            streamPanel.visibility          = View.VISIBLE
            streamLoadingOverlay.visibility = View.VISIBLE
    
            qualityToggleButton.visibility = View.GONE
            streamQualityPanel.visibility  = View.GONE
            qualityPanelVisible = false
    
            lensToggleButton.visibility = View.VISIBLE
            streamLensPanel.visibility  = View.GONE
            lensPanelVisible = false
    
            // ── SBS VIDEO + PANTALLA COMPLETA: no aplica al Menú Hub
            fullscreenVideoButton.visibility = View.GONE
            envModeVideoButton.visibility = View.GONE // ── SBS VIDEO + ENTORNO CON GIRO: no aplica al Menú Hub
            videoDistRow.visibility = View.GONE // ── SBS VIDEO + DISTANCIA: no aplica al Menú Hub
    
            closeStreamButton.visibility = View.VISIBLE
            recenterHmdButton.visibility = View.VISIBLE
            streamControlsVisible = true
    
            // Con el fix en StereoGLRenderer, la Surface de video se crea una
            // sola vez y no se recrea al mostrar/ocultar streamPanel, así que
            // alcanza con chequear isValid(): si ya existe, sigue siendo la
            // misma para siempre en esta sesión de la app.
            val surface = glInputSurface
            if (surface == null || !surface.isValid) {
                pendingHubStartAfterSurface = true
                streamStatusOverlayText.text = "Preparando superficie de vídeo…"
                return
            }
            pendingHubStartAfterSurface = false
    
            hubModeActive = true
            hubWindowController = HubWindowController()
            lastHubGrabbedState = false
            glRenderer.ensureBufferSize(1280, 720)
    
            streamStatusOverlayText.text = "Iniciando cámara y ARCore…"
    
            glRenderer.setWindowVisible(true)
            hubBrowser?.stop()
            hubBrowser = null
            hubBrowser = HubBrowserView(this, rootLayout) { bmp, release -> glRenderer.updateWindowBitmap(bmp, release) }
            hubBrowser?.start("https://www.google.com")
    
            // Conecta el gesto de "apuntar con el dedo índice" dentro del área
            // de la ventana con taps/drags REALES sobre el WebView. Sin esto,
            // el marco se puede mover/redimensionar pero nunca se puede hacer
            // click ni escribir dentro de la página (p.ej. el buscador de Google).
            hubPointerController = HubPointerController(
                window = hubWindowController,
                onTouchDown = { u, v -> hubBrowser?.touchDown(u, v) },
                onTouchMove = { u, v -> hubBrowser?.touchMove(u, v) },
                onTouchUp   = { u, v -> hubBrowser?.touchUp(u, v) }
            )
    
            sixDofTracker?.stop()
            sixDofTracker = SixDofTracker(
                context = this,
                onPose  = { /* el Menú Hub no reenvía pose a ningún PC */ },
                onError = { msg ->
                    runOnUiThread {
                        showStatus(msg)
                        val esAvisoInformativo = msg.contains("sin señal") || msg.contains("perdió el tracking")
                        if (!esAvisoInformativo && hubModeActive) stopHubMode()
                    }
                },
                handMode = SixDofHandMode.MEDIAPIPE,
                onHands  = { left, right -> handleHubHands(left, right) },
                onTrackingStarted = {
                    runOnUiThread { streamLoadingOverlay.visibility = View.GONE }
                },
                onCameraFrame = { bmp -> drawHubCameraFrame(bmp) }
            )
            sixDofTracker?.start()
    
            showStatus("Menú Hub iniciado: cámara + 6DoF + manos")
        }
    
        private fun stopHubMode() {
            if (!hubModeActive && !pendingHubStartAfterSurface) return
            hubModeActive = false
            pendingHubStartAfterSurface = false
    
            sixDofTracker?.stop()
            sixDofTracker = null
            glRenderer.setWindowVisible(false)
    
            // Libera cualquier touch pendiente sobre el WebView (evita dejar
            // un ACTION_DOWN sin su ACTION_UP si se cierra el Hub a mitad de gesto).
            if (::hubPointerController.isInitialized) {
                hubPointerController.update(
                    HandPose(-0.35f, 0.1f, -0.5f, false),
                    HandPose( 0.35f, 0.1f, -0.5f, false)
                )
            }
            screenTouchTracking = false   // evita quedar "pegado" tocando si se cierra a mitad de gesto físico
            hubBrowser?.stop()
            hubBrowser = null
    
            streamPanel.visibility          = View.GONE
            streamLoadingOverlay.visibility = View.GONE
            streamQualityPanel.visibility   = View.GONE
            qualityToggleButton.visibility  = View.VISIBLE
            qualityPanelVisible = false
            streamLensPanel.visibility      = View.GONE
            lensToggleButton.visibility     = View.VISIBLE
            lensPanelVisible = false
    
            connectPanel.visibility           = View.VISIBLE
            connectBackgroundImage.visibility = View.VISIBLE
            tutorialButton.visibility         = View.VISIBLE
            hubMenuButton.visibility          = View.VISIBLE
            sbsVideoButton.visibility         = View.VISIBLE // ── SBS VIDEO
    
            showStatus("Menú Hub detenido")
        }
    
        /** Se llama desde el hilo de procesamiento de manos de SixDofTracker
         *  (no es el hilo de UI) — solo hace matemática simple + volatile writes,
         *  igual que ya se hace con vrSender?.updateHands() en el resto del código. */
        private fun handleHubHands(left: HandPose, right: HandPose) {
            if (!hubModeActive || !::hubWindowController.isInitialized) return
            val tracker = sixDofTracker ?: return
            val (camPos, camQuat) = tracker.currentCameraPose()
    
            val ctrl = hubWindowController
            ctrl.update(left, right, camPos, camQuat)
    
            glRenderer.setWindowVisible(ctrl.visible)
            if (ctrl.visible) {
                glRenderer.setWindowScreenRect(ctrl.screenX, ctrl.screenY, ctrl.screenHalfW, ctrl.screenHalfH, ctrl.screenParallax)
            }
    
            val grabbed = ctrl.isGrabbed()
            if (grabbed != lastHubGrabbedState) {
                lastHubGrabbedState = grabbed
                hubBrowser?.grabbedIndicator = grabbed
            }
    
            // hubPointerController.update() puede terminar llamando a
            // wv.dispatchTouchEvent() dentro de HubBrowserView — eso SOLO puede
            // pasar en el hilo de UI. Este método corre en el hilo de manos, así
            // que hay que saltar al hilo principal antes de tocar el WebView.
            if (::hubPointerController.isInitialized) {
                runOnUiThread {
                    if (hubModeActive) hubPointerController.update(left, right)
                }
            }
        }
    
        /** Dibuja el frame de cámara dos veces lado a lado (izq=der) en el Surface
         *  que ya alimenta a StereoGLRenderer, para reusar el mismo pipeline SBS +
         *  distorsión de lente que usa el stream de SteamVR. */
        private fun drawHubCameraFrame(bmp: Bitmap) {
            val surface = glInputSurface ?: return
            if (!surface.isValid) return
            try {
                val canvas: Canvas = surface.lockCanvas(null)
                val halfW = canvas.width / 2
                canvas.drawBitmap(bmp, null, android.graphics.Rect(0, 0, halfW, canvas.height), null)
                canvas.drawBitmap(bmp, null, android.graphics.Rect(halfW, 0, canvas.width, canvas.height), null)
                surface.unlockCanvasAndPost(canvas)
            } catch (e: Exception) {
                android.util.Log.w("HubMode", "No se pudo dibujar frame de cámara: ${e.message}")
            }
        }
    
        private fun buildHubWindowBitmap(grabbed: Boolean): Bitmap {
            val w = 640; val h = 400
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(0xE6141420.toInt())
    
            val borderPaint = Paint().apply {
                color = if (grabbed) 0xFF6366F1.toInt() else 0x806366F1.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 6f
            }
            canvas.drawRect(3f, 3f, w - 3f, h - 3f, borderPaint)
    
            val titlePaint = Paint().apply {
                color = 0xFFF5F5F7.toInt()
                textSize = 42f
                isFakeBoldText = true
                isAntiAlias = true
            }
            canvas.drawText("🥽 Menú Hub", 32f, 70f, titlePaint)
    
            val bodyPaint = Paint().apply {
                color = 0xFFA1A1AA.toInt()
                textSize = 28f
                isAntiAlias = true
            }
            canvas.drawText("Cámara + 6DoF + manos", 32f, 130f, bodyPaint)
            canvas.drawText(
                if (grabbed) "Sujetando ventana ✋" else "Pellizca (👌) cerca de la ventana para moverla",
                32f, 175f, bodyPaint
            )
            canvas.drawText("Cierra con el botón ✕ de arriba", 32f, 220f, bodyPaint)
    
            return bmp
        }
    
    
        // ═══════════════════════ SBS VIDEO (reproductor local) ═══════════════════════
    
        private fun setupSbsVideo() {
            sbsVideoButton.setOnClickListener {
                if (streamActive || hubModeActive || isPlayingLocalVideo) return@setOnClickListener
                pickSbsVideoLauncher.launch("video/*")
            }
    
            videoPlayPauseButton.setOnClickListener {
                val player = localVideoPlayer ?: return@setOnClickListener
                if (player.isPlaying) {
                    player.pause()
                    videoPlayPauseButton.text = getString(R.string.str_play)
                } else {
                    player.start()
                    videoPlayPauseButton.text = getString(R.string.str_pause)
                }
            }
    
            // ← FIX 3: carga la imagen de fondo que se ve detrás de la
            // "pantalla" del video en modo ventana (world-locked). Se hace una
            // sola vez acá; el renderer la sube a la GPU en cuanto esté listo.
            loadSbsBackgroundBitmap()
        }
    
        /**
         * ← FIX 3: lee la imagen de fondo desde assets y se la pasa al
         * StereoGLRenderer para que se vea COMPLETA detrás del rectángulo del
         * video SBS mientras está en modo ventana (world-locked, no pantalla
         * completa).
         *
         * IMPORTANTE: como el archivo original tiene espacios y comas en el
         * nombre ("Aug 14, 2026, 05_50_56 PM.png"), no sirve como recurso
         * res/drawable (esos nombres deben ser snake_case sin espacios ni
         * mayúsculas). Copia el archivo a:
         *   app/src/main/assets/sbs_background.png
         * (podés renombrarlo con cualquier nombre válido, ajustando el string
         * de abajo).
         */
        private fun loadSbsBackgroundBitmap() {
            try {
                assets.open("sbs_background.png").use { input ->
                    val bmp = BitmapFactory.decodeStream(input)
                    if (bmp != null) {
                        glRenderer.setBackgroundBitmap(bmp)
                    } else {
                        android.util.Log.w("SbsVideo", "No se pudo decodificar sbs_background.png")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("SbsVideo", "No se pudo cargar el fondo del video SBS: ${e.message}")
            }
        }
    
        /**
         * Reproduce un video local (side-by-side) reutilizando el mismo Surface
         * OES que StereoGLRenderer ya expone para el stream de SteamVR
         * (glInputSurface). Como el fragment shader ya divide la imagen por
         * mitades para cada ojo, no hace falta tocar ni el shader ni el
         * renderer: cualquier cosa que se dibuje en esa Surface sale
         * automáticamente en modo estéreo con distorsión de lente aplicada.
         *
         * Además activa el "world lock" del video (glRenderer.setVideoWorldLocked)
         * y arranca GyroVideoWindowTracker, así el rectángulo del video se
         * desplaza dentro del campo de visión al girar la cabeza, simulando
         * una pantalla fija en la habitación.
         *
         * NUEVO (fondo con giroscopio): además del offset del rectángulo del
         * video, GyroVideoWindowTracker también reporta el yaw/pitch crudo de
         * la cabeza vía onHeadAnglesChanged, que se reenvía a
         * glRenderer.setHeadAngles() para que el FONDO (uBgTex) también se
         * mueva al girar la cabeza — sea con el parallax sutil del modo
         * ventana normal, o con la panorámica 360° completa del modo "Ver
         * entorno con giro" (ver envModeVideoButton / setVideoEnvMode()).
         *
         * NUEVO (distancia): además arranca siempre la "distancia" de la
         * pantalla del video en 1.00× (StereoGLRenderer.setVideoWindowScale()),
         * ajustable luego con videoDistDownButton/videoDistUpButton del panel
         * de Lentes.
         */
        private fun playLocalSbsVideo(uri: Uri) {
            if (streamActive || hubModeActive) {
                showStatus("Cierra el stream/Menú Hub activo antes de reproducir un video SBS")
                return
            }
    
            // Con el fix en StereoGLRenderer, la Surface de video se crea una
            // sola vez y no se recrea al mostrar/ocultar streamPanel, así que
            // alcanza con chequear isValid(): si ya existe, sigue siendo la
            // misma para siempre en esta sesión de la app.
            val surface = glInputSurface
            if (surface == null || !surface.isValid) {
                // La Surface de streamGLSurfaceView todavía no existe (puede
                // pasar si streamPanel nunca se mostró). La mostramos para
                // forzar su creación y reintentamos en el callback
                // onSurfaceReady() (ver setupStreamRenderer()).
                pendingSbsVideoUri = uri
                streamPanel.visibility          = View.VISIBLE
                streamLoadingOverlay.visibility = View.VISIBLE
                streamStatusOverlayText.text    = "Preparando superficie de vídeo…"
                connectPanel.visibility           = View.GONE
                connectBackgroundImage.visibility = View.GONE
                tutorialButton.visibility         = View.GONE
                hubMenuButton.visibility          = View.GONE
                sbsVideoButton.visibility         = View.GONE
                return
            }
    
            stopLocalVideoIfNeeded()
            glRenderer.setMonoSource(true)
            glRenderer.setVideoWorldLocked(true)   // ── SBS VIDEO + GIROSCOPIO
            videoHeadTracker = GyroVideoWindowTracker(
                context = this,
                onOffsetChanged = { x, y -> glRenderer.setVideoWindowOffset(x, y) },
                // FIX: sin esto uHeadYaw/uHeadPitch nunca se actualizaban y el
                // fondo del modo ventana quedaba congelado aunque gires la
                // cabeza — el rectángulo del video sí se movía (vía
                // setVideoWindowOffset) pero el fondo detrás no.
                //
                // FIX 2 (pitch invertido): el pitch crudo que reporta el
                // tracker tiene el signo correcto para mover el rectángulo del
                // video (targetY en GyroVideoWindowTracker, sin negar), pero
                // el eje V de la textura de fondo crece hacia ABAJO mientras
                // que "mirar arriba" debe mostrar la parte de ARRIBA del
                // panorama — signo opuesto. Por eso acá se niega solo el pitch
                // (el yaw del fondo sí coincide y no hace falta tocarlo). Si
                // алгу día se ve al revés otra vez, es esta línea la que hay
                // que tocar, no el shader.
                onHeadAnglesChanged = { yaw, pitch, roll -> glRenderer.setHeadAngles(yaw, -pitch, -roll) }
            )
            videoHeadTracker?.start()
    
            connectPanel.visibility           = View.GONE
            connectBackgroundImage.visibility = View.GONE
            tutorialButton.visibility         = View.GONE
            hubMenuButton.visibility          = View.GONE
            sbsVideoButton.visibility         = View.GONE
    
            streamPanel.visibility          = View.VISIBLE
            streamLoadingOverlay.visibility = View.VISIBLE
            streamStatusOverlayText.text    = "Cargando video…"
    
            qualityToggleButton.visibility = View.GONE
            streamQualityPanel.visibility  = View.GONE
            qualityPanelVisible = false
    
            lensToggleButton.visibility = View.VISIBLE
            streamLensPanel.visibility  = View.GONE
            lensPanelVisible = false
    
            // ── SBS VIDEO + PANTALLA COMPLETA: el botón solo tiene sentido
            // mientras se reproduce un video local; arranca siempre en modo
            // ventana (world-locked) para no sorprender al usuario.
            fullscreenVideoButton.visibility = View.VISIBLE
            glRenderer.setVideoFullscreen(false)
            updateFullscreenVideoButtonLabel()
    
            // ── SBS VIDEO + ENTORNO CON GIRO: mismo patrón que el botón de
            // arriba, arranca siempre apagado (fondo en modo parallax normal,
            // no panorámica 360°) para no sorprender al usuario.
            envModeVideoButton.visibility = View.VISIBLE
            glRenderer.setVideoEnvMode(false)
            updateEnvModeVideoButtonLabel()
    
            // ── SBS VIDEO + DISTANCIA: arranca siempre en 1.00× (tamaño por
            // defecto) para no sorprender al usuario.
            videoDistRow.visibility = View.VISIBLE
            glRenderer.setVideoWindowScale(1.0f)
            updateVideoDistLabel()
    
            closeStreamButton.visibility  = View.VISIBLE
            recenterHmdButton.visibility  = View.VISIBLE // ── SBS VIDEO + GIROSCOPIO: recentra la pantalla del video frente tuyo
            videoPlayPauseButton.visibility = View.VISIBLE
            videoPlayPauseButton.text = getString(R.string.str_pause)
            streamControlsVisible = true
    
            isPlayingLocalVideo = true
    
            localVideoPlayer = MediaPlayer().apply {
                try {
                    setDataSource(this@MainActivity, uri)
                    setSurface(surface)
                    isLooping = true
                    setOnPreparedListener {
                        glRenderer.setVideoAspectRatio(it.videoWidth, it.videoHeight)
                        streamLoadingOverlay.visibility = View.GONE
                        it.start()
                    }
                    setOnErrorListener { _, what, extra ->
                        showStatus("Error reproduciendo video SBS (código $what/$extra)")
                        stopLocalVideoAndReturnToMenu()
                        true
                    }
                    prepareAsync()
                } catch (e: Exception) {
                    showStatus("No se pudo abrir el video: ${e.message}")
                    stopLocalVideoAndReturnToMenu()
                }
            }
    
            showStatus("Reproduciendo video SBS")
        }
    
        private fun stopLocalVideoIfNeeded() {
            if (!isPlayingLocalVideo && localVideoPlayer == null) return
            try {
                localVideoPlayer?.stop()
            } catch (_: Exception) { /* puede lanzar si nunca llegó a prepararse */ }
            localVideoPlayer?.release()
            localVideoPlayer = null
            isPlayingLocalVideo = false
            glRenderer.setMonoSource(false)   // ← LÍNEA NUEVA
            glRenderer.setVideoWorldLocked(false)   // ── SBS VIDEO + GIROSCOPIO (también resetea videoFullscreen/videoWindowScale a default)
            videoHeadTracker?.stop()
            videoHeadTracker = null
        }
    
        /** Detiene el video local y vuelve al menú principal (mismo layout final
         *  que stopStream(), pero sin tocar vrStreamReceiver/vrAudioReceiver). */
        private fun stopLocalVideoAndReturnToMenu() {
            stopLocalVideoIfNeeded()
            runOnUiThread {
                streamPanel.visibility            = View.GONE
                streamLoadingOverlay.visibility   = View.GONE
                streamQualityPanel.visibility     = View.GONE
                qualityToggleButton.visibility    = View.VISIBLE
                qualityPanelVisible = false
                streamLensPanel.visibility        = View.GONE
                lensToggleButton.visibility       = View.VISIBLE
                lensPanelVisible = false
                videoPlayPauseButton.visibility   = View.GONE
                fullscreenVideoButton.visibility  = View.GONE // ── SBS VIDEO + PANTALLA COMPLETA
                envModeVideoButton.visibility     = View.GONE // ── SBS VIDEO + ENTORNO CON GIRO
                videoDistRow.visibility           = View.GONE // ── SBS VIDEO + DISTANCIA
                closeStreamButton.visibility      = View.VISIBLE
                recenterHmdButton.visibility      = View.VISIBLE
                streamControlsVisible = true
    
                connectPanel.visibility           = View.VISIBLE
                connectBackgroundImage.visibility = View.VISIBLE
                tutorialButton.visibility         = View.VISIBLE
                hubMenuButton.visibility          = View.VISIBLE
                sbsVideoButton.visibility         = View.VISIBLE
            }
            // ← FIX 1: se QUITÓ showControlPanel() de acá. Este flujo (video
            // local) nunca debe mostrar el panel de HMD/Manos/6DoF — ese panel
            // es exclusivo de la sesión conectada a una PC. Antes, como
            // playLocalSbsVideo() llamaba a hideControlPanel(), acá se
            // "restauraba" mostrándolo de nuevo aunque nunca hubiera estado
            // visible, causando el bug reportado.
        }
    
    
    
        private fun startEyeTrackingRequestingPermission() {
            pendingCameraAction = { startEyeTrackingInternal() }
            when {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED -> startEyeTrackingInternal()
                else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    
        private fun startEyeTrackingInternal() {
            if (connectionType == ConnectionType.USB_ADB) {
                switchEyeTracking.isChecked = false
                showStatus("Eye Tracking (OSC/UDP) no está disponible en modo USB/ADB — usa WiFi o cable RNDIS")
                return
            }
            if (connectedPcIp.isEmpty()) {
                switchEyeTracking.isChecked = false
                showStatus("Conecta primero con una PC para usar Eye Tracking")
                return
            }
            faceEyeTracker?.stop()
            faceEyeTracker = FaceEyeTracker(
                context = this,
                pcIp    = connectedPcIp,
                onError = { msg ->
                    runOnUiThread {
                        eyeTrackingStatusText.text = msg
                        showStatus(msg)
                        switchEyeTracking.isChecked = false
                    }
                },
                onReady = {
                    runOnUiThread {
                        eyeTrackingStatusText.text = "Eye tracking activo → VRChat OSC (puerto 9000)"
                    }
                }
            )
            faceEyeTracker?.start(this)
            showStatus("Eye Tracking VRChat iniciado (cámara frontal)")
        }
    
        private fun stopEyeTracking() {
            faceEyeTracker?.stop()
            faceEyeTracker = null
            eyeTrackingStatusText.text = "Cámara frontal · OSC → VRChat puerto 9000"
        }
    
    
    
        private fun startStream() {
            if (connectedPcIp.isEmpty()) { showStatus("No hay PC conectada"); return }
            stopStreamInternal()
            streamActive = true
    
            applyQuality()
    
            vrAudioReceiver?.stop()
            vrAudioReceiver = VrAudioReceiver(connectedPcIp) { msg ->
                runOnUiThread { showStatus(msg) }
            }
            vrAudioReceiver?.start()
    
            cameraOverlayPanel.visibility = View.GONE
            switchCameraOverlay.isEnabled = false
    
            streamPanel.visibility          = View.VISIBLE
            streamLoadingOverlay.visibility = View.VISIBLE
            streamStatusOverlayText.text    = "Conectando al stream…"
    
            qualityToggleButton.visibility = View.VISIBLE
            streamQualityPanel.visibility  = View.GONE
            qualityPanelVisible = false
    
            lensToggleButton.visibility = View.VISIBLE
            streamLensPanel.visibility  = View.GONE
            lensPanelVisible = false
    
            // ── SBS VIDEO + PANTALLA COMPLETA: no aplica al stream de SteamVR
            fullscreenVideoButton.visibility = View.GONE
            envModeVideoButton.visibility = View.GONE // ── SBS VIDEO + ENTORNO CON GIRO: no aplica
            videoDistRow.visibility = View.GONE // ── SBS VIDEO + DISTANCIA: no aplica al stream de SteamVR
    
            closeStreamButton.visibility  = View.VISIBLE
            recenterHmdButton.visibility  = View.VISIBLE
            videoPlayPauseButton.visibility = View.GONE // ── SBS VIDEO: no aplica al stream de SteamVR
            streamControlsVisible = true
    
            hideControlPanel()
    
            val surface = glInputSurface
            if (surface != null && surface.isValid) {
                launchStreamReceiver(surface)
            }
        }
    
        private fun launchStreamReceiver(surface: android.view.Surface) {
            if (!surface.isValid) {
                showStatus("Surface no válida, reintenta")
                streamPanel.visibility = View.GONE
                streamActive = false
                return
            }
            vrStreamReceiver = VrStreamReceiver(
                pcIp    = connectedPcIp,
                surface = surface,
                onStatus = { msg ->
                    runOnUiThread {
                        streamStatusOverlayText.text = msg
                        when {
                            msg.contains("✓") ->
                                streamLoadingOverlay.visibility = View.GONE
                            msg.contains("reconectando") || msg.contains("error", ignoreCase = true) ->
                                streamLoadingOverlay.visibility = View.VISIBLE
                        }
                    }
                }
            )
            vrStreamReceiver?.start()
        }
    
        private fun stopStreamInternal() {
            vrStreamReceiver?.stop()
            vrStreamReceiver = null
            stopAudioInternal()
        }
    
        private fun stopAudioInternal() {
            vrAudioReceiver?.stop()
            vrAudioReceiver = null
        }
    
        private fun stopStream() {
            stopStreamInternal()
            streamActive = false
            runOnUiThread {
                streamPanel.visibility          = View.GONE
                streamLoadingOverlay.visibility = View.GONE
                streamQualityPanel.visibility   = View.GONE
                qualityToggleButton.visibility  = View.VISIBLE
                qualityPanelVisible = false
                streamLensPanel.visibility      = View.GONE
                lensToggleButton.visibility     = View.VISIBLE
                lensPanelVisible = false
    
                closeStreamButton.visibility    = View.VISIBLE
                recenterHmdButton.visibility    = View.VISIBLE
                streamControlsVisible = true
            }
    
            switchCameraOverlay.isEnabled = switchHand.isChecked
            if (switchCameraOverlay.isChecked && switchHand.isChecked
                && currentTrackingMode != TrackingMode.NONE) {
                cameraOverlayPanel.visibility = View.VISIBLE
            }
    
            showControlPanel()
        }
    
    
    
        private fun setupQualityControls() {
            qualityToggleButton.setOnClickListener {
                if (qualityPanelVisible) hideQualityPanel() else showQualityPanel()
            }
            resDownButton.setOnClickListener {
                if (resIndex > 0) { resIndex--; applyQuality() }
            }
            resUpButton.setOnClickListener {
                if (resIndex < RES_PRESETS.size - 1) { resIndex++; applyQuality() }
            }
            brDownButton.setOnClickListener {
                bitrateMbps = (bitrateMbps - BITRATE_STEP).coerceAtLeast(BITRATE_MIN)
                applyQuality()
            }
            brUpButton.setOnClickListener {
                bitrateMbps = (bitrateMbps + BITRATE_STEP).coerceAtMost(BITRATE_MAX)
                applyQuality()
            }
            fpsDownButton.setOnClickListener {
                if (fpsIndex > 0) { fpsIndex--; applyQuality() }
            }
            fpsUpButton.setOnClickListener {
                if (fpsIndex < FPS_PRESETS.size - 1) { fpsIndex++; applyQuality() }
            }
            updateQualityLabels()
        }
    
        private fun updateQualityLabels() {
            resLabelText.text = RES_PRESETS[resIndex].label
            brLabelText.text  = "%.1f Mbps".format(bitrateMbps)
            fpsLabelText.text = "${FPS_PRESETS[fpsIndex]} FPS"
        }
    
        private fun applyQuality() {
            updateQualityLabels()
            val preset = RES_PRESETS[resIndex]
            val fps = FPS_PRESETS[fpsIndex]
            val bitrate = (bitrateMbps * 1_000_000).toInt()
            if (connectedPcIp.isNotEmpty()) {
                if (connectionType == ConnectionType.USB_ADB) {
                    (vrSender as? UsbTrackingSender)?.sendQualityChange(preset.w, preset.h, bitrate, fps)
                } else {
                    StreamQualityController.sendQuality(connectedPcIp, preset.w, preset.h, bitrate, fps)
                }
                showStatus("Calidad: ${preset.label} · ${"%.1f".format(bitrateMbps)} Mbps · ${fps}fps")
            }
        }
    
        private fun showQualityPanel() {
            qualityToggleButton.visibility = View.VISIBLE
            if (qualityPanelVisible) return
            qualityPanelVisible = true
            streamQualityPanel.visibility = View.VISIBLE
            streamQualityPanel.alpha = 0f
            streamQualityPanel.translationY = 40f
            streamQualityPanel.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(200)
                .start()
        }
    
        private fun hideQualityPanel() {
            qualityPanelVisible = false
            streamQualityPanel.animate()
                .alpha(0f)
                .translationY(40f)
                .setDuration(200)
                .withEndAction { streamQualityPanel.visibility = View.GONE }
                .start()
            qualityToggleButton.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    qualityToggleButton.visibility = View.GONE
                    qualityToggleButton.alpha = 1f
                }
                .start()
        }
    
    
    
        private fun setupLensControls() {
            val prefs = getSharedPreferences("vr_lens", MODE_PRIVATE)
    
            lensToggleButton.setOnClickListener {
                if (lensPanelVisible) hideLensPanel() else showLensPanel()
            }
    
            // ── SBS VIDEO + PANTALLA COMPLETA: solo tiene efecto visual mientras
            // se reproduce un video local (uVidLocked==1 en el shader). El botón
            // solo se muestra/oculta desde playLocalSbsVideo() /
            // stopLocalVideoAndReturnToMenu(), así que acá solo hace falta el
            // toggle y refrescar su label.
            fullscreenVideoButton.setOnClickListener {
                glRenderer.setVideoFullscreen(!glRenderer.videoFullscreen)
                updateFullscreenVideoButtonLabel()
                showStatus(
                    if (glRenderer.videoFullscreen)
                        "Pantalla completa activada — el video llena todo el lente ✓"
                    else
                        "Pantalla completa desactivada — volviendo a ventana fija con giroscopio"
                )
            }
    
            // ── SBS VIDEO + ENTORNO CON GIRO: mismo patrón que el botón de
            // arriba. Solo tiene efecto visual mientras el video está en modo
            // ventana (no en pantalla completa) — ver bloque 4b del fragment
            // shader, que directamente ignora uVidEnvMode cuando
            // uVidFullscreen>0.5.
            envModeVideoButton.setOnClickListener {
                glRenderer.setVideoEnvMode(!glRenderer.videoEnvMode)
                updateEnvModeVideoButtonLabel()
                showStatus(
                    if (glRenderer.videoEnvMode)
                        "Entorno con giro activado — el fondo te rodea en 360° ✓"
                    else
                        "Entorno con giro desactivado — volviendo a fondo fijo con parallax"
                )
            }
    
            // ── SBS VIDEO + DISTANCIA: acerca/aleja la "pantalla" del video
            // world-locked escalando videoHalfW/videoHalfH en el renderer. Solo
            // tiene efecto visual mientras el video está en modo ventana (no
            // aplica en pantalla completa, donde no hay rectángulo).
            videoDistDownButton.setOnClickListener {
                glRenderer.setVideoWindowScale(glRenderer.videoWindowScale - VIDEO_DIST_STEP)
                updateVideoDistLabel()
            }
            videoDistUpButton.setOnClickListener {
                glRenderer.setVideoWindowScale(glRenderer.videoWindowScale + VIDEO_DIST_STEP)
                updateVideoDistLabel()
            }
    
            distDownButton.setOnClickListener {
                glRenderer.distortionK = (glRenderer.distortionK - DIST_STEP).coerceIn(DIST_MIN, DIST_MAX)
                updateLensLabels()
                prefs.edit().putFloat("distortionK", glRenderer.distortionK).apply()
            }
            distUpButton.setOnClickListener {
                glRenderer.distortionK = (glRenderer.distortionK + DIST_STEP).coerceIn(DIST_MIN, DIST_MAX)
                updateLensLabels()
                prefs.edit().putFloat("distortionK", glRenderer.distortionK).apply()
            }
            sepDownButton.setOnClickListener {
                glRenderer.lensSeparation = (glRenderer.lensSeparation - SEP_STEP).coerceIn(SEP_MIN, SEP_MAX)
                updateLensLabels()
                prefs.edit().putFloat("lensSeparation", glRenderer.lensSeparation).apply()
            }
            sepUpButton.setOnClickListener {
                glRenderer.lensSeparation = (glRenderer.lensSeparation + SEP_STEP).coerceIn(SEP_MIN, SEP_MAX)
                updateLensLabels()
                prefs.edit().putFloat("lensSeparation", glRenderer.lensSeparation).apply()
            }
            zoomDownButton.setOnClickListener {
                glRenderer.lensZoom = (glRenderer.lensZoom - ZOOM_STEP).coerceIn(ZOOM_MIN, ZOOM_MAX)
                updateLensLabels()
                prefs.edit().putFloat("lensZoom", glRenderer.lensZoom).apply()
            }
            zoomUpButton.setOnClickListener {
                glRenderer.lensZoom = (glRenderer.lensZoom + ZOOM_STEP).coerceIn(ZOOM_MIN, ZOOM_MAX)
                updateLensLabels()
                prefs.edit().putFloat("lensZoom", glRenderer.lensZoom).apply()
            }
            updateLensLabels()
            updateFullscreenVideoButtonLabel()
            updateEnvModeVideoButtonLabel()
            updateVideoDistLabel()
        }
    
        private fun updateLensLabels() {
            distLabelText.text = "Distorsión: %.2f".format(glRenderer.distortionK)
            sepLabelText.text  = "Separación: %.2f".format(glRenderer.lensSeparation)
            zoomLabelText.text = "🔎 Lupa: %.2f×".format(glRenderer.lensZoom)
        }
    
        // ── SBS VIDEO + PANTALLA COMPLETA
        private fun updateFullscreenVideoButtonLabel() {
            fullscreenVideoButton.text =
                if (glRenderer.videoFullscreen) "🖥 Pantalla completa: ON"
                else "🖥 Pantalla completa: OFF"
        }
    
        // ── SBS VIDEO + ENTORNO CON GIRO
        private fun updateEnvModeVideoButtonLabel() {
            envModeVideoButton.text =
                if (glRenderer.videoEnvMode) "🌎 Entorno con giro: ON"
                else "🌎 Entorno con giro: OFF"
        }
    
        // ── SBS VIDEO + DISTANCIA
        private fun updateVideoDistLabel() {
            videoDistLabelText.text = "📏 Distancia: %.2f×".format(glRenderer.videoWindowScale)
        }
    
        private fun showLensPanel() {
            lensToggleButton.visibility = View.VISIBLE
            if (lensPanelVisible) return
            lensPanelVisible = true
            streamLensPanel.visibility = View.VISIBLE
            streamLensPanel.alpha = 0f
            streamLensPanel.translationY = 40f
            streamLensPanel.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(200)
                .start()
        }
    
        private fun hideLensPanel() {
            lensPanelVisible = false
            streamLensPanel.animate()
                .alpha(0f)
                .translationY(40f)
                .setDuration(200)
                .withEndAction { streamLensPanel.visibility = View.GONE }
                .start()
            lensToggleButton.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    lensToggleButton.visibility = View.GONE
                    lensToggleButton.alpha = 1f
                }
                .start()
        }
    
    
    
        private fun showStreamControls() {
            if (streamControlsVisible) return
            streamControlsVisible = true
    
            val buttons = if (isPlayingLocalVideo) // ── SBS VIDEO
                listOf(lensToggleButton, closeStreamButton, videoPlayPauseButton)
            else
                listOf(qualityToggleButton, lensToggleButton, closeStreamButton, recenterHmdButton)
    
            buttons.forEach { btn ->
                btn.visibility = View.VISIBLE
                btn.alpha = 0f
                btn.animate().alpha(1f).setDuration(200).start()
            }
    
            if (qualityPanelVisible) {
                streamQualityPanel.visibility = View.VISIBLE
                streamQualityPanel.alpha = 0f
                streamQualityPanel.translationY = 40f
                streamQualityPanel.animate().alpha(1f).translationY(0f).setDuration(200).start()
            }
            if (lensPanelVisible) {
                streamLensPanel.visibility = View.VISIBLE
                streamLensPanel.alpha = 0f
                streamLensPanel.translationY = 40f
                streamLensPanel.animate().alpha(1f).translationY(0f).setDuration(200).start()
            }
        }
    
        private fun hideStreamControls() {
            if (!streamControlsVisible) return
            streamControlsVisible = false
    
            if (qualityPanelVisible) {
                streamQualityPanel.animate()
                    .alpha(0f).translationY(40f).setDuration(200)
                    .withEndAction { streamQualityPanel.visibility = View.GONE }
                    .start()
            }
            if (lensPanelVisible) {
                streamLensPanel.animate()
                    .alpha(0f).translationY(40f).setDuration(200)
                    .withEndAction { streamLensPanel.visibility = View.GONE }
                    .start()
            }
    
            val buttons = if (isPlayingLocalVideo) // ── SBS VIDEO
                listOf(lensToggleButton, closeStreamButton, videoPlayPauseButton)
            else
                listOf(qualityToggleButton, lensToggleButton, closeStreamButton, recenterHmdButton)
    
            buttons.forEach { btn ->
                btn.animate()
                    .alpha(0f).setDuration(200)
                    .withEndAction { btn.visibility = View.GONE; btn.alpha = 1f }
                    .start()
            }
        }
    
    
    
        private fun startDiscovery() {
            discoveredPcs.clear()
            runOnUiThread {
                pcListLayout.removeAllViews()
                scanStatusText.text  = "🔍 Buscando PC con SteamVR…"
                scanButton.isEnabled = true
            }
            pcDiscovery?.stop()
            pcDiscovery = PcDiscovery(
                onFound = { ip, name -> runOnUiThread { addDiscoveredPc(ip, name) } },
                onError = { msg ->
                    runOnUiThread {
                        scanStatusText.text  = "Error: $msg"
                        scanButton.isEnabled = true
                    }
                }
            )
            pcDiscovery?.start()
            rootLayout.postDelayed({
                runOnUiThread {
                    scanButton.isEnabled = true
                    if (discoveredPcs.isEmpty())
                        scanStatusText.text =
                            "No se encontró ninguna PC.\nAsegúrate de que SteamVR esté abierto y en la misma red."
                }
            }, 8000)
        }
    
        private fun addDiscoveredPc(ip: String, name: String) {
            if (discoveredPcs.containsKey(ip)) return
            discoveredPcs[ip] = name
            scanStatusText.text  = "PC encontrada:"
            scanButton.isEnabled = true
            val btn = Button(this).apply {
                text = "🖥  $name\n$ip"
                textSize = 13f
                setTextColor(0xFFFFFFFF.toInt())
                backgroundTintList =
                    android.content.res.ColorStateList.valueOf(0xFF1E5631.toInt())
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 8, 0, 0)
                layoutParams = lp
                setPadding(16, 16, 16, 16)
                setOnClickListener { if (!connectingInProgress) connect(ip, ConnectionType.WIFI) }
            }
            pcListLayout.addView(btn)
            if (discoveredPcs.size == 1 && ipInput.text.isBlank()) {
                ipInput.setText(ip)
                rootLayout.postDelayed({ if (!connectingInProgress) connect(ip, ConnectionType.WIFI) }, 1200)
            }
        }
    
    
    
        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
            if (gamepadManager?.onGenericMotionEvent(event) == true) return true
            return super.dispatchGenericMotionEvent(event)
        }
    
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            val handled = when (event.action) {
                KeyEvent.ACTION_DOWN -> gamepadManager?.onKeyDown(event.keyCode, event) == true
                KeyEvent.ACTION_UP   -> gamepadManager?.onKeyUp(event.keyCode, event)   == true
                else -> false
            }
            return if (handled) true else super.dispatchKeyEvent(event)
        }
    
    
    
        private fun setupControlPanelSwipe() {
            controlPanelGestureDetector = GestureDetector(
                this,
                object : GestureDetector.SimpleOnGestureListener() {
    
                    private val density = resources.displayMetrics.density
                    private val SWIPE_THRESHOLD          = 64 * density   // ~64dp
                    private val SWIPE_VELOCITY_THRESHOLD = 900f           // px/s
    
                    override fun onFling(
                        e1: MotionEvent?, e2: MotionEvent,
                        velocityX: Float, velocityY: Float
                    ): Boolean {
                        val dy = e2.y - (e1?.y ?: e2.y)
                        val dx = e2.x - (e1?.x ?: e2.x)
    
                        val esVertical = abs(dy) > abs(dx) * 1.5f
                        if (esVertical &&
                            abs(dy) > SWIPE_THRESHOLD &&
                            abs(velocityY) > SWIPE_VELOCITY_THRESHOLD
                        ) {
                            if (streamPanel.visibility == View.VISIBLE) {
                                if (dy > 0) hideStreamControls() else showStreamControls()
                                return true
                            }
    
                            if (connectPanel.visibility != View.VISIBLE) {
                                if (dy > 0) hideControlPanel() else showControlPanel()
                                return true
                            }
                        }
                        return false
                    }
                }
            )
        }
    
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                swipeIgnoredForCurrentGesture = isInsideSwipeExclusionZone(event.rawX, event.rawY)
            }
    
            // Si el dedo está tocando físicamente la pantalla sobre la ventana
            // del Menú Hub, lo tratamos como click/scroll/escritura en la
            // página — no como swipe para mostrar/ocultar paneles.
            if (handleHubScreenTouch(event)) {
                return true
            }
    
            if (!swipeIgnoredForCurrentGesture) {
                controlPanelGestureDetector.onTouchEvent(event)
            }
            return super.dispatchTouchEvent(event)
        }
    
    
        private fun isInsideSwipeExclusionZone(rawX: Float, rawY: Float): Boolean {
            val x = rawX.toInt()
            val y = rawY.toInt()
    
            if (controlPanel.visibility == View.VISIBLE && ::controlScrollView.isInitialized) {
                controlScrollView.getGlobalVisibleRect(swipeHitRect)
                if (swipeHitRect.contains(x, y)) return true
            }
            if (streamPanel.visibility == View.VISIBLE) {
                if (qualityPanelVisible && streamQualityPanel.visibility == View.VISIBLE) {
                    streamQualityPanel.getGlobalVisibleRect(swipeHitRect)
                    if (swipeHitRect.contains(x, y)) return true
                }
                if (lensPanelVisible && streamLensPanel.visibility == View.VISIBLE) {
                    streamLensPanel.getGlobalVisibleRect(swipeHitRect)
                    if (swipeHitRect.contains(x, y)) return true
                }
            }
            return false
        }
    
        /**
         * ¿El punto de pantalla cae sobre alguno de los botones/paneles del
         * modo Hub (cerrar, recentrar, panel de lente)? Si es así, dejamos que
         * Android lo trate como un click normal y NO lo interpretamos como
         * touch sobre la página web.
         */
        private fun isInsideHubButtonZone(rawX: Float, rawY: Float): Boolean {
            val x = rawX.toInt()
            val y = rawY.toInt()
            listOf(closeStreamButton, recenterHmdButton, lensToggleButton).forEach { v ->
                if (v.visibility == View.VISIBLE) {
                    v.getGlobalVisibleRect(swipeHitRect)
                    if (swipeHitRect.contains(x, y)) return true
                }
            }
            if (lensPanelVisible && streamLensPanel.visibility == View.VISIBLE) {
                streamLensPanel.getGlobalVisibleRect(swipeHitRect)
                if (swipeHitRect.contains(x, y)) return true
            }
            return false
        }
    
        /**
         * Reenvía un touch real de la pantalla del teléfono (p.ej. el pad
         * táctil de un visor tipo Cardboard) como touch real dentro del
         * WebView del Menú Hub, cuando el dedo está sobre la ventana flotante.
         * Complementa (no reemplaza) el gesto de "apuntar con el índice" que
         * ya maneja HubPointerController vía cámara/6DoF.
         */
        private fun handleHubScreenTouch(event: MotionEvent): Boolean {
            if (!hubModeActive) return false
            if (!::streamGLSurfaceView.isInitialized || streamGLSurfaceView.visibility != View.VISIBLE) return false
            if (!::glRenderer.isInitialized) return false
    
            val loc = IntArray(2)
            streamGLSurfaceView.getLocationOnScreen(loc)
            val localX = event.rawX - loc[0]
            val localY = event.rawY - loc[1]
            val w = streamGLSurfaceView.width
            val h = streamGLSurfaceView.height
    
            return when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (isInsideHubButtonZone(event.rawX, event.rawY)) return false
                    val uv = glRenderer.screenToWindowUv(localX, localY, w, h) ?: return false
                    screenTouchTracking = true
                    hubBrowser?.touchDown(uv[0], uv[1])
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!screenTouchTracking) return false
                    val uv = glRenderer.screenToWindowUvClamped(localX, localY, w, h)
                    hubBrowser?.touchMove(uv[0], uv[1])
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!screenTouchTracking) return false
                    screenTouchTracking = false
                    val uv = glRenderer.screenToWindowUvClamped(localX, localY, w, h)
                    hubBrowser?.touchUp(uv[0], uv[1])
                    true
                }
                else -> false
            }
        }
    
        private fun hideControlPanel() {
            if (!controlPanelVisible) return
            controlPanelVisible = false
            controlPanel.animate()
                .translationY(controlPanel.height.toFloat())
                .setDuration(250)
                .withEndAction {
                    controlPanel.visibility = View.GONE
                    window.decorView.requestFocus()
                }.start()
        }
    
        private fun showControlPanel() {
            if (controlPanelVisible) return
            controlPanelVisible = true
            controlPanel.visibility = View.VISIBLE
            controlPanel.translationY = controlPanel.height.toFloat()
            controlPanel.animate().translationY(0f).setDuration(250).start()
        }
    
    
    
        private fun setupGamepad() {
            gamepadManager = GamepadManager(
                onLeft  = { state -> vrSender?.updateLeftGamepad(state)  },
                onRight = { state -> vrSender?.updateRightGamepad(state) }
            )
    
            joyConHid = JoyConHidManager(this) { side, state ->
                gamepadManager?.applyJoyConState(side, state)
                joyconDebugCounter++
                if (joyconDebugCounter % 6 == 0) {
                    runOnUiThread { refreshGamepadStatus(side, state) }
                }
            }
    
            connectPairedJoyCons()
            refreshGamepadStatus()
        }
    
    
        private fun connectPairedJoyCons() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                showStatus("Joy-Con: giroscopio no soportado en esta versión de Android (necesita Android 10+)")
                return
            }
    
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                    bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    return
                }
            }
            val hid = joyConHid ?: return
            val paired = hid.pairedJoyCons()
            if (paired.isEmpty()) {
                showStatus("Joy-Con: ninguno emparejado (Ajustes > Bluetooth)")
                return
            }
            paired.forEach { device: BluetoothDevice -> hid.connect(device) }
            showStatus("Joy-Con: conectando ${paired.size} dispositivo(s)…")
        }
    
        private val bluetoothPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) connectPairedJoyCons()
                else showStatus("Permiso de Bluetooth denegado — el Joy-Con no podrá conectarse")
            }
    
        private fun refreshGamepadStatus(
            lastSide: JoyConHidManager.Side? = null,
            lastState: JoyConHidManager.JoyConState? = null
        ) {
            val pads = GamepadManager.connectedGamepads()
            val base = if (pads.isEmpty()) "Sin mando conectado" else "🎮 ${pads.joinToString(", ")}"
    
            val debugLine = if (lastState != null) {
                val gyroTxt = if (lastState.hasGyro) "SI" else "no"
                "  ·  [$lastSide] gyro=$gyroTxt  q=(%.2f, %.2f, %.2f, %.2f)".format(
                    lastState.qw, lastState.qx, lastState.qy, lastState.qz
                )
            } else ""
    
            gamepadStatusText.text = when {
                currentTrackingMode == TrackingMode.HAND_JOYCONS ->
                    "$base · HandJoycons: posición=cámara, rotación/botones=Joy-Con$debugLine"
                sixDofHandMode == SixDofHandMode.MEDIAPIPE_JOYCONS ->
                    "$base · 6DoF+Manos+Joycons: posición=cámara, rotación/botones=Joy-Con$debugLine"
                else -> "$base$debugLine"
            }
        }
    
    
        private fun updateHandJoyconsFlag() {
            val active = currentTrackingMode == TrackingMode.HAND_JOYCONS ||
                    sixDofHandMode == SixDofHandMode.MEDIAPIPE_JOYCONS
            vrSender?.setHandJoyconsMode(active)
            vrSender?.setPlainHandJoyconsMode(currentTrackingMode == TrackingMode.HAND_JOYCONS)
        }
    
    
        private fun connect(pcIp: String, type: ConnectionType) {
            if (connectingInProgress) return
            connectingInProgress = true
            connectionType = type
            pcDiscovery?.stop()
    
            val typeLabel = when (type) {
                ConnectionType.CABLE   -> "Cable USB (RNDIS)"
                ConnectionType.USB_ADB -> "Cable USB (ADB)"
                ConnectionType.WIFI    -> "WiFi"
            }
            statusText.text = "Conectando a $pcIp ($typeLabel)…"
            setConnectInputsEnabled(false)
    
            val sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
    
            val onStatusCb: (String) -> Unit = { msg -> runOnUiThread { showStatus(msg) } }
    
            val onConnectedCb: () -> Unit = {
                runOnUiThread {
                    connectingInProgress = false
                    connectedPcIp = if (type == ConnectionType.USB_ADB) "127.0.0.1" else pcIp
    
                    vrSender?.setSixDofEnabled(switchSixDof.isChecked && sixDofTracker != null)
                    updateHandJoyconsFlag()
    
                    if (currentTrackingMode != TrackingMode.NONE) requestCameraAndStart(currentTrackingMode)
                    refreshGamepadStatus()
    
                    switchEyeTracking.isEnabled = (type != ConnectionType.USB_ADB)
                    if (type == ConnectionType.USB_ADB && switchEyeTracking.isChecked) {
                        switchEyeTracking.isChecked = false
                        showStatus("Eye Tracking (OSC/UDP) no está disponible en modo USB/ADB — usa WiFi o cable RNDIS")
                    }
    
                    connectionTypeBadge.text = when (type) {
                        ConnectionType.USB_ADB -> "🔌 ADB"
                        ConnectionType.CABLE   -> "🔌 Cable"
                        ConnectionType.WIFI    -> "📶 WiFi"
                    }
                    connectionTypeBadge.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(
                            if (type == ConnectionType.WIFI) 0xFF1A3A5C.toInt() else 0xFF6B3A10.toInt()
                        )
    
                    connectPanel.visibility           = View.GONE
                    connectBackgroundImage.visibility = View.GONE
                    tutorialButton.visibility         = View.GONE
                    // ← FIX 2: antes estos dos botones (Menú Hub y Ver videos
                    // SBS) quedaban VISIBLES de fondo al conectar a una PC y
                    // activar el stream de SteamVR, porque acá nunca se
                    // ocultaban. Se ocultan igual que tutorialButton.
                    hubMenuButton.visibility          = View.GONE
                    sbsVideoButton.visibility         = View.GONE
                    controlPanel.visibility           = View.VISIBLE
                    controlPanelVisible               = true
                    window.decorView.requestFocus()
    
                    setConnectInputsEnabled(true)
                }
            }
    
            val onFailedCb: (String) -> Unit = { msg ->
                runOnUiThread {
                    connectingInProgress = false
                    statusText.text = "No se pudo conectar: $msg"
                    setConnectInputsEnabled(true)
                    vrSender?.stop()
                    vrSender = null
    
                    if (pcDiscovery == null || discoveredPcs.isEmpty()) startDiscovery()
                }
            }
    
            vrSender?.stop()
            vrSender = if (type == ConnectionType.USB_ADB) {
                UsbTrackingSender(onStatus = onStatusCb, onConnected = onConnectedCb, onFailed = onFailedCb)
            } else {
                VrUdpSender(pcIp = pcIp, onStatus = onStatusCb, onConnected = onConnectedCb, onFailed = onFailedCb)
            }
            vrSender?.start(sensorManager)
        }
    
    
        private fun setConnectInputsEnabled(enabled: Boolean) {
            findViewById<Button>(R.id.connectButton).isEnabled = enabled
            connectCableButton.isEnabled = enabled
            scanButton.isEnabled = enabled
            ipInput.isEnabled = enabled
            cableIpInput.isEnabled = enabled
        }
    
    
    
        private fun setupControlListeners() {
    
            switchHmd.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) vrSender?.recenter()
                vrSender?.setHmdEnabled(isChecked)
                showStatus(if (isChecked) "HMD activado — orientación desde móvil"
                else "HMD desactivado — orientación desde PC")
            }
    
            switchHand.setOnCheckedChangeListener { _, isChecked ->
    
                handTrackingSubPanel.visibility = if (isChecked) View.VISIBLE else View.GONE
    
                setRadioGroupEnabled(trackingModeGroup, isChecked)
                switchCameraOverlay.isEnabled = isChecked
                if (isChecked) {
                    if (currentTrackingMode == TrackingMode.NONE)
                        trackingModeGroup.check(R.id.modeHand)
                    else
                        requestCameraAndStart(currentTrackingMode)
                } else {
                    stopAllTrackers()
                    switchCameraOverlay.isChecked = false
                    cameraOverlayPanel.visibility = View.GONE
                    vrSender?.updateHands(
                        HandPose(-0.35f, 0.1f, -0.5f, false),
                        HandPose( 0.35f, 0.1f, -0.5f, false)
                    )
                    showStatus("Tracking de manos desactivado")
                    window.decorView.requestFocus()
                }
                updateTrackingExclusivity()
            }
    
            switchSixDof.setOnCheckedChangeListener { _, isChecked ->
    
                sixDofSubPanel.visibility = if (isChecked) View.VISIBLE else View.GONE
    
                if (isChecked) {
                    if (!arCoreAvailable) {
                        android.util.Log.d("SixDofTracker", "Switch encendido pero arCoreAvailable=false -> se apaga sin intentar nada")
                        switchSixDof.isChecked = false
                        sixDofSubPanel.visibility = View.GONE
                        showStatus("6DoF no disponible: dispositivo no compatible con ARCore")
                    } else if (sixDofHandMode == SixDofHandMode.NONE && !supportsConcurrentCameras
                        && switchHand.isChecked && currentTrackingMode != TrackingMode.NONE) {
                        switchSixDof.isChecked = false
                        sixDofSubPanel.visibility = View.GONE
                        showStatus("Solo 1 cámara disponible: desactiva el tracking de manos o usa un modo '6DoF + Manos'")
                    } else {
                        if (sixDofHandMode != SixDofHandMode.NONE && switchHand.isChecked) {
                            switchHand.isChecked = false
                        }
                        startSixDofRequestingPermission()
                    }
                } else {
                    stopSixDof()
                    showStatus("Tracking 6DoF desactivado")
                }
                updateTrackingExclusivity()
            }
    
            trackingModeGroup.setOnCheckedChangeListener { _, checkedId ->
                val newMode = when (checkedId) {
                    R.id.modeHand         -> TrackingMode.HAND
                    R.id.modeLedBlue      -> TrackingMode.LED_BLUE
                    R.id.modeLedGreen     -> TrackingMode.LED_GREEN
                    R.id.modeHandJoycons  -> TrackingMode.HAND_JOYCONS
                    else                  -> TrackingMode.NONE
                }
                if (newMode != currentTrackingMode) {
                    currentTrackingMode = newMode
                    updateHandJoyconsFlag()
                    if (newMode == TrackingMode.NONE) {
                        stopAllTrackers()
                        switchCameraOverlay.isChecked = false
                        cameraOverlayPanel.visibility = View.GONE
                        vrSender?.updateHands(
                            HandPose(-0.35f, 0.1f, -0.5f, false),
                            HandPose( 0.35f, 0.1f, -0.5f, false)
                        )
                        showStatus("Tracking de cámara: ninguno")
                        window.decorView.requestFocus()
                    } else if (switchHand.isChecked) {
                        stopAllTrackers()
                        requestCameraAndStart(currentTrackingMode)
                    }
                    refreshGamepadStatus()
                }
            }
    
            switchCameraOverlay.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked && !streamActive) {
                    cameraOverlayPanel.visibility = View.VISIBLE
                } else {
                    cameraOverlayPanel.visibility = View.GONE
                }
                showStatus(if (isChecked) "Vista cámara activada" else "Vista cámara oculta")
                window.decorView.requestFocus()
            }
    
            switchEyeTracking.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    startEyeTrackingRequestingPermission()
                } else {
                    stopEyeTracking()
                    showStatus("Eye Tracking VRChat desactivado")
                }
            }
    
            recenterButton.setOnClickListener {
                vrSender?.recenter()
                sixDofTracker?.recenter()
                joyConHid?.recenterBoth()
                showStatus("Recentrado ✓")
            }
    
            streamButton.setOnClickListener { startStream() }
    
            closeStreamButton.setOnClickListener {
                when {
                    isPlayingLocalVideo -> stopLocalVideoAndReturnToMenu() // ── SBS VIDEO
                    hubModeActive || pendingHubStartAfterSurface -> stopHubMode()
                    else -> stopStream()
                }
            }
    
            recenterHmdButton.setOnClickListener {
                when {
                    isPlayingLocalVideo -> {
                        // ── SBS VIDEO + GIROSCOPIO: recentra la "pantalla" del
                        // video justo enfrente de hacia donde estás mirando ahora.
                        videoHeadTracker?.recenter()
                        showStatus("Video recentrado enfrente tuyo ✓")
                    }
                    hubModeActive -> {
                        hubWindowController.recenter()
                        sixDofTracker?.recenter()
                        showStatus("Recentrado ✓ (HMD)")
                    }
                    else -> {
                        vrSender?.recenter()
                        sixDofTracker?.recenter()
                        joyConHid?.recenterBoth()
                        showStatus("Recentrado ✓ (HMD)")
                    }
                }
            }
        }
    
    
    
        private fun requestCameraAndStart(mode: TrackingMode) {
            if (mode == TrackingMode.NONE) { stopAllTrackers(); return }
            currentTrackingMode = mode
            pendingCameraAction = { startTracker(mode) }
            when {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED -> startTracker(mode)
                else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    
        private fun startTracker(mode: TrackingMode) {
            stopAllTrackers()
            if (mode == TrackingMode.NONE) return
            cameraManager = CameraPreviewManager(this, cameraPreviewView, skeletonOverlay)
            runOnUiThread {
                cameraOverlayPanel.visibility =
                    if (switchCameraOverlay.isChecked && !streamActive) View.VISIBLE else View.GONE
            }
            when (mode) {
                TrackingMode.HAND, TrackingMode.HAND_JOYCONS -> {
                    cameraManager?.startWithHandTracker(
                        owner   = this,
                        onHands = { l, r -> vrSender?.updateHands(l, r) },
                        onError = { msg  -> runOnUiThread { showStatus(msg) } },
                        onReady = {
                            runOnUiThread {
                                if (switchCameraOverlay.isChecked && !streamActive)
                                    cameraOverlayPanel.visibility = View.VISIBLE
                                window.decorView.requestFocus()
                                if (mode == TrackingMode.HAND_JOYCONS) warnIfNoJoyConsConnected()
                                // Aplica el zoom guardado apenas la cámara
                                // termina de iniciar (antes de esto,
                                // CameraPreviewManager todavía no tenía la
                                // referencia a la Camera real).
                                cameraManager?.setZoomRatio(handCameraZoom)
                            }
                        }
                    )
                    showStatus(
                        if (mode == TrackingMode.HAND_JOYCONS)
                            "HandJoycons: posición de manos por cámara + rotación/botones de Joy-Con (iniciando…)"
                        else
                            "Tracking: MediaPipe manos (iniciando…)"
                    )
                }
                TrackingMode.LED_BLUE, TrackingMode.LED_GREEN -> {
                    cameraManager?.startWithColorTracker(
                        owner   = this,
                        onHands = { l, r -> vrSender?.updateHands(l, r) },
                        onError = { msg  -> runOnUiThread { showStatus(msg) } },
                        onReady = {
                            runOnUiThread {
                                if (switchCameraOverlay.isChecked && !streamActive)
                                    cameraOverlayPanel.visibility = View.VISIBLE
                                window.decorView.requestFocus()
                                // Mismo zoom guardado también para el modo LED
                                // (comparte la misma cámara).
                                cameraManager?.setZoomRatio(handCameraZoom)
                            }
                        }
                    )
                    showStatus("Tracking: LED verde=izq 🟢  azul=der 🔵")
                }
                TrackingMode.NONE -> {}
            }
        }
    
    
        private fun warnIfNoJoyConsConnected() {
            if (currentTrackingMode != TrackingMode.HAND_JOYCONS) return
            val pads = GamepadManager.connectedGamepads()
            val hasJoyCon = pads.any { it.lowercase().contains("joy-con") || it.lowercase().contains("joycon") }
            if (!hasJoyCon) {
                showStatus("HandJoycons: ningún Joy-Con emparejado todavía — rotación en reposo hasta emparejar uno")
            }
        }
    
        private fun stopAllTrackers() {
            cameraManager?.stop()
            cameraManager = null
            if (::skeletonOverlay.isInitialized) skeletonOverlay.clear()
        }
    
    
    
        private fun showStatus(msg: String) {
            runOnUiThread {
                if (controlPanel.visibility == View.VISIBLE)
                    controlStatusText.text = msg
                else
                    statusText.text = msg
    
                if (msg.contains("6DoF") || msg.contains("ARCore")) {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Diagnóstico 6DoF")
                        .setMessage(msg)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    
        private fun setRadioGroupEnabled(group: RadioGroup, enabled: Boolean) {
            for (i in 0 until group.childCount) group.getChildAt(i).isEnabled = enabled
        }
    
        private fun setupFullscreen() {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            window.decorView.isFocusable = true
            window.decorView.isFocusableInTouchMode = true
            window.decorView.requestFocus()
        }
    }
