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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.hnet.camera.Grid
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

/** The level is reached within this angle (SPEC §5.5). */
private const val LEVEL_DEGREES = 1.0

/** Flat from above (a document) when gravity is this close to the screen's axis: more than 60° from upright. */
private const val FLAT_Z = 0.866f

/**
 * The grid (3 × 3 or 4 × 4, single thin lines) and the level (SPEC §5.5, §7.4, in neutral double strokes) over the visible picture; the level turns `primary` and ticks once when
 * the horizon is within a degree. Held upright or sideways it is two fixed segments and a moving line; tilted flat (a document from above) a ring and a ball.
 */
@Composable
fun GridAndLevel(grid: Grid, tilt: Tilt?, rotation: Int, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val flat = tilt != null && abs(tilt.z) > FLAT_Z
    // The horizon's angle on the screen (clockwise, degrees): gravity's up in the screen's plane turned a quarter
    val angle = tilt?.let { Math.toDegrees(atan2(it.x.toDouble(), it.y.toDouble())) } ?: 0.0
    val reference = ((angle / 90).roundToInt() * 90).toDouble()
    fun within(degrees: Double) = tilt != null && if (flat) hypot(tilt.x.toDouble(), tilt.y.toDouble()) < sin(Math.toRadians(degrees)) else abs(angle - reference) < degrees
    // Level within a degree; it stops being level only past one and a half, so it doesn't tick on and off at the edge
    val wasLevel = remember { booleanArrayOf(false) }
    val level = if (wasLevel[0]) within(LEVEL_DEGREES * 1.5) else within(LEVEL_DEGREES)
    wasLevel[0] = level
    LaunchedEffect(level) { if (level) haptics.detent() }
    Canvas(modifier) {
        val lines = when (grid) {
            Grid.OFF -> 0
            Grid.THIRDS -> 3
            Grid.QUARTERS -> 4
        }
        for (i in 1 until lines) {
            val x = size.width * i / lines
            val y = size.height * i / lines
            // A single thin line in the outline's colour: the double stroke made the grid too heavy (owner, 23 Nov 2026); the level keeps it
            drawLine(colors.onSurface, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
            drawLine(colors.onSurface, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        }
        tilt ?: return@Canvas
        val center = Offset(size.width / 2, size.height / 2)
        if (flat) {
            val ring = 26.dp.toPx()
            val reach = 3 * ring
            // The ball rolls to the low side, as far as the tilt (a few degrees fill the ring)
            val ball = center + Offset((-tilt.x * reach * 8).coerceIn(-reach, reach), (tilt.y * reach * 8).coerceIn(-reach, reach))
            if (level) doubleStroke(colors.primary, colors.surface) { c, w -> drawCircle(c, ring, center, style = Stroke(w)) }
            else neutral(colors.surface, colors.onSurface, 2.dp.toPx()) { c, w -> drawCircle(c, ring, center, style = Stroke(w)) }
            drawCircle(colors.surface, 9.dp.toPx() + 1.25.dp.toPx(), ball)
            drawCircle(colors.primary, 9.dp.toPx(), ball)
        } else {
            val shown = if (level) reference else angle
            fun along(degrees: Double, distance: Float) = Offset((cos(Math.toRadians(degrees)) * distance).toFloat(), (sin(Math.toRadians(degrees)) * distance).toFloat())
            val half = 44.dp.toPx()
            val gap = 8.dp.toPx()
            val segment = 48.dp.toPx()
            val width = if (level) 2.5.dp.toPx() else 1.5.dp.toPx()
            val core = if (level) colors.primary else colors.surface
            val outline = if (level) colors.surface else colors.onSurface
            for (side in listOf(-1f, 1f)) {
                val from = center + along(reference, side * (half + gap))
                val to = center + along(reference, side * (half + gap + segment))
                neutral(core, outline, width) { c, w -> drawLine(c, from, to, w) }
            }
            neutral(core, outline, width) { c, w -> drawLine(c, center + along(shown, -half), center + along(shown, half), w) }
        }
    }
}

/** A double stroke of a given core width: the outline 1.25 dp wider on each side in [outline], then the core in [core]. */
private inline fun DrawScope.neutral(core: Color, outline: Color, width: Float, draw: DrawScope.(Color, Float) -> Unit) {
    draw(outline, width + 2.5.dp.toPx())
    draw(core, width)
}
