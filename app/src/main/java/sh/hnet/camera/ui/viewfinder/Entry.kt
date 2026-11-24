package sh.hnet.camera.ui.viewfinder

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.IntentCompat

/** Where the viewfinder starts from a shortcut or the system's camera actions (SPEC §15.5, LOGIKA §11). */
enum class Start { PHOTO, VIDEO, SELFIE, QR }

/** The way into the viewfinder (LOGIKA §11): the app itself, over the lock screen, or a shot for another app. */
sealed interface Entry {
    /** The app; [start] from a shortcut or the system's camera actions, each one new ([id]). */
    data class App(val start: Start? = null, val id: Long = 0) : Entry

    /** Over the lock screen (the power button pressed twice): only this session's shots are shown, Settings ask to unlock (SPEC §15.2). */
    data class LockScreen(val id: Long = 0) : Entry

    /**
     * A photo or a video for another app (SPEC §15.3): one mode, Review, the result to [output] or back in the result. [caller] is the app's name for the
     * indicator; [secure] over the lock screen; the limits and the low quality as the app asked.
     */
    data class Capture(
        val video: Boolean,
        val output: Uri?,
        val caller: String?,
        val secure: Boolean,
        val durationLimitMs: Long?,
        val sizeLimit: Long?,
        val lowQuality: Boolean,
    ) : Entry

    /** Over the lock screen: the gallery, the Settings and any access need the phone unlocked. */
    val locked: Boolean get() = this is LockScreen || (this is Capture && secure)

    companion object {
        private fun number(intent: Intent, key: String): Long? {
            if (!intent.hasExtra(key)) return null
            val long = intent.getLongExtra(key, Long.MIN_VALUE)
            return if (long != Long.MIN_VALUE) long else intent.getIntExtra(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }?.toLong()
        }

        const val EXTRA_START = "sh.hnet.camera.START"

        /** The app's own way in: the system's still and video camera actions, the shortcuts. */
        fun app(intent: Intent?, id: Long): App {
            val start = when (intent?.action) {
                MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA -> Start.PHOTO
                MediaStore.INTENT_ACTION_VIDEO_CAMERA -> Start.VIDEO
                else -> intent?.getStringExtra(EXTRA_START)?.let { name -> Start.entries.firstOrNull { it.name == name } }
            }
            return App(start, id)
        }

        /** A capture request from another app, as the system's API describes it. */
        fun capture(intent: Intent, caller: String?): Capture {
            val video = intent.action == MediaStore.ACTION_VIDEO_CAPTURE
            return Capture(
                video = video,
                output = IntentCompat.getParcelableExtra(intent, MediaStore.EXTRA_OUTPUT, Uri::class.java),
                caller = caller,
                secure = intent.action == MediaStore.ACTION_IMAGE_CAPTURE_SECURE,
                // Apps put these as Int or Long; either is read
                durationLimitMs = number(intent, MediaStore.EXTRA_DURATION_LIMIT)?.takeIf { video && it > 0 }?.let { it * 1000L },
                sizeLimit = number(intent, MediaStore.EXTRA_SIZE_LIMIT)?.takeIf { video && it > 0 },
                lowQuality = video && intent.getIntExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1) == 0,
            )
        }
    }
}
