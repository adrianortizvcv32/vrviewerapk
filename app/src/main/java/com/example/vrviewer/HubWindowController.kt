package com.example.vrviewer

import kotlin.math.hypot

/**
 * Controla la ventana flotante del Menú Hub, ahora ANCLADA en el espacio
 * MUNDO de ARCore (world-locked), no pegada a la pantalla (screen-locked).
 *
 * Mientras nadie la sujeta, worldX/worldY/worldZ NO cambian: por eso, al
 * moverte físicamente (6DoF real), la ventana se queda fija en el mismo
 * punto del cuarto, como cualquier objeto anclado en la escena ARCore.
 * Cada frame solo se recalcula su posición/tamaño APARENTE en pantalla
 * (screenX/Y/halfW/halfH) proyectando ese punto fijo con la pose real de
 * cámara — de ahí sale el paralaje real entre ambos ojos.
 *
 * Al pellizcar (mover/redimensionar) se hace el proceso inverso: se toma
 * la posición de pantalla que sigue a la mano y se "des-proyecta" a un
 * punto del mundo con la pose de cámara del momento, así al soltar queda
 * anclada donde la dejaste.
 *
 * FIX aspect ratio: el WebView interno de HubBrowserView es 800x500 px
 * (relación 1.6). baseHalfHeightM se deriva de baseHalfWidthM con esa
 * misma relación (0.34 / 1.6 = 0.2125) para que el fragment shader, que
 * mapea el bitmap completo sin preservar aspecto, no estire la página.
 * Si en algún momento cambia widthPx/heightPx en HubBrowserView, hay que
 * recalcular este valor a juego (y el de StereoGLRenderer.BASE_HALF_H).
 */
class HubWindowController(
    private val baseHalfWidthM: Float = 0.48f,
    private val baseHalfHeightM: Float = 0.27f,   // 0.48 / 1.778 ≈ ratio real de HubBrowserView (2560x1440)
    private val halfFovXTan: Float = 0.62f,
    private val halfFovYTan: Float = 0.62f,
    private val eyeSeparationM: Float = 0.064f
) {
    @Volatile var worldX = 0f; private set
    @Volatile var worldY = 0f; private set
    @Volatile var worldZ = 0f; private set
    private var hasAnchor = false

    @Volatile var scale = 1f; private set

    @Volatile var screenX = 0f; private set
    @Volatile var screenY = 0f; private set
    @Volatile var screenHalfW = baseHalfWidthM; private set
    @Volatile var screenHalfH = baseHalfHeightM; private set
    @Volatile var screenParallax = 0f; private set
    @Volatile var depthMeters = 0.5f; private set
    @Volatile var visible = false; private set   // false si el ancla quedó detrás de la cámara

    private enum class Hand { NONE, LEFT, RIGHT }
    private enum class Mode { NONE, MOVE, RESIZE }
    private var grabbedBy = Hand.NONE
    private var mode = Mode.NONE

    private var grabOffsetX = 0f
    private var grabOffsetY = 0f

    private var resizeAnchorX = 0f
    private var resizeAnchorY = 0f
    private var anchorOnLeft = false
    private var anchorOnTop = false
    private var resizeStartDist = 0f
    private var resizeStartScale = 1f

    companion object {
        private const val PINCH_START_THRESHOLD   = 0.62f
        private const val PINCH_RELEASE_THRESHOLD = 0.35f
        private const val GRAB_MARGIN = 0.08f
        private const val CORNER_ZONE = 0.09f
        private const val MIN_SCALE = 0.4f
        private const val MAX_SCALE = 3.0f
        private const val Z_DRAG_RESPONSIVENESS = 0.35f
        private const val DEFAULT_DISTANCE_M = 0.5f
    }

    /** Llamar cada frame con la pose REAL de la cámara ARCore (metros +
     *  cuaternión, en coordenadas mundo de la sesión). */
    fun update(left: HandPose, right: HandPose, camPos: FloatArray, camQuat: FloatArray) {
        if (!hasAnchor) placeInFrontOfCamera(camPos, camQuat)
        projectAnchorToScreen(camPos, camQuat)

        when (grabbedBy) {
            Hand.NONE -> {
                if (left.tracked && left.pinch >= PINCH_START_THRESHOLD && isNear(left)) {
                    startGrab(Hand.LEFT, left)
                } else if (right.tracked && right.pinch >= PINCH_START_THRESHOLD && isNear(right)) {
                    startGrab(Hand.RIGHT, right)
                }
            }
            Hand.LEFT -> {
                if (!left.tracked || left.pinch < PINCH_RELEASE_THRESHOLD) release()
                else drive(left, camPos, camQuat)
            }
            Hand.RIGHT -> {
                if (!right.tracked || right.pinch < PINCH_RELEASE_THRESHOLD) release()
                else drive(right, camPos, camQuat)
            }
        }
    }

    fun isGrabbed(): Boolean = grabbedBy != Hand.NONE

    private fun placeInFrontOfCamera(camPos: FloatArray, camQuat: FloatArray) {
        val world = CameraMath.cameraToWorld(camPos, camQuat, floatArrayOf(0f, 0f, -DEFAULT_DISTANCE_M))
        worldX = world[0]; worldY = world[1]; worldZ = world[2]
        hasAnchor = true
    }

    private fun projectAnchorToScreen(camPos: FloatArray, camQuat: FloatArray) {
        val worldPos = floatArrayOf(worldX, worldY, worldZ)
        val local = CameraMath.worldToCamera(camPos, camQuat, worldPos)
        val proj = CameraMath.cameraToScreen(local, halfFovXTan, halfFovYTan)
        if (proj == null) { visible = false; return }
        visible = true
        screenX = proj[0]; screenY = proj[1]; depthMeters = proj[2]
        screenHalfW = baseHalfWidthM * scale / (depthMeters * halfFovXTan)
        screenHalfH = baseHalfHeightM * scale / (depthMeters * halfFovYTan)

        // Paralaje REAL: proyectar el mismo punto desde cada ojo (offset
        // ±IPD/2 sobre el vector "derecha" de la cámara actual).
        val right = CameraMath.rotate(camQuat, floatArrayOf(1f, 0f, 0f))
        val half = eyeSeparationM / 2f
        val leftEyePos  = floatArrayOf(camPos[0]-right[0]*half, camPos[1]-right[1]*half, camPos[2]-right[2]*half)
        val rightEyePos = floatArrayOf(camPos[0]+right[0]*half, camPos[1]+right[1]*half, camPos[2]+right[2]*half)
        val projL = CameraMath.cameraToScreen(CameraMath.worldToCamera(leftEyePos, camQuat, worldPos), halfFovXTan, halfFovYTan)
        val projR = CameraMath.cameraToScreen(CameraMath.worldToCamera(rightEyePos, camQuat, worldPos), halfFovXTan, halfFovYTan)
        screenParallax = if (projL != null && projR != null) (projR[0] - projL[0]) / 2f else 0f
        // Si se ve invertido (doble contorno raro), prueba con eyeSeparationM negativo.
    }

    private fun isNear(hand: HandPose): Boolean =
        kotlin.math.abs(hand.x - screenX) < screenHalfW + GRAB_MARGIN &&
                kotlin.math.abs(hand.y - screenY) < screenHalfH + GRAB_MARGIN

    private fun nearestCorner(hand: HandPose): Pair<Float, Float>? {
        val corners = listOf(
            screenX - screenHalfW to screenY - screenHalfH,
            screenX + screenHalfW to screenY - screenHalfH,
            screenX - screenHalfW to screenY + screenHalfH,
            screenX + screenHalfW to screenY + screenHalfH
        )
        var best: Pair<Float, Float>? = null
        var bestDist = Float.MAX_VALUE
        for ((cx, cy) in corners) {
            val d = hypot((hand.x - cx).toDouble(), (hand.y - cy).toDouble()).toFloat()
            if (d < bestDist) { bestDist = d; best = cx to cy }
        }
        return if (bestDist < CORNER_ZONE) best else null
    }

    private fun startGrab(hand: Hand, pose: HandPose) {
        grabbedBy = hand
        val corner = nearestCorner(pose)
        if (corner != null) {
            mode = Mode.RESIZE
            anchorOnLeft = corner.first > screenX
            anchorOnTop  = corner.second > screenY
            resizeAnchorX = if (anchorOnLeft) screenX - screenHalfW else screenX + screenHalfW
            resizeAnchorY = if (anchorOnTop)  screenY - screenHalfH else screenY + screenHalfH
            resizeStartDist = hypot(
                (pose.x - resizeAnchorX).toDouble(), (pose.y - resizeAnchorY).toDouble()
            ).toFloat().coerceAtLeast(0.001f)
            resizeStartScale = scale
        } else {
            mode = Mode.MOVE
            grabOffsetX = pose.x - screenX
            grabOffsetY = pose.y - screenY
        }
    }

    private fun drive(pose: HandPose, camPos: FloatArray, camQuat: FloatArray) {
        when (mode) {
            Mode.MOVE -> {
                val desiredScreenX = pose.x - grabOffsetX
                val desiredScreenY = pose.y - grabOffsetY
                val desiredDepth = (depthMeters * (1f - Z_DRAG_RESPONSIVENESS) +
                        (-pose.z) * Z_DRAG_RESPONSIVENESS).coerceAtLeast(0.15f)
                reanchorFromScreen(desiredScreenX, desiredScreenY, desiredDepth, camPos, camQuat)
            }
            Mode.RESIZE -> {
                val dist = hypot(
                    (pose.x - resizeAnchorX).toDouble(), (pose.y - resizeAnchorY).toDouble()
                ).toFloat().coerceAtLeast(0.001f)
                scale = (resizeStartScale * (dist / resizeStartDist)).coerceIn(MIN_SCALE, MAX_SCALE)
                val newHalfW = baseHalfWidthM * scale / (depthMeters * halfFovXTan)
                val newHalfH = baseHalfHeightM * scale / (depthMeters * halfFovYTan)
                val newCenterX = if (anchorOnLeft) resizeAnchorX + newHalfW else resizeAnchorX - newHalfW
                val newCenterY = if (anchorOnTop)  resizeAnchorY + newHalfH else resizeAnchorY - newHalfH
                reanchorFromScreen(newCenterX, newCenterY, depthMeters, camPos, camQuat)
            }
            Mode.NONE -> {}
        }
    }

    private fun reanchorFromScreen(ndcX: Float, ndcY: Float, depth: Float, camPos: FloatArray, camQuat: FloatArray) {
        val local = CameraMath.screenToCameraLocal(ndcX, ndcY, depth, halfFovXTan, halfFovYTan)
        val world = CameraMath.cameraToWorld(camPos, camQuat, local)
        worldX = world[0]; worldY = world[1]; worldZ = world[2]
        screenX = ndcX; screenY = ndcY; depthMeters = depth
    }

    private fun release() { grabbedBy = Hand.NONE; mode = Mode.NONE }

    /** Vuelve a colocar la ventana 0.5m en frente de la cámara ACTUAL. */
    fun recenter() {
        grabbedBy = Hand.NONE
        mode = Mode.NONE
        hasAnchor = false
        scale = 1f
    }
}