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
import com.bruni.carscan.core.designsystem.gauge.LinearBarHGauge
import com.bruni.carscan.core.designsystem.gauge.LinearBarVGauge
import com.bruni.carscan.core.designsystem.gauge.LocalGaugeRenderer
import com.bruni.carscan.core.designsystem.gauge.ModernArcGauge
import com.bruni.carscan.core.designsystem.gauge.NumericGauge
import com.bruni.carscan.core.designsystem.gauge.SemicircleGauge
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
            GaugeStyleId.SEMICIRCLE -> SemicircleGaugeInstance
            GaugeStyleId.NUMERIC -> NumericGaugeInstance
            GaugeStyleId.LINEAR_BAR_H -> LinearBarHGaugeInstance
            GaugeStyleId.LINEAR_BAR_V -> LinearBarVGaugeInstance
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
private val SemicircleGaugeInstance = SemicircleGauge()
private val NumericGaugeInstance = NumericGauge()
private val LinearBarHGaugeInstance = LinearBarHGauge()
private val LinearBarVGaugeInstance = LinearBarVGauge()

/**
 * Dark is the default: this is an app used in a car, most often at night or in a dim cabin.
 *
 * A near-black, high-contrast, minimal palette in the spirit of a modern EV/automotive app —
 * deep blacks, crisp whites, an electric-cyan accent. Red is reserved for the redline/error
 * semantic (a gauge app cannot spend it on branding), amber on warnings, green on the optimal band.
 */
private val CarScanDarkColors = darkColorScheme(
    primary = Color(0xFF7C5CFF),          // electric indigo — vivid, alive, but not neon-cheap
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF2A2550),
    onPrimaryContainer = Color(0xFFDBD1FF),
    secondary = Color(0xFFA0A6B4),
    onSecondary = Color(0xFF1A1D22),
    secondaryContainer = Color(0xFF262832),
    onSecondaryContainer = Color(0xFFDEE1E8),
    tertiary = Color(0xFFF5B841),          // amber — warnings (semantic, not brand)
    onTertiary = Color(0xFF3A2A00),
    error = Color(0xFFFF5A5F),
    onError = Color(0xFF3A0006),
    background = Color(0xFF0C0D12),        // near-black with a faint violet warmth
    onBackground = Color(0xFFF2F3F7),
    surface = Color(0xFF17171F),
    onSurface = Color(0xFFF2F3F7),
    surfaceVariant = Color(0xFF20212B),
    onSurfaceVariant = Color(0xFFA7ABB6),
    outline = Color(0xFF2E3038),
    outlineVariant = Color(0xFF24252E),
)

private val CarScanLightColors = lightColorScheme(
    primary = Color(0xFF5B3FD9),          // vivid indigo, deep enough on white
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE7E0FF),
    onPrimaryContainer = Color(0xFF20124D),
    secondary = Color(0xFF5A5F6E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1E2EA),
    onSecondaryContainer = Color(0xFF171922),
    tertiary = Color(0xFF9A6B00),          // amber, darkened for light surfaces
    onTertiary = Color(0xFFFFFFFF),
    error = Color(0xFFD92D20),
    onError = Color(0xFFFFFFFF),
    background = Color(0xFFF4F4F8),
    onBackground = Color(0xFF1A1B21),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1B21),
    surfaceVariant = Color(0xFFE9E9F0),
    onSurfaceVariant = Color(0xFF484B57),
    outline = Color(0xFFC3C4D0),
    outlineVariant = Color(0xFFDEDFE8),
)

private val CarScanTypography = Typography()

/**
 * Generous, modern corner radii. Cards and tiles read as soft rounded panels, in the current
 * trend and in the spirit of the automotive references (Tesla / Auto Garage): rounded, not sharp.
 */
private val CarScanShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)
