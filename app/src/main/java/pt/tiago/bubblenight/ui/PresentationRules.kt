package pt.tiago.bubblenight.ui

import android.content.res.Resources
import pt.tiago.bubblenight.R
import java.text.NumberFormat

/** When there is no margin left, center the label; even a zero-size board has valid bounds. */
internal fun floatingLabelPosition(desired: Float, available: Float, label: Float): Float {
    val remaining = (available - label).coerceAtLeast(0f)
    val margin = minOf(4f, remaining / 2f)
    return desired.coerceIn(margin, (remaining - margin).coerceAtLeast(margin))
}

internal fun bubbleDescription(resources: Resources, bubble: UiBubble?): String = resources.getString(when (bubble?.kind) {
    UiBubbleKind.ROCK -> R.string.bubble_rock
    UiBubbleKind.STAR -> R.string.bubble_star
    UiBubbleKind.COLOR -> when (bubble.color) {
        UiBubbleColor.BLUE -> R.string.bubble_blue
        UiBubbleColor.PURPLE -> R.string.bubble_purple
        UiBubbleColor.GREEN -> R.string.bubble_green
        UiBubbleColor.ORANGE -> R.string.bubble_orange
        UiBubbleColor.PINK -> R.string.bubble_pink
        UiBubbleColor.CYAN -> R.string.bubble_cyan
        null -> R.string.bubble_generic
    }
    null -> R.string.bubble_unavailable
})

internal fun boardStateDescription(resources: Resources, game: UiGame): String = resources.getString(
    R.string.board_state,
    bubbleDescription(resources, game.currentBubble),
    bubbleDescription(resources, game.nextBubble),
    NumberFormat.getNumberInstance().format(game.preview.angleMilliDegrees / 1000.0),
    game.preview.targetDescription?.let { resources.getString(R.string.board_target, it) } ?: resources.getString(R.string.board_no_target),
)
