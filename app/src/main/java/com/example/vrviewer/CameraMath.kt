package com.example.vrviewer

/**
 * Utilidades de matemática de cámara para anclar la ventana del Menú Hub
 * en el espacio MUNDO de ARCore (en vez de en espacio de pantalla fijo).
 * Convención ARCore: la cámara mira hacia su -Z local, +X derecha,
 * +Y arriba (igual que OpenGL). qx,qy,qz,qw es la orientación de la
 * cámara en coordenadas mundo.
 */
object CameraMath {

    fun rotate(q: FloatArray, v: FloatArray): FloatArray {
        val qx = q[0]; val qy = q[1]; val qz = q[2]; val qw = q[3]
        val vx = v[0]; val vy = v[1]; val vz = v[2]
        val tx = 2f * (qy * vz - qz * vy)
        val ty = 2f * (qz * vx - qx * vz)
        val tz = 2f * (qx * vy - qy * vx)
        return floatArrayOf(
            vx + qw * tx + (qy * tz - qz * ty),
            vy + qw * ty + (qz * tx - qx * tz),
            vz + qw * tz + (qx * ty - qy * tx)
        )
    }

    private fun conj(q: FloatArray) = floatArrayOf(-q[0], -q[1], -q[2], q[3])

    /** Mundo -> local de cámara (para proyectar el ancla a pantalla). */
    fun worldToCamera(camPos: FloatArray, camQuat: FloatArray, worldPos: FloatArray): FloatArray {
        val rel = floatArrayOf(worldPos[0]-camPos[0], worldPos[1]-camPos[1], worldPos[2]-camPos[2])
        return rotate(conj(camQuat), rel)
    }

    /** Local de cámara -> mundo (para re-anclar tras arrastrar/redimensionar). */
    fun cameraToWorld(camPos: FloatArray, camQuat: FloatArray, local: FloatArray): FloatArray {
        val r = rotate(camQuat, local)
        return floatArrayOf(r[0]+camPos[0], r[1]+camPos[1], r[2]+camPos[2])
    }

    /** Proyección tipo pinhole a NDC aproximado. null si queda detrás de la cámara. */
    fun cameraToScreen(local: FloatArray, halfFovXTan: Float, halfFovYTan: Float): FloatArray? {
        val depth = -local[2]
        if (depth < 0.05f) return null
        return floatArrayOf((local[0] / depth) / halfFovXTan, (local[1] / depth) / halfFovYTan, depth)
    }

    fun screenToCameraLocal(ndcX: Float, ndcY: Float, depth: Float, halfFovXTan: Float, halfFovYTan: Float): FloatArray =
        floatArrayOf(ndcX * halfFovXTan * depth, ndcY * halfFovYTan * depth, -depth)
}