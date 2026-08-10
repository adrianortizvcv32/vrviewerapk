package com.example.vrviewer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

object UsbConnectionHelper {

    private const val TAG = "UsbConnectionHelper"


    private val TETHERING_PREFIXES = listOf("192.168.42.", "192.168.43.", "192.168.44.")
    private val RNDIS_IFACE_NAMES  = listOf("rndis0", "usb0", "ncm0")


    fun getPcIpViaCable(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces.iterator()) {
                if (!iface.isUp || iface.isLoopback) continue

                val isRndis = RNDIS_IFACE_NAMES.any { iface.name.startsWith(it) }

                for (addr in iface.inetAddresses) {
                    if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                    val ip = addr.hostAddress ?: continue

                    val matchesPrefix = TETHERING_PREFIXES.any { ip.startsWith(it) }

                    if (isRndis || matchesPrefix) {
                        // Derivar IP del gateway: reemplazar último octeto por .1
                        val gatewayIp = ip.substringBeforeLast(".") + ".1"
                        Log.d(TAG, "USB tethering detectado: iface=${iface.name} phoneIp=$ip pcIp=$gatewayIp")
                        return gatewayIp
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error detectando tethering USB: ${e.message}")
        }
        return null
    }


    fun isUsbTetherActive(): Boolean = getPcIpViaCable() != null
}