package com.example.vrviewer

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.hypot


object HandGesture {


    private val FINGERS = listOf(
        5 to 8,   // índice   (índice 0 en el array de curls de abajo)
        9 to 12,  // medio
        13 to 16, // anular
        17 to 20  // meñique
    )
    private const val INDEX_FINGER_SLOT = 0 // posición del índice dentro de FINGERS

    private fun dist(a: NormalizedLandmark, b: NormalizedLandmark): Float =
        hypot((a.x() - b.x()).toDouble(), (a.y() - b.y()).toDouble()).toFloat()


    fun compute(landmarks: List<NormalizedLandmark>): Pair<Float, Float> {
        if (landmarks.size < 21) return 0f to 0f

        val wrist = landmarks[0]


        val handSize  = dist(wrist, landmarks[9]).coerceAtLeast(0.01f)
        val pinchDist = dist(landmarks[4], landmarks[8]) / handSize
        val pinch = (1f - pinchDist / 0.9f).coerceIn(0f, 1f)

        var curlSum = 0f
        for (i in FINGERS.indices) {
            val (mcpIdx, tipIdx) = FINGERS[i]
            val mcpDist = dist(wrist, landmarks[mcpIdx]).coerceAtLeast(0.001f)
            val tipDist = dist(wrist, landmarks[tipIdx])
            var curl = (2.0f - tipDist / mcpDist).coerceIn(0f, 1f)


            if (i == INDEX_FINGER_SLOT) {
                curl *= (1f - pinch)
            }
            curlSum += curl
        }
        val grip = curlSum / FINGERS.size

        return grip to pinch
    }
}