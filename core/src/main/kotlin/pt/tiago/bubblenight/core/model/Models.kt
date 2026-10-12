package pt.tiago.bubblenight.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** Axial-by-row board coordinate. Legal rows are 0..13; row 13 is overflow. */
@Serializable
data class Cell(val row: Int, val column: Int)

@Serializable
sealed interface Bubble {
    @Serializable data class Color(val id: Int) : Bubble {
        init { require(id in ColorIds.BLUE..ColorIds.CYAN) { "Unknown color id: $id" } }
    }
    @Serializable data object Rock : Bubble
    @Serializable data object Star : Bubble
}

@Serializable
data class CellBubble(val cell: Cell, val bubble: Bubble)

/** Canonically ordered occupancy; use [of] whenever constructing a changed board. */
@Serializable
data class Board(val cells: List<CellBubble>) {
    init {
        require(cells == cells.sortedWith(compareBy<CellBubble>({ it.cell.row }, { it.cell.column }))) {
            "Board cells must be ordered by (row, column); use Board.of"
        }
        require(cells.map { it.cell }.distinct().size == cells.size) { "Duplicate board cell" }
    }

    /** Lookup index; aiming queries cells many times per frame, so a list scan is too slow. */
    @Transient private val index: Map<Cell, Bubble> = cells.associate { it.cell to it.bubble }

    fun at(cell: Cell): Bubble? = index[cell]
    fun occupied(cell: Cell): Boolean = cell in index
    fun colors(): List<Int> = cells.mapNotNull { (it.bubble as? Bubble.Color)?.id }.distinct().sorted()
    fun count(bubble: Bubble): Int = cells.count { it.bubble == bubble }

    fun with(cell: Cell, bubble: Bubble?): Board {
        val next = cells.filterNot { it.cell == cell }.toMutableList()
        if (bubble != null) next += CellBubble(cell, bubble)
        return of(next)
    }

    companion object {
        val EMPTY = Board(emptyList())
        fun of(cells: Iterable<CellBubble>): Board {
            val sorted = cells.sortedWith(compareBy<CellBubble>({ it.cell.row }, { it.cell.column }))
            require(sorted.map { it.cell }.distinct().size == sorted.size) { "Duplicate board cell" }
            return Board(sorted)
        }
    }
}

/** Stable content/UI color identifiers shared by catalog and presentation. */
object ColorIds {
    const val BLUE = 0
    const val PURPLE = 1
    const val GREEN = 2
    const val ORANGE = 3
    const val PINK = 4
    const val CYAN = 5
}

@Serializable enum class Difficulty { EASY, MEDIUM, HARD }
@Serializable enum class GameMode { CAMPAIGN, ENDLESS, ZEN, DAILY }

@Serializable
sealed interface Objective {
    @Serializable data object ClearColors : Objective
    @Serializable data object RescueStars : Objective
    @Serializable data class ClearColorSet(val colorIds: List<Int>) : Objective
}

@Serializable
data class LevelDefinition(
    val baseId: String,
    val variantId: String,
    val revision: Int,
    val semanticHash: String,
    val seedHex: String,
    val board: Board,
    val objective: Objective,
    val maxShots: Int,
    val twoStarMaxShots: Int,
    val threeStarMaxShots: Int,
    val schemaVersion: Int = 1,
    val engineVersion: Int = 1,
)

@Serializable enum class Outcome { READY, WON, LOST }
@Serializable enum class LossReason { DANGER_LINE, NO_SHOTS, NO_COMBINATIONS, ZEN_DANGER }

@Serializable
data class GameState(
    val sessionId: String,
    val level: LevelDefinition,
    val difficulty: Difficulty,
    val mode: GameMode,
    val board: Board,
    val currentColor: Int,
    val nextColor: Int,
    /** Raw SplitMix64 state, exactly 16 lowercase hexadecimal digits. */
    val rngStateHex: String,
    val shotsUsed: Int = 0,
    val score: Long = 0,
    val turnId: Long = 0,
    val outcome: Outcome = Outcome.READY,
    val lossReason: LossReason? = null,
    val rescuedStars: Int = 0,
    /** Consecutive shots that removed something; 0 after a shot that removed nothing. */
    val streak: Int = 0,
    /** Where [score] came from; the parts always add up to [score]. */
    val breakdown: ScoreBreakdown = ScoreBreakdown(),
)

/** Score parts, already multiplied by the difficulty factor. */
@Serializable
data class ScoreBreakdown(
    val popped: Long = 0,
    val dropped: Long = 0,
    val rescued: Long = 0,
    val streak: Long = 0,
    val unusedShots: Long = 0,
) {
    val total: Long get() = popped + dropped + rescued + streak + unusedShots
}

@Serializable data class Vec2(val x: Double, val y: Double)
@Serializable data class PathSegment(val from: Vec2, val to: Vec2, val bounceAfter: Boolean = false)

@Serializable
data class ShotPreview(
    val angleMilliDegrees: Int,
    val segments: List<PathSegment>,
    val target: Cell?,
    val contactPoint: Vec2?,
    val rejection: ShotRejection? = null,
)

@Serializable enum class ShotRejection { ANGLE_OUT_OF_RANGE, NO_LEGAL_SLOT, TRACE_LIMIT }

@Serializable
sealed interface TurnEvent {
    @Serializable data class Placed(val cell: Cell, val colorId: Int) : TurnEvent
    @Serializable data class Matched(val cells: List<Cell>) : TurnEvent
    @Serializable data class Dropped(val cells: List<Cell>, val rocks: Int, val colors: Int) : TurnEvent
    @Serializable data class Rescued(val count: Int) : TurnEvent
    /** Points earned by this shot; [multiplier] is the streak factor applied (1 = no streak). */
    @Serializable data class Scored(val points: Long, val multiplier: Int, val unusedShotBonus: Long = 0) : TurnEvent
    @Serializable data object Won : TurnEvent
    @Serializable data class Lost(val reason: LossReason) : TurnEvent
}

@Serializable
data class TurnResult(
    val state: GameState,
    val accepted: Boolean,
    val preview: ShotPreview? = null,
    val events: List<TurnEvent> = emptyList(),
    val rejection: ShotRejection? = null,
)
