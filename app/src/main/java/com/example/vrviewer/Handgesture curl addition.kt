import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.acos
import kotlin.math.sqrt
import kotlin.math.PI

object HandGestureCurl {

    /** @return FloatArray(5): [thumb, index, middle, ring, pinky], cada uno 0f..1f */
    fun computeCurls(landmarks: List<NormalizedLandmark>): FloatArray {
        // (base, articulación media, punta) por dedo
        val fingerJoints = arrayOf(
            intArrayOf(1, 2, 4),    // thumb
            intArrayOf(5, 6, 8),    // index
            intArrayOf(9, 10, 12),  // middle
            intArrayOf(13, 14, 16), // ring
            intArrayOf(17, 18, 20)  // pinky
        )

        val curls = FloatArray(5)
        for (f in fingerJoints.indices) {
            val (aIdx, bIdx, cIdx) = fingerJoints[f]
            val a = landmarks[aIdx]
            val b = landmarks[bIdx]
            val c = landmarks[cIdx]

            val v1x = b.x() - a.x(); val v1y = b.y() - a.y(); val v1z = b.z() - a.z()
            val v2x = c.x() - b.x(); val v2y = c.y() - b.y(); val v2z = c.z() - b.z()

            val len1 = sqrt(v1x * v1x + v1y * v1y + v1z * v1z)
            val len2 = sqrt(v2x * v2x + v2y * v2y + v2z * v2z)
            if (len1 < 1e-6f || len2 < 1e-6f) { curls[f] = 0f; continue }

            val dot = (v1x * v2x + v1y * v2y + v1z * v2z) / (len1 * len2)
            val angle = acos(dot.coerceIn(-1f, 1f)) // radianes: 0 = recto, ~PI = doblado al máximo


            val maxBendRad = (110.0 * PI / 180.0).toFloat()
            curls[f] = (angle / maxBendRad).coerceIn(0f, 1f)
        }
        return curls
    }
}

