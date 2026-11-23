package sh.hnet.camera.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.SystemClock
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The place for photos and videos (SPEC §7.8), from the system's own location (no Google services): asked for only while the viewfinder is shown and the setting
 * is on. A fix older than two minutes doesn't count.
 */
class LocationTracker(context: Context) {
    private val appContext = context.applicationContext
    private val manager = context.getSystemService(LocationManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    private val _fix = MutableStateFlow<Location?>(null)
    val fix: StateFlow<Location?> = _fix.asStateFlow()
    private var listening = false

    private val listener = LocationListener { location -> _fix.value = location }

    fun allowed(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The newest fix if it is fresh enough for a photo taken now. */
    fun current(): Location? = _fix.value?.takeIf { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < MAX_AGE_NS }

    @SuppressLint("MissingPermission") // checked by allowed()
    fun start() {
        if (listening || !allowed()) return
        val request = LocationRequest.Builder(INTERVAL_MS).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).setMinUpdateIntervalMillis(INTERVAL_MS / 2).build()
        // The platform's fused provider where the phone has one, else GPS and the network's together
        val providers = if (manager.hasProvider(LocationManager.FUSED_PROVIDER)) listOf(LocationManager.FUSED_PROVIDER) else listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { manager.hasProvider(it) }
        for (provider in providers) {
            runCatching { manager.requestLocationUpdates(provider, request, executor, listener) }
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()?.let { last ->
                val known = _fix.value
                if (known == null || last.elapsedRealtimeNanos > known.elapsedRealtimeNanos) _fix.value = last
            }
        }
        listening = true
    }

    fun stop() {
        if (!listening) return
        runCatching { manager.removeUpdates(listener) }
        listening = false
    }

    /** The tracker's thread ends with its owner. */
    fun close() {
        stop()
        executor.shutdown()
    }

    private companion object {
        const val INTERVAL_MS = 2_000L
        const val MAX_AGE_NS = 120_000_000_000L
    }
}
