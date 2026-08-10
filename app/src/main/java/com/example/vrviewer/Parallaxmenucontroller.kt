package com.example.vrviewer

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.View

class ParallaxMenuController(
    context: Context,
    private val backgroundView: View,
    private val foregroundView: View? = null,
    private val backgroundMaxOffsetPx: Float = 40f,
    private val foregroundMaxOffsetPx: Float = 8f,
    private val smoothing: Float = 0.15f
) : SensorEventListener {

    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)


    private val activity: Activity? = context as? Activity

    private var bgX = 0f
    private var bgY = 0f
    private var fgX = 0f
    private var fgY = 0f


    fun start() {
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }


    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)


        val remapped = FloatArray(9)
        val rotation = activity?.let {
            @Suppress("DEPRECATION")
            it.windowManager.defaultDisplay.rotation
        } ?: Surface.ROTATION_0

        when (rotation) {
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                rotationMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remapped
            )
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                rotationMatrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remapped
            )
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(
                rotationMatrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remapped
            )
            else -> rotationMatrix.copyInto(remapped) // ROTATION_0
        }

        val orientation = FloatArray(3)
        SensorManager.getOrientation(remapped, orientation)

        val pitch = orientation[1] // inclinación adelante/atrás (radianes)
        val roll = orientation[2]  // inclinación izquierda/derecha (radianes)


        val normX = (-roll / (Math.PI / 4)).toFloat().coerceIn(-1f, 1f)
        val normY = (pitch / (Math.PI / 4)).toFloat().coerceIn(-1f, 1f)


        val targetBgX = normX * backgroundMaxOffsetPx
        val targetBgY = normY * backgroundMaxOffsetPx
        bgX += (targetBgX - bgX) * smoothing
        bgY += (targetBgY - bgY) * smoothing
        backgroundView.translationX = bgX
        backgroundView.translationY = bgY


        foregroundView?.let { panel ->
            val targetFgX = -normX * foregroundMaxOffsetPx
            val targetFgY = -normY * foregroundMaxOffsetPx
            fgX += (targetFgX - fgX) * smoothing
            fgY += (targetFgY - fgY) * smoothing
            panel.translationX = fgX
            panel.translationY = fgY
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}