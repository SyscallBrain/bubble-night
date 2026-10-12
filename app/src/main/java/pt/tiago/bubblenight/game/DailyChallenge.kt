package pt.tiago.bubblenight.game

import pt.tiago.bubblenight.core.model.Difficulty
import pt.tiago.bubblenight.data.RuntimeLevel
import java.time.LocalDate

/** Version 1, device-local calendar date. Only selects existing, certified ammunition/boards. */
internal object DailyChallenge {
    fun levelId(date: LocalDate, difficulty: Difficulty, levels: List<RuntimeLevel>): String {
        require(levels.isNotEmpty())
        val ordered = levels.sortedBy { it.ordinal }
        // Coprime stride gives all 600 levels a turn before repeating; no platform-dependent hash.
        val index = Math.floorMod(date.toEpochDay() * 317L + difficulty.ordinal * 193L, ordered.size.toLong()).toInt()
        return ordered[index].id
    }

    fun resultKey(date: String, difficulty: Difficulty) = "$date/${difficulty.name}"
}
