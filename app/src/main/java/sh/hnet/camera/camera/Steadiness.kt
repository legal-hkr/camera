package sh.hnet.camera.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.coroutines.resume
import kotlin.math.sqrt
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The phone's stillness from the gyroscope, for a long exposure set by hand: it starts once the shake of the tap that asked for it has passed, and a phone
 * lying still (a support, a tripod) takes it without the optical stabilization, which drifts by itself over seconds. LOGIKA §30.4.
 */
class Steadiness(context: Context) {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val gyroscope = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    /** Waits until the turn rate stays under [STILL_RAD_S] for [STILL_NS], at most [maxMs]; true when the phone is still, false when it kept moving or has no gyroscope. */
    suspend fun awaitStill(maxMs: Long): Boolean {
        val sensors = sensors ?: return false
        val gyroscope = gyroscope ?: return false
        return withTimeoutOrNull(maxMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : SensorEventListener {
                    private var since = 0L

                    override fun onSensorChanged(event: SensorEvent) {
                        val (x, y, z) = event.values
                        if (sqrt(x * x + y * y + z * z) > STILL_RAD_S) {
                            since = 0L
                            return
                        }
                        if (since == 0L) since = event.timestamp
                        if (event.timestamp - since >= STILL_NS && cont.isActive) {
                            sensors.unregisterListener(this)
                            cont.resume(true)
                        }
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                sensors.registerListener(listener, gyroscope, SensorManager.SENSOR_DELAY_GAME)
                cont.invokeOnCancellation { sensors.unregisterListener(listener) }
            }
        } ?: false
    }

    private companion object {
        /** Turning slower than this (about a third of a degree a second) is a phone at rest: a hand holding it is several times more. */
        const val STILL_RAD_S = 0.006f
        const val STILL_NS = 300_000_000L
    }
}
