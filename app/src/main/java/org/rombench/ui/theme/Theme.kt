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

private val Light = lightColorScheme(
    primary = Color(0xFF006874), onPrimary = Color.White,
    primaryContainer = Color(0xFF97F0FF), onPrimaryContainer = Color(0xFF001F24),
    secondary = Color(0xFF4A6267), onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE7EC), onSecondaryContainer = Color(0xFF051F23),
    tertiary = Color(0xFF525E7D), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDAE2FF), onTertiaryContainer = Color(0xFF0E1B37),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF5FAFB), onBackground = Color(0xFF171D1E),
    surface = Color(0xFFF5FAFB), onSurface = Color(0xFF171D1E),
    surfaceVariant = Color(0xFFDBE4E6), onSurfaceVariant = Color(0xFF3F484A),
    outline = Color(0xFF6F797A), outlineVariant = Color(0xFFBFC8CA),
    inverseSurface = Color(0xFF2B3133), inverseOnSurface = Color(0xFFECF2F3), inversePrimary = Color(0xFF4FD8EB),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFEFF5F6),
    surfaceContainer = Color(0xFFE9EFF0), surfaceContainerHigh = Color(0xFFE3E9EA),
    surfaceContainerHighest = Color(0xFFDEE3E5), surfaceBright = Color(0xFFF5FAFB), surfaceDim = Color(0xFFD5DBDC),
    surfaceTint = Color(0xFF006874), scrim = Color.Black,
)

private val Dark = darkColorScheme(
    primary = Color(0xFF4FD8EB), onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF004F58), onPrimaryContainer = Color(0xFF97F0FF),
    secondary = Color(0xFFB1CBD0), onSecondary = Color(0xFF1C3438),
    secondaryContainer = Color(0xFF334B4F), onSecondaryContainer = Color(0xFFCDE7EC),
    tertiary = Color(0xFFBAC6EA), onTertiary = Color(0xFF24304D),
    tertiaryContainer = Color(0xFF3B4664), onTertiaryContainer = Color(0xFFDAE2FF),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1415), onBackground = Color(0xFFDEE3E5),
    surface = Color(0xFF0E1415), onSurface = Color(0xFFDEE3E5),
    surfaceVariant = Color(0xFF3F484A), onSurfaceVariant = Color(0xFFBFC8CA),
    outline = Color(0xFF899294), outlineVariant = Color(0xFF3F484A),
    inverseSurface = Color(0xFFDEE3E5), inverseOnSurface = Color(0xFF2B3133), inversePrimary = Color(0xFF006874),
    surfaceContainerLowest = Color(0xFF090F10), surfaceContainerLow = Color(0xFF171D1E),
    surfaceContainer = Color(0xFF1B2122), surfaceContainerHigh = Color(0xFF252B2C),
    surfaceContainerHighest = Color(0xFF303637), surfaceBright = Color(0xFF343A3B), surfaceDim = Color(0xFF0E1415),
    surfaceTint = Color(0xFF4FD8EB), scrim = Color.Black,
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
    Color(0xFF1B7F3A), Color(0xFFD3F4DB), Color(0xFF8F5F00), Color(0xFFFFEDC2),
    Color(0xFFBA1A1A), Color(0xFFFFDAD6), Color(0xFF3D5A99), Color(0xFFDCE4FF),
    Color(0xFF6F797A), Color(0xFFE3E9EA),
)
private val DarkStatus = StatusColors(
    Color(0xFF7EDB98), Color(0xFF0F3D1F), Color(0xFFF5C46B), Color(0xFF3D2E00),
    Color(0xFFFFB4AB), Color(0xFF5C1010), Color(0xFFB4C5FF), Color(0xFF1E2D57),
    Color(0xFF899294), Color(0xFF252B2C),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

/** Monospace style for raw values (sysfs, props, kernel strings). */
val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun RomBenchTheme(dynamic: Boolean = true, content: @Composable () -> Unit) {
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
