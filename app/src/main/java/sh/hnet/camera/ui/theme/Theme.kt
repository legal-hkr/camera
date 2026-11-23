package sh.hnet.camera.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * System (dynamic) colors only, standard motion; minSdk 34 always has dynamic color. Always the dark scheme, whatever the system's (owner, 23 Nov 2026: a light
 * camera makes little sense; Settings stay dark too, not to flash light after the viewfinder).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CameraTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    MaterialExpressiveTheme(
        colorScheme = dynamicDarkColorScheme(context),
        motionScheme = MotionScheme.standard(),
        content = content,
    )
}
