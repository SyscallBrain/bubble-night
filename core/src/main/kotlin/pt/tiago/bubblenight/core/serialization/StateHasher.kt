package pt.tiago.bubblenight.core.serialization

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import pt.tiago.bubblenight.core.model.*
import java.security.MessageDigest

/** Versioned canonical logical-state encoding for certificates and deterministic replay. */
object StateHasher {
    const val HASH_VERSION = 1
    private val canonicalJson = Json {
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }

    fun canonical(state: GameState): String = canonicalJson.encodeToString(
        LogicalStateV1(
            hashVersion = HASH_VERSION,
            engineVersion = state.level.engineVersion,
            baseId = state.level.baseId,
            variantId = state.level.variantId,
            levelRevision = state.level.revision,
            levelSemanticHash = state.level.semanticHash,
            seedHex = state.level.seedHex.lowercase(),
            levelBoard = state.level.board,
            objective = when (val objective = state.level.objective) {
                is Objective.ClearColorSet -> objective.copy(colorIds = objective.colorIds.sorted())
                else -> objective
            },
            maxShots = state.level.maxShots,
            twoStarMaxShots = state.level.twoStarMaxShots,
            threeStarMaxShots = state.level.threeStarMaxShots,
            difficulty = state.difficulty,
            mode = state.mode,
            board = state.board,
            currentColor = state.currentColor,
            nextColor = state.nextColor,
            rngStateHex = state.rngStateHex.lowercase(),
            shotsUsed = state.shotsUsed,
            score = state.score,
            turnId = state.turnId,
            outcome = state.outcome,
            lossReason = state.lossReason,
            rescuedStars = state.rescuedStars,
        ),
    )

    fun sha256(state: GameState): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(canonical(state).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    @Serializable
    private data class LogicalStateV1(
        val hashVersion: Int,
        val engineVersion: Int,
        val baseId: String,
        val variantId: String,
        val levelRevision: Int,
        val levelSemanticHash: String,
        val seedHex: String,
        val levelBoard: Board,
        val objective: Objective,
        val maxShots: Int,
        val twoStarMaxShots: Int,
        val threeStarMaxShots: Int,
        val difficulty: Difficulty,
        val mode: GameMode,
        val board: Board,
        val currentColor: Int,
        val nextColor: Int,
        val rngStateHex: String,
        val shotsUsed: Int,
        val score: Long,
        val turnId: Long,
        val outcome: Outcome,
        val lossReason: LossReason?,
        val rescuedStars: Int,
    )
}
