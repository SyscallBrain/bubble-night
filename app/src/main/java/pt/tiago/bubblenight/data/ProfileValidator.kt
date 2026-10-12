package pt.tiago.bubblenight.data

import pt.tiago.bubblenight.core.model.Bubble
import pt.tiago.bubblenight.core.model.Difficulty
import pt.tiago.bubblenight.core.model.GameMode
import pt.tiago.bubblenight.core.model.GameState
import pt.tiago.bubblenight.core.model.Objective
import pt.tiago.bubblenight.core.model.Outcome
import pt.tiago.bubblenight.core.rules.Connectivity
import pt.tiago.bubblenight.core.geometry.HexGrid
import pt.tiago.bubblenight.core.rules.GameEngine

object ProfileValidator {
    private val idPattern = Regex("[A-Za-z0-9._-]{1,96}")
    private val hexSeedPattern = Regex("[0-9a-f]{16}")
    private val hashPattern = Regex("[0-9a-f]{64}")
    private val baseIdPattern = Regex("level-[0-9]{4}")
    private val variantPatterns = Difficulty.entries.associateWith { Regex("level-[0-9]{4}-${it.name.lowercase()}") }

    fun violations(profile: Profile): List<String> = buildList {
        if (profile.schemaVersion != CURRENT_PROFILE_SCHEMA) add("unsupported schemaVersion")
        if (profile.contentVersion !in 1..CURRENT_CONTENT_VERSION) add("unsupported contentVersion")
        if (profile.engineVersion !in 1..CURRENT_ENGINE_VERSION) add("unsupported engineVersion")
        if (profile.revision < 0) add("negative revision")
        if (profile.progressByDifficulty.keys.any { it !in Difficulty.entries }) add("invalid progress difficulty")
        if (profile.statsByDifficulty.keys.any { it !in Difficulty.entries }) add("invalid stats difficulty")

        profile.progressByDifficulty.forEach { (difficulty, progress) ->
            if (progress.completedVariantIds.size > 600 || progress.bestByVariantId.size > 600) add("campaign progress exceeds catalog size")
            if (progress.completedVariantIds.any { !idPattern.matches(it) }) add("invalid completed variant id")
            val variantPattern = variantPatterns.getValue(difficulty)
            if (progress.completedVariantIds.any { !variantPattern.matches(it) }) add("variant difficulty mismatch in progress")
            if (progress.bestByVariantId.keys.any { !idPattern.matches(it) }) add("invalid best variant id")
            if (progress.bestByVariantId.keys.any { !variantPattern.matches(it) }) add("variant difficulty mismatch in best results")
            if (!progress.bestByVariantId.keys.containsAll(progress.completedVariantIds)) {
                add("completed variants must have a best result")
            }
            progress.bestByVariantId.forEach { (id, best) ->
                if (id !in progress.completedVariantIds) add("best result for incomplete variant")
                if (best.stars !in 0..3) add("stars out of range")
                if (best.fewestShots != null && best.fewestShots < 0) add("negative best shot count")
                if (best.bestScore < 0) add("negative best score")
                if (id in progress.completedVariantIds && best.stars < 1) add("completed variant needs at least one star")
            }
            if (difficulty !in Difficulty.entries) add("invalid difficulty")
        }
        profile.statsByDifficulty.values.forEach { stats ->
            if (listOf(stats.campaignWins, stats.campaignLosses, stats.campaignShots,
                    stats.campaignScore, stats.bubblesCleared, stats.endlessBestScore, stats.endlessWaves,
                    stats.endlessRuns, stats.zenSessions).any { it < 0 }) add("negative statistic")
        }
        if (profile.appliedOutcomeReceipts.size > MAX_OUTCOME_RECEIPTS) add("too many outcome receipts")
        if (profile.appliedOutcomeReceipts.distinct().size != profile.appliedOutcomeReceipts.size) add("duplicate outcome receipt")
        if (profile.appliedTurnReceipts.size > MAX_TURN_RECEIPTS) add("too many turn receipts")
        if (profile.appliedTurnReceipts.distinct().size != profile.appliedTurnReceipts.size) add("duplicate turn receipt")
        if (profile.achievements.any { it !in KNOWN_ACHIEVEMENTS }) add("unknown achievement")
        if (profile.seenTips.any { it !in KNOWN_TIPS }) add("unknown tip")
        if (profile.zenFavorites.size > 600 || profile.zenFavorites.any { !knownBaseId(it) }) add("invalid Zen favorites")
        if (profile.dailyResults.size > 270) add("too many daily results")
        profile.dailyResults.forEach { (key, best) ->
            val parts = key.split('/')
            if (parts.size != 2 || !validDate(parts[0]) || parts[1] !in Difficulty.entries.map { it.name }) add("invalid daily result key")
            if (best.stars !in 1..3 || best.score < 0 || best.fewestShots !in 1..100) add("invalid daily result")
        }
        profile.dailySession?.let { session ->
            if (!validDate(session.date) || session.state.mode != GameMode.DAILY) add("invalid daily session")
            addAll(stateViolations(session.state))
        }
        profile.appliedOutcomeReceipts.forEach {
            if (!validReceipt(it)) add("invalid outcome receipt")
        }
        profile.appliedTurnReceipts.forEach {
            val parts = it.split('|')
            if (parts.size != 2 || !idPattern.matches(parts[0]) || parts[1].toLongOrNull()?.let { turn -> turn >= 0 } != true) {
                add("invalid turn receipt")
            }
        }

        profile.campaignSession?.let { state ->
            if (state.mode != GameMode.CAMPAIGN) add("campaign session has wrong mode")
            addAll(stateViolations(state))
        }
        when (val session = profile.freeSession) {
            is FreeSession.Zen -> {
                if (session.state.mode != GameMode.ZEN) add("zen session has wrong mode")
                addAll(stateViolations(session.state))
            }
            is FreeSession.Endless -> {
                val run = session.run
                if (!idPattern.matches(run.runId)) add("invalid endless run id")
                if (!hexSeedPattern.matches(run.seedHex)) add("invalid endless seed")
                if (run.orderedVariantIds.isEmpty()) add("empty endless deck")
                if (run.orderedVariantIds.size > 600) add("endless deck exceeds catalog size")
                if (run.orderedVariantIds.any { !idPattern.matches(it) }) add("invalid endless variant id")
                if (run.orderedVariantIds.distinct().size != run.orderedVariantIds.size) add("duplicate endless deck entry")
                if (run.cursor < 0) add("negative endless cursor")
                if (run.score < 0) add("negative endless score")
                run.currentGame?.let { state ->
                    if (state.mode != GameMode.ENDLESS) add("endless game has wrong mode")
                    if (state.difficulty != run.difficulty) add("endless difficulty mismatch")
                    if (run.orderedVariantIds.isNotEmpty() && state.level.variantId !in run.orderedVariantIds) {
                        add("endless game is outside saved deck")
                    }
                    addAll(stateViolations(state))
                }
            }
            null -> Unit
        }
        val campaignId = profile.campaignSession?.sessionId
        val freeId = when (val session = profile.freeSession) {
            is FreeSession.Zen -> session.state.sessionId
            is FreeSession.Endless -> session.run.runId
            null -> null
        }
        if (campaignId != null && campaignId == freeId) add("campaign and free session ids collide")
    }

    fun requireValid(profile: Profile): Profile {
        val errors = violations(profile)
        require(errors.isEmpty()) { errors.joinToString(prefix = "Invalid profile: ") }
        return profile
    }

    private fun knownBaseId(id: String): Boolean = baseIdPattern.matches(id) && id.takeLast(4).toInt() in 1..600
    private fun validDate(date: String): Boolean = date.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) &&
        runCatching { java.time.LocalDate.parse(date) }.isSuccess

    private fun stateViolations(state: GameState): List<String> = buildList {
        val level = state.level
        if (!idPattern.matches(state.sessionId)) add("invalid session id")
        if (!baseIdPattern.matches(level.baseId)) add("invalid level base id")
        if (level.variantId != "${level.baseId}-${state.difficulty.name.lowercase()}") add("variant id/difficulty mismatch")
        if (level.revision < 1) add("invalid level revision")
        if (!hashPattern.matches(level.semanticHash)) add("invalid level semantic hash")
        if (!hexSeedPattern.matches(level.seedHex) || !hexSeedPattern.matches(state.rngStateHex)) add("invalid RNG seed")
        if (level.maxShots !in 1..100 || level.twoStarMaxShots < 1 || level.threeStarMaxShots < 1 ||
            level.threeStarMaxShots > level.twoStarMaxShots || level.twoStarMaxShots > level.maxShots) {
            add("invalid shot thresholds")
        }
        if (state.shotsUsed < 0 || (state.mode != GameMode.ZEN && state.shotsUsed > level.maxShots)) add("invalid shotsUsed")
        if (state.mode != GameMode.ZEN && state.outcome == Outcome.READY && state.shotsUsed >= level.maxShots) add("ready state has no shots remaining")
        if (state.score < 0 || state.turnId < 0 || state.turnId != state.shotsUsed.toLong()) add("invalid score or turnId")
        if (state.rescuedStars < 0) add("negative rescuedStars")
        if ((state.outcome == Outcome.LOST) != (state.lossReason != null)) add("outcome/lossReason mismatch")
        if (state.board.cells.size > 119) add("board too large")
        if (level.board.cells.size > 119) add("initial board too large")
        val occupied = HashSet<Pair<Int, Int>>()
        state.board.cells.forEach { entry ->
            val cell = entry.cell
            val maxColumn = if (cell.row % 2 == 0) 8 else 7
            if (cell.row !in 0..13 || cell.column !in 0..maxColumn) add("illegal board coordinate")
            if (!occupied.add(cell.row to cell.column)) add("duplicate board coordinate")
            val bubble = entry.bubble
            if (bubble is Bubble.Color && bubble.id !in 0..5) add("invalid color id")
            if (entry.bubble == Bubble.Star && cell.row == 0) add("star cannot be a root")
        }
        val ordered = state.board.cells.sortedWith(compareBy({ it.cell.row }, { it.cell.column }))
        if (ordered != state.board.cells) add("board cells are not canonically ordered")
        validateInitialBoard(level.board.cells, this)
        if (state.board.cells.size <= 119 && state.board.cells.all { HexGrid.isLegal(it.cell) } &&
            Connectivity.ceilingConnected(state.board).size != state.board.cells.size) add("snapshot contains detached cells")
        val initialColors = level.board.cells.count { it.bubble is Bubble.Color }
        val remainingColors = state.board.cells.count { it.bubble is Bubble.Color }
        if (remainingColors.toLong() > initialColors.toLong() + state.shotsUsed) add("snapshot created extra colors")
        state.board.cells.filter { it.bubble == Bubble.Rock || it.bubble == Bubble.Star }.forEach {
            if (level.board.at(it.cell) != it.bubble) add("snapshot created or moved a fixed bubble")
        }
        val initialStars = level.board.cells.count { it.bubble == Bubble.Star }
        if (state.rescuedStars.toLong() + state.board.cells.count { it.bubble == Bubble.Star } != initialStars.toLong()) add("star conservation mismatch")
        if (state.board.colors().any { it !in 0..5 } || state.currentColor !in 0..5 || state.nextColor !in 0..5) {
            add("invalid ammunition color")
        }
        try {
            if (level.board.cells.size <= 119) {
                val initial = GameEngine.start(level, state.difficulty, state.mode, state.sessionId)
                if (state.shotsUsed == 0 && (state.board != initial.board || state.score != 0L ||
                    state.rngStateHex != initial.rngStateHex || state.outcome != Outcome.READY ||
                    listOf(state.currentColor, state.nextColor).sorted() != listOf(initial.currentColor, initial.nextColor).sorted())) {
                    add("zero-turn snapshot differs from initial state or swap")
                }
            }
        } catch (error: IllegalArgumentException) {
            add("invalid initial level: ${error.message ?: "engine rejected level"}")
        }
        if (state.outcome == Outcome.READY) {
            val colors = state.board.colors()
            if (state.currentColor !in colors || state.nextColor !in colors) add("ready ammunition is not eligible")
            if (state.board.cells.size <= 119 && state.board.cells.all { HexGrid.isLegal(it.cell) } && Connectivity.ceilingConnected(state.board).size != state.board.cells.size) add("ready board contains detached cells")
            if (state.board.cells.any { HexGrid.isLegal(it.cell) && HexGrid.center(it.cell).y + HexGrid.RADIUS >= HexGrid.DANGER_Y - HexGrid.EPSILON }) {
                add("ready board reaches danger line")
            }
        }
        when (val objective = level.objective) {
            Objective.ClearColors -> {
                if (level.board.cells.none { it.bubble is Bubble.Color }) add("clear-colors level has no colors")
                if (state.outcome == Outcome.READY && state.board.cells.none { it.bubble is Bubble.Color }) add("ready clear-colors snapshot is already won")
                if (state.outcome == Outcome.WON && state.board.cells.any { it.bubble is Bubble.Color }) add("won clear-colors snapshot retains colors")
            }
            Objective.RescueStars -> {
                val initialStars = level.board.cells.count { it.bubble == Bubble.Star }
                val currentStars = state.board.cells.count { it.bubble == Bubble.Star }
                if (initialStars == 0) add("rescue-stars level has no stars")
                if (state.rescuedStars.toLong() + currentStars != initialStars.toLong()) add("star conservation mismatch")
                if (state.outcome == Outcome.READY && currentStars == 0) add("ready rescue-stars snapshot is already won")
                if (state.outcome == Outcome.WON && currentStars != 0) add("won rescue-stars snapshot retains stars")
            }
            is Objective.ClearColorSet -> {
                if (objective.colorIds.isEmpty() || objective.colorIds.any { it !in 0..5 } || objective.colorIds.distinct().size != objective.colorIds.size) {
                    add("invalid clear-color-set objective")
                }
                val initialColors = level.board.cells.mapNotNull { (it.bubble as? Bubble.Color)?.id }.toSet()
                val currentColors = state.board.cells.mapNotNull { (it.bubble as? Bubble.Color)?.id }.toSet()
                if (!initialColors.containsAll(objective.colorIds)) add("objective targets absent from level")
                if (state.outcome == Outcome.READY && objective.colorIds.none { it in currentColors }) add("ready color-set snapshot is already won")
                if (state.outcome == Outcome.WON && objective.colorIds.any { it in currentColors }) add("won color-set snapshot retains a target")
            }
        }
        if (state.outcome == Outcome.LOST && objectiveComplete(state)) add("lost snapshot already satisfies objective")
        val dangerReached = state.board.cells.any { entry ->
            HexGrid.isLegal(entry.cell) && HexGrid.center(entry.cell).y + HexGrid.RADIUS >= HexGrid.DANGER_Y - HexGrid.EPSILON
        }
        when (state.lossReason) {
            pt.tiago.bubblenight.core.model.LossReason.DANGER_LINE -> if (state.mode == GameMode.ZEN || !dangerReached) add("danger-line loss reason mismatch")
            pt.tiago.bubblenight.core.model.LossReason.ZEN_DANGER -> if (state.mode != GameMode.ZEN || !dangerReached) add("zen-danger loss reason mismatch")
            pt.tiago.bubblenight.core.model.LossReason.NO_SHOTS -> if (state.mode == GameMode.ZEN || state.shotsUsed < level.maxShots || dangerReached) add("no-shots loss reason mismatch")
            pt.tiago.bubblenight.core.model.LossReason.NO_COMBINATIONS -> if (dangerReached || state.board.colors().isNotEmpty() || objectiveComplete(state)) add("no-combinations loss reason mismatch")
            null -> Unit
        }
    }

    private fun validateInitialBoard(cells: List<pt.tiago.bubblenight.core.model.CellBubble>, issues: MutableList<String>) {
        if (cells.size > 119) return
        val ordered = cells.sortedWith(compareBy({ it.cell.row }, { it.cell.column }))
        if (ordered != cells) issues += "initial board cells are not canonically ordered"
        val seen = HashSet<Pair<Int, Int>>()
        cells.forEach { entry ->
            val row = entry.cell.row
            val column = entry.cell.column
            val lastColumn = if (row % 2 == 0) 8 else 7
            if (row !in 0..13 || column !in 0..lastColumn) issues += "illegal initial board coordinate"
            if (!seen.add(row to column)) issues += "duplicate initial board coordinate"
            val bubble = entry.bubble
            if (bubble is Bubble.Color && bubble.id !in 0..5) issues += "invalid initial color id"
            if (entry.bubble == Bubble.Star && row == 0) issues += "initial star cannot be a root"
        }
    }

    private fun validReceipt(receipt: String): Boolean {
        val parts = receipt.split('|')
        return parts.size == 3 && idPattern.matches(parts[0]) &&
            parts[1].toLongOrNull()?.let { it >= 0 } == true && parts[2] in setOf("WON", "LOST")
    }

    private fun objectiveComplete(state: GameState): Boolean = when (val objective = state.level.objective) {
        Objective.ClearColors -> state.board.cells.none { it.bubble is Bubble.Color }
        Objective.RescueStars -> state.board.cells.none { it.bubble == Bubble.Star }
        is Objective.ClearColorSet -> state.board.cells.none { entry ->
            (entry.bubble as? Bubble.Color)?.id?.let(objective.colorIds::contains) == true
        }
    }
}

/** Receipts only guard against re-applying recent turns; older ones cannot reach the reducer. */
const val MAX_OUTCOME_RECEIPTS = 512
const val MAX_TURN_RECEIPTS = 256
val KNOWN_ACHIEVEMENTS = listOf("first_win", "first_ricochet", "drop_10", "first_orbit", "levels_100")
val KNOWN_TIPS = setOf(TIP_ROCK, TIP_STAR, TIP_COLOR_SET)
const val TIP_ROCK = "rock"
const val TIP_STAR = "star"
const val TIP_COLOR_SET = "color_set"

