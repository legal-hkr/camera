package sh.hnet.camera

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import sh.hnet.camera.about.WhatsNew
import sh.hnet.camera.ui.CameraApp
import sh.hnet.camera.ui.theme.CameraTheme
import sh.hnet.camera.ui.viewfinder.HardwareKeys

class MainActivity : ComponentActivity() {
    // The volume keys go to the viewfinder first (SPEC §7.9)
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = HardwareKeys.dispatch(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = HardwareKeys.dispatch(event) || super.onKeyUp(keyCode, event)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) WhatsNew.recordStart(this)
        setContent {
            CameraTheme {
                CameraApp()
            }
        }
    }
}
