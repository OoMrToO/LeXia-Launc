// LeXia's own (not Nuvio code): the player's windows in LeXia's colours.
package com.nuvio.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.nuvio.tv.ui.theme.LocalNuvioColors
import com.nuvio.tv.ui.theme.LocalNuvioExtendedColors
import com.nuvio.tv.ui.theme.LocalNuvioFocusRingStyle
import com.nuvio.tv.ui.theme.LocalThemePalette
import com.nuvio.tv.ui.theme.NuvioColorScheme
import com.nuvio.tv.ui.theme.NuvioExtendedColors
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.ThemeColorPalette
import com.nuvio.tv.ui.theme.createFocusRingStyle

/**
 * Nuvio's theme with LeXia's palette in it, so every window the player opens (sound and subtitles,
 * sources, episodes, chapters, speed, "Girişi atla", "Sonraki bölüm") is drawn as LeXia's are:
 * the chosen line a white plate with dark words, a white rim on the one under the remote, and
 * LeXia's dark glass behind. Nuvio's own code stays as it is; only the colours it asks for change.
 */
object LeXiaTheme {
    private val ink = Color(0xFF0B0B0D)

    val palette = ThemeColorPalette(
        secondary = Color.White,
        secondaryVariant = Color(0xFFE6E6EA),
        onSecondary = ink,
        onSecondaryVariant = ink,
        accentGradient = listOf(Color.White, Color(0xFFDADAE0)),
        focusRing = Color.White,
        focusRingGradient = listOf(Color.White, Color(0xFFCFCFD6)),
        focusBackground = Color.White.copy(alpha = 0.16f),
        background = Color(0xFF0B0B0D),
        backgroundElevated = Color(0xFF1B1B20),
        backgroundCard = Color(0xFF1E1E24),
        surface = Color(0xFF1B1B20),
        surfaceVariant = Color(0xFF26262D),
        panel = Color(0xE61B1B20),
        overlay = Color(0xCC000000),
        field = Color(0xFF22222A),
        menu = Color(0xF21C1C21),
        modal = Color(0xF21C1C21),
        playerOverlay = Color(0xB3000000)
    )

    @OptIn(ExperimentalTvMaterial3Api::class)
    @Composable
    fun Wrap(content: @Composable () -> Unit) {
        NuvioTheme {
            val scheme = NuvioColorScheme(palette)
            val extended = NuvioExtendedColors(
                backgroundElevated = scheme.BackgroundElevated,
                backgroundCard = scheme.BackgroundCard,
                textSecondary = scheme.TextSecondary,
                textTertiary = scheme.TextTertiary,
                focusRing = scheme.FocusRing,
                focusBackground = scheme.FocusBackground,
                rating = scheme.Rating
            )
            CompositionLocalProvider(
                LocalNuvioColors provides scheme,
                LocalNuvioExtendedColors provides extended,
                LocalThemePalette provides palette,
                LocalNuvioFocusRingStyle provides createFocusRingStyle(palette)
            ) {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        primary = Color.White,
                        onPrimary = ink,
                        secondary = scheme.Secondary,
                        onSecondary = scheme.OnSecondary,
                        background = scheme.Background,
                        surface = scheme.Surface,
                        surfaceVariant = scheme.SurfaceVariant,
                        onBackground = scheme.TextPrimary,
                        onSurface = scheme.TextPrimary,
                        onSurfaceVariant = scheme.TextSecondary,
                        error = scheme.Error
                    ),
                    typography = MaterialTheme.typography,
                    content = content
                )
            }
        }
    }
}
