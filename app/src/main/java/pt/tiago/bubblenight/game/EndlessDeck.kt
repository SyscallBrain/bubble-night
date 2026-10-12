package pt.tiago.bubblenight.game

import pt.tiago.bubblenight.core.model.Difficulty
import pt.tiago.bubblenight.core.random.SplitMix64
import pt.tiago.bubblenight.data.EndlessRunSnapshot
import pt.tiago.bubblenight.data.RuntimeLevel
import java.util.Locale

/** Endless waves only select certified variants; their ammunition seeds never change. */
object EndlessDeck {
    data class Selection(val orderedVariantIds: List<String>, val variantId: String)

    fun choose(levels: List<RuntimeLevel>, seedHex: String, difficulty: Difficulty,
               waveIndex: Long, previous: EndlessRunSnapshot? = null): Selection {
        require(levels.isNotEmpty() && waveIndex >= 0)
        val start = poolStart(waveIndex)
        val range = when (start) { 0L -> 1..100; 5L -> 101..200; 10L -> 201..400; else -> 401..600 }
        val pool = levels.filter { it.ordinal in range }.ifEmpty { levels }
            .sortedBy { it.ordinal }.map { "${it.id}-${difficulty.name.lowercase(Locale.ROOT)}" }
        val offset = waveIndex - start
        val cycle = offset / pool.size
        val position = (offset % pool.size).toInt()
        val sameDeck = previous != null && poolStart(previous.cursor) == start &&
            (previous.cursor - start) / pool.size == cycle && previous.orderedVariantIds.toSet() == pool.toSet()
        val deck = if (sameDeck) previous!!.orderedVariantIds else {
            val rng = SplitMix64(SplitMix64.seedFromHex(seedHex) xor (start * 0x10001L) xor (cycle * 0x9E3779B9L))
            val shuffled = pool.toMutableList()
            for (i in shuffled.lastIndex downTo 1) {
                val j = rng.nextInt(i + 1)
                val item = shuffled[i]; shuffled[i] = shuffled[j]; shuffled[j] = item
            }
            val recent = previous?.let { old ->
                val oldPosition = ((old.cursor - poolStart(old.cursor)) % old.orderedVariantIds.size).toInt()
                (maxOf(0, oldPosition - 2)..oldPosition).map { old.orderedVariantIds[it] }.toSet()
            }.orEmpty()
            // Delay recent entries when the next pool overlaps (notably in small pilot catalogs).
            shuffled.filterNot(recent::contains) + shuffled.filter(recent::contains)
        }
        return Selection(deck, deck[position])
    }

    private fun poolStart(wave: Long): Long = when {
        wave < 5 -> 0
        wave < 10 -> 5
        wave < 20 -> 10
        else -> 20
    }
}
