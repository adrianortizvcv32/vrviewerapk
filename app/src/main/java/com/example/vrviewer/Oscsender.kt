package com.example.vrviewer

import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

object OscSender {

    private const val TAG = "OscSender"
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var socket: DatagramSocket? = null

    private fun getSocket(): DatagramSocket {
        var s = socket
        if (s == null || s.isClosed) {
            s = DatagramSocket()
            socket = s
        }
        return s
    }


    private fun pad4(len: Int): Int = (len + 4) and 0xFFFFFFFC.toInt()

    private fun writeOscString(out: ByteArrayOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.US_ASCII)
        out.write(bytes)
        val totalLen = pad4(bytes.size)
        // Rellenar con \0 hasta el múltiplo de 4 (mínimo 1 byte nulo).
        repeat(totalLen - bytes.size) { out.write(0) }
    }


    fun sendFloat(host: String, port: Int, address: String, value: Float) {
        sendFloats(host, port, address, value)
    }

    fun sendFloats(host: String, port: Int, address: String, vararg values: Float) {
        executor.execute {
            try {
                val out = ByteArrayOutputStream()
                writeOscString(out, address)
                writeOscString(out, "," + "f".repeat(values.size))

                for (v in values) {
                    val bits = java.lang.Float.floatToIntBits(v)
                    out.write((bits ushr 24) and 0xFF)
                    out.write((bits ushr 16) and 0xFF)
                    out.write((bits ushr 8) and 0xFF)
                    out.write(bits and 0xFF)
                }

                val data = out.toByteArray()
                val addr = InetAddress.getByName(host)
                getSocket().send(DatagramPacket(data, data.size, addr, port))
            } catch (e: Exception) {
                Log.e(TAG, "Error enviando OSC $address: ${e.message}")
            }
        }
    }


    fun sendBool(host: String, port: Int, address: String, value: Boolean) {
        executor.execute {
            try {
                val out = ByteArrayOutputStream()
                writeOscString(out, address)
                writeOscString(out, if (value) ",T" else ",F")
                // Los type tags T y F no llevan bytes de argumento adicionales.
                val data = out.toByteArray()
                val addr = InetAddress.getByName(host)
                getSocket().send(DatagramPacket(data, data.size, addr, port))
            } catch (e: Exception) {
                Log.e(TAG, "Error enviando OSC bool $address: ${e.message}")
            }
        }
    }

    fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }
}