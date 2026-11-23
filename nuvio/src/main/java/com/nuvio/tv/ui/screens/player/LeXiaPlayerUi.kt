// LeXia's own (not Nuvio code): the look of the player. Nuvio's PlayerScreen keeps everything it
// does (engine, keys, panels, skip intro, next episode); only its controls, seek bar and pause
// screen are drawn here instead, in LeXia's style. PlayerScreen calls these three in place of
// PlayerControlsOverlay, SeekOverlayHost and PauseOverlay (each call marked "LeXia:").
package com.nuvio.tv.ui.screens.player

import android.text.format.DateFormat
import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Toc
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.LeXiaCinema
import com.nuvio.tv.R
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.ui.screens.party.ConfirmDialog
import com.nuvio.tv.ui.util.rememberRawSvgPainter
import kotlinx.coroutines.delay
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** The film's own colour (LeXia gives it), or LeXia's blue. */
private fun lexiaAccent(): Color =
    LeXiaCinema.accent.takeIf { it != 0 }?.let { Color(it) } ?: Color(0xFF8AB4FF)

private val Ink = Color(0xFF111111)
private val Glass = Color.White.copy(alpha = 0.14f)

/** "1 sa 37 dk", "42 dk", "40 sn". */
internal fun lexiaSpan(ms: Long): String {
    val m = (ms / 60_000L).toInt()
    return when {
        m >= 60 -> if (m % 60 == 0) "${m / 60} sa" else "${m / 60} sa ${m % 60} dk"
        m >= 1 -> "$m dk"
        else -> "${(ms / 1000L).coerceAtLeast(0)} sn"
    }
}

/** What really plays now, read from the decoder: "4K", "HDR10", "Dolby Atmos 5.1"… */
internal fun lexiaQuality(info: PlayerMediaInfo): List<String> = buildList {
    val w = info.videoWidth ?: 0
    val h = info.videoHeight ?: 0
    when {
        w >= 3200 || h >= 2000 -> add("4K")
        w >= 1800 || h >= 1000 -> add("1080p")
        w >= 1200 || h >= 700 -> add("720p")
        h > 0 -> add("${h}p")
    }
    info.hdr.distinct().forEach { add(if (it.equals("DV", true)) "Dolby Vision" else it) }
    val codec = info.audioCodec.orEmpty()
    val profiles = info.audioProfiles.joinToString(" ").lowercase()
    val name = when {
        "atmos" in profiles || "joc" in profiles -> "Dolby Atmos"
        codec.contains("TrueHD", true) -> "Dolby TrueHD"
        codec.contains("E-AC3", true) || codec.contains("EAC3", true) || codec.contains("Dolby Digital Plus", true) -> "Dolby Digital+"
        codec.contains("AC3", true) || codec.contains("AC-3", true) || codec.contains("Dolby Digital", true) -> "Dolby Digital"
        codec.contains("DTS", true) -> if ("ma" in profiles.split(' ', '-')) "DTS-HD MA" else "DTS"
        else -> null
    }
    val channels = when (info.audioChannels) {
        null, 0 -> null
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "${info.audioChannels} kanal"
    }
    val audio = listOfNotNull(name, channels).joinToString(" ")
    if (audio.isNotBlank()) add(audio)
}

private fun lexiaLanguage(code: String?): String? {
    val c = code?.trim()?.lowercase()?.takeIf { it.isNotBlank() && it != "und" } ?: return null
    val tag = when (c) { "tur" -> "tr"; "eng" -> "en"; else -> c }
    val name = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.forLanguageTag("tr-TR"))
    return name.takeIf { it.isNotBlank() && !it.equals(tag, true) }?.replaceFirstChar { it.titlecase(Locale.forLanguageTag("tr-TR")) }
}

@Composable
private fun LeXiaClock(modifier: Modifier = Modifier, style: TextStyle) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay((60_000L - now % 60_000L).coerceAtLeast(1_000L))
        }
    }
    Text(DateFormat.getTimeFormat(context).format(Date(now)), modifier = modifier, style = style, color = Color.White.copy(alpha = 0.85f))
}

@Composable
private fun endTimeText(remainingMs: Long, speed: Float): String {
    val context = LocalContext.current
    val end = System.currentTimeMillis() + (remainingMs / speed.coerceAtLeast(0.25f)).toLong()
    return DateFormat.getTimeFormat(context).format(Date(end))
}

@Composable
private fun LogoOrTitle(logo: String?, title: String, height: Int) {
    var failed by remember(logo) { mutableStateOf(logo.isNullOrBlank()) }
    if (!failed) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(logo).memoryCacheKey(logo).crossfade(true).build(),
            contentDescription = title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart,
            modifier = Modifier.height(height.dp).widthIn(max = 420.dp),
            onError = { failed = true }
        )
    } else {
        Text(
            title,
            style = TextStyle(fontSize = (height * 0.5f).coerceIn(26f, 40f).sp, fontWeight = FontWeight.Bold, color = Color.White),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun QualityChips(labels: List<String>, trailing: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        labels.forEach {
            Text(
                it,
                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, color = Color.White),
                modifier = Modifier
                    .padding(end = 6.dp)
                    .border(1.3.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
        if (!trailing.isNullOrBlank()) {
            Text(
                trailing,
                style = TextStyle(fontSize = 13.sp, color = Color.White.copy(alpha = 0.75f)),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

/** A round glass button; focused it turns white and, with a [label], shows it beside the icon. */
@Composable
private fun LeXiaButton(
    icon: ImageVector? = null,
    painter: Painter? = null,
    text: String? = null,
    label: String,
    big: Boolean = false,
    onClick: () -> Unit,
    showLabel: Boolean = false,
    focusRequester: FocusRequester? = null,
    up: FocusRequester? = null,
    down: FocusRequester? = null,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onFocused: () -> Unit,
    accent: Color
) {
    var focused by remember { mutableStateOf(false) }
    val size = if (big) 50.dp else 42.dp
    val bg by animateColorAsState(if (focused) Color.White else Glass, tween(140), label = "bg")
    val fg = if (focused) Ink else Color.White
    val grow by animateFloatAsState(if (focused) 1.08f else 1f, tween(160), label = "grow")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .scale(grow)
            .then(if (focused && big) Modifier.shadow(18.dp, CircleShape, ambientColor = accent, spotColor = accent) else Modifier)
            .height(size)
            .widthIn(min = size)
            .clip(RoundedCornerShape(size / 2))
            .background(bg)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties {
                up?.let { this.up = it }
                down?.let { this.down = it }
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onPreviewKeyEvent { e ->
                val k = e.nativeKeyEvent
                if (k.action != KeyEvent.ACTION_DOWN) {
                    return@onPreviewKeyEvent k.action == KeyEvent.ACTION_UP && k.keyCode in OK_KEYS
                }
                when (k.keyCode) {
                    in OK_KEYS -> { if (k.repeatCount == 0) onClick(); true }
                    KeyEvent.KEYCODE_DPAD_UP -> when {
                        up != null -> { runCatching { up.requestFocus() }; true }
                        onUp != null -> { onUp(); true }
                        else -> true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> when {
                        down != null -> { runCatching { down.requestFocus() }; true }
                        onDown != null -> { onDown(); true }
                        else -> true
                    }
                    else -> false
                }
            }
            .focusable()
            .padding(horizontal = if (focused && showLabel) 16.dp else 0.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            when {
                painter != null -> Icon(painter, label, tint = fg, modifier = Modifier.size(if (big) 24.dp else 19.dp))
                icon != null -> Icon(icon, label, tint = fg, modifier = Modifier.size(if (big) 26.dp else 20.dp))
                text != null -> Text(text, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = fg))
            }
        }
        if (focused && showLabel) {
            Text(
                label,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = fg),
                maxLines = 1,
                modifier = Modifier.offset(x = (-6).dp)
            )
        }
    }
}

private val OK_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)

/** The thin bar: played part in the film's colour, a glowing dot, the seek bubble above it. */
@Composable
private fun LeXiaBar(
    position: Long,
    duration: Long,
    buffered: Long,
    chapters: List<PlayerChapter>,
    accent: Color,
    focused: Boolean,
    bubble: String?,
    modifier: Modifier = Modifier
) {
    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val buffer = if (duration > 0) (buffered.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shown by animateFloatAsState(progress, tween(100), label = "progress")
    BoxWithConstraints(modifier.fillMaxWidth().height(30.dp)) {
        val w = maxWidth
        val barH = if (focused) 6.dp else 4.dp
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 7.dp)
                .fillMaxWidth()
                .height(barH)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.22f))
                .drawBehind {
                    drawRect(Color.White.copy(alpha = 0.18f), size = Size(size.width * buffer, size.height))
                    drawRect(
                        Brush.horizontalGradient(listOf(accent.copy(alpha = 0.85f), lerp(accent, Color.White, 0.55f))),
                        size = Size(size.width * shown, size.height)
                    )
                    if (duration > 0) chapters.forEach { c ->
                        val f = c.startMs.toFloat() / duration
                        if (f > 0f && f < 1f) drawRect(Color.Black.copy(alpha = 0.55f), Offset(size.width * f - 1f, 0f), Size(2f, size.height))
                    }
                }
        )
        val dot = if (focused) 16.dp else 12.dp
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .offset(x = w * shown - dot / 2, y = -(7.dp + barH / 2) + dot / 2)
                .size(dot)
                .shadow(if (focused) 10.dp else 4.dp, CircleShape, ambientColor = accent, spotColor = accent)
                .background(Color.White, CircleShape)
        )
        if (bubble != null) {
            Text(
                bubble,
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (w * shown - 34.dp).coerceIn(0.dp, w - 68.dp), y = (-14).dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            )
        }
    }
}

private fun lerp(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f
)

private fun seekLabel(deltaMs: Long): String {
    val s = (abs(deltaMs) / 1000L).toInt()
    val text = if (s >= 60) "${s / 60} dk${if (s % 60 != 0) " ${s % 60} sn" else ""}" else "$s sn"
    return (if (deltaMs >= 0) "+" else "−") + text
}

/**
 * The controls, in place of Nuvio's PlayerControlsOverlay (same arguments, same events): clock top
 * left, Ses · Altyazı · Kaynak … top right, logo, real quality, bar and times bottom left, the play
 * buttons under them. Keys: Nuvio's (OK plays/pauses, left/right on the bar seek, down hides).
 */
@Composable
internal fun LeXiaPlayerControls(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    playPauseFocusRequester: FocusRequester,
    progressBarFocusRequester: FocusRequester,
    streamInfoFocusRequester: FocusRequester,
    progressBarUpFocusRequester: FocusRequester? = null,
    onPlayPause: () -> Unit,
    onPlayNextEpisode: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekBackward: () -> Unit,
    onShowChaptersPanel: () -> Unit,
    onShowEpisodesPanel: () -> Unit,
    onShowSourcesPanel: () -> Unit,
    onShowAudioDialog: () -> Unit,
    onShowSubtitleDialog: () -> Unit,
    onShowSpeedDialog: () -> Unit,
    onToggleAspectRatio: () -> Unit,
    onSwitchPlayerEngine: () -> Unit,
    onShowStreamInfo: () -> Unit,
    onResetHideTimer: () -> Unit,
    onHideControls: () -> Unit,
    // Nuvio's other arguments, taken so the call stays Nuvio's; LeXia has no use for them.
    @Suppress("UNUSED_PARAMETER") playerBadges: List<com.nuvio.tv.domain.model.StreamBadge> = emptyList(),
    @Suppress("UNUSED_PARAMETER") onSeekTo: (Long) -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onShowPartyPanel: (() -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") partyActive: Boolean = false,
    @Suppress("UNUSED_PARAMETER") onReportPlaybackIssue: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onToggleMoreActions: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onOpenInExternalPlayer: (() -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") onBack: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") reportCodeVisible: Boolean = false,
    @Suppress("UNUSED_PARAMETER") skipButtonVisible: Boolean = false,
    subtitleReportingActive: Boolean = true,
    onSubtitleClearanceChanged: (Float) -> Unit = {},
    onSubtitleTitleBoundsChanged: (Float, Float) -> Unit = { _, _ -> }
) {
    val accent = remember { lexiaAccent() }
    // Subtitles move up out of the controls' way, as in Nuvio.
    val latestClearance by androidx.compose.runtime.rememberUpdatedState(onSubtitleClearanceChanged)
    val latestBounds by androidx.compose.runtime.rememberUpdatedState(onSubtitleTitleBoundsChanged)
    var clearance by remember { mutableStateOf(0f) }
    var barClearance by remember { mutableStateOf(0f) }
    LaunchedEffect(subtitleReportingActive, clearance) { if (subtitleReportingActive && clearance > 0f) latestClearance(clearance) }
    LaunchedEffect(subtitleReportingActive, barClearance) { if (subtitleReportingActive) latestBounds(barClearance, 0f) }
    val timeline by viewModel.playbackTimeline.collectAsState()
    val live = timeline.isLive
    val topFocus = remember { FocusRequester() }
    var barFocused by remember { mutableStateOf(false) }
    var seekDelta by remember { mutableLongStateOf(0L) }
    var seekShownAt by remember { mutableLongStateOf(0L) }
    var showEngineConfirm by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(uiState.currentStreamUrl, uiState.isBuffering, uiState.internalPlayerEngine, uiState.audioTracks.firstOrNull { it.isSelected }?.index) {
        // The decoder knows the real picture and sound only once playing; ask again a little later.
        repeat(3) {
            if (!uiState.isBuffering) quality = lexiaQuality(viewModel.playerMediaInfo())
            delay(1500)
        }
    }
    LaunchedEffect(seekShownAt) {
        if (seekShownAt != 0L) {
            delay(1200)
            seekDelta = 0L
            seekShownAt = 0L
        }
    }
    val subtitle = uiState.subtitleTracks.firstOrNull { it.isSelected }?.let { lexiaLanguage(it.language) ?: it.name.takeIf { n -> n.isNotBlank() } }
        ?: uiState.selectedAddonSubtitle?.let { lexiaLanguage(it.lang) ?: it.lang }
    val chips = quality + listOfNotNull(subtitle?.let { "$it altyazı" })
    val source = uiState.currentStreamName?.lineSequence()?.firstOrNull()?.trim()
        ?.replace(Regex("""(?i)\s*[-–|·]\s*(\d{3,4}p|4k|2160p).*$"""), "")?.takeIf { it.isNotBlank() }

    val play = rememberRawSvgPainter(R.raw.ic_player_play)
    val pause = rememberRawSvgPainter(R.raw.ic_player_pause)
    val subs = rememberRawSvgPainter(R.raw.ic_player_subtitles)
    val audio = rememberRawSvgPainter(R.raw.ic_player_audio_filled)
    val sourceIcon = rememberRawSvgPainter(R.raw.ic_player_source)
    val episodesIcon = rememberRawSvgPainter(R.raw.ic_player_episodes)
    val aspect = rememberRawSvgPainter(R.raw.ic_player_aspect_ratio)

    val barTarget = if (live) playPauseFocusRequester else progressBarFocusRequester
    // From the bar, up goes to skip intro / next episode when Nuvio shows them, else to the top row.
    val barUp = progressBarUpFocusRequester ?: topFocus

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
        )
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.62f)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.55f to Color(0xFF080605).copy(alpha = 0.55f),
                        1f to Color(0xFF080605).copy(alpha = 0.92f)
                    )
                )
        )

        LeXiaClock(Modifier.align(Alignment.TopStart).padding(start = 56.dp, top = 30.dp), TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium))

        // Top right: what you change while watching.
        Row(
            Modifier.align(Alignment.TopEnd).padding(end = 34.dp, top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val common: @Composable (ImageVector?, Painter?, String?, String, () -> Unit, FocusRequester?) -> Unit =
                { icon, painter, text, label, click, requester ->
                    LeXiaButton(
                        icon = icon, painter = painter, text = text, label = label, onClick = click, showLabel = true,
                        focusRequester = requester, down = barTarget, onFocused = onResetHideTimer, accent = accent
                    )
                }
            if (uiState.audioTracks.isNotEmpty()) common(null, audio, null, "Ses", onShowAudioDialog, null)
            if (uiState.subtitleTracks.isNotEmpty() || uiState.addonSubtitles.isNotEmpty()) common(null, subs, null, "Altyazı", onShowSubtitleDialog, null)
            common(null, sourceIcon, null, "Kaynak", onShowSourcesPanel, topFocus)
            if (uiState.currentSeason != null && uiState.currentEpisode != null) common(Icons.AutoMirrored.Filled.List, episodesIcon, null, "Bölümler", onShowEpisodesPanel, null)
            if (uiState.chapters.isNotEmpty() && !live) common(Icons.AutoMirrored.Filled.Toc, null, null, "Sahneler", onShowChaptersPanel, null)
            common(Icons.Default.Speed, null, if (uiState.playbackSpeed != 1f) formatSpeed(uiState.playbackSpeed) else null, "Hız", onShowSpeedDialog, null)
            common(Icons.Default.AspectRatio, aspect, null, "Görüntü", onToggleAspectRatio, null)
            LeXiaButton(
                icon = Icons.Default.Info, label = "Bilgi", onClick = onShowStreamInfo, showLabel = true,
                focusRequester = streamInfoFocusRequester, down = barTarget, onFocused = onResetHideTimer, accent = accent
            )
            common(Icons.Default.Sync, null, null, if (uiState.internalPlayerEngine == InternalPlayerEngine.MVP_PLAYER) "ExoPlayer'a geç" else "MPV'ye geç", { showEngineConfirm = true }, null)
        }

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .onGloballyPositioned { c ->
                    val root = c.findRootCoordinates().size.height
                    if (root > 0) clearance = (root - c.boundsInRoot().top).coerceAtLeast(0f)
                }
                .padding(start = 56.dp, end = 56.dp, bottom = 26.dp)
        ) {
            AnimatedVisibility(uiState.activeSkipInterval == null, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
                Column {
                    val series = uiState.currentSeason != null && uiState.currentEpisode != null
                    LogoOrTitle(uiState.logo, if (series) uiState.contentName ?: uiState.title else uiState.title, if (series) 52 else 62)
                    if (series) {
                        val name = uiState.currentEpisodeTitle?.takeIf { it.isNotBlank() }
                        Text(
                            "${uiState.currentSeason}. Sezon ${uiState.currentEpisode}. Bölüm" + (name?.let { " · $it" } ?: ""),
                            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.9f)),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    QualityChips(chips, source)
                }
            }
            Spacer(Modifier.height(if (live) 18.dp else 8.dp))

            if (!live) {
                Box(
                    Modifier
                        .onGloballyPositioned { c ->
                            val root = c.findRootCoordinates().size.height
                            if (root > 0) barClearance = (root - c.boundsInRoot().top).coerceAtLeast(0f)
                        }
                        .focusRequester(progressBarFocusRequester)
                        .focusProperties { up = barUp; down = playPauseFocusRequester }
                        .onFocusChanged {
                            barFocused = it.isFocused
                            if (it.isFocused) onResetHideTimer()
                        }
                        .onPreviewKeyEvent { e ->
                            val k = e.nativeKeyEvent
                            if (k.action == KeyEvent.ACTION_UP) {
                                return@onPreviewKeyEvent when (k.keyCode) {
                                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                        viewModel.onEvent(PlayerEvent.OnCommitPreviewSeek); true
                                    }
                                    in OK_KEYS -> true
                                    else -> false
                                }
                            }
                            if (k.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                            when (k.keyCode) {
                                in OK_KEYS -> { if (k.repeatCount == 0) onPlayPause(); true }
                                KeyEvent.KEYCODE_DPAD_UP -> { runCatching { barUp.requestFocus() }; true }
                                KeyEvent.KEYCODE_DPAD_DOWN -> { runCatching { playPauseFocusRequester.requestFocus() }; true }
                                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    val forward = k.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                                    val d = PlayerScrubRates.deltaMsForKeyRepeat(repeatCount = k.repeatCount, forward = forward)
                                    viewModel.onEvent(PlayerEvent.OnPreviewSeekBy(d))
                                    seekDelta += d
                                    seekShownAt = System.currentTimeMillis()
                                    true
                                }
                                else -> false
                            }
                        }
                        .focusable()
                ) {
                    LeXiaBar(
                        timeline.currentPosition, timeline.duration, timeline.bufferedPosition, uiState.chapters,
                        accent, barFocused, seekDelta.takeIf { it != 0L }?.let(::seekLabel)
                    )
                }
                if (uiState.showChapterTitle) {
                    val i = PlayerChapters.indexAt(uiState.chapters, timeline.currentPosition)
                    if (i >= 0) Text(
                        uiState.chapters[i].title ?: "${i + 1}. sahne",
                        style = TextStyle(fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f)),
                        maxLines = 1
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(timeline.currentPosition), style = TextStyle(fontSize = 14.sp, color = Color.White.copy(alpha = 0.88f)))
                    if (timeline.duration > 0) {
                        val left = (timeline.duration - timeline.currentPosition).coerceAtLeast(0)
                        Text(
                            "${lexiaSpan(left)} kaldı · bitiş ${endTimeText(left, uiState.playbackSpeed)}",
                            style = TextStyle(fontSize = 14.sp, color = lerp(accent, Color.White, 0.6f))
                        )
                    }
                }
            } else {
                Text("Canlı · ${lexiaSpan(timeline.watchedDurationMs)} izlendi", style = TextStyle(fontSize = 14.sp, color = Color.White.copy(alpha = 0.88f)))
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val up = if (live) (progressBarUpFocusRequester ?: topFocus) else progressBarFocusRequester
                if (!live) LeXiaButton(icon = Icons.Default.Replay10, label = "10 sn geri", onClick = onSeekBackward, up = up, onDown = onHideControls, onFocused = onResetHideTimer, accent = accent)
                LeXiaButton(
                    painter = if (uiState.isPlaying) pause else play,
                    icon = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    label = if (uiState.isPlaying) "Durdur" else "Oynat",
                    big = true,
                    onClick = onPlayPause,
                    focusRequester = playPauseFocusRequester,
                    up = up,
                    onDown = onHideControls,
                    onFocused = onResetHideTimer,
                    accent = accent
                )
                if (!live) LeXiaButton(icon = Icons.Default.Forward10, label = "10 sn ileri", onClick = onSeekForward, up = up, onDown = onHideControls, onFocused = onResetHideTimer, accent = accent)
                val showNext = uiState.nextEpisode?.hasAired == true &&
                    (uiState.postPlayMode as? PostPlayMode.AutoPlay)?.let { !it.searching && it.countdownSec == null } != false
                if (showNext) LeXiaButton(icon = Icons.Default.SkipNext, label = "Sonraki bölüm", showLabel = true, onClick = onPlayNextEpisode, up = up, onDown = onHideControls, onFocused = onResetHideTimer, accent = accent)
            }
        }
    }

    if (showEngineConfirm) {
        val toMpv = uiState.internalPlayerEngine != InternalPlayerEngine.MVP_PLAYER
        ConfirmDialog(
            title = if (toMpv) "MPV ile oynatılsın mı?" else "ExoPlayer ile oynatılsın mı?",
            message = "Görüntü ya da ses sorunluysa diğer oynatma motoru deneyebilir. Film kaldığı yerden sürer.",
            confirmLabel = "Geç",
            onConfirm = {
                showEngineConfirm = false
                onSwitchPlayerEngine()
            },
            onDismiss = { showEngineConfirm = false }
        )
    }
}

private fun formatSpeed(rate: Float): String =
    if (rate % 1f == 0f) "${rate.toInt()}×" else "${"%.2f".format(Locale.US, rate).trimEnd('0').trimEnd('.')}×"

/** While seeking with the controls hidden: just the bar, the times and the bubble. */
@Composable
internal fun LeXiaSeekOverlay(viewModel: PlayerViewModel, chapters: List<PlayerChapter>, playbackSpeed: Float) {
    val accent = remember { lexiaAccent() }
    val timeline by viewModel.playbackTimeline.collectAsState()
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
            .padding(start = 56.dp, end = 56.dp, top = 40.dp, bottom = 28.dp)
    ) {
        Column {
            LeXiaBar(timeline.currentPosition, timeline.duration, timeline.bufferedPosition, chapters, accent, true, null)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(timeline.currentPosition), style = TextStyle(fontSize = 14.sp, color = Color.White))
                if (timeline.duration > 0) {
                    val left = (timeline.duration - timeline.currentPosition).coerceAtLeast(0)
                    Text("${lexiaSpan(left)} kaldı · bitiş ${endTimeText(left, playbackSpeed)}", style = TextStyle(fontSize = 14.sp, color = lerp(accent, Color.White, 0.6f)))
                }
            }
        }
    }
}

/**
 * Paused for a while (Nuvio decides when): the film's screen, Apple TV style. Logo, what it is about,
 * the cast with their faces, and where you are. Any key goes back to the film (Nuvio handles keys).
 */
@Composable
internal fun LeXiaPauseOverlay(
    visible: Boolean,
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val accent = remember { lexiaAccent() }
    AnimatedVisibility(visible, enter = fadeIn(tween(450)), exit = fadeOut(tween(220)), modifier = modifier) {
        val timeline by viewModel.playbackTimeline.collectAsState()
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color(0xFF060504).copy(alpha = 0.93f),
                        0.5f to Color(0xFF060504).copy(alpha = 0.75f),
                        1f to Color(0xFF060504).copy(alpha = 0.25f)
                    )
                )
        ) {
            if (!uiState.isLive) LeXiaClock(Modifier.align(Alignment.TopStart).padding(start = 56.dp, top = 30.dp), TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium))
            val tag = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.7.sp, color = lerp(accent, Color.White, 0.45f))
            Column(Modifier.padding(start = 56.dp, top = 64.dp).width(430.dp)) {
                val series = uiState.currentSeason != null && uiState.currentEpisode != null
                LogoOrTitle(uiState.logo, if (series) uiState.contentName ?: uiState.title else uiState.title, 72)
                Spacer(Modifier.height(16.dp))
                Text("DURAKLATILDI", style = tag)
                if (series) {
                    Text(
                        "${uiState.currentSeason}. Sezon ${uiState.currentEpisode}. Bölüm" +
                            (uiState.currentEpisodeTitle?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                        style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                (LeXiaCinema.description.takeIf { it.isNotBlank() && LeXiaCinema.descriptionOf == (if (uiState.currentSeason != null && uiState.currentEpisode != null) "${uiState.currentSeason}:${uiState.currentEpisode}" else "") } ?: uiState.description)?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = TextStyle(fontSize = 15.sp, lineHeight = 23.sp, color = Color.White.copy(alpha = 0.88f)),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                val facts = listOfNotNull(uiState.releaseYear?.takeIf { it.isNotBlank() }, timeline.duration.takeIf { it > 0 }?.let(::lexiaSpan))
                if (facts.isNotEmpty()) {
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        facts.forEach {
                            Text(
                                it,
                                style = TextStyle(fontSize = 12.sp, color = Color.White),
                                modifier = Modifier.background(Color.White.copy(alpha = 0.13f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
                if (uiState.castMembers.isNotEmpty()) {
                    Text("OYUNCULAR", style = tag, modifier = Modifier.padding(top = 22.dp, bottom = 10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        uiState.castMembers.take(5).forEach { CastFace(it, accent) }
                    }
                }
            }
            if (!uiState.isLive && timeline.duration > 0) {
                val left = (timeline.duration - timeline.currentPosition).coerceAtLeast(0)
                Column(Modifier.align(Alignment.BottomEnd).padding(end = 56.dp, bottom = 40.dp), horizontalAlignment = Alignment.End) {
                    Text(formatTime(timeline.currentPosition), style = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Light, color = Color.White))
                    Text("${lexiaSpan(left)} kaldı · bitiş ${endTimeText(left, uiState.playbackSpeed)}", style = TextStyle(fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f)))
                    Text("OK ile devam", style = TextStyle(fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f)), modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
    }
}

@Composable
private fun CastFace(member: MetaCastMember, accent: Color) {
    Column(Modifier.width(66.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(lerp(accent, Color.Black, 0.55f), Color(0xFF2D211B))))
                .border(1.5.dp, Color.White.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            val initials = member.name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase(Locale.forLanguageTag("tr-TR"))
            Text(initials, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White))
            if (!member.photo.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(member.photo).crossfade(true).build(),
                    contentDescription = member.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Text(
            member.name,
            style = TextStyle(fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f)),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
