package com.example.turncounter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class GraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val points = ArrayDeque<Float>()
    private val maxPoints = 160
    private var threshold = 0f

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1565C0")
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val thresholdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D32F2F")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DDDDDD")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val path = Path()

    fun addPoint(value: Float) {
        points.addLast(value)
        while (points.size > maxPoints) {
            points.removeFirst()
        }
        postInvalidate()
    }

    fun setThreshold(value: Float) {
        threshold = value
        postInvalidate()
    }

    fun clear() {
        points.clear()
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (width == 0 || height == 0) return

        canvas.drawColor(Color.parseColor("#FAFAFA"))

        canvas.drawLine(
            0f,
            height / 2f,
            width.toFloat(),
            height / 2f,
            gridPaint
        )

        if (points.isEmpty()) return

        val maxValue = points.maxOrNull() ?: 1f
        val maxY = max(1f, maxValue * 1.15f)

        if (threshold > 0f) {
            val thresholdValue = threshold.coerceAtMost(maxY)
            val thresholdY = height - (thresholdValue / maxY) * height

            canvas.drawLine(
                0f,
                thresholdY,
                width.toFloat(),
                thresholdY,
                thresholdPaint
            )
        }

        if (points.size < 2) return

        path.reset()

        val step = width.toFloat() / (maxPoints - 1)
        val startX = width - step * (points.size - 1)

        points.forEachIndexed { index, value ->
            val x = startX + index * step
            val y = height - (value.coerceAtLeast(0f) / maxY) * height

            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        canvas.drawPath(path, linePaint)
    }
}
