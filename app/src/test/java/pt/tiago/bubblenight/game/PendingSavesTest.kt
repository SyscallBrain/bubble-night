package pt.tiago.bubblenight.game

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.bubblenight.core.model.*
import pt.tiago.bubblenight.core.rules.GameEngine
import pt.tiago.bubblenight.data.*
import java.io.IOException

class PendingSavesTest {
    @Test fun failedVictoryBlocksReplacementAndRetryKeepsStarsExactlyOnce() = runBlocking {
        val level = LevelDefinition("level-0001", "level-0001-medium", 1, "b".repeat(64), "0000000000000001",
            Board.of(listOf(CellBubble(Cell(0, 3), Bubble.Color(0)), CellBubble(Cell(0, 5), Bubble.Color(0)))),
            Objective.ClearColors, 5, 4, 3)
        val start = GameEngine.start(level, Difficulty.MEDIUM, GameMode.CAMPAIGN, "first")
        val win = GameEngine.shoot(start, 0)
        assertEquals(Outcome.WON, win.state.outcome)
        var stored = Profile(campaignSession = start)
        var diskAvailable = false
        val queue = PendingSaves<Profile>()
        queue.enqueue {
            if (!diskAvailable) throw IOException("disk unavailable")
            ProfileReducer.recordTurn(stored, win.state, turnResult = win).also { stored = it }
        }
        var replacementRan = false
        try {
            queue.flush()
            replacementRan = true
            stored = stored.copy(campaignSession = start.copy(sessionId = "second"))
            fail("Should block replacement")
        } catch (_: IOException) { }
        assertFalse(replacementRan)
        assertTrue(queue.failed)
        assertTrue(queue.pending)
        assertEquals(start, stored.campaignSession)
        diskAvailable = true
        queue.flush()
        stored = stored.copy(campaignSession = start.copy(sessionId = "second"))
        queue.flush()
        assertFalse(queue.failed)
        assertFalse(queue.pending)
        assertEquals(3, stored.progressByDifficulty.getValue(Difficulty.MEDIUM).bestByVariantId.getValue(level.variantId).stars)
        assertEquals(1L, stored.statsByDifficulty.getValue(Difficulty.MEDIUM).campaignWins)
    }

    @Test fun laterWritesNeverOvertakeFailedWrite() = runBlocking {
        val queue = PendingSaves<Int>()
        val order = mutableListOf<Int>()
        var failFirst = true
        queue.enqueue { if (failFirst) throw IOException(); order.add(1); 1 }
        queue.enqueue { order.add(2); 2 }
        try { queue.flush(); fail("Expected failure") } catch (_: IOException) { }
        assertTrue(order.isEmpty())
        failFirst = false
        assertEquals(2, queue.flush())
        assertEquals(listOf(1, 2), order)
    }
}
