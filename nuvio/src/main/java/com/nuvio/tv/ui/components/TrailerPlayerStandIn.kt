// LeXia stand-in for NuvioTV's trailer view in its post-play recommendations. Not Nuvio code:
// LeXia's player never finds a trailer for them (TrailerService answers none), so it shows nothing.
package com.nuvio.tv.ui.components

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nuvio.tv.core.player.TrailerPlayerPool

@Composable
fun TrailerPlayer(
    trailerUrl: String?,
    trailerAudioUrl: String? = null,
    isPlaying: Boolean,
    isPaused: Boolean = false,
    onEnded: () -> Unit,
    onError: () -> Unit = {},
    onFirstFrameRendered: () -> Unit = {},
    muted: Boolean = false,
    seekRequestToken: Int = 0,
    seekDeltaMs: Long = 0L,
    onProgressChanged: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> },
    onRemoteKey: (keyCode: Int, action: Int, repeatCount: Int) -> Boolean = { _, _, _ -> false },
    cropToFill: Boolean = false,
    overscanZoom: Float = 1f,
    autoCropLetterbox: Boolean = false,
    modifier: Modifier = Modifier,
    enter: EnterTransition = fadeIn(animationSpec = tween(800)),
    exit: ExitTransition = fadeOut(animationSpec = tween(500)),
    trailerPlayerPool: TrailerPlayerPool? = null
) = Unit
