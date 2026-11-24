package sh.hnet.camera.camera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.os.PowerManager
import android.os.storage.StorageManager
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The phone's states that change what the camera may do (SPEC §14.1, LOGIKA §9). */
data class PhoneState(
    /** `PowerManager.THERMAL_STATUS_*`. */
    val thermal: Int = PowerManager.THERMAL_STATUS_NONE,
    /** Percent, null before the first reading. */
    val battery: Int? = null,
    val charging: Boolean = false,
    /** A phone or VoIP call has the sound. */
    val inCall: Boolean = false,
    /** Free bytes on the primary storage, null before the first reading. */
    val freeBytes: Long? = null,
) {
    /** From the first thermal step: no 4K at 60 fps, no flash (SPEC §14.1). */
    val hot: Boolean get() = thermal >= PowerManager.THERMAL_STATUS_MODERATE

    /** The last step: a recording stops and is saved. */
    val tooHot: Boolean get() = thermal >= PowerManager.THERMAL_STATUS_CRITICAL

    /** Under 10 % and not charging: no flash. */
    val lowBattery: Boolean get() = battery != null && battery < LOW_BATTERY && !charging

    /** The flash's LED (flash, flashlight, the timer's countdown) is off for heat or the battery. */
    val ledBlocked: Boolean get() = hot || lowBattery

    /** Under 50 MB nothing more fits: the shutter waits (LOGIKA §9). */
    val storageFull: Boolean get() = freeBytes != null && freeBytes < FULL_BYTES

    companion object {
        const val LOW_BATTERY = 10
        const val FULL_BYTES = 50L * 1024 * 1024
    }
}

/**
 * Watches the phone while a viewfinder is shown: heat (`PowerManager`'s thermal status), the battery (its sticky broadcast), calls (the audio mode, no phone
 * permission needed) and the free storage (every few seconds, more often while recording, and after every save).
 */
class PhoneStates(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(PhoneState())
    val state: StateFlow<PhoneState> = _state.asStateFlow()

    private val power = context.getSystemService(PowerManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val storage = context.getSystemService(StorageManager::class.java)
    private val main: Executor = ContextCompat.getMainExecutor(context)

    private val thermal = PowerManager.OnThermalStatusChangedListener { status -> _state.update { it.copy(thermal = status) } }
    private val mode = AudioManager.OnModeChangedListener { m -> _state.update { it.copy(inCall = inCall(m)) } }
    private val battery = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = read(intent)
    }
    private var storageJob: Job? = null
    private var watching = false

    private val fastFlow = MutableStateFlow(false)

    /** Checks the storage every second while recording (a few seconds of 4K fill tens of megabytes), at once when it begins. */
    var fast: Boolean
        get() = fastFlow.value
        set(value) {
            fastFlow.value = value
        }

    fun start() {
        if (watching) return
        watching = true
        power.addThermalStatusListener(main, thermal)
        audio.addOnModeChangedListener(main, mode)
        _state.update { it.copy(thermal = power.currentThermalStatus, inCall = inCall(audio.mode)) }
        ContextCompat.registerReceiver(context, battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)?.let(::read)
        storageJob = scope.launch(Dispatchers.IO) {
            fastFlow.collectLatest { quick ->
                while (isActive) {
                    readStorage()
                    delay(if (quick) FAST_MS else SLOW_MS)
                }
            }
        }
    }

    fun stop() {
        if (!watching) return
        watching = false
        power.removeThermalStatusListener(thermal)
        audio.removeOnModeChangedListener(mode)
        runCatching { context.unregisterReceiver(battery) }
        storageJob?.cancel()
        storageJob = null
    }

    /** Reads the free space again (after a save), off the main thread: asking the storage may take a moment. */
    fun refreshStorage() {
        scope.launch(Dispatchers.IO) { readStorage() }
    }

    private fun readStorage() {
        // The volume's free space (LOGIKA §9): what can be allocated keeps a reserve of up to 500 MB back, which would make storage look full far too early
        val free = runCatching { storage.primaryStorageVolume.directory?.usableSpace }.getOrNull()
            ?: runCatching { storage.getAllocatableBytes(StorageManager.UUID_DEFAULT) }.getOrNull()
            ?: return
        _state.update { it.copy(freeBytes = free) }
    }

    private fun read(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (level < 0 || scale <= 0) return
        _state.update { it.copy(battery = level * 100 / scale, charging = plugged) }
    }

    /** A call that has the microphone: answered (cellular or VoIP), not ringing nor screened by the phone. */
    private fun inCall(mode: Int) = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_CALL_REDIRECT || mode == AudioManager.MODE_COMMUNICATION_REDIRECT

    private companion object {
        const val SLOW_MS = 10_000L
        const val FAST_MS = 1_000L
    }
}
