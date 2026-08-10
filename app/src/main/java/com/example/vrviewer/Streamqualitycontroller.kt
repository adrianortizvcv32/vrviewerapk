package com.example.vrviewer

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors


object StreamQualityController {

    private const val TAG = "StreamQualityController"
    private const val QUALITY_PORT = 47296

    private val executor = Executors.newSingleThreadExecutor()

    fun sendQuality(pcIp: String, width: Int, height: Int, bitrateBps: Int, fps: Int) {
        executor.execute {
            try {
                val buf = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
                buf.put("QUAL".toByteArray(Charsets.US_ASCII))
                buf.putShort(width.toShort())
                buf.putShort(height.toShort())
                buf.putInt(bitrateBps)
                buf.put(fps.toByte())

                val addr = InetAddress.getByName(pcIp)
                val socket = DatagramSocket()
                socket.send(DatagramPacket(buf.array(), buf.array().size, addr, QUALITY_PORT))
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error enviando calidad: ${e.message}")
            }
        }
    }
}