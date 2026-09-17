package com.example.vrviewer

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.tan

/**
 * Simula una "pantalla fija en la habitación" para el modo Ver videos SBS:
 * lee el giroscopio (rotation vector) del teléfono metido en el visor y
 * traduce el giro de cabeza en un desplazamiento (offset) del rectángulo
 * del video dentro del campo de visión — como si el video estuviera
 * colgado en una posición fija del mundo real y vos movieras la cabeza
 * para mirarlo desde distintos ángulos. Ver MainActivity.playLocalSbsVideo().
 *
 * NUEVO (fondo con giroscopio): además del offset del rectángulo del
 * video (onOffsetChanged), ahora también reporta el yaw/pitch "crudo"
 * (relativo al último recentrado) vía onHeadAnglesChanged. StereoGLRenderer
 * usa esos ángulos (setHeadAngles) para mover el FONDO detrás del video —
 * con un parallax sutil en modo ventana normal, o con una vuelta 360°
 * completa en modo "Ver entorno con giro" (ver
 * StereoGLRenderer.setVideoEnvMode() y el bloque 4b del fragment shader).
 *
 * NUEVO (ROLL — volante): orientation[2] de SensorManager.getOrientation()
 * ya es exactamente el "roll" — inclinar el teléfono de lado a lado como
 * un volante, sin girar la cabeza ni mirar arriba/abajo. Antes se
 * ignoraba por completo. Ahora se calcula igual que yaw/pitch (delta
 * contra el punto de referencia del último recentrado) y se reenvía junto
 * con yaw/pitch en onHeadAnglesChanged, que pasó de 2 a 3 parámetros:
 * (yaw, pitch, roll). StereoGLRenderer usa ese roll para rotar tanto el
 * rectángulo del video como el fondo alrededor del centro de la vista
 * (ver uHeadRoll en el fragment shader), simulando un Cardboard 3DoF real.
 */
class GyroVideoWindowTracker(
    private val context: Context,
    private val onOffsetChanged: (Float, Float) -> Unit,
    private val onHeadAnglesChanged: ((Float, Float, Float) -> Unit)? = null,
    private val sensitivity: Float = 1.0f,
    private val halfFovXDeg: Float = 45f,
    private val halfFovYDeg: Float = 35f,
    private val smoothing: Float = 0.15f
) : SensorEventListener {

    private val activity: AppCompatActivity? = context as? AppCompatActivity
    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val halfFovXRad = Math.toRadians(halfFovXDeg.toDouble()).toFloat()
    private val halfFovYRad = Math.toRadians(halfFovYDeg.toDouble()).toFloat()

    // Punto de referencia (yaw/pitch/roll al momento de start()/recenter()):
    // todo se reporta como delta contra este punto. refYaw == null
    // significa "todavía no calibramos", y la próxima lectura del sensor
    // se toma como el nuevo cero.
    @Volatile private var refYaw: Float? = null
    @Volatile private var refPitch: Float = 0f
    @Volatile private var refRoll: Float = 0f

    private var smoothX = 0f
    private var smoothY = 0f

    fun start() {
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    /** Recentra la "pantalla" del video (y el fondo/roll) justo enfrente de
     *  hacia donde estás mirando ahora mismo: la próxima lectura del
     *  sensor pasa a ser el nuevo punto de referencia (delta = 0). */
    fun recenter() {
        refYaw = null
        smoothX = 0f
        smoothY = 0f
        onOffsetChanged(0f, 0f)
        onHeadAnglesChanged?.invoke(0f, 0f, 0f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        // Paso 1: normalizar a un frame "relativo a la pantalla" (X=derecha
        // en pantalla, Y=arriba en pantalla, Z=sale de la pantalla),
        // sin importar si Android reporta ROTATION_90 o ROTATION_270 según
        // de qué lado quedó el teléfono al meterlo en el visor.
        val screenNormalized = FloatArray(9)
        val rotation = activity?.let {
            @Suppress("DEPRECATION")
            it.windowManager.defaultDisplay.rotation
        } ?: Surface.ROTATION_0

        when (rotation) {
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                rotationMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, screenNormalized
            )
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                rotationMatrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, screenNormalized
            )
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(
                rotationMatrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, screenNormalized
            )
            else -> rotationMatrix.copyInto(screenNormalized)
        }

        // Paso 2 (EL FIX de orientación): el teléfono va PARADO en el
        // visor, no plano. Este remap (el mismo truco de Google Cardboard)
        // trata el eje que sale de la pantalla (Z) como el nuevo eje
        // "arriba" de referencia, y deja el eje X de pantalla como nuevo
        // eje X. Con esto, azimuth pasa a representar "girar la cabeza a
        // los lados" en vez de "rotar el teléfono como un volante", pitch
        // pasa a representar mirar arriba/abajo, y roll (orientation[2])
        // pasa a representar EXACTAMENTE "girar el teléfono como un
        // volante" — que es el eje que faltaba usar.
        val remapped = FloatArray(9)
        SensorManager.remapCoordinateSystem(
            screenNormalized, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped
        )

        val orientation = FloatArray(3)
        SensorManager.getOrientation(remapped, orientation)

        val yaw = orientation[0]   // girar la cabeza a los lados (radianes)
        val pitch = orientation[1] // mirar arriba/abajo (radianes)
        val roll = orientation[2]  // girar el teléfono como volante (radianes)

        val ref = refYaw
        if (ref == null) {
            refYaw = yaw
            refPitch = pitch
            refRoll = roll
            return
        }

        var deltaYaw = yaw - ref
        while (deltaYaw > Math.PI) deltaYaw -= (2 * Math.PI).toFloat()
        while (deltaYaw < -Math.PI) deltaYaw += (2 * Math.PI).toFloat()
        val deltaPitch = pitch - refPitch

        var deltaRoll = roll - refRoll
        while (deltaRoll > Math.PI) deltaRoll -= (2 * Math.PI).toFloat()
        while (deltaRoll < -Math.PI) deltaRoll += (2 * Math.PI).toFloat()

        // Fondo + roll (StereoGLRenderer.setHeadAngles): reporta el ángulo
        // crudo, sin el suavizado/clamp que sí se aplica más abajo para el
        // offset del rectángulo del video — así el fondo y la inclinación
        // responden 1:1 al giro real de la cabeza/teléfono.
        onHeadAnglesChanged?.invoke(deltaYaw, deltaPitch, deltaRoll)

        // Si al girar la cabeza a la derecha el video se mueve para el lado
        // equivocado (o arriba/abajo queda invertido), es solo cuestión de
        // sacar/poner el "-" en la línea correspondiente.
        val targetX = (-tan(deltaYaw.toDouble()) / tan(halfFovXRad.toDouble()))
            .toFloat().coerceIn(-2.5f, 2.5f) * sensitivity
        val targetY = (tan(deltaPitch.toDouble()) / tan(halfFovYRad.toDouble()))
            .toFloat().coerceIn(-2.5f, 2.5f) * sensitivity

        smoothX += (targetX - smoothX) * smoothing
        smoothY += (targetY - smoothY) * smoothing

        onOffsetChanged(smoothX, smoothY)
    }
}