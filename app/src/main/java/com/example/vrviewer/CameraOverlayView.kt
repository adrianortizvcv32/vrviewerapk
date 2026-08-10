package com.example.vrviewer

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View


class SkeletonOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {


    private var leftLandmarks:  List<PointF> = emptyList()
    private var rightLandmarks: List<PointF> = emptyList()


    private var leftBlob:  PointF? = null
    private var rightBlob: PointF? = null


    private val CONNECTIONS = listOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 4,           // pulgar
        0 to 5, 5 to 6, 6 to 7, 7 to 8,           // índice
        0 to 9, 9 to 10, 10 to 11, 11 to 12,      // medio
        0 to 13, 13 to 14, 14 to 15, 15 to 16,    // anular
        0 to 17, 17 to 18, 18 to 19, 19 to 20,    // meñique
        5 to 9, 9 to 13, 13 to 17                  // palma
    )

    private val paintLeft = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color  = Color.parseColor("#4ade80")   // verde
        style  = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val paintRight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color  = Color.parseColor("#60a5fa")   // azul
        style  = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val paintDotLeft = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4ade80")
        style = Paint.Style.FILL
    }
    private val paintDotRight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#60a5fa")
        style = Paint.Style.FILL
    }
    private val paintBlob = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.FILL
        strokeWidth = 3f
    }




    fun updateHandLandmarks(
        left:  List<PointF>,
        right: List<PointF>
    ) {
        leftLandmarks  = left
        rightLandmarks = right
        leftBlob       = null
        rightBlob      = null
        invalidate()
    }


    fun updateBlobs(left: PointF?, right: PointF?) {
        leftBlob       = left
        rightBlob      = right
        leftLandmarks  = emptyList()
        rightLandmarks = emptyList()
        invalidate()
    }

    fun clear() {
        leftLandmarks  = emptyList()
        rightLandmarks = emptyList()
        leftBlob       = null
        rightBlob      = null
        invalidate()
    }



    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        drawHand(canvas, leftLandmarks,  paintLeft,  paintDotLeft,  w, h)
        drawHand(canvas, rightLandmarks, paintRight, paintDotRight, w, h)


        leftBlob?.let {
            paintBlob.color = Color.parseColor("#4ade80")
            canvas.drawCircle(it.x * w, it.y * h, 28f, paintBlob)
            paintBlob.color = Color.parseColor("#052e16")
            paintBlob.style = Paint.Style.FILL
        }
        rightBlob?.let {
            paintBlob.color = Color.parseColor("#60a5fa")
            canvas.drawCircle(it.x * w, it.y * h, 28f, paintBlob)
        }
    }

    private fun drawHand(
        canvas: Canvas,
        landmarks: List<PointF>,
        linePaint: Paint,
        dotPaint:  Paint,
        w: Float, h: Float
    ) {
        if (landmarks.size < 21) return


        for ((a, b) in CONNECTIONS) {
            val pa = landmarks[a]
            val pb = landmarks[b]
            canvas.drawLine(pa.x * w, pa.y * h, pb.x * w, pb.y * h, linePaint)
        }

        for (pt in landmarks) {
            canvas.drawCircle(pt.x * w, pt.y * h, 6f, dotPaint)
        }

        canvas.drawCircle(landmarks[0].x * w, landmarks[0].y * h, 10f, dotPaint)
    }
}