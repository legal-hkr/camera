@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package sh.hnet.camera.ui.viewfinder

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import sh.hnet.camera.R
import sh.hnet.camera.ui.components.RowIcon
import sh.hnet.camera.ui.components.SettingsGroup
import sh.hnet.camera.ui.components.settingsRowColors

/** Pro's values on its tiles (SPEC §11). */
enum class ProValue { ISO, SHUTTER, FOCUS, WHITE_BALANCE, EV }

/** How a Pro tile looks: its value automatic (glass), set by hand (`primaryContainer`), or with its ruler open (`primary`). */
enum class TileLook { AUTO, MANUAL, SELECTED }

/**
 * Pro's row of value tiles (SPEC §11, board 10): 54 × 48 dp, the key small above the value; then `restart_alt` (everything automatic). A tile without [onClick]
 * only shows its value.
 */
@Composable
fun ProTiles(tiles: List<ProTile>, onReset: () -> Unit, turn: Float, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.tooltip_all_auto)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        tiles.forEach { ProTileView(it, turn) }
        Box(
            Modifier
                .padding(start = 2.dp)
                .size(40.dp)
                .controlSurface(CircleShape, controlColor())
                .clickable(role = Role.Button, onClick = onReset)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_restart_alt), contentDescription = null, modifier = Modifier.size(22.dp).rotate(turn))
        }
    }
}

/** One tile: [key] above [value], how it [look]s; [onClick] null: not to be opened. */
class ProTile(val key: String, val value: String, val look: TileLook, val onClick: (() -> Unit)?)

@Composable
private fun ProTileView(tile: ProTile, turn: Float) {
    val colors = MaterialTheme.colorScheme
    val (container, content, keyColor) = when (tile.look) {
        TileLook.AUTO -> Triple(controlColor(), colors.onSurface, colors.onSurfaceVariant)
        TileLook.MANUAL -> Triple(colors.primaryContainer, colors.onPrimaryContainer, colors.onPrimaryContainer)
        TileLook.SELECTED -> Triple(colors.primary, colors.onPrimary, colors.onPrimary)
    }
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .size(54.dp, 48.dp)
            .controlSurface(shape, container)
            .then(tile.onClick?.let { Modifier.clickable(role = Role.Button, onClick = it) } ?: Modifier)
            .rotate(turn),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(tile.key, style = MaterialTheme.typography.labelSmall, color = keyColor, maxLines = 1)
        Text(tile.value, style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"), color = content, maxLines = 1)
    }
}

/**
 * A Pro ruler (SPEC §5.3): the "A" button (that one value automatic again) and a ruler of [count] steps, dragged directly; [index] the step shown (an automatic
 * value's nearest), [label] the steps labelled under their tick, [detent] the ones felt.
 */
@Composable
fun ProRuler(count: Int, index: Int, label: (Int) -> String?, detent: (Int) -> Boolean, onIndex: (Int) -> Unit, onAuto: () -> Unit, modifier: Modifier = Modifier) {
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
    var dragging by remember { mutableStateOf(false) }
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
    }
}

/** A mode of the "More modes" sheet: its icon, name and a line about it. */
class MoreMode<T>(val mode: T, @DrawableRes val icon: Int, val name: String, val description: String)

/** The "More modes" sheet (SPEC §6.2): the modes not in the row, as the family's segmented rows. */
@Composable
fun <T> MoreModesSheet(modes: List<MoreMode<T>>, onChoose: (T) -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.title_more_modes), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))
            SettingsGroup {
                modes.forEach { more ->
                    row { shapes ->
                        SegmentedListItem(
                            onClick = { onChoose(more.mode) },
                            shapes = shapes,
                            colors = settingsRowColors(),
                            leadingContent = { RowIcon(more.icon) },
                            supportingContent = { Text(more.description) },
                        ) {
                            Text(more.name)
                        }
                    }
                }
            }
        }
    }
}

/** The spacing of a Pro ruler's steps. */
private const val STEP_DP = 18f
