package pt.tiago.bubblenight.core.rules

import pt.tiago.bubblenight.core.model.Difficulty
import pt.tiago.bubblenight.core.model.ScoreBreakdown

/**
 * Points for one shot. Base values: 10 per popped bubble; 20 per falling colour and 10 per
 * falling rock, multiplied by 1 + fallen/4 so big drops are worth more than many small ones;
 * 100 per rescued star. Consecutive removing shots form a streak (x1..x4). Every part is then
 * scaled by the difficulty (Easy x1, Medium x1.5, Hard x2) so harder variants score more.
 */
object Scoring {
    const val MAX_STREAK_MULTIPLIER = 4
    const val UNUSED_SHOT_POINTS = 50L

    data class Turn(val points: Long, val multiplier: Int, val streak: Int, val parts: ScoreBreakdown)

    fun percent(difficulty: Difficulty): Long = when (difficulty) {
        Difficulty.EASY -> 100L
        Difficulty.MEDIUM -> 150L
        Difficulty.HARD -> 200L
    }

    fun turn(difficulty: Difficulty, previousStreak: Int, popped: Int, droppedColors: Int, droppedRocks: Int, droppedStars: Int): Turn {
        val fallen = droppedColors + droppedRocks + droppedStars
        val pop = popped * 10L
        val drop = (droppedColors * 20L + droppedRocks * 10L) * (1 + fallen / 4)
        val star = droppedStars * 100L
        val base = pop + drop + star
        if (base == 0L) return Turn(0, 1, 0, ScoreBreakdown())
        val streak = previousStreak + 1
        val multiplier = streak.coerceAtMost(MAX_STREAK_MULTIPLIER)
        val pct = percent(difficulty)
        val parts = ScoreBreakdown(
            popped = pop * pct / 100,
            dropped = drop * pct / 100,
            rescued = star * pct / 100,
            streak = base * (multiplier - 1) * pct / 100,
        )
        return Turn(parts.total, multiplier, streak, parts)
    }

    fun unusedShotBonus(difficulty: Difficulty, unusedShots: Int): Long =
        unusedShots.coerceAtLeast(0) * UNUSED_SHOT_POINTS * percent(difficulty) / 100
}

internal fun ScoreBreakdown.plus(other: ScoreBreakdown) = ScoreBreakdown(
    popped + other.popped, dropped + other.dropped, rescued + other.rescued,
    streak + other.streak, unusedShots + other.unusedShots,
)
