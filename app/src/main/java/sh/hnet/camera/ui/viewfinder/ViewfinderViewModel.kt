package sh.hnet.camera.ui.viewfinder

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.MediaActionSound
import android.media.ToneGenerator
import android.os.SystemClock
import android.view.KeyEvent
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlin.math.acos
import kotlin.math.sqrt
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
import sh.hnet.camera.camera.AnalysisFrame
import sh.hnet.camera.camera.CameraRequest
import sh.hnet.camera.camera.CapturedPhoto
import sh.hnet.camera.camera.EngineState
import sh.hnet.camera.camera.Flash
import sh.hnet.camera.camera.FrameAnalyzer
import sh.hnet.camera.camera.Mode
import sh.hnet.camera.camera.PhoneState
import sh.hnet.camera.camera.PhoneStates
import sh.hnet.camera.capabilities.CameraSpec
import sh.hnet.camera.capabilities.model.Aspect
import sh.hnet.camera.capabilities.model.PixelSize
import sh.hnet.camera.capabilities.model.Point
import sh.hnet.camera.capabilities.model.SessionLadder
import sh.hnet.camera.capabilities.model.VideoChoice
import sh.hnet.camera.media.LastShot
import sh.hnet.camera.media.LocationTracker
import sh.hnet.camera.media.MediaFiles

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
    /** Started from the QR code shortcut: "Point the camera at the code" until one is found (SPEC §15.5). */
    val pointAtCode: Boolean = false,
    /** A Night photo is gathering its frames: hold still, the shutter waits (SPEC §9.2). */
    val nightGathering: Boolean = false,
    /** The Night photo gathering its frames: the extension's progress (0–1) on the shutter; null when it gives none. */
    val gatherProgress: Float? = null,
    /** The newest Night photo being put together: its progress (0–1) at the thumbnail; null when it gives none. */
    val nightProgress: Float? = null,
    /** Night photos being put together after their frames were gathered (SPEC §8.2): the queue at the thumbnail. */
    val processing: Int = 0,
    /** The scene has been dark for a while in Photo: the "Night mode" suggestion (SPEC §9.1). */
    val nightSuggested: Boolean = false,
    val settings: CameraSettings = CameraSettings(),
) {
    val aspect: Aspect get() = settings.aspect
}

/** What ended a recording by itself (SPEC §14.1), and whether the video was saved: said in a snackbar. */
enum class Stop { HOT, CALL, STORAGE }

class Stopped(val why: Stop, val saved: Boolean)

/** A shot for another app waiting for Use or Retake (SPEC §15.3). */
sealed interface Review {
    /** [preview] upright and mirrored as it will be handed over; null when it couldn't be decoded. */
    class Photo(val photo: CapturedPhoto, val mirror: Boolean, val preview: Bitmap?) : Review

    class Video(val file: File) : Review
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
    private val analyze: (AnalysisFrame) -> Unit = analyzer::analyze

    /** Read again until the camera permission is there (some characteristics need it). */
    val cameras: List<CameraSpec> get() = engine.cameras
    val engineState: StateFlow<EngineState> = engine.state

    /** Night's early postview, shown over the picture until its photo is out (SPEC §9.2). */
    val postviewImage: StateFlow<Bitmap?> = engine.postviewImage
    private val _entry = MutableStateFlow<Entry>(Entry.App())
    val entry: StateFlow<Entry> = _entry.asStateFlow()
    private var entered = false

    /** When this way in began: over the lock screen only the shots since then are shown (SPEC §15.2). */
    private var sessionStart = System.currentTimeMillis()

    /** The thumbnail's shot: the newest own one, over the lock screen only this session's. */
    val lastShot: StateFlow<LastShot?> = combine(media.last, media.saved, _entry) { last, saved, entry ->
        if (!entry.locked) last else last?.takeIf { shot -> saved.any { it.uri == shot.uri && it.at >= sessionStart } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** This lock-screen session's shots, newest first, for the gallery's secure review. */
    fun sessionShots(): List<MediaFiles.Saved> = media.saved.value.filter { it.at >= sessionStart }.reversed()

    /** Heat, battery, calls and storage while the viewfinder is shown (SPEC §14.1). */
    private val phone = PhoneStates(app, viewModelScope)
    val phoneState: StateFlow<PhoneState> = phone.state

    private val _stopped = MutableSharedFlow<Stopped>(extraBufferCapacity = 2)
    val stopped: SharedFlow<Stopped> = _stopped

    private val _refused = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** A recording refused because the phone is too hot. */
    val refused: SharedFlow<Unit> = _refused

    /** A new session waits for the recording or the countdown to end (a heat change, a setting): never cut under them. */
    private var configureLater = false

    /** The flash's LED can't be used now (heat, battery); the front camera's screen flash can. */
    val ledBlocked: Boolean get() = phone.state.value.ledBlocked && camera?.front != true

    private val _review = MutableStateFlow<Review?>(null)
    val review: StateFlow<Review?> = _review.asStateFlow()
    private var limitJob: Job? = null

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
        viewModelScope.launch {
            var before = phone.state.value
            phone.state.collect { now ->
                // A stopped viewfinder's states are stale (another one may have the camera and its own recording)
                if (started) {
                    if (now.ledBlocked != before.ledBlocked) applyLed()
                    // 4K at 60 fps comes back or goes with the heat: only Video's session changes, after a recording or a countdown
                    if (now.hot != before.hot && _ui.value.mode == Mode.VIDEO) configure()
                }
                before = now
            }
        }
        // A recording is stopped by the phone's state at any moment of it, also one started while the state already held (SPEC §14.1)
        viewModelScope.launch {
            var callBefore = phone.state.value.inCall
            var stopping = false
            combine(phone.state, engineState.map { it.recording != null }.distinctUntilChanged()) { state, on -> state to on }.collect { (now, on) ->
                if (!on) stopping = false
                val callStarted = now.inCall && !callBefore
                callBefore = now.inCall
                if (!started || !on || stopping) return@collect
                // A call stops it when it begins during the recording (one already going when it started doesn't)
                val why = when {
                    now.tooHot -> Stop.HOT
                    callStarted -> Stop.CALL
                    now.storageFull -> Stop.STORAGE
                    else -> null
                } ?: return@collect
                stopping = true
                application.appScope.launch {
                    val saved = engine.stopRecording()
                    _stopped.tryEmit(Stopped(why, saved))
                }
            }
        }
        // Night suggested after the scene has been dark for two seconds in Photo, where the camera has Night and the setting allows (SPEC §9.1)
        viewModelScope.launch {
            combine(engineState.map { it.dark }.distinctUntilChanged(), _ui.map { it.mode == Mode.PHOTO && it.settings.nightSuggestion }.distinctUntilChanged()) { dark, allowed -> dark && allowed }
                .distinctUntilChanged()
                .collectLatest { dark ->
                    if (!dark) {
                        _ui.update { it.copy(nightSuggested = false) }
                        return@collectLatest
                    }
                    delay(NIGHT_SUGGEST_MS)
                    if (camera?.night != null && !application.nightSuggestionClosed && _entry.value !is Entry.Capture) _ui.update { it.copy(nightSuggested = true) }
                }
        }
        viewModelScope.launch {
            var wasRecording = false
            engineState.collect { state ->
                val now = state.recording != null
                // While recording the storage is read every second; a finished video takes space
                phone.fast = now
                if (wasRecording && !now) {
                    phone.refreshStorage()
                    if (configureLater) configure()
                }
                wasRecording = now
            }
        }
    }

    /** The LED off while blocked (the flash and the flashlight), the chosen flash back after. */
    private fun applyLed() {
        if (ledBlocked) {
            engine.setFlash(Flash.OFF, null)
            if (_ui.value.torch) {
                _ui.update { it.copy(torch = false) }
                engine.setTorch(false)
            }
        } else {
            engine.setFlash(_ui.value.flash, _ui.value.flashLevel)
        }
    }

    val camera: CameraSpec? get() = cameras.getOrNull(_ui.value.cameraIndex)

    /**
     * The way in (LOGIKA §11), told by the screen as it is composed, so the first session is already the right one. A new one (another shortcut, another
     * press of the power button) applies its start again; the same one does nothing.
     */
    fun enter(entry: Entry) {
        if (entered && entry == _entry.value) return
        // Without the camera permission the cameras can't be read: the start is applied when they can (start())
        pendingEntry = if (cameras.isEmpty()) entry else null
        entered = true
        _entry.value = entry
        sessionStart = System.currentTimeMillis()
        cancelTimer()
        val front = cameras.indexOfFirst { it.front }
        val back = cameras.indexOfFirst { !it.front }.coerceAtLeast(0)
        val (mode, index) = when (entry) {
            is Entry.Capture -> (if (entry.video) Mode.VIDEO else Mode.PHOTO) to _ui.value.cameraIndex
            is Entry.LockScreen -> Mode.PHOTO to back
            is Entry.App -> when (entry.start) {
                Start.PHOTO, Start.QR -> Mode.PHOTO to back
                Start.VIDEO -> Mode.VIDEO to back
                Start.SELFIE -> Mode.PHOTO to (if (front >= 0) front else back)
                null -> _ui.value.mode to _ui.value.cameraIndex
            }
        }
        // A recording goes on as it is: no other mode or camera, and its flashlight, exposure and focus stay
        if (recording) return
        val point = entry is Entry.App && entry.start == Start.QR
        _ui.update { it.copy(mode = mode, cameraIndex = index, pointAtCode = point, focus = null, locked = false, exposure = 0, torch = false) }
        engine.setTorch(false)
        engine.resetFocus(exposure = 0)
        analyzer.reset()
        configure()
    }

    private var pendingEntry: Entry? = null

    /** A code was found, or the hint dismissed. */
    fun clearPointAtCode() {
        _ui.update { it.copy(pointAtCode = false) }
        analyzer.qrEnabled = _ui.value.mode == Mode.PHOTO && _ui.value.settings.qrCodes
    }

    fun request(): CameraRequest? {
        val ui = _ui.value
        val settings = ui.settings
        // For another app: no motion photo (SPEC §15.3), and its low quality is 1080p SDR at 30 (LOGIKA §11)
        val capture = _entry.value as? Entry.Capture
        val video = if (capture?.lowQuality == true) VideoChoice(uhd = false, fps = 30, hdr = false, hevc = settings.videoHevc) else VideoChoice(settings.videoUhd, videoFps, settings.hdrVideo && settings.videoHevc, settings.videoHevc)
        val motion = capture == null && ui.mode == Mode.PHOTO && settings.motionPhoto != MotionPhoto.OFF
        return camera?.let { CameraRequest(it, ui.mode, settings.aspect, video, settings.ultraHdr, motion = motion, motionFirst = settings.motionPhoto == MotionPhoto.ON) }
    }

    fun previewSize(): PixelSize? = request()?.let { engine.previewSize(it) }

    fun retry() = engine.retry()

    private fun configure() {
        // The QR shortcut reads codes even with QR codes off in Settings: it was asked for
        analyzer.qrEnabled = _ui.value.mode == Mode.PHOTO && (_ui.value.settings.qrCodes || _ui.value.pointAtCode)
        // A viewfinder not shown doesn't touch the shared engine (another one may have it); start() configures
        if (!started) return
        // Not under a recording or a countdown: a new session would cut the video or lose the timed shot
        if (recording || _ui.value.countdown != null) {
            configureLater = true
            return
        }
        configureLater = false
        request()?.let { engine.configure(it) }
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
        // The phone's states first: the first session asks for what the heat allows
        phone.start()
        // Also after the camera permission was just given (the cameras couldn't be read before)
        configure()
        pendingEntry?.let { pending ->
            if (cameras.isNotEmpty()) {
                entered = false
                enter(pending)
            }
        }
        engine.analyzer = analyze
        // The engine is the process's: this one's controls again, another viewfinder (the lock screen's, another app's shot) may have changed them
        _ui.update { it.copy(focus = null, locked = false) }
        engine.setZoom(_ui.value.zoom)
        engine.setTorch(_ui.value.torch && !ledBlocked)
        applyLed()
        engine.resetFocus(exposure = _ui.value.exposure)
        engine.start(this)
        sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let { sensors.registerListener(gravityListener, it, SensorManager.SENSOR_DELAY_UI) }
        updateLocation()
        viewModelScope.launch { media.refreshLast() }
    }

    fun stop() {
        started = false
        // Only its own: another viewfinder may have taken the engine already
        if (engine.analyzer === analyze) engine.analyzer = null
        analyzer.reset()
        unwatch?.invoke()
        unwatch = null
        cancelTimer()
        engine.stop(this)
        phone.stop()
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
        if (mode == _ui.value.mode || recording || _entry.value is Entry.Capture || _ui.value.nightGathering) return
        // Night only on a camera with the extension
        if (mode == Mode.NIGHT && camera?.night == null) return
        cancelTimer()
        _ui.update { it.copy(mode = mode, focus = null, locked = false, exposure = 0, torch = false, pointAtCode = false) }
        analyzer.reset()
        engine.setTorch(false)
        engine.resetFocus(exposure = 0)
        configure()
    }

    fun switchCamera() {
        if (cameras.size < 2 || recording || _ui.value.nightGathering) return
        cancelTimer()
        val next = (_ui.value.cameraIndex + 1) % cameras.size
        // Night stays only where the other camera has it too
        val mode = if (_ui.value.mode == Mode.NIGHT && cameras[next].night == null) Mode.PHOTO else _ui.value.mode
        _ui.update { it.copy(cameraIndex = next, mode = mode, zoom = 1.0, focus = null, locked = false, exposure = 0, torch = false, flashLevel = null) }
        engine.setTorch(false)
        engine.setZoom(1.0)
        engine.resetFocus(exposure = 0)
        applyLed()
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
        // Night's extension has no exposure lock: a long press focuses there as a tap does
        if (_ui.value.mode == Mode.NIGHT) {
            focusAt(point)
            return
        }
        gravityAtFocus = null
        _ui.update { it.copy(focus = point, locked = true) }
        engine.focusAt(point, lock = true)
    }

    fun unlock() {
        _ui.update { it.copy(focus = null, locked = false) }
        engine.resetFocus()
    }

    fun toggleTorch() {
        if (ledBlocked) return
        val on = !_ui.value.torch
        _ui.update { it.copy(torch = on) }
        engine.setTorch(on)
    }

    fun setFlash(flash: Flash) {
        _ui.update { it.copy(flash = flash) }
        applyLed()
    }

    fun setFlashLevel(level: Int) {
        _ui.update { it.copy(flashLevel = level) }
        applyLed()
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
    val videoFps: Int
        get() {
            val settings = _ui.value.settings
            val fps = settings.videoFps.takeIf { fps -> camera?.let { fps in videoRates(it.fixedFps) } ?: true } ?: 30
            // A hot phone records 4K at 30 at most (SPEC §14.1); only Video's session asks for it, so Photo's isn't rebuilt by the heat
            return if (fps > 30 && settings.videoUhd && phone.state.value.hot && _ui.value.mode == Mode.VIDEO) 30 else fps
        }

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
        // Too hot: it would be stopped at once (SPEC §14.1)
        if (phone.state.value.tooHot) {
            _refused.tryEmit(Unit)
            return
        }
        val place = placeNow()
        val capture = _entry.value as? Entry.Capture
        application.appScope.launch {
            if (capture != null) {
                // For another app: into the cache, then Review (SPEC §15.3)
                val video = application.captures.newVideo { file, saved -> if (saved) _review.value = Review.Video(file) } ?: return@launch
                if (engine.startRecording(video.target, rotation, place?.let { it.latitude to it.longitude })) watchLimits(capture, video.file)
                return@launch
            }
            val target = media.newVideo(System.currentTimeMillis()) ?: return@launch
            engine.startRecording(target, rotation, place?.let { it.latitude to it.longitude })
        }
    }

    /** The app's limits on its video (`EXTRA_DURATION_LIMIT`, `EXTRA_SIZE_LIMIT`): the recording stops by itself at either, a little before the size. */
    private fun watchLimits(capture: Entry.Capture, file: File) {
        if (capture.durationLimitMs == null && capture.sizeLimit == null) return
        limitJob?.cancel()
        limitJob = viewModelScope.launch {
            while (true) {
                delay(LIMIT_CHECK_MS)
                val running = engineState.value.recording ?: break
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - running.startedAt - running.pausedMs - (running.pausedAt?.let { now - it } ?: 0)
                val long = capture.durationLimitMs?.let { elapsed >= it } == true
                // The file grows in steps and its index comes last: stopped a second of data before the limit, by the larger of the file and the estimate
                val perSecond = ((engineState.value.video?.bitrate ?: 0) + AUDIO_BITS) / 8L
                val estimate = maxOf(file.length(), elapsed * perSecond / 1000)
                val large = capture.sizeLimit?.let { estimate + perSecond + INDEX_BYTES >= it } == true
                if (long || large) {
                    application.appScope.launch { engine.stopRecording() }
                    break
                }
            }
        }
    }

    /** Review's Retake: back to the viewfinder; a video's file goes. */
    fun retake() {
        (_review.value as? Review.Video)?.file?.delete()
        _review.value = null
    }

    /** Review's Use: the result for the calling app, null when it couldn't be written (the shot stays in Review). */
    suspend fun deliver(): Intent? {
        val capture = _entry.value as? Entry.Capture ?: return null
        return when (val shot = _review.value) {
            is Review.Photo -> application.captures.photoResult(shot.photo, shot.mirror, capture.output)
            is Review.Video -> application.captures.videoResult(shot.file, capture.output)
            null -> null
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
        if (_review.value != null) return
        // The session of another mode still stands (a Night photo being put together keeps it a moment): the shutter waits for this mode's
        if (!sessionMatches) return
        // Nothing more fits: only a recording may still be stopped, a countdown cancelled
        if (phone.state.value.storageFull && !recording && _ui.value.countdown == null) return
        when {
            recording -> record()
            _ui.value.countdown != null -> cancelTimer()
            _ui.value.timer > 0 -> startTimer(_ui.value.timer)
            _ui.value.mode == Mode.VIDEO -> record()
            _ui.value.mode == Mode.NIGHT -> shootNight()
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
        engine.setTorch(_ui.value.torch && !ledBlocked)
        // A session put off by the countdown
        if (configureLater) configure()
        return true
    }

    /**
     * The countdown (SPEC §7.3): the digit each second, a beep and a tick in the last three; with "Countdown with the flash" on, the back flash's LED blinks once a
     * second and four times a second in the last two, going dark half a second before the shot so it doesn't change the exposure (LOGIKA §3.2).
     */
    private fun startTimer(seconds: Int) {
        // Not while the flashlight is on: it stays on through the countdown
        val led = _ui.value.settings.timerFlash && !_ui.value.torch && !ledBlocked && camera?.let { !it.front && it.flash } == true
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
            engine.setTorch(_ui.value.torch && !ledBlocked)
            // A session put off by the countdown comes first; the engine waits for it before taking the shot or starting the recording
            if (configureLater) configure()
            // Storage may have filled during the countdown
            if (phone.state.value.storageFull) return@launch
            when (_ui.value.mode) {
                Mode.VIDEO -> record()
                Mode.NIGHT -> shootNight()
                Mode.PHOTO -> shoot(timed = true)
            }
        }
    }

    private suspend fun blink(left: Int) {
        val pulses = if (left <= 2) 4 else 1
        val period = 1_000L / pulses
        val on = if (pulses == 1) 250L else period / 2
        for (pulse in 0 until pulses) {
            // The last half second stays dark
            val dark = left == 1 && pulse * period >= 500
            // The LED may be blocked during the countdown (heat, battery): it stays off then
            if (!dark && !ledBlocked) engine.setTorch(true)
            delay(on)
            engine.setTorch(false)
            delay(period - on)
        }
    }

    /** The place for a shot taken now, when the setting is on and a fresh fix is there. */
    private fun placeNow() = if (_ui.value.settings.location) location.current() else null

    /**
     * A Night photo (SPEC §9.2, LOGIKA §17): the shutter waits while its frames are gathered (about a second; hold still), then the next one may be taken while
     * the extension puts this one together – shown in the queue at the thumbnail – and saves it. In the app's scope, like every photo.
     */
    private fun shootNight() {
        if (_ui.value.nightGathering) return
        val ui = _ui.value
        val place = placeNow()
        val front = camera?.front == true
        val id = ++nightShots
        _ui.update { it.copy(nightGathering = true, gatherProgress = null, nightSuggested = false) }
        application.appScope.launch {
            shots.withLock {
                val shot = engine.takeNightPhoto(rotation, place) {
                    if (!cleared && (ui.settings.shutterSound || MediaActionSound.mustPlayShutterSound())) sound.play(MediaActionSound.SHUTTER_CLICK)
                    _shutter.tryEmit(Unit)
                }
                if (shot == null) {
                    _ui.update { it.copy(nightGathering = false) }
                    return@withLock
                }
                // The progress on the shutter while this photo gathers, then at the thumbnail while it is the newest one put together
                var gathering = true
                val progress = launch {
                    shot.progress.collect { value ->
                        val share = value / 100f
                        _ui.update { if (gathering) it.copy(gatherProgress = share) else if (newestNight == id) it.copy(nightProgress = share) else it }
                    }
                }
                shot.gathered.await()
                gathering = false
                newestNight = id
                _ui.update { it.copy(nightGathering = false, gatherProgress = null, nightProgress = null, processing = it.processing + 1) }
                // Put together and saved outside the lock: the next Night photo may start meanwhile. Waited for even when the extension didn't say its frames
                // were in (the photo may still come)
                application.appScope.launch {
                    try {
                        val photo = shot.photo.await() ?: return@launch
                        val uri = media.savePhoto(photo.bytes, photo.takenAt, photo.crop, mirror = front && ui.settings.mirror)
                        phone.refreshStorage()
                        if (uri != null && ui.settings.location && place == null) {
                            withTimeoutOrNull(LATE_PLACE_MS) { location.fix.first { it != null && location.current() != null } }?.let { media.addLocation(uri, it) }
                        }
                    } finally {
                        progress.cancel()
                        _ui.update { it.copy(processing = (it.processing - 1).coerceAtLeast(0), nightProgress = if (newestNight == id) null else it.nightProgress) }
                        // The postview stays a moment once the photo is out, then the thumbnail has it (SPEC §9.2)
                        delay(POSTVIEW_HOLD_MS)
                        if (_ui.value.processing == 0 && !_ui.value.nightGathering) engine.clearPostview()
                    }
                }
            }
        }
    }

    /** Night photos asked for, and the newest one being put together (whose progress the thumbnail shows). */
    private var nightShots = 0L

    @Volatile
    private var newestNight = 0L

    /** The engine's session is this viewfinder's mode and camera (after a switch it may still be the last one's for a moment). */
    val sessionMatches: Boolean
        get() {
            val running = engineState.value.request ?: return false
            return running.mode == _ui.value.mode && running.camera.id == camera?.id
        }

    /** The Night suggestion closed: not again until the app starts again (SPEC §9.1). */
    fun closeNightSuggestion() {
        application.nightSuggestionClosed = true
        _ui.update { it.copy(nightSuggested = false) }
    }

    /** Takes a photo and saves it in the app's scope, so leaving the viewfinder right after doesn't lose it. Shots queue up; the shutter never waits. */
    fun shoot(timed: Boolean = false) {
        val ui = _ui.value
        val front = camera?.front == true
        val screenFlash = front && ui.mode == Mode.PHOTO && (ui.flash == Flash.ON || (ui.flash == Flash.AUTO && engineState.value.dark))
        val place = placeNow()
        // In the app's scope: a viewfinder going away right after the shutter (the lock screen's when the screen goes off) doesn't cancel the photo
        application.appScope.launch {
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
                // For another app: Review first, nothing saved here (SPEC §15.3)
                if (_entry.value is Entry.Capture) {
                    val mirror = front && ui.settings.mirror
                    _review.value = Review.Photo(photo, mirror, application.captures.preview(photo, mirror, REVIEW_PX))
                    return@withLock
                }
                val wantPlace = ui.settings.location && place == null
                // A motion photo (LOGIKA §3.3) where the stream runs, not after the timer (its LED blinks in the video) nor with a flash (one lit frame); automatic
                // only where the analysis can tell whether something moved
                val flashFired = screenFlash || (camera?.flash == true && !ui.torch && !ledBlocked && (ui.flash == Flash.ON || (ui.flash == Flash.AUTO && engineState.value.flashNeeded)))
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
                        phone.refreshStorage()
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
        // Review waits for Use or Retake: the keys do nothing (not the volume either, as the shutter they'd be taken for)
        if (_review.value != null) return _ui.value.settings.volumeKeys != VolumeKeys.VOLUME
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
        phone.stop()
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
        const val LIMIT_CHECK_MS = 250L
        const val POSTVIEW_HOLD_MS = 500L
        /** How long the scene stays dark before Night is suggested (SPEC §9.1). */
        const val NIGHT_SUGGEST_MS = 2_000L
        /** The sound's bit rate and a margin for the MP4's index, for the size limit's estimate. */
        const val AUDIO_BITS = 256_000L
        const val INDEX_BYTES = 64_000L
        /** Review's picture: sharp on the screen without decoding the whole photo. */
        const val REVIEW_PX = 2048
    }
}
