package com.bruni.carscan.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import com.bruni.carscan.core.designsystem.gauge.ClassicAnalogGauge
import com.bruni.carscan.core.designsystem.gauge.GaugeRenderer
import com.bruni.carscan.core.designsystem.gauge.GaugeStyleId
import com.bruni.carscan.core.designsystem.gauge.GaugeTheme
import com.bruni.carscan.core.designsystem.gauge.LocalGaugeRenderer
import com.bruni.carscan.core.designsystem.gauge.ModernArcGauge
import com.bruni.carscan.core.units.NumberFormatter

/**
 * The palette a gauge draws itself in. Provided by [CarScanTheme] to match the chosen style;
 * the HUD overrides it with [GaugeThemes.Hud] and nothing else changes.
 */
val LocalGaugeTheme = staticCompositionLocalOf { GaugeThemes.ModernDark }

/**
 * How numbers are written here — `13.8` or `13,8`.
 *
 * The fallback is English rather than `error(...)`, because a gauge is deliberately renderable
 * outside a theme (that is how the renderer tests drive it) and because a number in the wrong
 * separator is a cosmetic bug, while a crash on a dashboard is not. [CarScanTheme] replaces it
 * with the device's locale.
 */
val LocalNumberFormatter = staticCompositionLocalOf<NumberFormatter> {
    // Fails loudly rather than defaulting to NumberFormatter("en"), which is what this used to do.
    // A silent English fallback is the worst possible default here: it renders 13.8 where six of
    // our eight locales expect 13,8, it does so only on whatever screen forgot the theme, and it
    // looks completely fine to anyone reading the code or the tests. LocalGaugeRenderer already
    // fails this way; there is no reason for the number formatter to be the quiet one.
    error("No NumberFormatter provided — wrap the content in a CarScanTheme")
}

/**
 * Material colours, typography and shapes, plus the two things Material knows nothing about:
 * which gauge renderer the user picked, and how their locale writes a number.
 *
 * [gaugeStyle] is a persisted user setting, so it arrives here rather than at each tile — no
 * screen should have to know which renderer it is drawing.
 *
 * **A caveat on [GaugeStyleId.CLASSIC_ANALOG]**: `ClassicAnalogGauge` fills no dial face (a
 * [GaugeTheme] has no background, by design), so its near-black needle needs a *light* tile
 * behind it. A classic gauge dropped straight onto a dark Material surface is a black needle on
 * a black ground. The tile is the dashboard's to draw, not the theme's — so the dashboard must
 * give a classic tile a light face.
 */
@Composable
fun CarScanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    gaugeStyle: GaugeStyleId = GaugeStyleId.MODERN_ARC,
    content: @Composable () -> Unit,
) {
    val renderer: GaugeRenderer = remember(gaugeStyle) {
        when (gaugeStyle) {
            GaugeStyleId.MODERN_ARC -> ModernArcGaugeInstance
            GaugeStyleId.CLASSIC_ANALOG -> ClassicAnalogGaugeInstance
        }
    }

    val languageTag = Locale.current.toLanguageTag()
    val formatter = remember(languageTag) { NumberFormatter(languageTag) }

    CompositionLocalProvider(
        LocalGaugeRenderer provides renderer,
        LocalGaugeTheme provides GaugeThemes.forStyle(gaugeStyle),
        LocalNumberFormatter provides formatter,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) CarScanDarkColors else CarScanLightColors,
            typography = CarScanTypography,
            shapes = CarScanShapes,
            content = content,
        )
    }
}

// Renderers are stateless, so one of each is enough for the whole app.
private val ModernArcGaugeInstance = ModernArcGauge()
private val ClassicAnalogGaugeInstance = ClassicAnalogGauge()

/** Dark is the default: this is an app used in a car, most often at night or in a dim cabin. */
private val CarScanDarkColors = darkColorScheme(
    primary = Color(0xFF00E5FF),
    onPrimary = Color(0xFF00363D),
    secondary = Color(0xFF80DEEA),
    onSecondary = Color(0xFF00363D),
    error = Color(0xFFFF3B30),
    background = Color(0xFF0E1114),
    onBackground = Color(0xFFF2F5F7),
    surface = Color(0xFF14171B),
    onSurface = Color(0xFFF2F5F7),
    surfaceVariant = Color(0xFF1E2228),
    onSurfaceVariant = Color(0xFFC2C8CF),
    outline = Color(0xFF565D66),
)

private val CarScanLightColors = lightColorScheme(
    primary = Color(0xFF006874),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF4A6267),
    onSecondary = Color(0xFFFFFFFF),
    error = Color(0xFFBA1A1A),
    background = Color(0xFFFAFDFD),
    onBackground = Color(0xFF191C1D),
    surface = Color(0xFFFAFDFD),
    onSurface = Color(0xFF191C1D),
    surfaceVariant = Color(0xFFEDE7DA),
    onSurfaceVariant = Color(0xFF3F484A),
    outline = Color(0xFF6F797A),
)

private val CarScanTypography = Typography()

/** Tiles are cards on a dashboard grid, so the corners are generous rather than sharp. */
private val CarScanShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
