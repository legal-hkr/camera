package sh.hnet.camera.ui.viewfinder

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ln
import kotlin.math.pow

/** A mark of a ruler: a tick, longer when [major], with an optional label under it; a [detent] (a native lens, the zero) is also a dot. */
data class Tick(val value: Double, val major: Boolean, val label: String? = null, val detent: Boolean = false)

/** How values sit along a ruler: their position in dp from an origin, and back. */
interface RulerScale {
    val min: Double
    val max: Double
    fun position(value: Double): Float
    fun value(position: Float): Double
}

/** Zoom: logarithmic, so 1×–2× is as wide as 2×–4× (SPEC §5.2). */
class LogScale(override val min: Double, override val max: Double, private val octave: Float) : RulerScale {
    override fun position(value: Double) = (ln(value) / ln(2.0)).toFloat() * octave
    override fun value(position: Float) = 2.0.pow((position / octave).toDouble()).coerceIn(min, max)
}

/** Exposure and the like: even steps. */
class LinearScale(override val min: Double, override val max: Double, private val perUnit: Float) : RulerScale {
    override fun position(value: Double) = value.toFloat() * perUnit
    override fun value(position: Float) = (position / perUnit).toDouble().coerceIn(min, max)
}

/**
 * ValueRuler (SPEC §5.3): a pill of finder glass with a fixed needle in the middle and the scale moving under it. Only drawn here; the caller moves the value
 * (the zoom row takes over a drag that started on its stops, the exposure ruler drags itself).
 */
@Composable
fun ValueRuler(scale: RulerScale, value: Double, ticks: List<Tick>, modifier: Modifier = Modifier, height: Dp = 48.dp) {
    val colors = MaterialTheme.colorScheme
    val glass = glassColor()
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = colors.onSurfaceVariant)
    Box(modifier.fillMaxWidth().height(height).finderGlass(CircleShape, glass)) {
        Canvas(Modifier.fillMaxSize()) {
            val center = size.width / 2
            val now = scale.position(value)
            val minorTop = size.height * 0.22f
            clipRect {
                for (tick in ticks) {
                    val x = center + (scale.position(tick.value) - now).dp.toPx()
                    if (x < -20 || x > size.width + 20) continue
                    val top = if (tick.major) size.height * 0.16f else minorTop
                    val length = if (tick.major) 12.dp.toPx() else 8.dp.toPx()
                    val width = if (tick.major) 1.6.dp.toPx() else 1.2.dp.toPx()
                    drawLine(if (tick.major) colors.onSurface else colors.onSurfaceVariant, Offset(x, top), Offset(x, top + length), width)
                    if (tick.detent) drawCircle(colors.primary, 5.dp.toPx(), Offset(x, top + length + 6.dp.toPx()))
                    tick.label?.let { text ->
                        val layout = measurer.measure(text, labelStyle)
                        drawText(layout, topLeft = Offset(x - layout.size.width / 2f, size.height - layout.size.height - 4.dp.toPx()))
                    }
                }
            }
            // The needle: 4 dp wide, the ruler's height less 12 dp
            val needle = Size(4.dp.toPx(), size.height - 12.dp.toPx())
            drawRoundRect(colors.primary, Offset(center - needle.width / 2, 6.dp.toPx()), needle, CornerRadius(2.dp.toPx()))
        }
    }
}
