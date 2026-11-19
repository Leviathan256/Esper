package com.esper.app.ui.theme

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esper.app.ui.theme.EsperColors.ally
import com.esper.app.ui.theme.EsperColors.esper
import com.esper.app.ui.theme.EsperColors.foe
import com.esper.app.ui.theme.EsperColors.ink
import com.esper.app.ui.theme.EsperColors.inkSoft
import com.esper.app.ui.theme.EsperColors.line
import com.esper.app.ui.theme.EsperColors.paper
import com.esper.app.ui.theme.EsperColors.paperDim

/** §4.1's Material 3 scheme, for anything not yet drawn with the Esper surfaces. One light scheme. */
private val scheme = lightColorScheme(
    primary = ink, onPrimary = paper, primaryContainer = paperDim, onPrimaryContainer = ink, inversePrimary = paper,
    secondary = ally, onSecondary = paper, secondaryContainer = paperDim, onSecondaryContainer = ink,
    tertiary = esper, onTertiary = paper, tertiaryContainer = paperDim, onTertiaryContainer = ink,
    error = foe, onError = paper, errorContainer = paperDim, onErrorContainer = ink,
    background = paper, onBackground = ink, surface = paper, onSurface = ink,
    surfaceVariant = paperDim, onSurfaceVariant = inkSoft, surfaceTint = paper,
    inverseSurface = ink, inverseOnSurface = paper, outline = ink, outlineVariant = line, scrim = ink,
    surfaceBright = paper, surfaceDim = paperDim, surfaceContainer = paper, surfaceContainerHigh = paper,
    surfaceContainerHighest = paper, surfaceContainerLow = paper, surfaceContainerLowest = paper,
)

/** System families only: nothing bundled (`VisualGuardsTest`). */
private val display = FontFamily.Serif
private val body = FontFamily.SansSerif

@OptIn(ExperimentalTextApi::class)
private val hud = FontFamily(Font(DeviceFontFamilyName("sans-serif-condensed"), FontWeight.Bold))

private fun displayStyle(size: Int, lineHeight: Int) =
    TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = size.sp, lineHeight = lineHeight.sp)

private fun bodyStyle(size: Int, lineHeight: Int) =
    TextStyle(fontFamily = body, fontWeight = FontWeight.Normal, fontSize = size.sp, lineHeight = lineHeight.sp)

/** HUD: numbers, buttons, labels and stamps, in tabular figures. */
fun hudStyle(size: Int, lineHeight: Int = size + 4) = TextStyle(
    fontFamily = hud, fontWeight = FontWeight.Bold, fontSize = size.sp, lineHeight = lineHeight.sp,
    fontFeatureSettings = "tnum",
)

/** §4.1's slots. */
private val typography = Typography(
    headlineMedium = displayStyle(28, 34),
    titleLarge = displayStyle(22, 28),
    titleMedium = displayStyle(19, 24),
    titleSmall = hudStyle(16, 20),
    bodyLarge = bodyStyle(16, 22),
    bodyMedium = bodyStyle(16, 22),
    bodySmall = bodyStyle(14, 20),
    labelLarge = hudStyle(16, 20),
    labelMedium = hudStyle(14, 18),
    labelSmall = TextStyle(fontFamily = body, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)

private val shapes = Shapes(
    extraSmall = CutCornerShape(Spec.CUT_TAG_DP.dp),
    small = CutCornerShape(Spec.CUT_CONTROL_DP.dp),
    medium = CutCornerShape(Spec.CUT_CARD_DP.dp),
    large = CutCornerShape(Spec.CUT_CARD_DP.dp),
    extraLarge = CutCornerShape(Spec.CUT_CARD_DP.dp),
)

/** The Field Mat theme (docs/VISUALS.md §4.1). */
@Composable
fun EsperTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes) {
        // Text outside a Material surface is ink, not the default black.
        CompositionLocalProvider(LocalContentColor provides ink, content = content)
    }
}
