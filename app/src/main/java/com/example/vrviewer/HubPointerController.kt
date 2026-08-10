package com.example.vrviewer

/**
 * Interactúa con el CONTENIDO de la ventana del Menú Hub (la página
 * web) usando el dedo índice extendido — distinto del pellizco que
 * HubWindowController usa para mover/redimensionar el marco.
 *
 * Mientras se "apunta" dentro del área de la ventana, se reenvían
 * eventos de touch reales (DOWN/MOVE/UP) al WebView. Es el propio
 * WebView el que decide si fue un tap (poco movimiento) o un
 * scroll/drag (más movimiento) — igual que en una pantalla táctil.
 */
class HubPointerController(
    private val window: HubWindowController,
    private val onTouchDown: (u: Float, v: Float) -> Unit,
    private val onTouchMove: (u: Float, v: Float) -> Unit,
    private val onTouchUp: (u: Float, v: Float) -> Unit
) {
    private class HandState {
        var touching = false
        var lastU = 0.5f
        var lastV = 0.5f
    }

    private val leftState = HandState()
    private val rightState = HandState()

    companion object {
        private const val CURL_INDEX_EXTENDED_MAX = 0.35f
        private const val CURL_OTHERS_FOLDED_MIN  = 0.55f
        private const val PINCH_MAX_FOR_POINT     = 0.4f  // margen extra de seguridad
        private const val DEPTH_GATE              = 0.35f // margen generoso en Z (misma escala que winZ)
    }

    fun update(left: HandPose, right: HandPose) {
        // Si una mano está moviendo/redimensionando el marco, no
        // interpretamos gestos de apuntado (evita ambigüedad).
        if (window.isGrabbed()) {
            release(leftState)
            release(rightState)
            return
        }
        updateHand(left, leftState)
        updateHand(right, rightState)
    }

    private fun release(state: HandState) {
        if (state.touching) {
            state.touching = false
            onTouchUp(state.lastU, state.lastV)
        }
    }

    private fun updateHand(hand: HandPose, state: HandState) {
        if (!hand.tracked || !window.visible) { release(state); return }

        val insideXY = kotlin.math.abs(hand.x - window.screenX) < window.screenHalfW &&
                kotlin.math.abs(hand.y - window.screenY) < window.screenHalfH
        val insideZ = kotlin.math.abs(-hand.z - window.depthMeters) < DEPTH_GATE

        val isPointing = hand.curlIndex < CURL_INDEX_EXTENDED_MAX &&
                hand.curlMiddle > CURL_OTHERS_FOLDED_MIN &&
                hand.curlRing   > CURL_OTHERS_FOLDED_MIN &&
                hand.pinch < PINCH_MAX_FOR_POINT

        if (isPointing && insideXY && insideZ) {
            val u = (((hand.x - window.screenX) / window.screenHalfW) * 0.5f + 0.5f).coerceIn(0f, 1f)
            val v = (0.5f - ((hand.y - window.screenY) / window.screenHalfH) * 0.5f).coerceIn(0f, 1f)
            state.lastU = u; state.lastV = v
            if (!state.touching) { state.touching = true; onTouchDown(u, v) }
            else onTouchMove(u, v)
        } else {
            release(state)
        }
    }
}