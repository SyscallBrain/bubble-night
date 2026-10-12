package pt.tiago.bubblenight.core.geometry

import pt.tiago.bubblenight.core.model.*
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Continuous, frame-independent ray tracing and discrete legal-slot projection. */
object ShotTracer {
    private const val MAX_BOUNCES = 8
    private const val MAX_LENGTH = 120.0
    private data class Hit(val t: Double, val point: Vec2, val cells: List<Cell>, val ceiling: Boolean, val wall: Boolean)

    fun preview(board: Board, angleMilliDegrees: Int): ShotPreview {
        if (angleMilliDegrees !in -75_000..75_000)
            return ShotPreview(angleMilliDegrees, emptyList(), null, null, ShotRejection.ANGLE_OUT_OF_RANGE)
        val theta = Math.toRadians(angleMilliDegrees / 1000.0)
        var p = HexGrid.LAUNCHER
        var d = Vec2(StrictMath.sin(theta), -StrictMath.cos(theta))
        var total = 0.0
        var bounces = 0
        val segments = mutableListOf<PathSegment>()
        while (total < MAX_LENGTH) {
            val hit = firstHit(board, p, d)
                ?: return ShotPreview(angleMilliDegrees, segments, null, null, ShotRejection.TRACE_LIMIT)
            if (hit.t < -HexGrid.EPSILON) return ShotPreview(angleMilliDegrees, segments, null, null, ShotRejection.TRACE_LIMIT)
            total += hit.t
            segments += PathSegment(p, hit.point, hit.wall)
            if (total > MAX_LENGTH) return ShotPreview(angleMilliDegrees, segments, null, hit.point, ShotRejection.TRACE_LIMIT)
            if (hit.wall) {
                if (++bounces > MAX_BOUNCES) return ShotPreview(angleMilliDegrees, segments, null, hit.point, ShotRejection.TRACE_LIMIT)
                d = Vec2(-d.x, d.y)
                // Move one tiny deterministic epsilon away from the wall to avoid a zero-distance repeat.
                p = Vec2(hit.point.x + d.x * HexGrid.EPSILON * 4, hit.point.y + d.y * HexGrid.EPSILON * 4)
                total += HexGrid.EPSILON * 4
                continue
            }
            val slot = SnapResolver.resolve(board, hit.point, hit.cells, hit.ceiling)
                ?: return ShotPreview(angleMilliDegrees, segments, null, hit.point, ShotRejection.NO_LEGAL_SLOT)
            return ShotPreview(angleMilliDegrees, segments, slot, hit.point)
        }
        return ShotPreview(angleMilliDegrees, segments, null, null, ShotRejection.TRACE_LIMIT)
    }

    private fun firstHit(board: Board, p: Vec2, d: Vec2): Hit? {
        val hits = mutableListOf<Hit>()
        if (d.y < -HexGrid.EPSILON) {
            val t = (1.0 - p.y) / d.y
            if (t >= -HexGrid.EPSILON) hits += Hit(t.coerceAtLeast(0.0), Vec2(p.x + t * d.x, 1.0), emptyList(), true, false)
        }
        val wallX = if (d.x < 0) 1.0 else 17.0
        if (kotlin.math.abs(d.x) > HexGrid.EPSILON) {
            val t = (wallX - p.x) / d.x
            if (t >= -HexGrid.EPSILON) hits += Hit(t.coerceAtLeast(0.0), Vec2(wallX, p.y + t * d.y), emptyList(), false, true)
        }
        for (entry in board.cells) {
            val c = HexGrid.center(entry.cell)
            val ox = p.x - c.x; val oy = p.y - c.y
            val b = 2.0 * (ox * d.x + oy * d.y)
            val c0 = ox * ox + oy * oy - 4.0
            val disc = b * b - 4.0 * c0
            if (disc < -HexGrid.EPSILON) continue
            val root = sqrt(disc.coerceAtLeast(0.0))
            val roots = listOf((-b - root) / 2.0, (-b + root) / 2.0)
            val t = roots.firstOrNull { it >= -HexGrid.EPSILON && (ox + it * d.x) * d.x + (oy + it * d.y) * d.y < HexGrid.EPSILON } ?: continue
            hits += Hit(t.coerceAtLeast(0.0), Vec2(p.x + t * d.x, p.y + t * d.y), listOf(entry.cell), false, false)
        }
        if (hits.isEmpty()) return null
        val minT = hits.minOf { it.t }
        val tied = hits.filter { kotlin.math.abs(it.t - minT) <= HexGrid.EPSILON }
        val bubbles = tied.filter { it.cells.isNotEmpty() }.flatMap { it.cells }.distinct().sortedWith(compareBy({ it.row }, { it.column }))
        // Bubble/ceiling contact wins a tie with a wall, in stable cell order.
        if (bubbles.isNotEmpty()) return Hit(minT, Vec2(p.x + minT * d.x, p.y + minT * d.y), bubbles, false, false)
        val ceil = tied.firstOrNull { it.ceiling }
        return ceil ?: tied.firstOrNull { it.wall }
    }

}
