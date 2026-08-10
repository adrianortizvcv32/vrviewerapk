package com.example.vrviewer

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec


class UsbTrackingSender(
    private val onStatus: (String) -> Unit,
    private val onConnected: () -> Unit = {},
    private val onFailed: (String) -> Unit = {}
) : SensorEventListener, ITrackingSender {

    companion object {
        // Debe coincidir con UsbTrackingServer::PORT en el driver (PC).
        const val USB_TRACK_PORT = 47299
        private const val PACKET_BYTES = 196 // igual que TrackingPacket en el driver
    }

    private val executor = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(false)
    private val resultReported = AtomicBoolean(false)
    private val sendLock = Any()

    private var socket: Socket? = null
    private var out: DataOutputStream? = null
    private var sessionToken: Int = 0

    // ── Sensor de rotación (idéntico a VrUdpSender) ──
    private var sensorManager: SensorManager? = null
    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    @Volatile private var refQuat: FloatArray? = null

    @Volatile private var hmdEnabled = false
    @Volatile private var sixDofEnabled = false
    @Volatile private var sixDofPose: HmdPose6Dof? = null
    @Volatile private var leftHand = HandPose(0f, 0f, 0f, tracked = false)
    @Volatile private var rightHand = HandPose(0f, 0f, 0f, tracked = false)
    @Volatile private var leftGamepad = GamepadManager.GamepadState()
    @Volatile private var rightGamepad = GamepadManager.GamepadState()
    @Volatile private var handJoyconsModeActive = false
    @Volatile private var plainHandJoyconsModeActive = false
    @Volatile private var quatX = 0f
    @Volatile private var quatY = 0f
    @Volatile private var quatZ = 0f
    @Volatile private var quatW = 1f

    private val secret = byteArrayOf(
        0x4b, 0x3a, 0x1f, 0x08.toByte(),
        0xc2.toByte(), 0x77, 0x9e.toByte(), 0x34,
        0x05, 0xab.toByte(), 0x61, 0xd9.toByte(),
        0xf8.toByte(), 0x2e, 0x47, 0xbc.toByte()
    )

    override fun setHmdEnabled(enabled: Boolean) { hmdEnabled = enabled }
    override fun setSixDofEnabled(enabled: Boolean) { sixDofEnabled = enabled; if (!enabled) sixDofPose = null }
    override fun updateSixDofPose(pose: HmdPose6Dof) { sixDofPose = pose }
    override fun updateHands(left: HandPose, right: HandPose) { leftHand = left; rightHand = right }
    override fun updateLeftGamepad(state: GamepadManager.GamepadState) { leftGamepad = state }
    override fun updateRightGamepad(state: GamepadManager.GamepadState) { rightGamepad = state }
    override fun setHandJoyconsMode(active: Boolean) { handJoyconsModeActive = active }
    override fun setPlainHandJoyconsMode(active: Boolean) { plainHandJoyconsModeActive = active }
    override fun recenter() { refQuat = null }

    override fun start(sm: SensorManager) {
        sensorManager = sm
        running.set(true)
        resultReported.set(false)
        val rotSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotSensor != null) sm.registerListener(this, rotSensor, SensorManager.SENSOR_DELAY_GAME)
        executor.execute { connectLoop() }
    }

    override fun stop() {
        running.set(false)
        sensorManager?.unregisterListener(this)
        try { socket?.close() } catch (_: Exception) {}
        executor.shutdownNow()
    }

    private fun reportConnected() { if (resultReported.compareAndSet(false, true)) onConnected() }
    private fun reportFailed(msg: String) { if (resultReported.compareAndSet(false, true)) onFailed(msg) }

    private fun writeFrame(dout: DataOutputStream, payload: ByteArray) {
        dout.writeInt(payload.size) // DataOutputStream.writeInt ya es big-endian
        dout.write(payload)
        dout.flush()
    }

    private fun readFrame(din: DataInputStream, expectedLen: Int): ByteArray {
        val len = din.readInt()
        require(len == expectedLen) { "longitud de frame inesperada: $len (se esperaba $expectedLen)" }
        val buf = ByteArray(len)
        din.readFully(buf)
        return buf
    }

    private fun connectLoop() {
        try {
            onStatus("Conectando por USB (127.0.0.1:$USB_TRACK_PORT)...")
            val sock = Socket()
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress("127.0.0.1", USB_TRACK_PORT), 5000)
            sock.soTimeout = 5000
            socket = sock

            val din = DataInputStream(sock.getInputStream())
            val dout = DataOutputStream(sock.getOutputStream())
            out = dout

            // Handshake: mismo contenido que VrUdpSender.doAuthenticate(),
            // envuelto en frames [longitud][payload].
            writeFrame(dout, "CVRHELLO".toByteArray(Charsets.US_ASCII))

            val chlgMsg = readFrame(din, 12)
            if (String(chlgMsg, 0, 4, Charsets.US_ASCII) != "CHLG") {
                val msg = "Error auth USB: respuesta inesperada"
                onStatus(msg); reportFailed(msg); sock.close(); return
            }
            val challenge = chlgMsg.copyOfRange(4, 12)

            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret, "HmacSHA256"))
            val hmac8 = mac.doFinal(challenge).copyOf(8)

            val resp = ByteArray(12)
            "RESP".toByteArray(Charsets.US_ASCII).copyInto(resp, 0)
            hmac8.copyInto(resp, 4)
            writeFrame(dout, resp)

            val toknMsg = readFrame(din, 8)
            if (String(toknMsg, 0, 4, Charsets.US_ASCII) != "TOKN") {
                val msg = "Auth USB fallida: clave incorrecta"
                onStatus(msg); reportFailed(msg); sock.close(); return
            }
            sessionToken = ByteBuffer.wrap(toknMsg, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int

            sock.soTimeout = 0 // sin timeout para el streaming continuo
            onStatus("Conectado por USB ✓")
            reportConnected()
            sendLoop(dout)

        } catch (e: java.net.SocketTimeoutException) {
            val msg = "Error USB: la PC no respondió (timeout). ¿Corriste 'adb reverse tcp:$USB_TRACK_PORT tcp:$USB_TRACK_PORT'?"
            onStatus(msg); reportFailed(msg)
        } catch (e: Exception) {
            val msg = "Error de conexión USB: ${e.message}"
            onStatus(msg); reportFailed(msg)
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    // Misma lógica de fusión con Joy-Cons que VrUdpSender.
    private fun mergeHandRotation(hand: HandPose, pad: GamepadManager.GamepadState): HandPose {
        if (!handJoyconsModeActive) return hand
        return if (pad.joyConConnected) {
            hand.copy(
                qx = pad.qx, qy = pad.qy, qz = pad.qz, qw = pad.qw,
                curlThumb = 0f, curlIndex = 0f, curlMiddle = 0f, curlRing = 0f, curlPinky = 0f
            )
        } else {
            hand.copy(
                qx = 0f, qy = 0f, qz = 0f, qw = 1f,
                curlThumb = 0f, curlIndex = 0f, curlMiddle = 0f, curlRing = 0f, curlPinky = 0f
            )
        }
    }

    private fun mergeHandInput(hand: HandPose, pad: GamepadManager.GamepadState): Pair<Float, Float> {
        if (plainHandJoyconsModeActive) return pad.trigger to pad.grip
        return if (pad.joyConConnected) {
            pad.trigger to pad.grip
        } else {
            maxOf(pad.trigger, hand.pinch) to maxOf(pad.grip, hand.grip)
        }
    }

    // Mismo layout de 196 bytes que VrUdpSender.doSendLoop(): token +
    // mano izq (20 floats) + mano der (20 floats) + hmd (8 floats).
    private fun buildPacket(): ByteArray {
        val buf = ByteBuffer.allocate(PACKET_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buf.putFloat(java.lang.Float.intBitsToFloat(sessionToken))

        val lh = mergeHandRotation(leftHand, leftGamepad)
        val lg = leftGamepad
        val (lTrigger, lGrip) = mergeHandInput(lh, lg)
        buf.putFloat(lh.x); buf.putFloat(lh.y); buf.putFloat(lh.z)
        buf.putFloat(lh.qx); buf.putFloat(lh.qy); buf.putFloat(lh.qz); buf.putFloat(lh.qw)
        buf.putFloat(lTrigger); buf.putFloat(lGrip)
        buf.putFloat(lg.joyX); buf.putFloat(lg.joyY)
        buf.putFloat(if (lg.sysBtn) 1f else 0f)
        buf.putFloat(if (lg.appBtn) 1f else 0f)
        buf.putFloat(if (lg.clickBtn) 1f else 0f)
        buf.putFloat(if (lh.tracked) 1f else 0f)
        buf.putFloat(lh.curlThumb); buf.putFloat(lh.curlIndex); buf.putFloat(lh.curlMiddle)
        buf.putFloat(lh.curlRing); buf.putFloat(lh.curlPinky)

        val rh = mergeHandRotation(rightHand, rightGamepad)
        val rg = rightGamepad
        val (rTrigger, rGrip) = mergeHandInput(rh, rg)
        buf.putFloat(rh.x); buf.putFloat(rh.y); buf.putFloat(rh.z)
        buf.putFloat(rh.qx); buf.putFloat(rh.qy); buf.putFloat(rh.qz); buf.putFloat(rh.qw)
        buf.putFloat(rTrigger); buf.putFloat(rGrip)
        buf.putFloat(rg.joyX); buf.putFloat(rg.joyY)
        buf.putFloat(if (rg.sysBtn) 1f else 0f)
        buf.putFloat(if (rg.appBtn) 1f else 0f)
        buf.putFloat(if (rg.clickBtn) 1f else 0f)
        buf.putFloat(if (rh.tracked) 1f else 0f)
        buf.putFloat(rh.curlThumb); buf.putFloat(rh.curlIndex); buf.putFloat(rh.curlMiddle)
        buf.putFloat(rh.curlRing); buf.putFloat(rh.curlPinky)

        val sdPose = sixDofPose
        if (sixDofEnabled && sdPose != null && sdPose.tracked) {
            buf.putFloat(sdPose.x); buf.putFloat(sdPose.y); buf.putFloat(sdPose.z)
            buf.putFloat(sdPose.qx); buf.putFloat(sdPose.qy); buf.putFloat(sdPose.qz); buf.putFloat(sdPose.qw)
            buf.putFloat(1f)
        } else if (hmdEnabled) {
            buf.putFloat(0f); buf.putFloat(1.6f); buf.putFloat(0f)
            buf.putFloat(quatX); buf.putFloat(quatY); buf.putFloat(quatZ); buf.putFloat(quatW)
            buf.putFloat(1f)
        } else {
            buf.putFloat(0f); buf.putFloat(1.6f); buf.putFloat(0f)
            buf.putFloat(0f); buf.putFloat(0f); buf.putFloat(0f); buf.putFloat(1f)
            buf.putFloat(0f)
        }
        return buf.array()
    }

    private fun sendLoop(dout: DataOutputStream) {
        while (running.get()) {
            try {
                val packet = buildPacket()
                synchronized(sendLock) { writeFrame(dout, packet) }
            } catch (e: Exception) {
                onStatus("Error de envío USB: ${e.message}")
                return
            }
            Thread.sleep(11) // misma cadencia (~90Hz) que VrUdpSender
        }
    }


    fun sendQualityChange(width: Int, height: Int, bitrate: Int, fps: Int) {
        val dout = out ?: return
        try {
            val payload = ByteArray(13)
            "QUAL".toByteArray(Charsets.US_ASCII).copyInto(payload, 0)
            payload[4] = ((width shr 8) and 0xFF).toByte()
            payload[5] = (width and 0xFF).toByte()
            payload[6] = ((height shr 8) and 0xFF).toByte()
            payload[7] = (height and 0xFF).toByte()
            payload[8] = ((bitrate shr 24) and 0xFF).toByte()
            payload[9] = ((bitrate shr 16) and 0xFF).toByte()
            payload[10] = ((bitrate shr 8) and 0xFF).toByte()
            payload[11] = (bitrate and 0xFF).toByte()
            payload[12] = fps.toByte()
            synchronized(sendLock) { writeFrame(dout, payload) }
        } catch (e: Exception) {
            onStatus("Error enviando QUAL por USB: ${e.message}")
        }
    }

    // ── Sensor: idéntico a VrUdpSender.onSensorChanged/matrixToQuaternion ──
    // La orientación del teléfono no depende del transporte de red, así
    // que esta lógica se replica tal cual. Si algún día se refactoriza
    // VrUdpSender para extraer esto a una clase compartida (p.ej.
    // PhoneOrientationTracker), conviene hacer lo mismo acá para no
    // mantener dos copias divergiendo con el tiempo.
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        if (!hmdEnabled) return

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.remapCoordinateSystem(
            rotationMatrix,
            SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X,
            remappedMatrix
        )

        val q = FloatArray(4)
        matrixToQuaternion(remappedMatrix, q)

        if (refQuat == null) refQuat = floatArrayOf(q[0], q[1], q[2], q[3])
        val rq = refQuat!!

        val rw =  rq[0]; val rx = -rq[1]; val ry = -rq[2]; val rz = -rq[3]
        val w = rw*q[0] - rx*q[1] - ry*q[2] - rz*q[3]
        val x = rw*q[1] + rx*q[0] + ry*q[3] - rz*q[2]
        val y = rw*q[2] - rx*q[3] + ry*q[0] + rz*q[1]
        val z = rw*q[3] + rx*q[2] - ry*q[1] + rz*q[0]

        quatW =  w; quatX = -x; quatY = -y; quatZ = z
    }

    private fun matrixToQuaternion(m: FloatArray, q: FloatArray) {
        val m00=m[0];val m01=m[1];val m02=m[2]
        val m10=m[3];val m11=m[4];val m12=m[5]
        val m20=m[6];val m21=m[7];val m22=m[8]
        val trace = m00+m11+m22
        if (trace > 0) {
            val s = 0.5f / kotlin.math.sqrt(trace+1f)
            q[0]=0.25f/s; q[1]=(m21-m12)*s; q[2]=(m02-m20)*s; q[3]=(m10-m01)*s
        } else if (m00>m11 && m00>m22) {
            val s = 2f * kotlin.math.sqrt(1f+m00-m11-m22)
            q[0]=(m21-m12)/s; q[1]=0.25f*s; q[2]=(m01+m10)/s; q[3]=(m02+m20)/s
        } else if (m11>m22) {
            val s = 2f * kotlin.math.sqrt(1f+m11-m00-m22)
            q[0]=(m02-m20)/s; q[1]=(m01+m10)/s; q[2]=0.25f*s; q[3]=(m12+m21)/s
        } else {
            val s = 2f * kotlin.math.sqrt(1f+m22-m00-m11)
            q[0]=(m10-m01)/s; q[1]=(m02+m20)/s; q[2]=(m12+m21)/s; q[3]=0.25f*s
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}