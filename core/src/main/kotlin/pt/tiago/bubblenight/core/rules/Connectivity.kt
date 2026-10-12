package pt.tiago.bubblenight.core.rules

import pt.tiago.bubblenight.core.geometry.HexGrid
import pt.tiago.bubblenight.core.model.*

object Connectivity {
    fun colorGroup(board: Board, start: Cell): List<Cell> {
        val color = board.at(start) as? Bubble.Color ?: return emptyList()
        val seen = linkedSetOf(start)
        val queue = ArrayDeque<Cell>().apply { add(start) }
        while (queue.isNotEmpty()) {
            val cell = queue.removeFirst()
            for (n in HexGrid.neighbors(cell)) if (n !in seen && board.at(n) == color) {
                seen += n; queue += n
            }
        }
        return seen.sortedWith(compareBy({ it.row }, { it.column }))
    }

    fun ceilingConnected(board: Board): Set<Cell> {
        val seen = linkedSetOf<Cell>()
        val queue = ArrayDeque<Cell>()
        board.cells.asSequence().map { it.cell }.filter { it.row == 0 }.sortedBy { it.column }.forEach {
            seen += it; queue += it
        }
        while (queue.isNotEmpty()) {
            val cell = queue.removeFirst()
            for (n in HexGrid.neighbors(cell)) if (board.occupied(n) && seen.add(n)) queue += n
        }
        return seen
    }
}
