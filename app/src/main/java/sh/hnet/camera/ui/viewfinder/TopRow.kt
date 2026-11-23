@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.camera.ui.viewfinder

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import sh.hnet.camera.Grid
import sh.hnet.camera.R
import sh.hnet.camera.camera.Flash
import sh.hnet.camera.capabilities.model.Aspect
import sh.hnet.camera.ui.components.SettingsGroup
import sh.hnet.camera.ui.components.settingsRowColors

/** The angle the controls turn by (SPEC §18): against how the phone is held, the short way round, animated. */
@Composable
fun controlTurn(rotation: Int): Float {
    // Keeps turning the short way: from 270 to 0 it goes on to 360 rather than back through 180 (a plain array: no state written while composing)
    val last = remember { floatArrayOf(0f) }
    var next = -rotation.toFloat()
    while (next - last[0] > 180f) next -= 360f
    while (last[0] - next > 180f) next += 360f
    last[0] = next
    val angle by animateFloatAsState(next, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "turn")
    return angle
}

/**
 * A button of the top row (SPEC §4.3): a 40 dp circle of finder glass with a 20 dp icon, filled `secondaryContainer` when its value isn't the mode's default;
 * disabled in the component's disabled colour, a tap then explaining why. Long press shows its name.
 */
@Composable
fun TopButton(
    @DrawableRes icon: Int,
    label: String,
    state: String?,
    filled: Boolean,
    turn: Float,
    enabled: Boolean = true,
    onDisabled: () -> Unit = {},
    /** Stands out in amber (the automatic flash that will fire with the next photo; Calendar's amber, the owner's exception to the theme colours). */
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val disabled = IconButtonDefaults.iconButtonColors().disabledContentColor
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .then(
                    when {
                        accent && enabled -> Modifier.background(colorResource(R.color.flash_ready_fill), CircleShape)
                        filled && enabled -> Modifier.background(colors.secondaryContainer, CircleShape)
                        else -> Modifier.controlSurface(CircleShape, controlColor())
                    },
                )
                .clickable(role = Role.Button) { if (enabled) onClick() else onDisabled() }
                .semantics {
                    contentDescription = label
                    state?.let { stateDescription = it }
                },
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = if (!enabled) disabled else if (accent) colorResource(R.color.flash_ready_on) else if (filled) colors.onSecondaryContainer else colors.onSurface,
                modifier = Modifier.size(20.dp).rotate(turn),
            )
        }
    }
}

/** A text button of the top row ("4:3", "4K 30"): a pill 40 dp high, the text's width plus 22 dp, at least 48. */
@Composable
fun TopTextButton(text: String, label: String, filled: Boolean, turn: Float, stacked: List<String>? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .height(40.dp)
                .defaultMinSize(minWidth = 48.dp)
                .then(if (filled) Modifier.background(colors.secondaryContainer, CircleShape) else Modifier.controlSurface(CircleShape, controlColor()))
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label }
                .padding(horizontal = 11.dp),
        ) {
            val color = if (filled) colors.onSecondaryContainer else colors.onSurface
            if (stacked == null) {
                Text(text, style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.rotate(turn))
            } else {
                // The parts one over the other, as the phone is held
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.rotate(turn)) {
                    stacked.forEach { Text(it, style = MaterialTheme.typography.labelMedium, color = color, lineHeight = MaterialTheme.typography.labelMedium.fontSize) }
                }
            }
        }
    }
}

/** One choice of a top-row menu. */
class MenuChoice(val text: String, val selected: Boolean, val supporting: String? = null, val onClick: () -> Unit)

/** A top-row button with its menu below it (the family's menus: DropdownMenuPopup with groups); several groups split the choices (resolution, frame rate). */
@Composable
fun MenuButton(groups: List<List<MenuChoice>>, button: @Composable (open: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        button { open = true }
        DropdownMenuPopup(expanded = open, onDismissRequest = { open = false }) {
            groups.forEachIndexed { groupIndex, choices ->
                DropdownMenuGroup(shapes = MenuDefaults.groupShape(groupIndex, groups.size)) {
                    choices.forEachIndexed { index, choice ->
                        SelectableDropdownMenuItem(
                            selected = choice.selected,
                            onClick = {
                                open = false
                                choice.onClick()
                            },
                            text = { Text(choice.text, maxLines = 1) },
                            supportingText = choice.supporting?.let { { Text(it) } },
                            shapes = MenuDefaults.itemShape(index, choices.size),
                            trailingContent = { if (choice.selected) Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(20.dp)) },
                        )
                    }
                }
            }
        }
    }
}

@DrawableRes
fun flashIcon(flash: Flash) = when (flash) {
    Flash.OFF -> R.drawable.ic_flash_off
    Flash.AUTO -> R.drawable.ic_flash_auto
    Flash.ON -> R.drawable.ic_flash_on
}

@DrawableRes
fun timerIcon(seconds: Int) = when (seconds) {
    3 -> R.drawable.ic_timer_3_alt_1
    10 -> R.drawable.ic_timer_10_alt_1
    else -> R.drawable.ic_timer_off
}

@DrawableRes
fun gridIcon(grid: Grid) = when (grid) {
    Grid.OFF -> R.drawable.ic_grid_off
    Grid.THIRDS -> R.drawable.ic_grid_3x3
    Grid.QUARTERS -> R.drawable.ic_grid_4x4
}

fun aspectText(aspect: Aspect) = when (aspect) {
    Aspect.FOUR_THREE -> "4:3"
    Aspect.SIXTEEN_NINE -> "16:9"
    Aspect.SQUARE -> "1:1"
}

/** A row of the quick-settings panel (SPEC §7.12): the label, then its control at the end. */
@Composable
private fun PanelRow(shapes: ListItemShapes, title: String, control: @Composable () -> Unit) {
    SegmentedListItem(shapes = shapes, colors = settingsRowColors(), trailingContent = control) {
        Text(title)
    }
}

/** A row with a switch; the whole row toggles. */
@Composable
private fun PanelSwitchRow(shapes: ListItemShapes, title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SegmentedListItem(checked = checked, onCheckedChange = onChange, shapes = shapes, colors = settingsRowColors(), trailingContent = { Switch(checked = checked, onCheckedChange = null) }) {
        Text(title)
    }
}

/** Connected toggle buttons, 52 × 40 dp each, the chosen one selected (SPEC §7.12). */
@Composable
private fun <T> Toggles(values: List<T>, chosen: T, description: @Composable (T) -> String, onChoose: (T) -> Unit, content: @Composable (T) -> Unit) {
    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        values.forEachIndexed { index, value ->
            val label = description(value)
            ToggleButton(
                checked = value == chosen,
                onCheckedChange = { if (it) onChoose(value) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    values.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                contentPadding = ButtonDefaults.ExtraSmallContentPadding,
                // 52 dp each, wider where the text needs it ("1080p")
                modifier = Modifier.widthIn(min = 52.dp).height(40.dp).semantics {
                    role = Role.RadioButton
                    contentDescription = label
                },
            ) {
                content(value)
            }
        }
    }
}

/** What the quick-settings panel shows and changes; the values are the viewfinder's, one each. */
class PanelState(
    val video: Boolean,
    val flashAvailable: Boolean,
    val flash: Flash,
    val flashLevels: Int,
    val flashLevel: Int,
    val timer: Int,
    val aspect: Aspect,
    val grid: Grid,
    val level: Boolean,
    val hdr: Boolean,
    val hdrPossible: Boolean,
    val uhdAvailable: Boolean,
    val uhd: Boolean,
    val fpsChoices: List<Int>,
    val fps: Int,
    val torch: Boolean,
    val ultraHdrAvailable: Boolean,
    val ultraHdr: Boolean,
    val location: Boolean,
)

class PanelActions(
    val onFlash: (Flash) -> Unit,
    val onFlashLevel: (Int) -> Unit,
    val onTimer: (Int) -> Unit,
    val onAspect: (Aspect) -> Unit,
    val onGrid: (Grid) -> Unit,
    val onLevel: (Boolean) -> Unit,
    val onHdr: () -> Unit,
    val onUhd: (Boolean) -> Unit,
    val onFps: (Int) -> Unit,
    val onTorch: () -> Unit,
    val onUltraHdr: () -> Unit,
    val onLocation: () -> Unit,
    val onSettings: () -> Unit,
)

/** The quick-settings panel's content (SPEC §7.12): the mode's values, the most used lowest (nearest the thumb), Settings at the foot. */
@Composable
fun QuickSettings(state: PanelState, actions: PanelActions) {
    val numbers = rememberNumbers()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsGroup {
            // Every value of the top row is here too, within the thumb's reach (SPEC §2.1)
            row { PanelSwitchRow(it, stringResource(R.string.label_panel_location), state.location) { actions.onLocation() } }
            // The grid and the level show in both modes, so both panels set them
            row { PanelSwitchRow(it, stringResource(R.string.label_panel_level), state.level, actions.onLevel) }
            row { shapes ->
                PanelRow(shapes, stringResource(R.string.label_panel_grid)) {
                    Toggles(Grid.entries, state.grid, { stringResource(gridName(it)) }, actions.onGrid) { Icon(painterResource(gridIcon(it)), contentDescription = null, modifier = Modifier.size(20.dp)) }
                }
            }
            if (!state.video) {
                if (state.ultraHdrAvailable) row { PanelSwitchRow(it, stringResource(R.string.label_panel_ultra_hdr), state.ultraHdr) { actions.onUltraHdr() } }
                row { shapes ->
                    PanelRow(shapes, stringResource(R.string.label_panel_aspect_ratio)) {
                        Toggles(Aspect.entries.toList(), state.aspect, { aspectText(it) }, actions.onAspect) { Text(aspectText(it), maxLines = 1) }
                    }
                }
                row { shapes ->
                    PanelRow(shapes, stringResource(R.string.label_panel_timer)) {
                        Toggles(listOf(0, 3, 10), state.timer, { if (it == 0) stringResource(R.string.label_off) else stringResource(R.string.value_seconds, it) }, actions.onTimer) {
                            Text(if (it == 0) stringResource(R.string.label_off) else stringResource(R.string.value_seconds, it), maxLines = 1)
                        }
                    }
                }
                if (state.flashAvailable && state.flash == Flash.ON && state.flashLevels > 1) row { shapes ->
                    PanelRow(shapes, stringResource(R.string.label_panel_flash_strength)) {
                        val slider = remember(state.flashLevels) { SliderState(state.flashLevel.toFloat(), (state.flashLevels - 2).coerceAtLeast(0), 1f..state.flashLevels.toFloat()) }
                        // This overload hands the value over instead of setting it: the thumb moves only when it is set here
                        Slider(state = slider, onValueChange = {
                            slider.value = it
                            actions.onFlashLevel(it.roundToInt())
                        }, modifier = Modifier.width(160.dp))
                    }
                }
                if (state.flashAvailable) row { shapes ->
                    PanelRow(shapes, stringResource(R.string.label_panel_flash)) {
                        Toggles(Flash.entries, state.flash, { stringResource(flashName(it)) }, actions.onFlash) { Icon(painterResource(flashIcon(it)), contentDescription = null, modifier = Modifier.size(20.dp)) }
                    }
                }
            } else {
                row { PanelSwitchRow(it, stringResource(R.string.label_panel_hdr), state.hdr && state.hdrPossible) { actions.onHdr() } }
                if (state.uhdAvailable) row { shapes ->
                    PanelRow(shapes, stringResource(R.string.label_panel_resolution)) {
                        Toggles(listOf(true, false), state.uhd, { stringResource(if (it) R.string.menu_resolution_4k else R.string.menu_resolution_1080p) }, actions.onUhd) {
                            Text(stringResource(if (it) R.string.menu_resolution_4k else R.string.menu_resolution_1080p), maxLines = 1)
                        }
                    }
                }
                if (state.fpsChoices.size > 1) row { shapes ->
                    PanelRow(shapes, stringResource(R.string.label_panel_frame_rate)) {
                        Toggles(state.fpsChoices, state.fps, { stringResource(R.string.value_fps, it) }, actions.onFps) { Text(numbers.zoom(it.toDouble()), maxLines = 1) }
                    }
                }
                row { PanelSwitchRow(it, stringResource(R.string.label_panel_flashlight), state.torch) { actions.onTorch() } }
            }
        }
        FilledTonalButton(onClick = actions.onSettings, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Icon(painterResource(R.drawable.ic_settings), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.button_panel_settings))
        }
    }
}

fun flashName(flash: Flash) = when (flash) {
    Flash.OFF -> R.string.menu_flash_off
    Flash.AUTO -> R.string.menu_flash_auto
    Flash.ON -> R.string.menu_flash_on
}

fun gridName(grid: Grid) = when (grid) {
    Grid.OFF -> R.string.label_grid_off
    Grid.THIRDS -> R.string.label_grid_thirds
    Grid.QUARTERS -> R.string.label_grid_quarters
}
