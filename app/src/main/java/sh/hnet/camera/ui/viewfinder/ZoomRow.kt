@file:OptIn(ExperimentalMaterial3Api::class)

package sh.hnet.camera.ui.viewfinder

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.floor
import kotlinx.coroutines.delay
import sh.hnet.camera.R
import sh.hnet.camera.capabilities.model.ZoomScale

/** Values of the zoom ruler within this of a native stop snap to it (SPEC §5.2). */
private const val SNAP = 0.06

/** 1×–2× on the ruler, in dp; the whole 1×–5× of the reference phone then fits its width. */
private const val OCTAVE_DP = 120f

/** A hold this long on the stops opens the ruler. */
private const val HOLD_MS = 250L

/** The ruler folds back into the stops this long after the last touch. */
private const val COLLAPSE_MS = 2_000L

/**
 * The zoom row (SPEC §4.6, §5.2): ZoomControl in the middle – the ultrawide button and the group of native stops, turning into the zoom ruler when dragged – and
 * the quick-settings button at the end. [rulerOpen] is hoisted, as the value chip above the needle lives in the suggestion slot.
 */
@Composable
fun ZoomRow(
    scale: ZoomScale?,
    zoom: Double,
    rulerOpen: Boolean,
    onRulerOpen: (Boolean) -> Unit,
    /** A zoom value, and whether it is a jump (a stop tapped) rather than a slide. */
    onZoom: (Double, Boolean) -> Unit,
    onQuickSettings: () -> Unit,
    quickSettingsOpen: Boolean,
    modifier: Modifier = Modifier,
    /** Hidden while recording (SPEC §4.6). */
    showQuickSettings: Boolean = true,
    /** The numbers and the button turn with the phone (SPEC §18). */
    turn: Float = 0f,
) {
    val haptics = rememberHaptics()
    val numbers = rememberNumbers()
    val density = LocalDensity.current
    var groupBounds by remember { mutableStateOf(Rect.Zero) }
    var rowOrigin by remember { mutableStateOf(Offset.Zero) }
    var lastTouch by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    val currentZoom by rememberUpdatedState(zoom)
    val currentOpen by rememberUpdatedState(rulerOpen)

    // Folds back 2 s after the finger left, not while it holds the ruler still
    LaunchedEffect(rulerOpen, lastTouch, dragging) {
        if (!rulerOpen || dragging) return@LaunchedEffect
        delay(COLLAPSE_MS)
        onRulerOpen(false)
    }

    val group = scale?.group.orEmpty()
    val end = scale?.end ?: 1.0
    val start = group.firstOrNull()?.ratio ?: 1.0
    val ruler = remember(start, end) { LogScale(start, end, OCTAVE_DP) }
    val ticks = remember(scale) { zoomTicks(scale, numbers) }

    Box(modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
        // Only the control takes touches (the rest of the row is picture: a tap there focuses); open, the ruler spans the row
        Box(
            Modifier
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(if (currentOpen) constraints.copy(minWidth = constraints.maxWidth) else constraints.copy(minWidth = 0))
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .height(52.dp)
                .onGloballyPositioned { rowOrigin = it.positionInRoot() }
                .pointerInput(scale) {
                    if (scale == null || !scale.hasControl || group.size < 2) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!currentOpen && !groupBounds.contains(down.position)) return@awaitEachGesture
                        // A short hold opens the ruler under the still finger (owner, 23 Nov 2026: starting with a drag one way to move the other was awkward);
                        // a drag past the slop opens it too. A tap stays the stop's own.
                        var slop: PointerInputChange? = null
                        val decided = withTimeoutOrNull(HOLD_MS) {
                            slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                            true
                        }
                        val held = decided == null && currentEvent.changes.any { it.id == down.id && it.pressed }
                        if (!held && slop == null) return@awaitEachGesture
                        if (held) haptics.lock()
                        onRulerOpen(true)
                        dragging = true
                        var position = ruler.position(currentZoom.coerceAtLeast(start))
                        var shown = currentZoom
                        // Read before the stops do (the initial pass), so letting go after a hold doesn't also tap the stop under the finger
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            val dx = change.positionChange().x
                            if (dx != 0f) {
                                lastTouch = System.currentTimeMillis()
                                // The scale moves under the finger: a drag to the left brings larger values to the needle
                                position = (position - with(density) { dx.toDp().value }).coerceIn(ruler.position(start), ruler.position(end))
                                val raw = ruler.value(position)
                                val stop = group.firstOrNull { abs(it.ratio - raw) <= SNAP }
                                val next = stop?.ratio ?: raw
                                if (stop != null && abs(shown - stop.ratio) > 1e-6) haptics.detent()
                                else if (stop == null && floor(next) != floor(shown) && next >= 1) haptics.step()
                                if (next >= end && shown < end) haptics.end()
                                shown = next
                                onZoom(next, false)
                            }
                            change.consume()
                        }
                        dragging = false
                        lastTouch = System.currentTimeMillis()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (scale != null && scale.hasControl) {
                if (rulerOpen) {
                    ValueRuler(ruler, zoom.coerceAtLeast(start), ticks, Modifier.padding(horizontal = 16.dp), height = 52.dp)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        scale.ultrawide?.let { wide ->
                            val selected = zoom < start - 1e-6
                            val label = if (selected) stringResource(R.string.value_zoom, numbers.zoom(wide.ratio)) else numbers.zoom(wide.ratio)
                            ZoomPill(label, selected, stringResource(R.string.tooltip_ultrawide), Modifier.height(44.dp).defaultMinSize(minWidth = 44.dp).controlSurface(CircleShape, controlColor()), turn) {
                                haptics.detent()
                                onZoom(wide.ratio, true)
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(0.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .height(44.dp)
                                .controlSurface(CircleShape, controlColor())
                                .padding(4.dp)
                                .onGloballyPositioned { coordinates ->
                                    // The group's bounds in the row's box, for telling a drag on the stops from one elsewhere
                                    groupBounds = coordinates.boundsInRoot().translate(-rowOrigin)
                                },
                        ) {
                            group.forEachIndexed { index, stop ->
                                val next = group.getOrNull(index + 1)?.ratio ?: Double.MAX_VALUE
                                // The stop the value is on, or the nearest one below it, shows the value
                                val current = zoom >= stop.ratio - 1e-6 && zoom < next - 1e-6
                                val label = if (current) stringResource(R.string.value_zoom, numbers.zoom(zoom)) else numbers.zoom(stop.ratio)
                                ZoomPill(label, current, label, Modifier.height(36.dp).defaultMinSize(minWidth = 36.dp), turn) {
                                    haptics.detent()
                                    onZoom(stop.ratio, true)
                                }
                            }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = !rulerOpen && showQuickSettings,
            enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
            exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
        ) {
            GlassIconButton(R.drawable.ic_tune, stringResource(R.string.tooltip_quick_settings), selected = quickSettingsOpen, size = 44, iconSize = 22, turn = turn, onClick = onQuickSettings)
        }
    }
}

/** A stop of the zoom: the selected one in `primaryContainer` with "×", the others the bare number. */
@Composable
private fun ZoomPill(label: String, selected: Boolean, description: String, modifier: Modifier, turn: Float, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .background(if (selected) colors.primaryContainer else Color.Transparent, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 8.dp),
    ) {
        Text(
            label,
            style = if (selected) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
            color = if (selected) colors.onPrimaryContainer else colors.onSurface,
            modifier = Modifier.rotate(turn),
        )
    }
}

/** Ticks every 0.1× from the first stop to the end, whole numbers longer and labeled ("×" at the native stops), native stops as dots. */
private fun zoomTicks(scale: ZoomScale?, numbers: Numbers): List<Tick> {
    scale ?: return emptyList()
    val start = scale.group.firstOrNull()?.ratio ?: return emptyList()
    val stops = scale.group.map { it.ratio }
    val ticks = mutableListOf<Tick>()
    var tenths = (start * 10).toInt()
    while (tenths <= (scale.end * 10 + 1e-6).toInt()) {
        val value = tenths / 10.0
        val whole = tenths % 10 == 0
        val native = stops.any { abs(it - value) < 1e-6 }
        val label = when {
            native -> numbers.zoom(value) + "×"
            whole -> numbers.zoom(value)
            else -> null
        }
        ticks += Tick(value, major = whole || native, label = label, detent = native)
        tenths++
    }
    return ticks
}

/** A round icon button of finder glass (the quick-settings button), `secondaryContainer` while what it opens is open; long press shows its name. */
@Composable
fun GlassIconButton(icon: Int, label: String, selected: Boolean, size: Int, iconSize: Int, turn: Float = 0f, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size.dp)
                .then(if (selected) Modifier.background(colors.secondaryContainer, CircleShape) else Modifier.controlSurface(CircleShape, controlColor()))
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label },
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = if (selected) colors.onSecondaryContainer else colors.onSurface, modifier = Modifier.width(iconSize.dp).height(iconSize.dp).rotate(turn))
        }
    }
}
