package sh.hnet.camera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import sh.hnet.camera.ui.theme.CameraTheme
import sh.hnet.camera.ui.viewfinder.Entry
import sh.hnet.camera.ui.viewfinder.ViewfinderScreen

/**
 * A photo or a video for another app (SPEC §15.3, LOGIKA §11): `IMAGE_CAPTURE`, `IMAGE_CAPTURE_SECURE` (over the lock screen) and `VIDEO_CAPTURE` – one mode,
 * Review, the result back to the app. Cancel and Back leave without one.
 */
class CaptureActivity : ViewfinderActivity() {
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = finish()
    }
    private var watchingScreen = false

    override fun onDestroy() {
        if (watchingScreen) unregisterReceiver(screenOff)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val entry = Entry.capture(intent, callerName())
        setResult(RESULT_CANCELED)
        // An address this app mustn't write for someone else: no shot at all (LOGIKA §11)
        if (entry.output?.let { (application as CameraApplication).captures.writable(it) } == false) {
            finish()
            return
        }
        if (entry.secure) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            // Over the lock screen only while it is on, as the lock screen's camera
            ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
            watchingScreen = true
        }
        setContent {
            CameraTheme {
                ViewfinderScreen(
                    entry,
                    onOpenSettings = null,
                    onCancel = { finish() },
                    onResult = { result ->
                        setResult(RESULT_OK, result)
                        finish()
                    },
                )
            }
        }
    }

    /** The asking app's name for the indicator; none when it didn't ask for a result (the system's launcher, say) or can't be read. */
    private fun callerName(): String? {
        val pkg = callingActivity?.packageName ?: return null
        if (pkg == packageName) return null
        return runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))).toString() }.getOrNull()
    }
}
