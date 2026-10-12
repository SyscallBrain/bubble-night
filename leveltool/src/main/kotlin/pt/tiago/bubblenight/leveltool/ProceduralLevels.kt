package pt.tiago.bubblenight.leveltool

import pt.tiago.bubblenight.core.geometry.HexGrid
import pt.tiago.bubblenight.core.model.Cell
import pt.tiago.bubblenight.core.random.SplitMix64

/**
 * Procedural candidates for orbits 2–12. Shapes grow from the ceiling, colours get at
 * least three cells each, and every variant is checked by the same rules as authored
 * blueprints before the solver certifies it. Nothing here simulates physics: solvability
 * is decided only by the solver and replay.
 */
internal object ProceduralLevels {
    private val colorIds = listOf("blue", "purple", "green", "orange", "pink", "cyan")

    /** Board size and colour profile per difficulty, plus the candidate shot budget before rebalance. */
    internal data class BoardProfile(
        val depthLimit: Map<String, Int>,
        val colorRange: Map<String, IntRange>,
        val cellRange: Map<String, IntRange>,
        val budgets: Map<String, Int>,
    )

    /** Orbits 2–12 as first published: 12–24 cells, solved in about three shots. */
    val STANDARD = BoardProfile(
        depthLimit = mapOf("easy" to 7, "medium" to 8, "hard" to 9),
        colorRange = mapOf("easy" to 3..4, "medium" to 4..5, "hard" to 5..6),
        cellRange = mapOf("easy" to 12..16, "medium" to 16..22, "hard" to 18..24),
        budgets = mapOf("easy" to 14, "medium" to 11, "hard" to 9),
    )

    /**
     * Larger boards that need a plan over several shots. The candidate budget is generous only so
     * the solver can prove a route; `rebalance` then derives the published budgets from it.
     */
    val LARGE = BoardProfile(
        depthLimit = mapOf("easy" to 7, "medium" to 8, "hard" to 9),
        colorRange = mapOf("easy" to 3..4, "medium" to 4..5, "hard" to 5..6),
        cellRange = mapOf("easy" to 26..32, "medium" to 32..40, "hard" to 38..46),
        budgets = mapOf("easy" to 30, "medium" to 30, "hard" to 30),
    )
    private val difficulties = listOf("easy", "medium", "hard")

    /** Families whose orbitRange includes the orbit (content/templates/structural-families.json). */
    val families2 = listOf("twin-supports", "cross-bridge", "spine", "alternating-columns", "anchored-islands",
        "fan", "side-pockets", "staircase", "open-corridor", "shallow-arch")
    val families3 = families2 + listOf("central-rescue", "lateral-rescue")

    fun familiesFor(orbit: Int): List<String> = if (orbit >= 3) families3 else families2

    /** Seed for one base: depends only on the catalog seed, the ordinal and its redraw count. */
    fun levelSeed(seed: Long, ordinal: Int, redraw: Int): Long =
        seed xor (ordinal.toLong() * 0x9E3779B97F4A7C15uL.toLong()) xor (redraw.toLong() * 0xC2B2AE3D27D4EB4FuL.toLong())

    fun level(ordinal: Int, orbit: Int, familyId: String, rng: SplitMix64, profile: BoardProfile = STANDARD, revision: Int = 1): SourceLevel {
        // One objective per level, the same in every difficulty:
        // 0 clear colors, 1 rescue stars (orbit 3+), 2 clear a colour set (orbit 5+; no stars).
        val kind = when {
            orbit >= 5 -> rng.nextInt(3)
            orbit >= 3 -> rng.nextInt(2)
            else -> 0
        }
        val variants = difficulties.associateWith { difficulty ->
            val budget = profile.budgets.getValue(difficulty)
            val board = board(rng, difficulty, if (kind == 1) 1 + rng.nextInt(3) else 0, profile)
            SourceVariant(
                seedHex = java.lang.Long.toUnsignedString(rng.nextLong(), 16).padStart(16, '0'),
                board = board,
                objective = when (kind) {
                    1 -> SourceObjective("rescue_stars", total = board.count { it.kind == "star" })
                    2 -> SourceObjective("clear_color_set", colors = targetColors(board, rng))
                    else -> SourceObjective("clear_colors")
                },
                maxShots = budget,
                threeStarMaxShots = budget - 2,
                twoStarMaxShots = budget - 1,
                expectedMetrics = ExpectedMetrics(familyId, 0),
            )
        }
        // titlePt is Portuguese content data ("Orbit N · level M"); the app localizes titles itself.
        return SourceLevel(
            schemaVersion = 1, engineVersion = 1, id = "level-%04d".format(ordinal), ordinal = ordinal,
            orbitId = "orbit-%02d".format(orbit), titlePt = "Órbita $orbit · nível ${(ordinal - 1) % 50 + 1}",
            familyId = familyId, learningTags = listOf("procedural"), revision = revision,
            authorNotes = "Procedural candidate; certified by solver and replay.", variants = variants,
        )
    }

    /**
     * Each colour grows as connected blocks of three or four cells, anchored to the
     * existing shape. Accessible groups are what the authored levels that solve share.
     */
    /** Repeats until the board meets the colour rules; a rejected attempt consumes RNG deterministically. */
    /** One or two colours present on the board, in colour-id order; the rest may stay. */
    private fun targetColors(board: List<SourceCell>, rng: SplitMix64): List<String> {
        val present = colorIds.filter { id -> board.any { it.kind == "color" && it.color == id } }
        val count = 1 + rng.nextInt(2)
        return present.toMutableList().also { shuffle(it, rng) }.take(count).sortedBy { colorIds.indexOf(it) }
    }

    private fun board(rng: SplitMix64, difficulty: String, stars: Int, profile: BoardProfile): List<SourceCell> {
        repeat(200) {
            attemptBoard(rng, difficulty, stars, profile)?.let { return it }
        }
        throw IllegalStateException("could not generate a $difficulty board that meets the colour rules")
    }

    private fun attemptBoard(rng: SplitMix64, difficulty: String, stars: Int, profile: BoardProfile): List<SourceCell>? {
        val limit = profile.depthLimit.getValue(difficulty)
        val range = profile.cellRange.getValue(difficulty)
        val target = range.first + rng.nextInt(range.last - range.first + 1)
        val colorRange = profile.colorRange.getValue(difficulty)
        val colorCount = colorRange.first + rng.nextInt(colorRange.last - colorRange.first + 1)
        val palette = colorIds.toMutableList().also { shuffle(it, rng) }.take(colorCount)
        val filled = LinkedHashSet<Cell>()
        val colorOf = HashMap<Cell, String>()
        fun open(cell: Cell) = HexGrid.isLegal(cell) && cell.row <= limit && cell !in filled
        // Returns false (and leaves the shape unchanged) when the block cannot reach three cells.
        fun addBlock(color: String, size: Int, anchors: List<Cell>): Boolean {
            val candidates = anchors.filter(::open).distinct()
            if (candidates.isEmpty()) return false
            val block = mutableListOf(candidates[rng.nextInt(candidates.size)])
            while (block.size < size) {
                val next = block.flatMap(HexGrid::neighbors).filter { open(it) && it !in block }.distinct()
                    .sortedWith(compareBy({ it.row }, { it.column }))
                if (next.isEmpty()) break
                block += next[rng.nextInt(next.size)]
            }
            if (block.size < 3) return false
            filled += block
            block.forEach { colorOf[it] = color }
            return true
        }
        // Seed block on the ceiling, then blocks anchored to what already exists.
        if (!addBlock(palette[0], 3 + rng.nextInt(2), (0..8).map { Cell(0, it) })) return null
        var blockIndex = 1
        var failures = 0
        while (filled.size < target && failures < 50) {
            val anchors = filled.flatMap(HexGrid::neighbors).filter(::open)
            if (anchors.isEmpty()) return null
            if (addBlock(palette[blockIndex % palette.size], 3 + rng.nextInt(2), anchors)) failures = 0 else failures++
            blockIndex++
        }
        val counts = HashMap<String, Int>()
        colorOf.values.forEach { counts.merge(it, 1, Int::plus) }
        if (counts.size !in colorRange || counts.values.any { it < 3 }) return null
        // Rocks (orbit 2+), about 8 % of cells, only where they keep every colour at three or more cells.
        val cells = filled.toMutableList().also { shuffle(it, rng) }
        val rocks = HashSet<Cell>()
        for (cell in cells.take(cells.size * 8 / 100)) {
            val color = colorOf.getValue(cell)
            if (counts.getValue(color) > 3) {
                counts.merge(color, -1, Int::plus)
                rocks += cell
            }
        }
        // Stars sit below the ceiling; a colour keeps at least three cells after losing one.
        val starCells = HashSet<Cell>()
        for (cell in cells) {
            if (starCells.size == stars) break
            if (cell.row == 0 || cell in rocks) continue
            val color = colorOf.getValue(cell)
            if (counts.getValue(color) > 3) {
                counts.merge(color, -1, Int::plus)
                starCells += cell
            }
        }
        if (starCells.size < stars) return null
        return filled.sortedWith(compareBy({ it.row }, { it.column })).map { cell ->
            when {
                cell in rocks -> SourceCell(cell.row, cell.column, "rock")
                cell in starCells -> SourceCell(cell.row, cell.column, "star")
                else -> SourceCell(cell.row, cell.column, "color", colorOf.getValue(cell))
            }
        }
    }

    private fun <T> shuffle(list: MutableList<T>, rng: SplitMix64) {
        for (i in list.lastIndex downTo 1) {
            val j = rng.nextInt(i + 1)
            val item = list[i]; list[i] = list[j]; list[j] = item
        }
    }
}
