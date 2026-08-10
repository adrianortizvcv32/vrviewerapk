package com.example.vrviewer

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.ar.core.ArCoreApk


object DeviceCapabilities {

    private const val TAG = "DeviceCapabilities"

    enum class ArAvailability { SUPPORTED, UNSUPPORTED }


    fun checkArCoreAvailability(context: Context, onResult: (ArAvailability) -> Unit) {
        checkArCoreAvailabilityInternal(context, onResult, retriesLeft = 6)
    }


    private fun checkArCoreAvailabilityInternal(
        context: Context,
        onResult: (ArAvailability) -> Unit,
        retriesLeft: Int
    ) {
        try {
            val availability = ArCoreApk.getInstance().checkAvailability(context)
            Log.d(TAG, "checkAvailability() -> $availability (retriesLeft=$retriesLeft)")

            if (availability.isTransient) {
                // Todavía se está resolviendo (p.ej. consultando Play Store) — reintentar.
                Handler(Looper.getMainLooper()).postDelayed({
                    checkArCoreAvailabilityInternal(context, onResult, retriesLeft)
                }, 300)
                return
            }

            if (availability == ArCoreApk.Availability.UNKNOWN_ERROR && retriesLeft > 0) {
                // Probable fallo temporal de red/Play Services — reintentar con backoff.
                Handler(Looper.getMainLooper()).postDelayed({
                    checkArCoreAvailabilityInternal(context, onResult, retriesLeft - 1)
                }, 500)
                return
            }

            val result = if (availability.isSupported) ArAvailability.SUPPORTED else ArAvailability.UNSUPPORTED
            Log.d(TAG, "Resultado final ARCore: $result (raw=$availability)")
            onResult(result)
        } catch (e: Exception) {
            Log.e(TAG, "Excepción comprobando ARCore: ${e.javaClass.simpleName}: ${e.message}", e)
            onResult(ArAvailability.UNSUPPORTED)
        }
    }


    fun supportsConcurrentCameras(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val sets = cm.concurrentCameraIds
            val hasPair = sets.any { it.size >= 2 }
            Log.d(TAG, "concurrentCameraIds=$sets hasPair=$hasPair")
            hasPair
        } catch (e: Exception) {
            Log.e(TAG, "Error comprobando cámaras concurrentes: ${e.message}")
            false
        }
    }

    fun cameraCount(context: Context): Int {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.cameraIdList.size
        } catch (e: Exception) {
            0
        }
    }
}