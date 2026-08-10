package com.example.vrviewer

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors


object AdbTunnelHelper {
    private val executor = Executors.newSingleThreadExecutor()

    fun checkReachable(timeoutMs: Int = 400, callback: (Boolean) -> Unit) {
        executor.execute {
            val ok = try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", UsbTrackingSender.USB_TRACK_PORT), timeoutMs)
                    true
                }
            } catch (_: Exception) {
                false
            }
            callback(ok)
        }
    }
}