package pt.tiago.bubblenight.data

import kotlinx.serialization.Serializable
import pt.tiago.bubblenight.core.model.Difficulty
import pt.tiago.bubblenight.core.model.GameState

const val CURRENT_PROFILE_SCHEMA = 1
const val CURRENT_CONTENT_VERSION = 1
const val CURRENT_ENGINE_VERSION = 1

@Serializable
data class Settings(
    val sound: Boolean = true,
    val vibration: Boolean = true,
    val symbols: Boolean = true,
    val preciseAim: Boolean = false,
    val leftHanded: Boolean = false,
    val reducedMotion: Boolean = false,
    /** Kept for profile compatibility; the guide now follows the difficulty (see fullAimGuide). */
    val aimAssist: Boolean = true,
    val lastDifficulty: Difficulty = Difficulty.MEDIUM,
    /** Shows the whole trajectory on every difficulty instead of the difficulty's own guide. */
    val fullAimGuide: Boolean = false,
    /** Hidden test mode (seven taps on the version in Settings): every level can be opened. */
    val unlockAllLevels: Boolean = false,
)

@Serializable
data class LevelBest(
    val stars: Int = 0,
    val fewestShots: Int? = null,
    val bestScore: Long = 0,
)

@Serializable
data class CampaignProgress(
    val completedVariantIds: Set<String> = emptySet(),
    val bestByVariantId: Map<String, LevelBest> = emptyMap(),
)

@Serializable
data class DifficultyStats(
    val campaignWins: Long = 0,
    val campaignLosses: Long = 0,
    val campaignShots: Long = 0,
    val campaignScore: Long = 0,
    val bubblesCleared: Long = 0,
    val endlessBestScore: Long = 0,
    val endlessWaves: Long = 0,
    val endlessRuns: Long = 0,
    val zenSessions: Long = 0,
)

/** Snapshot of the app-owned endless run state; deck selection remains in the controller. */
@Serializable
data class EndlessRunSnapshot(
    val runId: String,
    val seedHex: String,
    val difficulty: Difficulty,
    val orderedVariantIds: List<String>,
    val cursor: Long,
    val score: Long,
    val currentGame: GameState? = null,
)

@Serializable
sealed interface FreeSession {
    @Serializable data class Zen(val state: GameState) : FreeSession
    @Serializable data class Endless(val run: EndlessRunSnapshot) : FreeSession
}

@Serializable
data class Profile(
    val schemaVersion: Int = CURRENT_PROFILE_SCHEMA,
    val contentVersion: Int = CURRENT_CONTENT_VERSION,
    val engineVersion: Int = CURRENT_ENGINE_VERSION,
    val revision: Long = 0,
    val settings: Settings = Settings(),
    val progressByDifficulty: Map<Difficulty, CampaignProgress> = emptyMap(),
    val statsByDifficulty: Map<Difficulty, DifficultyStats> = emptyMap(),
    val campaignSession: GameState? = null,
    val freeSession: FreeSession? = null,
    /** Bounded recent history; active terminal sessions stay pinned when older receipts expire. */
    val appliedOutcomeReceipts: List<String> = emptyList(),
    val appliedTurnReceipts: List<String> = emptyList(),
    val achievements: Set<String> = emptySet(),
    /** Piece/objective introductions already shown in a level preparation (see KNOWN_TIPS). */
    val seenTips: Set<String> = emptySet(),
    val tutorialCompleted: Boolean = false,
    val zenFavorites: Set<String> = emptySet(),
    val dailySession: DailySession? = null,
    /** Last 270 results. Key is ISO date + / + difficulty enum name. */
    val dailyResults: Map<String, DailyBest> = emptyMap(),
)

@Serializable
data class DailySession(val date: String, val state: GameState)

@Serializable
data class DailyBest(val stars: Int, val score: Long, val fewestShots: Int)

@Serializable
data class ProfileBackupEnvelope(
    val schemaVersion: Int,
    val contentVersion: Int,
    val engineVersion: Int,
    val profile: Profile,
    val sha256: String,
    /** 1: historical compact payload; 2: recursively sorted JSON object keys, before migration. */
    val checksumVersion: Int = 1,
)

data class ImportPreview(
    val profile: Profile,
    val completedLevels: Int,
    val stars: Int,
    val hasCampaignSession: Boolean,
    val hasFreeSession: Boolean,
)

enum class ProfileRecovery { NONE, RECOVERED_FROM_BACKUP, RECOVERED_FROM_IMPORT }

