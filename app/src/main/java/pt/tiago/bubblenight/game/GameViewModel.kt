package pt.tiago.bubblenight.game

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pt.tiago.bubblenight.BuildConfig
import pt.tiago.bubblenight.R
import pt.tiago.bubblenight.core.geometry.HexGrid
import pt.tiago.bubblenight.core.model.*
import pt.tiago.bubblenight.core.rules.GameEngine
import pt.tiago.bubblenight.data.*
import pt.tiago.bubblenight.ui.*
import java.util.Locale
import java.util.UUID
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import android.text.format.DateFormat
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * Serial command coordinator. A shot is shown at once; its durable save runs right after, in
 * order, on a process-wide chain. Commands that read the stored profile wait for that chain.
 */
class GameViewModel(application: Application) : AndroidViewModel(application), UiCallbacks {
    private val repository = ProfileRepository(application)
    private val catalogRepository = CatalogRepository(application)
    private val audio = AudioController(application)
    private val mutableUi = MutableStateFlow(UiModel(loadingMessage = application.getString(R.string.loading_constellations)))
    val ui: StateFlow<UiModel> = mutableUi
    private var profile = Profile()
    private var catalog: RuntimeCatalog? = null
    private var screen = UiScreen.START
    private var returnScreen = UiScreen.START
    private var selectedId = "level-0001"
    private var selectedOrbit = 1
    private var prepared: LevelDefinition? = null
    private var game: GameState? = null
    private var endless: EndlessRunSnapshot? = null
    private var angle = 0
    private var preview: ShotPreview? = null
    private var animationEvents = emptyList<UiAnimationEvent>()
    private var eventId = 0L
    private var busy = false
    private var foreground = true
    private var pauseRequested = false
    private var loaded = false
    private var error: String? = null
    private var confirmation: UiConfirmation? = null
    private var pendingAction: (() -> Unit)? = null
    private var freeChoice = UiFreeMode.INFINITE
    private var loadingLabel: String? = null
    /** Short message under the HUD when a shot is refused; cleared by the next aim. */
    private var aimHint: String? = null
    private var resultInfo: ResultInfo? = null
    private var noticeJob: Job? = null
    private var tutorialStep: Int? = null

    /** Extra facts for the result card, captured when the game ends. */
    private data class ResultInfo(val sessionId: String, val turnId: Long, val previousBest: Long, val newRecord: Boolean)

    init {
        loadProfile()
        viewModelScope.launch {
            while (true) {
                delay(60_000)
                if (foreground && loaded && screen in setOf(UiScreen.START, UiScreen.DAILY)) render()
            }
        }
    }

    private fun loadProfile() {
        perform(text(R.string.loading_constellations)) {
            catalog = catalogRepository.load()
            profile = repository.profile.first()
            selectedId = nextCampaignLevel().id
            selectedOrbit = orbitNumber(nextCampaignLevel())
            loaded = true
            if (repository.recovery.value == ProfileRecovery.RECOVERED_FROM_BACKUP) {
                error = text(R.string.error_recovered_backup)
            }
        }
    }

    private fun perform(label: String? = null, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        render()
        // Quick commands finish before the indicator would appear, so it does not flash.
        val indicator = label?.let { viewModelScope.launch { delay(LOADING_DELAY_MS); loadingLabel = it; render() } }
        viewModelScope.launch {
            try {
                pendingSaves.flush()
                if (loaded) profile = repository.profile.first()
                action()
            }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                error = if (pendingSaves.failed) text(R.string.error_save_pending)
                    else text(R.string.error_action_failed, failure.message ?: text(R.string.error_save_generic))
                if (game != null && screen in gameScreens) { pauseRequested = true; screen = UiScreen.PAUSED }
            } finally {
                indicator?.cancel()
                loadingLabel = null
                busy = false
                render()
            }
        }
    }

    /** Queues a save after the previous one; the game already shows the new state. */
    private fun persist(save: suspend () -> Profile) {
        pendingSaves.enqueue(save)
        persistScope.launch {
            try {
                pendingSaves.flush()?.let { profile = it }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                error = text(R.string.error_save_failed)
                if (game != null && screen in gameScreens && animationEvents.isEmpty()) { pauseRequested = true; screen = UiScreen.PAUSED }
            }
            render()
        }
    }

    /** Informative message that closes by itself. */
    private fun notice(message: String) {
        error = message
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(NOTICE_MS)
            if (error == message) { error = null; render() }
        }
        render()
    }

    private fun confirm(title: String, message: String, label: String, action: () -> Unit) {
        if (busy) return
        confirmation = UiConfirmation(title, message, label)
        pendingAction = action
        render()
    }

    override fun onConfirmAction() {
        val action = pendingAction
        confirmation = null; pendingAction = null
        render(); action?.invoke()
    }
    override fun onDismissConfirmation() { confirmation = null; pendingAction = null; render() }

    override fun onContinue() {
        if (!loaded || busy) return
        perform(text(R.string.loading_resuming_game)) {
            val saved = profile.campaignSession
            if (saved != null) restoreNow(saved) else prepareNow(nextCampaignLevel())
        }
    }

    private suspend fun restoreNow(saved: GameState) {
        val definition = catalogRepository.definition(saved.level.baseId, saved.difficulty)
        if (definition.semanticHash != saved.level.semanticHash || definition.revision != saved.level.revision) {
            if (saved.mode == GameMode.ENDLESS) {
                confirmation = UiConfirmation(text(R.string.confirm_levels_updated_title), text(R.string.confirm_levels_updated_message), text(R.string.action_new_run))
                pendingAction = { newFree(UiFreeMode.INFINITE) }
            } else {
                confirmation = UiConfirmation(text(R.string.confirm_level_updated_title), text(R.string.confirm_level_updated_message), text(R.string.action_reset_level))
                pendingAction = { if (saved.mode == GameMode.DAILY) startDaily(profile.dailySession!!.date, saved.difficulty)
                    else startGame(saved.level.baseId, saved.difficulty, saved.mode) }
            }
            return
        }
        game = saved
        tutorialStep = null
        profile = repository.update { it.copy(settings = it.settings.copy(lastDifficulty = saved.difficulty)) }
        endless = (profile.freeSession as? FreeSession.Endless)?.run.takeIf { saved.mode == GameMode.ENDLESS }
        freeChoice = if (saved.mode == GameMode.ZEN) UiFreeMode.ZEN else UiFreeMode.INFINITE
        pauseRequested = true
        angle = 0; animationEvents = emptyList(); aimHint = null
        preview = GameEngine.preview(saved, angle)
        screen = if (saved.outcome == Outcome.READY) UiScreen.PAUSED else outcomeScreen(saved)
        selectedId = saved.level.baseId
        audio.setActive(false)
    }

    override fun onOpenMap() {
        if (!loaded || busy) return
        animationEvents = emptyList(); audio.setActive(false)
        selectedOrbit = orbitNumber(catalog!!.levels.first { it.id == selectedId })
        screen = UiScreen.MAP; render()
    }

    override fun onOpenPreparation(levelId: String) {
        if (!loaded || busy) return
        val entry = catalog!!.levels.firstOrNull { it.id == levelId } ?: return
        if (!unlocked(entry, difficulty())) { notice(text(R.string.notice_level_locked)); return }
        perform(text(R.string.loading_level)) { prepareNow(entry) }
    }
    override fun onSelectLevel(levelId: String) = onOpenPreparation(levelId)

    private suspend fun prepareNow(entry: RuntimeLevel) {
        prepared = catalogRepository.definition(entry.id, difficulty())
        selectedId = entry.id; selectedOrbit = orbitNumber(entry)
        screen = UiScreen.PREPARATION
    }

    override fun onDifficultyChanged(difficulty: UiDifficulty) = perform {
        profile = repository.update { it.copy(settings = it.settings.copy(lastDifficulty = Difficulty.valueOf(difficulty.name))) }
        if (screen == UiScreen.PREPARATION) screen = UiScreen.MAP
        selectedId = nextCampaignLevel().id
    }

    override fun onOrbitChanged(delta: Int) {
        if (!loaded || busy) return
        selectedOrbit = (selectedOrbit + delta).coerceIn(1, ((catalog!!.levels.size - 1) / 50 + 1))
        render()
    }

    override fun onStartLevel() {
        val definition = prepared ?: return
        // The game in memory is newer than the stored session while a save is queued.
        val existing = game?.takeIf { it.mode == GameMode.CAMPAIGN } ?: profile.campaignSession
        if (existing != null && existing.outcome == Outcome.READY && existing.shotsUsed > 0) {
            confirm(text(R.string.confirm_start_level_title), text(R.string.confirm_start_level_message), text(R.string.action_start)) {
                startGame(definition.baseId, difficulty(), GameMode.CAMPAIGN)
            }
        } else startGame(definition.baseId, difficulty(), GameMode.CAMPAIGN)
    }

    private fun startGame(id: String, difficulty: Difficulty, mode: GameMode) = perform(text(R.string.loading_opening_constellation)) {
        val definition = catalogRepository.definition(id, difficulty)
        val started = GameEngine.start(definition, difficulty, mode, UUID.randomUUID().toString())
        profile = repository.saveSession(started)
        val introduced = levelTips(definition).keys - profile.seenTips
        if (mode == GameMode.CAMPAIGN && introduced.isNotEmpty()) {
            profile = repository.update { it.copy(seenTips = it.seenTips + introduced) }
        }
        game = started; endless = null; selectedId = id; angle = 0; aimHint = null; resultInfo = null
        tutorialStep = if (mode == GameMode.CAMPAIGN && id == "level-0001" && !profile.tutorialCompleted &&
            profile.progressByDifficulty.values.all { it.completedVariantIds.isEmpty() }) 0 else null
        pauseRequested = !foreground
        freeChoice = if (mode == GameMode.ZEN) UiFreeMode.ZEN else UiFreeMode.INFINITE
        animationEvents = emptyList(); preview = GameEngine.preview(started, 0)
        screen = if (foreground) gameScreen(started) else UiScreen.PAUSED
        audio.setActive(foreground)
    }

    override fun onOpenFreeGame() { if (!busy && loaded) { screen = UiScreen.FREE_MODE_SELECT; render() } }

    override fun onStartFreeMode(mode: UiFreeMode) {
        if (!loaded || busy) return
        freeChoice = mode
        if (mode == UiFreeMode.ZEN) { screen = UiScreen.ZEN_SELECT; render(); return }
        if (profile.freeSession != null) {
            confirm(text(R.string.confirm_new_free_title), text(R.string.confirm_replace_free_message), text(R.string.action_new_game)) { newFree(mode) }
        } else newFree(mode)
    }

    override fun onChooseZenLevel(levelId: String) {
        if (!loaded || busy || catalog!!.levels.none { it.id == levelId }) return
        if (profile.freeSession != null) confirm(text(R.string.confirm_start_board_title), text(R.string.confirm_replace_free_message), text(R.string.action_start)) {
            startGame(levelId, difficulty(), GameMode.ZEN)
        } else startGame(levelId, difficulty(), GameMode.ZEN)
    }

    override fun onToggleZenFavorite(levelId: String) = perform {
        require(catalog!!.levels.any { it.id == levelId })
        profile = repository.update { it.copy(zenFavorites = if (levelId in it.zenFavorites) it.zenFavorites - levelId else it.zenFavorites + levelId) }
    }

    override fun onOpenDaily() {
        if (loaded && !busy) { audio.setActive(false); screen = UiScreen.DAILY; render() }
    }

    override fun onStartDaily() {
        if (!loaded || busy) return
        val date = LocalDate.now().toString()
        val saved = profile.dailySession
        if (saved != null && saved.date == date && saved.state.difficulty == difficulty() && saved.state.outcome == Outcome.READY) {
            perform(text(R.string.loading_resuming_daily)) { restoreNow(profile.dailySession!!.state) }
        } else if (saved != null && saved.state.outcome == Outcome.READY && saved.state.shotsUsed > 0) {
            confirm(text(R.string.confirm_start_daily_title), text(R.string.confirm_start_daily_message), text(R.string.action_start)) { startDaily(date, difficulty()) }
        } else startDaily(date, difficulty())
    }

    private fun startDaily(date: String, difficulty: Difficulty) = perform(text(R.string.loading_daily)) {
        val id = DailyChallenge.levelId(LocalDate.parse(date), difficulty, catalog!!.levels)
        val definition = catalogRepository.definition(id, difficulty)
        val started = GameEngine.start(definition, difficulty, GameMode.DAILY, UUID.randomUUID().toString())
        profile = repository.saveDailySession(DailySession(date, started))
        game = started; endless = null; tutorialStep = null; selectedId = id; angle = 0; aimHint = null; resultInfo = null
        animationEvents = emptyList(); preview = GameEngine.preview(started, 0)
        pauseRequested = !foreground; screen = if (foreground) UiScreen.FREE_GAME else UiScreen.PAUSED
        audio.setActive(foreground)
    }

    fun resumeFree() {
        if (!loaded || busy) return
        perform(text(R.string.loading_resuming_game)) {
            when (val saved = profile.freeSession) {
                is FreeSession.Zen -> restoreNow(saved.state)
                is FreeSession.Endless -> saved.run.currentGame?.let { restoreNow(it) }
                null -> screen = UiScreen.FREE_MODE_SELECT
            }
        }
    }

    private fun newFree(mode: UiFreeMode) {
        if (mode == UiFreeMode.ZEN) {
            val entries = catalog?.levels ?: return
            val id = entries[java.security.SecureRandom().nextInt(entries.size)].id
            startGame(id, difficulty(), GameMode.ZEN)
        } else perform(text(R.string.loading_first_wave)) {
            val seed = java.lang.Long.toUnsignedString(java.security.SecureRandom().nextLong(), 16).padStart(16, '0')
            val selection = EndlessDeck.choose(catalog!!.levels, seed, difficulty(), 0)
            val id = selection.variantId.substringBeforeLast('-')
            val definition = catalogRepository.definition(id, difficulty())
            val started = GameEngine.start(definition, difficulty(), GameMode.ENDLESS, UUID.randomUUID().toString())
            val run = EndlessRunSnapshot(UUID.randomUUID().toString(), seed, difficulty(), selection.orderedVariantIds, 0, 0, started)
            profile = repository.saveEndlessRun(run)
            endless = run; game = started; tutorialStep = null; angle = 0; animationEvents = emptyList(); aimHint = null; resultInfo = null
            pauseRequested = !foreground
            preview = GameEngine.preview(started, 0)
            screen = if (foreground) UiScreen.FREE_GAME else UiScreen.PAUSED
            audio.setActive(foreground)
        }
    }

    override fun onNextLevel() {
        val current = game ?: return
        if (current.mode == GameMode.DAILY) { onOpenDaily(); return }
        if (current.mode == GameMode.CAMPAIGN) {
            val index = catalog!!.levels.indexOfFirst { it.id == current.level.baseId }
            val next = catalog!!.levels.getOrNull(index + 1)
            if (next != null) startGame(next.id, current.difficulty, GameMode.CAMPAIGN) else onOpenMap()
        } else if (current.mode == GameMode.ZEN) {
            val entries = catalog!!.levels
            val index = entries.indexOfFirst { it.id == current.level.baseId }
            startGame(entries[(index + 1) % entries.size].id, current.difficulty, GameMode.ZEN)
        } else if (current.outcome == Outcome.WON) {
            val run = endless ?: return
            perform(text(R.string.loading_next_wave)) {
                val cursor = run.cursor + 1
                val selection = EndlessDeck.choose(catalog!!.levels, run.seedHex, run.difficulty, cursor, run)
                val level = catalogRepository.definition(selection.variantId.substringBeforeLast('-'), run.difficulty)
                val started = GameEngine.start(level, run.difficulty, GameMode.ENDLESS, UUID.randomUUID().toString())
                val updated = run.copy(orderedVariantIds = selection.orderedVariantIds, cursor = cursor, currentGame = started)
                profile = repository.saveEndlessRun(updated)
                endless = updated; game = started; angle = 0; animationEvents = emptyList(); aimHint = null; resultInfo = null
                pauseRequested = !foreground
                preview = GameEngine.preview(started, 0)
                screen = if (foreground) UiScreen.FREE_GAME else UiScreen.PAUSED
                audio.setActive(foreground)
            }
        } else newFree(UiFreeMode.INFINITE)
    }

    override fun onRestart() {
        val current = game ?: return
        val restart = {
            if (current.mode == GameMode.ENDLESS) newFree(UiFreeMode.INFINITE)
            else if (current.mode == GameMode.DAILY) startDaily(profile.dailySession!!.date, current.difficulty)
            else startGame(current.level.baseId, current.difficulty, current.mode)
        }
        if (current.shotsUsed > 0 && current.outcome == Outcome.READY) {
            confirm(text(R.string.confirm_restart_title),
                text(if (current.mode == GameMode.ENDLESS) R.string.confirm_restart_run_message else R.string.confirm_restart_level_message),
                text(R.string.action_restart), restart)
        } else restart()
    }

    override fun onAimChanged(angleMilliDegrees: Int) {
        val current = game ?: return
        if (!canPlay()) return
        val next = angleMilliDegrees.coerceIn(-75000, 75000)
        if (next == angle && preview != null && aimHint == null) return
        angle = next
        aimHint = null
        preview = GameEngine.preview(current, angle)
        renderAim(current)
    }
    override fun onPreciseAdjust(deltaMilliDegrees: Int) = onAimChanged(angle + deltaMilliDegrees)

    override fun onSwap() {
        val current = game ?: return
        if (!canPlay()) return
        val swapped = GameEngine.swap(current)
        game = swapped
        if (tutorialStep != null) { tutorialStep = 2; render(); return }
        if (swapped.mode == GameMode.ENDLESS) endless = endless?.copy(currentGame = swapped)
        render()
        persist { repository.saveSession(swapped) }
    }

    override fun onShoot(angleMilliDegrees: Int) {
        val before = game ?: return
        if (!canPlay()) return
        if (tutorialStep != null) { if (tutorialStep == 0) tutorialStep = 1; render(); return }
        val result = GameEngine.shoot(before, angleMilliDegrees.coerceIn(-75000, 75000))
        if (!result.accepted) {
            angle = angleMilliDegrees.coerceIn(-75000, 75000)
            preview = GameEngine.preview(before, angle)
            aimHint = text(R.string.aim_no_room)
            renderAim(before)
            return
        }
        aimHint = null
        val priorRun = endless
        val updatedRun = priorRun?.copy(currentGame = result.state,
            score = if (result.state.outcome != Outcome.READY) safeAdd(priorRun.score, result.state.score) else priorRun.score)
        if (result.state.outcome != Outcome.READY) resultInfo = resultInfoFor(result.state, updatedRun)
        game = result.state; endless = updatedRun
        angle = angleMilliDegrees
        animationEvents = if (foreground && !pauseRequested) animations(before, result) else emptyList()
        preview = if (result.state.outcome == Outcome.READY) GameEngine.preview(result.state, angle) else null
        if (!foreground || pauseRequested) screen = UiScreen.PAUSED
        else if (animationEvents.isEmpty()) screen = outcomeScreen(result.state)
        else { screen = gameScreen(result.state); cueFor(animationEvents.first()) }
        render()
        persist {
            if (updatedRun != null && before.mode == GameMode.ENDLESS) repository.recordTurn(result, updatedRun)
            else repository.recordTurn(result)
        }
    }

    private fun resultInfoFor(state: GameState, run: EndlessRunSnapshot?): ResultInfo = when (state.mode) {
        GameMode.CAMPAIGN -> {
            val prior = profile.progressByDifficulty[state.difficulty]?.bestByVariantId?.get(state.level.variantId)?.bestScore ?: 0L
            ResultInfo(state.sessionId, state.turnId, prior, state.outcome == Outcome.WON && prior > 0 && state.score > prior)
        }
        GameMode.ENDLESS -> {
            val prior = profile.statsByDifficulty[state.difficulty]?.endlessBestScore ?: 0L
            ResultInfo(state.sessionId, state.turnId, prior, (run?.score ?: 0L) > prior)
        }
        GameMode.ZEN -> ResultInfo(state.sessionId, state.turnId, 0L, false)
        GameMode.DAILY -> {
            val prior = profile.dailySession?.date?.let { profile.dailyResults[DailyChallenge.resultKey(it, state.difficulty)]?.score } ?: 0L
            ResultInfo(state.sessionId, state.turnId, prior, state.outcome == Outcome.WON && state.score > prior)
        }
    }

    override fun onAnimationFinished(eventId: Long) {
        if (animationEvents.firstOrNull()?.id != eventId) return
        animationEvents = animationEvents.drop(1)
        if (animationEvents.isEmpty()) {
            val current = game ?: return
            screen = if (foreground && !pauseRequested && !pendingSaves.failed) outcomeScreen(current) else UiScreen.PAUSED
        } else cueFor(animationEvents.first())
        render()
    }

    override fun onPause() {
        if (game == null) return
        pauseRequested = true
        animationEvents = emptyList(); screen = UiScreen.PAUSED; audio.setActive(false); render()
    }
    override fun onResume() {
        val current = game ?: return
        if (busy || pendingSaves.failed) return
        pauseRequested = false
        screen = outcomeScreen(current); audio.setActive(foreground && current.outcome == Outcome.READY); render()
    }
    fun onBackground() { foreground = false; audio.setActive(false); if (game != null && screen in gameScreens) onPause() }
    fun onForeground() { foreground = true; render() }

    override fun onReturnToStart() { if (!busy) { animationEvents = emptyList(); audio.setActive(false); screen = UiScreen.START; render() } }
    override fun onOpenSettings() { if (!busy) { returnScreen = screen; screen = UiScreen.SETTINGS; render() } }
    override fun onOpenStatistics() { if (!busy) { returnScreen = screen; screen = UiScreen.STATISTICS; render() } }
    override fun onOpenCredits() { if (!busy) { returnScreen = screen; screen = UiScreen.CREDITS; render() } }
    override fun onBack() {
        if (busy) return
        when (screen) {
            UiScreen.GAME, UiScreen.FREE_GAME -> onPause()
            UiScreen.PAUSED -> onResume()
            UiScreen.SETTINGS, UiScreen.STATISTICS, UiScreen.CREDITS -> { screen = returnScreen; render() }
            UiScreen.PREPARATION -> onOpenMap()
            UiScreen.WON, UiScreen.LOST -> when (game?.mode) {
                GameMode.CAMPAIGN -> onOpenMap()
                GameMode.DAILY -> onOpenDaily()
                else -> onOpenFreeGame()
            }
            UiScreen.ZEN_SELECT -> { screen = UiScreen.FREE_MODE_SELECT; render() }
            else -> onReturnToStart()
        }
    }

    override fun onDismissError() { noticeJob?.cancel(); if (!pendingSaves.failed) error = null; render() }
    override fun onRetryLoad() = retryLoad()
    override fun onRetrySave() = perform(text(R.string.loading_saving_move)) { notice(text(R.string.notice_move_saved)) }

    override fun onFinishTutorial() = perform {
        val current = game ?: return@perform
        if (tutorialStep == null) return@perform
        val reset = GameEngine.start(current.level, current.difficulty, current.mode, current.sessionId)
        profile = repository.update { it.copy(tutorialCompleted = true, campaignSession = reset) }
        game = reset; tutorialStep = null; angle = 0; preview = GameEngine.preview(reset, 0)
    }

    override fun onSettingChanged(setting: UiSetting, enabled: Boolean) = perform {
        profile = repository.update { old ->
            val s = old.settings
            old.copy(settings = when (setting) {
                UiSetting.SOUND -> s.copy(sound = enabled)
                UiSetting.VIBRATION -> s.copy(vibration = enabled)
                UiSetting.SYMBOLS -> s.copy(symbols = enabled)
                UiSetting.PRECISE_AIM -> s.copy(preciseAim = enabled)
                UiSetting.FULL_AIM_GUIDE -> s.copy(fullAimGuide = enabled)
                UiSetting.LEFT_HANDED -> s.copy(leftHanded = enabled)
                UiSetting.REDUCED_MOTION -> s.copy(reducedMotion = enabled)
            })
        }
        if (setting == UiSetting.SOUND && !enabled) audio.stop()
        if (setting == UiSetting.FULL_AIM_GUIDE) game?.let { preview = GameEngine.preview(it, angle) }
    }

    override fun onToggleAllLevels() {
        if (!BuildConfig.DEBUG) return
        perform {
            profile = repository.update { old -> old.copy(settings = old.settings.copy(unlockAllLevels = !old.settings.unlockAllLevels)) }
            notice(text(if (profile.settings.unlockAllLevels) R.string.notice_test_mode_on else R.string.notice_test_mode_off))
        }
    }

    suspend fun exportPayload(): ByteArray {
        pendingSaves.flush()
        return repository.exportPayload()
    }
    suspend fun flushPendingSaves() { pendingSaves.flush() }
    fun importPayload(bytes: ByteArray) = perform(text(R.string.loading_checking_backup)) {
        val imported = withContext(Dispatchers.Default) { repository.prepareImport(bytes) }
        val completed = imported.progressByDifficulty.values.sumOf { it.completedVariantIds.size }
        confirmation = UiConfirmation(text(R.string.confirm_restore_title), plural(R.plurals.confirm_restore_message, completed), text(R.string.action_restore))
        pendingAction = {
            perform(text(R.string.loading_restoring)) {
                profile = repository.applyImport(imported)
                catalog = catalogRepository.load()
                loaded = true
                game = null; endless = null; tutorialStep = null; animationEvents = emptyList(); screen = UiScreen.START
                selectedId = nextCampaignLevel().id; selectedOrbit = orbitNumber(nextCampaignLevel())
            }
        }
    }
    fun reportMessage(message: String) { error = message; render() }
    fun dismissMessage() { error = null; render() }
    fun retryLoad() { if (!loaded && !busy) loadProfile() }

    private fun animations(before: GameState, result: TurnResult): List<UiAnimationEvent> {
        val events = mutableListOf<UiAnimationEvent>()
        val placed = result.events.filterIsInstance<TurnEvent.Placed>().first()
        var board = before.board.with(placed.cell, Bubble.Color(placed.colorId))
        val path = result.preview!!.segments.let { segments -> listOf(segments.first().from) + segments.map { it.to } }
        val duration = (path.zipWithNext().sumOf { (a, b) -> hypot(b.x - a.x, b.y - a.y) } / 45.0 * 1000).toInt().coerceIn(100, 1500)
        events += UiAnimationEvent(++eventId, UiEventKind.SHOT, visual(CellBubble(placed.cell, Bubble.Color(placed.colorId))),
            trajectory = path.map(::point), before = before.board.cells.map(::visual), after = board.cells.map(::visual), durationMillis = duration)
        for (event in result.events) when (event) {
            is TurnEvent.Matched -> {
                val old = board
                board = Board.of(board.cells.filterNot { it.cell in event.cells })
                events += UiAnimationEvent(++eventId, UiEventKind.MATCH, before = old.cells.map(::visual), after = board.cells.map(::visual),
                    affected = old.cells.filter { it.cell in event.cells }.map(::visual), durationMillis = 140)
            }
            is TurnEvent.Dropped -> {
                val old = board
                board = Board.of(board.cells.filterNot { it.cell in event.cells })
                events += UiAnimationEvent(++eventId, UiEventKind.DROP, before = old.cells.map(::visual), after = board.cells.map(::visual),
                    affected = old.cells.filter { it.cell in event.cells }.map(::visual), durationMillis = 320)
            }
            TurnEvent.Won -> events += UiAnimationEvent(++eventId, UiEventKind.VICTORY, before = board.cells.map(::visual), after = board.cells.map(::visual), durationMillis = 220)
            is TurnEvent.Lost -> events += UiAnimationEvent(++eventId, UiEventKind.DEFEAT, before = board.cells.map(::visual), after = board.cells.map(::visual), durationMillis = 120)
            else -> Unit
        }
        // Shot points float over the last removal; the unused-shot bonus over the victory.
        val scored = result.events.filterIsInstance<TurnEvent.Scored>()
        scored.firstOrNull { it.unusedShotBonus == 0L }?.let { shot ->
            val index = events.indexOfLast { it.kind == UiEventKind.MATCH || it.kind == UiEventKind.DROP }
            if (index >= 0) events[index] = events[index].copy(points = shot.points, multiplier = shot.multiplier,
                durationMillis = maxOf(events[index].durationMillis, 420))
        }
        scored.firstOrNull { it.unusedShotBonus > 0L }?.let { bonus ->
            val index = events.indexOfLast { it.kind == UiEventKind.VICTORY }
            if (index >= 0) events[index] = events[index].copy(points = bonus.points, durationMillis = 650)
        }
        return events
    }

    private fun cueFor(event: UiAnimationEvent) {
        val cue = when (event.kind) {
            UiEventKind.SHOT -> AudioController.Cue.LAUNCH
            UiEventKind.MATCH -> AudioController.Cue.MATCH
            UiEventKind.DROP -> AudioController.Cue.DROP
            UiEventKind.BOUNCE -> AudioController.Cue.BOUNCE
            UiEventKind.VICTORY -> AudioController.Cue.VICTORY
            UiEventKind.DEFEAT -> AudioController.Cue.DEFEAT
        }
        audio.play(cue, profile.settings.sound, profile.settings.vibration)
    }

    /** Aiming changes only the preview; the rest of the screen model is reused. */
    private fun renderAim(state: GameState) {
        val current = mutableUi.value
        val shown = current.game
        if (shown == null) { render(); return }
        mutableUi.value = current.copy(game = shown.copy(preview = uiPreview(state), inputEnabled = canPlay()))
    }

    private fun uiPreview(state: GameState): UiPreview {
        val aim = preview ?: return UiPreview(angle, rejectionLabel = aimHint)
        val guide = when {
            profile.settings.fullAimGuide || state.difficulty == Difficulty.EASY -> Guide.FULL
            state.difficulty == Difficulty.MEDIUM -> Guide.FIRST_BOUNCE
            else -> Guide.SHORT
        }
        val valid = aim.target != null && aim.rejection == null
        val showsTarget = when (guide) {
            Guide.FULL -> true
            Guide.FIRST_BOUNCE -> aim.segments.none { it.bounceAfter }
            Guide.SHORT -> false
        }
        val destination = aim.target?.let { cell -> point(HexGrid.center(cell)) }?.takeIf { valid && showsTarget }
        return UiPreview(angle, guidePoints(aim.segments, guide), valid, destination, aimHint,
            aim.target?.takeIf { valid }?.let { text(R.string.target_description, it.row + 1, it.column + 1) })
    }

    private fun uiGame(state: GameState, levels: List<RuntimeLevel>): UiGame {
        val info = levels.firstOrNull { l -> l.id == state.level.baseId }
        val starsTotal = state.level.board.cells.count { entry -> entry.bubble == Bubble.Star }
        val pending = animationEvents.sumOf { it.points }
        val total = if (state.mode == GameMode.ENDLESS && state.outcome == Outcome.READY) safeAdd(endless?.score ?: 0, state.score)
            else if (state.mode == GameMode.ENDLESS) endless?.score ?: state.score else state.score
        val campaign = state.mode != GameMode.ZEN
        return UiGame(
            levelLabel = when (state.mode) {
                GameMode.ENDLESS -> text(R.string.label_wave, (endless?.cursor ?: 0) + 1)
                GameMode.ZEN -> text(R.string.label_zen_mode)
                GameMode.DAILY -> text(R.string.label_daily, profile.dailySession?.date?.let(::shortDate).orEmpty())
                GameMode.CAMPAIGN -> text(R.string.label_level, info?.ordinal ?: 1)
            },
            title = info?.let { levelTitle(it.ordinal) }.orEmpty(), objectiveLabel = objectiveText(state.level.objective),
            objectiveProgress = if (starsTotal > 0) "${state.rescuedStars} / $starsTotal" else "",
            shotsRemaining = if (campaign) (state.level.maxShots - state.shotsUsed).coerceAtLeast(0) else null,
            maxShots = if (campaign) state.level.maxShots else null,
            score = (total - pending).coerceAtLeast(0),
            streak = if (animationEvents.isEmpty()) state.streak else 0,
            difficulty = UiDifficulty.valueOf(state.difficulty.name),
            threeStarShots = if (state.mode in setOf(GameMode.CAMPAIGN, GameMode.DAILY)) state.level.threeStarMaxShots else null,
            twoStarShots = if (state.mode in setOf(GameMode.CAMPAIGN, GameMode.DAILY)) state.level.twoStarMaxShots else null,
            shotsUsed = state.shotsUsed,
            board = state.board.cells.map(::visual), currentBubble = colorBubble(state.currentColor), nextBubble = colorBubble(state.nextColor),
            preview = uiPreview(state),
            events = animationEvents, danger = state.board.cells.any { b -> b.cell.row >= 10 }, preciseAim = profile.settings.preciseAim,
            inputEnabled = canPlay(), leftHanded = profile.settings.leftHanded, starsRescued = state.rescuedStars, starsToRescue = starsTotal,
            starsPotential = potentialStars(state),
            tips = levelTips(state.level).values.toList(),
            isFree = state.mode != GameMode.CAMPAIGN, zenMode = state.mode == GameMode.ZEN,
            dailyMode = state.mode == GameMode.DAILY, tutorialStep = tutorialStep,
        )
    }

    private fun uiResult(state: GameState, levels: List<RuntimeLevel>): UiResult {
        val won = state.outcome == Outcome.WON
        val info = resultInfo?.takeIf { it.sessionId == state.sessionId && it.turnId == state.turnId }
        val parts = state.breakdown
        val breakdown = if (state.mode == GameMode.ZEN) emptyList() else listOf(
            UiScoreLine(text(R.string.score_popped), parts.popped),
            UiScoreLine(text(R.string.score_dropped), parts.dropped),
            UiScoreLine(text(R.string.score_rescued), parts.rescued),
            UiScoreLine(text(R.string.score_streak), parts.streak),
            UiScoreLine(text(R.string.score_unused), parts.unusedShots),
        ).filter { it.points > 0 }
        val lastLevel = levels.lastOrNull()?.id == state.level.baseId
        val ordinal = levels.firstOrNull { it.id == state.level.baseId }?.ordinal ?: 0
        val orbit = levels.filter { orbitNumber(it) == (ordinal - 1) / 50 + 1 }
        val orbitDone = state.mode == GameMode.CAMPAIGN && won && ordinal > 0 && ordinal % 50 == 0 &&
            orbit.all { it.id == state.level.baseId || completed(it, state.difficulty) }
        val earned = orbit.sumOf { if (it.id == state.level.baseId) maxOf(GameEngine.stars(state), profile.progressByDifficulty[state.difficulty]?.bestByVariantId?.get(state.level.variantId)?.stars ?: 0)
            else profile.progressByDifficulty[state.difficulty]?.bestByVariantId?.get(variantId(it.id, state.difficulty))?.stars ?: 0 }
        return UiResult(
            title = text(if (won) R.string.result_won_title else if (state.mode == GameMode.ZEN) R.string.result_zen_title else R.string.result_lost_title),
            message = when {
                won && state.mode == GameMode.DAILY -> text(R.string.result_daily_won)
                won && state.mode == GameMode.ENDLESS -> text(R.string.result_wave_won, (endless?.cursor ?: 0) + 1)
                won && state.mode == GameMode.CAMPAIGN && lastLevel -> text(R.string.result_campaign_finished)
                orbitDone -> text(R.string.result_orbit_done, orbitName((ordinal - 1) / 50 + 1))
                won -> text(R.string.result_won)
                state.lossReason == LossReason.NO_SHOTS -> text(R.string.result_no_shots)
                state.lossReason == LossReason.NO_COMBINATIONS -> text(R.string.result_no_combinations)
                else -> text(R.string.result_overflow)
            },
            score = if (state.mode == GameMode.ENDLESS) endless?.score ?: state.score else state.score,
            shotsUsed = state.shotsUsed, stars = GameEngine.stars(state),
            maxShots = if (state.mode == GameMode.ZEN) null else state.level.maxShots,
            breakdown = breakdown,
            difficulty = UiDifficulty.valueOf(state.difficulty.name),
            previousBest = info?.previousBest ?: 0L,
            newRecord = info?.newRecord == true,
            waveScore = if (state.mode == GameMode.ENDLESS) state.score else null,
            showScore = state.mode != GameMode.ZEN,
            hasNext = !(won && state.mode == GameMode.CAMPAIGN && lastLevel),
            primaryLabel = if (state.mode == GameMode.DAILY && won) text(R.string.menu_daily) else "",
            dailyDate = profile.dailySession?.date?.takeIf { state.mode == GameMode.DAILY },
            orbitSummary = if (orbitDone) text(R.string.result_orbit_summary, earned, orbit.size * 3) else null,
        )
    }

    private fun render() {
        val levels = catalog?.levels.orEmpty()
        val today = LocalDate.now()
        val settings = profile.settings
        val difficulty = difficulty()
        val progress = profile.progressByDifficulty[difficulty] ?: CampaignProgress()
        val stats = profile.statsByDifficulty[difficulty] ?: DifficultyStats()
        val active = game
        val next = levels.firstOrNull { !completed(it, difficulty) } ?: levels.lastOrNull()
        val homeLevel = profile.campaignSession?.let { saved -> levels.firstOrNull { it.id == saved.level.baseId } } ?: next
        val displayOrbit = if (screen == UiScreen.START) homeLevel?.let(::orbitNumber) ?: 1 else selectedOrbit
        val orbitLevels = levels.filter { orbitNumber(it) == displayOrbit }
        val selected = levels.firstOrNull { it.id == selectedId }
        val result = active?.takeIf { it.outcome != Outcome.READY }?.let { uiResult(it, levels) }
        mutableUi.value = UiModel(
            screen = screen,
            orbit = if (orbitLevels.isEmpty()) null else UiOrbit("orbit-%02d".format(displayOrbit), orbitName(displayOrbit),
                orbitLevels.count { completed(it, difficulty) }, orbitLevels.size,
                orbitLevels.map { level -> UiLevelNode(level.id, level.ordinal,
                    when { completed(level, difficulty) -> UiLevelNode.NodeState.COMPLETED
                        level.id == next?.id -> UiLevelNode.NodeState.CURRENT
                        unlocked(level, difficulty) -> UiLevelNode.NodeState.AVAILABLE
                        else -> UiLevelNode.NodeState.LOCKED }, progress.bestByVariantId[variantId(level.id, difficulty)]?.stars ?: 0) }),
            preparation = prepared?.let { UiPreparation(text(R.string.label_level, selected?.ordinal ?: 1), selected?.let { level -> levelTitle(level.ordinal) }.orEmpty(), objectiveText(it.objective),
                difficulty = UiDifficulty.valueOf(difficulty.name), shots = it.maxShots,
                threeStarShots = it.threeStarMaxShots, twoStarShots = it.twoStarMaxShots,
                bestStars = progress.bestByVariantId[it.variantId]?.stars ?: 0,
                bestScore = progress.bestByVariantId[it.variantId]?.bestScore ?: 0L,
                instruction = introduction(selected?.ordinal ?: 1),
                newTips = levelTips(it).filterKeys { key -> key !in profile.seenTips }.values.toList()) },
            game = active?.let { uiGame(it, levels) },
            result = result,
            developmentCatalog = catalog?.buildStage != "full",
            orbitNumber = selectedOrbit,
            statistics = statisticsFor(difficulty, levels.size),
            statisticsByDifficulty = Difficulty.entries.associate { UiDifficulty.valueOf(it.name) to statisticsFor(it, levels.size) },
            achievements = KNOWN_ACHIEVEMENTS.map { id ->
                val (title, description) = achievementTexts.getValue(id)
                UiAchievement(text(title), text(description), id in profile.achievements)
            },
            settings = listOf(
                UiSettingValue(UiSetting.SOUND, settings.sound),
                UiSettingValue(UiSetting.VIBRATION, settings.vibration),
                UiSettingValue(UiSetting.SYMBOLS, settings.symbols),
                UiSettingValue(UiSetting.FULL_AIM_GUIDE, settings.fullAimGuide),
                UiSettingValue(UiSetting.PRECISE_AIM, settings.preciseAim),
                UiSettingValue(UiSetting.LEFT_HANDED, settings.leftHanded),
                UiSettingValue(UiSetting.REDUCED_MOTION, settings.reducedMotion)),
            difficulty = UiDifficulty.valueOf(difficulty.name), currentLevelLabel = text(R.string.label_level, homeLevel?.ordinal ?: 1) +
                (profile.campaignSession?.let { " · ${text(UiDifficulty.valueOf(it.difficulty.name).label)}" } ?: ""),
            currentOrbitName = orbitName(displayOrbit), freeMode = freeChoice,
            freeModeScore = if (active?.mode == GameMode.ENDLESS && active.outcome == Outcome.READY)
                safeAdd(endless?.score ?: 0, active.score) else endless?.score ?: 0, freeModeBest = stats.endlessBestScore,
            freeModeBestByDifficulty = Difficulty.entries.associate { d ->
                UiDifficulty.valueOf(d.name) to (profile.statsByDifficulty[d]?.endlessBestScore ?: 0L) },
            symbolsEnabled = settings.symbols, preciseAim = settings.preciseAim, reducedMotion = settings.reducedMotion,
            firstRun = profile.progressByDifficulty.values.all { it.completedVariantIds.isEmpty() } && profile.campaignSession == null,
            hasCampaignSession = profile.campaignSession != null, hasFreeSession = profile.freeSession != null,
            versionLabel = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", allLevelsUnlocked = BuildConfig.DEBUG && settings.unlockAllLevels,
            testModeAvailable = BuildConfig.DEBUG, canRetryLoad = !loaded && !busy, canRetrySave = pendingSaves.failed,
            loadingMessage = loadingLabel ?: if (!loaded && busy) text(R.string.loading_constellations) else null, errorMessage = error, confirmation = confirmation,
            dailyDate = today.toString(),
            dailyBestStars = Difficulty.entries.associate { UiDifficulty.valueOf(it.name) to (profile.dailyResults[DailyChallenge.resultKey(today.toString(), it)]?.stars ?: 0) },
            dailyBestScores = Difficulty.entries.associate { UiDifficulty.valueOf(it.name) to (profile.dailyResults[DailyChallenge.resultKey(today.toString(), it)]?.score ?: 0L) },
            dailyCanResume = profile.dailySession?.let { it.date == today.toString() && it.state.difficulty == difficulty && it.state.outcome == Outcome.READY } == true,
            dailyLevelNumber = if (levels.isEmpty()) 1 else levels.first { it.id == DailyChallenge.levelId(today, difficulty, levels) }.ordinal,
            zenLevels = orbitLevels.map { UiZenLevel(it.id, it.ordinal, levelTitle(it.ordinal), it.id in profile.zenFavorites) },
        )
    }

    private fun canPlay() = !busy && foreground && !pauseRequested && screen in setOf(UiScreen.GAME, UiScreen.FREE_GAME) &&
        animationEvents.isEmpty() && game?.outcome == Outcome.READY && confirmation == null && !pendingSaves.failed
    private fun difficulty() = profile.settings.lastDifficulty
    private fun variantId(id: String, difficulty: Difficulty) = "$id-${difficulty.name.lowercase(Locale.ROOT)}"
    private fun completed(level: RuntimeLevel, difficulty: Difficulty) =
        variantId(level.id, difficulty) in profile.progressByDifficulty[difficulty]?.completedVariantIds.orEmpty()
    private fun unlocked(level: RuntimeLevel, difficulty: Difficulty) = level.ordinal == 1 || completed(level, difficulty) || (BuildConfig.DEBUG && profile.settings.unlockAllLevels) ||
        catalog!!.levels.getOrNull(level.ordinal - 2)?.let { completed(it, difficulty) } == true
    private fun nextCampaignLevel(): RuntimeLevel = catalog!!.levels.firstOrNull { !completed(it, difficulty()) } ?: catalog!!.levels.last()
    /** Short controls tutorial for the first levels only; later levels show no instruction. */
    private fun introduction(ordinal: Int): String = when (ordinal) {
        1 -> text(if (profile.settings.preciseAim) R.string.instruction_aim_precise else R.string.instruction_aim_drag)
        2, 3 -> text(R.string.instruction_match)
        4, 5 -> text(R.string.instruction_drop)
        else -> ""
    }

    private fun statisticsFor(difficulty: Difficulty, totalLevels: Int): UiStatistics {
        val progress = profile.progressByDifficulty[difficulty] ?: CampaignProgress()
        val stats = profile.statsByDifficulty[difficulty] ?: DifficultyStats()
        return UiStatistics(
            levelsCompleted = progress.completedVariantIds.size.toLong(), totalLevels = totalLevels.toLong(),
            starsEarned = progress.bestByVariantId.values.sumOf { it.stars.toLong() }, freeBestScore = stats.endlessBestScore,
            wins = stats.campaignWins, bubblesCleared = stats.bubblesCleared, losses = stats.campaignLosses,
            shots = stats.campaignShots, campaignScore = stats.campaignScore, endlessWaves = stats.endlessWaves,
        )
    }

    /** Pieces and objectives a level uses, keyed by the tip id recorded once seen. */
    private fun levelTips(level: LevelDefinition): Map<String, UiTip> = buildMap {
        if (level.board.cells.any { it.bubble == Bubble.Rock }) put(TIP_ROCK, UiTip(text(R.string.tip_rock_title),
            text(R.string.tip_rock_text), UiBubbleKind.ROCK))
        if (level.board.cells.any { it.bubble == Bubble.Star }) put(TIP_STAR, UiTip(text(R.string.tip_star_title),
            text(R.string.tip_star_text), UiBubbleKind.STAR))
        (level.objective as? Objective.ClearColorSet)?.let { objective -> put(TIP_COLOR_SET, UiTip(text(R.string.tip_color_set_title),
            text(R.string.tip_color_set_text, colorList(objective.colorIds)))) }
    }

    private fun orbitNumber(level: RuntimeLevel) = (level.ordinal - 1) / 50 + 1
    private fun outcomeScreen(state: GameState) = when (state.outcome) {
        Outcome.READY -> gameScreen(state); Outcome.WON -> UiScreen.WON; Outcome.LOST -> UiScreen.LOST
    }
    private fun gameScreen(state: GameState) = if (state.mode == GameMode.CAMPAIGN) UiScreen.GAME else UiScreen.FREE_GAME
    private fun objectiveText(objective: Objective) = when (objective) {
        Objective.ClearColors -> text(R.string.objective_clear_all)
        Objective.RescueStars -> text(R.string.objective_rescue_stars)
        is Objective.ClearColorSet -> text(R.string.objective_clear_set, colorList(objective.colorIds))
    }
    private val resources get() = getApplication<Application>().resources
    private fun text(@StringRes id: Int, vararg args: Any): String = resources.getString(id, *args)
    private fun plural(@PluralsRes id: Int, count: Int): String = resources.getQuantityString(id, count, count)
    private fun orbitName(number: Int): String =
        resources.getStringArray(R.array.orbit_names).getOrElse(number - 1) { text(R.string.orbit_name_fallback) }
    /** Orbit 1 has authored titles; procedural orbits are named by position. */
    private fun levelTitle(ordinal: Int): String =
        resources.getStringArray(R.array.orbit_one_level_titles).getOrNull(ordinal - 1)
            ?: text(R.string.level_title_generic, (ordinal - 1) / 50 + 1, (ordinal - 1) % 50 + 1)
    /** Objectives name at most two colors: "blue" or "blue and green". */
    private fun colorList(ids: List<Int>): String {
        val names = resources.getStringArray(R.array.color_names)
        val parts = ids.map { names[it] }
        return parts.reduce { first, second -> text(R.string.two_items, first, second) }
    }
    private fun shortDate(date: String): String =
        LocalDate.parse(date).format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(Locale.getDefault(), "ddMM")))
    private fun visual(entry: CellBubble) = UiBubble(entry.cell.row, entry.cell.column,
        when (entry.bubble) { is Bubble.Color -> UiBubbleKind.COLOR; Bubble.Rock -> UiBubbleKind.ROCK; Bubble.Star -> UiBubbleKind.STAR },
        (entry.bubble as? Bubble.Color)?.id?.let { UiBubbleColor.entries[it] }, "${entry.cell.row}:${entry.cell.column}")
    private fun colorBubble(color: Int) = UiBubble(-1, -1, UiBubbleKind.COLOR, UiBubbleColor.entries[color])
    private fun point(v: Vec2) = UiPoint(v.x.toFloat(), v.y.toFloat())
    private fun safeAdd(a: Long, b: Long) = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
    override fun onCleared() { audio.release() }

    companion object {
        private const val LOADING_DELAY_MS = 300L
        private const val NOTICE_MS = 3500L
        /** Saves outlive the view model so a game left mid-save still reaches the disk. */
        private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val pendingSaves = PendingSaves<Profile>()
        private val achievementTexts = mapOf(
            "first_win" to (R.string.achievement_first_win_title to R.string.achievement_first_win_text),
            "first_ricochet" to (R.string.achievement_first_ricochet_title to R.string.achievement_first_ricochet_text),
            "drop_10" to (R.string.achievement_drop_10_title to R.string.achievement_drop_10_text),
            "first_orbit" to (R.string.achievement_first_orbit_title to R.string.achievement_first_orbit_text),
            "levels_100" to (R.string.achievement_levels_100_title to R.string.achievement_levels_100_text),
        )
        private val gameScreens = setOf(UiScreen.GAME, UiScreen.FREE_GAME, UiScreen.WON, UiScreen.LOST)
    }
}
