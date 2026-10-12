package pt.tiago.bubblenight.data

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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
import pt.tiago.bubblenight.core.rules.GameEngine

class ProfileValidatorTest {
    @Test fun seenTipsMustBeKnownAndSurviveExport() {
        val profile = Profile(seenTips = setOf(TIP_STAR, TIP_ROCK))
        assertTrue(ProfileValidator.violations(profile).isEmpty())
        assertTrue(ProfileJson.prepareImport(ProfileJson.export(profile)).seenTips == setOf(TIP_ROCK, TIP_STAR))
        assertFalse(ProfileValidator.violations(Profile(seenTips = setOf("ghost"))).isEmpty())
    }

    @Test fun hiddenTestModeSurvivesExportAndDefaultsOff() {
        assertFalse(Profile().settings.unlockAllLevels)
        val profile = Profile(settings = Settings(unlockAllLevels = true))
        assertTrue(ProfileJson.prepareImport(ProfileJson.export(profile)).settings.unlockAllLevels)
    }

    @Test fun acceptsEngineStartedSnapshotAndProfile() {
        val state = readyState()
        assertTrue(ProfileValidator.violations(Profile(campaignSession = state)).toString(),
            ProfileValidator.violations(Profile(campaignSession = state)).isEmpty())
    }

    @Test fun rejectsBadTurnCounterAndIneligibleAmmo() {
        val state = readyState()
        assertTrue(ProfileValidator.violations(Profile(campaignSession = state.copy(turnId = 1))).isNotEmpty())
        assertTrue(ProfileValidator.violations(Profile(campaignSession = state.copy(currentColor = 5))).isNotEmpty())
    }

    @Test fun illegalColumnProducesValidationErrorsWithoutGeometryThrowing() {
        val state = readyState().copy(board = Board(listOf(CellBubble(Cell(0, 99), Bubble.Color(0)))))
        assertTrue(ProfileValidator.violations(Profile(campaignSession = state)).isNotEmpty())
    }

    @Test fun rejectsSnapshotThatCreatesRocks() {
        val state = readyState().copy(shotsUsed = 1, turnId = 1,
            board = Board.of(readyState().board.cells + CellBubble(Cell(0, 1), Bubble.Rock)))
        assertTrue(ProfileValidator.violations(Profile(campaignSession = state)).isNotEmpty())
    }

    @Test fun rejectsDetachedOrDangerousReadyBoards() {
        val state = readyState()
        val detached = state.copy(board = Board.of(state.board.cells + CellBubble(Cell(5, 0), Bubble.Rock)))
        assertTrue(ProfileValidator.violations(Profile(campaignSession = detached)).isNotEmpty())
        val dangerous = state.copy(board = Board.of(state.board.cells + CellBubble(Cell(13, 0), Bubble.Rock)))
        assertTrue(ProfileValidator.violations(Profile(campaignSession = dangerous)).isNotEmpty())
    }

    @Test fun rejectsAReadyStateThatAlreadyWon() {
        val state = readyState().copy(board = Board.EMPTY)
        assertTrue(ProfileValidator.violations(Profile(campaignSession = state)).isNotEmpty())
    }

    @Test fun stableVariantIdMustMatchDifficulty() {
        val state = readyState().copy(level = readyState().level.copy(variantId = "medium"))
        assertTrue(ProfileValidator.violations(Profile(campaignSession = state)).isNotEmpty())
    }

    @Test fun rejectsMalformedAndDuplicateReceipts() {
        assertTrue(ProfileValidator.violations(Profile(appliedOutcomeReceipts = listOf("bad"))).isNotEmpty())
        assertTrue(ProfileValidator.violations(Profile(appliedTurnReceipts = listOf("session|1", "session|1"))).isNotEmpty())
        assertFalse(ProfileValidator.violations(Profile(appliedTurnReceipts = listOf("session-1|1"))).isNotEmpty())
    }

    @Test fun rejectsOutcomeThatContradictsObjective() {
        val ready = readyState()
        val lostButWon = ready.copy(board = Board.EMPTY, shotsUsed = 1, turnId = 1, outcome = Outcome.LOST,
            lossReason = pt.tiago.bubblenight.core.model.LossReason.NO_SHOTS)
        assertTrue(ProfileValidator.violations(Profile(campaignSession = lostButWon)).isNotEmpty())
    }

    private fun readyState(): GameState {
        val level = LevelDefinition(
            baseId = "level-0001", variantId = "level-0001-medium", revision = 1,
            semanticHash = "a".repeat(64), seedHex = "0000000000000001",
            board = Board.of(listOf(CellBubble(Cell(0, 0), Bubble.Color(0)))),
            objective = Objective.ClearColors, maxShots = 5, twoStarMaxShots = 4, threeStarMaxShots = 3,
        )
        return GameEngine.start(level, Difficulty.MEDIUM, GameMode.CAMPAIGN, "session-1")
    }
}

