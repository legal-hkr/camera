package sh.hnet.camera.ui.viewfinder

import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.MediaActionSound
import android.media.ToneGenerator
import android.view.KeyEvent
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlin.math.acos
import kotlin.math.sqrt
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import sh.hnet.camera.CameraApplication
import sh.hnet.camera.CameraSettings
import sh.hnet.camera.Grid
import sh.hnet.camera.MotionPhoto
import sh.hnet.camera.VolumeKeys
import sh.hnet.camera.camera.CameraRequest
import sh.hnet.camera.camera.EngineState
import sh.hnet.camera.camera.Flash
import sh.hnet.camera.camera.FrameAnalyzer
import sh.hnet.camera.camera.Mode
import sh.hnet.camera.capabilities.CameraSpec
import sh.hnet.camera.capabilities.model.Aspect
import sh.hnet.camera.capabilities.model.PixelSize
import sh.hnet.camera.capabilities.model.Point
import sh.hnet.camera.capabilities.model.SessionLadder
import sh.hnet.camera.capabilities.model.VideoChoice
import sh.hnet.camera.media.LastShot
import sh.hnet.camera.media.LocationTracker

/**
 * The viewfinder's own state. What isn't remembered starts the same way every time (back camera, Photo, 1×, flash and timer off; SPEC §17.5); what is
 * remembered comes from [settings].
 */
data class ViewfinderUi(
    val cameraIndex: Int = 0,
    val mode: Mode = Mode.PHOTO,
    val zoom: Double = 1.0,
    /** Exposure compensation in the camera's steps. */
    val exposure: Int = 0,
    /** The tapped point (a share of the picture), shown as the focus mark. */
    val focus: Point? = null,
    val locked: Boolean = false,
    /** The flashlight (Video mode). */
    val torch: Boolean = false,
    val flash: Flash = Flash.OFF,
    /** The flash's strength level chosen in the panel, null for the camera's default. */
    val flashLevel: Int? = null,
    /** The timer: 0 (off), 3 or 10 seconds. */
    val timer: Int = 0,
    /** Seconds left of a running countdown. */
    val countdown: Int? = null,
    /** The front camera's screen flash is lit. */
    val screenFlash: Boolean = false,
    /** Shots whose motion photo video is still being recorded (the 1.5 s after each): the "Motion" indicator. */
    val motionWaiting: Int = 0,
    /** How the phone is held (0, 90, 180, 270): the controls turn the other way. */
    val rotation: Int = 0,
    val settings: CameraSettings = CameraSettings(),
) {
    val aspect: Aspect get() = settings.aspect
}

/** The phone's tilt for the level: gravity in the screen's axes (x right, y up, z out of the screen), as a unit vector. */
data class Tilt(val x: Float, val y: Float, val z: Float)

class ViewfinderViewModel(app: Application) : AndroidViewModel(app) {
    private val application = app as CameraApplication
    private val engine = application.camera
    private val media = application.media
    private val preferences = application.preferences
    val location = LocationTracker(app)

    /** QR codes and the finder glass from the analysis stream, read against the preview's shape. */
    val analyzer = FrameAnalyzer { previewSize()?.ratio ?: (4.0 / 3) }

    /** Read again until the camera permission is there (some characteristics need it). */
    val cameras: List<CameraSpec> get() = engine.cameras
    val engineState: StateFlow<EngineState> = engine.state
    val lastShot: StateFlow<LastShot?> = media.last

    private val _ui = MutableStateFlow(ViewfinderUi())
    val ui: StateFlow<ViewfinderUi> = _ui.asStateFlow()

    private val _tilt = MutableStateFlow<Tilt?>(null)
    val tilt: StateFlow<Tilt?> = _tilt.asStateFlow()

    private val _shutter = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    /** A still's exposure started: the picture blinks and the phone ticks. */
    val shutter: SharedFlow<Unit> = _shutter

    private val _countdownTick = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    /** One of the countdown's last three seconds: a tick of the phone. */
    val countdownTick: SharedFlow<Unit> = _countdownTick

    /** How the phone is held (0, 90, 180, 270), for the photo's orientation and the controls. */
    var rotation = 0
        set(value) {
            if (field == value) return
            field = value
            engine.deviceRotation = value
            _ui.update { it.copy(rotation = value) }
        }

    private val sound = MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }
    private val beep = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, BEEP_VOLUME) }.getOrNull()
    private val shots = Mutex()
    private var timerJob: Job? = null

    /** The shutter sound is released with the view model; a still still in flight then stays silent. */
    @Volatile
    private var cleared = false

    private val sensors = app.getSystemService(SensorManager::class.java)
    private var gravity: FloatArray? = null
    private var gravityAtFocus: FloatArray? = null
    private val gravityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val now = event.values.copyOf()
            gravity = now
            if (_ui.value.settings.level) {
                val norm = sqrt(now[0] * now[0] + now[1] * now[1] + now[2] * now[2])
                // Rounded so the level redraws only when it visibly moves
                if (norm > 0) _tilt.value = Tilt(round(now[0] / norm), round(now[1] / norm), round(now[2] / norm))
            } else if (_tilt.value != null) {
                _tilt.value = null
            }
            val then = gravityAtFocus ?: return
            // A tapped focus holds until the phone turns away from where it was aimed (a locked one stays)
            if (angle(now, then) > FOCUS_HOLD_DEGREES && !_ui.value.locked && _ui.value.focus != null) {
                gravityAtFocus = null
                _ui.update { it.copy(focus = null) }
                engine.resetFocus()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    init {
        engine.resetControls()
        // The engine outlives this screen: it is told at once what this one wants (back camera, Photo), not only when a setting changes
        configure()
        viewModelScope.launch {
            preferences.camera.collect { settings ->
                val before = _ui.value.settings
                _ui.update { it.copy(settings = settings) }
                configure()
                if (settings.location != before.location) updateLocation()
            }
        }
        viewModelScope.launch { media.refreshLast() }
    }

    val camera: CameraSpec? get() = cameras.getOrNull(_ui.value.cameraIndex)

    fun request(): CameraRequest? {
        val ui = _ui.value
        val settings = ui.settings
        return camera?.let { CameraRequest(it, ui.mode, settings.aspect, VideoChoice(settings.videoUhd, videoFps, settings.hdrVideo && settings.videoHevc, settings.videoHevc), settings.ultraHdr, motion = ui.mode == Mode.PHOTO && settings.motionPhoto != MotionPhoto.OFF, motionFirst = settings.motionPhoto == MotionPhoto.ON) }
    }

    fun previewSize(): PixelSize? = request()?.let { engine.previewSize(it) }

    fun retry() = engine.retry()

    private fun configure() {
        request()?.let { engine.configure(it) }
        analyzer.qrEnabled = _ui.value.mode == Mode.PHOTO && _ui.value.settings.qrCodes
    }

    private var started = false

    private var unwatch: (() -> Unit)? = null
    private var refreshJob: Job? = null

    fun start() {
        started = true
        // The thumbnail follows the media storage while the viewfinder is shown (a shot deleted in the gallery opened from it)
        unwatch = media.watch {
            refreshJob?.cancel()
            refreshJob = viewModelScope.launch {
                delay(REFRESH_SETTLE_MS)
                media.refreshLast()
            }
        }
        // Also after the camera permission was just given (the cameras couldn't be read before)
        configure()
        engine.analyzer = analyzer::analyze
        engine.start()
        sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let { sensors.registerListener(gravityListener, it, SensorManager.SENSOR_DELAY_UI) }
        updateLocation()
        viewModelScope.launch { media.refreshLast() }
    }

    fun stop() {
        started = false
        engine.analyzer = null
        analyzer.reset()
        unwatch?.invoke()
        unwatch = null
        cancelTimer()
        engine.stop()
        sensors.unregisterListener(gravityListener)
        location.stop()
    }

    /** Asked for only while the viewfinder is shown and the setting is on. */
    fun updateLocation() {
        if (started && _ui.value.settings.location && location.allowed()) location.start() else location.stop()
    }

    fun setPreviewSurface(surface: Surface) = engine.setPreviewSurface(surface)

    fun clearPreviewSurface(surface: Surface) = engine.clearPreviewSurface(surface)

    private val recording: Boolean get() = engineState.value.recording != null

    fun selectMode(mode: Mode) {
        if (mode == _ui.value.mode || recording) return
        cancelTimer()
        _ui.update { it.copy(mode = mode, focus = null, locked = false, exposure = 0, torch = false) }
        analyzer.reset()
        engine.setTorch(false)
        engine.resetFocus(exposure = 0)
        configure()
    }

    fun switchCamera() {
        if (cameras.size < 2 || recording) return
        cancelTimer()
        _ui.update { it.copy(cameraIndex = (it.cameraIndex + 1) % cameras.size, zoom = 1.0, focus = null, locked = false, exposure = 0, torch = false, flashLevel = null) }
        engine.setTorch(false)
        engine.setZoom(1.0)
        engine.resetFocus(exposure = 0)
        engine.setFlash(_ui.value.flash, null)
        analyzer.reset()
        configure()
    }

    /** A zoom value from the ruler or a stop; a different lens (another stop is the nearest one below) ends the tapped focus and the exposure compensation. */
    fun zoomTo(ratio: Double) {
        val scale = camera?.zoom ?: return
        val value = ratio.coerceIn(scale.min, scale.max.coerceAtMost(scale.end))
        val before = lensOf(_ui.value.zoom)
        _ui.update { it.copy(zoom = value) }
        engine.setZoom(value)
        if (lensOf(value) != before && (_ui.value.focus != null || _ui.value.exposure != 0)) {
            _ui.update { it.copy(focus = null, locked = false, exposure = 0) }
            engine.resetFocus(exposure = 0)
        }
    }

    /** The native lens a zoom value is on: the nearest stop at or below it. */
    private fun lensOf(zoom: Double): Double = camera?.zoom?.stops?.lastOrNull { it.ratio <= zoom + 1e-6 }?.ratio ?: 1.0

    fun focusAt(point: Point) {
        if (camera?.let { it.autofocus || it.aeRegions > 0 } != true) return
        gravityAtFocus = gravity
        _ui.update { it.copy(focus = point, locked = false) }
        engine.focusAt(point, lock = false)
    }

    fun lockAt(point: Point) {
        gravityAtFocus = null
        _ui.update { it.copy(focus = point, locked = true) }
        engine.focusAt(point, lock = true)
    }

    fun unlock() {
        _ui.update { it.copy(focus = null, locked = false) }
        engine.resetFocus()
    }

    fun toggleTorch() {
        val on = !_ui.value.torch
        _ui.update { it.copy(torch = on) }
        engine.setTorch(on)
    }

    fun setFlash(flash: Flash) {
        _ui.update { it.copy(flash = flash) }
        engine.setFlash(flash, _ui.value.flashLevel)
    }

    fun setFlashLevel(level: Int) {
        _ui.update { it.copy(flashLevel = level) }
        engine.setFlash(_ui.value.flash, level)
    }

    fun setTimer(seconds: Int) = _ui.update { it.copy(timer = seconds) }

    fun setAspect(aspect: Aspect) = viewModelScope.launch { preferences.setAspect(aspect) }

    /** HDR of the current mode: Ultra HDR photos, or HLG video. */
    fun toggleHdr() = viewModelScope.launch {
        val settings = _ui.value.settings
        if (_ui.value.mode == Mode.VIDEO) preferences.setHdrVideo(!settings.hdrVideo) else preferences.setUltraHdr(!settings.ultraHdr)
    }

    /** HLG only up to 30 fps and in HEVC: at 60 or with AVC the HDR button is disabled and says why. */
    val videoHdrPossible: Boolean get() = videoFps <= SessionLadder.HLG_MAX_FPS && _ui.value.settings.videoHevc

    /** The frame rate the current camera records: the remembered one where it has it (a backup or the other camera may have set 60), else 30. */
    val videoFps: Int get() = _ui.value.settings.videoFps.takeIf { fps -> camera?.let { fps in videoRates(it.fixedFps) } ?: true } ?: 30

    fun setVideoSize(uhd: Boolean) = viewModelScope.launch { preferences.setVideoUhd(uhd) }

    fun setVideoFps(fps: Int) = viewModelScope.launch { preferences.setVideoFps(fps) }

    fun setGrid(grid: Grid) = viewModelScope.launch { preferences.setGrid(grid) }

    fun setLevel(on: Boolean) = viewModelScope.launch { preferences.setLevel(on) }

    fun setLocation(on: Boolean) = viewModelScope.launch { preferences.setLocation(on) }

    fun setMotionPhoto(mode: MotionPhoto) = viewModelScope.launch { preferences.setMotionPhoto(mode) }

    /** The video shutter: starts a recording into a new pending file, or ends the one running (saved either way as far as it got). */
    fun record() {
        // In the app's scope: a screen going away right after must not cut a start or a stop in half
        if (recording) {
            application.appScope.launch { engine.stopRecording() }
            return
        }
        if (engineState.value.finishing) return
        val place = placeNow()
        application.appScope.launch {
            val target = media.newVideo(System.currentTimeMillis()) ?: return@launch
            engine.startRecording(target, rotation, place?.let { it.latitude to it.longitude })
        }
    }

    fun pauseRecording() = engine.pauseRecording()

    fun resumeRecording() = engine.resumeRecording()

    fun setExposure(steps: Int) {
        _ui.update { it.copy(exposure = steps) }
        engine.setExposure(steps)
    }

    /** The shutter: a recording ends, a running countdown is cancelled, a set timer starts one, else the photo is taken or the recording started. */
    fun shutterPressed() {
        when {
            recording -> record()
            _ui.value.countdown != null -> cancelTimer()
            _ui.value.timer > 0 -> startTimer(_ui.value.timer)
            _ui.value.mode == Mode.VIDEO -> record()
            else -> shoot()
        }
    }

    /** Back during a countdown cancels it; true when there was one. */
    fun cancelTimer(): Boolean {
        val job = timerJob ?: return false
        job.cancel()
        timerJob = null
        _ui.update { it.copy(countdown = null) }
        // The LED's blinking ends where the flashlight was
        engine.setTorch(_ui.value.torch)
        return true
    }

    /**
     * The countdown (SPEC §7.3): the digit each second, a beep and a tick in the last three; with "Countdown with the flash" on, the back flash's LED blinks once a
     * second and four times a second in the last two, going dark half a second before the shot so it doesn't change the exposure (LOGIKA §3.2).
     */
    private fun startTimer(seconds: Int) {
        // Not while the flashlight is on: it stays on through the countdown
        val led = _ui.value.settings.timerFlash && !_ui.value.torch && camera?.let { !it.front && it.flash } == true
        timerJob = viewModelScope.launch {
            for (left in seconds downTo 1) {
                _ui.update { it.copy(countdown = left) }
                if (left <= LAST_SECONDS) {
                    beep?.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS)
                    _countdownTick.tryEmit(Unit)
                }
                if (led) blink(left) else delay(1_000)
            }
            timerJob = null
            _ui.update { it.copy(countdown = null) }
            engine.setTorch(_ui.value.torch)
            if (_ui.value.mode == Mode.VIDEO) record() else shoot(timed = true)
        }
    }

    private suspend fun blink(left: Int) {
        val pulses = if (left <= 2) 4 else 1
        val period = 1_000L / pulses
        val on = if (pulses == 1) 250L else period / 2
        for (pulse in 0 until pulses) {
            // The last half second stays dark
            val dark = left == 1 && pulse * period >= 500
            if (!dark) engine.setTorch(true)
            delay(on)
            engine.setTorch(false)
            delay(period - on)
        }
    }

    /** The place for a shot taken now, when the setting is on and a fresh fix is there. */
    private fun placeNow() = if (_ui.value.settings.location) location.current() else null

    /** Takes a photo and saves it in the app's scope, so leaving the viewfinder right after doesn't lose it. Shots queue up; the shutter never waits. */
    fun shoot(timed: Boolean = false) {
        val ui = _ui.value
        val front = camera?.front == true
        val screenFlash = front && ui.mode == Mode.PHOTO && (ui.flash == Flash.ON || (ui.flash == Flash.AUTO && engineState.value.dark))
        val place = placeNow()
        viewModelScope.launch {
            shots.withLock {
                if (screenFlash) {
                    // The screen lit white at full brightness first – counted from when the overlay is really there – then the exposure metered for it (SPEC §7.2)
                    _ui.update { it.copy(screenFlash = true) }
                    withTimeoutOrNull(SCREEN_FLASH_WAIT_MS) { screenLit.first { it } }
                    delay(SCREEN_FLASH_WARM_MS)
                }
                val photo = try {
                    engine.takePhoto(rotation, place, precapture = screenFlash) {
                        // The click where the setting wants it or the law does
                        if (!cleared && (ui.settings.shutterSound || MediaActionSound.mustPlayShutterSound())) sound.play(MediaActionSound.SHUTTER_CLICK)
                        _shutter.tryEmit(Unit)
                    }
                } finally {
                    if (screenFlash) _ui.update { it.copy(screenFlash = false) }
                } ?: return@withLock
                val wantPlace = ui.settings.location && place == null
                // A motion photo (LOGIKA §3.3) where the stream runs, not after the timer (its LED blinks in the video) nor with a flash (one lit frame); automatic
                // only where the analysis can tell whether something moved
                val flashFired = screenFlash || (camera?.flash == true && !ui.torch && (ui.flash == Flash.ON || (ui.flash == Flash.AUTO && engineState.value.flashNeeded)))
                val motionMode = ui.settings.motionPhoto
                val state = engineState.value
                // Nor a mirrored selfie: the video can't be mirrored with it, and the picture would jump to the other side when it plays
                val mirror = front && ui.settings.mirror
                val wantMotion = !timed && !flashFired && !mirror && state.motion && (motionMode == MotionPhoto.ON || (motionMode == MotionPhoto.AUTO && state.analysis))
                if (wantMotion) _ui.update { it.copy(motionWaiting = it.motionWaiting + 1) }
                application.appScope.launch {
                    // The photo is saved at once; its video is added when the 1.5 s after it are there, gathered meanwhile (the ring keeps only a few seconds)
                    var waiting = wantMotion
                    val clipping = if (wantMotion) async { engine.motionClip(photo) } else null
                    val uri = try {
                        val saved = media.savePhoto(photo.bytes, photo.takenAt, photo.crop, mirror)
                        if (clipping != null) {
                            val clip = clipping.await()
                            _ui.update { it.copy(motionWaiting = it.motionWaiting - 1) }
                            waiting = false
                            // Automatic: the video stays only when something moved around the shot
                            val keep = clip?.takeIf { motionMode == MotionPhoto.ON || analyzer.moved(photo.sensorTimeNs - MOTION_AROUND_NS, photo.sensorTimeNs + MOTION_AROUND_NS) }
                            if (saved != null && keep != null) media.addMotion(saved, keep)
                        }
                        saved
                    } finally {
                        if (waiting) _ui.update { it.copy(motionWaiting = it.motionWaiting - 1) }
                    } ?: return@launch
                    // A fix that comes within a few seconds still goes into the photo
                    if (wantPlace) withTimeoutOrNull(LATE_PLACE_MS) { location.fix.first { it != null && location.current() != null } }?.let { media.addLocation(uri, it) }
                }
            }
        }
    }

    /**
     * The volume keys (SPEC §7.9): as the shutter (on release, so a held key doesn't fire twice; it also starts and stops a recording), as the zoom (up zooms in),
     * or left to the volume. Returns true when the viewfinder took the key.
     */
    fun onVolumeKey(event: KeyEvent): Boolean {
        when (_ui.value.settings.volumeKeys) {
            VolumeKeys.VOLUME -> return false
            VolumeKeys.SHUTTER -> {
                if (event.action == KeyEvent.ACTION_UP) shutterPressed()
                return true
            }
            VolumeKeys.ZOOM -> {
                if (event.action == KeyEvent.ACTION_DOWN) keyZoom(event.keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                return true
            }
        }
    }

    /**
     * A step of the zoom by a volume key: smooth from 1× to the end; the ultrawide only as its own stop (it is a separate button), reached below 1× and left
     * upwards back to 1×.
     */
    private fun keyZoom(up: Boolean) {
        val scale = camera?.zoom ?: return
        val start = scale.group.firstOrNull()?.ratio ?: 1.0
        val wide = scale.ultrawide?.ratio
        val zoom = _ui.value.zoom
        val next = when {
            up && zoom < start - 1e-6 -> start
            up -> (zoom * KEY_ZOOM_STEP).coerceAtMost(scale.end)
            zoom <= start + 1e-6 -> wide ?: start
            else -> (zoom / KEY_ZOOM_STEP).coerceAtLeast(start)
        }
        zoomTo(next)
    }

    /** The screen flash's overlay is on the screen at full brightness (the UI says so); the exposure is metered only then. */
    private val screenLit = MutableStateFlow(false)

    fun onScreenLit(lit: Boolean) {
        screenLit.value = lit
    }

    override fun onCleared() {
        cleared = true
        timerJob?.cancel()
        sensors.unregisterListener(gravityListener)
        location.close()
        unwatch?.invoke()
        analyzer.close()
        sound.release()
        beep?.release()
    }

    private fun round(value: Float) = Math.round(value * 400) / 400f

    private fun angle(a: FloatArray, b: FloatArray): Double {
        val dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        val norm = sqrt((a[0] * a[0] + a[1] * a[1] + a[2] * a[2]) * (b[0] * b[0] + b[1] * b[1] + b[2] * b[2]))
        if (norm == 0f) return 0.0
        return Math.toDegrees(acos((dot / norm).coerceIn(-1f, 1f).toDouble()))
    }

    private companion object {
        const val FOCUS_HOLD_DEGREES = 10.0
        const val LAST_SECONDS = 3
        const val BEEP_VOLUME = 60
        const val BEEP_MS = 80
        const val SCREEN_FLASH_WARM_MS = 300L
        const val SCREEN_FLASH_WAIT_MS = 1_000L
        const val LATE_PLACE_MS = 5_000L
        const val KEY_ZOOM_STEP = 1.1
        const val REFRESH_SETTLE_MS = 300L
        const val MOTION_AROUND_NS = 1_500_000_000L
    }
}
