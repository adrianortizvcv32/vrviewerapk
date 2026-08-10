package com.example.vrviewer

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean


class PcDiscovery(
    private val onFound: (ip: String, name: String) -> Unit,
    private val onError: (String) -> Unit = {}
) {
    companion object {
        const val ANNOUNCE_PORT   = 47294
        const val ANNOUNCE_MAGIC  = "CVRANNOUNCE:"
        private const val TAG     = "PcDiscovery"
        private const val TIMEOUT = 500
    }

    private val running  = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private var future: Future<*>? = null

    fun start() {
        if (running.getAndSet(true)) return
        future = executor.submit {
            try {
                val sock = DatagramSocket(ANNOUNCE_PORT)
                sock.broadcast   = true
                sock.soTimeout   = TIMEOUT
                val buf = ByteArray(64)
                while (running.get()) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        sock.receive(pkt)
                        val msg = String(pkt.data, 0, pkt.length, Charsets.US_ASCII)
                        if (msg.startsWith(ANNOUNCE_MAGIC)) {
                            val ip   = pkt.address.hostAddress ?: continue
                            val name = msg.removePrefix(ANNOUNCE_MAGIC).trim()
                            Log.d(TAG, "Descubierto SteamVR PC: $name @ $ip")
                            onFound(ip, name)
                        }
                    } catch (_: java.net.SocketTimeoutException) {

                    }
                }
                sock.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error en discovery: ${e.message}")
                onError("Discovery: ${e.message}")
            }
        }
    }

    fun stop() {
        running.set(false)
        future?.cancel(true)
    }
}