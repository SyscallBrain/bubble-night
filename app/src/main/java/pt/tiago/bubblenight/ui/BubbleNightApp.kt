package pt.tiago.bubblenight.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pt.tiago.bubblenight.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch

private object NightPalette {
    val background = Color(0xFF1A1B26)
    val surface = Color(0xFF24283B)
    val raised = Color(0xFF292E42)
    val text = Color(0xFFC0CAF5)
    val strong = Color(0xFFE2E8FF)
    val muted = Color(0xFF8C94BE)
    val accent = Color(0xFF7AA2F7)
    val purple = Color(0xFFBB9AF7)
    val green = Color(0xFF9ECE6A)
    val orange = Color(0xFFFF9E64)
    val pink = Color(0xFFF7768E)
    val cyan = Color(0xFF7DCFFF)
    val line = Color(0xFF414868)
    val onAccent = Color(0xFF16161E)
}

private val FigTree = FontFamily(Font(R.font.figtree))
private val Bricolage = FontFamily(Font(R.font.bricolage_grotesque))

/** Compose entry point; the host owns navigation, storage and all game rules. */
@Composable
fun BubbleNightApp(
    model: UiModel,
    callbacks: UiCallbacks,
    modifier: Modifier = Modifier,
) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = NightPalette.accent, onPrimary = NightPalette.onAccent,
            background = NightPalette.background, onBackground = NightPalette.text, surface = NightPalette.surface,
            onSurface = NightPalette.text, secondary = NightPalette.purple, error = NightPalette.pink),
        typography = Typography(bodyLarge = BodyStyle, bodyMedium = BodyStyle, bodySmall = LabelStyle,
            titleLarge = TitleStyle, titleMedium = CardTitleStyle, labelLarge = BodyStyle),
    ) {
        Box(Modifier.fillMaxSize().background(NightPalette.background)) {
            NightBackdrop(Modifier.fillMaxSize(), model.orbitNumber)
            Box(
                modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                contentAlignment = Alignment.TopCenter,
            ) {
                val pageModifier = Modifier.widthIn(max = 520.dp).fillMaxSize()
                when (model.screen) {
                    UiScreen.START -> StartPage(model, callbacks, pageModifier)
                    UiScreen.MAP -> MapPage(model, callbacks, pageModifier)
                    UiScreen.PREPARATION -> PreparationPage(model, callbacks, pageModifier)
                    UiScreen.FREE_MODE_SELECT -> FreeModePage(model, callbacks, pageModifier)
                    UiScreen.GAME, UiScreen.FREE_GAME, UiScreen.PAUSED, UiScreen.WON, UiScreen.LOST ->
                        GamePage(model, callbacks, Modifier.fillMaxSize())
                    UiScreen.SETTINGS -> SettingsPage(model, callbacks, pageModifier)
                    UiScreen.STATISTICS -> StatisticsPage(model, callbacks, pageModifier)
                    UiScreen.CREDITS -> CreditsPage(model, callbacks, pageModifier)
                    UiScreen.DAILY -> DailyPage(model, callbacks, pageModifier)
                    UiScreen.ZEN_SELECT -> ZenSelectionPage(model, callbacks, pageModifier)
                }
            }
            model.loadingMessage?.let { LoadingOverlay(it) }
            model.errorMessage?.let { ErrorBanner(it, callbacks, model.canRetryLoad, model.canRetrySave) }
            model.confirmation?.let { confirmation ->
                AlertDialog(
                    onDismissRequest = callbacks::onDismissConfirmation,
                    title = { Text(confirmation.title, style = TitleStyle) },
                    text = { Text(confirmation.message, style = BodyStyle) },
                    confirmButton = {
                        TextButton(onClick = callbacks::onConfirmAction) {
                            Text(confirmation.confirmLabel, color = if (confirmation.destructive) NightPalette.pink else NightPalette.accent)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = callbacks::onDismissConfirmation) { Text(stringResource(R.string.cancel), color = NightPalette.text) }
                    },
                    containerColor = NightPalette.surface,
                    titleContentColor = NightPalette.strong,
                    textContentColor = NightPalette.text,
                )
            }
        }
    }
}

private val TitleStyle = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.SemiBold, color = NightPalette.strong, fontSize = 25.sp, lineHeight = 31.sp)
private val CardTitleStyle = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.SemiBold, color = NightPalette.strong, fontSize = 20.sp, lineHeight = 26.sp)
private val BodyStyle = TextStyle(fontFamily = FigTree, fontWeight = FontWeight.Normal, color = NightPalette.text, fontSize = 16.sp, lineHeight = 22.sp)
private val LabelStyle = TextStyle(fontFamily = FigTree, fontWeight = FontWeight.Medium, color = NightPalette.muted, fontSize = 13.sp, lineHeight = 18.sp)

@Composable
private fun NightBackdrop(modifier: Modifier = Modifier, orbitNumber: Int = 1) {
    // The sky is shared; the planet, rings, moons and skyline are the orbit's own vector illustration.
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val randomStars = listOf(
            .08f to .08f, .81f to .12f, .93f to .28f, .15f to .32f, .74f to .43f,
            .06f to .58f, .88f to .66f, .31f to .72f, .95f to .84f, .12f to .9f,
        )
        randomStars.forEachIndexed { index, (x, y) ->
            val radius = if (index % 3 == 0) 1.8f else 1.1f
            drawCircle(if (index % 4 == 0) NightPalette.purple.copy(alpha = .75f) else NightPalette.text.copy(alpha = .4f), radius, Offset(w * x, h * y))
        }
        drawOrbitArt(orbitArt(orbitNumber))
    }
}

@Composable
private fun StartPage(model: UiModel, callbacks: UiCallbacks, modifier: Modifier) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 112.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Bubble", style = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, color = NightPalette.strong, fontSize = 38.sp, lineHeight = 40.sp))
                Text("Night", style = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, color = NightPalette.purple, fontSize = 38.sp, lineHeight = 40.sp))
                Text(stringResource(R.string.home_tagline), style = LabelStyle)
            }
            MiniPlanet(Modifier.size(112.dp))
        }

        val openMapLabel = stringResource(R.string.cd_open_orbit_map, model.currentOrbitName)
        Surface(
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = callbacks::onOpenMap)
                .semantics { contentDescription = openMapLabel },
            shape = RoundedCornerShape(22.dp), color = NightPalette.surface.copy(alpha = .94f),
            border = androidx.compose.foundation.BorderStroke(1.dp, NightPalette.line.copy(alpha = .8f)),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.home_campaign), style = LabelStyle.copy(color = NightPalette.purple, letterSpacing = 2.sp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(model.currentOrbitName, style = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, color = NightPalette.strong, fontSize = 27.sp, lineHeight = 33.sp))
                        val stats = model.statistics
                        val progress = if (stats.totalLevels > 0L) stringResource(R.string.progress_completed_of, stats.levelsCompleted, stats.totalLevels)
                            else if (model.firstRun) stringResource(R.string.progress_first_run) else stringResource(R.string.progress_completed, stats.levelsCompleted)
                        Text(progress, style = BodyStyle.copy(color = NightPalette.text))
                    }
                    Text("✦", color = NightPalette.purple, fontSize = 30.sp)
                }
                OrbitPreview(model.orbit, callbacks::onOpenMap)
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            PrimaryButton(
                stringResource(when {
                    model.firstRun -> R.string.home_start_adventure
                    model.hasCampaignSession -> R.string.home_resume_campaign
                    else -> R.string.home_continue_campaign
                }),
                callbacks::onContinue,
            )
            Text(
                when {
                    model.firstRun -> stringResource(R.string.home_first_level_hint)
                    model.hasCampaignSession -> stringResource(R.string.home_saved_game, model.currentLevelLabel)
                    else -> stringResource(R.string.home_next_challenge, model.currentLevelLabel)
                },
                style = LabelStyle,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (model.hasFreeSession) MenuRow(stringResource(R.string.menu_resume_free), stringResource(R.string.menu_resume_free_sub), "◉", callbacks::onResumeFreeGame)
        MenuRow(stringResource(R.string.menu_daily), stringResource(R.string.menu_daily_sub), "✦", callbacks::onOpenDaily)
        MenuRow(stringResource(R.string.menu_free), stringResource(R.string.menu_free_sub), "◉", callbacks::onOpenFreeGame)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            MenuRow(stringResource(R.string.menu_settings), stringResource(R.string.menu_settings_sub), "⚙", callbacks::onOpenSettings, Modifier.weight(1f))
            MenuRow(stringResource(R.string.menu_statistics), stringResource(R.string.menu_statistics_sub), "✧", callbacks::onOpenStatistics, Modifier.weight(1f))
        }
        TextButtonLine(stringResource(R.string.menu_credits), callbacks::onOpenCredits, Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun MiniPlanet(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val center = Offset(size.width * .5f, size.height * .5f)
        drawOval(NightPalette.purple.copy(alpha = .6f), topLeft = Offset(size.width * .05f, size.height * .37f), size = Size(size.width * .9f, size.height * .3f), style = Stroke(width = 1.1.dp.toPx()))
        drawCircle(Color(0xFF303653), size.minDimension * .29f, center)
        drawCircle(NightPalette.accent.copy(alpha = .22f), size.minDimension * .29f, center, style = Stroke(width = 1.dp.toPx()))
        drawCircle(NightPalette.pink, size.minDimension * .035f, Offset(size.width * .91f, size.height * .44f))
        drawCircle(NightPalette.accent, size.minDimension * .035f, Offset(size.width * .13f, size.height * .59f))
        drawCircle(NightPalette.text.copy(alpha = .28f), size.minDimension * .3f, center, style = Stroke(width = 1.dp.toPx()))
    }
}

@Composable
private fun OrbitPreview(orbit: UiOrbit?, onClick: () -> Unit) {
    val nodes = orbit?.nodes?.take(6).orEmpty()
    if (nodes.isEmpty()) return
    Row(Modifier.fillMaxWidth().height(78.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
        nodes.forEachIndexed { index, node ->
            val nodeLabel = node.accessibleLabel ?: stringResource(R.string.label_level, node.number)
            if (index > 0) Canvas(Modifier.weight(.6f).height(30.dp)) {
                val p = Path().apply { moveTo(0f, size.height * .5f); cubicTo(size.width * .35f, 0f, size.width * .65f, size.height, size.width, size.height * .5f) }
                drawPath(p, NightPalette.purple.copy(alpha = .7f), style = Stroke(1.4.dp.toPx()))
            }
            Column(Modifier.weight(1f).clickable(role = Role.Button, onClick = { if (node.state != UiLevelNode.NodeState.LOCKED) onClick() }).semantics { contentDescription = nodeLabel }, horizontalAlignment = Alignment.CenterHorizontally) {
                NodeDot(node, 36.dp)
                if (node.stars > 0) Text("★".repeat(node.stars.coerceIn(1, 3)), style = LabelStyle.copy(color = NightPalette.orange, fontSize = 10.sp, lineHeight = 12.sp))
            }
        }
    }
}

@Composable
private fun NodeDot(node: UiLevelNode, size: androidx.compose.ui.unit.Dp) {
    val (fill, stroke, text) = when (node.state) {
        UiLevelNode.NodeState.CURRENT -> Triple(NightPalette.accent, NightPalette.accent, NightPalette.onAccent)
        UiLevelNode.NodeState.AVAILABLE -> Triple(NightPalette.surface, NightPalette.accent, NightPalette.text)
        UiLevelNode.NodeState.LOCKED -> Triple(NightPalette.raised, NightPalette.line, NightPalette.muted)
        UiLevelNode.NodeState.COMPLETED -> Triple(NightPalette.surface, NightPalette.purple, NightPalette.text)
    }
    Box(Modifier.size(size).clip(CircleShape).background(fill).border(1.5.dp, stroke, CircleShape), contentAlignment = Alignment.Center) {
        Text(node.number.toString(), style = TextStyle(fontFamily = FigTree, fontWeight = FontWeight.SemiBold, color = text, fontSize = if (size < 38.dp) 12.sp else 14.sp))
    }
}

@Composable
private fun MenuRow(title: String, subtitle: String, glyph: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.heightIn(min = 78.dp).clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(18.dp), color = NightPalette.surface.copy(alpha = .96f),
        border = androidx.compose.foundation.BorderStroke(1.dp, NightPalette.line.copy(alpha = .78f)),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(glyph, color = NightPalette.purple, fontSize = 25.sp)
            Column(Modifier.weight(1f)) {
                Text(title, style = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.SemiBold, color = NightPalette.strong, fontSize = 16.sp, lineHeight = 20.sp))
                Text(subtitle, style = LabelStyle, maxLines = 2)
            }
            Text("›", color = NightPalette.muted, fontSize = 26.sp)
        }
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick), shape = RoundedCornerShape(16.dp), color = NightPalette.accent) {
        Box(contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(label, style = TextStyle(fontFamily = FigTree, fontWeight = FontWeight.SemiBold, color = NightPalette.onAccent, fontSize = 17.sp, lineHeight = 22.sp))
                Text("›", color = NightPalette.onAccent, fontSize = 23.sp)
            }
        }
    }
}

@Composable
private fun TextButtonLine(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, style = LabelStyle.copy(color = NightPalette.text))
    }
}

@Composable
private fun PageHeader(title: String, onBack: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        val backLabel = stringResource(R.string.cd_back)
        Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onBack), contentAlignment = Alignment.Center) {
            Text("‹", color = NightPalette.text, fontSize = 32.sp, modifier = Modifier.semantics { contentDescription = backLabel })
        }
        Text(title, style = TitleStyle, modifier = Modifier.weight(1f), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        trailing?.invoke()
    }
}

@Composable
private fun MapPage(model: UiModel, callbacks: UiCallbacks, modifier: Modifier) {
    val orbit = model.orbit
    var unfinishedStars by remember { mutableStateOf(false) }
    val nodes = orbit?.nodes.orEmpty().filter { !unfinishedStars || (it.state == UiLevelNode.NodeState.COMPLETED && it.stars < 3) }
    val initialIndex = orbit?.nodes?.indexOfFirst { it.state == UiLevelNode.NodeState.CURRENT }?.takeIf { it >= 0 }
        ?: orbit?.nodes?.indexOfLast { it.state == UiLevelNode.NodeState.COMPLETED }?.takeIf { it >= 0 } ?: 0
    val initialItem = if (initialIndex < 2) 0 else initialIndex + 1
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialItem)
    LaunchedEffect(orbit?.id, model.difficulty, unfinishedStars) { listState.scrollToItem(if (unfinishedStars) 0 else initialItem) }
    Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        PageHeader(orbit?.name ?: model.currentOrbitName, callbacks::onBack, trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallAction("‹", stringResource(R.string.cd_previous_orbit)) { callbacks.onOrbitChanged(-1) }
                SmallAction("›", stringResource(R.string.cd_next_orbit)) { callbacks.onOrbitChanged(1) }
            }
        })
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
            item { DifficultyPicker(model.difficulty, callbacks::onDifficultyChanged) }
            item { Text(stringResource(R.string.progress_completed_of, orbit?.completed ?: 0, orbit?.total ?: 0), style = LabelStyle, modifier = Modifier.padding(8.dp)) }
            item { SettingRow(stringResource(R.string.map_unfinished_stars), unfinishedStars) { unfinishedStars = it } }
            if (orbit != null && orbit.completed == orbit.total) item {
                Text(stringResource(R.string.map_orbit_complete, orbit.nodes.sumOf { it.stars }, orbit.total * 3), style = LabelStyle, modifier = Modifier.padding(8.dp))
            }
            if (nodes.isEmpty()) item {
                Text(stringResource(if (unfinishedStars) R.string.map_no_unfinished else R.string.map_unavailable), style = BodyStyle, modifier = Modifier.padding(12.dp))
            }
            itemsIndexed(nodes, key = { _, node -> node.id }) { index, node ->
                MapNodeRow(node, index, onSelect = { callbacks.onOpenPreparation(node.id) })
            }
        }
        val current = orbit?.nodes?.firstOrNull { it.state == UiLevelNode.NodeState.CURRENT }
        if (!unfinishedStars && current != null) PrimaryButton(stringResource(R.string.map_play_level, current.number), {
            callbacks.onOpenPreparation(current.id)
        }, Modifier.padding(bottom = 8.dp))
    }
}

@Composable
private fun DifficultyPicker(selected: UiDifficulty, onSelected: (UiDifficulty) -> Unit, showSummary: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(NightPalette.surface).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            UiDifficulty.entries.forEach { difficulty ->
                val active = selected == difficulty
                val color = difficultyColor(difficulty)
                val label = stringResource(difficulty.label)
                val description = stringResource(if (active) R.string.cd_difficulty_selected else R.string.cd_difficulty, label)
                Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (active) color.copy(alpha = .18f) else Color.Transparent)
                    .border(1.dp, if (active) color.copy(alpha = .7f) else Color.Transparent, RoundedCornerShape(12.dp))
                    .clickable(role = Role.RadioButton, onClick = { onSelected(difficulty) }).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
                    Text(label, style = BodyStyle.copy(color = if (active) color else NightPalette.muted, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal))
                }
            }
        }
        if (showSummary) Text(stringResource(R.string.difficulty_summary_with_points, stringResource(selected.summary), stringResource(selected.scoreFactor)), style = LabelStyle, modifier = Modifier.padding(horizontal = 6.dp))
    }
}

@Composable
private fun MapNodeRow(node: UiLevelNode, index: Int, onSelect: () -> Unit) {
    val positions = listOf(.2f, .4f, .7f, .5f)
    val x = positions[index % positions.size]
    val description = node.accessibleLabel ?: stringResource(
        if (node.state == UiLevelNode.NodeState.LOCKED) R.string.cd_level_locked else R.string.cd_level_available, node.number)
    BoxWithConstraints(Modifier.fillMaxWidth().height(84.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val currentX = size.width * x
            val previousX = size.width * positions[(index - 1).mod(positions.size)]
            if (index > 0) {
                val path = Path().apply {
                    moveTo(previousX, 0f)
                    cubicTo(previousX, size.height * .45f, currentX, size.height * .55f, currentX, size.height)
                }
                drawPath(path, if (node.state == UiLevelNode.NodeState.LOCKED) NightPalette.line.copy(alpha = .7f) else NightPalette.purple.copy(alpha = .62f), style = Stroke(1.5.dp.toPx()))
            }
        }
        Column(
            Modifier.align(Alignment.CenterStart).padding(start = maxWidth * x - 28.dp)
                .widthIn(min = 56.dp).heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onSelect)
                .semantics { contentDescription = description },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NodeDot(node, 44.dp)
            if (node.state == UiLevelNode.NodeState.LOCKED) Text(stringResource(R.string.map_locked), style = LabelStyle)
            if (node.stars > 0) Text("★".repeat(node.stars.coerceIn(1, 3)), style = LabelStyle.copy(color = NightPalette.orange, fontSize = 11.sp, lineHeight = 14.sp))
        }
    }
}

@Composable
private fun SmallAction(symbol: String, label: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Text(symbol, color = NightPalette.text, fontSize = 26.sp)
    }
}

@Composable
private fun PreparationPage(model: UiModel, callbacks: UiCallbacks, modifier: Modifier) {
    val prep = model.preparation
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        PageHeader(stringResource(R.string.prep_title), callbacks::onBack)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(prep?.levelLabel ?: model.currentLevelLabel, style = LabelStyle.copy(color = NightPalette.purple, letterSpacing = 1.sp))
            Text(prep?.title ?: stringResource(R.string.prep_ready), style = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, color = NightPalette.strong, fontSize = 30.sp, lineHeight = 36.sp))
            Text(prep?.objective ?: stringResource(R.string.prep_default_objective), style = BodyStyle)
        }
        Surface(shape = RoundedCornerShape(22.dp), color = NightPalette.surface.copy(alpha = .96f), border = androidx.compose.foundation.BorderStroke(1.dp, NightPalette.line)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.prep_objective), style = LabelStyle)
                        Text(prep?.objective ?: stringResource(R.string.prep_default_goal), style = BodyStyle.copy(color = NightPalette.strong, fontWeight = FontWeight.SemiBold))
                    }
                    Text("✦", color = NightPalette.orange, fontSize = 27.sp)
                }
                val difficulty = prep?.difficulty ?: model.difficulty
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.prep_difficulty), style = LabelStyle)
                        DifficultyChip(difficulty)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${prep?.shots ?: "—"}", style = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, color = NightPalette.strong, fontSize = 26.sp, lineHeight = 28.sp))
                        Text(stringResource(if (prep?.shots == 1) R.string.shot_unit_one else R.string.shot_unit_other), style = LabelStyle)
                    }
                }
                if (prep != null && prep.threeStarShots > 0) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        StarGoal(3, pluralStringResource(R.plurals.prep_star_within, prep.threeStarShots, prep.threeStarShots))
                        StarGoal(2, pluralStringResource(R.plurals.prep_star_within, prep.twoStarShots, prep.twoStarShots))
                        StarGoal(1, stringResource(R.string.prep_star_finish))
                    }
                }
                Text(stringResource(R.string.prep_aim, stringResource(difficulty.aimGuide).lowercase(), stringResource(difficulty.scoreFactor)), style = LabelStyle)
                if ((prep?.bestStars ?: 0) > 0 || (prep?.bestScore ?: 0L) > 0L) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.prep_your_best), style = LabelStyle)
                        StarMeter(prep!!.bestStars)
                        if (prep.bestScore > 0L) Text(stringResource(R.string.prep_best_points, formatPoints(prep.bestScore)), style = LabelStyle.copy(color = NightPalette.text))
                    }
                }
                if (!prep?.instruction.isNullOrBlank()) {
                    Text(stringResource(R.string.prep_how_to_play), style = LabelStyle.copy(color = NightPalette.purple))
                    Text(prep!!.instruction, style = BodyStyle)
                }
            }
        }
        if (!prep?.newTips.isNullOrEmpty()) {
            Surface(shape = RoundedCornerShape(22.dp), color = NightPalette.surface.copy(alpha = .96f), border = androidx.compose.foundation.BorderStroke(1.dp, NightPalette.orange.copy(alpha = .7f))) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(stringResource(R.string.prep_new), style = LabelStyle.copy(color = NightPalette.orange, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold))
                    prep!!.newTips.forEach { TipRow(it) }
                }
            }
        }
        Spacer(Modifier.weight(1f, fill = false))
        PrimaryButton(stringResource(R.string.prep_start), callbacks::onStartLevel)
    }
}

@Composable
private fun StarGoal(stars: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StarMeter(stars, size = 12.dp)
        Text(text, style = BodyStyle.copy(fontSize = 14.sp, lineHeight = 19.sp))
    }
}

@Composable
private fun FreeModePage(model: UiModel, callbacks: UiCallbacks, modifier: Modifier) {
    Column(modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeader(stringResource(R.string.menu_free), callbacks::onBack)
        Text(stringResource(R.string.free_intro), style = BodyStyle)
        DifficultyPicker(model.difficulty, callbacks::onDifficultyChanged)
        if (model.developmentCatalog) {
            Text(stringResource(R.string.free_dev_catalog), style = LabelStyle, color = NightPalette.purple)
        }
        ModeCard(
            title = stringResource(R.string.mode_endless),
            description = stringResource(R.string.mode_endless_desc),
            detail = stringResource(R.string.mode_endless_record, stringResource(model.difficulty.label),
                formatPoints(model.freeModeBestByDifficulty[model.difficulty] ?: model.freeModeBest)),
            glyph = "∞",
            onClick = { callbacks.onStartFreeMode(UiFreeMode.INFINITE) },
        )
        ModeCard(
            title = stringResource(R.string.mode_zen),
            description = stringResource(R.string.mode_zen_desc),
            detail = stringResource(if (model.hasFreeSession && model.freeMode == UiFreeMode.ZEN) R.string.mode_zen_session else R.string.mode_zen_unranked),
            glyph = "☾",
            onClick = { callbacks.onStartFreeMode(UiFreeMode.ZEN) },
        )
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.free_footer), style = LabelStyle)
    }
}

@Composable
private fun ModeCard(title: String, description: String, detail: String, glyph: String, onClick: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().heightIn(min = 116.dp).clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(20.dp), color = NightPalette.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, NightPalette.line),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(54.dp).clip(CircleShape).background(NightPalette.raised), contentAlignment = Alignment.Center) {
                Text(glyph, color = NightPalette.purple, fontSize = 29.sp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = CardTitleStyle)
                Text(description, style = BodyStyle)
                Text(detail, style = LabelStyle.copy(color = NightPalette.accent))
            }
            Text("›", color = NightPalette.muted, fontSize = 28.sp)
        }
    }
}

@Composable
private fun DailyPage(model: UiModel, callbacks: UiCallbacks, modifier: Modifier) {
    val date = remember(model.dailyDate) { runCatching { java.time.LocalDate.parse(model.dailyDate)
        .format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)) }.getOrDefault(model.dailyDate) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(stringResource(R.string.menu_daily), callbacks::onBack)
        Text(date, style = TitleStyle.copy(color = NightPalette.purple))
        Text(stringResource(R.string.daily_intro), style = BodyStyle)
        DifficultyPicker(model.difficulty, callbacks::onDifficultyChanged)
        Surface(shape = RoundedCornerShape(20.dp), color = NightPalette.surface) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.daily_today, model.dailyLevelNumber), style = CardTitleStyle)
                val stars = model.dailyBestStars[model.difficulty] ?: 0
                StarMeter(stars, size = 24.dp)
                Text(if (stars == 0) stringResource(R.string.daily_waiting) else stringResource(R.string.daily_best, formatPoints(model.dailyBestScores[model.difficulty] ?: 0)), style = BodyStyle)
            }
        }
        PrimaryButton(stringResource(if (model.dailyCanResume) R.string.daily_resume else if ((model.dailyBestStars[model.difficulty] ?: 0) > 0) R.string.daily_replay else R.string.daily_play), callbacks::onStartDaily)
        Text(stringResource(R.string.daily_footer), style = LabelStyle)
    }
}

@Composable
private fun ZenSelectionPage(model: UiModel, callbacks: UiCallbacks, modifier: Modifier) {
    var favoritesOnly by remember { mutableStateOf(false) }
    val levels = model.zenLevels.filter { !favoritesOnly || it.favorite }
    LazyColumn(modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { PageHeader(stringResource(R.string.zen_title), callbacks::onBack) }
        item { DifficultyPicker(model.difficulty, callbacks::onDifficultyChanged) }
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SmallAction("‹", stringResource(R.string.cd_previous_orbit)) { callbacks.onOrbitChanged(-1) }
            Text(model.orbit?.name.orEmpty(), style = CardTitleStyle, modifier = Modifier.weight(1f))
            SmallAction("›", stringResource(R.string.cd_next_orbit)) { callbacks.onOrbitChanged(1) }
        } }
        item { SettingRow(stringResource(R.string.zen_favorites), favoritesOnly) { favoritesOnly = it } }
        if (levels.isEmpty()) item { Text(stringResource(R.string.zen_no_favorites), style = BodyStyle) }
        itemsIndexed(levels, key = { _, level -> level.id }) { _, level ->
            val favoriteLabel = stringResource(if (level.favorite) R.string.cd_zen_unfavorite else R.string.cd_zen_favorite, level.number)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(NightPalette.surface), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(role = Role.Button, onClick = { callbacks.onChooseZenLevel(level.id) }).padding(16.dp)) {
                    Text(stringResource(R.string.label_level, level.number), style = CardTitleStyle)
                    Text(level.title, style = LabelStyle)
                }
                Text(if (level.favorite) "★" else "☆", color = NightPalette.orange, fontSize = 27.sp,
                    modifier = Modifier.size(56.dp).clickable(role = Role.Button, onClick = { callbacks.onToggleZenFavorite(level.id) })
                        .semantics { contentDescription = favoriteLabel }.wrapContentHeight())
            }
        }
        item { Text(stringResource(R.string.zen_footer), style = LabelStyle) }
    }
}

@Composable
internal fun TipRow(tip: UiTip) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(NightPalette.raised), contentAlignment = Alignment.Center) {
            val piece = tip.piece
            if (piece != null) Canvas(Modifier.size(34.dp)) {
                drawSatinBubble(Offset(size.width / 2f, size.height / 2f), size.minDimension * .45f, UiBubble(0, 0, piece, UiBubbleColor.BLUE), symbols = true)
            } else Text("◎", color = NightPalette.orange, fontSize = 22.sp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tip.title, style = BodyStyle.copy(color = NightPalette.strong, fontWeight = FontWeight.SemiBold))
            Text(tip.text, style = BodyStyle.copy(fontSize = 15.sp, lineHeight = 21.sp))
        }
    }
}

@Composable
private fun SettingsPage(model: UiModel, callbacks: UiCallbacks, modifier: Modifier) {
    Column(modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        PageHeader(stringResource(R.string.menu_settings), callbacks::onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            UiSetting.entries.forEach { setting ->
                val value = model.settings.firstOrNull { it.setting == setting }
                val enabled = value?.enabled ?: when (setting) {
                    UiSetting.SYMBOLS -> model.symbolsEnabled
                    UiSetting.PRECISE_AIM -> model.preciseAim
                    else -> false
                }
                SettingRow(stringResource(settingLabel(setting)), enabled) { callbacks.onSettingChanged(setting, it) }
            }
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.settings_local_data), style = LabelStyle.copy(color = NightPalette.purple, letterSpacing = 1.sp), modifier = Modifier.padding(start = 4.dp))
            MenuRow(stringResource(R.string.settings_export), stringResource(R.string.settings_export_sub), "↗", callbacks::onExportBackup)
            MenuRow(stringResource(R.string.settings_import), stringResource(R.string.settings_import_sub), "↙", callbacks::onImportBackup)
            MenuRow(stringResource(R.string.settings_diagnostics), stringResource(R.string.settings_diagnostics_sub), "↗", callbacks::onExportDiagnostics)
            Text(stringResource(R.string.settings_copies_hint), style = LabelStyle, modifier = Modifier.padding(start = 4.dp))
            if (model.versionLabel.isNotBlank()) {
                // Seven taps in a row toggle the hidden test mode; the label has no visual affordance.
                var taps by remember { mutableIntStateOf(0) }
                var lastTap by remember { mutableLongStateOf(0L) }
                Text(stringResource(if (model.allLevelsUnlocked) R.string.settings_version_unlocked else R.string.settings_version, model.versionLabel), style = LabelStyle,
                    modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 8.dp).clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                    ) {
                        if (!model.testModeAvailable) return@clickable
                        val now = System.currentTimeMillis()
                        taps = if (now - lastTap < 1_500) taps + 1 else 1
                        lastTap = now
                        if (taps >= 7) { taps = 0; callbacks.onToggleAllLevels() }
                    })
            }
        }
    }
}

private fun settingLabel(setting: UiSetting): Int = when (setting) {
    UiSetting.SOUND -> R.string.setting_sound
    UiSetting.VIBRATION -> R.string.setting_vibration
    UiSetting.SYMBOLS -> R.string.setting_symbols
    UiSetting.PRECISE_AIM -> R.string.setting_precise_aim
    UiSetting.FULL_AIM_GUIDE -> R.string.setting_full_aim_guide
    UiSetting.LEFT_HANDED -> R.string.setting_left_handed
    UiSetting.REDUCED_MOTION -> R.string.setting_reduced_motion
}

@Composable
private fun SettingRow(label: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(NightPalette.surface).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = BodyStyle, modifier = Modifier.weight(1f))
        Switch(checked = enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun StatisticsPage(model: UiModel, callbacks: UiCallbacks, modifier: Modifier) {
    var selected by remember { mutableStateOf(model.difficulty) }
    val stats = model.statisticsByDifficulty[selected] ?: model.statistics
    Column(modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        PageHeader(stringResource(R.string.menu_statistics), callbacks::onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DifficultyPicker(selected, { selected = it }, showSummary = false)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(stringResource(R.string.stats_levels), "${stats.levelsCompleted}" + if (stats.totalLevels > 0) " / ${stats.totalLevels}" else "", Modifier.weight(1f))
                StatTile(stringResource(R.string.stats_stars), "${stats.starsEarned}" + if (stats.totalLevels > 0) " / ${stats.totalLevels * 3}" else "", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(stringResource(R.string.stats_wins), "${stats.wins}", Modifier.weight(1f))
                StatTile(stringResource(R.string.stats_losses), "${stats.losses}", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(stringResource(R.string.stats_shots), "${stats.shots}", Modifier.weight(1f))
                StatTile(stringResource(R.string.stats_bubbles), "${stats.bubblesCleared}", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(stringResource(R.string.stats_campaign_points), formatPoints(stats.campaignScore), Modifier.weight(1f))
                StatTile(stringResource(R.string.stats_endless_record), formatPoints(stats.freeBestScore), Modifier.weight(1f))
            }
            if (model.achievements.isNotEmpty()) {
                val unlocked = model.achievements.count { it.unlocked }
                Text(stringResource(R.string.stats_achievements, unlocked, model.achievements.size), style = LabelStyle.copy(color = NightPalette.purple, letterSpacing = 1.sp), modifier = Modifier.padding(start = 4.dp, top = 10.dp))
                model.achievements.forEach { AchievementRow(it) }
            }
        }
    }
}

@Composable
private fun AchievementRow(achievement: UiAchievement) {
    val description = stringResource(if (achievement.unlocked) R.string.cd_achievement_unlocked else R.string.cd_achievement_locked,
        achievement.title, achievement.description)
    Surface(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
        contentDescription = description
    }, shape = RoundedCornerShape(16.dp), color = NightPalette.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (achievement.unlocked) NightPalette.orange.copy(alpha = .6f) else NightPalette.line)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StarMeter(if (achievement.unlocked) 1 else 0, size = 18.dp, count = 1)
            Column(Modifier.weight(1f)) {
                Text(achievement.title, style = BodyStyle.copy(color = if (achievement.unlocked) NightPalette.strong else NightPalette.muted, fontWeight = FontWeight.SemiBold))
                Text(achievement.description, style = LabelStyle)
            }
        }
    }
}

@Composable
private fun StatTile(title: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = NightPalette.surface, border = androidx.compose.foundation.BorderStroke(1.dp, NightPalette.line)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = LabelStyle)
            Text(value, style = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, color = NightPalette.strong, fontSize = 25.sp, lineHeight = 31.sp))
        }
    }
}

@Composable
private fun CreditsPage(model: UiModel, callbacks: UiCallbacks, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        PageHeader(stringResource(R.string.menu_credits), callbacks::onBack)
        Surface(shape = RoundedCornerShape(22.dp), color = NightPalette.surface, border = androidx.compose.foundation.BorderStroke(1.dp, NightPalette.line)) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.app_name), style = CardTitleStyle)
                Text(stringResource(R.string.credits_description), style = BodyStyle)
                Box(Modifier.fillMaxWidth().height(1.dp).background(NightPalette.line))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Tiago Correia", style = BodyStyle.copy(color = NightPalette.strong, fontWeight = FontWeight.SemiBold))
                    Text("tiagorcorreia87@gmail.com", style = BodyStyle.copy(color = NightPalette.accent))
                }
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, message: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("✧", color = NightPalette.purple, fontSize = 32.sp)
        Text(title, style = CardTitleStyle, textAlign = TextAlign.Center)
        Text(message, style = LabelStyle, textAlign = TextAlign.Center)
    }
}

@Composable
private fun LoadingOverlay(message: String) {
    // Drawn in the same window: a Dialog steals focus and makes short waits flicker.
    Box(Modifier.fillMaxSize().background(NightPalette.background.copy(alpha = .55f)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(22.dp), color = NightPalette.surface, border = androidx.compose.foundation.BorderStroke(1.dp, NightPalette.line)) {
            Row(Modifier.padding(horizontal = 22.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp), color = NightPalette.accent, strokeWidth = 2.dp)
                Text(message, style = BodyStyle)
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, callbacks: UiCallbacks, canRetryLoad: Boolean, canRetrySave: Boolean) {
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
        Surface(Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(16.dp), color = NightPalette.surface, border = androidx.compose.foundation.BorderStroke(1.dp, NightPalette.pink.copy(alpha = .75f))) {
            Column(Modifier.padding(16.dp)) {
                Text(message, style = BodyStyle.copy(color = NightPalette.strong))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (canRetryLoad) TextButtonLine(stringResource(R.string.retry_load), callbacks::onRetryLoad, Modifier.weight(1f))
                    if (canRetrySave) TextButtonLine(stringResource(R.string.retry_save), callbacks::onRetrySave, Modifier.weight(1f))
                    if (!canRetrySave) TextButtonLine(stringResource(R.string.close), callbacks::onDismissError)
                }
            }
        }
    }
}
