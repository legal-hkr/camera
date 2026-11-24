package sh.hnet.camera

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import sh.hnet.camera.backup.BackupManager
import sh.hnet.camera.camera.CameraEngine
import sh.hnet.camera.media.CaptureOutput
import sh.hnet.camera.media.MediaFiles

class CameraApplication : Application() {
    /** Work that outlives a screen; lives as long as the process. */
    val appScope = CoroutineScope(SupervisorJob())

    val preferences: Preferences by lazy { Preferences(this) }

    /** The one camera of the process: one device and one session at a time, whichever screen shows the viewfinder. */
    val camera: CameraEngine by lazy { CameraEngine(this) }

    val media: MediaFiles by lazy { MediaFiles(this) }

    val captures: CaptureOutput by lazy { CaptureOutput(this) }

    /** The Night suggestion closed: hidden until the app starts again (SPEC §9.1). */
    @Volatile
    var nightSuggestionClosed = false

    val backup: BackupManager by lazy { BackupManager(this, getSharedPreferences("stamps", MODE_PRIVATE)) }

    override fun onCreate() {
        super.onCreate()
        // Only when the process starts: no recording of this process can be pending then
        appScope.launch { media.removeAbandoned() }
    }
}
