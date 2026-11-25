package sh.hnet.camera.ui.viewfinder

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

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
