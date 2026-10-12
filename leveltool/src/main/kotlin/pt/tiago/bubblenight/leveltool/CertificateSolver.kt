package pt.tiago.bubblenight.leveltool

import pt.tiago.bubblenight.core.model.*
import pt.tiago.bubblenight.core.rules.Connectivity
import pt.tiago.bubblenight.core.rules.GameEngine

internal data class SolverAction(val swapBefore: Boolean, val angleMilliDegrees: Int)
internal data class SolverResult(
    val actions: List<SolverAction>,
    val aimWindows: List<AimWindow>,
    val metrics: List<ShotMetrics>,
    val initialStateHash: String,
    val finalState: GameState,
    val expandedStates: Int,
    val elapsedMillis: Long,
)

/** Bounded single-thread beam search. A miss or timeout is reported as uncertified, never as unsolvable. */
internal object CertificateSolver {
    private data class Node(val state: GameState, val actions: List<SolverAction>, val rank: Long, val bounces: Int = 0, val swapped: Boolean = false, val bankSeen: Boolean = false)
    private data class Child(val node: Node, val preview: ShotPreview)

    fun solve(
        level: LevelDefinition,
        difficulty: Difficulty,
        width: Int = 128,
        maxExpanded: Int = 100_000,
        timeoutMillis: Long = 30_000,
        minBounces: Int = 0,
        requireSwap: Boolean = false,
        bankSide: Int = 0,
        minShots: Int = 1,
    ): SolverResult? {
        require(width > 0 && maxExpanded > 0 && timeoutMillis > 0)
        val started = System.nanoTime()
        val deadline = started + timeoutMillis * 1_000_000
        val initial = GameEngine.start(level, difficulty, GameMode.CAMPAIGN, "leveltool-${level.variantId}")
        val initialHash = stateHash(initial)
        var beam = listOf(Node(initial, emptyList(), rank(initial)))
        val visited = mutableSetOf(initialHash)
        var expanded = 0
        while (beam.isNotEmpty() && expanded < maxExpanded && System.nanoTime() < deadline) {
            val nextBeam = linkedMapOf<String, Child>()
            for (node in beam) {
                if (expanded >= maxExpanded || System.nanoTime() >= deadline) break
                expanded++
                val perState = linkedMapOf<String, Child>()
                for (swap in listOf(false, true)) {
                    val ready = if (swap) GameEngine.swap(node.state) else node.state
                    for ((angle, preview) in candidateAngles(ready)) {
                        if (System.nanoTime() >= deadline) break
                        val result = GameEngine.shoot(ready, angle)
                        if (!result.accepted) continue
                        val state = result.state
                        val action = SolverAction(swap, angle)
                        val firstBank = preview.segments.firstOrNull { it.bounceAfter }?.to?.x
                        val sideSeen = node.bankSeen || (bankSide < 0 && firstBank == 1.0) || (bankSide > 0 && firstBank == 17.0)
                        val child = Node(state, node.actions + action, rank(state), node.bounces + bounceCount(preview),
                            node.swapped || (swap && node.state.currentColor != node.state.nextColor), sideSeen)
                        val hash = stateHash(state) + ":${child.bounces.coerceAtMost(minBounces)}:${if (requireSwap) child.swapped else false}:${if (bankSide != 0) child.bankSeen else false}"
                        if (state.outcome == Outcome.WON) {
                            val actions = child.actions
                            if (actions.size > level.threeStarMaxShots || actions.size < minShots || child.bounces < minBounces ||
                                (requireSwap && !child.swapped) || (bankSide != 0 && !child.bankSeen)) continue
                            val robust = analyzeAimWindows(initial, actions, difficulty, deadline)
                            if (robust != null) {
                                val replay = replay(initial, actions) ?: continue
                                val metrics = actionMetrics(initial, actions, robust.windows) ?: continue
                                return SolverResult(
                                    actions = actions,
                                    aimWindows = robust.windows,
                                    metrics = metrics,
                                    initialStateHash = initialHash,
                                    finalState = replay,
                                    expandedStates = expanded,
                                    elapsedMillis = (System.nanoTime() - started) / 1_000_000,
                                )
                            }
                            continue
                        }
                        if (state.outcome != Outcome.READY || child.actions.size >= level.threeStarMaxShots) continue
                        if (!visited.add(hash)) continue
                        val key = hash
                        val old = perState[key]
                        if (old == null || bounceCount(preview) < bounceCount(old.preview)) perState[key] = Child(child, preview)
                    }
                }
                for ((hash, child) in perState) {
                    val old = nextBeam[hash]
                    if (old == null || child.node.rank < old.node.rank) nextBeam[hash] = child
                }
            }
            beam = nextBeam.values.asSequence().map { it.node }.sortedBy { it.rank }.take(width).toList()
        }
        return null
    }

    /** Representatives of contiguous angular intervals; refine target transitions to 0.1 degrees. */
    private fun candidateAngles(state: GameState): List<Pair<Int, ShotPreview>> {
        fun signature(p: ShotPreview): String = "${p.target}:${p.rejection}:${bounceCount(p)}:${p.segments.firstOrNull { it.bounceAfter }?.to?.x}"
        val samples = sortedMapOf<Int, ShotPreview>()
        for (angle in -75_000..75_000 step 500) samples[angle] = GameEngine.preview(state, angle)
        val coarse = samples.entries.map { it.key to it.value }
        for ((left, right) in coarse.zipWithNext()) if (signature(left.second) != signature(right.second)) {
            for (angle in left.first + 100 until right.first step 100) samples[angle] = GameEngine.preview(state, angle)
        }
        val runs = mutableListOf<Pair<Int, ShotPreview>>()
        var low: Int? = null
        var high = 0
        var key: String? = null
        fun flush() {
            val first = low ?: return
            val middle = (first + high) / 2
            val preview = GameEngine.preview(state, middle)
            if (preview.target != null && preview.rejection == null) runs += middle to preview
        }
        for ((angle, preview) in samples) {
            val currentKey = signature(preview)
            if (currentKey != key) { flush(); low = angle; key = currentKey }
            high = angle
        }
        flush()
        return runs.sortedWith(compareBy({ bounceCount(it.second) }, { kotlin.math.abs(it.first) }, { it.first }))
    }

    private data class WindowAnalysis(val windows: List<AimWindow>)

    private fun analyzeAimWindows(
        initial: GameState,
        actions: List<SolverAction>,
        difficulty: Difficulty,
        deadline: Long,
    ): WindowAnalysis? {
        var state = initial
        val threshold = when (difficulty) { Difficulty.EASY -> 2_000; Difficulty.MEDIUM -> 1_000; Difficulty.HARD -> 500 }
        val windows = mutableListOf<AimWindow>()
        for ((index, action) in actions.withIndex()) {
            if (System.nanoTime() >= deadline) return null
            if (action.swapBefore) state = GameEngine.swap(state)
            val base = GameEngine.shoot(state, action.angleMilliDegrees)
            if (!base.accepted) return null
            val baseState = base.state
            fun sameAt(angle: Int): Boolean {
                val changed = GameEngine.shoot(state, angle)
                return changed.accepted && changed.state == baseState
            }
            fun boundary(direction: Int): Int {
                var safe = action.angleMilliDegrees
                // Probe one millidegree at a time; the first logical transition bounds the tested interval.
                repeat(threshold) {
                    if (System.nanoTime() >= deadline) return safe
                    val candidate = safe + direction
                    if (candidate !in -75_000..75_000 || !sameAt(candidate)) return safe
                    safe = candidate
                }
                return safe
            }
            val lower = boundary(-1)
            val upper = boundary(1)
            val width = upper - lower
            if (width < threshold) return null
            windows += AimWindow(index, lower, upper, width)
            state = base.state
        }
        return WindowAnalysis(windows)
    }

    private fun actionMetrics(initial: GameState, actions: List<SolverAction>, windows: List<AimWindow>): List<ShotMetrics>? {
        var state = initial
        val metrics = mutableListOf<ShotMetrics>()
        for ((index, action) in actions.withIndex()) {
            if (action.swapBefore) state = GameEngine.swap(state)
            val result = GameEngine.shoot(state, action.angleMilliDegrees)
            if (!result.accepted) return null
            val target = result.preview?.target ?: return null
            val matched = result.events.filterIsInstance<TurnEvent.Matched>().sumOf { it.cells.size }
            val dropped = result.events.filterIsInstance<TurnEvent.Dropped>().sumOf { it.cells.size }
            val rescued = result.events.filterIsInstance<TurnEvent.Rescued>().sumOf { it.count }
            metrics += ShotMetrics(
                target = CertificateCell(target.row, target.column),
                bounces = result.preview?.segments?.count { it.bounceAfter } ?: 0,
                matchedCells = matched,
                droppedCells = dropped,
                rescuedStars = rescued,
                testedAimWindowMilliDegrees = windows[index].testedWidthMilliDegrees,
            )
            state = result.state
        }
        return metrics
    }

    private fun replay(initial: GameState, actions: List<SolverAction>): GameState? {
        var state = initial
        for ((index, action) in actions.withIndex()) {
            if (state.outcome != Outcome.READY) return null
            if (action.swapBefore) state = GameEngine.swap(state)
            val result = GameEngine.shoot(state, action.angleMilliDegrees)
            if (!result.accepted) return null
            state = result.state
            if (state.outcome == Outcome.WON && index != actions.lastIndex) return null
        }
        return state.takeIf { it.outcome == Outcome.WON }
    }

    private fun rank(state: GameState): Long {
        val remaining = when (val objective = state.level.objective) {
            Objective.ClearColors -> state.board.cells.count { it.bubble is Bubble.Color }
            Objective.RescueStars -> state.board.cells.count { it.bubble == Bubble.Star }
            is Objective.ClearColorSet -> state.board.cells.count { entry ->
                (entry.bubble as? Bubble.Color)?.id?.let(objective.colorIds::contains) == true
            }
        }
        val droppedPotential = state.board.cells.count { it.cell.row > 0 }
        val colorGroups = state.board.colors().sumOf { color ->
            state.board.cells.count { (it.bubble as? Bubble.Color)?.id == color }
        }
        val detached = state.board.cells.size - Connectivity.ceilingConnected(state.board).size
        val dangerPenalty = state.board.cells.maxOfOrNull { it.cell.row }?.toLong()?.times(4) ?: 0L
        return remaining * 10_000L + colorGroups * 20L + state.board.cells.size * 5L -
            droppedPotential - state.score / 100L + dangerPenalty + detached * 500L + state.shotsUsed * 25L
    }

    private fun bounceCount(preview: ShotPreview): Int = preview.segments.count { it.bounceAfter }

    fun stateHash(state: GameState): String = pt.tiago.bubblenight.core.serialization.StateHasher.sha256(state)
}
