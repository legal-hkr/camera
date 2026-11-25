@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package sh.hnet.camera.ui.viewfinder

import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow
import sh.hnet.camera.R
import sh.hnet.camera.camera.FrameAnalyzer
import sh.hnet.camera.capabilities.model.Point

/** Pro's values on its tiles (SPEC §11). */
enum class ProValue { ISO, SHUTTER, FOCUS, WHITE_BALANCE, EV }

/** How a Pro tile looks: its value automatic (white), set by hand (amber), or with its ruler open (amber, a bar under the value). */
enum class TileLook { AUTO, MANUAL, SELECTED }

/**
 * Pro's row of value tiles (SPEC §11, board 10): the key small above the value, over the picture without a surface and with a shadow (owner, 25 Nov 2026),
 * spread evenly over the width; then `restart_alt` (everything automatic). A tile without [onClick] only shows its value.
 */
@Composable
fun ProTiles(tiles: List<ProTile>, onReset: () -> Unit, turn: Float, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.tooltip_all_auto)
    val overlay = overlayColors()
    Row(modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        // On a narrow screen the tiles give way, the reset button keeps its size
        tiles.forEach { ProTileView(it, turn, Modifier.weight(1f, fill = false)) }
        Box(
            Modifier
                .size(48.dp)
                .clickable(interactionSource = null, indication = ripple(bounded = false, radius = 24.dp), role = Role.Button, onClick = onReset)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Shadowed(Modifier.rotate(turn)) { shadow -> Icon(painterResource(R.drawable.ic_restart_alt), contentDescription = null, tint = shadow ?: overlay.content, modifier = Modifier.size(22.dp)) }
        }
    }
}

/** One tile: [key] above [value], how it [look]s; [onClick] null: not to be opened; [name] the key in full where it is shortened ("WB"). */
class ProTile(val key: String, val value: String, val look: TileLook, val name: String? = null, val onClick: (() -> Unit)?)

@Composable
private fun ProTileView(tile: ProTile, turn: Float, modifier: Modifier = Modifier) {
    val overlay = overlayColors()
    val ink = if (tile.look == TileLook.AUTO) overlay.content else overlay.accent
    Box(
        modifier
            .size(58.dp, 52.dp)
            // The ripple fills the tile without cutting the text's shadow; the key and value read as one
            .then(tile.onClick?.let { Modifier.clickable(interactionSource = null, indication = ripple(bounded = false, radius = 30.dp), role = Role.Button, onClick = it) } ?: Modifier.semantics(mergeDescendants = true) {})
            .then(tile.name?.let { name -> Modifier.semantics { contentDescription = "$name, ${tile.value}" } } ?: Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Shadowed(Modifier.rotate(turn)) { shadow ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(tile.key, style = MaterialTheme.typography.labelSmall, color = shadow ?: ink.copy(alpha = if (tile.look == TileLook.AUTO) KEY_ALPHA else 1f), maxLines = 1)
                // A long value ("1/8000 s") shrinks to fit rather than lose its unit
                Text(
                    tile.value,
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    color = shadow ?: ink,
                    maxLines = 1,
                    softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize),
                )
                // The open one: a bar under its value
                Box(Modifier.padding(top = 2.dp).size(18.dp, 3.dp).clip(RoundedCornerShape(2.dp)).background(if (tile.look == TileLook.SELECTED) shadow ?: ink else Color.Transparent))
            }
        }
    }
}

/** The key of an automatic tile, a little softer than its value. */
private const val KEY_ALPHA = 0.85f

/**
 * A Pro ruler (SPEC §5.3): the "A" button (that one value automatic again) and a ruler of [count] steps, dragged directly; [index] the step shown (an automatic
 * value's nearest), [label] the steps labeled under their tick, [detent] the ones felt.
 */
@Composable
fun ProRuler(
    count: Int,
    index: Int,
    label: (Int) -> String?,
    detent: (Int) -> Boolean,
    onIndex: (Int) -> Unit,
    onAuto: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val haptics = rememberHaptics()
    val autoLabel = stringResource(R.string.tooltip_auto_one)
    // The drag reads the newest callbacks (another camera's steps may have the same count)
    val pick by rememberUpdatedState(onIndex)
    val felt by rememberUpdatedState(detent)
    val last = (count - 1).coerceAtLeast(0)
    val scale = remember(count) { LinearScale(0.0, last.toDouble(), STEP_DP) }
    val ticks = (0 until count).map { Tick(it.toDouble(), major = label(it) != null, label = label(it), detent = detent(it)) }
    var current by remember(count) { mutableIntStateOf(index.coerceIn(0, last)) }
    // An automatic value moving under the ruler moves it too, unless the finger has it or the value it set isn't back yet (no jump back after a drag)
    // Keyed as the gesture is: steps that change under a drag (a lens switched by the camera) end it, and with it the finger's hold
    var dragging by remember(count) { mutableStateOf(false) }
    var set by remember { mutableStateOf<Int?>(null) }
    if (set == index) set = null
    if (!dragging && set == null && current != index) current = index.coerceIn(0, last)
    var position by remember(count) { mutableFloatStateOf(scale.position(current.toDouble())) }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(40.dp)
                .controlSurface(CircleShape, controlColor())
                .clickable(role = Role.Button, onClick = onAuto)
                .semantics { contentDescription = autoLabel },
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.button_auto_short), style = MaterialTheme.typography.labelLarge)
        }
        ValueRuler(
            scale,
            current.toDouble(),
            ticks,
            Modifier
                .padding(start = 8.dp)
                .weight(1f)
                .pointerInput(count) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragging = true
                            position = scale.position(current.toDouble())
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, amount ->
                        position = (position - amount / density).coerceIn(scale.position(scale.min), scale.position(scale.max))
                        val next = scale.value(position).roundToInt()
                        if (next != current) {
                            if (felt(next)) haptics.detent() else haptics.step()
                            current = next
                            set = next
                            pick(next)
                        }
                        change.consume()
                    }
                },
        )
        // A switch of the ruler's own at its other end (the focus ruler's peaking)
        trailing?.let { Box(Modifier.padding(start = 8.dp)) { it() } }
    }
}

/**
 * Focus peaking on or off (owner, 25 Nov 2026), at the focus ruler's end opposite "A": on the controls' surface as "A", its icon amber while on (as every
 * control over the picture that is on).
 */
@Composable
fun PeakingSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    val label = stringResource(R.string.tooltip_focus_peaking)
    val overlay = overlayColors()
    Box(
        Modifier
            .size(40.dp)
            .controlSurface(CircleShape, controlColor())
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(if (on) R.drawable.ic_center_focus_strong else R.drawable.ic_center_focus_weak),
            contentDescription = null,
            tint = if (on) overlay.accent else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** The spacing of a Pro ruler's steps. */
private const val STEP_DP = 18f

/**
 * Pro's histogram (SPEC §11, §4.8): the picture's luma in [FrameAnalyzer.HISTOGRAM_BINS] bins as an area in `onSurfaceVariant` on a solid 112 × 56 dp plate,
 * a 4 dp `primary` bar at an end where the picture clips (more than 2 % of it in the end bin). Collected here: it changes five times a second.
 */
@Composable
fun HistogramPlate(histogram: StateFlow<IntArray?>, modifier: Modifier = Modifier) {
    val bins by histogram.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val surface = controlColor()
    Canvas(
        modifier
            .size(112.dp, 56.dp)
            .controlSurface(RoundedCornerShape(12.dp), surface)
            .clearAndSetSemantics {},
    ) {
        val values = bins ?: return@Canvas
        val total = values.sum().takeIf { it > 0 } ?: return@Canvas
        val inset = 8.dp.toPx()
        val width = size.width - 2 * inset
        val height = size.height - 2 * inset
        // The tallest bin but the clipped ends fills the plate: a picture clipping at black or white still shows its shape
        val peak = values.drop(1).dropLast(1).maxOrNull()?.coerceAtLeast(1) ?: 1
        val step = width / (values.size - 1)
        val path = Path().apply {
            moveTo(inset, inset + height)
            values.forEachIndexed { i, v -> lineTo(inset + i * step, inset + height - height * (v.toFloat() / peak).coerceAtMost(1f)) }
            lineTo(inset + width, inset + height)
            close()
        }
        drawPath(path, colors.onSurfaceVariant)
        val bar = 4.dp.toPx()
        if (values.first() > total * CLIP_SHARE) drawRect(colors.primary, topLeft = Offset(inset - bar / 2, inset), size = Size(bar, height))
        if (values.last() > total * CLIP_SHARE) drawRect(colors.primary, topLeft = Offset(inset + width - bar / 2, inset), size = Size(bar, height))
    }
}

/** The share of the picture in an end bin that counts as clipping. */
private const val CLIP_SHARE = 0.02f

/**
 * Focus peaking (SPEC §11, LOGIKA §27): the sharp edges the analysis found, in `primary` over the picture, their mask laid where the preview shows that part of
 * the frame ([toView]: a point of the analysis frame, as shares, to shares of the preview). Collected here: it changes ten times a second.
 */
@Composable
fun FocusPeaking(edges: StateFlow<FrameAnalyzer.Edges?>, toView: (Double, Double, FrameAnalyzer.Edges) -> Point, modifier: Modifier = Modifier) {
    val found by edges.collectAsStateWithLifecycle()
    val color = MaterialTheme.colorScheme.primary.toArgb()
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    val matrix = remember { Matrix() }
    Canvas(modifier.fillMaxSize().clearAndSetSemantics {}) {
        val mask = found ?: return@Canvas
        val w = mask.mask.width.toFloat()
        val h = mask.mask.height.toFloat()
        // Three corners fix the turn, the mirror and the scale
        val corners = listOf(0.0 to 0.0, 1.0 to 0.0, 0.0 to 1.0).map { (x, y) -> toView(x, y, mask) }
        val to = FloatArray(6) { i -> corners[i / 2].let { if (i % 2 == 0) (it.x * size.width).toFloat() else (it.y * size.height).toFloat() } }
        matrix.setPolyToPoly(floatArrayOf(0f, 0f, w, 0f, 0f, h), 0, to, 0, 3)
        paint.color = color
        drawIntoCanvas { it.nativeCanvas.drawBitmap(mask.mask, matrix, paint) }
    }
}
