package pt.tiago.bubblenight.game

import org.junit.Assert.*
import org.junit.Test
import pt.tiago.bubblenight.core.model.Difficulty
import pt.tiago.bubblenight.data.EndlessRunSnapshot
import pt.tiago.bubblenight.data.RuntimeLevel

class EndlessDeckTest {
    private fun levels(size: Int) = (1..size).map { n ->
        RuntimeLevel("level-%04d".format(n), n, "orbit-%02d".format((n - 1) / 50 + 1), "Teste", "test", emptyList(), 1, emptyMap())
    }

    @Test fun wavePoolsFollowProgressionAndAllSelectedVariantsExist() {
        val catalog = levels(600)
        for ((index, range) in listOf(0L to 1..100, 5L to 101..200, 10L to 201..400, 20L to 401..600)) {
            val chosen = EndlessDeck.choose(catalog, "0000000000000001", Difficulty.HARD, index)
            assertTrue(chosen.orderedVariantIds.all { it.removePrefix("level-").substringBefore('-').toInt() in range })
            assertEquals(chosen.orderedVariantIds.size, chosen.orderedVariantIds.distinct().size)
        }
    }

    @Test fun savedDeckSurvivesResumeAndDoesNotRepeatWithinCycle() {
        val catalog = levels(600)
        val seed = "a0123456789abcde"
        var previous: EndlessRunSnapshot? = null
        val selected = mutableSetOf<String>()
        for (wave in 20L..219L) {
            val result = EndlessDeck.choose(catalog, seed, Difficulty.MEDIUM, wave, previous)
            assertTrue(selected.add(result.variantId))
            val snapshot = EndlessRunSnapshot("run", seed, Difficulty.MEDIUM, result.orderedVariantIds, wave, 0)
            assertEquals(result, EndlessDeck.choose(catalog, seed, Difficulty.MEDIUM, wave, snapshot))
            previous = snapshot
        }
    }

    @Test fun pilotCatalogCanContinueIndefinitelyUsingAvailableLevels() {
        val catalog = levels(10)
        var previous: EndlessRunSnapshot? = null
        repeat(150) { index ->
            val chosen = EndlessDeck.choose(catalog, "0000000000000010", Difficulty.EASY, index.toLong(), previous)
            assertEquals(10, chosen.orderedVariantIds.size)
            assertTrue(chosen.variantId.endsWith("-easy"))
            previous = EndlessRunSnapshot("run", "0000000000000010", Difficulty.EASY, chosen.orderedVariantIds, index.toLong(), 0)
        }
    }
}
