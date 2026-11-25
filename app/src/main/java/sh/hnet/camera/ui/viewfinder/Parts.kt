package sh.hnet.camera.ui.viewfinder

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import sh.hnet.camera.R

/** The haptics of SPEC §20.2, through the view so the system's touch-feedback setting applies. */
class Haptics(private val view: View) {
    fun photo() = view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    /** A zoom stop or detent reached, a ruler's zero. */
    fun detent() = view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
    /** A ruler's step, the zoom's whole numbers. */
    fun step() = view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
    /** The end of a range. */
    fun end() = view.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
    fun mode() = view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    fun lock() = view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

/**
 * The surface of controls lying over the live picture (SPEC §4.8): solid `surfaceContainerHigh`, always readable. The handoff's translucent "finder glass" over a
 * blurred copy of the picture was built and dropped by the owner (23 Nov 2026): its picture changed in visible steps while the phone moved.
 */
fun Modifier.controlSurface(shape: Shape, color: Color): Modifier = clip(shape).background(color, shape)

/** The controls' colour over the picture. */
@Composable
@ReadOnlyComposable
fun controlColor(): Color = MaterialTheme.colorScheme.surfaceContainerHigh

/** Numbers in the viewfinder follow the language's decimal separator ("0,5" in Polish, "0.5" in English): one decimal at most, none when whole. */
@Composable
fun rememberNumbers(): Numbers {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { Numbers(locale) }
}

class Numbers(locale: Locale) {
    private val format = NumberFormat.getNumberInstance(locale).apply {
        maximumFractionDigits = 1
        minimumFractionDigits = 0
    }

    fun zoom(value: Double): String = decimal(value)

    /** A number with at most one decimal ("0,5", "13"). */
    fun decimal(value: Double): String = format.format((value * 10).roundToInt() / 10.0)

    /** Exposure compensation with its sign ("+0,7", "−1"; zero without one); the minus is the typographic one. */
    fun exposure(ev: Double): String {
        val rounded = (ev * 10).roundToInt() / 10.0
        if (abs(rounded) < 0.05) return format.format(0)
        return (if (rounded > 0) "+" else "−") + format.format(abs(rounded))
    }
}

/**
 * The colours of controls drawn over the picture without a surface (owner, 25 Nov 2026): the light scheme's white (as the screen flash's) for plain ones –
 * brighter than the dark scheme's `onSurface`, which matters on a bright sky – and amber for one switched on or set by hand (the owner's exception to the
 * theme colours, as the flash that will fire: `primaryFixed` looked dimmer than the white, as if off).
 */
class OverlayColors(val content: Color, val accent: Color, val shadow: Color)

@Composable
fun overlayColors(): OverlayColors {
    val scheme = MaterialTheme.colorScheme
    val active = colorResource(R.color.overlay_active)
    return remember(scheme, active) { OverlayColors(LIGHT_WHITE, active, scheme.scrim) }
}

/** The light scheme's white (its `surfaceContainerLowest`), made once. */
private val LIGHT_WHITE = lightColorScheme().surfaceContainerLowest

/**
 * Content over the picture with the theme's scrim as its shadow (owner, 25 Nov 2026): a tight shadow around it (2.5 dp) and a wider one slightly below (5 dp,
 * 83 %), both blurred copies of the content itself, kept out of the semantics. [content] gets the colour to draw in for a shadow copy, or null for itself;
 * [alpha] dims the shadow with a dimmed content (a disabled control).
 */
@Composable
fun Shadowed(modifier: Modifier = Modifier, alpha: Float = 1f, content: @Composable (shadow: Color?) -> Unit) {
    val shadow = overlayColors().shadow
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.clearAndSetSemantics {}.blur(SHADOW_TIGHT, BlurredEdgeTreatment.Unbounded), contentAlignment = Alignment.Center) { content(shadow.copy(alpha = alpha)) }
        Box(Modifier.clearAndSetSemantics {}.offset(y = 1.dp).blur(SHADOW_WIDE, BlurredEdgeTreatment.Unbounded), contentAlignment = Alignment.Center) { content(shadow.copy(alpha = SHADOW_WIDE_ALPHA * alpha)) }
        content(null)
    }
}

private val SHADOW_TIGHT = 2.5.dp
private val SHADOW_WIDE = 5.dp
private const val SHADOW_WIDE_ALPHA = 0.83f
