package sh.hnet.camera.ui.viewfinder

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** A swipe of this many dp across the picture changes the mode by one (SPEC §4.10); the row itself slides. */
const val MODE_SWIPE_DP = 48

/**
 * ModeRow (SPEC §5.4): pills of the modes the phone can do, the selected one in `secondaryContainer` and always in the middle; the row's ends are cut by the
 * screen's edge. A tap selects; the row is a slider (owner, 25 Nov 2026): it follows the finger, the mode under the middle lit as it passes (with a tick),
 * and on release it settles on the nearest – a fling carries it further – and that mode is selected at once, however far. A mode that isn't [slidable]
 * ("More", which opens a sheet) is only tapped. The row gives the ticks of its changes itself.
 */
@Composable
fun <T> ModeRow(modes: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier, slidable: (T) -> Boolean = { true }) {
    val colors = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val index = modes.indexOf(selected).coerceAtLeast(0)
    // The gesture outlives these (a camera opening or a mode changing under the finger must not cut it off)
    val currentModes by rememberUpdatedState(modes)
    val currentIndex by rememberUpdatedState(index)
    val select by rememberUpdatedState(onSelect)
    val canSlide by rememberUpdatedState(slidable)
    // The row slides so the selected pill is in the middle; the target is found while measuring
    val shift = remember { Animatable(Float.NaN) }
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    var target by remember { mutableFloatStateOf(Float.NaN) }
    LaunchedEffect(target) {
        if (target.isNaN()) return@LaunchedEffect
        if (shift.value.isNaN()) shift.snapTo(target) else shift.animateTo(target, spec)
    }
    // While the finger has it: the row's offset, and the mode under the middle
    var dragged by remember { mutableStateOf<Float?>(null) }
    var hover by remember { mutableIntStateOf(-1) }
    // Read by the composition (the lit pill) instead of the offset, which changes with every move and is read only while placing
    var dragging by remember { mutableStateOf(false) }
    // The pills' centres and the row's width from the last measuring (read by the drag, not by the composition)
    val centres = remember { arrayOf(FloatArray(0)) }
    val width = remember { floatArrayOf(0f) }
    fun offsetFor(i: Int) = width[0] / 2 - centres[0][i]
    fun slidables() = currentModes.indices.filter { canSlide(currentModes[it]) && it < centres[0].size }
    fun nearest(offset: Float): Int = slidables().minByOrNull { kotlin.math.abs(width[0] / 2 - offset - centres[0][it]) } ?: currentIndex
    val shown = if (dragging && hover >= 0) hover else index
    Layout(
        content = {
            modes.forEachIndexed { i, mode ->
                val isSelected = i == shown
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(36.dp)
                        .background(if (isSelected) colors.secondaryContainer else Color.Transparent, CircleShape)
                        .selectable(selected = mode == selected, role = Role.Tab, onClick = {
                            if (mode != selected && slidable(mode)) haptics.mode()
                            onSelect(mode)
                        })
                        .padding(horizontal = 14.dp),
                ) {
                    Text(label(mode), style = MaterialTheme.typography.labelLarge, color = if (isSelected) colors.onSecondaryContainer else colors.onSurfaceVariant, maxLines = 1, softWrap = false)
                }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .clipToBounds()
            .selectableGroup()
            .pointerInput(Unit) {
                val tracker = VelocityTracker()
                // The row ends in the middle of a mode whatever happens: the chosen one if it is taken (its own move to the middle then takes over), else
                // the one selected still (a mode the view model refused)
                fun settle(from: Float, to: Int) {
                    val hovered = hover
                    dragged = null
                    hover = -1
                    dragging = false
                    scope.launch {
                        shift.snapTo(from)
                        shift.animateTo(offsetFor(currentIndex), spec)
                    }
                    if (to != currentIndex) {
                        // The tick was given when the mode lit up; again only if a fling carried the row past it
                        if (to != hovered) haptics.mode()
                        select(currentModes[to])
                    }
                }
                detectHorizontalDragGestures(
                    onDragStart = {
                        tracker.resetTracking()
                        dragged = if (shift.value.isNaN()) offsetFor(currentIndex) else shift.value
                        hover = currentIndex
                        dragging = true
                    },
                    // A fling carries the row on about a fifth of a second
                    onDragEnd = { dragged?.let { from -> settle(from, nearest(from + tracker.calculateVelocity().x * FLING_S)) } },
                    onDragCancel = { dragged?.let { from -> settle(from, currentIndex) } },
                ) { change, amount ->
                    tracker.addPosition(change.uptimeMillis, change.position)
                    val slidables = slidables()
                    if (slidables.isEmpty()) return@detectHorizontalDragGestures
                    // As far as the first and the last mode, and a little past them
                    val edge = EDGE_DP.dp.toPx()
                    val now = ((dragged ?: 0f) + amount).coerceIn(offsetFor(slidables.last()) - edge, offsetFor(slidables.first()) + edge)
                    dragged = now
                    val under = nearest(now)
                    if (under != hover) {
                        hover = under
                        haptics.mode()
                    }
                    change.consume()
                }
            },
    ) { measurables, constraints ->
        val gap = 2.dp.roundToPx()
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, maxWidth = Int.MAX_VALUE)) }
        val starts = placeables.runningFold(0) { x, p -> x + p.width + gap }
        centres[0] = FloatArray(placeables.size) { starts[it] + placeables[it].width / 2f }
        width[0] = constraints.maxWidth.toFloat()
        val wanted = constraints.maxWidth / 2f - (starts[index] + placeables[index].width / 2f)
        if (target != wanted) target = wanted
        layout(constraints.maxWidth, placeables.maxOf { it.height }) {
            // Read while placing only: a move of the finger places the pills again, nothing more
            val offset = (dragged ?: if (shift.value.isNaN()) wanted else shift.value).roundToInt()
            placeables.forEachIndexed { i, p -> p.place(offset + starts[i], 0) }
        }
    }
}

/** How far a fling carries the row: its speed times this many seconds. */
private const val FLING_S = 0.18f

/** How far past the first and the last mode the row can be pulled. */
private const val EDGE_DP = 24
