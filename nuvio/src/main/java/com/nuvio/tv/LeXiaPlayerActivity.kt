// LeXia stand-in for a part of NuvioTV that the copied files refer to but LeXia does not
// have (Nuvio's navigation, Hilt). Not Nuvio code: keep it minimal.
package com.nuvio.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.data.repository.LeXiaProgress
import com.nuvio.tv.ui.screens.player.PlayerScreen
import com.nuvio.tv.ui.screens.player.PlayerViewModel
import com.nuvio.tv.ui.theme.NuvioTheme
import org.json.JSONObject
import java.net.URLEncoder

/**
 * NuvioTV's player screen (ui/screens/player/PlayerScreen.kt), unchanged, for one source that LeXia
 * picked: the extras are the route arguments of Nuvio's "player" route. Where Nuvio's navigation
 * would go on (back to its stream or detail screens, on to the next episode's sources), this
 * finishes and answers LeXia instead: [RESULT_FAILED], [RESULT_NEXT] (season and episode in the
 * data), RESULT_OK when watched to the end, RESULT_CANCELED otherwise.
 */
class LeXiaPlayerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val extras = intent.extras
        val arguments: Map<String, String?> = extras?.keySet()?.associateWith { extras.getString(it) }.orEmpty()
        LeXiaNuvio.applyPlayerDefaults(this)
        val keep = progress
        val viewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                LeXiaNuvio.playerViewModel(applicationContext, arguments, keep) as T
        })[PlayerViewModel::class.java]
        val curtain = LeXiaCinema.enabled && savedInstanceState == null
        setContent {
            NuvioTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                PlayerScreen(
                    viewModel = viewModel,
                    onBackPress = { videoId, season, episode, _, completed ->
                        answer(if (completed) Activity.RESULT_OK else Activity.RESULT_CANCELED, videoId, season, episode)
                    },
                    onPlaybackErrorBack = { answer(RESULT_FAILED, null, null, null) },
                    onPlaybackEnded = { nextVideoId, nextSeason, nextEpisode, _ ->
                        if (nextVideoId != null && nextSeason != null && nextEpisode != null) {
                            answer(RESULT_NEXT, nextVideoId, nextSeason, nextEpisode)
                        } else {
                            answer(Activity.RESULT_OK, null, null, null)
                        }
                    }
                )
                if (curtain) Curtain()
                Credits(viewModel)
                if (quickFailMs > 0) QuickFail(viewModel, quickFailMs)
                }
            }
        }
    }

    /**
     * "Devam et" from a remembered source: a link that has gone stale must not keep the film on
     * "Yayın başlatılıyor". If nothing plays within [ms], LeXia's source list takes over.
     */
    @androidx.compose.runtime.Composable
    private fun QuickFail(viewModel: PlayerViewModel, ms: Long) {
        val state by viewModel.uiState.collectAsState()
        var started by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        if (state.isPlaying && !state.showLoadingOverlay) started = true
        androidx.compose.runtime.LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(ms)
            if (!started) answer(RESULT_FAILED, null, null, null)
        }
    }

    private val quickFailMs: Long get() = intent.getStringExtra(EXTRA_QUICK_FAIL)?.toLongOrNull() ?: 0L

    override fun onResume() {
        super.onResume()
        LeXiaCinema.playing = true
    }

    override fun onPause() {
        super.onPause()
        LeXiaCinema.playing = false
    }

    /**
     * Sinema modu: logo/title shimmers on a dark screen, then black curtains sweep apart like a
     * real cinema screen. The logo "fills" with the film's accent colour as the curtains open.
     */
    @Composable
    private fun Curtain() {
        val open = remember { Animatable(0f) }
        val logoReveal = remember { Animatable(0f) }
        var done by remember { mutableStateOf(false) }
        val accent = LeXiaCinema.accent.takeIf { it != 0 }?.let { Color(it) } ?: Color(0xFF8AB4FF)
        LaunchedEffect(Unit) {
            logoReveal.animateTo(1f, tween(800, easing = LinearEasing))
            kotlinx.coroutines.delay(200)
            open.animateTo(1f, tween(1600, easing = FastOutSlowInEasing))
            done = true
        }
        if (done) return
        val logo = intent.getStringExtra("logo")?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        val title = intent.getStringExtra("title")?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ""
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val half = maxWidth / 2
            val shift = half * open.value
            val logoAlpha = (1f - open.value * 1.8f).coerceIn(0f, 1f)
            val logoScale = 1f + open.value * 0.12f
            val fillFraction = logoReveal.value
            if (logoAlpha > 0f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .alpha(logoAlpha),
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.scale(logoScale).clipToBounds()) {
                        if (!logo.isNullOrBlank()) {
                            var failed by remember { mutableStateOf(false) }
                            if (!failed) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current).data(logo).memoryCacheKey("curtain_$logo").crossfade(false).build(),
                                    contentDescription = title,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.height(80.dp).widthIn(max = 420.dp),
                                    onError = { failed = true },
                                    alpha = 0.25f + fillFraction * 0.75f
                                )
                            } else {
                                CurtainTitle(title, fillFraction, accent)
                            }
                        } else {
                            CurtainTitle(title, fillFraction, accent)
                        }
                    }
                }
            }
            val shadeL = Brush.horizontalGradient(listOf(Color.Black, Color(0xFF0B0B0D)))
            Box(
                Modifier
                    .offset(x = -shift)
                    .width(half)
                    .fillMaxHeight()
                    .background(shadeL)
            )
            Box(
                Modifier
                    .offset(x = half + shift)
                    .width(half)
                    .fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Color(0xFF0B0B0D), Color.Black)))
            )
        }
    }

    @Composable
    private fun CurtainTitle(title: String, fill: Float, accent: Color) {
        val color = lerp(Color.White.copy(alpha = 0.35f), accent, fill)
        Text(
            title,
            style = TextStyle(fontSize = 42.sp, fontWeight = FontWeight.Light, color = color, letterSpacing = 1.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 48.dp)
        )
    }

    private fun lerp(a: Color, b: Color, t: Float) = Color(
        a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t,
        a.alpha + (b.alpha - a.alpha) * t
    )

    /**
     * The credits of a film: a note when a scene is still to come in or after them (TMDB), and
     * "Bunu sevdiysen", three films to go on with, beside the credits as they roll. Geri closes it.
     */
    @Composable
    private fun Credits(viewModel: PlayerViewModel) {
        val scene = LeXiaCinema.creditsScene
        val films = LeXiaCinema.suggestions
        if (scene == 0 && films.isEmpty()) return
        val timeline by viewModel.playbackTimeline.collectAsState()
        val state by viewModel.uiState.collectAsState()
        val duration = timeline.duration
        if (duration <= 0 || timeline.isLive) return
        val left = duration - timeline.currentPosition
        val window = (duration * 0.045).toLong().coerceIn(3 * 60_000L, 8 * 60_000L)
        val creditsNow = state.activeSkipInterval?.type?.let { it == "movie-credits" || it == "outro" || it == "ed" } == true
        var started by remember { mutableStateOf(false) }
        if (!started && (creditsNow || left in 1..window)) started = true
        if (left > window + 60_000L && !creditsNow) started = false
        val accent = LeXiaCinema.accent.takeIf { it != 0 }?.let { Color(it) } ?: Color(0xFF8AB4FF)

        // The note: twelve seconds when the credits begin.
        var noteShown by remember { mutableStateOf(false) }
        var noteDone by remember { mutableStateOf(false) }
        LaunchedEffect(started) {
            if (started && scene != 0 && !noteDone) {
                noteShown = true
                kotlinx.coroutines.delay(12_000)
                noteShown = false
                noteDone = true
            }
        }
        // The films: after the note, while the credits roll; not over a scene still to come at the very end.
        var panelClosed by remember { mutableStateOf(false) }
        val afterScene = scene and 2 != 0
        val panel = started && films.isNotEmpty() && !panelClosed && !noteShown &&
            (!afterScene || left > 90_000L)
        Box(Modifier.fillMaxSize()) {
            androidx.compose.animation.AnimatedVisibility(
                noteShown,
                enter = androidx.compose.animation.fadeIn(tween(400)) + androidx.compose.animation.slideInVertically { -it / 2 },
                exit = androidx.compose.animation.fadeOut(tween(400)),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp)
            ) {
                val text = when (scene) {
                    1 -> "Jenerik sırasında bir sahne var"
                    2 -> "Jenerikten sonra bir sahne var · Kapatma"
                    else -> "Jenerikte ve sonrasında sahne var · Kapatma"
                }
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(Color(0xD91C1C1E), androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                        .border(1.dp, accent.copy(alpha = 0.45f), androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                        .padding(horizontal = 22.dp, vertical = 12.dp)
                ) {
                    Box(Modifier.width(8.dp).height(8.dp).background(accent, androidx.compose.foundation.shape.CircleShape))
                    androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
                    Text(text, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White, letterSpacing = 0.2.sp))
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                panel,
                enter = androidx.compose.animation.fadeIn(tween(600)) + androidx.compose.animation.slideInHorizontally { it / 3 },
                exit = androidx.compose.animation.fadeOut(tween(300)),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
            ) {
                SuggestionPanel(films, accent, afterScene, onClose = { panelClosed = true }) { i ->
                    answer(RESULT_SUGGEST, null, null, null, i)
                }
            }
        }
    }

    @Composable
    private fun SuggestionPanel(
        films: List<LeXiaCinema.Suggestion>,
        accent: Color,
        afterScene: Boolean,
        onClose: () -> Unit,
        onPick: (Int) -> Unit
    ) {
        val first = remember { androidx.compose.ui.focus.FocusRequester() }
        androidx.activity.compose.BackHandler { onClose() }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(150)
            runCatching { first.requestFocus() }
        }
        androidx.compose.foundation.layout.Column(
            Modifier
                .fillMaxHeight()
                .width(560.dp)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xCC000000), Color(0xF2000000))))
                .padding(start = 96.dp, end = 56.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
        ) {
            Text("BUNU SEVDİYSEN", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, color = accent))
            androidx.compose.foundation.layout.Spacer(Modifier.height(18.dp))
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(18.dp),
            ) {
                films.forEachIndexed { i, f ->
                    SuggestionCard(f, accent, if (i == 0) first else null) { onPick(i) }
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.height(22.dp))
            Text(
                if (afterScene) "Geri: jeneriğe dön · sahne birazdan" else "Geri: jeneriğe dön",
                style = TextStyle(fontSize = 13.sp, color = Color.White.copy(alpha = 0.5f))
            )
        }
    }

    @Composable
    private fun SuggestionCard(
        film: LeXiaCinema.Suggestion,
        accent: Color,
        focus: androidx.compose.ui.focus.FocusRequester?,
        onClick: () -> Unit
    ) {
        var focused by remember { mutableStateOf(false) }
        val grow by androidx.compose.animation.core.animateFloatAsState(if (focused) 1.08f else 1f, tween(180), label = "card")
        androidx.compose.foundation.layout.Column(
            Modifier
                .width(118.dp)
                .scale(grow)
                .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { e ->
                    val k = e.nativeKeyEvent
                    when (k.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (k.action == android.view.KeyEvent.ACTION_UP) onClick()
                            true
                        }
                        // Up and down stay in the panel: the player's own controls are not under it.
                        android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN -> true
                        else -> false
                    }
                }
                .focusable()
        ) {
            Box(
                Modifier
                    .width(118.dp)
                    .height(177.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .background(Color(0xFF1C1C1E))
                    .then(
                        if (focused) Modifier.border(3.dp, Color.White, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                        else Modifier
                    )
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(film.poster).crossfade(true).build(),
                    contentDescription = film.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
            Text(
                film.title,
                style = TextStyle(fontSize = 13.sp, fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal, color = if (focused) Color.White else Color.White.copy(alpha = 0.75f)),
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            if (film.line.isNotBlank()) Text(
                film.line,
                style = TextStyle(fontSize = 11.sp, color = if (focused) accent else Color.White.copy(alpha = 0.45f)),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }

    private fun answer(result: Int, videoId: String?, season: Int?, episode: Int?, suggestion: Int = -1) {
        if (isFinishing) return
        setResult(result, Intent().apply {
            if (suggestion >= 0) putExtra(RESULT_SUGGESTION, suggestion)
            videoId?.let { putExtra(RESULT_VIDEO_ID, it) }
            season?.let { putExtra(RESULT_SEASON, it) }
            episode?.let { putExtra(RESULT_EPISODE, it) }
        })
        finish()
    }

    companion object {
        const val RESULT_FAILED = Activity.RESULT_FIRST_USER
        const val RESULT_NEXT = Activity.RESULT_FIRST_USER + 1
        /** A film offered during the credits was chosen; its place in the list is [RESULT_SUGGESTION]. */
        const val RESULT_SUGGEST = Activity.RESULT_FIRST_USER + 2
        const val RESULT_SUGGESTION = "lexia.player.suggestion"
        const val RESULT_VIDEO_ID = "lexia.player.videoId"
        const val RESULT_SEASON = "lexia.player.season"
        const val RESULT_EPISODE = "lexia.player.episode"
        /** Milliseconds to wait for the picture before giving up on a remembered source. */
        const val EXTRA_QUICK_FAIL = "lexia.player.quickFail"

        /** LeXia's record of where films stop; set before [intent] is started. */
        @Volatile
        var progress: LeXiaProgress? = null

        /** As Nuvio's Screen.Player.createRoute encodes the text arguments. */
        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        /**
         * The player for [streamUrl]. [contentId] is the title's id ("tt…" or "tmdb:…"), [videoId] the
         * episode's ("tt…:2:5") or the film's; [headers] go with every request, as the source asked.
         */
        fun intent(
            context: Context,
            streamUrl: String,
            title: String,
            contentId: String,
            contentType: String,
            videoId: String,
            season: Int? = null,
            episode: Int? = null,
            episodeTitle: String? = null,
            year: String? = null,
            poster: String? = null,
            backdrop: String? = null,
            logo: String? = null,
            headers: Map<String, String> = emptyMap(),
            streamName: String? = null,
            addonName: String? = null,
            streamDescription: String? = null,
            filename: String? = null,
            bingeGroup: String? = null,
            startFromBeginning: Boolean = false,
            /** Subtitles that come with this source, offered beside the add-on ones (as Nuvio does). */
            subtitles: List<com.nuvio.tv.domain.model.Subtitle> = emptyList()
        ): Intent {
            com.nuvio.tv.ui.screens.player.StreamSidecarSubtitles.set(streamUrl, subtitles)
            val raw = mapOf(
                "streamUrl" to streamUrl,
                "contentId" to contentId,
                "contentType" to contentType,
                "videoId" to videoId,
                "season" to season?.toString(),
                "episode" to episode?.toString(),
                "bingeGroup" to bingeGroup,
                "startFromBeginning" to startFromBeginning.toString(),
                "launchStartedAtMs" to System.currentTimeMillis().toString()
            )
            val encoded = mapOf(
                "title" to title,
                "contentName" to title,
                "streamName" to streamName,
                "year" to year,
                "headers" to headers.takeIf { it.isNotEmpty() }?.let { JSONObject(it).toString() },
                "poster" to poster,
                "backdrop" to backdrop,
                "logo" to logo,
                "episodeTitle" to episodeTitle,
                "addonName" to addonName,
                "streamDescription" to streamDescription,
                "filename" to filename
            )
            return Intent(context, LeXiaPlayerActivity::class.java).apply {
                raw.forEach { (key, value) -> if (!value.isNullOrEmpty()) putExtra(key, value) }
                encoded.forEach { (key, value) -> if (!value.isNullOrEmpty()) putExtra(key, encode(value)) }
            }
        }
    }
}
