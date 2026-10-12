package pt.tiago.bubblenight.core

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import pt.tiago.bubblenight.core.geometry.HexGrid
import pt.tiago.bubblenight.core.geometry.ShotTracer
import pt.tiago.bubblenight.core.geometry.SnapResolver
import pt.tiago.bubblenight.core.model.*
import pt.tiago.bubblenight.core.random.SplitMix64
import pt.tiago.bubblenight.core.rules.Connectivity
import pt.tiago.bubblenight.core.rules.GameEngine
import pt.tiago.bubblenight.core.rules.Scoring
import pt.tiago.bubblenight.core.serialization.StateHasher

class GameEngineTest {
    private fun level(board: Board, objective: Objective, seed: String = "0000000000000000", maxShots: Int = 8) = LevelDefinition(
        baseId = "fixture", variantId = "fixture-medium", revision = 1, semanticHash = "fixture-hash",
        seedHex = seed, board = board, objective = objective, maxShots = maxShots,
        twoStarMaxShots = maxShots, threeStarMaxShots = 1,
    )

    @Test fun gridNeighborsAreReciprocalAtBothParitiesAndEdges() {
        assertTrue(HexGrid.reciprocalNeighbors())
        assertEquals(6, HexGrid.neighbors(Cell(4, 4)).size)
        assertTrue(HexGrid.neighbors(Cell(0, 0)).all(HexGrid::isLegal))
        assertEquals(2.0, HexGrid.distance(HexGrid.center(Cell(5, 3)), HexGrid.center(Cell(5, 4))), 1e-8)
    }

    @Test fun verticalPreviewAndShotUseSameCeilingSlot() {
        val state = GameEngine.start(level(Board.of(listOf(CellBubble(Cell(0, 4), Bubble.Color(0)))), Objective.ClearColors),
            Difficulty.MEDIUM, GameMode.CAMPAIGN, "s")
        val preview = GameEngine.preview(state, 0)
        assertNull(preview.rejection)
        assertNotNull(preview.target)
        val result = GameEngine.shoot(state, 0)
        assertTrue(result.accepted)
        assertEquals(preview.target, result.preview?.target)
        assertEquals(1, result.state.shotsUsed)
        assertEquals(2, result.state.board.count(Bubble.Color(0))) // a two-bubble group must remain
    }

    @Test fun extremeAngleReflectsContinuouslyAndReplayIsStable() {
        val empty = Board.EMPTY
        val first = ShotTracer.preview(empty, 75_000)
        val replay = ShotTracer.preview(empty, 75_000)
        assertNull(first.rejection)
        assertNotNull(first.target)
        assertEquals(first, replay)
        assertTrue(first.segments.count { it.bounceAfter } >= 2)
        assertEquals(1.0, first.segments.last().to.y, 1e-8)
    }

    @Test fun simultaneousBubbleContactsUseStableSnapTieBreak() {
        val board = Board.of(listOf(
            CellBubble(Cell(1, 3), Bubble.Color(ColorIds.BLUE)),
            CellBubble(Cell(1, 4), Bubble.Color(ColorIds.PURPLE)),
        ))
        val preview = ShotTracer.preview(board, 0)
        assertNull(preview.rejection)
        assertEquals(Cell(2, 4), preview.target) // exact contact lies on this empty, tangent legal slot
        assertEquals(preview, ShotTracer.preview(board, 0))
    }

    @Test fun completelyBlockedNeighborRingReturnsNoLegalSlotWithoutMutation() {
        val center = Cell(6, 4)
        val ring = HexGrid.neighbors(center)
        val board = Board.of((ring + center).map { CellBubble(it, Bubble.Rock) })
        val contact = HexGrid.center(center)
        assertNull(SnapResolver.resolve(board, contact, listOf(center)))

        val fullBoard = Board.of((0..HexGrid.MAX_ROW).flatMap { row ->
            (0 until HexGrid.columnCount(row)).map { CellBubble(Cell(row, it), Bubble.Rock) }
        })
        val valid = GameEngine.start(level(Board.of(listOf(CellBubble(Cell(0, 4), Bubble.Color(0)))), Objective.ClearColors),
            Difficulty.MEDIUM, GameMode.CAMPAIGN, "blocked")
        val blockedState = valid.copy(board = fullBoard)
        val result = GameEngine.shoot(blockedState, 0)
        assertFalse(result.accepted)
        assertEquals(ShotRejection.NO_LEGAL_SLOT, result.rejection)
        assertEquals(blockedState, result.state)
    }

    @Test fun oneThousandAnglesHaveIdenticalPreviewAndShotGeometry() {
        val state = GameEngine.start(level(Board.of(listOf(CellBubble(Cell(0, 4), Bubble.Color(0)))), Objective.ClearColors),
            Difficulty.MEDIUM, GameMode.CAMPAIGN, "sweep")
        for (angle in -75_000..75_000 step 150) {
            val preview = GameEngine.preview(state, angle)
            val shot = GameEngine.shoot(state, angle)
            if (preview.rejection == null) {
                assertTrue("angle $angle", shot.accepted)
                assertEquals(preview.target, shot.preview?.target)
                assertEquals(preview.segments, shot.preview?.segments)
            } else {
                assertFalse("angle $angle", shot.accepted)
                assertEquals(preview.rejection, shot.rejection)
                assertEquals(state, shot.state)
            }
        }
    }

    @Test fun clearingTargetColorWinsWithOtherColorsLeftAndLastShot() {
        val board = Board.of(listOf(
            CellBubble(Cell(0, 3), Bubble.Color(ColorIds.BLUE)),
            CellBubble(Cell(0, 4), Bubble.Color(ColorIds.BLUE)),
            CellBubble(Cell(0, 8), Bubble.Color(ColorIds.PURPLE)),
        ))
        val state = GameEngine.start(level(board, Objective.ClearColorSet(listOf(ColorIds.BLUE)), maxShots = 1),
            Difficulty.MEDIUM, GameMode.CAMPAIGN, "last-target").copy(currentColor = ColorIds.BLUE)
        val result = GameEngine.shoot(state, 0)
        assertTrue(result.accepted)
        assertEquals(Outcome.WON, result.state.outcome)
        assertEquals(1, result.state.shotsUsed)
        assertEquals(1, result.state.board.count(Bubble.Color(ColorIds.PURPLE)))
    }

    @Test fun clearColorsAllowsAnchoredRocksToRemain() {
        val board = Board.of(listOf(
            CellBubble(Cell(0, 3), Bubble.Color(ColorIds.BLUE)),
            CellBubble(Cell(0, 4), Bubble.Color(ColorIds.BLUE)),
            CellBubble(Cell(0, 8), Bubble.Rock),
        ))
        val state = GameEngine.start(level(board, Objective.ClearColors, maxShots = 1),
            Difficulty.EASY, GameMode.CAMPAIGN, "clear-colors").copy(currentColor = ColorIds.BLUE)
        val result = GameEngine.shoot(state, 0)
        assertEquals(Outcome.WON, result.state.outcome)
        assertEquals(1, result.state.board.count(Bubble.Rock))
    }

    @Test fun clearingSupportDropsRockAndRescuesStarOnLastShot() {
        val board = Board.of(listOf(
            CellBubble(Cell(0, 4), Bubble.Color(ColorIds.BLUE)),
            CellBubble(Cell(1, 3), Bubble.Color(ColorIds.BLUE)),
            CellBubble(Cell(1, 4), Bubble.Color(ColorIds.BLUE)),
            CellBubble(Cell(1, 2), Bubble.Rock),
            CellBubble(Cell(2, 2), Bubble.Star),
        ))
        val state = GameEngine.start(level(board, Objective.RescueStars, maxShots = 1), Difficulty.MEDIUM, GameMode.CAMPAIGN, "cascade")
            .copy(currentColor = ColorIds.BLUE)
        val result = GameEngine.shoot(state, 0)
        assertTrue("vertical fixture must find a legal placement", result.accepted)
        assertEquals(Outcome.WON, result.state.outcome)
        assertEquals(1, result.state.shotsUsed)
        assertEquals(1, result.state.rescuedStars)
        assertEquals(0, result.state.board.cells.size)
        assertTrue(result.events.any { it is TurnEvent.Matched && it.cells.size == 4 })
        assertTrue(result.events.any { it is TurnEvent.Dropped && it.rocks == 1 })
        assertTrue(result.events.contains(TurnEvent.Rescued(1)))
        // Medium x1.5: 4 popped x10 + falling rock 10 + rescued star 100 = 150 -> 225.
        assertEquals(225L, result.state.score)
        assertEquals(result.state.score, result.state.breakdown.total)
        assertEquals(3, GameEngine.stars(result.state))
    }

    @Test fun zenIgnoresShotBudgetAndNeverScores() {
        val board = Board.of(listOf(
            CellBubble(Cell(0, 2), Bubble.Color(ColorIds.BLUE)),
            CellBubble(Cell(0, 4), Bubble.Rock),
        ))
        var state = GameEngine.start(level(board, Objective.ClearColors, maxShots = 1),
            Difficulty.EASY, GameMode.ZEN, "zen-budget")
        var accepted = 0
        repeat(3) {
            if (state.outcome != Outcome.READY) return@repeat
            val result = GameEngine.shoot(state, 0)
            if (result.accepted) accepted++
            state = result.state
        }
        assertTrue("Zen must accept shots beyond the level limit", accepted >= 2)
        assertNotEquals(LossReason.NO_SHOTS, state.lossReason)
        assertEquals(0L, state.score)
    }

    @Test fun zenDangerIsTerminalAndUsesNeutralReason() {
        val cells = (0..11).map { row -> CellBubble(Cell(row, 4), Bubble.Rock) }.toMutableList()
        cells += CellBubble(Cell(12, 4), Bubble.Color(ColorIds.BLUE))
        val zen = GameEngine.start(level(Board.of(cells), Objective.ClearColors, maxShots = 1),
            Difficulty.EASY, GameMode.ZEN, "zen")
        val result = GameEngine.shoot(zen, 0)
        assertTrue(result.accepted)
        assertEquals(Outcome.LOST, result.state.outcome)
        assertEquals(LossReason.ZEN_DANGER, result.state.lossReason)
        assertTrue(result.events.contains(TurnEvent.Lost(LossReason.ZEN_DANGER)))
    }

    @Test fun invalidAngleDoesNotConsumeShotOrAdvanceRandomState() {
        val state = GameEngine.start(level(Board.of(listOf(CellBubble(Cell(0, 4), Bubble.Color(0)))), Objective.ClearColors), Difficulty.EASY, GameMode.CAMPAIGN, "invalid")
        val result = GameEngine.shoot(state, 75_001)
        assertFalse(result.accepted)
        assertEquals(state, result.state)
        assertEquals(ShotRejection.ANGLE_OUT_OF_RANGE, result.rejection)
    }

    @Test fun swappingTwiceRestoresQueueAndDoesNotChangeRng() {
        val state = GameEngine.start(level(Board.of(listOf(
            CellBubble(Cell(0, 0), Bubble.Color(0)), CellBubble(Cell(0, 1), Bubble.Color(1)),
        )), Objective.ClearColors), Difficulty.HARD, GameMode.CAMPAIGN, "swap")
        assertEquals(state, GameEngine.swap(GameEngine.swap(state)))
    }

    @Test fun splitMix64MatchesPublishedAlgorithmVectorAndHexRoundTrip() {
        val rng = SplitMix64(0L)
        assertEquals("e220a8397b1dcdaf", java.lang.Long.toUnsignedString(rng.nextLong(), 16))
        val restored = SplitMix64.fromHex(rng.stateHex())
        assertEquals(rng.nextLong(), restored.nextLong())
    }

    @Test fun canonicalStateHashIgnoresSessionIdButTracksLogicalChanges() {
        val base = GameEngine.start(level(Board.of(listOf(CellBubble(Cell(0, 4), Bubble.Color(0)))), Objective.ClearColors),
            Difficulty.EASY, GameMode.CAMPAIGN, "one")
        assertEquals(StateHasher.sha256(base), StateHasher.sha256(base.copy(sessionId = "another")))
        assertNotEquals(StateHasher.sha256(base), StateHasher.sha256(base.copy(currentColor = ColorIds.PURPLE)))
        val setA = base.copy(level = base.level.copy(objective = Objective.ClearColorSet(listOf(0, 1))))
        val setB = base.copy(level = base.level.copy(objective = Objective.ClearColorSet(listOf(1, 0))))
        assertEquals(StateHasher.sha256(setA), StateHasher.sha256(setB))
    }

    @Test fun serializableStateRoundTripsBoardAmmoAndUnsignedRngState() {
        val board = Board.of(listOf(
            CellBubble(Cell(0, 4), Bubble.Color(ColorIds.BLUE)),
            CellBubble(Cell(1, 3), Bubble.Rock),
        ))
        val state = GameEngine.start(level(board, Objective.ClearColors, seed = "fedcba9876543210"),
            Difficulty.HARD, GameMode.CAMPAIGN, "serialized")
        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(state)
        val decoded = json.decodeFromString<GameState>(encoded)
        assertEquals(state, decoded)
        assertEquals(16, decoded.rngStateHex.length)
    }

    @Test fun colorSearchDoesNotTreatRockOrStarAsMatchingColor() {
        val board = Board.of(listOf(
            CellBubble(Cell(0, 4), Bubble.Color(0)),
            CellBubble(Cell(1, 3), Bubble.Rock),
            CellBubble(Cell(1, 4), Bubble.Star),
        ))
        assertEquals(listOf(Cell(0, 4)), Connectivity.colorGroup(board, Cell(0, 4)))
        assertEquals(setOf(Cell(0, 4), Cell(1, 3), Cell(1, 4)), Connectivity.ceilingConnected(board))
    }

    @Test fun harderDifficultiesScoreMoreForTheSameTurn() {
        val easy = Scoring.turn(Difficulty.EASY, 0, popped = 3, droppedColors = 4, droppedRocks = 0, droppedStars = 0)
        val medium = Scoring.turn(Difficulty.MEDIUM, 0, popped = 3, droppedColors = 4, droppedRocks = 0, droppedStars = 0)
        val hard = Scoring.turn(Difficulty.HARD, 0, popped = 3, droppedColors = 4, droppedRocks = 0, droppedStars = 0)
        // 3 x10 + 4 falling x20 x(1 + 4/4) = 30 + 160 = 190
        assertEquals(190L, easy.points)
        assertEquals(285L, medium.points)
        assertEquals(380L, hard.points)
        assertTrue(Scoring.unusedShotBonus(Difficulty.HARD, 2) > Scoring.unusedShotBonus(Difficulty.EASY, 2))
    }

    @Test fun streakMultipliesConsecutiveRemovingShotsAndResetsOnMiss() {
        val first = Scoring.turn(Difficulty.EASY, 0, 3, 0, 0, 0)
        val second = Scoring.turn(Difficulty.EASY, first.streak, 3, 0, 0, 0)
        val miss = Scoring.turn(Difficulty.EASY, second.streak, 0, 0, 0, 0)
        val capped = Scoring.turn(Difficulty.EASY, 9, 3, 0, 0, 0)
        assertEquals(30L, first.points)
        assertEquals(60L, second.points)
        assertEquals(30L, second.parts.streak)
        assertEquals(0, miss.streak)
        assertEquals(0L, miss.points)
        assertEquals(Scoring.MAX_STREAK_MULTIPLIER, capped.multiplier)
    }

    @Test fun winScoreBreakdownAddsUpAndIncludesUnusedShots() {
        val board = Board.of(listOf(
            CellBubble(Cell(0, 3), Bubble.Color(ColorIds.BLUE)),
            CellBubble(Cell(0, 5), Bubble.Color(ColorIds.BLUE)),
        ))
        val state = GameEngine.start(level(board, Objective.ClearColors, maxShots = 5), Difficulty.HARD, GameMode.CAMPAIGN, "breakdown")
            .copy(currentColor = ColorIds.BLUE)
        val result = GameEngine.shoot(state, 0)
        assertEquals(Outcome.WON, result.state.outcome)
        // Hard x2: 3 popped x10 = 60; 4 unused shots x50 = 400.
        assertEquals(60L, result.state.breakdown.popped)
        assertEquals(400L, result.state.breakdown.unusedShots)
        assertEquals(460L, result.state.score)
        assertEquals(2, result.events.filterIsInstance<TurnEvent.Scored>().size)
    }
}
