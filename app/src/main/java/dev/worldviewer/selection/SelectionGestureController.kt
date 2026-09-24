package dev.worldviewer.selection

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import dev.worldviewer.reader.TextBlockAdapter
import kotlin.math.hypot

class SelectionGestureController(
    private val reader: RecyclerView,
    private val adapter: TextBlockAdapter,
    private val text: SelectionTextNavigator,
    private val confirmationDelayMillis: Long,
    private val onSelectionChanged: (SelectionRange?) -> Unit = {},
    private val onSelectionCompleted: (SelectionRange?) -> Unit = {},
) : RecyclerView.SimpleOnItemTouchListener(), View.OnAttachStateChangeListener {
    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(reader.context).scaledTouchSlop.toFloat()
    private var machine: SelectionStateMachine? = null
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    private val confirmRunnable = Runnable {
        val transition = machine?.advanceTime(downTime + confirmationDelayMillis) ?: return@Runnable
        if (SelectionEffect.CONFIRM_HAPTIC in transition.effects) {
            reader.stopScroll()
            reader.parent?.requestDisallowInterceptTouchEvent(true)
            reader.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            applySelection(transition)
            Log.i(
                TAG,
                "confirmed delay=$confirmationDelayMillis actual=${SystemClock.uptimeMillis() - downTime} " +
                    "range=${transition.rangeOrNull()}",
            )
        }
    }

    init {
        reader.addOnItemTouchListener(this)
        reader.addOnAttachStateChangeListener(this)
    }

    override fun onInterceptTouchEvent(recyclerView: RecyclerView, event: MotionEvent): Boolean {
        if (event.pointerCount > 1) {
            cancel(clearSelection = true)
            return false
        }
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                start(event)
                false
            }
            MotionEvent.ACTION_MOVE -> handleMove(event)
            MotionEvent.ACTION_UP -> finishIfConfirmed()
            MotionEvent.ACTION_CANCEL -> {
                cancel(clearSelection = true)
                false
            }
            else -> false
        }
    }

    override fun onTouchEvent(recyclerView: RecyclerView, event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> handleMove(event)
            MotionEvent.ACTION_UP -> finishIfConfirmed()
            MotionEvent.ACTION_CANCEL -> cancel(clearSelection = true)
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit

    override fun onViewAttachedToWindow(view: View) = Unit

    override fun onViewDetachedFromWindow(view: View) {
        dispose()
    }

    fun dispose() {
        handler.removeCallbacks(confirmRunnable)
        reader.removeOnItemTouchListener(this)
        reader.removeOnAttachStateChangeListener(this)
        reader.parent?.requestDisallowInterceptTouchEvent(false)
        machine = null
    }

    fun adjust(edge: SelectionEdge, direction: Int) {
        require(direction == -1 || direction == 1)
        val current = machine ?: return
        val completed = current.state as? SelectionState.Completed ?: return
        val range = completed.range
        try {
            val target = when (edge) {
                SelectionEdge.START -> if (direction < 0) {
                    if (range.startUtf16 == 0) return
                    text.previousCharacterStart(range.startUtf16)
                } else {
                    val next = text.nextCharacterEnd(range.startUtf16)
                    if (next >= range.endUtf16) return
                    next
                }
                SelectionEdge.END -> if (direction < 0) {
                    val newEnd = text.previousCharacterStart(range.endUtf16)
                    if (newEnd <= range.startUtf16) return
                    text.previousCharacterStart(newEnd)
                } else {
                    if (range.endUtf16 >= text.utf16Length) return
                    range.endUtf16
                }
            }
            current.beginAdjustment(edge)
            current.move(target, exceededTouchSlop = false)
            val transition = current.release()
            applySelection(transition)
            onSelectionCompleted(transition.rangeOrNull())
            Log.i(TAG, "adjusted edge=$edge direction=$direction range=${transition.rangeOrNull()}")
        } catch (error: SelectionTextUnavailableException) {
            if (current.state is SelectionState.Adjusting) {
                applySelection(current.release())
            }
            Log.i(TAG, "adjustment ignored: ${error.message}")
        }
    }

    fun cancelSelection() {
        cancel(clearSelection = true)
        Log.i(TAG, "cancelled")
    }

    fun restoreSelection(range: SelectionRange) {
        try {
            machine = SelectionStateMachine(text, confirmationDelayMillis).also { restored ->
                applySelection(restored.restore(range))
            }
            onSelectionCompleted(range)
            Log.i(TAG, "restored range=$range")
        } catch (error: SelectionTextUnavailableException) {
            machine = null
            Log.i(TAG, "restore ignored: ${error.message}")
        }
    }

    fun completedSelection(): SelectionRange? =
        (machine?.state as? SelectionState.Completed)?.range

    private fun start(event: MotionEvent) {
        handler.removeCallbacks(confirmRunnable)
        val offset = globalOffset(event) ?: return
        try {
            machine = SelectionStateMachine(text, confirmationDelayMillis).also {
                it.press(offset, event.eventTime)
            }
        } catch (error: SelectionTextUnavailableException) {
            machine = null
            Log.i(TAG, "press ignored: ${error.message}")
            return
        }
        downX = event.x
        downY = event.y
        downTime = event.eventTime
        handler.postDelayed(confirmRunnable, confirmationDelayMillis)
    }

    private fun handleMove(event: MotionEvent): Boolean {
        val current = machine ?: return false
        val exceededSlop = hypot(event.x - downX, event.y - downY) > touchSlop
        if (current.state is SelectionState.AwaitingConfirmation && exceededSlop) {
            handler.removeCallbacks(confirmRunnable)
            val transition = current.move(globalOffset(event) ?: 0, exceededTouchSlop = true)
            if (SelectionEffect.YIELD_TO_SCROLL in transition.effects) {
                Log.i(TAG, "yield-to-scroll")
            }
            machine = null
            return false
        }
        if (current.state !is SelectionState.Confirmed) return false
        val offset = globalOffset(event) ?: return true
        try {
            applySelection(current.move(offset, exceededTouchSlop = exceededSlop))
        } catch (error: SelectionTextUnavailableException) {
            Log.i(TAG, "move ignored: ${error.message}")
        }
        return true
    }

    private fun finishIfConfirmed(): Boolean {
        handler.removeCallbacks(confirmRunnable)
        val current = machine ?: return false
        if (current.state is SelectionState.Completed) return true
        if (current.state !is SelectionState.Confirmed) {
            current.release()
            machine = null
            return false
        }
        val transition = current.release()
        applySelection(transition)
        onSelectionCompleted(transition.rangeOrNull())
        reader.parent?.requestDisallowInterceptTouchEvent(false)
        Log.i(TAG, "completed range=${transition.rangeOrNull()}")
        return true
    }

    private fun globalOffset(event: MotionEvent): Int? {
        val child = reader.findChildViewUnder(event.x, event.y) ?: return null
        val position = reader.getChildAdapterPosition(child)
        if (position == RecyclerView.NO_POSITION) return null
        return adapter.globalUtf16OffsetFor(
            position,
            child,
            event.x - child.left,
            event.y - child.top,
        )
    }

    private fun applySelection(transition: SelectionTransition) {
        transition.rangeOrNull()?.let { range ->
            adapter.setSelection(range)
            onSelectionChanged(range)
        }
    }

    private fun cancel(clearSelection: Boolean) {
        handler.removeCallbacks(confirmRunnable)
        machine?.cancel()
        machine = null
        reader.parent?.requestDisallowInterceptTouchEvent(false)
        if (clearSelection) adapter.setSelection(null)
        if (clearSelection) onSelectionChanged(null)
        if (clearSelection) onSelectionCompleted(null)
    }

    private fun SelectionTransition.rangeOrNull(): SelectionRange? = when (val current = state) {
        is SelectionState.Confirmed -> current.range
        is SelectionState.Adjusting -> current.range
        is SelectionState.Completed -> current.range
        else -> null
    }

    private companion object {
        const val TAG = "WorldViewerSelection"
    }
}
