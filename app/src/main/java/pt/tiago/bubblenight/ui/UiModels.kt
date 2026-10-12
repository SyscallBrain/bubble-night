package pt.tiago.bubblenight.ui

import androidx.annotation.StringRes
import pt.tiago.bubblenight.R

/** Screen-facing data contract. These models deliberately contain no engine or Android types. */
enum class UiScreen {
    START, MAP, PREPARATION, GAME, FREE_MODE_SELECT, FREE_GAME, SETTINGS, STATISTICS, CREDITS, PAUSED, WON, LOST, DAILY, ZEN_SELECT,
}

enum class UiDifficulty(
    @StringRes val label: Int,
    @StringRes val scoreFactor: Int,
    @StringRes val aimGuide: Int,
    @StringRes val summary: Int,
) {
    EASY(R.string.difficulty_easy, R.string.score_factor_easy, R.string.aim_guide_easy, R.string.difficulty_summary_easy),
    MEDIUM(R.string.difficulty_medium, R.string.score_factor_medium, R.string.aim_guide_medium, R.string.difficulty_summary_medium),
    HARD(R.string.difficulty_hard, R.string.score_factor_hard, R.string.aim_guide_hard, R.string.difficulty_summary_hard),
}

enum class UiBubbleKind { COLOR, ROCK, STAR }
enum class UiBubbleColor { BLUE, PURPLE, GREEN, ORANGE, PINK, CYAN }
enum class UiEventKind { SHOT, MATCH, DROP, BOUNCE, VICTORY, DEFEAT }
enum class UiSetting { SOUND, VIBRATION, SYMBOLS, PRECISE_AIM, FULL_AIM_GUIDE, LEFT_HANDED, REDUCED_MOTION }
enum class UiFreeMode { INFINITE, ZEN }

data class UiBubble(
    /** Row/column are board coordinates; rows use the core's staggered hex layout. */
    val row: Int,
    val column: Int,
    val kind: UiBubbleKind = UiBubbleKind.COLOR,
    val color: UiBubbleColor? = null,
    /** Stable unique board identity, used to pair before/after snapshots during drop animations. */
    val id: String = "",
)

data class UiPoint(val x: Float, val y: Float)

/** Point coordinates use the logical 18x32 board. Invalid previews never trigger a shot. */
data class UiPreview(
    val angleMilliDegrees: Int = 0,
    val points: List<UiPoint> = emptyList(),
    val valid: Boolean = false,
    val destination: UiPoint? = null,
    val rejectionLabel: String? = null,
    val targetDescription: String? = null,
)

/** Core supplies event snapshots; the UI only interpolates their visual presentation. */
data class UiAnimationEvent(
    val id: Long,
    val kind: UiEventKind,
    val bubble: UiBubble? = null,
    val from: UiPoint? = null,
    val to: UiPoint? = null,
    /** Logical 18x32 board-space path, sampled by core for the real shot trajectory. */
    val trajectory: List<UiPoint> = emptyList(),
    /** Full immutable board snapshots let the UI animate without predicting game rules. */
    val before: List<UiBubble> = emptyList(),
    val after: List<UiBubble> = emptyList(),
    val affected: List<UiBubble> = emptyList(),
    val durationMillis: Int = 260,
    /** Points shown as a floating label while this event plays (0 = none). */
    val points: Long = 0L,
    /** Streak factor of [points]; shown when above 1. */
    val multiplier: Int = 1,
)

data class UiGame(
    val levelLabel: String,
    val title: String,
    val objectiveLabel: String,
    val objectiveProgress: String = "",
    val shotsRemaining: Int? = null,
    val maxShots: Int? = null,
    /** Score shown in the HUD; points of events still animating are not included yet. */
    val score: Long = 0L,
    /** Consecutive removing shots; the next removing shot is worth min(streak + 1, 4)x. */
    val streak: Int = 0,
    val difficulty: UiDifficulty = UiDifficulty.MEDIUM,
    /** Shots that still give three and two stars; null in Zen. */
    val threeStarShots: Int? = null,
    val twoStarShots: Int? = null,
    val shotsUsed: Int = 0,
    val board: List<UiBubble> = emptyList(),
    val currentBubble: UiBubble? = null,
    val nextBubble: UiBubble? = null,
    val preview: UiPreview = UiPreview(),
    val events: List<UiAnimationEvent> = emptyList(),
    val dangerLine: Int = 24,
    val danger: Boolean = false,
    val preciseAim: Boolean = false,
    val inputEnabled: Boolean = true,
    val leftHanded: Boolean = false,
    val starsRescued: Int = 0,
    val starsToRescue: Int = 0,
    val starsPotential: Int = 0,
    /** Pieces and objective of this level, for the pause card. */
    val tips: List<UiTip> = emptyList(),
    val isFree: Boolean = false,
    val zenMode: Boolean = false,
    val dailyMode: Boolean = false,
    val tutorialStep: Int? = null,
)

data class UiLevelNode(
    val id: String,
    val number: Int,
    val state: NodeState,
    val stars: Int = 0,
    val accessibleLabel: String? = null,
) {
    enum class NodeState { CURRENT, AVAILABLE, LOCKED, COMPLETED }
}

data class UiOrbit(
    val id: String,
    val name: String,
    val completed: Int,
    val total: Int,
    val nodes: List<UiLevelNode>,
)

/** Explanation of a piece or objective; [piece] is drawn next to it when present. */
data class UiTip(val title: String, val text: String, val piece: UiBubbleKind? = null)

data class UiAchievement(val title: String, val description: String, val unlocked: Boolean)

data class UiPreparation(
    val levelLabel: String,
    val title: String,
    val objective: String,
    /** Pieces or objectives this level introduces to the player for the first time. */
    val newTips: List<UiTip> = emptyList(),
    val difficulty: UiDifficulty = UiDifficulty.MEDIUM,
    val shots: Int,
    val threeStarShots: Int = 0,
    val twoStarShots: Int = 0,
    val bestStars: Int = 0,
    val bestScore: Long = 0L,
    val instruction: String = "",
)

data class UiScoreLine(val label: String, val points: Long)
data class UiZenLevel(val id: String, val number: Int, val title: String, val favorite: Boolean)

data class UiResult(
    val title: String,
    val message: String,
    val score: Long,
    val shotsUsed: Int,
    val stars: Int = 0,
    val maxShots: Int? = null,
    /** How the score was made; empty in Zen. */
    val breakdown: List<UiScoreLine> = emptyList(),
    val difficulty: UiDifficulty = UiDifficulty.MEDIUM,
    /** Best before this game (level best in the campaign, record in Endless). */
    val previousBest: Long = 0L,
    val newRecord: Boolean = false,
    /** Endless only: points of the wave just played; [score] is then the run total. */
    val waveScore: Long? = null,
    val showScore: Boolean = true,
    val nextOrbitName: String? = null,
    val primaryLabel: String = "",
    val hasNext: Boolean = true,
    val dailyDate: String? = null,
    val orbitSummary: String? = null,
)

data class UiStatistics(
    val levelsCompleted: Long = 0L,
    val totalLevels: Long = 0L,
    val wins: Long = 0L,
    val bubblesCleared: Long = 0L,
    val starsEarned: Long = 0L,
    val freeBestScore: Long = 0L,
    val losses: Long = 0L,
    val shots: Long = 0L,
    val campaignScore: Long = 0L,
    val endlessWaves: Long = 0L,
)

data class UiSettingValue(val setting: UiSetting, val enabled: Boolean)

data class UiConfirmation(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val destructive: Boolean = false,
)

data class UiModel(
    val screen: UiScreen = UiScreen.START,
    val orbit: UiOrbit? = null,
    val preparation: UiPreparation? = null,
    val game: UiGame? = null,
    val result: UiResult? = null,
    val statistics: UiStatistics = UiStatistics(),
    val statisticsByDifficulty: Map<UiDifficulty, UiStatistics> = emptyMap(),
    val achievements: List<UiAchievement> = emptyList(),
    /** True while the bundled catalog is a pilot or slice; endless waves then reuse the available levels. */
    val developmentCatalog: Boolean = false,
    /** Orbit shown in the backdrop (1–12); each orbit has its own planet and orbit-line tint. */
    val orbitNumber: Int = 1,
    val settings: List<UiSettingValue> = emptyList(),
    val difficulty: UiDifficulty = UiDifficulty.MEDIUM,
    val currentLevelLabel: String = "",
    val currentOrbitName: String = "",
    val freeMode: UiFreeMode = UiFreeMode.INFINITE,
    val freeModeScore: Long = 0L,
    val freeModeBest: Long = 0L,
    /** Supplied by the host from BuildConfig; shown at the end of the settings. */
    val versionLabel: String = "",
    /** Hidden test mode: every campaign level is open regardless of progress. */
    val allLevelsUnlocked: Boolean = false,
    val testModeAvailable: Boolean = false,
    val canRetryLoad: Boolean = false,
    val canRetrySave: Boolean = false,
    /** Free mode record per difficulty, shown on the Endless card. */
    val freeModeBestByDifficulty: Map<UiDifficulty, Long> = emptyMap(),
    val symbolsEnabled: Boolean = true,
    val preciseAim: Boolean = false,
    val reducedMotion: Boolean = false,
    val firstRun: Boolean = true,
    val hasCampaignSession: Boolean = false,
    val hasFreeSession: Boolean = false,
    val loadingMessage: String? = null,
    val errorMessage: String? = null,
    val confirmation: UiConfirmation? = null,
    val dailyDate: String = "",
    val dailyBestStars: Map<UiDifficulty, Int> = emptyMap(),
    val dailyBestScores: Map<UiDifficulty, Long> = emptyMap(),
    val dailyCanResume: Boolean = false,
    val dailyLevelNumber: Int = 1,
    val zenLevels: List<UiZenLevel> = emptyList(),
)

/** Actions leave all navigation, persistence and game rules with the host application. */
interface UiCallbacks {
    fun onContinue() {}
    fun onOpenMap() {}
    fun onOpenPreparation(levelId: String) {}
    fun onSelectLevel(levelId: String) {}
    fun onDifficultyChanged(difficulty: UiDifficulty) {}
    fun onStartLevel() {}
    fun onStartFreeMode(mode: UiFreeMode) {}
    fun onResumeFreeGame() {}
    fun onOpenSettings() {}
    fun onOpenStatistics() {}
    fun onOpenCredits() {}
    fun onDismissError() {}
    fun onRetryLoad() {}
    fun onRetrySave() {}
    fun onOpenFreeGame() {}
    fun onOpenDaily() {}
    fun onStartDaily() {}
    fun onChooseZenLevel(levelId: String) {}
    fun onToggleZenFavorite(levelId: String) {}
    fun onFinishTutorial() {}
    fun onShareResult() {}
    fun onOrbitChanged(delta: Int) {}
    fun onBack() {}
    fun onPause() {}
    fun onResume() {}
    fun onRestart() {}
    fun onNextLevel() {}
    fun onReturnToStart() {}
    fun onSettingChanged(setting: UiSetting, enabled: Boolean) {}
    /** Hidden toggle for the test mode that opens every level. */
    fun onToggleAllLevels() {}
    fun onExportBackup() {}
    fun onImportBackup() {}
    fun onExportDiagnostics() {}
    fun onConfirmAction() {}
    fun onDismissConfirmation() {}
    fun onSwap() {}
    fun onAimChanged(angleMilliDegrees: Int) {}
    fun onShoot(angleMilliDegrees: Int) {}
    fun onPreciseAdjust(deltaMilliDegrees: Int) {}
    /** Called once after each event animation has completed (also for reduced motion). */
    fun onAnimationFinished(eventId: Long) {}
}
