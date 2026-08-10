package com.example.vrviewer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

class VrAudioReceiver(
    private val pcIp:     String,
    private val onStatus: (String) -> Unit
) {
    companion object {
        private const val TAG = "VrAudioReceiver"
        private const val AUDIO_PORT = 47298

        private const val SAMPLE_RATE = 48000
        private const val CHANNELS    = AudioFormat.CHANNEL_OUT_STEREO
        private const val ENCODING    = AudioFormat.ENCODING_PCM_16BIT

        private const val RECONNECT_DELAY_MS = 2_000L
        private const val CONNECT_TIMEOUT_MS = 5_000
    }

    private val running  = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private var future: Future<*>? = null

    @Volatile private var activeSocket: Socket? = null
    private var audioTrack: AudioTrack? = null

    fun start() {
        if (running.getAndSet(true)) return
        future = executor.submit { receiveLoop() }
    }

    fun stop() {
        running.set(false)
        try { activeSocket?.close() } catch (_: Exception) {}
        future?.cancel(false)
    }



    private fun receiveLoop() {
        var track: AudioTrack? = null

        while (running.get()) {
            var socket: Socket? = null
            try {
                onStatus("Audio: conectando…")
                socket = Socket()
                activeSocket = socket
                socket.connect(InetSocketAddress(pcIp, AUDIO_PORT), CONNECT_TIMEOUT_MS)
                socket.tcpNoDelay = true
                val input = socket.getInputStream()
                onStatus("Audio conectado ✓")

                track?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
                track = createAudioTrack()
                track.play()
                audioTrack = track

                receivePcm(input, track)

            } catch (e: Exception) {
                Log.e(TAG, "Error de audio: ${e.message}")
                if (running.get()) {
                    onStatus("Audio desconectado — reconectando…")
                    Thread.sleep(RECONNECT_DELAY_MS)
                }
            } finally {
                try { socket?.close() } catch (_: Exception) {}
                activeSocket = null
            }
        }

        track?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        audioTrack = null
        onStatus("Audio detenido")
    }



    private fun createAudioTrack(): AudioTrack {
        val minBufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING)
        // Un poco más grande que el mínimo para absorber jitter de red
        // sin acumular demasiada latencia extra.
        val bufSize = (minBufSize * 2).coerceAtLeast(minBufSize)

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNELS)
            .setEncoding(ENCODING)
            .build()

        return AudioTrack(
            attrs, format, bufSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
    }



    private fun receivePcm(input: InputStream, track: AudioTrack) {
        val lenBuf = ByteArray(4)

        while (running.get()) {
            readFully(input, lenBuf, 4)

            val totalLen = ((lenBuf[0].toInt() and 0xFF) shl 24) or
                    ((lenBuf[1].toInt() and 0xFF) shl 16) or
                    ((lenBuf[2].toInt() and 0xFF) shl  8) or
                    (lenBuf[3].toInt() and 0xFF)

            if (totalLen <= 0 || totalLen > 1 * 1024 * 1024) {
                Log.w(TAG, "Longitud de audio inválida: $totalLen")
                throw RuntimeException("Longitud de paquete de audio inválida: $totalLen")
            }

            val pcm = ByteArray(totalLen)
            readFully(input, pcm, totalLen)

            track.write(pcm, 0, pcm.size)
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int) {
        var offset = 0
        while (offset < len) {
            val n = input.read(buf, offset, len - offset)
            if (n < 0) throw RuntimeException("EOF inesperado en audio (offset=$offset len=$len)")
            offset += n
        }
    }
}