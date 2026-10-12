package pt.tiago.bubblenight.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.bubblenight.core.model.Board
import pt.tiago.bubblenight.core.model.Bubble
import pt.tiago.bubblenight.core.model.Cell
import pt.tiago.bubblenight.core.model.CellBubble
import pt.tiago.bubblenight.core.model.Difficulty
import pt.tiago.bubblenight.core.model.GameMode
import pt.tiago.bubblenight.core.model.GameState
import pt.tiago.bubblenight.core.model.LevelDefinition
import pt.tiago.bubblenight.core.model.Objective
import pt.tiago.bubblenight.core.model.Outcome
import pt.tiago.bubblenight.core.model.PathSegment
import pt.tiago.bubblenight.core.model.ShotPreview
import pt.tiago.bubblenight.core.model.TurnEvent
import pt.tiago.bubblenight.core.model.TurnResult
import pt.tiago.bubblenight.core.model.Vec2
import pt.tiago.bubblenight.core.rules.GameEngine

class ProfileReducerTest {
    @Test fun campaignOutcomeIsAppliedOnceAndUpdatesBestAndStats() {
        val won = wonState()
        val once = ProfileReducer.recordTurn(Profile(), won)
        val twice = ProfileReducer.recordTurn(once, won)
        assertEquals(once, twice)
        assertEquals(1L, once.statsByDifficulty.getValue(Difficulty.MEDIUM).campaignWins)
        assertEquals(2L, once.statsByDifficulty.getValue(Difficulty.MEDIUM).bubblesCleared)
        assertEquals(3, once.progressByDifficulty.getValue(Difficulty.MEDIUM).bestByVariantId.getValue("level-0001-medium").stars)
        assertTrue("first_win" in once.achievements)
    }

    @Test fun endlessScoreComesFromControllerSnapshotWithoutAddingGameScore() {
        val endlessState = wonState().copy(mode = GameMode.ENDLESS)
        val run = EndlessRunSnapshot("run-1", "0000000000000002", Difficulty.MEDIUM,
            listOf("level-0001-medium"), 0, 875, endlessState)
        val once = ProfileReducer.recordTurn(Profile(), endlessState, run)
        val twice = ProfileReducer.recordTurn(once, endlessState, run.copy(score = 1_750))
        val stats = once.statsByDifficulty.getValue(Difficulty.MEDIUM)
        assertEquals(875L, stats.endlessBestScore)
        assertEquals(1L, stats.endlessWaves)
        assertEquals(once, twice)
    }

    @Test fun acceptedTurnEventsUnlockRicochetAndTenBubbleDropOnce() {
        val initial = GameEngine.start(level(), Difficulty.MEDIUM, GameMode.CAMPAIGN, "session-2")
        val state = initial.copy(
            board = Board.of(initial.board.cells + CellBubble(Cell(0, 1), Bubble.Color(0))),
            shotsUsed = 1, turnId = 1,
        )
        val result = TurnResult(
            state = state, accepted = true,
            preview = ShotPreview(0, listOf(PathSegment(Vec2(0.0, 0.0), Vec2(1.0, 1.0), bounceAfter = true)), Cell(0, 1), Vec2(1.0, 1.0)),
            events = listOf(
                TurnEvent.Matched(listOf(Cell(0, 0), Cell(0, 1), Cell(1, 0))),
                TurnEvent.Dropped((0..9).map { Cell(it, 0) }, rocks = 0, colors = 10),
            ),
        )
        val once = ProfileReducer.recordTurn(Profile(), state, turnResult = result)
        val twice = ProfileReducer.recordTurn(once, state, turnResult = result)
        assertTrue("first_ricochet" in once.achievements)
        assertTrue("drop_10" in once.achievements)
        assertEquals(once, twice)
    }

    @Test fun delayedResultCannotReplaceAnotherActiveCampaign() {
        val active = GameEngine.start(level(), Difficulty.MEDIUM, GameMode.CAMPAIGN, "new-session")
        val profile = Profile(campaignSession = active)
        assertEquals(profile, ProfileReducer.recordTurn(profile, wonState()))
    }

    @Test fun activeCampaignOutcomeSurvivesReceiptEvictionByFreeGames() {
        val won = wonState()
        val campaign = ProfileReducer.recordTurn(Profile(), won)
        val filled = campaign.copy(appliedOutcomeReceipts = campaign.appliedOutcomeReceipts +
            (1 until MAX_OUTCOME_RECEIPTS).map { "old-$it|1|WON" })
        val endlessState = won.copy(sessionId = "wave-1", mode = GameMode.ENDLESS)
        val run = EndlessRunSnapshot("run-1", "0000000000000002", Difficulty.MEDIUM,
            listOf("level-0001-medium"), 0, 100, endlessState)
        val updated = ProfileReducer.recordTurn(filled, endlessState, run)
        assertEquals(MAX_OUTCOME_RECEIPTS, updated.appliedOutcomeReceipts.size)
        assertTrue("session-1|1|WON" in updated.appliedOutcomeReceipts)
        assertEquals(updated, ProfileReducer.recordTurn(updated, won))
    }

    @Test fun bounceWithoutMatchingDoesNotUnlockRicochetAchievement() {
        val state = GameEngine.start(level(), Difficulty.MEDIUM, GameMode.CAMPAIGN, "bounce-session")
        val turn = TurnResult(state, true, ShotPreview(0,
            listOf(PathSegment(Vec2(1.0, 1.0), Vec2(2.0, 2.0), true)), null, null))
        val result = ProfileReducer.recordTurn(Profile(), state, turnResult = turn)
        assertTrue("first_ricochet" !in result.achievements)
    }

    private fun wonState(): GameState {
        val started = GameEngine.start(level(), Difficulty.MEDIUM, GameMode.CAMPAIGN, "session-1")
        return started.copy(board = Board.EMPTY, shotsUsed = 1, turnId = 1, score = 75, outcome = Outcome.WON)
    }

    private fun level() = LevelDefinition(
        baseId = "level-0001", variantId = "level-0001-medium", revision = 1,
        semanticHash = "a".repeat(64), seedHex = "0000000000000001",
        board = Board.of(listOf(CellBubble(Cell(0, 0), Bubble.Color(0)))),
        objective = Objective.ClearColors, maxShots = 5, twoStarMaxShots = 4, threeStarMaxShots = 3,
    )
}

