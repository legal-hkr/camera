package sh.hnet.camera.ui.viewfinder

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The shutter's looks of stage 1a (SPEC §5.1); burst, timer, long shot and recording come with their stages. */
enum class ShutterLook { PHOTO, VIDEO }

/**
 * ShutterButton (SPEC §5.1): 80 dp in `primary`, its shape carrying the state. Photo: a ring inside a circle, both turning to rounded squares while the finger is
 * down; video: a dot. Disabled in the component's own disabled colours.
 */
@Composable
fun ShutterButton(look: ShutterLook, label: String, onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    val down = pressed && enabled && look == ShutterLook.PHOTO
    val corner by animateDpAsState(if (down) 28.dp else 40.dp, spec, label = "shutter")
    val ringCorner by animateDpAsState(if (down) 22.dp else 32.dp, spec, label = "ring")
    val colors = ButtonDefaults.buttonColors()
    val container = if (enabled) colors.containerColor else colors.disabledContainerColor
    val content = if (enabled) colors.contentColor else colors.disabledContentColor
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(80.dp)
            .clip(RoundedCornerShape(corner))
            .background(container)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        when (look) {
            ShutterLook.PHOTO -> Box(Modifier.size(64.dp).border(3.dp, content, RoundedCornerShape(ringCorner)))
            ShutterLook.VIDEO -> Box(Modifier.size(26.dp).clip(CircleShape).background(content))
        }
    }
}
