package com.stanislavlyalin.stopenuresis.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(190, 190, 190)
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 150, 0)
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }
    private val inactivePath = Path()
    private val activePath = Path()

    private var waveform: FloatArray = FloatArray(0)
    private var maxAmplitude = 1f
    private var progress = 0f

    fun setWaveform(values: FloatArray) {
        waveform = values
        maxAmplitude = values.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        invalidate()
    }

    fun setProgress(value: Float) {
        progress = value.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (waveform.isEmpty()) {
            drawFlatLine(canvas, inactivePaint, 0f, width.toFloat())
            return
        }

        buildPath(inactivePath, 0, waveform.lastIndex)
        canvas.drawPath(inactivePath, inactivePaint)

        val activeLastIndex = (waveform.lastIndex * progress).toInt()
        if (activeLastIndex > 0) {
            buildPath(activePath, 0, activeLastIndex)
            canvas.drawPath(activePath, activePaint)
        }
    }

    private fun buildPath(path: Path, startIndex: Int, endIndex: Int) {
        path.reset()
        if (width <= 0 || height <= 0) return

        val horizontalStep = width.toFloat() / max(1, waveform.lastIndex)
        for (index in startIndex..endIndex) {
            val x = index * horizontalStep
            val y = amplitudeToY(waveform[index])
            if (index == startIndex) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
    }

    private fun amplitudeToY(value: Float): Float {
        val verticalPadding = inactivePaint.strokeWidth / 2f
        val availableHeight = (height - inactivePaint.strokeWidth).coerceAtLeast(1f)
        val normalized = (value / maxAmplitude).coerceIn(0f, 1f)
        return verticalPadding + availableHeight * (1f - normalized)
    }

    private fun drawFlatLine(canvas: Canvas, paint: Paint, startX: Float, endX: Float) {
        val centerY = height / 2f
        canvas.drawLine(startX, centerY, endX, centerY, paint)
    }
}
