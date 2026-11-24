package sh.hnet.camera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import sh.hnet.camera.ui.theme.CameraTheme
import sh.hnet.camera.ui.viewfinder.Entry
import sh.hnet.camera.ui.viewfinder.ViewfinderScreen
import sh.hnet.camera.ui.viewfinder.unlockThen

/**
 * The camera over the lock screen (SPEC §15.2, LOGIKA §11): an activity of its own, so the app's Settings never show over the lock screen. Every mode works;
 * the thumbnail shows only this session's shots; Settings ask to unlock, then open in the app. Gone when the screen goes off.
 */
class SecureCameraActivity : ViewfinderActivity() {
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent {
            CameraTheme {
                ViewfinderScreen(Entry.LockScreen(), onOpenSettings = {
                    unlockThen(this, locked = true) {
                        // Into the app's own task, the one already there if any
                        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SETTINGS, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        finish()
                    }
                })
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(screenOff)
        super.onDestroy()
    }
}
