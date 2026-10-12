package pt.tiago.bubblenight.game

import pt.tiago.bubblenight.core.model.*
import pt.tiago.bubblenight.core.rules.GameEngine
import pt.tiago.bubblenight.ui.UiPoint
import kotlin.math.hypot

internal enum class Guide { FULL, FIRST_BOUNCE, SHORT }

/** Presentation of the engine's trace; no collision or board physics are calculated here. */
internal fun guidePoints(segments: List<PathSegment>, guide: Guide): List<UiPoint> {
    if (segments.isEmpty()) return emptyList()
    val first = segments.first()
    var left = when (guide) {
        Guide.FULL -> Double.MAX_VALUE
        Guide.FIRST_BOUNCE -> hypot(first.to.x - first.from.x, first.to.y - first.from.y) + 3.0
        Guide.SHORT -> 6.0
    }
    fun point(v: Vec2) = UiPoint(v.x.toFloat(), v.y.toFloat())
    val points = mutableListOf(point(first.from))
    for (segment in if (guide == Guide.FULL) segments else segments.take(2)) {
        val length = hypot(segment.to.x - segment.from.x, segment.to.y - segment.from.y)
        if (length <= left) { points += point(segment.to); left -= length }
        else {
            val t = left / length.coerceAtLeast(.001)
            points += point(Vec2(segment.from.x + (segment.to.x - segment.from.x) * t, segment.from.y + (segment.to.y - segment.from.y) * t))
            break
        }
    }
    return points
}

internal fun potentialStars(state: GameState): Int = when {
    state.mode !in setOf(GameMode.CAMPAIGN, GameMode.DAILY) -> 0
    state.outcome == Outcome.WON -> GameEngine.stars(state)
    state.outcome == Outcome.LOST -> 0
    state.shotsUsed < state.level.threeStarMaxShots -> 3
    state.shotsUsed < state.level.twoStarMaxShots -> 2
    else -> 1
}
