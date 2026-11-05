package com.stanislavlyalin.stopenuresis.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.stanislavlyalin.stopenuresis.R
import kotlin.math.abs
import kotlin.math.min

/** Захват только за малыша; два кадра меняются по пройденному пути, а не по времени. */
class AlarmDismissView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var onDismiss: (() -> Unit)? = null
    private var completed = false
    private var progress = 0f
    private var pointer = MotionEvent.INVALID_POINTER_ID
    private var grabOffset = 0f
    private var walked = 0f
    private var animator: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val children = arrayOf(
        BitmapFactory.decodeResource(resources, R.drawable.alarm_child_step_right),
        BitmapFactory.decodeResource(resources, R.drawable.alarm_child_step_left)
    )
    private val potty = BitmapFactory.decodeResource(resources, R.drawable.alarm_potty)
    // Общая область кадров сохраняет положение головы и стоп при смене изображения.
    private val childSource = Rect(292, 72, 943, 1182)
    private val pottySource = Rect(192, 180, 1060, 1012)
    private val thumb = RectF()
    private val destination = RectF()
    private val inset get() = dp(6f)
    private val diameter get() = min(dp(88f), height - dp(16f))
    private val travel get() = (width - inset * 2 - diameter).coerceAtLeast(1f)

    init {
        isFocusable = true
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(R.string.turnOffAlarm)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(dp(320f).toInt(), widthMeasureSpec),
            resolveSize(dp(112f).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = (height - dp(16f)) / 2
        destination.set(dp(1f), dp(8f), width - dp(1f), height - dp(8f))
        paint.style = Paint.Style.FILL
        paint.color = context.getColor(R.color.alarm_track)
        canvas.drawRoundRect(destination, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        paint.color = context.getColor(R.color.brand_orange)
        canvas.drawRoundRect(destination, radius, radius, paint)
        paint.style = Paint.Style.FILL

        paint.color = context.getColor(R.color.alarm_cream)
        paint.alpha = (255 * (1f - progress * 2).coerceAtLeast(0f)).toInt()
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textSize = 16f * resources.displayMetrics.scaledDensity
        val label = context.getString(R.string.alarmDismissLabel)
        val room = (width - 2 * (diameter + inset + dp(8f))).coerceAtLeast(1f)
        if (paint.measureText(label) > room) paint.textSize *= room / paint.measureText(label)
        canvas.drawText(label, (width - paint.measureText(label)) / 2,
            height / 2f - (paint.ascent() + paint.descent()) / 2 - dp(4f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.strokeCap = Paint.Cap.ROUND
        val arrowY = height / 2f + dp(16f)
        for (offset in -1..1) {
            val x = width / 2f + offset * dp(12f)
            canvas.drawLine(x - dp(3f), arrowY - dp(4f), x + dp(1f), arrowY, paint)
            canvas.drawLine(x + dp(1f), arrowY, x - dp(3f), arrowY + dp(4f), paint)
        }
        paint.alpha = 255
        paint.style = Paint.Style.FILL
        val pottySize = diameter * .82f
        val pottyLeft = width - inset - (diameter + pottySize) / 2
        destination.set(pottyLeft, (height - pottySize) / 2, pottyLeft + pottySize, (height + pottySize) / 2)
        canvas.drawBitmap(potty, pottySource, destination, paint)

        updateThumb()
        paint.color = context.getColor(R.color.brand_orange)
        canvas.drawOval(thumb, paint)
        val childHeight = diameter * 1.12f
        val childWidth = childHeight * childSource.width() / childSource.height()
        destination.set(thumb.centerX() - childWidth / 2, (height - childHeight) / 2,
            thumb.centerX() + childWidth / 2, (height + childHeight) / 2)
        val frame = (walked / dp(STEP_DISTANCE_DP)).toInt() % children.size
        canvas.drawBitmap(children[frame], childSource, destination, paint)
    }

    private fun updateThumb() {
        val left = inset + travel * progress
        thumb.set(left, (height - diameter) / 2, left + diameter, (height + diameter) / 2)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (completed || !isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                updateThumb()
                if (!thumb.contains(event.x, event.y)) return false
                animator?.cancel()
                pointer = event.getPointerId(0)
                grabOffset = event.x - thumb.left
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                if (pointer == MotionEvent.INVALID_POINTER_ID) return false
                val index = event.findPointerIndex(pointer)
                if (index < 0) { cancelDrag(); return true }
                val next = ((event.getX(index) - grabOffset - inset) / travel).coerceIn(0f, 1f)
                walked += abs(next - progress) * travel
                progress = next
                invalidate()
                if (progress >= COMPLETION_PROGRESS) complete()
                else if (event.actionMasked == MotionEvent.ACTION_UP) cancelDrag()
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pointer) {
                    cancelDrag()
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelDrag()
                return true
            }
        }
        return pointer != MotionEvent.INVALID_POINTER_ID
    }

    private fun cancelDrag() {
        pointer = MotionEvent.INVALID_POINTER_ID
        parent?.requestDisallowInterceptTouchEvent(false)
        animator?.cancel()
        walked = 0f
        animator = ValueAnimator.ofFloat(progress, 0f).apply {
            duration = RETURN_DURATION_MS
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun complete() {
        if (completed || !isEnabled) return
        animator?.cancel()
        completed = true
        pointer = MotionEvent.INVALID_POINTER_ID
        progress = 1f
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidate()
        onDismiss?.invoke()
    }

    override fun performClick(): Boolean {
        if (completed || !isEnabled) return false
        super.performClick()
        complete()
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,
            context.getString(R.string.turnOffAlarm)))
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        return if (action == AccessibilityNodeInfo.ACTION_CLICK) performClick()
        else super.performAccessibilityAction(action, arguments)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) performClick()
        else super.onKeyUp(keyCode, event)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        pointer = MotionEvent.INVALID_POINTER_ID
        parent?.requestDisallowInterceptTouchEvent(false)
        super.onDetachedFromWindow()
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    companion object {
        private const val STEP_DISTANCE_DP = 12f
        private const val COMPLETION_PROGRESS = .985f
        private const val RETURN_DURATION_MS = 160L
    }
}
