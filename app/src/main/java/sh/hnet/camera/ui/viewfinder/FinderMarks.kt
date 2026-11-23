package sh.hnet.camera.ui.viewfinder

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.hnet.camera.R
import sh.hnet.camera.camera.FaceMark
import sh.hnet.camera.capabilities.model.Point

/** The focus mark fades this long after the camera has focused (a locked one stays). */
private const val FOCUS_FADE_AFTER_MS = 2_000L

/**
 * FinderMarks of stage 1a (SPEC §5.5) in the picture's box: the focus point with the exposure value next to it and the lock under it, and the face the camera
 * focuses on. Lines are double strokes – a core in one role inside an outline in the opposite one – so they read on any picture in both themes (SPEC §4.9).
 */
@Composable
fun FinderMarks(focus: Point?, focusing: Boolean, locked: Boolean, exposure: String?, face: FaceMark?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val alpha = remember { Animatable(1f) }
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(focus, focusing, locked, exposure) {
        alpha.snapTo(1f)
        if (focus == null || locked || focusing || exposure != null) return@LaunchedEffect
        delay(FOCUS_FADE_AFTER_MS)
        alpha.animateTo(0f, tween(300))
    }
    LaunchedEffect(focusing) {
        if (focusing) pulse.animateTo(0.9f, infiniteRepeatable(tween(400), RepeatMode.Reverse)) else pulse.animateTo(1f)
    }
    // The face mark glides to a new detection instead of jumping; a face that appears is drawn where it is
    val faceSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val corners = remember { List(4) { Animatable(0f) } }
    val faceShown = remember { booleanArrayOf(false) }
    LaunchedEffect(face) {
        val target = face ?: run {
            faceShown[0] = false
            return@LaunchedEffect
        }
        val values = listOf(target.topLeft.x, target.topLeft.y, target.bottomRight.x, target.bottomRight.y).map { it.toFloat() }
        if (!faceShown[0]) {
            corners.forEachIndexed { i, a -> a.snapTo(values[i]) }
            faceShown[0] = true
        } else {
            corners.forEachIndexed { i, a -> launch { a.animateTo(values[i], faceSpec) } }
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        Canvas(Modifier.fillMaxSize()) {
            if (face != null) {
                val (left, top, right, bottom) = corners.map { it.value }
                val topLeft = Offset(left * width, top * height)
                val size = Size((right - left) * width, (bottom - top) * height)
                doubleStroke(colors.primary, colors.surface) { core, w -> drawRoundRect(core, topLeft, size, CornerRadius(14.dp.toPx()), style = Stroke(w)) }
            }
            focus?.let {
                val center = Offset(it.x.toFloat() * width, it.y.toFloat() * height)
                val radius = 34.dp.toPx() * pulse.value
                doubleStroke(colors.primary.copy(alpha = alpha.value), colors.surface.copy(alpha = alpha.value)) { core, w -> drawCircle(core, radius, center, style = Stroke(w)) }
                drawCircle(colors.surface.copy(alpha = alpha.value), 1.5.dp.toPx() + 1.25.dp.toPx(), center)
                drawCircle(colors.primary.copy(alpha = alpha.value), 1.5.dp.toPx(), center)
            }
        }
        if (focus != null) {
            val x = (focus.x * width).roundToInt()
            val y = (focus.y * height).roundToInt()
            // The exposure chip 8 dp right of the circle, the lock 10 dp under it
            exposure?.let { value ->
                MarkChip(
                    Modifier.offset { IntOffset(x + (34 + 8).dp.roundToPx(), y - 14.dp.roundToPx()) },
                    glass = true,
                ) {
                    Icon(painterResource(R.drawable.ic_light_mode), contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(value, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (locked) {
                MarkChip(Modifier.offset { IntOffset(x - 14.dp.roundToPx(), y + (34 + 10).dp.roundToPx()) }, glass = false) {
                    Icon(painterResource(R.drawable.ic_lock), contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** A double stroke: the outline 1.25 dp wider on each side in [outline], then the 2 dp core in [core]. */
private inline fun DrawScope.doubleStroke(core: Color, outline: Color, draw: DrawScope.(Color, Float) -> Unit) {
    draw(outline, 2.dp.toPx() + 2.5.dp.toPx())
    draw(core, 2.dp.toPx())
}

/** A 28 dp chip on the picture: finder glass, or solid `primaryContainer` for the lock. */
@Composable
private fun MarkChip(modifier: Modifier, glass: Boolean, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.height(28.dp).then(if (glass) Modifier.finderGlass(CircleShape, glassColor()) else Modifier.finderGlass(CircleShape, colors.primaryContainer)), contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = if (glass) 10.dp else 6.dp)) {
            CompositionLocalProvider(LocalContentColor provides if (glass) colors.onSurface else colors.onPrimaryContainer) { content() }
        }
    }
}
