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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** A drag of this many dp along the row or across the picture changes the mode by one (SPEC §4.10). */
const val MODE_SWIPE_DP = 48

/**
 * ModeRow (SPEC §5.4): pills of the modes the phone can do, the selected one in `secondaryContainer` and always in the middle; the row's ends are cut by the
 * screen's edge. A tap selects, a drag along the row moves one mode at a time.
 */
@Composable
fun <T> ModeRow(modes: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val index = modes.indexOf(selected).coerceAtLeast(0)
    // The row slides so the selected pill is in the middle; the target is found while measuring
    val shift = remember { Animatable(Float.NaN) }
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    var target by remember { mutableFloatStateOf(Float.NaN) }
    LaunchedEffect(target) {
        if (target.isNaN()) return@LaunchedEffect
        if (shift.value.isNaN()) shift.snapTo(target) else shift.animateTo(target, spec)
    }
    Layout(
        content = {
            modes.forEach { mode ->
                val isSelected = mode == selected
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(36.dp)
                        .background(if (isSelected) colors.secondaryContainer else Color.Transparent, CircleShape)
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(mode) })
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
            .pointerInput(modes, index) {
                var travel = 0f
                detectHorizontalDragGestures(onDragStart = { travel = 0f }) { change, amount ->
                    travel += amount
                    val step = MODE_SWIPE_DP.dp.toPx()
                    if (travel <= -step && index < modes.lastIndex) {
                        travel = 0f
                        onSelect(modes[index + 1])
                    } else if (travel >= step && index > 0) {
                        travel = 0f
                        onSelect(modes[index - 1])
                    }
                    change.consume()
                }
            },
    ) { measurables, constraints ->
        val gap = 2.dp.roundToPx()
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, maxWidth = Int.MAX_VALUE)) }
        val starts = placeables.runningFold(0) { x, p -> x + p.width + gap }
        val center = starts[index] + placeables[index].width / 2f
        val wanted = constraints.maxWidth / 2f - center
        if (target != wanted) target = wanted
        val offset = (if (shift.value.isNaN()) wanted else shift.value).roundToInt()
        layout(constraints.maxWidth, placeables.maxOf { it.height }) {
            placeables.forEachIndexed { i, p -> p.place(offset + starts[i], 0) }
        }
    }
}
