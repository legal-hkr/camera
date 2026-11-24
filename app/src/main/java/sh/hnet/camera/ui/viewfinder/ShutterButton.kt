@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.camera.ui.viewfinder

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.hnet.camera.R

/** The shutter's looks (SPEC §5.1); burst and long shot come with their modes. */
enum class ShutterLook { PHOTO, VIDEO, RECORDING, TIMER, NIGHT }

/**
 * ShutterButton (SPEC §5.1): 80 dp in `primary`, its shape carrying the state. Photo: a ring inside a circle, both turning to rounded squares while the finger is
 * down; video: a dot. Disabled in the component's own disabled colours.
 */
@Composable
fun ShutterButton(look: ShutterLook, label: String, onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier, progress: Float? = null, turn: Float = 0f) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    val down = pressed && enabled && look == ShutterLook.PHOTO
    val corner by animateDpAsState(if (down || look == ShutterLook.RECORDING) 28.dp else 40.dp, spec, label = "shutter")
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
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        when (look) {
            ShutterLook.PHOTO -> Box(Modifier.size(64.dp).border(3.dp, content, RoundedCornerShape(ringCorner)))
            ShutterLook.VIDEO -> Box(Modifier.size(26.dp).clip(CircleShape).background(content))
            ShutterLook.RECORDING -> Box(Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)).background(content))
            ShutterLook.TIMER -> Icon(painterResource(R.drawable.ic_close), contentDescription = null, tint = content, modifier = Modifier.size(28.dp).rotate(turn))
            ShutterLook.NIGHT -> Icon(painterResource(R.drawable.ic_bedtime), contentDescription = null, tint = content, modifier = Modifier.size(28.dp).rotate(turn))
        }
    }
    }
}
