package dev.worldviewer.selection

data class SelectionRange(
    val startUtf16: Int,
    val endUtf16: Int,
) {
    init {
        require(startUtf16 >= 0)
        require(endUtf16 > startUtf16)
    }
}

enum class SelectionEdge {
    START,
    END,
}

sealed interface SelectionState {
    data object Idle : SelectionState

    data class AwaitingConfirmation(
        val anchor: SelectionRange,
        val deadlineMillis: Long,
    ) : SelectionState

    data class Confirmed(
        val anchor: SelectionRange,
        val range: SelectionRange,
    ) : SelectionState

    data class Adjusting(
        val range: SelectionRange,
        val edge: SelectionEdge,
    ) : SelectionState

    data class Completed(val range: SelectionRange) : SelectionState
}

enum class SelectionEffect {
    CONFIRM_HAPTIC,
    YIELD_TO_SCROLL,
}

data class SelectionTransition(
    val state: SelectionState,
    val effects: Set<SelectionEffect> = emptySet(),
)

class SelectionStateMachine(
    private val text: SelectionTextNavigator,
    private val confirmationDelayMillis: Long,
) {
    constructor(text: String, confirmationDelayMillis: Long) : this(
        StringSelectionTextNavigator(text),
        confirmationDelayMillis,
    )

    init {
        require(text.utf16Length > 0)
        require(confirmationDelayMillis > 0)
    }

    var state: SelectionState = SelectionState.Idle
        private set

    fun press(offsetUtf16: Int, eventTimeMillis: Long): SelectionTransition {
        state = SelectionState.AwaitingConfirmation(
            anchor = characterRangeAt(offsetUtf16),
            deadlineMillis = eventTimeMillis + confirmationDelayMillis,
        )
        return transition()
    }

    fun advanceTime(eventTimeMillis: Long): SelectionTransition {
        val awaiting = state as? SelectionState.AwaitingConfirmation ?: return transition()
        if (eventTimeMillis < awaiting.deadlineMillis) return transition()
        state = SelectionState.Confirmed(awaiting.anchor, awaiting.anchor)
        return transition(SelectionEffect.CONFIRM_HAPTIC)
    }

    fun move(offsetUtf16: Int, exceededTouchSlop: Boolean): SelectionTransition {
        state = when (val current = state) {
            is SelectionState.AwaitingConfirmation -> {
                if (exceededTouchSlop) {
                    SelectionState.Idle
                } else {
                    current
                }
            }
            is SelectionState.Confirmed -> current.copy(
                range = current.anchor.span(characterRangeAt(offsetUtf16)),
            )
            is SelectionState.Adjusting -> current.copy(
                range = current.range.adjust(current.edge, characterRangeAt(offsetUtf16)),
            )
            else -> current
        }
        return if (state == SelectionState.Idle && exceededTouchSlop) {
            transition(SelectionEffect.YIELD_TO_SCROLL)
        } else {
            transition()
        }
    }

    fun release(): SelectionTransition {
        state = when (val current = state) {
            is SelectionState.AwaitingConfirmation -> SelectionState.Idle
            is SelectionState.Confirmed -> SelectionState.Completed(current.range)
            is SelectionState.Adjusting -> SelectionState.Completed(current.range)
            else -> current
        }
        return transition()
    }

    fun beginAdjustment(edge: SelectionEdge): SelectionTransition {
        val completed = state as? SelectionState.Completed ?: return transition()
        state = SelectionState.Adjusting(completed.range, edge)
        return transition()
    }

    fun cancel(): SelectionTransition {
        state = SelectionState.Idle
        return transition()
    }

    fun restore(range: SelectionRange): SelectionTransition {
        require(range.endUtf16 <= text.utf16Length)
        require(text.isCharacterBoundary(range.startUtf16))
        require(text.isCharacterBoundary(range.endUtf16))
        state = SelectionState.Completed(range)
        return transition()
    }

    private fun characterRangeAt(offsetUtf16: Int): SelectionRange = text.characterRangeAt(offsetUtf16)

    private fun SelectionRange.span(other: SelectionRange): SelectionRange = SelectionRange(
        startUtf16 = minOf(startUtf16, other.startUtf16),
        endUtf16 = maxOf(endUtf16, other.endUtf16),
    )

    private fun SelectionRange.adjust(
        edge: SelectionEdge,
        character: SelectionRange,
    ): SelectionRange = when (edge) {
        SelectionEdge.START -> SelectionRange(
            startUtf16 = minOf(character.startUtf16, previousCharacterStart(endUtf16)),
            endUtf16 = endUtf16,
        )
        SelectionEdge.END -> SelectionRange(
            startUtf16 = startUtf16,
            endUtf16 = maxOf(character.endUtf16, nextCharacterEnd(startUtf16)),
        )
    }

    private fun previousCharacterStart(endExclusive: Int): Int =
        text.previousCharacterStart(endExclusive)

    private fun nextCharacterEnd(start: Int): Int =
        text.nextCharacterEnd(start)

    private fun transition(vararg effects: SelectionEffect) =
        SelectionTransition(state, effects.toSet())
}
