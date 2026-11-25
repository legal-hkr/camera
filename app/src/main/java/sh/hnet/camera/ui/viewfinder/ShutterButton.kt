@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.camera.ui.viewfinder

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.hnet.camera.R

/** The shutter's looks (SPEC §5.1); the long shot comes with its mode. */
enum class ShutterLook { PHOTO, VIDEO, RECORDING, TIMER, NIGHT, BURST }

/**
 * ShutterButton (SPEC §5.1): 80 dp in `primary`, its shape carrying the state. Photo: a ring inside a circle, both turning to rounded squares while the finger is
 * down; video: a dot; a burst: a rounded square counting its frames [count] (read where it is drawn: it changes with every frame). Disabled in the component's
 * own disabled colors.
 *
 * [onHold]: held past the long-press time, the shutter calls it and [onRelease] when let go (a burst, LOGIKA §18); a shorter touch is [onClick]. Without
 * [onHold] any touch is a click on release, as before.
 */
@Composable
fun ShutterButton(
    look: ShutterLook,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    turn: Float = 0f,
    count: () -> Int? = { null },
    onHold: (() -> Unit)? = null,
    onRelease: () -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val click by rememberUpdatedState(onClick)
    val hold by rememberUpdatedState(onHold)
    val release by rememberUpdatedState(onRelease)
    val pressed by interaction.collectIsPressedAsState()
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    val down = pressed && enabled && look == ShutterLook.PHOTO
    val corner by animateDpAsState(if (down || look == ShutterLook.RECORDING || look == ShutterLook.BURST) 28.dp else 40.dp, spec, label = "shutter")
    val ringCorner by animateDpAsState(if (down) 22.dp else 32.dp, spec, label = "ring")
    val colors = ButtonDefaults.buttonColors()
    val container = if (enabled) colors.containerColor else colors.disabledContainerColor
    val content = if (enabled) colors.contentColor else colors.disabledContentColor
    // The ring's room, whatever the row gives (a squeezed box would draw the ring off-centre)
    Box(modifier.requiredSize(92.dp), contentAlignment = Alignment.Center) {
    // The timer: a wavy ring of the countdown around a smaller circle (SPEC §5.1)
    if (look == ShutterLook.TIMER) {
        CircularWavyProgressIndicator(progress = { progress ?: 0f }, modifier = Modifier.size(92.dp), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.secondaryContainer)
    }
    // A Night photo gathering its frames: the extension's progress where it gives one, else a ring that just turns (SPEC §9.2)
    if (look == ShutterLook.NIGHT) {
        val value = progress
        if (value != null) CircularWavyProgressIndicator(progress = { value }, modifier = Modifier.size(92.dp), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.secondaryContainer)
        else CircularWavyProgressIndicator(modifier = Modifier.size(92.dp), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.secondaryContainer)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(if (look == ShutterLook.TIMER || look == ShutterLook.NIGHT) 64.dp else 80.dp)
            .clip(RoundedCornerShape(corner))
            .background(container)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val first = awaitFirstDown()
                    val press = PressInteraction.Press(first.position)
                    interaction.tryEmit(press)
                    // A gesture cut off (the shutter disabled, the screen going) ends as cancelled, a hold with its release
                    var outcome: PressInteraction = PressInteraction.Cancel(press)
                    var holding = false
                    try {
                        val onHoldNow = hold
                        if (onHoldNow == null) {
                            if (waitForUpOrCancellation() != null) {
                                outcome = PressInteraction.Release(press)
                                click()
                            }
                        } else {
                            var timedOut = true
                            val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation().also { timedOut = false } }
                            if (!timedOut) {
                                if (up != null) {
                                    outcome = PressInteraction.Release(press)
                                    click()
                                }
                            } else {
                                // Held: a burst until the finger is lifted, wherever it slid to
                                holding = true
                                onHoldNow()
                                do {
                                    val event = awaitPointerEvent()
                                } while (event.changes.any { it.pressed })
                                outcome = PressInteraction.Release(press)
                            }
                        }
                    } finally {
                        interaction.tryEmit(outcome)
                        if (holding) release()
                    }
                }
            }
            .semantics {
                role = Role.Button
                contentDescription = label
                if (enabled) onClick { click(); true } else disabled()
            },
    ) {
        when (look) {
            ShutterLook.PHOTO -> Box(Modifier.size(64.dp).border(3.dp, content, RoundedCornerShape(ringCorner)))
            ShutterLook.VIDEO -> Box(Modifier.size(26.dp).clip(CircleShape).background(content))
            ShutterLook.RECORDING -> Box(Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)).background(content))
            ShutterLook.TIMER -> Icon(painterResource(R.drawable.ic_close), contentDescription = null, tint = content, modifier = Modifier.size(28.dp).rotate(turn))
            ShutterLook.NIGHT -> Icon(painterResource(R.drawable.ic_bedtime), contentDescription = null, tint = content, modifier = Modifier.size(28.dp).rotate(turn))
            ShutterLook.BURST -> Text("${count() ?: 0}", color = content, style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), modifier = Modifier.rotate(turn))
        }
    }
    }
}
