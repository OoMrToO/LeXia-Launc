// LeXia's own (not Nuvio code): the look of the player. Nuvio's PlayerScreen keeps everything it
// does (engine, keys, panels, skip intro, next episode); only its controls, seek bar and pause
// screen are drawn here instead, in LeXia's style. PlayerScreen calls these three in place of
// PlayerControlsOverlay, SeekOverlayHost and PauseOverlay (each call marked "LeXia:").
package com.nuvio.tv.ui.screens.player

import android.text.format.DateFormat
import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.text.style.TextAlign
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
private val Glass = Color.White.copy(alpha = 0.12f)
private val GlassBorder = Color.White.copy(alpha = 0.08f)
private val GlassHi = Color.White.copy(alpha = 0.20f)
private val Dim = Color.White.copy(alpha = 0.52f)
private val Dim2 = Color.White.copy(alpha = 0.32f)

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
    Text(DateFormat.getTimeFormat(context).format(Date(now)), modifier = modifier, style = style, color = Dim)
}

@Composable
private fun endTimeText(remainingMs: Long, speed: Float): String {
    val context = LocalContext.current
    val end = System.currentTimeMillis() + (remainingMs / speed.coerceAtLeast(0.25f)).toLong()
    return DateFormat.getTimeFormat(context).format(Date(end))
}

@Composable
private fun LogoOrTitle(logo: String?, title: String, height: Int, alignment: Alignment = Alignment.BottomStart) {
    var failed by remember(logo) { mutableStateOf(logo.isNullOrBlank()) }
    if (!failed) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(logo).memoryCacheKey(logo).crossfade(true).build(),
            contentDescription = title,
            contentScale = ContentScale.Fit,
            alignment = alignment,
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

/** A quality/audio badge. First one uses accent tint, rest are neutral glass. */
@Composable
private fun Badge(label: String, isAccent: Boolean = false) {
    val bg = if (isAccent) lexiaAccent().copy(alpha = 0.18f) else Color.White.copy(alpha = 0.10f)
    val border = if (isAccent) lexiaAccent().copy(alpha = 0.25f) else GlassBorder
    val fg = if (isAccent) lerp(lexiaAccent(), Color.White, 0.5f) else Color.White.copy(alpha = 0.70f)
    Text(
        label.uppercase(),
        style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp, color = fg),
        modifier = Modifier
            .background(bg, RoundedCornerShape(4.dp))
            .border(1.dp, border, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/**
 * The top floor's buttons: round glass icons; the selected one turns white (dark icon) and opens
 * to show its name. A changed speed ("1,5×") shows even while closed.
 */
@Composable
private fun PillButton(
    icon: ImageVector? = null,
    painter: Painter? = null,
    text: String? = null,
    label: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    up: FocusRequester? = null,
    down: FocusRequester? = null,
    onFocused: () -> Unit,
    accent: Color
) {
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(if (focused) Color.White else Glass, tween(140), label = "pbg")
    val borderColor by animateColorAsState(if (focused) Color.White else GlassBorder, tween(140), label = "pbd")
    val fg = if (focused) Color(0xFF0B0B0D) else Color.White.copy(alpha = 0.85f)
    val grow by animateFloatAsState(if (focused) 1.08f else 1f, tween(160), label = "pgrow")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .scale(grow)
            .animateContentSize(tween(180))
            .height(34.dp)
            .widthIn(min = 34.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(20.dp))
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
                        else -> true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> when {
                        down != null -> { runCatching { down.requestFocus() }; true }
                        else -> true
                    }
                    else -> false
                }
            }
            .focusable()
            .padding(horizontal = if (focused) 14.dp else 9.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
    ) {
        when {
            painter != null -> Icon(painter, label, tint = fg, modifier = Modifier.size(16.dp))
            icon != null -> Icon(icon, label, tint = fg, modifier = Modifier.size(16.dp))
        }
        if ((focused && (text != null || label.isNotBlank())) || (!focused && text != null)) {
            Text(
                if (focused && text != null) "$label · $text" else text ?: label,
                style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.2.sp, color = fg),
                maxLines = 1
            )
        }
    }
}

/** Big glass circle play/pause button for the transport row. */
@Composable
private fun TransportPlayButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    up: FocusRequester? = null,
    onDown: (() -> Unit)? = null,
    onFocused: () -> Unit,
    accent: Color,
    play: Painter,
    pause: Painter
) {
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(if (focused) Color.White else Glass, tween(140), label = "tpbg")
    val borderColor by animateColorAsState(if (focused) Color.White else Color.White.copy(alpha = 0.18f), tween(140), label = "tpbd")
    val grow by animateFloatAsState(if (focused) 1.08f else 1f, tween(160), label = "tpgrow")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .scale(grow)
            .size(56.dp)
            .then(if (focused) Modifier.shadow(18.dp, CircleShape, ambientColor = accent, spotColor = accent) else Modifier)
            .clip(CircleShape)
            .background(bg)
            .border(1.5.dp, borderColor, CircleShape)
            .focusRequester(focusRequester)
            .focusProperties { up?.let { this.up = it } }
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
                    KeyEvent.KEYCODE_DPAD_UP -> { up?.let { runCatching { it.requestFocus() } }; true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { onDown?.invoke(); true }
                    else -> false
                }
            }
            .focusable()
    ) {
        Icon(if (isPlaying) pause else play, if (isPlaying) "Durdur" else "Oynat", tint = if (focused) Color(0xFF0B0B0D) else Color.White, modifier = Modifier.size(24.dp))
    }
}

/** Small transport button (10s back/forward, next episode). */
@Composable
private fun TransportButton(
    icon: ImageVector? = null,
    painter: Painter? = null,
    label: String,
    showLabel: Boolean = false,
    onClick: () -> Unit,
    up: FocusRequester? = null,
    onDown: (() -> Unit)? = null,
    onFocused: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val fg by animateColorAsState(if (focused) Color(0xFF0B0B0D) else Color.White.copy(alpha = 0.85f), tween(140), label = "tbfg")
    val plate by animateColorAsState(if (focused) Color.White else Color.Transparent, tween(140), label = "tbbg")
    val grow by animateFloatAsState(if (focused) 1.08f else 1f, tween(160), label = "tbgrow")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .scale(grow)
            .clip(RoundedCornerShape(24.dp))
            .background(plate)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .focusProperties { up?.let { this.up = it } }
            .onPreviewKeyEvent { e ->
                val k = e.nativeKeyEvent
                if (k.action != KeyEvent.ACTION_DOWN) {
                    return@onPreviewKeyEvent k.action == KeyEvent.ACTION_UP && k.keyCode in OK_KEYS
                }
                when (k.keyCode) {
                    in OK_KEYS -> { if (k.repeatCount == 0) onClick(); true }
                    KeyEvent.KEYCODE_DPAD_UP -> { up?.let { runCatching { it.requestFocus() } }; true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { onDown?.invoke(); true }
                    else -> false
                }
            }
            .focusable()
    ) {
        when {
            painter != null -> Icon(painter, label, tint = fg, modifier = Modifier.size(28.dp))
            icon != null -> Icon(icon, label, tint = fg, modifier = Modifier.size(28.dp))
        }
        if (showLabel) {
            Text(label, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal, color = if (focused) Color(0xFF0B0B0D) else Dim), maxLines = 1)
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
        val barH = if (focused) 5.dp else 3.dp
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 7.dp)
                .fillMaxWidth()
                .height(barH)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.12f))
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
        // The knob shows only while skipping, as the time bubble does.
        val dot = 14.dp
        val knob by animateFloatAsState(if (bubble != null) 1f else 0f, tween(220), label = "knob")
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .alpha(knob)
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

/** Thin accent mini-bar for the pause overlay bottom. */
@Composable
private fun MiniBar(progress: Float, accent: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(Color.White.copy(alpha = 0.06f))
            .drawBehind {
                drawRect(
                    Brush.horizontalGradient(listOf(accent.copy(alpha = 0.85f), lerp(accent, Color.White, 0.55f))),
                    size = Size(size.width * progress.coerceIn(0f, 1f), size.height)
                )
            }
    )
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
 * Apple TV + Netflix style controls. Film title top-left, pill buttons + clock top-right.
 * Info row (name, metadata, badges) and progress bar at the bottom, centered transport below.
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
    val latestClearance by androidx.compose.runtime.rememberUpdatedState(onSubtitleClearanceChanged)
    val latestBounds by androidx.compose.runtime.rememberUpdatedState(onSubtitleTitleBoundsChanged)
    var clearance by remember { mutableStateOf(0f) }
    var barClearance by remember { mutableStateOf(0f) }
    LaunchedEffect(subtitleReportingActive, clearance) { if (subtitleReportingActive && clearance > 0f) latestClearance(clearance) }
    LaunchedEffect(subtitleReportingActive, barClearance) { if (subtitleReportingActive) latestBounds(barClearance, 0f) }
    val timeline by viewModel.playbackTimeline.collectAsState()
    val live = timeline.isLive
    val topFocus = remember { FocusRequester() }
    var topHasFocus by remember { mutableStateOf(false) }
    var barFocused by remember { mutableStateOf(false) }
    var seekDelta by remember { mutableLongStateOf(0L) }
    var seekShownAt by remember { mutableLongStateOf(0L) }
    var showEngineConfirm by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(uiState.currentStreamUrl, uiState.isBuffering, uiState.internalPlayerEngine, uiState.audioTracks.firstOrNull { it.isSelected }?.index) {
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
    val barUp = progressBarUpFocusRequester ?: topFocus

    Box(Modifier.fillMaxSize()) {
        // Gradient veils (Apple TV style)
        Box(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .align(Alignment.TopCenter)
                .graphicsLayer { alpha = if (topHasFocus) 1f else 0.45f }
                .background(Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.72f),
                    0.5f to Color.Black.copy(alpha = 0.35f),
                    1f to Color.Transparent
                ))
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(200.dp)
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.55f to Color.Black.copy(alpha = 0.42f),
                    1f to Color.Black.copy(alpha = 0.78f)
                ))
        )

        // The upper floor: the options (sound, subtitles, source …) stay out of sight until ▲ from
        // the bar takes the focus there; they slide down softly, and go when the focus leaves.
        // The clock stays.
        val topShown by animateFloatAsState(if (topHasFocus) 1f else 0f, tween(if (topHasFocus) 320 else 220), label = "top")
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(start = 36.dp, end = 34.dp, top = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                uiState.title.uppercase(),
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.9.sp, color = Color.White.copy(alpha = 0.92f)),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).widthIn(max = 400.dp).alpha(topShown)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
              Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .onFocusChanged { topHasFocus = it.hasFocus }
                    .graphicsLayer {
                        alpha = topShown
                        translationY = (1f - topShown) * -18.dp.toPx()
                    }
              ) {
                if (uiState.audioTracks.isNotEmpty()) PillButton(painter = audio, label = "Ses", onClick = onShowAudioDialog, down = barTarget, onFocused = onResetHideTimer, accent = accent)
                if (uiState.subtitleTracks.isNotEmpty() || uiState.addonSubtitles.isNotEmpty()) PillButton(painter = subs, label = "Altyazı", onClick = onShowSubtitleDialog, down = barTarget, onFocused = onResetHideTimer, accent = accent)
                PillButton(painter = sourceIcon, label = "Kaynak", onClick = onShowSourcesPanel, focusRequester = topFocus, down = barTarget, onFocused = onResetHideTimer, accent = accent)
                if (uiState.currentSeason != null && uiState.currentEpisode != null) PillButton(painter = episodesIcon, label = "Bölümler", onClick = onShowEpisodesPanel, down = barTarget, onFocused = onResetHideTimer, accent = accent)
                if (uiState.chapters.isNotEmpty() && !live) PillButton(icon = Icons.AutoMirrored.Filled.Toc, label = "Sahneler", onClick = onShowChaptersPanel, down = barTarget, onFocused = onResetHideTimer, accent = accent)
                PillButton(icon = Icons.Default.Speed, text = if (uiState.playbackSpeed != 1f) formatSpeed(uiState.playbackSpeed) else null, label = "Hız", onClick = onShowSpeedDialog, down = barTarget, onFocused = onResetHideTimer, accent = accent)
                PillButton(painter = aspect, label = "Görüntü", onClick = onToggleAspectRatio, down = barTarget, onFocused = onResetHideTimer, accent = accent)
                PillButton(icon = Icons.Default.Info, label = "Bilgi", onClick = onShowStreamInfo, focusRequester = streamInfoFocusRequester, down = barTarget, onFocused = onResetHideTimer, accent = accent)
                PillButton(icon = Icons.Default.Sync, label = if (uiState.internalPlayerEngine == InternalPlayerEngine.MVP_PLAYER) "ExoPlayer" else "MPV", onClick = { showEngineConfirm = true }, down = barTarget, onFocused = onResetHideTimer, accent = accent)
              }
                LeXiaClock(style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Light, letterSpacing = 0.3.sp))
            }
        }

        // Bottom section
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .onGloballyPositioned { c ->
                    val root = c.findRootCoordinates().size.height
                    if (root > 0) clearance = (root - c.boundsInRoot().top).coerceAtLeast(0f)
                }
                .padding(start = 36.dp, end = 36.dp, bottom = 22.dp)
        ) {
            // Info row: film name + metadata left, quality badges right
            AnimatedVisibility(uiState.activeSkipInterval == null, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(Modifier.weight(1f, fill = false)) {
                        val series = uiState.currentSeason != null && uiState.currentEpisode != null
                        Text(
                            if (series) uiState.contentName ?: uiState.title else uiState.title,
                            style = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Light, color = Color.White, letterSpacing = 0.2.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val meta = buildString {
                            if (series) {
                                append("${uiState.currentSeason}. Sezon ${uiState.currentEpisode}. Bölüm")
                                uiState.currentEpisodeTitle?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                            } else {
                                uiState.releaseYear?.takeIf { it.isNotBlank() }?.let { append(it) }
                                source?.let { if (isNotBlank()) append(" · "); append(it) }
                            }
                        }
                        if (meta.isNotBlank()) {
                            Text(
                                meta,
                                style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Normal, color = Dim, letterSpacing = 0.2.sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                    val badgeLabels = quality + listOfNotNull(subtitle?.let { it })
                    if (badgeLabels.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            badgeLabels.forEachIndexed { i, label -> Badge(label, isAccent = i == 0) }
                        }
                    }
                }
            }

            // "︿": the options are above.
            val chevron by animateFloatAsState(if (topHasFocus) 0f else 0.7f, tween(200), label = "chev")
            Box(Modifier.fillMaxWidth().padding(bottom = 4.dp).alpha(chevron), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(width = 20.dp, height = 10.dp).drawBehind {
                        val p = androidx.compose.ui.graphics.Path().apply {
                            moveTo(2.dp.toPx(), size.height - 2.dp.toPx())
                            lineTo(size.width / 2f, 2.dp.toPx())
                            lineTo(size.width - 2.dp.toPx(), size.height - 2.dp.toPx())
                        }
                        drawPath(
                            p, Color.White,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = 1.6.dp.toPx(),
                                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                join = androidx.compose.ui.graphics.StrokeJoin.Round
                            )
                        )
                    }
                )
            }

            // Progress bar
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
                    Text(formatTime(timeline.currentPosition), style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Light, color = Dim))
                    if (timeline.duration > 0) {
                        val left = (timeline.duration - timeline.currentPosition).coerceAtLeast(0)
                        Text(
                            "${lexiaSpan(left)} kaldı · bitiş ${endTimeText(left, uiState.playbackSpeed)}",
                            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Light, color = lerp(accent, Color.White, 0.6f))
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(18.dp))
                Text("Canlı · ${lexiaSpan(timeline.watchedDurationMs)} izlendi", style = TextStyle(fontSize = 14.sp, color = Color.White.copy(alpha = 0.88f)))
            }

            // Centered transport controls
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val tUp = if (live) (progressBarUpFocusRequester ?: topFocus) else progressBarFocusRequester
                if (!live) TransportButton(icon = Icons.Default.Replay10, label = "10 sn geri", onClick = onSeekBackward, up = tUp, onDown = onHideControls, onFocused = onResetHideTimer)
                TransportPlayButton(
                    isPlaying = uiState.isPlaying,
                    onClick = onPlayPause,
                    focusRequester = playPauseFocusRequester,
                    up = tUp,
                    onDown = onHideControls,
                    onFocused = onResetHideTimer,
                    accent = accent,
                    play = play,
                    pause = pause
                )
                if (!live) TransportButton(icon = Icons.Default.Forward10, label = "10 sn ileri", onClick = onSeekForward, up = tUp, onDown = onHideControls, onFocused = onResetHideTimer)
                val showNext = uiState.nextEpisode?.hasAired == true &&
                    (uiState.postPlayMode as? PostPlayMode.AutoPlay)?.let { !it.searching && it.countdownSec == null } != false
                if (showNext) TransportButton(icon = Icons.Default.SkipNext, label = "Sonraki", showLabel = true, onClick = onPlayNextEpisode, up = tUp, onDown = onHideControls, onFocused = onResetHideTimer)
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

/** While seeking with the controls hidden: radial glow + bar at the bottom. */
@Composable
internal fun LeXiaSeekOverlay(viewModel: PlayerViewModel, chapters: List<PlayerChapter>, playbackSpeed: Float) {
    val accent = remember { lexiaAccent() }
    val timeline by viewModel.playbackTimeline.collectAsState()
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
            .padding(start = 36.dp, end = 36.dp, top = 40.dp, bottom = 28.dp)
    ) {
        Column {
            LeXiaBar(timeline.currentPosition, timeline.duration, timeline.bufferedPosition, chapters, accent, true, null)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(timeline.currentPosition), style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Light, color = Color.White))
                if (timeline.duration > 0) {
                    val left = (timeline.duration - timeline.currentPosition).coerceAtLeast(0)
                    Text("${lexiaSpan(left)} kaldı · bitiş ${endTimeText(left, playbackSpeed)}", style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Light, color = lerp(accent, Color.White, 0.6f)))
                }
            }
        }
    }
}

/**
 * Paused: the film's logo (its name when it has none) alone in the middle of the darkened picture,
 * as on the home screen; for a series, which episode under it. No story, no controls, no times:
 * the remote brings the controls back.
 */
@Composable
internal fun LeXiaPauseOverlay(
    visible: Boolean,
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(visible, enter = fadeIn(tween(600)), exit = fadeOut(tween(220)), modifier = modifier) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                val series = uiState.currentSeason != null && uiState.currentEpisode != null
                val filmTitle = if (series) uiState.contentName ?: uiState.title else uiState.title
                PauseLogo(uiState.logo, filmTitle)
                if (series) {
                    Text(
                        "${uiState.currentSeason}. Sezon ${uiState.currentEpisode}. Bölüm" +
                            (uiState.currentEpisodeTitle?.takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: ""),
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Dim),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
                Text(
                    "OK: devam et",
                    style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Dim2, letterSpacing = 0.6.sp),
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        }
    }
}

/** The pause screen's logo: large and centred; the name in large light letters when there is no logo. */
@Composable
private fun PauseLogo(logo: String?, title: String) {
    var failed by remember(logo) { mutableStateOf(logo.isNullOrBlank()) }
    if (!failed) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(logo).memoryCacheKey(logo).crossfade(true).build(),
            contentDescription = title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.Center,
            modifier = Modifier.height(150.dp).widthIn(max = 560.dp),
            onError = { failed = true }
        )
    } else {
        Text(
            title,
            style = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Light, color = Color.White, letterSpacing = 0.6.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
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
            textAlign = TextAlign.Center
        )
    }
}
