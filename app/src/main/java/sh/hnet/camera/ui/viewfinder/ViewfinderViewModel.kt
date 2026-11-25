package sh.hnet.camera.ui.viewfinder

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraMetadata
import android.media.AudioManager
import android.media.MediaActionSound
import android.media.ToneGenerator
import android.os.SystemClock
import android.view.KeyEvent
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ReceiveChannel
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import sh.hnet.camera.CameraApplication
import sh.hnet.camera.CameraSettings
import sh.hnet.camera.Grid
import sh.hnet.camera.MotionPhoto
import sh.hnet.camera.ProValues
import sh.hnet.camera.RawMode
import sh.hnet.camera.VolumeKeys
import sh.hnet.camera.camera.AnalysisFrame
import sh.hnet.camera.camera.BurstFrame
import sh.hnet.camera.camera.CameraRequest
import sh.hnet.camera.camera.CameraStatus
import sh.hnet.camera.camera.CapturedPhoto
import sh.hnet.camera.camera.EngineState
import sh.hnet.camera.camera.Flash
import sh.hnet.camera.camera.FrameAnalyzer
import sh.hnet.camera.camera.MAX_BURST
import sh.hnet.camera.camera.Mode
import sh.hnet.camera.camera.PhoneState
import sh.hnet.camera.camera.PhoneStates
import sh.hnet.camera.capabilities.CameraSpec
import sh.hnet.camera.capabilities.LensLook
import sh.hnet.camera.capabilities.fiftyMp
import sh.hnet.camera.capabilities.lookOf
import sh.hnet.camera.capabilities.model.Aspect
import sh.hnet.camera.capabilities.model.ColorCalibration
import sh.hnet.camera.capabilities.model.Exposure
import sh.hnet.camera.capabilities.model.Metering
import sh.hnet.camera.capabilities.model.PixelSize
import sh.hnet.camera.capabilities.model.Point
import sh.hnet.camera.capabilities.model.ProExposure
import sh.hnet.camera.capabilities.model.SessionLadder
import sh.hnet.camera.capabilities.model.Sizes
import sh.hnet.camera.capabilities.model.VideoChoice
import sh.hnet.camera.capabilities.model.WhiteBalance
import sh.hnet.camera.capabilities.model.ZoomScale
import sh.hnet.camera.media.BurstFiles
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
    /** Pro's long exposure under way (SPEC §11): the shutter counts its time down, the mode row is hidden; null when none. */
    val exposing: Exposing? = null,
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

/** A long exposure under way: when it started (uptime) and how long it takes. */
data class Exposing(val startMs: Long, val durationMs: Long)

/** A still the phone must be held for is under way (a Night photo gathering its frames, a long exposure): what would end it waits. */
val ViewfinderUi.holdingStill: Boolean get() = nightGathering || exposing != null

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

    /** 50 MP DNGs written one at a time (each holds its 60 MB raw image until written), 12 MP ones at most two waiting (25 MB each). */
    private val raw50Saves = Semaphore(1)
    private val rawSaves = Semaphore(2)

    private val _rawOtherLens = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** A photo went without its DNG: the camera took it with another lens than the raw stream's (macro, a near subject at the tele's stop). */
    val rawOtherLens: SharedFlow<Unit> = _rawOtherLens

    /**
     * The lens the session's raw stream is asked of when the zoom's stop doesn't say it: kept while the zoom slides, then the lens the camera reports once
     * the zoom rests for [RAW_LENS_SETTLE_MS] (the Pixel takes the tele from about 4.9×, not at its 5× stop – diag 25 Nov 2026; one lens' raw stream per
     * session, so the slide doesn't rebuild it at every lens). Null: the zoom's stop's lens.
     */
    private var rawLensPick: String? = null
    private var rawLensJob: Job? = null

    private val _photoLost = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** A photo the camera started taking never came (it says nothing about it otherwise). */
    val photoLost: SharedFlow<Unit> = _photoLost

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

    /** A burst under way: its frames so far (the shutter counts them); null when there is none (LOGIKA §18). Apart from [ui]: it changes with every frame. */
    private val _burstCount = MutableStateFlow<Int?>(null)
    val burstCount: StateFlow<Int?> = _burstCount.asStateFlow()

    /** Pro's values as its tiles show them – the automatic ones as the camera or the metering has them – and the meter with both set by hand (LOGIKA §26). */
    data class ProLive(
        val iso: Int? = null,
        val timeNs: Long? = null,
        val meterEv: Double? = null,
        val focusDiopters: Double? = null,
        val kelvin: Int? = null,
        /** The white balance set by hand is in effect (a lens without its calibration nor a fitting preset has the camera's own). */
        val kelvinHeld: Boolean = false,
        /** The physical lens the camera reports (the color calibration is its own), and the one the zoom chose (the focus range is its own). */
        val lens: String? = null,
        val focusLens: String? = null,
    )

    private val _proLive = MutableStateFlow(ProLive())
    val proLive: StateFlow<ProLive> = _proLive.asStateFlow()
    private var proJob: Job? = null
    /** The exposure Pro last gave the engine (null: the camera's own). */
    private var proApplied: Exposure? = null
    /** The focus and white balance Pro last gave the engine, with the camera and lens they were for (null: none sent yet); [held]: the white balance took. */
    private data class ProLook(val cameraId: String, val lens: String?, val focusLens: String?, val focusDiopters: Double?, val kelvin: Int?, val held: Boolean = false)
    private var proLookSent: ProLook? = null
    /** Reading the automatic white balance's temperature, per color calibration (its table is worked out once). */
    private val temperatureReaders = HashMap<ColorCalibration, WhiteBalance.TemperatureReader>()
    /** Focus peaking shows while the focus ruler is open and for a moment after the focus was changed (LOGIKA §27). */
    private var focusRulerOpen = false
    private var peakingUntil = 0L
    /** A lens switch let happen (the stop and the focus it was for), and until when the camera's own focus runs for it. */
    private var lensNudgedFor: Triple<String, Double, Boolean>? = null
    private var lensNudgeUntil = 0L
    /** Photos asked for and not done yet: the camera's own focus doesn't run for a lens switch meanwhile (the photo takes the focus set by hand). */
    private var lensNudgeHeld = 0
    /** A long exposure asked for and not done yet (from the press, before the camera starts it). */
    private var longShot = false

    /** Pro's histogram (luma in bins) and focus peaking's edges, from the analysis stream. */
    val histogram: StateFlow<IntArray?> get() = analyzer.histogram
    val edges: StateFlow<FrameAnalyzer.Edges?> get() = analyzer.edges
    /** The metering's targets are learned apart for an HLG and an SDR picture. */
    private val meterings = mapOf(false to Metering(ProExposure.defaultTarget(hlg = false)), true to Metering(ProExposure.defaultTarget(hlg = true)))

    /** A burst's tick every ten frames; true for the last one it may have (LOGIKA §18). */
    private val _burstTick = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    val burstTick: SharedFlow<Boolean> = _burstTick

    /** A burst's frames put aside and not in the gallery yet: the thumbnail shows them as being processed. */
    val burstsSaving: StateFlow<Int> = application.bursts.pending

    private var burstJob: Job? = null
    /** The shutter (or a volume key) is held: a burst starting after it was let go stops at once. */
    private var held = false
    /** The volume key held for a burst: its release ends the burst instead of taking a photo. */
    private var keyBurst = false

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

    /** The request the engine was told last: the live picture, the square's bands and the photo's frame follow it. */
    private val _shown = MutableStateFlow<CameraRequest?>(null)
    val shown: StateFlow<CameraRequest?> = _shown.asStateFlow()

    /** The last live picture, fading out over a session being rebuilt (LOGIKA §23.3); null when the new session shows its first frame. */
    private val _frozen = MutableStateFlow<FrozenFrame?>(null)
    val frozen: StateFlow<FrozenFrame?> = _frozen.asStateFlow()
    /** No frozen frame for a change: the live picture fades out to the background, until the new session shows its first frame. */
    private val _covering = MutableStateFlow(false)
    val covering: StateFlow<Boolean> = _covering.asStateFlow()
    private var surface: Surface? = null
    private var freezing: Job? = null
    /** Resets of the controls a mode or camera change makes, sent when the change is told (before, the old session's picture would jump in its fade). */
    private var onTold: (() -> Unit)? = null

    init {
        engine.resetControls()
        // The engine outlives this screen: it is told at once what this one wants (back camera, Photo), not only when a setting changes
        configure()
        viewModelScope.launch {
            preferences.camera.collect { settings ->
                val before = _ui.value.settings
                _ui.update { it.copy(settings = settings) }
                // 50 MP however it was set (a restored backup too): at 1×, with the session that comes
                if (fiftyMp) toOneX()
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
        // The frozen frame goes when the new session shows its first frame, or when the camera has a problem instead
        viewModelScope.launch { combine(engineState, _shown, _frozen, _covering) { _, _, _, _ -> }.collect { settle() } }
        viewModelScope.launch {
            var wasRecording = false
            engineState.collect { state ->
                val now = state.recording != null
                // While recording the storage is read every second; a finished video takes space
                phone.fast = now || burstJob != null
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
        dropRawLensWait()
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
        engine.resetFocus(exposure = aeSteps(0))
        analyzer.reset()
        configure()
        watchPro()
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
        // Nor a DNG for another app (SPEC §15.3); the raw stream of the zoom's lens
        // In Pro only (owner, 25 Nov 2026): the DNG beside each photo, or 50 MP alone
        val raw = capture == null && ui.mode == Mode.PRO && settings.rawMode == RawMode.RAW
        return camera?.let {
            val rawLens = if (raw) rawLensPick ?: it.zoom.lensAt(ui.zoom) else null
            val raw50 = capture == null && ui.mode == Mode.PRO && settings.rawMode == RawMode.MP50 && it.fiftyMp
            // 50 MP takes no Ultra HDR photo: the plain still the session keeps for its ladder, and the 50 MP stream's confirmed sets (D22)
            CameraRequest(it, ui.mode, settings.aspect, video, settings.ultraHdr && !raw50, motion = motion, motionFirst = settings.motionPhoto == MotionPhoto.ON, raw = raw, rawLens = rawLens, raw50 = raw50)
        }
    }

    /** Whether two requests are served by one session (the square's bands slide only then). */
    fun sameSession(a: CameraRequest, b: CameraRequest) = engine.sameSession(a, b)

    /** The live picture's buffer size: of the request the engine was told last (a new one is told after the last frame is frozen). */
    fun previewSize(): PixelSize? = _shown.value?.let { engine.previewSize(it) }

    fun retry() = engine.retry()

    /** [freeze]: the last frame is copied first when the change needs a new session (false: the copy is done or gave up). */
    private fun configure(freeze: Boolean = true) {
        // The QR shortcut reads codes even with QR codes off in Settings: it was asked for
        analyzer.qrEnabled = _ui.value.mode == Mode.PHOTO && (_ui.value.settings.qrCodes || _ui.value.pointAtCode)
        // A viewfinder not shown doesn't touch the shared engine (another one may have it); start() configures
        if (!started) return
        // Not under a recording, a countdown, a Night photo gathering its frames or a burst: a new session would cut the video, lose the timed shot, hold a
        // frozen frame over the gathering, or end the burst
        if (recording || _ui.value.countdown != null || _ui.value.holdingStill || burstJob != null) {
            configureLater = true
            // A frame frozen for the change would stay over the live picture until it comes
            _frozen.value = null
            _covering.value = false
            told()
            return
        }
        configureLater = false
        val next = request() ?: return
        // A copy under way tells the engine the newest request when it is done
        if (freezing != null) return
        val before = _shown.value
        if (freeze && before != null && !engine.sameSession(before, next) && freeze(before)) return
        _shown.value = next
        told()
        engine.configure(next)
    }

    private fun told() {
        onTold?.invoke()
        onTold = null
    }

    /** Lifts the frozen frame or the cover once the session told shows its first frame, or the camera has a problem instead. */
    private fun settle() {
        // Not before the change is told (the old session's frames would count)
        if ((_frozen.value == null && !_covering.value) || freezing != null) return
        val state = engineState.value
        val running = state.request
        val shown = _shown.value
        val live = state.status == CameraStatus.Ready && state.frameShown && running != null && shown != null && engine.sameSession(running, shown)
        val problem = state.status is CameraStatus.Busy || state.status is CameraStatus.Blocked || state.status is CameraStatus.Error
        if (live || problem) {
            _frozen.value = null
            _covering.value = false
        }
    }

    /** Copies the live picture before its session goes; the change waits for the copy, at most [FREEZE_WAIT_MS]. False: nothing to copy. */
    private fun freeze(before: CameraRequest): Boolean {
        val surface = surface?.takeIf { it.isValid } ?: return false
        val state = engineState.value
        if (state.status != CameraStatus.Ready || !state.frameShown || _frozen.value != null) return false
        val stream = engine.previewSize(before)?.let { if (Sizes.near(it, Aspect.SIXTEEN_NINE)) Aspect.SIXTEEN_NINE else Aspect.FOUR_THREE } ?: Aspect.FOUR_THREE
        // Started only once it is known as the change under way: a copy refused at once would otherwise run before (the collector would take the old
        // session's frames for the new one's)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val picture = withTimeoutOrNull(FREEZE_WAIT_MS) {
                suspendCancellableCoroutine { cont -> FrozenFrame.copy(surface, application.mainExecutor) { cont.resume(it) { _, bitmap, _ -> bitmap?.recycle() } } }
            }
            if (picture != null) {
                _frozen.value = FrozenFrame(picture, stream, before.pictureAspect, before.camera.sensorOrientation, before.camera.front)
            } else {
                // No copy (refused, or late): the live picture, still running, fades out first (a frame more: the fade starts with the next one)
                _covering.value = true
                delay(COVER_FADE_MS.toLong() + FRAME_MS)
            }
        }
        freezing = job
        job.invokeOnCompletion {
            if (freezing === job) freezing = null
            configure(freeze = false)
            // A change undone meanwhile tells the engine nothing new: nothing else would lift the frame or the cover
            settle()
        }
        job.start()
        return true
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
        engine.resetFocus(exposure = aeSteps(_ui.value.exposure))
        engine.start(this)
        watchPro()
        sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let { sensors.registerListener(gravityListener, it, SensorManager.SENSOR_DELAY_UI) }
        updateLocation()
        viewModelScope.launch { media.refreshLast() }
    }

    fun stop() {
        started = false
        dropRawLensWait()
        proJob?.cancel()
        proJob = null
        // Leaving ends a burst (its frames taken are saved)
        keyBurst = false
        shutterReleased()
        freezing?.cancel()
        _frozen.value = null
        _covering.value = false
        // start() pushes all the controls again
        onTold = null
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

    fun setPreviewSurface(surface: Surface, size: PixelSize) {
        this.surface = surface
        engine.setPreviewSurface(surface, size)
    }

    fun clearPreviewSurface(surface: Surface) {
        if (this.surface == surface) this.surface = null
        engine.clearPreviewSurface(surface)
    }

    private val recording: Boolean get() = engineState.value.recording != null

    fun selectMode(mode: Mode) {
        if (mode == _ui.value.mode || recording || _entry.value is Entry.Capture || _ui.value.holdingStill || burstJob != null) return
        // Night only on a camera with the extension, Pro only on one with the manual sensor
        if (mode == Mode.NIGHT && camera?.night == null) return
        if (mode == Mode.PRO && camera?.manualSensor != true) return
        cancelTimer()
        dropRawLensWait()
        _ui.update { it.copy(mode = mode, focus = null, locked = false, exposure = 0, torch = false, pointAtCode = false) }
        // Into Pro with 50 MP: at 1×
        if (fiftyMp) _ui.update { it.copy(zoom = 1.0) }
        analyzer.reset()
        // After any resets still waiting (a camera switch just before, its zoom back to 1×)
        val before = onTold
        onTold = {
            before?.invoke()
            engine.setTorch(false)
            engine.setZoom(_ui.value.zoom)
            engine.resetFocus(exposure = aeSteps(0))
        }
        configure()
        watchPro()
    }

    fun switchCamera() {
        if (cameras.size < 2 || recording || _ui.value.holdingStill || burstJob != null) return
        cancelTimer()
        val next = (_ui.value.cameraIndex + 1) % cameras.size
        // Night stays only where the other camera has it too, Pro where it has the manual sensor
        val mode = when {
            _ui.value.mode == Mode.NIGHT && cameras[next].night == null -> Mode.PHOTO
            _ui.value.mode == Mode.PRO && !cameras[next].manualSensor -> Mode.PHOTO
            else -> _ui.value.mode
        }
        // The Night suggestion only where the camera has Night
        dropRawLensWait()
        _ui.update { it.copy(cameraIndex = next, mode = mode, zoom = 1.0, focus = null, locked = false, exposure = 0, torch = false, flashLevel = null, nightSuggested = it.nightSuggested && cameras[next].night != null) }
        val before = onTold
        onTold = {
            before?.invoke()
            engine.setTorch(false)
            engine.setZoom(_ui.value.zoom)
            engine.resetFocus(exposure = aeSteps(0))
            applyLed()
        }
        analyzer.reset()
        configure()
        watchPro()
    }

    /**
     * Pro's values while it is shown (LOGIKA §26), ten times a second: both automatic – the camera's own exposure with the compensation, its values shown and
     * the metering's target learned from it; one set by hand – the other from the metering; both by hand – as they are, with the meter.
     */
    private fun watchPro() {
        proJob?.cancel()
        proJob = null
        // Sent again whatever was sent before: another viewfinder may have reset the engine's controls meanwhile
        proApplied = null
        proLookSent = null
        lensNudgedFor = null
        lensNudgeUntil = 0L
        if (!started) {
            // The engine may be another viewfinder's now (its values aren't to be cleared); a start sends everything again
            analyzer.histogramEnabled = false
            analyzer.peakingEnabled = false
            return
        }
        if (_ui.value.mode != Mode.PRO) {
            engine.setManualExposure(null)
            engine.setManualFocus(null)
            engine.setManualWhiteBalance(null)
            analyzer.histogramEnabled = false
            analyzer.peakingEnabled = false
            return
        }
        meterings.values.forEach { it.reset() }
        proJob = viewModelScope.launch {
            while (true) {
                proStep()
                delay(PRO_STEP_MS)
            }
        }
    }

    private fun proStep() {
        // A mode changed some other way (a new launch): the loop ends
        if (!started || _ui.value.mode != Mode.PRO) {
            watchPro()
            return
        }
        // Only the viewfinder whose frames the engine analyses: another one (the lock screen's) may have the camera now
        if (engine.analyzer !== analyze) return
        val camera = camera ?: return
        val settings = _ui.value.settings
        val now = engine.liveNow.value
        val lens = now?.lens
        // The focus range is the lens' taking the picture, but not the ultrawide the camera went to by itself (macro: a manual focus nearer than the main
        // lens reaches makes the Pixel switch, and its range would then hold it there) – then the zoom's lens
        val zoom = _ui.value.zoom
        val ultrawideStop = camera.zoom.ultrawide?.ratio
        val macro = lens != null && lens in camera.ultrawideIds && (ultrawideStop == null || zoom > ultrawideStop + 1e-6)
        val focusLens = if (lens == null || macro) camera.zoom.lensAt(zoom) else lens
        // The lens switch only from this session's own report: right after a mode change or a start the last session's lens is still there
        val nudging = lensNudge(camera, settings, zoom, now?.takeIf { it.session == engine.sessionNumber }?.lens)
        val sent = proLook(camera, settings, lens, focusLens, nudging)
        val lensLook = camera.lookOf(lens)
        analyzer.histogramEnabled = settings.proHistogram
        // The histogram counts only the photo's part of the frame
        analyzer.histogramAspect = settings.aspect.ratio
        analyzer.peakingEnabled = settings.proPeaking && focusable(camera, camera.lookOf(focusLens)) && (focusRulerOpen || SystemClock.uptimeMillis() < peakingUntil)
        val look = { live: ProLive ->
            live.copy(
                // While the camera's own focus runs for a lens switch, the tile keeps the focus set by hand (it comes back)
                focusDiopters = (if (nudging) settings.proFocusDiopters else sent.focusDiopters) ?: now?.focusDiopters,
                kelvin = sent.kelvin?.takeIf { sent.held } ?: lensLook.colorCalibration?.let { c ->
                    now?.gainRed?.let { r -> now.gainBlue?.let { b -> temperatureReaders.getOrPut(c) { WhiteBalance.TemperatureReader(c) }.read(r, b) } }
                },
                kelvinHeld = sent.held,
                lens = lens,
                focusLens = focusLens,
            )
        }
        if (!camera.manualSensor || camera.isoRange.isEmpty() || camera.shutterRange.isEmpty()) {
            // Focus and white balance still show (the exposure tiles aren't offered)
            _proLive.value = look(ProLive())
            return
        }
        val iso = settings.proIso?.coerceIn(camera.isoRange)
        val time = settings.proShutterNs?.coerceIn(camera.shutterRange)
        val ev = settings.proEvThirds
        // A recent frame's brightness and the exposure that very frame had (no analysis stream in the session: no metering)
        val measured = analyzer.brightness?.takeIf { engineState.value.analysis }
        val seen = measured?.let { engine.exposureAt(analyzer.brightnessFrameNs) }
        val metering = meterings.getValue(analyzer.brightnessHlg)
        val shown = now?.exposure
        _proLive.value = look(when {
            iso == null && time == null -> {
                applyPro(null)
                engine.setExposure(aeSteps(0))
                // What the camera's settled exposure brings the picture to, its compensation taken out, is what the metering aims at (not while it meters a
                // tapped point: the metering is centre-weighted)
                if (measured != null && seen?.settled == true && _ui.value.focus == null) metering.learn(measured / 2.0.pow(ev / 3.0))
                ProLive(shown?.iso, shown?.timeNs)
            }
            iso != null && time != null -> {
                val photo = Exposure(iso, time)
                applyPro(photo)
                ProLive(iso, time, if (measured != null && seen != null) metering.meter(measured, seen.exposure, photo) else null)
            }
            else -> {
                val photo = if (measured != null && seen != null) {
                    metering.step(measured, seen.exposure, ev, iso, time, camera.isoRange, camera.shutterRange)
                } else {
                    // No metering: the camera's last exposure kept, the value not set by hand taken from it
                    shown?.let { held(it, iso, time, camera) }
                }
                photo?.let { applyPro(it, metered = true) }
                val applied = photo ?: proApplied
                ProLive(applied?.iso ?: iso, applied?.timeNs ?: time)
            }
        })
    }

    private fun focusable(camera: CameraSpec, look: LensLook) = camera.autofocus && look.minimumFocusDiopters > 0

    /**
     * Whether the camera's own focus runs for a moment now, to let it switch lenses: with the focus set by hand the Pixel keeps its lens while the zoom
     * slides onto another lens' stop (it switches only with its own focus running, or on a jump – the owner, 25 Nov 2026). Once when the zoom comes to the
     * stop, and once more if the focus set comes within that lens' reach there (not at every step of the focus ruler: the camera's focus would fight it),
     * until it is the lens reported or [LENS_NUDGE_MS] pass; then the focus set by hand is sent again. A photo pauses it, and the stop nudges again after it.
     */
    private fun lensNudge(camera: CameraSpec, settings: CameraSettings, zoom: Double, lens: String?): Boolean {
        val focus = settings.proFocusDiopters
        val stop = camera.zoom.stops.firstOrNull { abs(it.ratio - zoom) < 1e-6 }
        val target = stop?.lens
        if (focus == null || stop == null || target == null || lens == null) {
            // Off the stop, or a new session not reporting yet (a rebuild would otherwise end the nudge for good): the stop nudges again
            if (stop == null || lens == null) lensNudgedFor = null
            lensNudgeUntil = 0L
            return false
        }
        val now = SystemClock.uptimeMillis()
        if (lens == target) {
            lensNudgeUntil = 0L
            return false
        }
        // Not while a photo is being taken; an unfinished switch runs again once it is done
        if (lensNudgeHeld > 0) {
            lensNudgeUntil = 0L
            return false
        }
        val reachable = focus <= camera.lookOf(target).minimumFocusDiopters
        val key = Triple(camera.id, stop.ratio, reachable)
        if (lensNudgedFor != key) {
            lensNudgedFor = key
            if (reachable) lensNudgeUntil = now + LENS_NUDGE_MS
        }
        return now < lensNudgeUntil
    }

    /**
     * Pro's focus and white balance to the engine when they changed – the setting, the camera, or a lens – and what was sent. The focus range is
     * [focusLens]'s, the color calibration the reported lens' ([lens]: the one taking the picture; the gains and matrix are worked out once per change). Without its color calibration the temperature picks the camera's nearest preset it has; without
     * one, the camera's own balance stays.
     */
    private fun proLook(camera: CameraSpec, settings: CameraSettings, lens: String?, focusLens: String?, nudging: Boolean = false): ProLook {
        val lensLook = camera.lookOf(lens)
        val focusLook = camera.lookOf(focusLens)
        val focus = settings.proFocusDiopters?.takeIf { focusable(camera, focusLook) && !nudging }?.coerceIn(0.0, focusLook.minimumFocusDiopters)
        val kelvin = settings.proKelvin
        val wanted = ProLook(camera.id, lens, focusLens, focus, kelvin)
        proLookSent?.let { if (it.copy(held = false) == wanted) return it }
        engine.setManualFocus(focus)
        val balance = kelvin?.let { k -> lensLook.colorCalibration?.let { WhiteBalance.of(k.toDouble(), it) } }
        val preset = if (kelvin != null && balance == null) awbPreset(kelvin, camera.awbModes) else null
        engine.setManualWhiteBalance(balance, preset)
        return wanted.copy(held = balance != null || preset != null).also { proLookSent = it }
    }

    /** The camera's own white balance preset nearest to a temperature, of those it has (a lens without its color calibration); null: none. */
    private fun awbPreset(kelvin: Int, available: Set<Int>): Int? = listOf(
        2800 to CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
        4000 to CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT,
        5500 to CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
        6500 to CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
        7500 to CameraMetadata.CONTROL_AWB_MODE_SHADE,
    ).filter { it.second in available }.minByOrNull { abs(it.first - kelvin) }?.second

    /** The exposure for a value set by hand that keeps [exposure]'s brightness. */
    private fun held(exposure: Exposure, iso: Int?, timeNs: Long?, camera: CameraSpec): Exposure? = when {
        iso != null -> Exposure(iso, (exposure.product / iso * 1e9).toLong().coerceIn(camera.shutterRange.first, minOf(camera.shutterRange.last, ProExposure.AUTO_SHUTTER_MAX_NS)))
        timeNs != null -> Exposure((exposure.product / (timeNs / 1e9)).toInt().coerceIn(camera.isoRange), timeNs)
        else -> null
    }

    /**
     * Pro's exposure to the engine. A metered one ([metered]) only when it changed enough to matter – a request a tenth of a second for a few per cent would
     * only churn – and the value set by hand stayed; a value set by hand always.
     */
    private fun applyPro(exposure: Exposure?, metered: Boolean = false) {
        val last = proApplied
        if (exposure == null) {
            engine.setManualExposure(null)
            proApplied = null
            return
        }
        if (metered && last != null) {
            if (last.iso == exposure.iso && abs(ln(exposure.timeNs.toDouble() / last.timeNs)) < PRO_SETTLE) return
            if (last.timeNs == exposure.timeNs && abs(ln(exposure.iso.toDouble() / last.iso)) < PRO_SETTLE) return
        }
        proApplied = exposure
        engine.setManualExposure(exposure)
    }

    /** The camera's exposure compensation: Pro's in Pro (thirds of a stop turned into the camera's steps), else [base]. */
    private fun aeSteps(base: Int): Int {
        if (_ui.value.mode != Mode.PRO) return base
        val camera = camera ?: return base
        return (_ui.value.settings.proEvThirds / 3.0 / camera.exposureStep).roundToInt().coerceIn(camera.exposureSteps)
    }

    /** Pro with a value set by hand: the camera's own exposure is off. */
    private val proManual: Boolean
        get() = _ui.value.mode == Mode.PRO && (_ui.value.settings.proIso != null || _ui.value.settings.proShutterNs != null)

    /** Pro's ISO set by hand, or automatic again (null); kept until reset (D20). */
    fun setProIso(iso: Int?) {
        viewModelScope.launch { preferences.setProIso(iso) }
        meterings.values.forEach { it.reset() }
    }

    /** Pro's shutter set by hand, or automatic again (null). */
    fun setProShutter(timeNs: Long?) {
        viewModelScope.launch { preferences.setProShutter(timeNs) }
        meterings.values.forEach { it.reset() }
    }

    fun setProEv(thirds: Int) {
        viewModelScope.launch { preferences.setProEv(thirds) }
    }

    /** Pro's focus set by hand in diopters (0: infinity), or automatic again (null); the peaking shows a moment after. */
    fun setProFocus(diopters: Double?) {
        viewModelScope.launch { preferences.setProFocus(diopters) }
        peakingUntil = SystemClock.uptimeMillis() + PEAKING_AFTER_MS
    }

    /** Pro's white balance in kelvins, or automatic again (null). */
    fun setProKelvin(kelvin: Int?) {
        viewModelScope.launch { preferences.setProKelvin(kelvin) }
    }

    /** Focus peaking on or off (the switch at the focus ruler's end); kept. */
    fun setProPeaking(on: Boolean) {
        viewModelScope.launch { preferences.setProPeaking(on) }
    }

    /** The DNG next to each photo (Photo and Pro); kept. */
    fun setRawMode(mode: RawMode) {
        dropRawLensWait()
        if (mode == RawMode.MP50 && camera?.fiftyMp == true && _ui.value.mode == Mode.PRO) toOneX()
        viewModelScope.launch { preferences.setRawMode(mode) }
    }

    /** Pro's 50 MP is chosen and the camera has it: the main lens at 1× only (D22: the zoom row hidden, HDR off). */
    val fiftyMp: Boolean
        get() = _ui.value.mode == Mode.PRO && _ui.value.settings.rawMode == RawMode.MP50 && camera?.fiftyMp == true && _entry.value !is Entry.Capture

    /** The zoom back at 1× for 50 MP, sent with the session that comes (the frozen frame covers the change). */
    private fun toOneX() {
        if (abs(_ui.value.zoom - 1.0) < 1e-6) return
        val reset = lensOf(1.0) != lensOf(_ui.value.zoom) && (_ui.value.focus != null || _ui.value.exposure != 0)
        _ui.update { if (reset) it.copy(zoom = 1.0, focus = null, locked = false, exposure = 0) else it.copy(zoom = 1.0) }
        val pending = onTold
        onTold = {
            pending?.invoke()
            engine.setZoom(_ui.value.zoom)
            if (reset) engine.resetFocus(exposure = aeSteps(0))
        }
    }

    fun setProHistogram(on: Boolean) {
        viewModelScope.launch { preferences.setProHistogram(on) }
    }

    /** The focus ruler opened or closed: the peaking shows while it is open, and a moment after. */
    fun focusRuler(open: Boolean) {
        if (focusRulerOpen && !open) peakingUntil = SystemClock.uptimeMillis() + PEAKING_AFTER_MS
        focusRulerOpen = open
    }

    /** All of Pro automatic (`restart_alt`); returns what was set, for the snackbar's Undo. */
    fun resetPro(): ProValues {
        val settings = _ui.value.settings
        viewModelScope.launch { preferences.setPro(ProValues.AUTO) }
        return ProValues(settings.proIso, settings.proShutterNs, settings.proEvThirds, settings.proFocusDiopters, settings.proKelvin)
    }

    fun restorePro(values: ProValues) {
        viewModelScope.launch { preferences.setPro(values) }
        meterings.values.forEach { it.reset() }
    }

    /** A zoom value from the ruler or a stop; a different lens (another stop is the nearest one below) ends the tapped focus and the exposure compensation. */
    fun zoomTo(ratio: Double, jump: Boolean = false) {
        // 50 MP is the main lens' whole sensor only
        if (fiftyMp) return
        val scale = camera?.zoom ?: return
        val value = ratio.coerceIn(scale.min, scale.max.coerceAtMost(scale.end))
        val before = lensOf(_ui.value.zoom)
        _ui.update { it.copy(zoom = value) }
        val told = toldRawLens()
        // Another lens ends the tapped focus and the exposure compensation
        val reset = lensOf(value) != before && (_ui.value.focus != null || _ui.value.exposure != 0)
        if (reset) _ui.update { it.copy(focus = null, locked = false, exposure = 0) }
        when {
            jump && told != null && scale.lensAt(value) != told -> {
                // A stop tapped with the DNG on, another lens' raw stream: the picture covered first, the lens and its session together, then the picture
                // back (owner, 25 Nov 2026: not the switch seen and then covered); the zoom sent then is the newest one
                dropRawLensWait()
                val pending = onTold
                onTold = {
                    pending?.invoke()
                    engine.setZoom(_ui.value.zoom)
                    if (reset) engine.resetFocus(exposure = aeSteps(0))
                }
                configure()
            }
            // A change being covered: the zoom goes with it (sent now it would show on the old session, or be overwritten by the change's)
            onTold != null -> {
                val pending = onTold
                onTold = {
                    pending?.invoke()
                    engine.setZoom(_ui.value.zoom)
                    if (reset) engine.resetFocus(exposure = aeSteps(0))
                }
            }
            else -> {
                engine.setZoom(value)
                if (reset) engine.resetFocus(exposure = aeSteps(0))
                followRawLens(scale)
            }
        }
    }

    /** The lens of the raw stream the engine was last told (null: no DNG asked for, or no session told yet). */
    private fun toldRawLens(): String? {
        if (_ui.value.settings.rawMode != RawMode.RAW || _ui.value.mode != Mode.PRO || _entry.value is Entry.Capture) return null
        return _shown.value?.takeIf { it.raw }?.rawLens
    }

    /**
     * A slide with the DNG on: the session's lens is kept while the zoom moves; once it rests, the lens the camera reports for it (else the stop's), and a
     * new session when that is another one.
     */
    private fun followRawLens(scale: ZoomScale) {
        val told = toldRawLens() ?: return
        rawLensJob?.cancel()
        rawLensPick = told
        rawLensJob = viewModelScope.launch {
            delay(RAW_LENS_SETTLE_MS)
            // Pro's manual focus: the camera's own focus lets it switch lenses for a moment; the pick waits for it to end
            withTimeoutOrNull(LENS_NUDGE_MS + RAW_LENS_SETTLE_MS) { while (SystemClock.uptimeMillis() < lensNudgeUntil) delay(PRO_STEP_MS) }
            pickRawLens(scale, told, freeze = true)
            // A switch the camera makes a little later (its own focus taking its time): looked at once more
            delay(RAW_LENS_RECHECK_MS)
            rawLensJob = null
            toldRawLens()?.let { now -> if (engine.activeLens != null && engine.activeLens != now && engine.activeLens in camera?.rawLenses.orEmpty()) pickRawLens(scale, now, freeze = true) }
        }
    }

    /** The lens the camera takes the picture with now (this session's report, one of the lenses with a raw stream), else the zoom's stop's. */
    private fun pickRawLens(scale: ZoomScale, told: String, freeze: Boolean) {
        val camera = camera ?: return
        val reported = engine.activeLens?.takeIf { it in camera.rawLenses }
        val lens = reported ?: scale.lensAt(_ui.value.zoom) ?: return
        rawLensPick = lens
        if (lens != told) configure(freeze)
    }

    /** A lens change of the DNG's raw stream still waiting: decided now, without the frozen frame (a photo takes it: the engine must know before the shot). */
    private fun settleRawLens() {
        if (rawLensJob == null) return
        rawLensJob?.cancel()
        rawLensJob = null
        val scale = camera?.zoom ?: return
        toldRawLens()?.let { pickRawLens(scale, it, freeze = false) }
    }

    /** Forgets the slide's lens (a stop tapped, another camera or mode, the DNG switched: the stop's lens again). */
    private fun dropRawLensWait() {
        rawLensJob?.cancel()
        rawLensJob = null
        rawLensPick = null
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
        // Night's extension has no exposure lock, nor has an exposure set by hand in Pro: a long press focuses there as a tap does
        if (_ui.value.mode == Mode.NIGHT || proManual) {
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

    /** A burst may start: in Photo from the app (not another app's photo), without the timer, nothing else under way. */
    private val burstable: Boolean
        get() = _ui.value.mode == Mode.PHOTO && _entry.value !is Entry.Capture && _ui.value.timer == 0 && _ui.value.countdown == null && _review.value == null && !_ui.value.holdingStill

    /**
     * The shutter held (LOGIKA §18, D14, D15): a burst in Photo, in the photo's format (Ultra HDR with HDR on, plain JPEG at up to 30 fps with it off), until it
     * is let go or has [MAX_BURST] frames. No flash, timer or motion photo in it. Held where no burst may start, it is a plain press (so is a burst without
     * room for it). Returns false when it did nothing at all (a volume key's release is then a press).
     */
    fun shutterHeld(): Boolean {
        if (_review.value != null) return false
        held = true
        // A change waiting for its frozen frame first (the burst would go to the old session); let go meanwhile, nothing starts
        freezing?.let { job ->
            viewModelScope.launch {
                job.join()
                if (held) shutterHeld()
            }
            return true
        }
        val free = phone.state.value.freeBytes
        if (!burstable || (free != null && free < BURST_RESERVE_BYTES)) {
            shutterPressed()
            return true
        }
        if (!sessionMatches || burstJob != null) return false
        val ui = _ui.value
        val place = placeNow()
        val front = camera?.front == true
        val clicks = ui.settings.shutterSound || MediaActionSound.mustPlayShutterSound()
        val startedAt = System.currentTimeMillis()
        val bursts = application.bursts
        // In the app's scope, as every photo (the screen going away doesn't cut the frames already taken), on the main thread with the viewfinder's state;
        // known as the burst under way before it runs
        val job = application.appScope.launch(Dispatchers.Main.immediate, start = CoroutineStart.LAZY) {
            phone.fast = true
            var frames: ReceiveChannel<BurstFrame>? = null
            var files: BurstFiles? = null
            try {
                val shot = engine.startBurst(rotation, place) ?: return@launch
                frames = shot.frames
                if (!held) engine.stopBurst()
                val burst = BurstFiles(startedAt, UUID.randomUUID().toString(), shot.crop, mirror = front && ui.settings.mirror)
                files = burst
                var first = -1L
                var index = 0
                _burstCount.value = 0
                for (frame in shot.frames) {
                    if (first < 0) first = frame.sensorTimeNs
                    val takenAt = startedAt + (frame.sensorTimeNs - first) / 1_000_000
                    if (!bursts.stage(burst, index, frame.bytes, takenAt)) {
                        engine.stopBurst()
                        continue
                    }
                    index++
                    _burstCount.value = index
                    if (index == 1) _shutter.tryEmit(Unit)
                    // The click at the first frame and every tenth: one per frame would rattle at 15–30 fps
                    if ((index == 1 || index % BURST_TICK == 0) && clicks && !cleared) sound.play(MediaActionSound.SHUTTER_CLICK)
                    if (index % BURST_TICK == 0) _burstTick.tryEmit(index >= MAX_BURST)
                    // Room for the frames put aside and their copies in the gallery: below it the burst ends (SPEC §14.1)
                    val left = phone.state.value.freeBytes
                    if (left != null && left < BURST_RESERVE_BYTES) engine.stopBurst()
                }
            } finally {
                // A loop left early doesn't leave the engine's collector waiting to hand on a frame
                frames?.cancel()
                files?.let { bursts.ended(it) }
                _burstCount.value = null
                phone.fast = recording
                phone.refreshStorage()
                // A change put off by the burst (the job is still the one under way here: the check in configure() is left by clearing it first)
                if (burstJob === coroutineContext[Job]) burstJob = null
                if (configureLater) configure()
            }
        }
        burstJob = job
        job.invokeOnCompletion { if (burstJob === job) burstJob = null }
        job.start()
        return true
    }

    /** The shutter let go: a burst under way ends (the stills already in flight still come). */
    fun shutterReleased() {
        held = false
        if (burstJob != null) engine.stopBurst()
    }

    /** The shutter: a recording ends, a running countdown is cancelled, a set timer starts one, else the photo is taken or the recording started. */
    fun shutterPressed() {
        // A press during a burst (a volume key while the finger holds the shutter) does nothing: the two would take each other's stills
        // Nor during a long exposure: it can't be ended early (one frame of the camera's), and another would queue behind it
        if (_review.value != null || burstJob != null || _ui.value.exposing != null || longShot) return
        // A change waiting for its frozen frame isn't told to the engine yet: the shot waits for it (at most the copy's time), not taken on the old session
        freezing?.let { job ->
            viewModelScope.launch {
                job.join()
                shutterPressed()
            }
            return
        }
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
            // A session put off by the countdown comes first, told at once (no frozen frame to wait for); the engine waits for it before taking the shot or
            // starting the recording
            if (configureLater) configure(freeze = false)
            // Storage may have filled during the countdown
            if (phone.state.value.storageFull) return@launch
            when (_ui.value.mode) {
                Mode.VIDEO -> record()
                Mode.NIGHT -> shootNight()
                Mode.PHOTO, Mode.PRO -> shoot(timed = true)
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
                // Read by the progress collector on another thread
                val gathering = AtomicBoolean(true)
                val progress = launch {
                    shot.progress.collect { value ->
                        val share = value / 100f
                        _ui.update { if (gathering.get()) it.copy(gatherProgress = share) else if (newestNight == id) it.copy(nightProgress = share) else it }
                    }
                }
                shot.gathered.await()
                gathering.set(false)
                newestNight = id
                _ui.update { it.copy(nightGathering = false, gatherProgress = null, nightProgress = null, processing = it.processing + 1) }
                // A change put off by the gathering
                withContext(Dispatchers.Main) { if (configureLater) configure() }
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
        // The front camera's screen flash in Photo, and in Pro while the camera's exposure is its own (it meters for the lit screen)
        val screenFlash = front && (ui.mode == Mode.PHOTO || (ui.mode == Mode.PRO && !proManual)) && (ui.flash == Flash.ON || (ui.flash == Flash.AUTO && engineState.value.dark))
        val place = placeNow()
        // The DNG's lens the zoom has just moved to: its session first (the shot waits for it)
        settleRawLens()
        // A lens switch let happen by the camera's own focus pauses for the photo (it takes the focus set by hand); unfinished, the stop nudges again after it
        lensNudgeHeld++
        if (SystemClock.uptimeMillis() < lensNudgeUntil) {
            lensNudgeUntil = 0L
            lensNudgedFor = null
            proLookSent = null
            proStep()
        }
        // A long exposure set by hand (LOGIKA §26): further presses wait for it from now (another would queue behind it); its countdown starts with the request
        val long = ui.mode == Mode.PRO && ui.settings.proShutterNs?.let { it >= LONG_EXPOSURE_NS } == true
        if (long) longShot = true
        val done = AtomicBoolean(false)
        val clicked = AtomicBoolean(false)
        // A DNG's raw image is held until it is written: at most one 50 MP (60 MB) and two 12 MP ones (25 MB each) at a time; the next shot waits its turn
        val gate = when {
            fiftyMp -> raw50Saves
            ui.mode == Mode.PRO && ui.settings.rawMode == RawMode.RAW -> rawSaves
            else -> null
        }
        val released = AtomicBoolean(false)
        val release = { if (gate != null && released.compareAndSet(false, true)) gate.release() }
        // In the app's scope: a viewfinder going away right after the shutter (the lock screen's when the screen goes off) doesn't cancel the photo
        application.appScope.launch {
            shots.withLock {
                gate?.acquire()
                if (screenFlash) {
                    // The screen lit white at full brightness first – counted from when the overlay is really there – then the exposure metered for it (SPEC §7.2)
                    _ui.update { it.copy(screenFlash = true) }
                    withTimeoutOrNull(SCREEN_FLASH_WAIT_MS) { screenLit.first { it } }
                    delay(SCREEN_FLASH_WARM_MS)
                }
                val photo = try {
                    engine.takePhoto(rotation, place, precapture = screenFlash) { start ->
                        val longNs = start.exposureNs?.takeIf { it >= LONG_EXPOSURE_NS }
                        // The request's word only for a long time: a shorter one clicks when the camera starts it (a click before it would let the phone go
                        // down while the request still waits behind the preview's)
                        if (!start.measured && longNs == null) return@takePhoto
                        // Once per shot: a long time is told as its request goes, then by the camera
                        if (clicked.compareAndSet(false, true)) {
                            // The click where the setting wants it or the law does
                            if (!cleared && (ui.settings.shutterSound || MediaActionSound.mustPlayShutterSound())) sound.play(MediaActionSound.SHUTTER_CLICK)
                            _shutter.tryEmit(Unit)
                        }
                        // Counted down from the request, then from the camera's word of the real start (its sensor's time); a word that comes once that count
                        // is over (the Pixel's, after the exposure) is left out: the ring would go back from spinning for a moment
                        longNs?.let { ns ->
                            val ms = ns / 1_000_000
                            _ui.update {
                                val keep = done.get() || (it.exposing != null && (!start.measured || SystemClock.uptimeMillis() >= start.uptimeMs + ms))
                                if (keep) it else it.copy(exposing = Exposing(start.uptimeMs, ms))
                            }
                        }
                    }.also {
                        // Started and never delivered: said, not lost in silence
                        if (it == null && clicked.get()) _photoLost.tryEmit(Unit)
                        if (it?.rawOtherLens == true) _rawOtherLens.tryEmit(Unit)
                    }
                } catch (e: Throwable) {
                    // The turn of the DNG's memory given back however the shot ended
                    release()
                    throw e
                } finally {
                    done.set(true)
                    if (screenFlash) _ui.update { it.copy(screenFlash = false) }
                    if (long || _ui.value.exposing != null) {
                        _ui.update { it.copy(exposing = null) }
                        // A change put off by the exposure
                        withContext(Dispatchers.Main) {
                            longShot = false
                            if (configureLater) configure()
                        }
                    }
                } ?: run {
                    release()
                    return@withLock
                }
                // Pro's 50 MP: the DNG alone, made from the raw image in the background (rearranged and written, a second or two); in the thumbnail once its
                // turn is given back
                photo.raw50?.let { shot ->
                    application.appScope.launch {
                        val uri = try {
                            media.saveRaw50(shot, photo.takenAt)
                        } finally {
                            release()
                        }
                        if (uri == null) _photoLost.tryEmit(Unit) else media.showRaw50(uri, photo.takenAt)
                        phone.refreshStorage()
                    }
                    return@withLock
                }
                // For another app: Review first, nothing saved here (SPEC §15.3)
                if (_entry.value is Entry.Capture) {
                    val mirror = front && ui.settings.mirror
                    // Another app gets no DNG (its request has none)
                    photo.raw?.close()
                    release()
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
                        val saved = try {
                            media.savePhoto(photo.bytes, photo.takenAt, photo.crop, mirror, photo.longExposureNs)?.also {
                                // Its DNG beside it, named as the photo (after it: the thumbnail shows the photo at once); none without the photo
                                photo.raw?.let { raw -> media.saveRaw(raw, photo.takenAt) }
                            }
                        } finally {
                            photo.raw?.close()
                            release()
                        }
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
        }.invokeOnCompletion { application.mainExecutor.execute { lensNudgeHeld-- } }
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
                // Held (the key's first repeat) where a burst may start: a burst until it is let go; else a press on release
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 1 && burstable && !keyBurst) keyBurst = shutterHeld()
                if (event.action == KeyEvent.ACTION_UP) {
                    if (keyBurst) {
                        keyBurst = false
                        shutterReleased()
                    } else {
                        shutterPressed()
                    }
                }
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
        // Not while a long exposure is taken (the zoom row is hidden then too)
        if (_ui.value.holdingStill) return
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
        // Between the ultrawide and 1× a jump, as its button
        zoomTo(next, jump = (zoom < start - 1e-6) != (next < start - 1e-6))
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
        /** A copy of the live picture that takes longer isn't waited for: the live picture fades out instead before the change goes on. */
        const val FREEZE_WAIT_MS = 100L
        /** Pro's metering steps this often. */
        const val PRO_STEP_MS = 100L
        /** A change of Pro's exposure smaller than this (in natural log, about 6 %) isn't sent. */
        const val PRO_SETTLE = 0.06
        /** A burst ticks (and clicks) every this many frames. */
        const val BURST_TICK = 10
        /** A burst needs room for its frames put aside and their copies in the gallery (150 Ultra HDR frames are about 300 MB each way). */
        const val BURST_RESERVE_BYTES = 400L * 1024 * 1024
        const val FRAME_MS = 17L
        const val FOCUS_HOLD_DEGREES = 10.0
        /** A photo exposed this long or longer counts its time down on the shutter (from half a second, the first step the eye waits for). */
        const val LONG_EXPOSURE_NS = 500_000_000L
        /** How long the camera's own focus may run to let it switch lenses at a stop (the focus set by hand then comes back). */
        const val LENS_NUDGE_MS = 1_500L
        /** How long the zoom rests in another lens' range before the DNG's raw stream follows it (a new session). */
        const val RAW_LENS_SETTLE_MS = 400L
        /** After the pick, how long until the lens the camera streams from is looked at once more. */
        const val RAW_LENS_RECHECK_MS = 1_000L
        /** Focus peaking stays this long after the focus changed or its ruler closed (SPEC §11). */
        const val PEAKING_AFTER_MS = 2_000L
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
