package com.stanislavlyalin.stopenuresis.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.stanislavlyalin.stopenuresis.audio.AudioWaveformColumn
import kotlin.math.abs
import kotlin.math.max

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(com.stanislavlyalin.stopenuresis.R.color.ui_divider)
        strokeWidth = 2f
        style = Paint.Style.FILL
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(com.stanislavlyalin.stopenuresis.R.color.brand_orange)
        strokeWidth = 2f
        style = Paint.Style.FILL
    }
    private val barRect = RectF()

    private var waveform: List<AudioWaveformColumn> = emptyList()
    private var maxAmplitude = 1f
    private var progress = 0f

    fun setWaveform(values: List<AudioWaveformColumn>) {
        waveform = values
        maxAmplitude = calculateMaxAmplitude(values)
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

        drawBars(canvas, inactivePaint)

        val activeWidth = width * progress
        if (activeWidth > 0f) {
            val checkpoint = canvas.save()
            canvas.clipRect(0f, 0f, activeWidth, height.toFloat())
            drawBars(canvas, activePaint)
            canvas.restoreToCount(checkpoint)
        }
    }

    private fun drawBars(canvas: Canvas, paint: Paint) {
        if (width <= 0 || height <= 0) return

        val step = width.toFloat() / waveform.size.toFloat()
        val gap = 1f.coerceAtMost(step / 3f)
        val barWidth = max(1f, step - gap)
        val cornerRadius = barWidth / 2f

        for (index in waveform.indices) {
            val column = waveform[index]
            val left = index * step + gap / 2f
            val right = (left + barWidth).coerceAtMost(width.toFloat())
            val minY = sampleToY(column.minAmplitude)
            val maxY = sampleToY(column.maxAmplitude)
            var top = minOf(minY, maxY)
            var bottom = maxOf(minY, maxY)
            if (bottom - top < MIN_BAR_HEIGHT_PX) {
                val center = (top + bottom) / 2f
                top = (center - MIN_BAR_HEIGHT_PX / 2f).coerceAtLeast(0f)
                bottom = (top + MIN_BAR_HEIGHT_PX).coerceAtMost(height.toFloat())
                top = bottom - MIN_BAR_HEIGHT_PX
            }
            if (right <= left) {
                continue
            }

            barRect.set(left, top, right, bottom)
            canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, paint)
        }
    }

    private fun sampleToY(value: Float): Float {
        val verticalPadding = 1f
        val centerY = height / 2f
        val availableHalfHeight = (height / 2f - verticalPadding).coerceAtLeast(1f)
        val normalized = (value / maxAmplitude).coerceIn(-1f, 1f)
        return centerY - normalized * availableHalfHeight
    }

    private fun drawFlatLine(canvas: Canvas, paint: Paint, startX: Float, endX: Float) {
        val centerY = height / 2f
        canvas.drawLine(startX, centerY, endX, centerY, paint)
    }

    private fun calculateMaxAmplitude(values: List<AudioWaveformColumn>): Float {
        var maxValue = 1f
        for (column in values) {
            maxValue = max(maxValue, abs(column.minAmplitude))
            maxValue = max(maxValue, abs(column.maxAmplitude))
        }
        return maxValue
    }

    private companion object {
        const val MIN_BAR_HEIGHT_PX = 2f
    }
}
