package pt.tiago.bubblenight.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextMeasurer
import java.text.NumberFormat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import pt.tiago.bubblenight.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private val BoardColors = mapOf(
    UiBubbleColor.BLUE to Color(0xFF7AA2F7), UiBubbleColor.PURPLE to Color(0xFFBB9AF7),
    UiBubbleColor.GREEN to Color(0xFF9ECE6A), UiBubbleColor.ORANGE to Color(0xFFFF9E64),
    UiBubbleColor.PINK to Color(0xFFF7768E), UiBubbleColor.CYAN to Color(0xFF7DCFFF),
)
private val BoardBg = Color(0xFF1A1B26)
private val BoardLine = Color(0xFF414868)
private val BoardText = Color(0xFFC0CAF5)
private val BoardStrong = Color(0xFFE2E8FF)
private val BoardMuted = Color(0xFF8C94BE)
private val BoardAccent = Color(0xFF7AA2F7)
private val BoardPurple = Color(0xFFBB9AF7)
private val BoardPink = Color(0xFFF7768E)
private val BoardOrange = Color(0xFFFF9E64)
private val BoardSurface = Color(0xFF24283B)
private val BoardRaised = Color(0xFF292E42)
private val BoardOnAccent = Color(0xFF16161E)
private val BoardFont = FontFamily(Font(R.font.figtree))
private val BoardDisplayFont = FontFamily(Font(R.font.bricolage_grotesque))

@Composable
internal fun GamePage(model: UiModel, callbacks: UiCallbacks, modifier: Modifier = Modifier) {
    var showBoardInfo by remember { mutableStateOf(false) }
    val accessibility = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    var accessibleControls by remember(accessibility) { mutableStateOf(accessibility?.isTouchExplorationEnabled == true) }
    DisposableEffect(accessibility) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { accessibleControls = it }
        accessibility?.addTouchExplorationStateChangeListener(listener)
        onDispose { accessibility?.removeTouchExplorationStateChangeListener(listener) }
    }
    val canInteract = model.screen in setOf(UiScreen.GAME, UiScreen.FREE_GAME) && model.loadingMessage == null && model.confirmation == null
    val game = model.game?.let { it.copy(inputEnabled = it.inputEnabled && canInteract && it.events.isEmpty(), preciseAim = it.preciseAim || accessibleControls) }
    if (game == null) {
        Column(modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            GameBackHeader(model, callbacks)
            Text(stringResource(R.string.game_preparing_board), color = BoardText, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            GameAction(stringResource(R.string.action_back_to_start), callbacks::onReturnToStart, primary = false)
        }
        return
    }

    val isFree = game.isFree || model.screen == UiScreen.FREE_GAME
    BoxWithConstraints(modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                FittedGameBoard(game, callbacks, model, Modifier.fillMaxHeight().aspectRatio(18f / 32f))
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 14.dp, top = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GameHud(game, isFree, model, callbacks, compact = true, onInspect = { showBoardInfo = true })
                    if (game.tutorialStep != null) TutorialCard(game, callbacks)
                    Spacer(Modifier.weight(1f))
                    if (game.preciseAim) PreciseControls(game, callbacks, vertical = true)
                    else SwapAndNext(game, callbacks, compact = true)
                    if (game.zenMode) GameAction(stringResource(R.string.action_other_board), callbacks::onNextLevel, primary = false, enabled = game.inputEnabled)
                }
            }
        } else {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                GameHud(game, isFree, model, callbacks, onInspect = { showBoardInfo = true })
                if (game.tutorialStep != null) TutorialCard(game, callbacks)
                FittedGameBoard(game, callbacks, model, Modifier.weight(1f).fillMaxWidth())
                if (game.preciseAim) PreciseControls(game, callbacks)
                else SwapAndNext(game, callbacks)
                if (game.zenMode) GameAction(stringResource(R.string.action_other_board), callbacks::onNextLevel, primary = false, enabled = game.inputEnabled)
            }
        }
        when (model.screen) {
            UiScreen.PAUSED -> GameOverlay(
                title = stringResource(R.string.pause_title),
                message = stringResource(if (model.canRetrySave) R.string.pause_save_pending else R.string.pause_message),
                primary = stringResource(R.string.action_continue), onPrimary = callbacks::onResume,
                secondary = stringResource(R.string.action_restart), onSecondary = callbacks::onRestart,
                tertiary = stringResource(R.string.menu_settings), onTertiary = callbacks::onOpenSettings,
                callbacks = callbacks,
                zenMode = game.zenMode,
                tips = game.tips,
            )
            UiScreen.WON -> ResultOverlay(model, callbacks, won = true)
            UiScreen.LOST -> ResultOverlay(model, callbacks, won = false)
            else -> Unit
        }
    }
    if (showBoardInfo) AlertDialog(
        onDismissRequest = { showBoardInfo = false },
        title = { Text(stringResource(R.string.board_pieces_title)) },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val resources = LocalResources.current
            Text(boardStateDescription(resources, game))
            game.board.sortedWith(compareBy({ it.row }, { it.column })).forEach {
                Text(stringResource(R.string.board_piece_line, it.row + 1, it.column + 1, bubbleDescription(resources, it)))
            }
        } },
        confirmButton = { TextButton(onClick = { showBoardInfo = false }) { Text(stringResource(R.string.back_to_game)) } },
    )
}

@Composable
private fun GameHud(game: UiGame, isFree: Boolean, model: UiModel, callbacks: UiCallbacks, compact: Boolean = false, onInspect: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = if (compact) 48.dp else 56.dp).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        PauseButton(callbacks::onPause)
        Column(Modifier.weight(1f).padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val title = when {
                game.zenMode -> stringResource(R.string.mode_zen)
                game.dailyMode -> game.levelLabel
                isFree -> stringResource(R.string.hud_endless, game.levelLabel)
                else -> game.levelLabel
            }
            Text(title, style = HudTitle, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!game.zenMode) DifficultyChip(game.difficulty)
                val potential = pluralStringResource(R.plurals.cd_stars_potential, game.starsPotential, game.starsPotential)
                if (game.starsPotential > 0) StarMeter(game.starsPotential, Modifier.semantics {
                    contentDescription = potential
                })
            }
        }
        ShotsCounter(game)
        val inspectLabel = stringResource(R.string.cd_board_pieces)
        Box(Modifier.size(48.dp).clickable(role = Role.Button, onClick = onInspect).semantics { contentDescription = inspectLabel }, contentAlignment = Alignment.Center) {
            Text("☷", style = HudTitle)
        }
    }
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
        shape = RoundedCornerShape(16.dp), color = BoardSurface.copy(alpha = .82f),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (game.danger) BoardPink.copy(alpha = .7f) else BoardLine.copy(alpha = .7f)),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    game.objectiveLabel + if (game.objectiveProgress.isNotBlank()) " · ${game.objectiveProgress}" else "",
                    style = Caption.copy(color = if (game.danger) BoardPink else BoardStrong, fontWeight = FontWeight.SemiBold), maxLines = 2,
                )
                val hint = when {
                    game.danger -> stringResource(R.string.hud_danger)
                    game.threeStarShots != null && game.shotsUsed < game.threeStarShots -> pluralStringResource(R.plurals.hud_three_stars, game.threeStarShots, game.threeStarShots)
                    game.twoStarShots != null && game.shotsUsed < game.twoStarShots -> pluralStringResource(R.plurals.hud_two_stars, game.twoStarShots, game.twoStarShots)
                    game.threeStarShots != null -> stringResource(R.string.hud_one_star)
                    isFree && !game.zenMode -> stringResource(R.string.hud_record, formatPoints(model.freeModeBest))
                    else -> ""
                }
                if (hint.isNotEmpty()) Text(hint, style = Caption.copy(fontSize = 12.sp, lineHeight = 15.sp, color = if (game.danger) BoardPink else BoardMuted), maxLines = 1)
            }
            if (!game.zenMode) ScoreBlock(game, model.reducedMotion)
        }
    }
    val hint = game.preview.rejectionLabel
    if (!hint.isNullOrBlank()) {
        Text(hint, style = Caption.copy(color = BoardOrange), modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp), maxLines = 2, textAlign = TextAlign.Center)
    }
}

@Composable
private fun TutorialCard(game: UiGame, callbacks: UiCallbacks) {
    val text = stringResource(when (game.tutorialStep) {
        0 -> if (game.preciseAim) R.string.tutorial_aim_precise else R.string.tutorial_aim_drag
        1 -> R.string.tutorial_swap
        else -> R.string.tutorial_match
    })
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = BoardRaised) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text, style = Caption.copy(color = BoardStrong))
            Text(stringResource(if (game.tutorialStep == 2) R.string.tutorial_start else R.string.tutorial_skip), color = BoardAccent,
                modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = callbacks::onFinishTutorial).wrapContentHeight())
        }
    }
}

@Composable
private fun ScoreBlock(game: UiGame, reducedMotion: Boolean) {
    val target = game.score.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    val shown by animateIntAsState(target, if (reducedMotion) tween(0) else tween(450), label = "score")
    val description = stringResource(if (game.streak > 0) R.string.cd_points_streak else R.string.points_value, formatPoints(game.score))
    Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 10.dp).semantics(mergeDescendants = true) {
        contentDescription = description
    }) {
        Text(formatPoints(shown.toLong()), style = TextStyle(fontFamily = BoardDisplayFont, fontWeight = FontWeight.Bold, color = BoardStrong, fontSize = 22.sp, lineHeight = 24.sp))
        if (game.streak > 0) {
            Text(stringResource(R.string.hud_streak, minOf(game.streak + 1, 4)), style = Caption.copy(color = BoardOrange, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold))
        } else Text(stringResource(R.string.points_unit), style = Caption.copy(fontSize = 12.sp, lineHeight = 14.sp))
    }
}

@Composable
private fun ShotsCounter(game: UiGame) {
    val remaining = game.shotsRemaining
    val low = remaining != null && remaining <= 2
    val description = if (remaining == null) stringResource(R.string.cd_unlimited_shots)
        else stringResource(R.string.cd_shots_left, remaining, game.maxShots ?: remaining)
    Column(Modifier.widthIn(min = 56.dp).padding(end = 4.dp).semantics(mergeDescendants = true) {
        contentDescription = description
    }, horizontalAlignment = Alignment.End) {
        Text(remaining?.toString() ?: "∞", style = HudTitle.copy(fontSize = 24.sp, lineHeight = 26.sp, color = if (low) BoardPink else BoardStrong))
        Text(stringResource(if (remaining == 1) R.string.shot_unit_one else R.string.shot_unit_other), style = Caption.copy(fontSize = 12.sp, lineHeight = 14.sp))
    }
}

internal fun difficultyColor(difficulty: UiDifficulty) = when (difficulty) {
    UiDifficulty.EASY -> Color(0xFF9ECE6A)
    UiDifficulty.MEDIUM -> Color(0xFF7AA2F7)
    UiDifficulty.HARD -> Color(0xFFF7768E)
}

@Composable
internal fun DifficultyChip(difficulty: UiDifficulty, modifier: Modifier = Modifier) {
    val color = difficultyColor(difficulty)
    Box(modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = .16f)).border(1.dp, color.copy(alpha = .6f), RoundedCornerShape(50))
        .padding(horizontal = 8.dp, vertical = 1.dp)) {
        Text(stringResource(difficulty.label), style = Caption.copy(color = color, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold))
    }
}

@Composable
internal fun StarMeter(filled: Int, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 13.dp, gap: androidx.compose.ui.unit.Dp = 2.dp, count: Int = 3) {
    Canvas(modifier.width(size * count + gap * (count - 1)).height(size)) {
        val step = size.toPx() + gap.toPx()
        repeat(count) { index ->
            val center = Offset(size.toPx() / 2f + index * step, this.size.height / 2f)
            if (index < filled) drawStar(center, size.toPx() / 2f, BoardOrange, 5)
            else drawStarOutline(center, size.toPx() / 2f, BoardMuted.copy(alpha = .7f))
        }
    }
}

@Composable
private fun PauseButton(onClick: () -> Unit) {
    val label = stringResource(R.string.cd_pause)
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(BoardSurface).border(1.dp, BoardLine, CircleShape), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(14.dp)) {
                val w = size.width * .3f
                drawRoundRect(BoardText, Offset(size.width * .1f, 0f), Size(w, size.height), androidx.compose.ui.geometry.CornerRadius(w / 3))
                drawRoundRect(BoardText, Offset(size.width * .6f, 0f), Size(w, size.height), androidx.compose.ui.geometry.CornerRadius(w / 3))
            }
        }
    }
}

internal fun formatPoints(value: Long): String = NumberFormat.getIntegerInstance().format(value)

private val HudTitle = TextStyle(fontFamily = BoardDisplayFont, fontWeight = FontWeight.SemiBold, color = BoardStrong, fontSize = 18.sp, lineHeight = 22.sp)
private val Caption = TextStyle(fontFamily = BoardFont, fontWeight = FontWeight.Normal, color = BoardMuted, fontSize = 13.sp, lineHeight = 18.sp)

@Composable
private fun GameBackHeader(model: UiModel, callbacks: UiCallbacks) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        GameIconButton("‹", stringResource(R.string.cd_back), callbacks::onBack)
        Text(stringResource(if (model.screen == UiScreen.PREPARATION) R.string.prep_title else R.string.app_name), style = HudTitle)
    }
}

@Composable
private fun FittedGameBoard(game: UiGame, callbacks: UiCallbacks, model: UiModel, modifier: Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val boardWidth = minOf(maxWidth, maxHeight * (18f / 32f))
        GameBoard(game, callbacks, model.reducedMotion, model.symbolsEnabled,
            Modifier.size(boardWidth, boardWidth * (32f / 18f)).clipToBounds())
    }
}

@Composable
private fun GameBoard(game: UiGame, callbacks: UiCallbacks, reducedMotion: Boolean, symbolsEnabled: Boolean, modifier: Modifier) {
    val completedIds = remember { mutableStateListOf<Long>() }
    LaunchedEffect(game.events.map { it.id }) {
        completedIds.retainAll(game.events.mapTo(mutableSetOf()) { it.id })
    }
    var activeEvent by remember { mutableStateOf<UiAnimationEvent?>(null) }
    var progress by remember { mutableFloatStateOf(1f) }
    val nextEvent = game.events.firstOrNull { it.id !in completedIds }
    val latestGame by rememberUpdatedState(game)
    val latestCallbacks by rememberUpdatedState(callbacks)

    LaunchedEffect(nextEvent?.id, reducedMotion) {
        val event = nextEvent ?: run { activeEvent = null; progress = 1f; return@LaunchedEffect }
        activeEvent = event
        progress = 0f
        val animation = Animatable(0f)
        animation.animateTo(1f, tween(if (reducedMotion) 100 else event.durationMillis.coerceIn(80, 5000), easing = LinearEasing)) { progress = value }
        if (!completedIds.contains(event.id)) {
            completedIds.add(event.id)
            activeEvent = null
            latestCallbacks.onAnimationFinished(event.id)
        }
    }

    val previewState by rememberUpdatedState(game.preview)
    val enabledState by rememberUpdatedState(game.inputEnabled && nextEvent == null)
    val callbackState by rememberUpdatedState(callbacks)
    val textMeasurer = rememberTextMeasurer()
    val resources = LocalResources.current
    val boardDescription = stringResource(R.string.cd_board, game.levelLabel,
        stringResource(if (game.preciseAim) R.string.board_hint_precise else R.string.board_hint_drag))
    val aimLeft = stringResource(R.string.a11y_aim_left)
    val aimRight = stringResource(R.string.a11y_aim_right)
    val swapLabel = stringResource(R.string.swap_bubbles)
    val shootLabel = stringResource(R.string.shoot)
    val labels = BoardLabels(stringResource(R.string.next_bubble_label), stringResource(R.string.unused_shots_float))
    Canvas(
        modifier
            .semantics {
                contentDescription = boardDescription
                stateDescription = boardStateDescription(resources, game)
                customActions = listOf(
                    CustomAccessibilityAction(aimLeft) { if (enabledState) callbackState.onPreciseAdjust(-1000); enabledState },
                    CustomAccessibilityAction(aimRight) { if (enabledState) callbackState.onPreciseAdjust(1000); enabledState },
                    CustomAccessibilityAction(swapLabel) { if (enabledState) callbackState.onSwap(); enabledState },
                    CustomAccessibilityAction(shootLabel) { val enabled = enabledState && previewState.valid; if (enabled) callbackState.onShoot(previewState.angleMilliDegrees); enabled },
                )
            }
            .onKeyEvent { keyEvent ->
                if (!enabledState || keyEvent.type != KeyEventType.KeyDown) false else when (keyEvent.key) {
                    Key.DirectionLeft -> { callbackState.onPreciseAdjust(-500); true }
                    Key.DirectionRight -> { callbackState.onPreciseAdjust(500); true }
                    Key.Enter, Key.Spacebar -> {
                        if (previewState.valid) callbackState.onShoot(previewState.angleMilliDegrees)
                        true
                    }
                    else -> false
                }
            }.focusable()
            .pointerInput(game.inputEnabled) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    if (!enabledState) return@awaitEachGesture
                    val launchX = 9f
                    val launchY = 29f
                    fun logical(point: Offset) = UiPoint(point.x / size.width * 18f, point.y / size.height * 32f)
                    val start = logical(down.position)
                    if (start.y !in 0f..27f || start.x !in 0f..18f) return@awaitEachGesture
                    var angle = angleFor(start, launchX, launchY) ?: return@awaitEachGesture
                    callbackState.onAimChanged(angle)
                    var releasedInside = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed || !enabledState) break
                        val p = logical(change.position)
                        if (change.pressed) {
                            if (p.y < launchY - .8f) {
                                val newAngle = angleFor(p, launchX, launchY)
                                if (newAngle != null) {
                                    angle = newAngle
                                    callbackState.onAimChanged(angle)
                                }
                            }
                            change.consume()
                        } else {
                            val releaseAngle = angleFor(p, launchX, launchY)
                            releasedInside = p.x in 0f..18f && p.y in 0f..29f && releaseAngle != null
                            if (releasedInside) {
                                angle = releaseAngle!!
                                callbackState.onAimChanged(angle)
                            }
                            change.consume()
                            break
                        }
                    }
                    // The host traces the same angle again and refuses it when there is no slot.
                    if (releasedInside && enabledState && !latestGame.preciseAim) callbackState.onShoot(angle)
                }
            },
    ) {
        drawNightBoard(game, nextEvent, if (activeEvent?.id == nextEvent?.id) progress else 0f, game.preview, game.currentBubble, game.nextBubble, game.leftHanded, symbolsEnabled, reducedMotion, textMeasurer, labels)
    }
}

/** Localized words drawn inside the board canvas. */
private data class BoardLabels(val next: String, val unusedShots: String)

private fun angleFor(point: UiPoint, launcherX: Float, launcherY: Float): Int? {
    val dx = point.x - launcherX
    val dyUp = launcherY - point.y
    if (dyUp <= .1f || dx * dx + dyUp * dyUp <= 4f) return null
    return (atan2(dx, dyUp) * 180_000f / PI.toFloat()).roundToInt().coerceIn(-75_000, 75_000)
}

private fun DrawScope.drawNightBoard(
    game: UiGame,
    event: UiAnimationEvent?,
    progress: Float,
    preview: UiPreview,
    current: UiBubble?,
    next: UiBubble?,
    leftHanded: Boolean,
    modelSymbols: Boolean,
    reducedMotion: Boolean,
    textMeasurer: TextMeasurer,
    labels: BoardLabels,
) {
    val unitX = size.width / 18f
    val unitY = size.height / 32f
    fun px(point: UiPoint) = Offset(point.x * unitX, point.y * unitY)
    fun center(bubble: UiBubble) = Offset((1f + bubble.column * 2f + if (bubble.row % 2 == 1) 1f else 0f) * unitX, (1f + bubble.row * kotlin.math.sqrt(3f)) * unitY)

    drawRoundRect(BoardBg.copy(alpha = .52f), topLeft = Offset(0f, 0f), size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * .1f))
    // Thin framed arch. Its curve is decorative; the physical side walls remain straight.
    drawRoundRect(BoardLine.copy(alpha = .45f), topLeft = Offset(unitX * .08f, unitY * .08f), size = Size(size.width - unitX * .16f, size.height - unitY * .16f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(unitX * 2.1f), style = Stroke(width = .8.dp.toPx()))

    val decorStars = listOf(.12f to .34f, .84f to .42f, .23f to .65f, .78f to .72f, .16f to .81f, .89f to .87f)
    decorStars.forEachIndexed { index, pair -> drawCircle(BoardPurple.copy(alpha = if (index % 2 == 0) .36f else .22f), if (index % 3 == 0) 1.6.dp.toPx() else 1.dp.toPx(), Offset(size.width * pair.first, size.height * pair.second)) }

    // Dim lunar ridges around the launcher recess.
    val ridge = Path().apply {
        moveTo(0f, size.height * .9f); cubicTo(size.width * .13f, size.height * .83f, size.width * .18f, size.height * .88f, size.width * .27f, size.height * .91f)
        cubicTo(size.width * .43f, size.height * .95f, size.width * .53f, size.height * .88f, size.width * .68f, size.height * .91f)
        cubicTo(size.width * .83f, size.height * .94f, size.width * .89f, size.height * .83f, size.width, size.height * .9f)
    }
    drawPath(ridge, BoardPurple.copy(alpha = .2f), style = Stroke(width = 1.dp.toPx()))
    val mountains = Path().apply {
        moveTo(0f, size.height * .91f); lineTo(size.width * .1f, size.height * .83f); lineTo(size.width * .17f, size.height * .91f)
        lineTo(size.width * .28f, size.height * .86f); lineTo(size.width * .39f, size.height * .93f); lineTo(size.width * .56f, size.height * .88f)
        lineTo(size.width * .69f, size.height * .94f); lineTo(size.width * .83f, size.height * .84f); lineTo(size.width, size.height * .91f)
        lineTo(size.width, size.height); lineTo(0f, size.height); close()
    }
    drawPath(mountains, Color(0xFF171B2D).copy(alpha = .82f))

    val dangerY = game.dangerLine.coerceIn(1, 31) * unitY
    val dashes = size.width / (unitX * .8f).coerceAtLeast(1f)
    repeat(dashes.toInt().coerceIn(1, 50)) { index ->
        val x = index * unitX * .8f
        drawLine(if (game.danger) BoardPink.copy(alpha = .8f) else BoardLine.copy(alpha = .4f), Offset(x, dangerY), Offset(x + unitX * .35f, dangerY), if (game.danger) 1.5.dp.toPx() else 1.dp.toPx())
    }

    val eventBubbles = event?.before ?: game.board
    val affected = event?.affected.orEmpty().toSet()
    val movingKinds = setOf(UiEventKind.MATCH, UiEventKind.DROP)
    val afterById = event?.after?.associateBy { it.id }.orEmpty()
    val afterSet = event?.after?.toSet().orEmpty()
    eventBubbles.forEach { bubble ->
        val pairedAfter = if (event == null) null
            else if (bubble.id.isNotEmpty()) afterById[bubble.id]
            else event.after.firstOrNull { it.row == bubble.row && it.column == bubble.column }
        val changedBySnapshot = if (event == null) false else when (event.kind) {
            UiEventKind.MATCH -> if (bubble.id.isNotEmpty()) pairedAfter == null else bubble !in afterSet
            UiEventKind.DROP -> pairedAfter == null || pairedAfter.row != bubble.row || pairedAfter.column != bubble.column
            else -> false
        }
        val shouldFade = event != null && event.kind in movingKinds && (bubble in affected || event.bubble == bubble || changedBySnapshot)
        if (!shouldFade) {
            drawSatinBubble(center(bubble), unitX * .96f, bubble, modelSymbols)
        } else if (event != null && event.kind == UiEventKind.DROP) {
            val fromCenter = center(bubble)
            val toCenter = pairedAfter?.let { center(it) }
                ?: (if (event.bubble == bubble) event.to?.let { px(it) } else null)
                ?: Offset(fromCenter.x, fromCenter.y + unitY * 5f)
            val t = if (reducedMotion) 0f else progress * progress
            val moved = Offset(fromCenter.x + (toCenter.x - fromCenter.x) * t, fromCenter.y + (toCenter.y - fromCenter.y) * t)
            drawSatinBubble(moved, unitX * .96f, bubble, modelSymbols, alpha = 1f - progress)
        } else if (progress < 1f) {
            drawSatinBubble(center(bubble), unitX * .96f * (if (reducedMotion) 1f else 1f - progress * .45f), bubble, modelSymbols, alpha = 1f - progress)
        }
    }

    if (event != null && event.kind == UiEventKind.SHOT && event.trajectory.isNotEmpty() && event.bubble != null) {
        val pathPoints = event.trajectory.map(::px)
        for (i in 0 until (pathPoints.size - 1).coerceAtLeast(0)) {
            if (!reducedMotion && i.toFloat() / (pathPoints.size - 1).coerceAtLeast(1) <= progress) {
                drawLine(BoardAccent.copy(alpha = .2f), pathPoints[i], pathPoints[i + 1], 1.2.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        val location = pointOnPolyline(event.trajectory, progress)
        drawSatinBubble(px(location), unitX * .96f, event.bubble, modelSymbols)
    } else if (event == null && preview.points.size > 1 && preview.valid) {
        val pts = preview.points.map(::px)
        for (i in 0 until pts.lastIndex) {
            val alpha = if (i % 2 == 0) .75f else .5f
            drawLine(BoardAccent.copy(alpha = alpha), pts[i], pts[i + 1], 1.3.dp.toPx(), cap = StrokeCap.Round)
        }
        preview.destination?.let { d -> drawCircle(BoardAccent.copy(alpha = .35f), unitX * 1.15f, px(d), style = Stroke(width = 1.dp.toPx())) }
    } else if (event == null && preview.destination != null && !preview.valid) {
        drawCircle(BoardMuted.copy(alpha = .48f), unitX * .55f, px(preview.destination), style = Stroke(width = 1.4.dp.toPx()))
        val c = px(preview.destination)
        drawLine(BoardMuted.copy(alpha = .65f), Offset(c.x - unitX * .35f, c.y - unitY * .35f), Offset(c.x + unitX * .35f, c.y + unitY * .35f), 1.dp.toPx())
    }

    val launcher = Offset(9f * unitX, 29f * unitY)
    // Orbital launcher halo with a small directional pointer.
    drawCircle(BoardPurple.copy(alpha = .15f), unitX * 2.35f, launcher)
    drawCircle(BoardPurple.copy(alpha = .66f), unitX * 1.27f, launcher, style = Stroke(width = 1.dp.toPx()))
    drawCircle(BoardAccent.copy(alpha = .45f), unitX * 1.06f, launcher, style = Stroke(width = .8.dp.toPx()))
    val aimAngle = preview.angleMilliDegrees / 1000f * PI.toFloat() / 180f
    val arrowEnd = Offset(launcher.x + sin(aimAngle) * unitX * 1.52f, launcher.y - cos(aimAngle) * unitY * 1.52f)
    drawLine(BoardAccent.copy(alpha = .9f), launcher, arrowEnd, 1.4.dp.toPx(), cap = StrokeCap.Round)
    drawCircle(BoardAccent, unitX * .1f, arrowEnd)
    current?.let { drawSatinBubble(launcher, unitX * .86f, it, modelSymbols) }

    val nextX = if (leftHanded) 5f else 13f
    next?.let {
        drawCircle(BoardPurple.copy(alpha = .45f), unitX * .74f, Offset(nextX * unitX, 29f * unitY), style = Stroke(width = .8.dp.toPx()))
        drawSatinBubble(Offset(nextX * unitX, 29f * unitY), unitX * .66f, it, modelSymbols)
        val label = textMeasurer.measure(labels.next, TextStyle(fontFamily = BoardFont, fontSize = 9.sp, color = BoardMuted, letterSpacing = 1.sp))
        drawText(label, topLeft = Offset(nextX * unitX - label.size.width / 2f, 29f * unitY + unitX * .95f))
    }

    // Floating points: over the removed bubbles, or the board centre for the unused-shot bonus.
    if (event != null && event.points > 0 && progress < 1f) {
        val anchor = if (event.kind == UiEventKind.VICTORY || event.affected.isEmpty()) Offset(size.width / 2f, size.height * .42f)
            else event.affected.map(::center).let { points -> Offset(points.map { it.x }.average().toFloat(), points.map { it.y }.average().toFloat()) }
        val rise = if (reducedMotion) 0f else progress * unitY * 2.2f
        val alpha = (1f - progress * progress).coerceIn(0f, 1f)
        val text = buildString {
            append("+").append(formatPoints(event.points))
            if (event.multiplier > 1) append("  ×").append(event.multiplier)
            if (event.kind == UiEventKind.VICTORY) append("\n").append(labels.unusedShots)
        }
        val layout = textMeasurer.measure(text, TextStyle(fontFamily = BoardDisplayFont, fontWeight = FontWeight.Bold, fontSize = 20.sp,
            color = (if (event.multiplier > 1) BoardOrange else BoardStrong).copy(alpha = alpha), textAlign = TextAlign.Center),
            constraints = Constraints(maxWidth = (size.width - 8f).toInt().coerceAtLeast(0), maxHeight = (size.height - 8f).toInt().coerceAtLeast(0)))
        val x = floatingLabelPosition(anchor.x - layout.size.width / 2f, size.width, layout.size.width.toFloat())
        val y = floatingLabelPosition(anchor.y - layout.size.height / 2f - rise, size.height, layout.size.height.toFloat())
        drawText(layout, topLeft = Offset(x, y))
    }
}

private fun pointOnPolyline(points: List<UiPoint>, fraction: Float): UiPoint {
    if (points.size == 1) return points.first()
    val lengths = points.zipWithNext { a, b -> kotlin.math.sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y)) }
    val total = lengths.sum().coerceAtLeast(.001f)
    var distance = total * fraction.coerceIn(0f, 1f)
    lengths.forEachIndexed { index, length ->
        if (distance <= length || index == lengths.lastIndex) {
            val t = (distance / length.coerceAtLeast(.001f)).coerceIn(0f, 1f)
            val a = points[index]; val b = points[index + 1]
            return UiPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        }
        distance -= length
    }
    return points.last()
}

internal fun DrawScope.drawSatinBubble(center: Offset, radius: Float, bubble: UiBubble, symbols: Boolean, alpha: Float = 1f) {
    if (bubble.kind == UiBubbleKind.ROCK) {
        val shape = Path().apply {
            moveTo(center.x - radius * .68f, center.y - radius * .56f); lineTo(center.x - radius * .12f, center.y - radius * .96f)
            lineTo(center.x + radius * .63f, center.y - radius * .62f); lineTo(center.x + radius * .91f, center.y + radius * .12f)
            lineTo(center.x + radius * .42f, center.y + radius * .85f); lineTo(center.x - radius * .52f, center.y + radius * .72f)
            lineTo(center.x - radius * .91f, center.y + radius * .08f); close()
        }
        drawCircle(Color(0xFF666C82).copy(alpha = alpha), radius, center)
        drawPath(shape, Color(0xFF9096A8).copy(alpha = alpha))
        drawPath(shape, Color(0xFFCBD0DC).copy(alpha = alpha * .55f), style = Stroke(width = .8.dp.toPx()))
        drawLine(Color(0xFF5E6378).copy(alpha = alpha), Offset(center.x - radius * .12f, center.y - radius * .96f), Offset(center.x + radius * .03f, center.y + radius * .12f), .8.dp.toPx())
        drawLine(Color(0xFF5E6378).copy(alpha = alpha), Offset(center.x - radius * .91f, center.y + radius * .08f), Offset(center.x + radius * .03f, center.y + radius * .12f), .8.dp.toPx())
        return
    }
    val fill = if (bubble.kind == UiBubbleKind.STAR) Color(0xFF312B50) else BoardColors[bubble.color] ?: BoardAccent
    drawCircle(Color.Black.copy(alpha = .28f * alpha), radius * 1.04f, Offset(center.x, center.y + radius * .08f))
    drawCircle(Brush.radialGradient(listOf(lerp(fill, Color.White, .25f).copy(alpha = alpha), fill.copy(alpha = alpha), lerp(fill, Color.Black, .4f).copy(alpha = alpha)), center = Offset(center.x - radius * .2f, center.y - radius * .3f), radius = radius * 1.45f), radius, center)
    drawCircle(fill.copy(alpha = alpha * .75f), radius, center, style = Stroke(width = .8.dp.toPx()))
    // The highlight stays small so the fixed symbol remains the visual anchor.
    drawOval(Color.White.copy(alpha = .24f * alpha), topLeft = Offset(center.x - radius * .58f, center.y - radius * .69f), size = Size(radius * .5f, radius * .17f))
    if (bubble.kind == UiBubbleKind.STAR) {
        drawCircle(BoardPurple.copy(alpha = alpha), radius * .92f, center, style = Stroke(width = 1.5.dp.toPx()))
        drawCircle(BoardText.copy(alpha = alpha), radius * .68f, center, style = Stroke(width = 1.dp.toPx()))
        drawStar(center, radius * .39f, BoardOrange.copy(alpha = alpha), 5)
    } else if (symbols) {
        drawSymbol(center, radius * .38f, bubble.color ?: UiBubbleColor.BLUE, alpha)
    }
}

private fun DrawScope.drawSymbol(center: Offset, r: Float, color: UiBubbleColor, alpha: Float) {
    val dark = Color(0xFF16161E).copy(alpha = alpha * .88f)
    when (color) {
        UiBubbleColor.BLUE -> drawCircle(dark, r * .72f, center)
        UiBubbleColor.PURPLE -> drawStar(center, r, dark, 5)
        UiBubbleColor.GREEN -> {
            val path = Path().apply { moveTo(center.x, center.y - r); lineTo(center.x + r * .88f, center.y + r * .72f); lineTo(center.x - r * .88f, center.y + r * .72f); close() }
            drawPath(path, dark)
        }
        UiBubbleColor.ORANGE -> drawRect(dark, Offset(center.x - r * .68f, center.y - r * .68f), Size(r * 1.36f, r * 1.36f))
        UiBubbleColor.PINK -> {
            val path = Path().apply { moveTo(center.x, center.y - r); lineTo(center.x + r, center.y); lineTo(center.x, center.y + r); lineTo(center.x - r, center.y); close() }
            drawPath(path, dark)
        }
        UiBubbleColor.CYAN -> {
            drawRoundRect(dark, Offset(center.x - r * .28f, center.y - r), Size(r * .56f, r * 2f), androidx.compose.ui.geometry.CornerRadius(r * .2f))
            drawRoundRect(dark, Offset(center.x - r, center.y - r * .28f), Size(r * 2f, r * .56f), androidx.compose.ui.geometry.CornerRadius(r * .2f))
        }
    }
}

internal fun DrawScope.drawStarOutline(center: Offset, radius: Float, color: Color) {
    val path = Path()
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) radius else radius * .46f
        val angle = -PI / 2 + i * PI / 5
        val x = center.x + cos(angle).toFloat() * r
        val y = center.y + sin(angle).toFloat() * r
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color, style = Stroke(width = 1.dp.toPx()))
}

internal fun DrawScope.drawStar(center: Offset, radius: Float, color: Color, points: Int) {
    val path = Path()
    val inner = radius * .46f
    for (i in 0 until points * 2) {
        val r = if (i % 2 == 0) radius else inner
        val angle = -PI / 2 + i * PI / points
        val x = center.x + cos(angle).toFloat() * r
        val y = center.y + sin(angle).toFloat() * r
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color)
}

@Composable
private fun SwapAndNext(game: UiGame, callbacks: UiCallbacks, compact: Boolean = false) {
    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        val swapLabel = stringResource(if (compact) R.string.swap_short else R.string.swap_bubbles)
        val swapDescription = stringResource(R.string.swap_bubbles)
        Surface(
            Modifier.heightIn(min = 48.dp).clickable(enabled = game.inputEnabled, role = Role.Button, onClick = callbacks::onSwap)
                .semantics { contentDescription = swapDescription },
            shape = RoundedCornerShape(15.dp), color = BoardSurface, border = androidx.compose.foundation.BorderStroke(1.dp, BoardLine),
        ) {
            Row(Modifier.padding(horizontal = 15.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                SwapGlyph(Modifier.size(20.dp))
                Text(swapLabel, style = TextStyle(fontFamily = BoardFont, color = BoardText, fontSize = 15.sp, fontWeight = FontWeight.Medium))
            }
        }
    }
}

@Composable
private fun SwapGlyph(modifier: Modifier) {
    Canvas(modifier) {
        val stroke = 1.6.dp.toPx()
        val w = size.width; val h = size.height
        drawLine(BoardAccent, Offset(w * .15f, h * .35f), Offset(w * .85f, h * .35f), stroke, StrokeCap.Round)
        drawLine(BoardAccent, Offset(w * .85f, h * .35f), Offset(w * .65f, h * .15f), stroke, StrokeCap.Round)
        drawLine(BoardAccent, Offset(w * .85f, h * .65f), Offset(w * .15f, h * .65f), stroke, StrokeCap.Round)
        drawLine(BoardAccent, Offset(w * .15f, h * .65f), Offset(w * .35f, h * .85f), stroke, StrokeCap.Round)
    }
}

@Composable
private fun PreciseControls(game: UiGame, callbacks: UiCallbacks, vertical: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (vertical) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AimControl(-1, game, callbacks, Modifier.weight(1f))
                AimControl(1, game, callbacks, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ControlButton(stringResource(R.string.swap_short), stringResource(R.string.swap_bubbles), Modifier.weight(1f), enabled = game.inputEnabled, onClick = callbacks::onSwap)
                FireControl(game, callbacks, Modifier.weight(1f))
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AimControl(-1, game, callbacks, Modifier.weight(1f))
                AimControl(1, game, callbacks, Modifier.weight(1f))
                FireControl(game, callbacks, Modifier.weight(1f))
            }
            ControlButton(stringResource(R.string.swap_bubbles), stringResource(R.string.swap_bubbles), Modifier.fillMaxWidth(), enabled = game.inputEnabled, onClick = callbacks::onSwap)
        }
    }
}

@Composable
private fun FireControl(game: UiGame, callbacks: UiCallbacks, modifier: Modifier) {
    ControlButton(stringResource(R.string.shoot), stringResource(R.string.shoot_bubble), modifier, primary = true, enabled = game.inputEnabled && game.preview.valid) {
        callbacks.onShoot(game.preview.angleMilliDegrees.coerceIn(-75_000, 75_000))
    }
}

@Composable
private fun AimControl(direction: Int, game: UiGame, callbacks: UiCallbacks, modifier: Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val enabled by rememberUpdatedState(game.inputEnabled)
    val latestCallback by rememberUpdatedState(callbacks)
    var repeated by remember { mutableStateOf(false) }
    LaunchedEffect(pressed, game.inputEnabled) {
        if (!pressed || !enabled) return@LaunchedEffect
        repeated = false
        delay(400)
        var ticks = 0
        while (enabled) {
            repeated = true
            latestCallback.onPreciseAdjust(direction * 500)
            delay(if (ticks++ < 8) 120 else 50)
        }
    }
    val label = stringResource(if (direction < 0) R.string.aim_left else R.string.aim_right)
    val description = stringResource(R.string.cd_fine_aim, label)
    val fineAdjustLabel = stringResource(R.string.a11y_fine_aim)
    Surface(
        modifier.heightIn(min = 48.dp).clickable(
            enabled = game.inputEnabled, role = Role.Button,
            interactionSource = interactions, indication = LocalIndication.current,
            onClick = { if (!repeated) callbacks.onPreciseAdjust(direction * 500) },
        ).semantics {
            contentDescription = description
            customActions = listOf(CustomAccessibilityAction(fineAdjustLabel) {
                if (enabled) latestCallback.onPreciseAdjust(direction * 100)
                enabled
            })
        },
        shape = RoundedCornerShape(13.dp), color = if (pressed) BoardRaised else BoardSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, BoardLine),
    ) {
        Box(Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
            Text(label, style = Caption.copy(color = BoardText, fontSize = 14.sp), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ControlButton(label: String, description: String, modifier: Modifier, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(modifier.heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = description }, shape = RoundedCornerShape(13.dp), color = if (primary) BoardAccent else BoardSurface, border = if (primary) null else androidx.compose.foundation.BorderStroke(1.dp, BoardLine)) {
        Box(Modifier.padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
            Text(label, style = TextStyle(color = if (primary) BoardOnAccent else BoardText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun GameIconButton(symbol: String, description: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Text(symbol, color = BoardText, fontSize = 25.sp)
    }
}

@Composable
private fun GameAction(label: String, onClick: () -> Unit, primary: Boolean, enabled: Boolean = true) {
    Surface(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick), shape = RoundedCornerShape(15.dp), color = if (primary) BoardAccent else BoardSurface) {
        Box(contentAlignment = Alignment.Center) { Text(label, color = if (primary) BoardOnAccent else BoardText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun GameOverlay(
    title: String,
    message: String,
    primary: String,
    onPrimary: () -> Unit,
    secondary: String,
    onSecondary: () -> Unit,
    tertiary: String,
    onTertiary: () -> Unit,
    callbacks: UiCallbacks,
    zenMode: Boolean = false,
    tips: List<UiTip> = emptyList(),
) {
    Box(Modifier.fillMaxSize().background(BoardBg.copy(alpha = .76f)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxWidth().padding(horizontal = 22.dp).widthIn(max = 440.dp), shape = RoundedCornerShape(28.dp), color = BoardSurface, border = androidx.compose.foundation.BorderStroke(1.dp, BoardLine)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("☾", color = BoardPurple, fontSize = 31.sp)
                Text(title, style = TextStyle(fontFamily = BoardDisplayFont, fontWeight = FontWeight.Bold, color = BoardStrong, fontSize = 26.sp))
                Text(message, style = TextStyle(color = BoardText, fontSize = 16.sp, lineHeight = 22.sp), textAlign = TextAlign.Center)
                if (tips.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(BoardRaised).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.pause_in_level), style = Caption.copy(color = BoardPurple, fontWeight = FontWeight.SemiBold))
                        tips.forEach { TipRow(it) }
                    }
                }
                GameAction(primary, onPrimary, primary = true)
                GameAction(secondary, onSecondary, primary = false)
                if (zenMode) GameAction(stringResource(R.string.action_other_board), callbacks::onNextLevel, primary = false)
                Text(tertiary, Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onTertiary).wrapContentWidth().padding(horizontal = 8.dp).wrapContentHeight(), color = BoardMuted, fontSize = 15.sp)
                Text(stringResource(R.string.action_exit_to_start), Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = callbacks::onReturnToStart).wrapContentWidth().padding(horizontal = 8.dp).wrapContentHeight(), color = BoardMuted, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun ResultOverlay(model: UiModel, callbacks: UiCallbacks, won: Boolean) {
    val result = model.result
    Box(Modifier.fillMaxSize().background(BoardBg.copy(alpha = .8f)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp).widthIn(max = 440.dp), shape = RoundedCornerShape(28.dp), color = BoardSurface, border = androidx.compose.foundation.BorderStroke(1.dp, if (won) BoardPurple.copy(alpha = .8f) else BoardLine)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (won && result?.maxShots != null && result.waveScore == null) {
                    ResultStars(result.stars, model.reducedMotion)
                } else Text(if (won) "✦" else "☾", color = if (won) BoardOrange else BoardPurple, fontSize = 30.sp)
                Text(result?.title ?: stringResource(if (won) R.string.result_won_title else R.string.game_over), style = TextStyle(fontFamily = BoardDisplayFont, fontWeight = FontWeight.Bold, color = BoardStrong, fontSize = 25.sp, lineHeight = 30.sp), textAlign = TextAlign.Center)
                Text(result?.message.orEmpty(), style = TextStyle(fontFamily = BoardFont, color = BoardText, fontSize = 15.sp, lineHeight = 21.sp), textAlign = TextAlign.Center)
                result?.orbitSummary?.let { Text(it, style = Caption, textAlign = TextAlign.Center) }
                if (result != null && result.showScore) ResultScore(result, model.reducedMotion)
                if (result?.maxShots != null) {
                    Text(stringResource(R.string.result_shots_used, result.shotsUsed, result.maxShots), style = Caption)
                }
                val hasNext = result?.hasNext != false
                val primaryLabel = result?.primaryLabel?.takeIf { it.isNotBlank() } ?: stringResource(when {
                    !won -> R.string.action_try_again
                    result?.waveScore != null -> R.string.action_next_wave
                    hasNext -> R.string.action_next_level
                    else -> R.string.action_map
                })
                val primaryAction: () -> Unit = if (!won) callbacks::onRestart else if (hasNext) callbacks::onNextLevel else callbacks::onOpenMap
                Spacer(Modifier.height(2.dp))
                GameAction(primaryLabel, primaryAction, primary = true)
                if (won && result?.maxShots != null && result.waveScore == null) GameAction(stringResource(R.string.action_replay_level), callbacks::onRestart, primary = false)
                if (result?.dailyDate != null) GameAction(stringResource(R.string.action_choose_challenge), callbacks::onOpenDaily, primary = false)
                else if (result?.waveScore == null && (!won || hasNext)) GameAction(stringResource(R.string.action_orbit_map), callbacks::onOpenMap, primary = false)
                if (result?.showScore == true) GameAction(stringResource(R.string.action_share_result), callbacks::onShareResult, primary = false)
                Text(stringResource(R.string.action_back_to_start), Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = callbacks::onReturnToStart).padding(horizontal = 12.dp).wrapContentHeight(), color = BoardMuted, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun ResultStars(stars: Int, reducedMotion: Boolean) {
    val scales = List(3) { remember { Animatable(if (reducedMotion) 1f else 0f) } }
    LaunchedEffect(stars) {
        if (reducedMotion) return@LaunchedEffect
        scales.forEachIndexed { index, scale ->
            if (index < stars) { delay(140); scale.animateTo(1f, tween(260)) } else scale.snapTo(1f)
        }
    }
    val description = stringResource(R.string.cd_stars_of_three, stars)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom,
        modifier = Modifier.semantics { contentDescription = description }) {
        repeat(3) { index ->
            val big = index == 1
            Canvas(Modifier.size(if (big) 46.dp else 36.dp)) {
                val r = size.minDimension / 2f
                if (index < stars) drawStar(center, r * scales[index].value.coerceAtLeast(.01f), BoardOrange, 5)
                else drawStarOutline(center, r * .92f, BoardMuted.copy(alpha = .6f))
            }
        }
    }
}

@Composable
private fun ResultScore(result: UiResult, reducedMotion: Boolean) {
    val target = result.score.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    val counted = remember { Animatable(if (reducedMotion) target.toFloat() else 0f) }
    LaunchedEffect(target) { if (!reducedMotion) counted.animateTo(target.toFloat(), tween(700)) }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = BoardRaised) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(if (result.waveScore != null) R.string.run_total else R.string.score_label), style = Caption)
                    Text(formatPoints(counted.value.roundToInt().toLong()), style = TextStyle(fontFamily = BoardDisplayFont, fontWeight = FontWeight.Bold, color = BoardStrong, fontSize = 30.sp, lineHeight = 34.sp))
                }
                when {
                    result.newRecord -> Box(Modifier.clip(RoundedCornerShape(50)).background(BoardPurple).padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Text(stringResource(R.string.new_record), style = Caption.copy(color = BoardOnAccent, fontWeight = FontWeight.Bold))
                    }
                    result.previousBest > 0 -> Column(horizontalAlignment = Alignment.End) {
                        Text(stringResource(if (result.waveScore != null) R.string.record else R.string.best), style = Caption)
                        Text(formatPoints(result.previousBest), style = Caption.copy(color = BoardText, fontWeight = FontWeight.SemiBold))
                    }
                }
            }
            result.waveScore?.let { ScoreLine(stringResource(R.string.this_wave), it, strong = true) }
            result.breakdown.forEach { ScoreLine(it.label, it.points) }
            if (result.breakdown.isNotEmpty()) {
                Text(stringResource(R.string.result_difficulty_factor, stringResource(result.difficulty.label), stringResource(result.difficulty.scoreFactor)), style = Caption.copy(fontSize = 12.sp, color = difficultyColor(result.difficulty)), modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun ScoreLine(label: String, points: Long, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = Caption.copy(color = if (strong) BoardText else BoardMuted), modifier = Modifier.weight(1f))
        Text("+${formatPoints(points)}", style = Caption.copy(color = if (strong) BoardStrong else BoardText, fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun ResultValue(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = TextStyle(fontFamily = BoardDisplayFont, fontWeight = FontWeight.Bold, color = BoardStrong, fontSize = 22.sp))
        Text(label, style = Caption)
    }
}
