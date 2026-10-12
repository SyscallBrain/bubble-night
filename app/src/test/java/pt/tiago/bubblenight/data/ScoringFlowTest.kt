package pt.tiago.bubblenight.data

import org.junit.Assert.assertEquals
import org.junit.Test
import pt.tiago.bubblenight.core.model.Board
import pt.tiago.bubblenight.core.model.Bubble
import pt.tiago.bubblenight.core.model.Cell
import pt.tiago.bubblenight.core.model.CellBubble
import pt.tiago.bubblenight.core.model.Difficulty
import pt.tiago.bubblenight.core.model.GameMode
import pt.tiago.bubblenight.core.model.LevelDefinition
import pt.tiago.bubblenight.core.model.Objective
import pt.tiago.bubblenight.core.model.Outcome
import pt.tiago.bubblenight.core.rules.GameEngine

/** Real engine turns followed by the reducer, to check the points that reach the profile. */
class ScoringFlowTest {
    /** Two blues on the ceiling with a gap at column 4 (x = 9, straight above the launcher). */
    private fun pairLevel(maxShots: Int = 5) = LevelDefinition(
        baseId = "level-0001", variantId = "level-0001-medium", revision = 1,
        semanticHash = "b".repeat(64), seedHex = "0000000000000001",
        board = Board.of(listOf(CellBubble(Cell(0, 3), Bubble.Color(0)), CellBubble(Cell(0, 5), Bubble.Color(0)))),
        objective = Objective.ClearColors, maxShots = maxShots, twoStarMaxShots = maxShots - 1, threeStarMaxShots = maxShots - 2,
    )

    @Test fun campaignWinRecordsMatchPointsPlusUnusedShotBonus() {
        val start = GameEngine.start(pairLevel(), Difficulty.MEDIUM, GameMode.CAMPAIGN, "campaign-1")
        val turn = GameEngine.shoot(start, 0)
        assertEquals(Outcome.WON, turn.state.outcome)
        // Medium x1.5: 3 popped x10 = 45; 4 unused shots x50 = 300.
        assertEquals(345L, turn.state.score)
        assertEquals(turn.state.score, turn.state.breakdown.total)
        val profile = ProfileReducer.recordTurn(Profile(), turn.state, turnResult = turn)
        assertEquals(345L, profile.statsByDifficulty.getValue(Difficulty.MEDIUM).campaignScore)
        assertEquals(345L, profile.progressByDifficulty.getValue(Difficulty.MEDIUM).bestByVariantId.getValue("level-0001-medium").bestScore)
    }

    @Test fun endlessWaveAddsToRunTotalAndRecordsBest() {
        val start = GameEngine.start(pairLevel(), Difficulty.MEDIUM, GameMode.ENDLESS, "endless-1")
        val turn = GameEngine.shoot(start, 0)
        assertEquals(Outcome.WON, turn.state.outcome)
        // Same rule the view model uses: the run keeps its prior total and adds the finished wave.
        val priorRun = EndlessRunSnapshot("run-1", "0000000000000002", Difficulty.MEDIUM, listOf("level-0001-medium"), 0, 500, start)
        val run = priorRun.copy(score = priorRun.score + turn.state.score)
        val profile = ProfileReducer.recordTurn(Profile(), turn.state, run, turnResult = turn)
        assertEquals(845L, profile.statsByDifficulty.getValue(Difficulty.MEDIUM).endlessBestScore)
    }

    @Test fun savedSessionsWithoutBreakdownKeepTheirPoints() {
        val start = GameEngine.start(pairLevel(), Difficulty.MEDIUM, GameMode.CAMPAIGN, "legacy").copy(score = 200)
        val turn = GameEngine.shoot(start, 0)
        assertEquals(200L + 345L, turn.state.score)
        assertEquals(turn.state.score, turn.state.breakdown.total)
    }

    @Test fun oldProfilesAreTrimmedToTheReceiptBounds() {
        val crowded = Profile(
            appliedTurnReceipts = (1..4096).map { "s|$it" },
            appliedOutcomeReceipts = (1..4096).map { "s|$it|WON" },
        )
        val migrated = ProfileMigrations.migrate(crowded)
        assertEquals(MAX_TURN_RECEIPTS, migrated.appliedTurnReceipts.size)
        assertEquals(MAX_OUTCOME_RECEIPTS, migrated.appliedOutcomeReceipts.size)
        assertEquals("s|4096", migrated.appliedTurnReceipts.last())
        ProfileValidator.requireValid(migrated)
    }
}
