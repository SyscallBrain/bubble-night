package pt.tiago.bubblenight.game

import org.junit.Assert.*
import org.junit.Test
import pt.tiago.bubblenight.core.model.*
import pt.tiago.bubblenight.core.rules.GameEngine
import pt.tiago.bubblenight.ui.UiPoint

class AimGuideTest {
    private val level = LevelDefinition("level-0001", "level-0001-easy", 1, "a".repeat(64), "0000000000000001",
        Board.of(listOf(CellBubble(Cell(0, 0), Bubble.Color(0)))), Objective.ClearColors, 9, 6, 4)

    @Test fun fullGuideReachesContactEvenAfterSevenBounces() {
        val state = GameEngine.start(level, Difficulty.EASY, GameMode.CAMPAIGN, "guide")
        val preview = GameEngine.preview(state, 75000)
        assertNull(preview.rejection)
        assertEquals(8, preview.segments.size)
        val points = guidePoints(preview.segments, Guide.FULL)
        assertEquals(9, points.size)
        val contact = preview.segments.last().to
        assertEquals(UiPoint(contact.x.toFloat(), contact.y.toFloat()), points.last())
        assertTrue(guidePoints(preview.segments, Guide.SHORT).size < points.size)
    }

    @Test fun readyAtStarThresholdMustAllowForOneMoreShotButWonKeepsActualStars() {
        val start = GameEngine.start(level, Difficulty.EASY, GameMode.CAMPAIGN, "stars")
        assertEquals(3, potentialStars(start.copy(shotsUsed = 3)))
        assertEquals(2, potentialStars(start.copy(shotsUsed = 4)))
        assertEquals(1, potentialStars(start.copy(shotsUsed = 6)))
        assertEquals(3, potentialStars(start.copy(shotsUsed = 4, outcome = Outcome.WON)))
        assertEquals(0, potentialStars(start.copy(shotsUsed = 9, outcome = Outcome.LOST)))
    }
}
