package sh.hnet.camera.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import sh.hnet.camera.CameraApplication
import sh.hnet.camera.R

/**
 * Keeps the process alive while photos are developed (D31): a foreground service of the media processing kind (Android 15+), stopped as soon as the queue
 * is empty. Its notification shows only where notifications are allowed, and only after the system's usual few seconds.
 */
class ProcessingService : Service() {
    private val scope = CoroutineScope(SupervisorJob())
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val processor = (application as CameraApplication).processor
        // Started only where the media processing kind exists
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        } catch (e: Exception) {
            // Not allowed now (the app in the background): the developing goes on without it
            Log.w(TAG, "Not in the foreground: ${e.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        // Watched again from each start, stopped for that start only: a newer start (a photo taken just as the queue emptied) makes the older stop fail
        watch?.cancel()
        watch = scope.launch {
            processor.queued.first { it == 0 }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    /** The system's limit for this kind (hours a day) is far beyond a queue's seconds; reached anyway, the service ends. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_processing), NotificationManager.IMPORTANCE_MIN))
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_photo_camera)
            .setContentTitle(getString(R.string.msg_processing))
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED)
            .build()
    }

    private companion object {
        const val TAG = "ProcessingService"
        const val CHANNEL = "processing"
        const val NOTIFICATION = 1
    }
}
