package pt.tiago.bubblenight.leveltool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetTest {
    @Test fun budgetsTightenWithDifficultyAndAlwaysFitTheSolution() {
        for (shots in 1..9) {
            val easy = ContentCommands.budgetFor("easy", shots)
            val medium = ContentCommands.budgetFor("medium", shots)
            val hard = ContentCommands.budgetFor("hard", shots)
            for ((max, two, three) in listOf(easy, medium, hard)) {
                assertTrue("three-star must fit the certified route", shots <= three)
                assertTrue(three <= two && two <= max)
            }
            assertTrue(easy.first >= medium.first && medium.first >= hard.first)
            assertTrue(easy.third >= medium.third && medium.third >= hard.third)
        }
        // Long routes keep their margin: no fixed cap.
        assertEquals(Triple(20, 17, 15), ContentCommands.budgetFor("easy", 12))
        assertEquals(Triple(15, 14, 13), ContentCommands.budgetFor("hard", 12))
        // Typical certified route of three shots.
        assertEquals(Triple(11, 8, 6), ContentCommands.budgetFor("easy", 3))
        assertEquals(Triple(8, 6, 5), ContentCommands.budgetFor("medium", 3))
        assertEquals(Triple(6, 5, 4), ContentCommands.budgetFor("hard", 3))
    }
}
