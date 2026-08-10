package com.example.vrviewer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import kotlinx.coroutines.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.PI

class JoyConHidManager(
    context: Context,
    private val onState: (side: Side, state: JoyConState) -> Unit
) {
    enum class Side { LEFT, RIGHT }

    data class JoyConState(
        val trigger: Float = 0f,
        val grip: Float = 0f,
        val joyX: Float = 0f,
        val joyY: Float = 0f,
        val sysBtn: Boolean = false,
        val appBtn: Boolean = false,
        val clickBtn: Boolean = false,
        val qx: Float = 0f,
        val qy: Float = 0f,
        val qz: Float = 0f,
        val qw: Float = 1f,
        val hasGyro: Boolean = false,
        val connected: Boolean = false
    )

    companion object {
        private const val TAG = "JoyConHidManager"


        private const val PSM_HID_CONTROL = 0x11
        private const val PSM_HID_INTERRUPT = 0x13


        private const val OUT_RUMBLE_AND_SUBCMD: Byte = 0x01
        private const val OUT_RUMBLE_ONLY: Byte = 0x10


        private const val SUB_SET_REPORT_MODE: Byte = 0x03
        private const val SUB_SET_PLAYER_LIGHTS: Byte = 0x30
        private const val SUB_ENABLE_IMU: Byte = 0x40
        private const val SUB_SET_IMU_SENSITIVITY: Byte = 0x41


        private const val IN_REPORT_STANDARD_FULL: Int = 0x30


        private const val ACCEL_SCALE_G = 0.000244f
        private const val GYRO_SCALE_DPS = 0.06103f
        private const val DEG_TO_RAD = (PI / 180.0).toFloat()


        private const val IMU_SAMPLE_HZ = 200f

        fun looksLikeJoyCon(device: BluetoothDevice?): Boolean {
            val name = device?.name?.lowercase() ?: return false
            return name.contains("joy-con") || name.contains("joycon")
        }

        fun sideOf(device: BluetoothDevice): Side? {
            val name = device.name?.lowercase() ?: return null
            return when {
                name.contains("(l)") || name.contains(" l)") || name.contains("joycon l") -> Side.LEFT
                name.contains("(r)") || name.contains(" r)") || name.contains("joycon r") -> Side.RIGHT
                else -> null
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = HashMap<Side, Connection>()
    private val appContext = context.applicationContext

    private var discoveryReceiver: BroadcastReceiver? = null

    private inner class Connection(val device: BluetoothDevice, val side: Side) {
        var controlSocket: BluetoothSocket? = null
        var interruptSocket: BluetoothSocket? = null
        var interruptIn: InputStream? = null
        var interruptOut: OutputStream? = null
        var readJob: Job? = null
        var packetCounter = 0
        val madgwick = MadgwickAHRS(IMU_SAMPLE_HZ, beta = 0.08f)
        var state = JoyConState()
        var lastClickPressed = false
        var running = false
    }


    @SuppressLint("MissingPermission")
    fun pairedJoyCons(): List<BluetoothDevice> {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        return adapter.bondedDevices.filter { looksLikeJoyCon(it) }
    }


    fun startDiscovery(onFound: (BluetoothDevice) -> Unit) {
        stopDiscovery()
        val receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_FOUND) return
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (device != null && looksLikeJoyCon(device)) onFound(device)
            }
        }
        discoveryReceiver = receiver
        appContext.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
        BluetoothAdapter.getDefaultAdapter()?.startDiscovery()
    }

    fun stopDiscovery() {
        discoveryReceiver?.let {
            try { appContext.unregisterReceiver(it) } catch (_: IllegalArgumentException) {}
        }
        discoveryReceiver = null
        BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
    }


    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice, forceSide: Side? = null): Boolean {
        val side = forceSide ?: sideOf(device) ?: run {
            Log.w(TAG, "No se pudo determinar lado (L/R) de ${device.name}, usando RIGHT")
            Side.RIGHT
        }

        disconnect(side)

        val conn = Connection(device, side)
        connections[side] = conn

        return try {

            conn.controlSocket = openL2cap(device, PSM_HID_CONTROL)

            conn.interruptSocket = openL2cap(device, PSM_HID_INTERRUPT)

            val interrupt = conn.interruptSocket ?: throw IOException("Sin canal de interrupción")
            conn.interruptIn = interrupt.inputStream
            conn.interruptOut = interrupt.outputStream

            initializeJoyCon(conn)

            conn.running = true
            conn.readJob = scope.launch { readLoop(conn) }

            conn.state = conn.state.copy(connected = true)
            onState(side, conn.state)
            true
        } catch (e: IOException) {
            Log.e(TAG, "Error conectando Joy-Con ${device.name} ($side): ${e.message}")
            cleanup(conn)
            connections.remove(side)
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun openL2cap(device: BluetoothDevice, psm: Int): BluetoothSocket {

        val socket = device.createInsecureL2capChannel(psm)
        socket.connect()
        return socket
    }

    private fun initializeJoyCon(conn: Connection) {

        sendSubcommand(conn, SUB_SET_REPORT_MODE, byteArrayOf(IN_REPORT_STANDARD_FULL.toByte()))


        sendSubcommand(conn, SUB_SET_IMU_SENSITIVITY, byteArrayOf(0x00, 0x00, 0x00, 0x00))

        sendSubcommand(conn, SUB_ENABLE_IMU, byteArrayOf(0x01))


        sendSubcommand(conn, SUB_SET_PLAYER_LIGHTS, byteArrayOf(0x01))
    }

    private fun sendSubcommand(conn: Connection, subcommandId: Byte, data: ByteArray) {
        val out = conn.interruptOut ?: return
        val buf = ByteArray(0x40)
        buf[0] = OUT_RUMBLE_AND_SUBCMD
        buf[1] = (conn.packetCounter and 0x0F).toByte()
        conn.packetCounter = (conn.packetCounter + 1) and 0x0F

        val neutralRumble = byteArrayOf(0x00, 0x01, 0x40.toByte(), 0x40.toByte(), 0x00, 0x01, 0x40.toByte(), 0x40.toByte())
        System.arraycopy(neutralRumble, 0, buf, 2, 8)
        buf[10] = subcommandId
        System.arraycopy(data, 0, buf, 11, minOf(data.size, buf.size - 11))
        try {
            out.write(buf)
            out.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Error enviando subcomando 0x${subcommandId.toString(16)}: ${e.message}")
        }
    }



    private suspend fun readLoop(conn: Connection) {
        val input = conn.interruptIn ?: return
        val buf = ByteArray(64)
        while (conn.running) {
            try {
                val n = input.read(buf)
                if (n <= 0) continue
                if ((buf[0].toInt() and 0xFF) == IN_REPORT_STANDARD_FULL) {
                    handleFullReport(conn, buf)
                }

            } catch (e: IOException) {
                Log.w(TAG, "Conexión perdida con ${conn.device.name}: ${e.message}")
                withContext(Dispatchers.Main.immediate) {
                    conn.state = conn.state.copy(connected = false, hasGyro = false)
                    onState(conn.side, conn.state)
                }
                break
            }
        }
    }

    private fun handleFullReport(conn: Connection, buf: ByteArray) {

        val rightBtn = buf[3].toInt() and 0xFF
        val sharedBtn = buf[4].toInt() and 0xFF
        val leftBtn = buf[5].toInt() and 0xFF

        val isLeft = conn.side == Side.LEFT

        val stickOffset = if (isLeft) 6 else 9
        val s0 = buf[stickOffset].toInt() and 0xFF
        val s1 = buf[stickOffset + 1].toInt() and 0xFF
        val s2 = buf[stickOffset + 2].toInt() and 0xFF
        val rawX = (s0 or ((s1 and 0xF) shl 8))
        val rawY = ((s1 shr 4) or (s2 shl 4))

        var joyX = ((rawX - 2048) / 1536f).coerceIn(-1f, 1f)
        var joyY = ((rawY - 2048) / 1536f).coerceIn(-1f, 1f)
        if (kotlin.math.abs(joyX) < 0.08f) joyX = 0f
        if (kotlin.math.abs(joyY) < 0.08f) joyY = 0f


        val grip: Float
        val trigger: Float
        val clickBtn: Boolean
        val sysBtn: Boolean
        val appBtn: Boolean

        if (isLeft) {
            grip = if ((leftBtn and 0x40) != 0) 1f else 0f      // L
            trigger = if ((leftBtn and 0x80) != 0) 1f else 0f   // ZL
            clickBtn = (sharedBtn and 0x08) != 0                // L stick press
            sysBtn = (sharedBtn and 0x01) != 0                  // Minus
            appBtn = false
        } else {
            grip = if ((rightBtn and 0x40) != 0) 1f else 0f     // R
            trigger = if ((rightBtn and 0x80) != 0) 1f else 0f  // ZR
            clickBtn = (sharedBtn and 0x04) != 0                // R stick press
            sysBtn = false
            appBtn = (sharedBtn and 0x02) != 0                  // Plus
        }


        if (clickBtn && !conn.lastClickPressed) {
            conn.madgwick.reset()
        }
        conn.lastClickPressed = clickBtn


        var hadAnySample = false
        for (sample in 0 until 3) {
            val base = 13 + sample * 12
            if (base + 12 > buf.size) break

            val accX = readInt16LE(buf, base)
            val accY = readInt16LE(buf, base + 2)
            val accZ = readInt16LE(buf, base + 4)
            val gyr1 = readInt16LE(buf, base + 6)
            val gyr2 = readInt16LE(buf, base + 8)
            val gyr3 = readInt16LE(buf, base + 10)

            val ax = accX * ACCEL_SCALE_G
            val ay = accY * ACCEL_SCALE_G
            val az = accZ * ACCEL_SCALE_G

            val gx = gyr1 * GYRO_SCALE_DPS * DEG_TO_RAD
            val gy = gyr2 * GYRO_SCALE_DPS * DEG_TO_RAD
            val gz = gyr3 * GYRO_SCALE_DPS * DEG_TO_RAD

            conn.madgwick.update(gx, gy, gz, ax, ay, az)

            val magDps = kotlin.math.abs(gyr1) + kotlin.math.abs(gyr2) + kotlin.math.abs(gyr3)
            if (magDps * GYRO_SCALE_DPS > 3f) hadAnySample = true
        }

        val q = conn.madgwick.quaternion() // [w, x, y, z]

        conn.state = conn.state.copy(
            trigger = trigger,
            grip = grip,
            joyX = joyX,
            joyY = joyY,
            sysBtn = sysBtn,
            appBtn = appBtn,
            clickBtn = clickBtn,
            qw = q[0], qx = q[1], qy = q[2], qz = q[3],
            hasGyro = hadAnySample,
            connected = true
        )
        onState(conn.side, conn.state)
    }

    private fun readInt16LE(buf: ByteArray, offset: Int): Int {
        val lo = buf[offset].toInt() and 0xFF
        val hi = buf[offset + 1].toInt()
        return (hi shl 8) or lo
    }


    fun recenter(side: Side) {
        connections[side]?.madgwick?.reset()
    }

    fun recenterBoth() {
        recenter(Side.LEFT)
        recenter(Side.RIGHT)
    }



    fun disconnect(side: Side) {
        connections[side]?.let { conn ->
            conn.running = false
            conn.readJob?.cancel()
            cleanup(conn)
            conn.state = conn.state.copy(connected = false, hasGyro = false)
            onState(side, conn.state)
        }
        connections.remove(side)
    }

    fun disconnectAll() {
        connections.keys.toList().forEach { disconnect(it) }
    }

    private fun cleanup(conn: Connection) {
        try { conn.interruptIn?.close() } catch (_: IOException) {}
        try { conn.interruptOut?.close() } catch (_: IOException) {}
        try { conn.interruptSocket?.close() } catch (_: IOException) {}
        try { conn.controlSocket?.close() } catch (_: IOException) {}
    }

    fun isConnected(side: Side): Boolean = connections[side]?.state?.connected == true
}