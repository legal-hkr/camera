package sh.hnet.camera

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import sh.hnet.camera.about.WhatsNew
import sh.hnet.camera.ui.CameraApp
import sh.hnet.camera.ui.Launch
import sh.hnet.camera.ui.theme.CameraTheme
import sh.hnet.camera.ui.viewfinder.Entry

/**
 * The app (LOGIKA §11): the viewfinder and Settings; also the system's still and video camera actions and the shortcuts, each new one starting the viewfinder
 * as it asks; Settings opened from the lock screen once the phone is unlocked.
 */
class MainActivity : ViewfinderActivity() {
    private var launch by mutableStateOf(Launch(Entry.App()))
    private var launches = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) WhatsNew.recordStart(this)
        // The same way in again after a recreation (a language change): its start isn't applied twice
        launches = savedInstanceState?.getLong(LAUNCHES) ?: 1L
        launch = launchOf(intent)
        setContent {
            CameraTheme {
                CameraApp(launch)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launches++
        launch = launchOf(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(LAUNCHES, launches)
    }

    private fun launchOf(intent: Intent?) = Launch(Entry.app(intent, launches), settings = intent?.getBooleanExtra(EXTRA_SETTINGS, false) == true)

    companion object {
        /** Opens Settings (from the lock screen, after unlocking). */
        const val EXTRA_SETTINGS = "sh.hnet.camera.SETTINGS"
        private const val LAUNCHES = "launches"
    }
}
