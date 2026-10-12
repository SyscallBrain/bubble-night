package pt.tiago.bubblenight.data

import pt.tiago.bubblenight.core.model.GameMode
import pt.tiago.bubblenight.core.model.GameState
import pt.tiago.bubblenight.core.model.Outcome
import pt.tiago.bubblenight.core.model.TurnEvent
import pt.tiago.bubblenight.core.model.TurnResult
import pt.tiago.bubblenight.core.rules.GameEngine

/** Pure profile reducer. Only terminal states affect result statistics. */
object ProfileReducer {
    fun recordTurn(
        profile: Profile,
        state: GameState,
        suppliedRun: EndlessRunSnapshot? = null,
        turnResult: TurnResult? = null,
    ): Profile {
        if (turnResult != null) {
            require(turnResult.state == state) { "Turn result/state mismatch" }
            if (!turnResult.accepted) return profile
        }
        val current = when (state.mode) {
            GameMode.CAMPAIGN -> profile.campaignSession
            GameMode.ZEN -> (profile.freeSession as? FreeSession.Zen)?.state
            GameMode.DAILY -> profile.dailySession?.state
            GameMode.ENDLESS -> (profile.freeSession as? FreeSession.Endless)?.run?.currentGame
        }
        // New games are installed by saveSession/saveEndlessRun. Delayed callbacks may not
        // replace another active game or rewind the current game, even after receipt eviction.
        if (current != null && (current.sessionId != state.sessionId || current.turnId > state.turnId)) return profile
        if (current != null && turnResult != null && current.turnId >= state.turnId) return profile
        if (current != null && current.outcome != Outcome.READY &&
            "${current.sessionId}|${current.turnId}|${current.outcome.name}" in profile.appliedOutcomeReceipts) return profile
        val receipt = "${state.sessionId}|${state.turnId}|${state.outcome.name}"
        if (state.outcome != Outcome.READY && receipt in profile.appliedOutcomeReceipts) return profile
        val turnReceipt = "${state.sessionId}|${state.turnId}"
        if (turnResult != null && turnReceipt in profile.appliedTurnReceipts) return profile
        val storedRun = when {
            suppliedRun != null -> suppliedRun.copy(currentGame = state)
            profile.freeSession is FreeSession.Endless -> (profile.freeSession as FreeSession.Endless).run.copy(currentGame = state)
            else -> null
        }
        var result = profile.withSession(state, storedRun)
        if (turnResult != null) {
            result = result.copy(
                appliedTurnReceipts = (result.appliedTurnReceipts + turnReceipt).takeLast(MAX_TURN_RECEIPTS),
                achievements = updateTurnAchievements(result.achievements, turnResult),
            )
        }
        if (state.outcome == Outcome.READY) return result

        val stats = result.statsByDifficulty[state.difficulty] ?: DifficultyStats()
        val nextStats = when (state.mode) {
            GameMode.CAMPAIGN -> if (state.outcome == Outcome.WON) {
                val progress = result.progressByDifficulty[state.difficulty] ?: CampaignProgress()
                val stars = GameEngine.stars(state)
                val prior = progress.bestByVariantId[state.level.variantId] ?: LevelBest()
                val best = LevelBest(
                    stars = maxOf(prior.stars, stars),
                    fewestShots = listOfNotNull(prior.fewestShots, state.shotsUsed).minOrNull(),
                    bestScore = maxOf(prior.bestScore, state.score),
                )
                result = result.copy(progressByDifficulty = result.progressByDifficulty +
                    (state.difficulty to progress.copy(
                        completedVariantIds = progress.completedVariantIds + state.level.variantId,
                        bestByVariantId = progress.bestByVariantId + (state.level.variantId to best),
                    )))
                stats.copy(
                    campaignWins = stats.campaignWins.satAdd(1),
                    campaignShots = stats.campaignShots.satAdd(state.shotsUsed.toLong()),
                    campaignScore = stats.campaignScore.satAdd(state.score),
                    bubblesCleared = stats.bubblesCleared.satAdd(clearedColorCount(state)),
                )
            } else stats.copy(
                campaignLosses = stats.campaignLosses.satAdd(1),
                campaignShots = stats.campaignShots.satAdd(state.shotsUsed.toLong()),
                campaignScore = stats.campaignScore.satAdd(state.score),
                bubblesCleared = stats.bubblesCleared.satAdd(clearedColorCount(state)),
            )
            GameMode.ENDLESS -> {
                val run = storedRun
                stats.copy(
                    endlessBestScore = maxOf(stats.endlessBestScore, run?.score ?: state.score),
                    endlessWaves = if (state.outcome == Outcome.WON) stats.endlessWaves.satAdd(1) else stats.endlessWaves,
                    endlessRuns = if (state.outcome == Outcome.LOST) stats.endlessRuns.satAdd(1) else stats.endlessRuns,
                )
            }
            GameMode.ZEN -> stats.copy(zenSessions = stats.zenSessions.satAdd(1))
            GameMode.DAILY -> {
                if (state.outcome == Outcome.WON) {
                    val session = result.dailySession ?: error("Daily result without a date")
                    val key = "${session.date}/${state.difficulty.name}"
                    val previous = result.dailyResults[key]
                    val best = DailyBest(maxOf(previous?.stars ?: 0, GameEngine.stars(state)),
                        maxOf(previous?.score ?: 0, state.score), minOf(previous?.fewestShots ?: Int.MAX_VALUE, state.shotsUsed))
                    result = result.copy(dailyResults = (result.dailyResults + (key to best)).toSortedMap().entries.toList().takeLast(270).associate { it.toPair() })
                }
                stats
            }
        }
        result = result.copy(
            statsByDifficulty = if (state.mode == GameMode.DAILY) result.statsByDifficulty else result.statsByDifficulty + (state.difficulty to nextStats),
            appliedOutcomeReceipts = retainOutcomes(result, receipt),
        )
        if (state.mode == GameMode.CAMPAIGN && state.outcome == Outcome.WON) {
            result = result.copy(achievements = updateCampaignAchievements(result.achievements, result, state))
        }
        return result
    }

    private fun updateTurnAchievements(existing: Set<String>, result: TurnResult): Set<String> {
        val earned = buildSet {
            addAll(existing)
            if (result.preview?.segments?.any { it.bounceAfter } == true &&
                result.events.filterIsInstance<TurnEvent.Matched>().any { it.cells.size >= 3 }) add("first_ricochet")
            if (result.events.filterIsInstance<TurnEvent.Dropped>().any { it.cells.size >= 10 }) add("drop_10")
        }
        return earned
    }

    private fun updateCampaignAchievements(existing: Set<String>, profile: Profile, state: GameState): Set<String> {
        val earned = existing.toMutableSet().apply { add("first_win") }
        val progress = profile.progressByDifficulty[state.difficulty] ?: return earned
        if (progress.completedVariantIds.size >= 100) earned += "levels_100"
        val number = Regex("level-([0-9]{4})").matchEntire(state.level.baseId)?.groupValues?.get(1)?.toIntOrNull()
        if (number != null) {
            val orbit = (number - 1) / 50
            val completedInOrbit = progress.completedVariantIds.count { variantId ->
                val levelNumber = Regex("level-([0-9]{4})-${state.difficulty.name.lowercase()}")
                    .matchEntire(variantId)?.groupValues?.get(1)?.toIntOrNull()
                levelNumber != null && (levelNumber - 1) / 50 == orbit
            }
            if (completedInOrbit >= 50) earned += "first_orbit"
        }
        return earned
    }

    private fun clearedColorCount(state: GameState): Long {
        val initial = state.level.board.cells.count { it.bubble is pt.tiago.bubblenight.core.model.Bubble.Color }
        val remaining = state.board.cells.count { it.bubble is pt.tiago.bubblenight.core.model.Bubble.Color }
        return (initial.toLong() + state.shotsUsed - remaining).coerceAtLeast(0)
    }

    private fun Profile.withSession(state: GameState, run: EndlessRunSnapshot?): Profile = when (state.mode) {
        GameMode.CAMPAIGN -> copy(campaignSession = state)
        GameMode.ZEN -> copy(freeSession = FreeSession.Zen(state))
        GameMode.DAILY -> copy(dailySession = (dailySession ?: error("Daily session without a date")).copy(state = state))
        GameMode.ENDLESS -> {
            require(run != null) { "An endless turn requires a saved EndlessRunSnapshot" }
            copy(freeSession = FreeSession.Endless(run))
        }
    }

    private fun Long.satAdd(value: Long): Long = if (Long.MAX_VALUE - this < value) Long.MAX_VALUE else this + value

    private fun retainOutcomes(profile: Profile, receipt: String): List<String> {
        val free = when (val session = profile.freeSession) {
            is FreeSession.Zen -> session.state
            is FreeSession.Endless -> session.run.currentGame
            null -> null
        }
        val active = listOfNotNull(profile.campaignSession, free, profile.dailySession?.state)
            .filter { it.outcome != Outcome.READY }
            .map { "${it.sessionId}|${it.turnId}|${it.outcome.name}" }
        val receipts = profile.appliedOutcomeReceipts + receipt
        val pinned = active.filter { it in receipts }
        return (receipts.filterNot { it in pinned }.takeLast(MAX_OUTCOME_RECEIPTS - pinned.size) + pinned).distinct()
    }
}

