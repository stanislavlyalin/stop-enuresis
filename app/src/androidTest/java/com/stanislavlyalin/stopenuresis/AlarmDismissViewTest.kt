package com.stanislavlyalin.stopenuresis

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.stanislavlyalin.stopenuresis.ui.AlarmDismissView
import org.junit.Assert.*
import org.junit.Test

class AlarmDismissViewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun withSlider(test: (AlarmDismissView, Float) -> Unit) {
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val density = context.resources.displayMetrics.density
            val view = AlarmDismissView(context)
            view.measure(View.MeasureSpec.makeMeasureSpec((320 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((112 * density).toInt(), View.MeasureSpec.EXACTLY))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            test(view, density)
        }
    }

    private fun touch(view: View, density: Float, action: Int, x: Float, y: Float = 56f): Boolean {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x * density, y * density, 0)
        return try { view.onTouchEvent(event) } finally { event.recycle() }
    }

    @Test
    fun onlyDraggingChildAllTheWayDismissesOnce() = withSlider { view, density ->
        var dismissed = 0
        view.onDismiss = { dismissed++ }
        assertFalse(touch(view, density, MotionEvent.ACTION_DOWN, 280f))
        assertFalse(touch(view, density, MotionEvent.ACTION_DOWN, 50f, 0f))
        assertTrue(touch(view, density, MotionEvent.ACTION_DOWN, 50f))
        touch(view, density, MotionEvent.ACTION_MOVE, 130f)
        assertEquals(0, dismissed)
        touch(view, density, MotionEvent.ACTION_UP, 270f)
        assertEquals(1, dismissed)
        view.performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun cancelAndShortDragDoNotDismissAndAccessibilityWorks() = withSlider { view, density ->
        var dismissed = 0
        view.onDismiss = { dismissed++ }
        touch(view, density, MotionEvent.ACTION_DOWN, 50f)
        touch(view, density, MotionEvent.ACTION_UP, 100f)
        assertEquals(0, dismissed)
        touch(view, density, MotionEvent.ACTION_DOWN, 100f)
        touch(view, density, MotionEvent.ACTION_CANCEL, 100f)
        assertEquals(0, dismissed)
        assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(1, dismissed)
        assertFalse(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
    }

    @Test
    fun liftingActiveFingerCancelsWithoutTransferringDrag() = withSlider { view, density ->
        var dismissed = 0
        view.onDismiss = { dismissed++ }
        touch(view, density, MotionEvent.ACTION_DOWN, 50f)
        val properties = Array(2) { id -> MotionEvent.PointerProperties().apply { this.id = id } }
        val coordinates = Array(2) { id -> MotionEvent.PointerCoords().apply {
            x = (50f + 150f * id) * density
            y = 56f * density
        } }
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_POINTER_UP, 2,
            properties, coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0)
        try { assertTrue(view.onTouchEvent(event)) } finally { event.recycle() }
        assertFalse(touch(view, density, MotionEvent.ACTION_MOVE, 270f))
        assertEquals(0, dismissed)
        view.performClick()
    }

    @Test
    fun disabledSliderCannotDismiss() = withSlider { view, _ ->
        view.onDismiss = { fail("Отключённый ползунок не должен выключать будильник") }
        view.isEnabled = false
        assertFalse(view.performClick())
    }
}
