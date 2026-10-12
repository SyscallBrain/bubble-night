package pt.tiago.bubblenight.core.rules

import pt.tiago.bubblenight.core.geometry.HexGrid
import pt.tiago.bubblenight.core.geometry.ShotTracer
import pt.tiago.bubblenight.core.model.*
import pt.tiago.bubblenight.core.random.SplitMix64

/** Pure deterministic rules facade. No Android, clock, or shared mutable state. */
object GameEngine {
    const val ENGINE_VERSION = 1

    fun start(level: LevelDefinition, difficulty: Difficulty, mode: GameMode, sessionId: String): GameState {
        validate(level)
        require(sessionId.isNotBlank()) { "sessionId cannot be blank" }
        val rng = SplitMix64(SplitMix64.seedFromHex(level.seedHex))
        val eligible = level.board.colors()
        require(eligible.isNotEmpty()) { "A level must have at least one eligible color" }
        val current = eligible[rng.nextInt(eligible.size)]
        val next = eligible[rng.nextInt(eligible.size)]
        return GameState(sessionId, level, difficulty, mode, level.board, current, next, rng.stateHex())
    }

    /** Angle is in integer millidegrees relative to vertical. */
    fun preview(state: GameState, angleMilliDegrees: Int): ShotPreview = ShotTracer.preview(state.board, angleMilliDegrees)

    fun swap(state: GameState): GameState {
        require(state.outcome == Outcome.READY) { "Cannot swap after a result" }
        return state.copy(currentColor = state.nextColor, nextColor = state.currentColor)
    }

    fun shoot(state: GameState, angleMilliDegrees: Int): TurnResult {
        if (state.outcome != Outcome.READY) return TurnResult(state, false)
        if (state.mode != GameMode.ZEN && state.shotsUsed >= state.level.maxShots) return TurnResult(state, false)
        val aim = preview(state, angleMilliDegrees)
        if (aim.rejection != null || aim.target == null) return TurnResult(state, false, aim, rejection = aim.rejection)

        var board = state.board.with(aim.target, Bubble.Color(state.currentColor))
        val events = mutableListOf<TurnEvent>(TurnEvent.Placed(aim.target, state.currentColor))
        var rescued = state.rescuedStars
        var popped = 0
        var droppedColors = 0
        var droppedRocks = 0
        var droppedStars = 0
        val group = Connectivity.colorGroup(board, aim.target)
        if (group.size >= 3) {
            board = group.fold(board) { b, cell -> b.with(cell, null) }
            events += TurnEvent.Matched(group)
            popped = group.size
        }

        val anchored = Connectivity.ceilingConnected(board)
        val droppedEntries = board.cells.filter { it.cell !in anchored }
        if (droppedEntries.isNotEmpty()) {
            val droppedCells = droppedEntries.map { it.cell }.sortedWith(compareBy({ it.row }, { it.column }))
            droppedRocks = droppedEntries.count { it.bubble == Bubble.Rock }
            droppedColors = droppedEntries.count { it.bubble is Bubble.Color }
            droppedStars = droppedEntries.count { it.bubble == Bubble.Star }
            board = droppedCells.fold(board) { b, cell -> b.with(cell, null) }
            events += TurnEvent.Dropped(droppedCells, droppedRocks, droppedColors)
            if (droppedStars > 0) { rescued += droppedStars; events += TurnEvent.Rescued(droppedStars) }
        }

        val scoring = state.mode != GameMode.ZEN
        val turn = Scoring.turn(state.difficulty, state.streak, popped, droppedColors, droppedRocks, droppedStars)
        // Sessions saved before the breakdown existed keep their points under "popped".
        val prior = if (state.breakdown.total == state.score) state.breakdown else ScoreBreakdown(popped = state.score)
        var breakdown = if (scoring) prior.plus(turn.parts) else prior
        if (scoring && turn.points > 0) events += TurnEvent.Scored(turn.points, turn.multiplier)

        val shots = state.shotsUsed + 1
        var nextState = state.copy(board = board, shotsUsed = shots, score = breakdown.total, rescuedStars = rescued,
            turnId = state.turnId + 1, streak = turn.streak, breakdown = breakdown)
        val terminal = when {
            objectiveComplete(board, state.level.objective) -> Outcome.WON
            board.cells.any { HexGrid.center(it.cell).y + HexGrid.RADIUS >= HexGrid.DANGER_Y - HexGrid.EPSILON } -> Outcome.LOST
            state.mode != GameMode.ZEN && shots >= state.level.maxShots -> Outcome.LOST
            board.colors().isEmpty() -> Outcome.LOST
            else -> Outcome.READY
        }
        when (terminal) {
            Outcome.WON -> {
                if (scoring) {
                    val bonus = Scoring.unusedShotBonus(state.difficulty, state.level.maxShots - shots)
                    breakdown = breakdown.copy(unusedShots = breakdown.unusedShots + bonus)
                    if (bonus > 0) events += TurnEvent.Scored(bonus, 1, unusedShotBonus = bonus)
                }
                nextState = nextState.copy(score = breakdown.total, breakdown = breakdown, outcome = Outcome.WON)
                events += TurnEvent.Won
            }
            Outcome.LOST -> {
                val reason = when {
                    state.mode == GameMode.ZEN && board.cells.any { HexGrid.center(it.cell).y + HexGrid.RADIUS >= HexGrid.DANGER_Y - HexGrid.EPSILON } -> LossReason.ZEN_DANGER
                    board.cells.any { HexGrid.center(it.cell).y + HexGrid.RADIUS >= HexGrid.DANGER_Y - HexGrid.EPSILON } -> LossReason.DANGER_LINE
                    state.mode != GameMode.ZEN && shots >= state.level.maxShots -> LossReason.NO_SHOTS
                    else -> LossReason.NO_COMBINATIONS
                }
                nextState = nextState.copy(outcome = Outcome.LOST, lossReason = reason)
                events += TurnEvent.Lost(reason)
            }
            Outcome.READY -> {
                val rng = SplitMix64.fromHex(state.rngStateHex)
                val eligible = board.colors()
                var promoted = state.nextColor
                if (promoted !in eligible) promoted = eligible[rng.nextInt(eligible.size)]
                val next = eligible[rng.nextInt(eligible.size)]
                nextState = nextState.copy(currentColor = promoted, nextColor = next, rngStateHex = rng.stateHex())
            }
        }
        return TurnResult(nextState, true, aim, events)
    }

    fun stars(state: GameState): Int {
        if (state.outcome != Outcome.WON || state.mode == GameMode.ZEN) return 0
        return when {
            state.shotsUsed <= state.level.threeStarMaxShots -> 3
            state.shotsUsed <= state.level.twoStarMaxShots -> 2
            else -> 1
        }
    }

    private fun objectiveComplete(board: Board, objective: Objective): Boolean = when (objective) {
        Objective.ClearColors -> board.cells.none { it.bubble is Bubble.Color }
        Objective.RescueStars -> board.cells.none { it.bubble == Bubble.Star }
        is Objective.ClearColorSet -> board.cells.none { entry -> (entry.bubble as? Bubble.Color)?.id?.let(objective.colorIds::contains) == true }
    }

    fun validate(level: LevelDefinition) {
        try {
            validateLevel(level)
        } catch (failure: IllegalArgumentException) {
            if (failure is LevelDefinitionException) throw failure
            throw LevelDefinitionException(failure.message ?: "Invalid level definition", failure)
        }
    }

    private fun validateLevel(level: LevelDefinition) {
        require(level.schemaVersion == 1) { "Unsupported level schemaVersion: ${level.schemaVersion}" }
        require(level.engineVersion == ENGINE_VERSION) { "Unsupported engineVersion: ${level.engineVersion}" }
        require(level.baseId.isNotBlank() && level.variantId.isNotBlank())
        require(level.revision > 0 && level.semanticHash.isNotBlank())
        require(level.seedHex.matches(Regex("[0-9a-fA-F]{1,16}")))
        require(level.maxShots in 1..100) { "maxShots must be in 1..100" }
        require(level.threeStarMaxShots in 1..level.twoStarMaxShots && level.twoStarMaxShots <= level.maxShots)
        require(level.board.cells.all { HexGrid.isLegal(it.cell) && ((it.bubble as? Bubble.Color)?.id?.let { id -> id in 0..5 } ?: true) })
        require(level.board.cells.map { it.cell }.distinct().size == level.board.cells.size)
        require(Connectivity.ceilingConnected(level.board).size == level.board.cells.size) { "Initial board contains cells detached from the ceiling" }
        require(level.board.cells.none { it.cell.row == 0 && it.bubble == Bubble.Star }) { "STAR cannot be on the ceiling row" }
        require(level.board.cells.all { HexGrid.center(it.cell).y + 1.0 < HexGrid.DANGER_Y }) { "Initial board reaches danger line" }
        when (val objective = level.objective) {
            Objective.ClearColors -> require(level.board.cells.none { it.bubble == Bubble.Star })
            Objective.RescueStars -> require(level.board.cells.any { it.bubble == Bubble.Star })
            is Objective.ClearColorSet -> {
                require(objective.colorIds.isNotEmpty() && objective.colorIds.distinct().size == objective.colorIds.size)
                require(level.board.cells.any { entry -> (entry.bubble as? Bubble.Color)?.id?.let(objective.colorIds::contains) == true })
                require(level.board.cells.none { it.bubble == Bubble.Star })
            }
        }
        require(!objectiveComplete(level.board, level.objective)) { "Level starts already complete" }
    }
}
