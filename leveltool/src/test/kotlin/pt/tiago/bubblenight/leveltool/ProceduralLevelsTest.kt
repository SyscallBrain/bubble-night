package pt.tiago.bubblenight.leveltool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.bubblenight.core.random.SplitMix64

class ProceduralLevelsTest {
    @Test
    fun sameSeedProducesSameBoards() {
        val first = ProceduralLevels.level(51, 2, "fan", SplitMix64(7))
        val second = ProceduralLevels.level(51, 2, "fan", SplitMix64(7))
        assertEquals(first, second)
    }

    @Test
    fun everyGeneratedVariantPassesTheAuthoringRules() {
        val rng = SplitMix64(20261010)
        repeat(30) { index ->
            val level = ProceduralLevels.level(51 + index, 2, ProceduralLevels.familiesFor(2)[index % 10], rng)
            level.variants.forEach { (name, variant) ->
                val errors = mutableListOf<String>()
                ContentCommands.validateVariant(level, variant, name, errors::add)
                assertTrue("${level.id}/$name: $errors", errors.isEmpty())
                val colors = variant.board.filter { it.kind == "color" }.groupingBy { it.color }.eachCount()
                assertTrue("${level.id}/$name: colors with fewer than three cells $colors", colors.values.all { it >= 3 })
            }
        }
    }

    @Test
    fun orbitThreeRescueLevelsHaveMatchingStarObjectivesBelowTheCeiling() {
        val rng = SplitMix64(3)
        var rescue = 0
        repeat(40) { index ->
            val level = ProceduralLevels.level(101 + index, 3, ProceduralLevels.familiesFor(3)[index % 10], rng)
            level.variants.forEach { (name, variant) ->
                val errors = mutableListOf<String>()
                ContentCommands.validateVariant(level, variant, name, errors::add)
                assertTrue("${level.id}/$name: $errors", errors.isEmpty())
                val stars = variant.board.count { it.kind == "star" }
                if (variant.objective.type == "rescue_stars") {
                    rescue++
                    assertEquals(stars, variant.objective.total)
                    assertTrue(stars >= 1)
                }
                assertTrue(variant.board.none { it.kind == "star" && it.r == 0 })
            }
        }
        assertTrue("orbit 3 must contain rescue levels", rescue > 0)
    }

    @Test
    fun orbitFiveColourSetLevelsTargetPresentColoursWithoutStars() {
        val rng = SplitMix64(5)
        var colorSets = 0
        repeat(60) { index ->
            val level = ProceduralLevels.level(201 + index, 5, ProceduralLevels.familiesFor(5)[index % 10], rng)
            level.variants.forEach { (name, variant) ->
                val errors = mutableListOf<String>()
                ContentCommands.validateVariant(level, variant, name, errors::add)
                assertTrue("${level.id}/$name: $errors", errors.isEmpty())
                if (variant.objective.type == "clear_color_set") {
                    colorSets++
                    val present = variant.board.filter { it.kind == "color" }.map { it.color }.toSet()
                    assertTrue(variant.objective.colors.orEmpty().all { it in present })
                    assertTrue(variant.board.none { it.kind == "star" })
                }
            }
        }
        assertTrue("orbit 5 must contain color-set levels", colorSets > 0)
    }
}
