package pt.tiago.bubblenight.core.geometry

import pt.tiago.bubblenight.core.model.Board
import pt.tiago.bubblenight.core.model.Cell
import pt.tiago.bubblenight.core.model.Vec2

/** Discrete grid projection for a continuous contact; public for validators and geometry fixtures. */
object SnapResolver {
    fun resolve(board: Board, contactPoint: Vec2, touchedCells: List<Cell>, ceilingContact: Boolean = false): Cell? {
        val touched = touchedCells.distinct().sortedWith(compareBy({ it.row }, { it.column }))
        val candidates = linkedSetOf<Cell>()
        if (ceilingContact) for (c in 0 until HexGrid.columnCount(0)) candidates += Cell(0, c)
        for (cell in touched) {
            if (!HexGrid.isLegal(cell)) return null
            candidates += HexGrid.neighbors(cell)
        }
        return candidates.asSequence()
            .filter(HexGrid::isLegal)
            .filterNot(board::occupied)
            .filter { candidate ->
                val center = HexGrid.center(candidate)
                val dx = center.x - contactPoint.x; val dy = center.y - contactPoint.y
                dx * dx + dy * dy <= 4.0 + HexGrid.EPSILON &&
                    touched.all { touchedCell ->
                        if (candidate !in HexGrid.neighbors(touchedCell)) true
                        else {
                            val tc = HexGrid.center(touchedCell)
                            (center.x - tc.x) * (contactPoint.x - tc.x) +
                                (center.y - tc.y) * (contactPoint.y - tc.y) > HexGrid.EPSILON
                        }
                    }
            }
            .filter { candidate -> !segmentBlocked(contactPoint, HexGrid.center(candidate), board, touched.toSet()) }
            .minWithOrNull(compareBy<Cell>({ sqDistance(HexGrid.center(it), contactPoint) }, { -it.row }, { it.column }))
    }

    private fun segmentBlocked(a: Vec2, b: Vec2, board: Board, excluded: Set<Cell>): Boolean {
        val vx = b.x - a.x; val vy = b.y - a.y; val len2 = vx * vx + vy * vy
        if (len2 <= HexGrid.EPSILON * HexGrid.EPSILON) return false
        for (entry in board.cells) if (entry.cell !in excluded) {
            val c = HexGrid.center(entry.cell)
            val u = (((c.x - a.x) * vx + (c.y - a.y) * vy) / len2).coerceIn(0.0, 1.0)
            val dx = a.x + u * vx - c.x; val dy = a.y + u * vy - c.y
            if (dx * dx + dy * dy < 1.0 - HexGrid.EPSILON) return true
        }
        return false
    }

    private fun sqDistance(a: Vec2, b: Vec2): Double {
        val x = a.x - b.x; val y = a.y - b.y
        return x * x + y * y
    }
}
