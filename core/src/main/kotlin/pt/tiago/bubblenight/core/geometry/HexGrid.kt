package pt.tiago.bubblenight.core.geometry

import pt.tiago.bubblenight.core.model.Cell
import pt.tiago.bubblenight.core.model.Vec2
import kotlin.math.abs
import kotlin.math.sqrt

object HexGrid {
    const val MIN_ROW = 0
    const val MAX_ROW = 13
    const val DANGER_Y = 24.0
    const val RADIUS = 1.0
    const val EPSILON = 1e-8
    const val WIDTH = 18.0
    const val HEIGHT = 32.0
    val LAUNCHER = Vec2(9.0, 29.0)

    fun columnCount(row: Int): Int = if (row % 2 == 0) 9 else 8
    fun isLegal(cell: Cell): Boolean = cell.row in MIN_ROW..MAX_ROW && cell.column in 0 until columnCount(cell.row)
    fun center(cell: Cell): Vec2 {
        require(isLegal(cell)) { "Illegal cell $cell" }
        return centers[cell.row][cell.column]
    }

    /** Aiming asks for neighbours and centres thousands of times per drag, so both are precomputed. */
    fun neighbors(cell: Cell): List<Cell> {
        require(isLegal(cell)) { "Illegal cell $cell" }
        return neighborTable[cell.row][cell.column]
    }

    private val centers: List<List<Vec2>> = (MIN_ROW..MAX_ROW).map { row ->
        (0 until columnCount(row)).map { column -> Vec2(1.0 + 2.0 * column + (row % 2), 1.0 + sqrt(3.0) * row) }
    }

    private val neighborTable: List<List<List<Cell>>> = (MIN_ROW..MAX_ROW).map { row ->
        (0 until columnCount(row)).map { column -> computeNeighbors(Cell(row, column)) }
    }

    private fun computeNeighbors(cell: Cell): List<Cell> {
        val candidates = mutableListOf(Cell(cell.row, cell.column - 1), Cell(cell.row, cell.column + 1))
        val offset = if (cell.row % 2 == 0) -1 else 0
        for (r in listOf(cell.row - 1, cell.row + 1)) {
            val c0 = cell.column + offset
            candidates += Cell(r, c0)
            candidates += Cell(r, c0 + 1)
        }
        return candidates.filter(::isLegal).distinct().sortedWith(compareBy({ it.row }, { it.column }))
    }

    fun reciprocalNeighbors(): Boolean = (0..MAX_ROW).flatMap { r ->
        (0 until columnCount(r)).map { Cell(r, it) }
    }.all { cell -> neighbors(cell).all { cell in neighbors(it) && distance(center(cell), center(it)) <= 2.0 + EPSILON } }

    fun distance(a: Vec2, b: Vec2): Double = hypot(a.x - b.x, a.y - b.y)
    private fun hypot(x: Double, y: Double) = sqrt(x * x + y * y)
}
