package org.rombench.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.rombench.core.Status

// Neutral palette: colour is reserved for result states, so a status reads at a glance.
private val Light = lightColorScheme(
    primary = Color(0xFF1F5FD6), onPrimary = Color.White,
    primaryContainer = Color(0xFFE3ECFC), onPrimaryContainer = Color(0xFF0B2A66),
    secondary = Color(0xFF475467), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEEF0F3), onSecondaryContainer = Color(0xFF1D2433),
    tertiary = Color(0xFF475467), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEEF0F3), onTertiaryContainer = Color(0xFF1D2433),
    error = Color(0xFFD92D20), onError = Color.White,
    errorContainer = Color(0xFFFEE4E2), onErrorContainer = Color(0xFF7A1A12),
    background = Color(0xFFF4F5F7), onBackground = Color(0xFF14171C),
    surface = Color(0xFFF4F5F7), onSurface = Color(0xFF14171C),
    surfaceVariant = Color(0xFFEEF0F3), onSurfaceVariant = Color(0xFF5B6472),
    outline = Color(0xFFD0D5DD), outlineVariant = Color(0xFFE4E7EC),
    inverseSurface = Color(0xFF1D2433), inverseOnSurface = Color(0xFFF4F5F7), inversePrimary = Color(0xFF8AB4F8),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF9FAFB), surfaceContainerHigh = Color(0xFFF2F4F7),
    surfaceContainerHighest = Color(0xFFEAECF0), surfaceBright = Color.White, surfaceDim = Color(0xFFE4E7EC),
    surfaceTint = Color.Transparent, scrim = Color.Black,
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8AB4F8), onPrimary = Color(0xFF0B2A66),
    primaryContainer = Color(0xFF1C2F52), onPrimaryContainer = Color(0xFFD6E4FD),
    secondary = Color(0xFFB4BCC8), onSecondary = Color(0xFF1D2433),
    secondaryContainer = Color(0xFF262B33), onSecondaryContainer = Color(0xFFE4E7EC),
    tertiary = Color(0xFFB4BCC8), onTertiary = Color(0xFF1D2433),
    tertiaryContainer = Color(0xFF262B33), onTertiaryContainer = Color(0xFFE4E7EC),
    error = Color(0xFFF97066), onError = Color(0xFF55160C),
    errorContainer = Color(0xFF55160C), onErrorContainer = Color(0xFFFEE4E2),
    background = Color(0xFF0F1114), onBackground = Color(0xFFECEEF1),
    surface = Color(0xFF0F1114), onSurface = Color(0xFFECEEF1),
    surfaceVariant = Color(0xFF22262D), onSurfaceVariant = Color(0xFF98A2B3),
    outline = Color(0xFF3A404A), outlineVariant = Color(0xFF2A2F37),
    inverseSurface = Color(0xFFECEEF1), inverseOnSurface = Color(0xFF1D2433), inversePrimary = Color(0xFF1F5FD6),
    surfaceContainerLowest = Color(0xFF0B0D10), surfaceContainerLow = Color(0xFF181B20),
    surfaceContainer = Color(0xFF1C2026), surfaceContainerHigh = Color(0xFF22262D),
    surfaceContainerHighest = Color(0xFF2A2F37), surfaceBright = Color(0xFF2A2F37), surfaceDim = Color(0xFF0F1114),
    surfaceTint = Color.Transparent, scrim = Color.Black,
)

/** Colours for result states; same meaning in both themes. */
@Immutable
data class StatusColors(
    val pass: Color, val passContainer: Color,
    val warn: Color, val warnContainer: Color,
    val fail: Color, val failContainer: Color,
    val info: Color, val infoContainer: Color,
    val skip: Color, val skipContainer: Color,
) {
    fun of(s: Status?): Color = when (s) {
        Status.PASS -> pass; Status.WARN -> warn; Status.FAIL -> fail; Status.INFO -> info; else -> skip
    }

    fun container(s: Status?): Color = when (s) {
        Status.PASS -> passContainer; Status.WARN -> warnContainer; Status.FAIL -> failContainer
        Status.INFO -> infoContainer; else -> skipContainer
    }
}

private val LightStatus = StatusColors(
    Color(0xFF079455), Color(0xFFDCFAE6), Color(0xFFDC6803), Color(0xFFFEF0C7),
    Color(0xFFD92D20), Color(0xFFFEE4E2), Color(0xFF475467), Color(0xFFEEF0F3),
    Color(0xFF98A2B3), Color(0xFFF2F4F7),
)
private val DarkStatus = StatusColors(
    Color(0xFF47CD89), Color(0xFF0E3B25), Color(0xFFFDB022), Color(0xFF4A2F05),
    Color(0xFFF97066), Color(0xFF55160C), Color(0xFFB4BCC8), Color(0xFF262B33),
    Color(0xFF667085), Color(0xFF1C2026),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

/** Monospace style for raw values (sysfs, props, kernel strings). */
val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun RomBenchTheme(dynamic: Boolean = false, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme: ColorScheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
