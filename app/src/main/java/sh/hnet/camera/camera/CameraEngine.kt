package sh.hnet.camera.camera

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraExtensionSession
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.ExtensionSessionConfiguration
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.location.Location
import android.media.AudioManager
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import sh.hnet.camera.capabilities.CameraCatalog
import sh.hnet.camera.capabilities.CameraSpec
import sh.hnet.camera.capabilities.fiftyMp
import sh.hnet.camera.capabilities.model.Aspect
import sh.hnet.camera.capabilities.model.Exposure
import sh.hnet.camera.capabilities.model.ManualWhiteBalance
import sh.hnet.camera.capabilities.model.Orientation
import sh.hnet.camera.capabilities.model.PictureRange
import sh.hnet.camera.capabilities.model.PixelRect
import sh.hnet.camera.capabilities.model.PixelSize
import sh.hnet.camera.capabilities.model.Point
import sh.hnet.camera.capabilities.model.ProExposure
import sh.hnet.camera.capabilities.model.SessionLadder
import sh.hnet.camera.capabilities.model.Sizes
import sh.hnet.camera.capabilities.model.StillFormat
import sh.hnet.camera.capabilities.model.StillPlan
import sh.hnet.camera.capabilities.model.VideoChoice
import sh.hnet.camera.capabilities.model.WhiteBalance
import sh.hnet.camera.processing.FrameMeta
import sh.hnet.camera.processing.from

/** The engine's modes; [PRO] is Photo's session with the values set by hand (no motion photo). */
enum class Mode { PHOTO, VIDEO, NIGHT, PRO }

/** What the viewfinder wants: which camera, in which mode and aspect ratio. */
data class CameraRequest(
    val camera: CameraSpec,
    val mode: Mode,
    val aspect: Aspect,
    val video: VideoChoice = VideoChoice(),
    val ultraHdr: Boolean = true,
    val motion: Boolean = false,
    val motionFirst: Boolean = false,
    /** The raw sensor image (DNG) next to each photo (Photo and Pro). */
    val raw: Boolean = false,
    /** The physical lens whose raw image it is (the zoom's stop's; a session holds one lens' raw stream), null: the logical camera's. */
    val rawLens: String? = null,
    /** Pro's 50 MP: the main lens' maximum-resolution raw image alone, as a DNG (D4, D18). */
    val raw50: Boolean = false,
    /** Photo's own processing (LOGIKA §38): the raw stream of the zoom's lens ([rawLens]) for a series developed by the app; without it the camera's photo. */
    val processing: Boolean = false,
) {
    /** The aspect the preview and the session use: video is always 16:9. */
    val pictureAspect: Aspect get() = if (mode == Mode.VIDEO) Aspect.SIXTEEN_NINE else aspect
}

sealed interface CameraStatus {
    data object Idle : CameraStatus
    data object Opening : CameraStatus
    data object Ready : CameraStatus
    /** Another app or a call has the camera; it comes back by itself when the camera is free. */
    data object Busy : CameraStatus
    /** Blocked by the phone's administrator (device policy). */
    data object Blocked : CameraStatus
    /** Restarting after an error ([attempt] 1 or 2), or [failed] after both attempts; [code] is the camera's error, with no user data. */
    data class Error(val code: String, val attempt: Int, val failed: Boolean) : CameraStatus
}

/** The focused face's corners as shares of the preview. */
data class FaceMark(val topLeft: Point, val bottomRight: Point)

/** A recording in progress, timed by the elapsed-time clock (ms): its length is (paused at, or now) − started − paused before. */
data class Recording(val startedAt: Long, val pausedAt: Long? = null, val pausedMs: Long = 0, val silenced: Boolean = false)

data class EngineState(
    val status: CameraStatus = CameraStatus.Idle,
    val request: CameraRequest? = null,
    /** The range of the session in use (which rung of the ladder the camera confirmed). */
    val range: PictureRange? = null,
    /** Video mode: what the session records. */
    val video: VideoFormat? = null,
    val recording: Recording? = null,
    /** The camera switched to the ultrawide by itself at 1× or more, for a close subject (SPEC §3.3: shown, it does it on its own). */
    val macro: Boolean = false,
    /** The session has the analysis stream: QR codes come only then. */
    val analysis: Boolean = false,
    /** The session keeps the motion photo's last seconds. */
    val motion: Boolean = false,
    /** The session has the raw stream: a photo gets its DNG. */
    val raw: Boolean = false,
    /** The session has the raw stream for Photo's own processing: a photo may be a series developed by the app. */
    val processing: Boolean = false,
    /** The session has the 50 MP raw stream: a shot is that DNG alone. */
    val raw50: Boolean = false,
    /** A recording is being finished and saved: the shutter waits for it. */
    val finishing: Boolean = false,
    /** A frame of the current session has been shown (the viewfinder's crossfade ends then). */
    val frameShown: Boolean = false,
    val focusing: Boolean = false,
    /** The scene is dark (about 14 lux or less): the front camera's automatic screen flash fires, Night is suggested. */
    val dark: Boolean = false,
    /** The flash is needed: the exposure says so, or the scene is dark (automatic flash: it will fire with the next photo). */
    val flashNeeded: Boolean = false,
    val face: FaceMark? = null,
)

enum class Flash { OFF, AUTO, ON }

/** What the repeating request carries besides the template: zoom, exposure, the tapped point, the AE/AF lock, the flash, the torch (flashlight, timer's LED). */
data class Controls(
    val zoom: Double = 1.0,
    val exposure: Int = 0,
    val focus: Point? = null,
    val locked: Boolean = false,
    val torch: Boolean = false,
    val flash: Flash = Flash.OFF,
    /** The flash's strength level, null for the camera's default. */
    val flashLevel: Int? = null,
    /** Pro's exposure set by hand (both values), null for the camera's own. */
    val manual: Exposure? = null,
    /** Pro's focus set by hand, in diopters (0: infinity); null for the camera's own. */
    val focusDiopters: Double? = null,
    /** Pro's white balance set by hand; null for the camera's own. On a camera without its color calibration, one of the camera's own presets instead. */
    val whiteBalance: ManualWhiteBalance? = null,
    val whiteBalancePreset: Int? = null,
    /** The optical stabilization off: a still phone's long exposure (it drifts by itself over seconds), where the camera lets it be switched. */
    val stabilizationOff: Boolean = false,
)

/** A Night photo on its way: its frames gathered (true) or not, the extension's progress (0–100) while it puts the photo together, and the photo. */
class NightShot(val gathered: Deferred<Boolean>, val photo: Deferred<CapturedPhoto?>, val progress: StateFlow<Int>)

/** A burst's frames: at most this many (LOGIKA §18), about 5 to 10 s. */
const val MAX_BURST = 150

/** A burst's frame as the camera gave it: the JPEG (Ultra HDR or plain, turned by its EXIF) and its sensor time. */
class BurstFrame(val bytes: ByteArray, val sensorTimeNs: Long)

/** A burst under way (LOGIKA §18): its frames as they come (the channel ends with the burst) and the crop each needs for the aspect. */
class BurstShot(val frames: ReceiveChannel<BurstFrame>, val crop: PixelSize?)

/** A still as it came from the camera, with the crop the app still has to make for its aspect ratio. */
class CapturedPhoto(
    val bytes: ByteArray,
    val format: StillFormat,
    val crop: PixelSize?,
    val takenAt: Long,
    val sensorTimeNs: Long,
    /** The motion photo's stream of the session the photo was taken in, and the photo's orientation: its clip belongs to it, whatever happens after. */
    internal val motionSource: MotionBuffer?,
    val orientation: Int,
    /** A time set by hand longer than the camera's EXIF holds (the Pixel writes 4.29 s for 16 s): the one taken, for the file. */
    val longExposureNs: Long? = null,
    /** The photo's raw image for its DNG (Photo and Pro with RAW on); null without it. */
    val raw: RawShot? = null,
    /** The DNG was asked for, but the camera took the photo with another lens than the raw stream's: none. */
    val rawOtherLens: Boolean = false,
    /** Pro's 50 MP shot: this DNG is the shot ([bytes] empty, no photo beside it). */
    val raw50: Raw50Shot? = null,
    /** Photo's own processing: the series went to the [SeriesSink] ([bytes] empty: the app develops the photo). */
    val series: SeriesTaken? = null,
    /** A series was asked for and this is the camera's own photo: why (for the tests' EXIF note, D35). */
    val notSeries: String? = null,
)

/**
 * Where a series' raw frames go as they come (Photo's own processing, LOGIKA §38): the camera hands each frame over and takes its buffer back as soon as
 * [add] returns.
 */
interface SeriesSink {
    /** A series of [frames] raw frames of [width] × [height] starts; false when it can't be taken now (the camera's own photo is taken instead). */
    suspend fun begin(frames: Int, width: Int, height: Int): Boolean

    /** One frame: its 16-bit samples (valid only until this returns), [rowStride] bytes from one row to the next, its metadata; false when it didn't get it. */
    suspend fun add(samples: ByteBuffer, rowStride: Int, meta: FrameMeta): Boolean
}

/**
 * A series taken for the app's development: how many frames went to the sink, and what the photo needs besides them – its framing within the lens' raw
 * image (the lens' crop [cropShare] where its result gives one, else [zoom] over the lens' field of view; then [aspect], the long side over the short one),
 * and the EXIF's numbers (exposure, ISO, aperture and focal length, the 35 mm equivalent; null where the camera didn't say).
 */
class SeriesTaken(
    val frames: Int,
    /** Saved as Ultra HDR (the setting), else the SDR picture alone. */
    val ultraHdr: Boolean,
    val zoom: Double,
    val aspect: Double,
    val cropShare: DoubleArray?,
    val exposureNs: Long?,
    val iso: Int?,
    val aperture: Float?,
    val focalLength: Float?,
    val focalLength35: Int?,
)

/**
 * A 50 MP raw image (RAW10 as the camera packs it, quad Bayer on the Pixel) with the lens' characteristics and the shot's result: the DNG is made from it when
 * saved ([sh.hnet.camera.media.DngWriter]: rearranged to Bayer, the lens shading as gain maps, orientation, place, the photo's aspect as its crop).
 */
class Raw50Shot(
    val packed: ByteBuffer,
    val width: Int,
    val height: Int,
    val characteristics: CameraCharacteristics,
    val result: CaptureResult,
    val exifOrientation: Int,
    val location: Location?,
    val aspect: Double,
)

/**
 * A photo's raw image with the camera's metadata, written as a DNG when the photo is saved ([DngCreator]: orientation, place, the lens shading map); [close]
 * lets the writer go, once written or when the photo isn't kept.
 */
class RawShot internal constructor(
    private val creator: DngCreator,
    private val size: PixelSize,
    pixels: ByteArray,
    /**
     * The photo's framing in the raw image, written as the DNG's default crop: the lens' own crop as shares of its active array where its result gives one,
     * else the zoom over the lens' field of view; then the aspect's long side over its short one.
     */
    val zoom: Double,
    val aspect: Double,
    val cropShare: DoubleArray? = null,
) : AutoCloseable {
    /** The 25 MB or so of pixels, let go with the writer (the photo itself may be held a while longer, for its motion video or a late place). */
    private var pixels: ByteArray? = pixels

    fun write(out: OutputStream) {
        val data = pixels ?: error("DNG already written or let go")
        creator.writeByteBuffer(out, Size(size.width, size.height), ByteBuffer.wrap(data), 0)
    }

    override fun close() {
        pixels = null
        creator.close()
    }
}

/**
 * The app's camera (LOGIKA §1, §2): one device and one session at a time on a thread of its own. The viewfinder says what it wants (camera, mode, aspect, preview
 * surface, started or not) and the engine gets there step by step, trying the session ladder from the best rung down.
 */
class CameraEngine(context: Context) {
    private val appContext = context.applicationContext
    private val manager = context.getSystemService(CameraManager::class.java)
    private val policy = context.getSystemService(DevicePolicyManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val dispatcher = handler.asCoroutineDispatcher("camera")
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @Volatile
    private var catalog: List<CameraSpec>? = null

    /** The back and front cameras. Some characteristics (the closest focus distance) need the camera permission, so a list read without it isn't kept. */
    val cameras: List<CameraSpec>
        get() {
            catalog?.let { return it }
            val read = runCatching { CameraCatalog.read(manager) }.getOrDefault(emptyList())
            if (appContext.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) catalog = read
            return read
        }

    /** [surfaceSize]: the buffer size the viewfinder gave [surface] (a session is made only on a surface of its request's preview size). */
    private data class Desired(val started: Boolean = false, val request: CameraRequest? = null, val surface: Surface? = null, val surfaceSize: PixelSize? = null, val retry: Int = 0)

    private val desired = MutableStateFlow(Desired())
    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    // Everything below is touched only on the camera thread
    private var controls = Controls()
    private var device: CameraDevice? = null
    private var deviceLost = false
    /** The retry count the device was opened for: a retry opens it again. */
    private var deviceRetry = -1
    private var session: CameraCaptureSession? = null
    private var sessionKey: Desired? = null
    private var still: StillOutput? = null
    /** The session's raw stream (Photo and Pro with the DNG asked for), and Pro's 50 MP one. */
    private var rawOutput: ImageOutput? = null
    private var raw50Output: ImageOutput? = null
    /** The physical lens the camera streams from now (the newest result's; null before this session's first), and whether the session has had a result yet. */
    @Volatile
    var activeLens: String? = null
        private set
    private var resultSeen = false
    /** The preview's newest result: a series takes its exposure and focus. */
    private var lastResult: TotalCaptureResult? = null
    private var recorder: VideoRecorder? = null
    /** Written on the camera thread, read on the analysis thread: frames of a session that is going aren't handed on. */
    @Volatile
    private var analyzing: AnalysisOutput? = null
    private var motion: MotionBuffer? = null
    private var silenceWatch: Job? = null
    /** A recording being finished: a new one, or closing the session, waits for it. */
    private var stopping: Deferred<Boolean>? = null
    private var previewSurface: Surface? = null
    private var attempts = 0
    private var pendingRetry: Job? = null
    private var lockJob: Job? = null
    /** A still being taken: the session isn't closed under it (leaving right after the shutter still saves the photo). */
    private var capture: CompletableDeferred<Unit>? = null
    /** How long the still in flight may take, fixed when it was asked for (Pro's time may change meanwhile). */
    private var captureTimeoutMs = STILL_TIMEOUT_MS
    /** The exposure has settled on the last frame (converged, or decided it needs the flash). */
    private val aeSettled = MutableStateFlow(false)
    /** The exposure's state on the last frame. */
    private val aeState = MutableStateFlow<Int?>(null)
    /** The pre-capture metering began after the last trigger. */
    @Volatile
    private var precaptureSeen = false
    private var shownFace: FaceMark? = null

    /** How the phone is held (0, 90, 180, 270): faces stand that way, which tells the face mark its long side. */
    @Volatile
    var deviceRotation = 0
    private var faceMissing = 0

    private val availability = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            // Back from another app or a call; an error is handled by its own restarts, not here
            val status = _state.value.status
            if (cameraId == desired.value.request?.camera?.id && (status == CameraStatus.Busy || status == CameraStatus.Blocked)) bump()
        }
    }

    /** The viewfinders that want the camera (two activities may hand over in either order): it runs while there is one. */
    private val owners = mutableSetOf<Any>()

    fun start(owner: Any) {
        synchronized(owners) { owners += owner }
        scope.launch {
            // Stopped again meanwhile
            if (synchronized(owners) { owners.isEmpty() }) return@launch
            open()
        }
    }

    private fun open() {
        val status = _state.value.status
        if (status == CameraStatus.Busy || status == CameraStatus.Blocked || (status is CameraStatus.Error && status.failed)) {
            attempts = 0
            bump()
        }
        desired.update { it.copy(started = true) }
    }

    fun stop(owner: Any) {
        val none = synchronized(owners) {
            owners -= owner
            owners.isEmpty()
        }
        if (none) desired.update { it.copy(started = false) }
    }

    fun configure(request: CameraRequest) = desired.update { if (it.request == request) it else it.copy(request = request) }

    /** Whether [a] and [b] are served by the same session: they differ at most in an aspect cut from the same streams (a square cut from 4:3, any aspect in Video). */
    fun sameSession(a: CameraRequest, b: CameraRequest): Boolean = sessionShape(a) == sessionShape(b)

    /** What of a request makes its session: the aspect of its streams, and only what its mode uses (Photo and Night have no video choice, only Photo a motion photo). */
    private fun sessionShape(request: CameraRequest) = request.copy(
        aspect = sessionAspect(request),
        video = if (request.mode == Mode.VIDEO) request.video else VideoChoice(),
        motion = request.mode == Mode.PHOTO && request.motion,
        motionFirst = request.mode == Mode.PHOTO && request.motionFirst,
        raw = (request.mode == Mode.PHOTO || request.mode == Mode.PRO) && request.raw && request.camera.raw != null,
        rawLens = request.rawLens.takeIf { (request.mode == Mode.PHOTO || request.mode == Mode.PRO) && (request.raw || request.processing) && it in request.camera.rawLenses },
        raw50 = request.mode == Mode.PRO && request.raw50 && request.camera.fiftyMp,
        processing = request.mode == Mode.PHOTO && request.processing && request.camera.raw != null,
    )

    /** The raw stream a request's session has: its size, and the physical lens it is asked of (null: the logical camera's). */
    private fun rawStream(request: CameraRequest): Pair<PixelSize, String?>? {
        val lens = request.rawLens?.takeIf { it in request.camera.rawLenses }
        return lens?.let { request.camera.rawLenses.getValue(it) to it } ?: request.camera.raw?.let { it to null }
    }

    /** The session there is the one wanted now (not one about to be replaced). */
    private fun sessionSettled() = (session != null || nightSession != null) && sessionKey?.shape() == desired.value.shape()

    /** The aspect the session's streams have: a square cut from the 4:3 still is 4:3 (unless the camera has a full square size of its own), Video is 16:9. */
    private fun sessionAspect(request: CameraRequest): Aspect = when {
        request.mode == Mode.VIDEO -> Aspect.SIXTEEN_NINE
        request.aspect == Aspect.SQUARE && squareIsCut(request) -> Aspect.FOUR_THREE
        else -> request.aspect
    }

    private fun squareIsCut(request: CameraRequest): Boolean {
        val lists = if (request.mode == Mode.NIGHT) request.camera.night?.let { listOf(it.jpegR, it.jpeg) }.orEmpty() else listOf(request.camera.jpegR, request.camera.jpeg)
        return lists.filter { it.isNotEmpty() }.all { Sizes.still(it, Aspect.SQUARE)?.capture == Sizes.still(it, Aspect.FOUR_THREE)?.capture }
    }

    private fun Desired.shape(): Desired = copy(request = request?.let(::sessionShape))

    /** The crop a still of [capture] needs for the aspect asked at the shot: 4:3 and a square share the session, so it is decided here, not with the session. */
    private fun cropFor(capture: PixelSize, request: CameraRequest): PixelSize? = request.pictureAspect.let { if (Sizes.near(capture, it)) null else Sizes.cropTo(capture, it) }

    fun setPreviewSurface(surface: Surface, size: PixelSize) = desired.update { it.copy(surface = surface, surfaceSize = size) }

    /** The viewfinder's surface went away; a newer one set meanwhile (a new size) stays. */
    fun clearPreviewSurface(surface: Surface) = desired.update { if (it.surface == surface) it.copy(surface = null, surfaceSize = null) else it }

    /** Open again (the problem card's button). */
    fun retry() = scope.launch {
        attempts = 0
        bump()
    }

    private fun bump() {
        pendingRetry?.cancel()
        pendingRetry = null
        desired.update { it.copy(retry = it.retry + 1) }
    }

    /** The preview's buffer size for a request; the viewfinder sizes its surface with it before the session is made. */
    fun previewSize(request: CameraRequest): PixelSize? =
        // Night previews in the extension's own sizes
        if (request.mode == Mode.NIGHT) {
            // An aspect the extension lacks previews in 4:3 rather than not at all
            request.camera.night?.preview.orEmpty().let { Sizes.preview(it, request.pictureAspect) ?: Sizes.preview(it, Aspect.FOUR_THREE) }
        } else {
            Sizes.preview(request.camera.preview, request.pictureAspect)
        }

    /** A new viewfinder starts from the defaults (1×, no compensation, no focus point): the engine outlives the screens. */
    fun resetControls() = scope.launch {
        lockJob?.cancel()
        controls = Controls()
        repeat()
    }

    fun setZoom(ratio: Double) = scope.launch {
        controls = controls.copy(zoom = ratio)
        repeat()
    }

    /** The flashlight in Video mode. */
    /** The optical stabilization off for a long exposure on a still phone, and on again after (where the camera can switch it). */
    fun setStabilizationOff(off: Boolean) = scope.launch {
        if (controls.stabilizationOff == off) return@launch
        controls = controls.copy(stabilizationOff = off)
        repeat()
    }

    fun setTorch(on: Boolean) = scope.launch {
        controls = controls.copy(torch = on)
        repeat()
    }

    /** The photo flash (the front camera's screen flash is the viewfinder's; the camera only meters for it). */
    fun setFlash(flash: Flash, level: Int?) = scope.launch {
        controls = controls.copy(flash = flash, flashLevel = level)
        repeat()
    }

    fun setExposure(steps: Int) = scope.launch {
        if (controls.exposure == steps) return@launch
        controls = controls.copy(exposure = steps)
        repeat()
    }

    /**
     * Pro's exposure set by hand (LOGIKA §26): both given, the camera's own exposure is off and they are used – the photo as they are, the preview with the time
     * held short and the gain it loses added ([ProExposure.preview]); null, the camera's own exposure again. Only in Pro.
     */
    fun setManualExposure(exposure: Exposure?) = scope.launch {
        if (controls.manual == exposure) return@launch
        controls = controls.copy(manual = exposure)
        repeat()
    }

    /** Pro's focus set by hand (diopters), or the camera's own again (null). Only in Pro, on a lens that focuses. */
    fun setManualFocus(diopters: Double?) = scope.launch {
        if (controls.focusDiopters == diopters) return@launch
        controls = controls.copy(focusDiopters = diopters)
        lookChangedAt = SystemClock.uptimeMillis()
        repeat()
        // Back to automatic with a tapped point: AF_MODE_AUTO only moves the lens on a trigger, which the manual focus ignored – it would stay where it was set
        if (diopters == null && controls.focus != null) trigger(CameraMetadata.CONTROL_AF_TRIGGER_START, precapture = false, flashMetering = false)
    }

    /** Pro's white balance set by hand – gains and matrix, or the camera's own [preset] (`CONTROL_AWB_MODE_*`) –, or the camera's own again (both null). Only in Pro. */
    fun setManualWhiteBalance(balance: ManualWhiteBalance?, preset: Int? = null) = scope.launch {
        if (controls.whiteBalance == balance && controls.whiteBalancePreset == preset) return@launch
        controls = controls.copy(whiteBalance = balance, whiteBalancePreset = preset)
        lookChangedAt = SystemClock.uptimeMillis()
        repeat()
    }

    /** When Pro's focus or white balance last changed: the camera's buffer of frames (ZSL) still holds frames from before for a moment. */
    @Volatile
    private var lookChangedAt = 0L

    /**
     * What the camera reports of a frame for Pro's automatic values: the exposure (the gain after RAW counted in), the focus distance, the white balance's
     * gains against green, and the physical lens that took it (null: the camera has one).
     */
    class LiveNow(val exposure: Exposure, val focusDiopters: Double?, val gainRed: Double?, val gainBlue: Double?, val lens: String?, val session: Int)

    /** Counts the sessions closed: a [LiveNow] of an earlier one tells of a lens the new session may not be on. */
    @Volatile
    var sessionNumber = 0
        private set

    private val _liveNow = MutableStateFlow<LiveNow?>(null)

    /** The preview's values as the camera reports them, a few times a second: Pro's automatic values. */
    val liveNow: StateFlow<LiveNow?> = _liveNow.asStateFlow()
    private var liveNowAt = 0L

    /** A frame's exposure as its result said, and whether the camera's own exposure had settled. */
    class SeenExposure(val exposure: Exposure, val settled: Boolean)

    /** The last results' exposures by their sensor time: the metering pairs a frame it measured with the exposure it really had. */
    private val seen = ArrayDeque<Pair<Long, SeenExposure>>()

    /** The exposure of the frame taken at [timestampNs] (the analysis frame's), if its result is still known. */
    fun exposureAt(timestampNs: Long): SeenExposure? = synchronized(seen) { seen.lastOrNull { it.first == timestampNs }?.second }

    /** Focus and meter at a point of the preview. With [lock] the exposure is metered there first and locked once it settles (AE/AF lock), and focus stays. */
    fun focusAt(point: Point, lock: Boolean) = scope.launch {
        lockJob?.cancel()
        controls = controls.copy(focus = point, locked = false)
        aeSettled.value = false
        repeat()
        // The lock meters with plain auto-exposure: no pre-flash for a lock
        trigger(CameraMetadata.CONTROL_AF_TRIGGER_START, precapture = lock, flashMetering = false)
        if (!lock) return@launch
        lockJob = scope.launch {
            // A few frames for the new region to count, then until the exposure settles (at most a moment)
            delay(LOCK_MIN_WAIT_MS)
            withTimeoutOrNull(LOCK_MAX_WAIT_MS) { aeSettled.first { it } }
            if (controls.focus == point) {
                controls = controls.copy(locked = true)
                repeat()
            }
        }
    }

    /** Back to continuous focus over the whole picture (the lock ended, the phone moved, the mode or lens changed); the exposure compensation stays unless [exposure] says otherwise. */
    fun resetFocus(exposure: Int? = null) = scope.launch {
        lockJob?.cancel()
        controls = controls.copy(focus = null, locked = false, exposure = exposure ?: controls.exposure)
        trigger(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL, precapture = false, flashMetering = false)
        repeat()
    }

    /**
     * When a still's exposure started, in [SystemClock.uptimeMillis]'s clock (from the sensor's own timestamp), and its time when set by hand. [measured] false:
     * counted from the request, which a time set by hand gets at once (some cameras say the exposure started only once it has ended); the camera's own word
     * follows.
     */
    class ShotStart(val uptimeMs: Long, val exposureNs: Long?, val measured: Boolean = true)

    /**
     * Takes a still; [onShutter] runs on the camera thread when the camera says the exposure started (the blink, the sound, a long exposure's countdown), and
     * for a time set by hand once before that, as the request goes.
     * Null when there is no session or the camera didn't deliver.
     */
    suspend fun takePhoto(deviceRotation: Int, location: Location?, precapture: Boolean, series: SeriesSink? = null, onShutter: (ShotStart) -> Unit): CapturedPhoto? = withContext(dispatcher) {
        // A session being built (a mode, a setting, the end of a countdown that put it off) is waited for, not taken as none; before the still is counted
        // as in flight, which the session's close would wait for
        awaitSession()
        // Not during a burst (the two would take each other's stills), nor on a session about to go
        if (bursting != null || !sessionSettled()) return@withContext null
        val done = CompletableDeferred<Unit>()
        capture = done
        captureTimeoutMs = stillTimeoutMs()
        try {
            shoot(deviceRotation, location, precapture, series, onShutter)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Still capture failed: ${e.javaClass.simpleName}")
            null
        } finally {
            done.complete(Unit)
            if (capture === done) capture = null
        }
    }

    /**
     * [precapture]: meter first (the flash's pre-flash, or the front camera's screen lit as its flash) and take the photo once the exposure has settled; the
     * camera's own buffer of frames (ZSL) can't be used then.
     */
    private suspend fun shoot(deviceRotation: Int, location: Location?, precapture: Boolean, series: SeriesSink?, onShutter: (ShotStart) -> Unit): CapturedPhoto? {
        val session = session ?: return null
        val output = still ?: return null
        val request = desired.value.request ?: return null
        val device = device ?: return null
        // Pro's 50 MP: the maximum-resolution raw image alone
        raw50Output?.takeIf { request.mode == Mode.PRO && request.raw50 }?.let { return shoot50(it, session, device, request, deviceRotation, location, onShutter) }
        // The flash meters first: always when on, when auto only if the exposure says the flash is needed (daylight shots stay quick, from the buffer)
        // With the exposure set by hand the flash fires as it is, without metering
        val manual = manualExposure(request)
        val flashShot = manual == null && (request.mode == Mode.PHOTO || request.mode == Mode.PRO) && request.camera.flash && !controls.torch && (controls.flash == Flash.ON || (controls.flash == Flash.AUTO && _state.value.flashNeeded))
        val metered = precapture || flashShot
        // A locked exposure stays as it is: metering again would wait in vain
        if (metered && !controls.locked) {
            precaptureSeen = false
            trigger(CameraMetadata.CONTROL_AF_TRIGGER_IDLE, precapture = true, flashMetering = true)
            withTimeoutOrNull(PRECAPTURE_MAX_MS) {
                // Into the pre-capture metering (seen by the results, which may pass it between two looks), then out of it
                while (!(precaptureSeen && aeState.value != CameraMetadata.CONTROL_AE_STATE_PRECAPTURE)) delay(PRECAPTURE_POLL_MS)
            }
        }
        val video = request.mode == Mode.VIDEO
        val builder = device.createCaptureRequest(if (video) CameraDevice.TEMPLATE_VIDEO_SNAPSHOT else CameraDevice.TEMPLATE_STILL_CAPTURE)
        builder.addTarget(output.reader.surface)
        // The motion photo's video keeps its frame at the shot where one request may hold both (SDR; HLG streams can't go with the still's), and not when the
        // still may come from an earlier frame (ZSL), whose time would go back in the video
        // Nor with the exposure set by hand: the buffer's frames have the preview's (shorter) one
        // Nor for a moment after Pro's focus or white balance changed: the buffer's frames are from before
        // Nor with the DNG: the buffer's still and the raw image might be of different frames. A lens' raw stream gives an image only while the camera takes
        // the picture with that lens: when it took another one by itself (macro, a near subject at the tele's stop) the photo goes without its DNG
        // Right after a new session its first result may not be in yet: the lens it streams from is waited for a moment (a camera that doesn't report one,
        // results in and still none, gets the raw target anyway: a lens not streaming only loses that buffer)
        // A series takes this session's exposure from its result: waited for too, whatever the lens
        if (((rawOutput?.lens != null && request.raw) || (series != null && request.processing)) && !video && !resultSeen) withTimeoutOrNull(FIRST_RESULT_MS) { while (!resultSeen) delay(SETTLE_POLL_MS) }
        val lensMatches = rawOutput?.let { it.lens == null || activeLens == null || it.lens == activeLens } == true
        // Photo's own processing: a series of the raw stream's lens, not with a flash (metered for one lit frame) nor when the camera took the picture with
        // another lens by itself (the camera's own photo then)
        var notSeries: String? = null
        if (series != null) {
            notSeries = when {
                !request.processing || rawOutput == null -> "session without raw"
                !request.camera.manualSensor -> "no manual sensor"
                !lensMatches -> "lens ${activeLens} not raw ${rawOutput?.lens}"
                metered -> "flash"
                else -> null
            }
            if (notSeries == null) rawOutput?.let { out -> shootSeries(out, session, device, request, deviceRotation, series, { notSeries = it }, onShutter)?.let { return it } }
        }
        val rawOut = rawOutput?.takeIf { request.raw && !video && lensMatches }
        val rawOtherLens = rawOutput != null && request.raw && !video && rawOut == null
        val zsl = request.camera.zsl && !video && !metered && manual == null && rawOut == null && SystemClock.uptimeMillis() - lookChangedAt > LOOK_SETTLE_MS
        if (_state.value.range == PictureRange.SDR && !zsl) motion?.let { builder.addTarget(it.surface) }
        applyControls(builder, request, still = true)
        // The Pixel makes a JPEG still of the still-capture intent by its own processing, which ignores an exposure set by hand (4 s and 1/4 s alike, the
        // camera's automatic one taken instead); the manual intent gets the time and ISO asked for (LOGIKA §30.4). JPEG_R takes them either way
        if (manual != null && output.format == StillFormat.JPEG) builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_MANUAL)
        // Set either way: the still template may turn the buffer on by itself, and a flash photo must not come from it
        if (request.camera.zsl) builder.set(CaptureRequest.CONTROL_ENABLE_ZSL, zsl)
        // The raw image of the same frame, with the lens shading map where the camera gives it (the DNG's vignetting correction)
        rawOut?.let {
            builder.addTarget(it.reader.surface)
            if (request.camera.rawShading) builder.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
        }
        location?.let { builder.set(CaptureRequest.JPEG_GPS_LOCATION, it) }
        val orientation = Orientation.jpeg(request.camera.sensorOrientation, deviceRotation, request.camera.front)
        builder.set(CaptureRequest.JPEG_ORIENTATION, orientation)
        val motionSource = motion
        builder.set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY)
        // The zoom the photo is taken at (the DNG's framing when its lens' result gives no crop)
        val shotZoom = controls.zoom
        output.drain()
        rawOut?.drain()
        val takenAt = System.currentTimeMillis()
        var startedAt = 0L
        var takenExposureNs: Long? = null
        val started = CompletableDeferred<Unit>()
        val failed = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<TotalCaptureResult?>()
        val rawLost = CompletableDeferred<Unit>()
        val realtime = request.camera.sensorRealtime
        session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                startedAt = timestamp
                started.complete(Unit)
                // The sensor's time of the exposure's start, not this call's (it may come late, some cameras only at the readout)
                val ago = (if (realtime) SystemClock.elapsedRealtimeNanos() else System.nanoTime()) - timestamp
                onShutter(ShotStart(SystemClock.uptimeMillis() - (ago / 1_000_000).coerceAtLeast(0), manual?.timeNs))
            }

            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                takenExposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                completed.complete(result)
            }

            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                // The image may still come (its metadata failed, not the picture); without the metadata no DNG can be written
                if (!failure.wasImageCaptured()) failed.complete(Unit)
                completed.complete(null)
            }

            override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
                // Only the still's own buffer matters; the motion video's encoder may drop one; without its raw image the photo has no DNG
                if (target == output.reader.surface) failed.complete(Unit)
                if (target == rawOut?.reader?.surface) rawLost.complete(Unit)
            }

            // Aborted before it started (the session going): no start, no failure would ever come
            override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                failed.complete(Unit)
            }
        }, handler)
        // A time set by hand is counted from the request at once: the Pixel says an HLG still's exposure started only once it has ended (LOGIKA §30.4)
        manual?.let { onShutter(ShotStart(SystemClock.uptimeMillis(), it.timeNs, measured = false)) }
        val image = withTimeoutOrNull(captureTimeoutMs) {
            var found: StillImage? = null
            while (found == null) {
                val next = select<StillImage?> {
                    output.images.onReceiveCatching { it.getOrNull() }
                    failed.onAwait { null }
                } ?: break
                // The camera may hand the still before it says the capture started: the still waits for that word instead of being taken for an older one
                if (startedAt == 0L) {
                    try {
                        select<Unit> {
                            started.onAwait {}
                            failed.onAwait {}
                        }
                    } catch (e: CancellationException) {
                        next.close()
                        throw e
                    }
                }
                // Not from before this capture started: what comes earlier is an older shot's (a burst's last stills); a late one is older than this exposure
                if (startedAt == 0L || next.timestamp < startedAt) next.close() else found = next
            }
            found
        } ?: return null
        val bytes = image.use { it.compressedBytes() }
        // The camera's EXIF holds the time in 32 bits of nanoseconds; a longer one set by hand is written by the app: the result's when it came before the image
        val longExposureNs = manual?.timeNs?.takeIf { it > EXIF_MAX_EXPOSURE_NS }?.let { takenExposureNs ?: it }
        // The photo's framing within the lens' raw image when the lens' result has no crop: the zoom over the lens' own field of view (its optics, not the
        // stop's label: the Pixel's tele is about 4.5× shown as 5×)
        val rawZoom = shotZoom / (rawOut?.lens?.let { lens -> request.camera.zoom.stops.firstOrNull { it.lens == lens }?.optical } ?: 1.0)
        val raw = rawOut?.let { rawShot(it, completed, rawLost, request, orientation, location, rawZoom) }
        return CapturedPhoto(bytes, output.format, cropFor(output.plan.capture, request), takenAt, if (startedAt != 0L) startedAt else image.timestamp, motionSource, orientation, longExposureNs, raw, rawOtherLens, notSeries = notSeries)
    }

    /**
     * The photo's DNG (LOGIKA §28): this capture's raw image (not one from before it started) and its result, waited for a little after the photo; null when
     * either doesn't come – the photo is kept without it.
     */
    private suspend fun rawShot(out: ImageOutput, completed: Deferred<TotalCaptureResult?>, lost: Deferred<Unit>, request: CameraRequest, orientation: Int, location: Location?, zoom: Double): RawShot? {
        // A lens' raw image is described by that lens: its own characteristics (color filter layout, matrices, size) and its own result
        val lens = out.lens
        val taken = withTimeoutOrNull(RAW_WAIT_MS) {
            val total = completed.await() ?: return@withTimeoutOrNull null
            val result = (if (lens != null) total.physicalCameraTotalResults[lens] else total) ?: return@withTimeoutOrNull null
            // The raw image of that very frame: older ones are an earlier shot's, a newer one means this one's was lost
            val frame = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return@withTimeoutOrNull null
            var found: StillImage? = null
            while (found == null) {
                val next = select<StillImage?> {
                    out.images.onReceiveCatching { it.getOrNull() }
                    lost.onAwait { null }
                } ?: return@withTimeoutOrNull null
                when {
                    next.timestamp < frame -> next.close()
                    next.timestamp > frame -> {
                        next.close()
                        return@withTimeoutOrNull null
                    }
                    else -> found = next
                }
            }
            found to result
        }
        if (taken == null) {
            Log.w(TAG, "No raw image for the photo")
            return null
        }
        val (image, result) = taken
        return image.use {
            runCatching {
                val chars = characteristicsOf(lens ?: request.camera.id) ?: error("no characteristics")
                val (size, pixels) = it.rawPixels()
                val creator = DngCreator(chars, result)
                try {
                    creator.setOrientation(exifOrientation(orientation))
                    location?.let { place -> creator.setLocation(place) }
                } catch (e: Exception) {
                    creator.close()
                    throw e
                }
                RawShot(creator, size, pixels, zoom, request.pictureAspect.ratio.let { r -> maxOf(r, 1 / r) }, cropShare(chars, result))
            }.onFailure { e -> Log.w(TAG, "DNG not prepared: ${e.javaClass.simpleName}") }.getOrNull()
        }
    }

    /**
     * Photo's own processing (LOGIKA §38.1, D29): [SERIES_FRAMES] raw frames of the lens' stream right after the press, at the preview's last exposure and
     * focus, both locked for the whole series, the white balance held, the lens shading map on. With an SDR preview each request feeds the preview too (and
     * the analysis' and the motion photo's streams); with an HLG one (a request can't mix the ranges) preview requests go between them. Each frame goes to
     * [sink] as it comes and its buffer back at once. Null when nothing was taken (the sink refused, no exposure known yet, no frame came): the caller takes
     * the camera's own photo then.
     */
    private suspend fun shootSeries(
        out: ImageOutput, session: CameraCaptureSession, device: CameraDevice, request: CameraRequest, deviceRotation: Int, sink: SeriesSink, miss: (String) -> Unit,
        onShutter: (ShotStart) -> Unit,
    ): CapturedPhoto? {
        fun missed(why: String): CapturedPhoto? {
            miss(why)
            return null
        }
        // This session's newest result (none yet: the camera's own photo)
        val preview = lastResult?.takeIf { resultSeen } ?: return missed("no preview result")
        val surface = previewSurface ?: return missed("no preview")
        val format = still?.format ?: return missed("no still")
        val lens = out.lens
        val chars = characteristicsOf(lens ?: request.camera.id) ?: return missed("no characteristics")
        val time = preview.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return missed("no exposure")
        val iso = preview.get(CaptureResult.SENSOR_SENSITIVITY) ?: return missed("no iso")
        val focus = preview.get(CaptureResult.LENS_FOCUS_DISTANCE)
        if (!sink.begin(SERIES_FRAMES, out.reader.width, out.reader.height)) return missed("processing refused")
        val hlg = _state.value.range == PictureRange.HLG
        val shotZoom = controls.zoom
        val frame = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(out.reader.surface)
            if (!hlg) {
                addTarget(surface)
                analyzing?.let { addTarget(it.reader.surface) }
                motion?.let { addTarget(it.surface) }
            }
            applyControls(this, request, still = true)
            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, time)
            set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(time, PREVIEW_FRAME_NS))
            // The gain after the raw image the camera chose (its own exposure would have kept it): the preview and the motion video don't dim meanwhile
            preview.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST)?.let { set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, it) }
            if (request.camera.flash) set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            if (focus != null && request.camera.autofocus) {
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, focus)
            }
            set(CaptureRequest.CONTROL_AWB_LOCK, true)
            if (request.camera.zsl) set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
            if (request.camera.rawShading) set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
            setTag(SERIES_TAG)
        }.build()
        val requests = if (hlg) previewRequest(device, surface, request).let { between -> List(SERIES_FRAMES * 2) { if (it % 2 == 0) frame else between } } else List(SERIES_FRAMES) { frame }
        val results = Channel<CaptureResult>(Channel.UNLIMITED)
        // The frames the camera said it won't deliver (by frame number: a failure and a lost buffer of one frame count once)
        val lost = java.util.Collections.synchronizedSet(HashSet<Long>())
        var startedAt = 0L
        val started = CompletableDeferred<Unit>()
        val realtime = request.camera.sensorRealtime
        out.drain()
        val takenAt = System.currentTimeMillis()
        session.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                if (request.tag != SERIES_TAG || startedAt != 0L) return
                startedAt = timestamp
                started.complete(Unit)
                val ago = (if (realtime) SystemClock.elapsedRealtimeNanos() else System.nanoTime()) - timestamp
                onShutter(ShotStart(SystemClock.uptimeMillis() - (ago / 1_000_000).coerceAtLeast(0), null))
            }

            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                if (request.tag != SERIES_TAG) return
                // A lens' raw image is described by that lens' own result
                val own = if (lens != null) result.physicalCameraTotalResults[lens] else result
                if (own == null) lost += result.frameNumber else results.trySend(own)
            }

            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                if (request.tag == SERIES_TAG) lost += failure.frameNumber
            }

            override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
                if (target == out.reader.surface) lost += frameNumber
            }
        }, handler)
        val orientation = Orientation.jpeg(request.camera.sensorOrientation, deviceRotation, request.camera.front)
        // Results by the frame's sensor time: an image may come before or after its result
        val known = HashMap<Long, CaptureResult>()
        suspend fun resultOf(timestamp: Long): CaptureResult? {
            known.remove(timestamp)?.let { return it }
            return withTimeoutOrNull(RESULT_WAIT_MS) {
                var found: CaptureResult? = null
                while (found == null) {
                    val next = results.receive()
                    val at = next.get(CaptureResult.SENSOR_TIMESTAMP)
                    if (at == timestamp) found = next else if (at != null) known[at] = next
                }
                found
            }
        }
        var delivered = 0
        var skipped = 0
        var reference: CaptureResult? = null
        val frameWaitMs = FRAME_WAIT_MS + time / 1_000_000 * (if (hlg) 2 else 1)
        withTimeoutOrNull(SERIES_SLACK_MS + time / 1_000_000 * SERIES_FRAMES * (if (hlg) 2 else 1)) {
            while (delivered + skipped + lost.size < SERIES_FRAMES) {
                val image = withTimeoutOrNull(frameWaitMs) { out.images.receiveCatching().getOrNull() } ?: break
                image.use {
                    // An image may come before the word that the series started: judged once that word is there
                    if (startedAt == 0L) withTimeoutOrNull(frameWaitMs) { started.await() }
                    // An earlier shot's, left in the reader
                    if (startedAt == 0L || it.timestamp < startedAt) return@use
                    val result = resultOf(it.timestamp)
                    val meta = result?.let { r -> FrameMeta.from(chars, r, it.width, it.height, exifOrientation(orientation)) }
                    // Without its result (or one it can't be described by) the frame is left out
                    if (meta == null) {
                        skipped++
                        return@use
                    }
                    val (samples, rowStride) = it.plane()
                    // Counted only once the GPU has it: a series of failed uploads is the camera's own photo
                    if (sink.add(samples, rowStride, meta)) {
                        delivered++
                        if (reference == null) reference = result
                    } else {
                        skipped++
                    }
                }
            }
        }
        val first = reference
        if (delivered == 0 || first == null) {
            Log.w(TAG, "No frame of the series came")
            return missed("no frames (skipped $skipped, lost ${lost.size})")
        }
        val rawZoom = shotZoom / (lens?.let { l -> request.camera.zoom.stops.firstOrNull { it.lens == l }?.optical } ?: 1.0)
        val focal = first.get(CaptureResult.LENS_FOCAL_LENGTH)
        val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focal35 = if (focal != null && sensor != null && sensor.width > 0f) (focal * FULL_FRAME_DIAGONAL_MM / hypot(sensor.width.toDouble(), sensor.height.toDouble()) * rawZoom.coerceAtLeast(1.0)).roundToInt() else null
        val taken = SeriesTaken(
            delivered, request.ultraHdr, rawZoom, request.pictureAspect.ratio.let { maxOf(it, 1 / it) }, cropShare(chars, first),
            first.get(CaptureResult.SENSOR_EXPOSURE_TIME), first.get(CaptureResult.SENSOR_SENSITIVITY), first.get(CaptureResult.LENS_APERTURE), focal, focal35,
        )
        return CapturedPhoto(ByteArray(0), format, null, takenAt, if (startedAt != 0L) startedAt else first.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L, motion, orientation, series = taken)
    }

    /**
     * The crop the lens' result says it took for the photo, as shares of its active array (left, top, right, bottom): the zoom on that lens and where the
     * camera aligned it with the others; null when it gives none, or the whole array.
     */
    private fun cropShare(chars: CameraCharacteristics, result: CaptureResult): DoubleArray? {
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val crop = result.get(CaptureResult.SCALER_CROP_REGION) ?: return null
        if (crop.width() <= 0 || crop.height() <= 0 || active.width() <= 0 || active.height() <= 0) return null
        val share = doubleArrayOf(
            (crop.left - active.left).toDouble() / active.width(),
            (crop.top - active.top).toDouble() / active.height(),
            (crop.right - active.left).toDouble() / active.width(),
            (crop.bottom - active.top).toDouble() / active.height(),
        )
        // Outside the array (another lens' coordinates, a maximum-resolution crop): not to be trusted
        if (share.any { it < -0.01 || it > 1.01 }) return null
        return share.takeIf { it[2] - it[0] < 0.999 || it[3] - it[1] < 0.999 }
    }

    /**
     * A 50 MP shot (LOGIKA §30.6): one request for the maximum-resolution raw image only, at the main lens' whole sensor (zoom 1×), with the lens shading map;
     * its bytes copied out at once (the reader's one buffer back), the DNG made when saved. Its start told as a photo's (the click, a long time's countdown).
     */
    private suspend fun shoot50(out: ImageOutput, session: CameraCaptureSession, device: CameraDevice, request: CameraRequest, deviceRotation: Int, location: Location?, onShutter: (ShotStart) -> Unit): CapturedPhoto? {
        val manual = manualExposure(request)
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        builder.addTarget(out.reader.surface)
        applyControls(builder, request, still = true)
        // No flash (offered nowhere at 50 MP: one maximum-resolution frame isn't metered for it), and no regions: theirs are in the normal array's
        // coordinates, not the maximum-resolution one's
        builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
        if (manual == null) builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AE_REGIONS, null)
        builder.set(CaptureRequest.CONTROL_AF_REGIONS, null)
        builder.set(CaptureRequest.CONTROL_AWB_REGIONS, null)
        builder.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
        builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f)
        if (manual != null) builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_MANUAL)
        if (request.camera.zsl) builder.set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
        if (request.camera.rawShading) builder.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
        val orientation = Orientation.jpeg(request.camera.sensorOrientation, deviceRotation, request.camera.front)
        out.drain()
        val takenAt = System.currentTimeMillis()
        var startedAt = 0L
        val completed = CompletableDeferred<TotalCaptureResult?>()
        val failed = CompletableDeferred<Unit>()
        val realtime = request.camera.sensorRealtime
        session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                startedAt = timestamp
                val ago = (if (realtime) SystemClock.elapsedRealtimeNanos() else System.nanoTime()) - timestamp
                onShutter(ShotStart(SystemClock.uptimeMillis() - (ago / 1_000_000).coerceAtLeast(0), manual?.timeNs))
            }

            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                completed.complete(result)
            }

            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                completed.complete(null)
                if (!failure.wasImageCaptured()) failed.complete(Unit)
            }

            override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
                failed.complete(Unit)
            }

            override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                completed.complete(null)
                failed.complete(Unit)
            }
        }, handler)
        manual?.let { onShutter(ShotStart(SystemClock.uptimeMillis(), it.timeNs, measured = false)) }
        val taken = withTimeoutOrNull(captureTimeoutMs) {
            // Its result first (the frame's time and the metadata the DNG needs), then the image of that very frame
            val result = select<TotalCaptureResult?> {
                completed.onAwait { it }
                failed.onAwait { null }
            } ?: return@withTimeoutOrNull null
            val frame = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return@withTimeoutOrNull null
            var found: StillImage? = null
            while (found == null) {
                val next = select<StillImage?> {
                    out.images.onReceiveCatching { it.getOrNull() }
                    failed.onAwait { null }
                } ?: return@withTimeoutOrNull null
                when {
                    next.timestamp < frame -> next.close()
                    next.timestamp > frame -> {
                        next.close()
                        return@withTimeoutOrNull null
                    }
                    else -> found = next
                }
            }
            found to result
        } ?: return null
        val (image, total) = taken
        val shot = image.use {
            runCatching {
                // The lens that took it describes it, where its maximum-resolution array is the image's size (else the logical camera's, if that one is)
                fun fits(chars: CameraCharacteristics) = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)?.let { a -> a.width == it.width && a.height == it.height } == true
                val lens = total.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)?.takeIf { id -> characteristicsOf(id)?.let(::fits) == true }
                val chars = lens?.let { id -> characteristicsOf(id) } ?: characteristicsOf(request.camera.id)?.takeIf(::fits) ?: error("no characteristics of the image's size")
                val result = lens?.let { id -> total.physicalCameraTotalResults[id] } ?: total
                Raw50Shot(it.packedPlane(), it.width, it.height, chars, result, exifOrientation(orientation), location, request.pictureAspect.ratio.let { r -> maxOf(r, 1 / r) })
            }.onFailure { e -> Log.w(TAG, "50 MP image not kept: ${e.javaClass.simpleName}") }.getOrNull()
        } ?: return null
        return CapturedPhoto(ByteArray(0), still?.format ?: StillFormat.JPEG, null, takenAt, total.get(CaptureResult.SENSOR_TIMESTAMP) ?: startedAt, null, orientation, raw50 = shot)
    }

    /** Characteristics read once per camera id (a binder call each). */
    private val characteristics = mutableMapOf<String, CameraCharacteristics>()

    private fun characteristicsOf(id: String): CameraCharacteristics? = characteristics[id] ?: runCatching { manager.getCameraCharacteristics(id) }.getOrNull()?.also { characteristics[id] = it }

    /** The burst under way: its stills' orientation and place, and the stop (touched on the camera thread only). */
    private class Bursting(val orientation: Int, val location: Location?, val done: CompletableDeferred<Unit>) {
        var stopping = false
        /** Wakes the frames' collector, which may be waiting for a still that won't come any more. */
        val stopped = CompletableDeferred<Unit>()

        fun stop() {
            stopping = true
            stopped.complete(Unit)
        }
    }

    private var bursting: Bursting? = null

    /**
     * Starts a burst in Photo (LOGIKA §18, D14, D15): stills at the session's format as fast as the camera gives them, the live picture going on – next to an
     * HLG preview their requests alternate with the preview's (one request may not hold both: about 15 + 15 fps on the Pixel), next to an SDR one each preview
     * request takes a still too (up to 30 fps for JPEG). No flash, no buffer (ZSL), no motion photo. The frames come until [stopBurst] or [MAX_BURST]; the
     * consumer's pace holds the camera back rather than memory growing. Null when there is no Photo session.
     */
    suspend fun startBurst(deviceRotation: Int, location: Location?): BurstShot? = withContext(dispatcher) {
        awaitSession()
        if (session == null || bursting != null || capture != null || !sessionSettled()) return@withContext null
        // Stills from before the burst (a single photo's late one) are older than the newest frame seen
        val after = latestFrameNs
        val output = still ?: return@withContext null
        val request = desired.value.request?.takeIf { it.mode == Mode.PHOTO } ?: return@withContext null
        val done = CompletableDeferred<Unit>()
        capture = done
        captureTimeoutMs = stillTimeoutMs()
        val state = Bursting(Orientation.jpeg(request.camera.sensorOrientation, deviceRotation, request.camera.front), location, done)
        bursting = state
        output.drain()
        val frames = Channel<BurstFrame>(BURST_HANDOFF)
        if (!repeatBurst()) {
            endBurst(state, frames)
            return@withContext null
        }
        scope.launch {
            var count = 0
            try {
                while (true) {
                    // After the stop only the stills already in flight still come; the stop wakes a wait for one that won't
                    val image = if (state.stopping) {
                        withTimeoutOrNull(BURST_TAIL_MS) { output.images.receiveCatching().getOrNull() } ?: break
                    } else {
                        val (stopped, next) = select<Pair<Boolean, StillImage?>> {
                            output.images.onReceiveCatching { false to it.getOrNull() }
                            state.stopped.onAwait { true to null }
                        }
                        if (stopped) continue
                        next ?: break
                    }
                    // Older than the burst, or past its last frame (the stills in flight at the end): given back, not handed on
                    if (image.timestamp <= after || count >= MAX_BURST) {
                        image.close()
                        continue
                    }
                    val frame = image.use { BurstFrame(it.compressedBytes(), it.timestamp) }
                    // A consumer gone (its result lost) doesn't hold the burst for good
                    if (withTimeoutOrNull(BURST_HANDOFF_WAIT_MS) { frames.send(frame) } == null) break
                    count++
                    // The last frame: no more asked for; those in flight still come and are given back above
                    if (count >= MAX_BURST) stopAsking(state)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Burst ended: ${e.javaClass.simpleName}")
            } finally {
                endBurst(state, frames)
            }
        }
        BurstShot(frames, cropFor(output.plan.capture, request))
    }

    /** Ends the burst: no more stills asked for; those already in flight still come. */
    fun stopBurst() = scope.launch { bursting?.let(::stopAsking) }

    /** No more stills asked for: the plain preview again. */
    private fun stopAsking(state: Bursting) {
        if (state.stopping) return
        state.stop()
        repeat()
    }

    private fun endBurst(state: Bursting, frames: Channel<BurstFrame>) {
        if (bursting === state) {
            // Ended by itself (the last frame): the plain preview again
            stopAsking(state)
            bursting = null
        }
        frames.close()
        state.done.complete(Unit)
        if (capture === state.done) capture = null
    }

    /** The burst's repeating requests with the current controls (a zoom or a tap during the burst asks again); false when the session refused them. */
    private fun repeatBurst(): Boolean {
        val state = bursting ?: return false
        val session = session ?: return false
        val device = device ?: return false
        val surface = previewSurface ?: return false
        val output = still ?: return false
        val request = desired.value.request ?: return false
        return try {
            fun stillControls(builder: CaptureRequest.Builder) {
                applyControls(builder, request)
                // No flash in a burst; no frame from the buffer either
                builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                if (request.camera.flash) builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                if (request.camera.zsl) builder.set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
                builder.set(CaptureRequest.JPEG_ORIENTATION, state.orientation)
                builder.set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY)
                state.location?.let { builder.set(CaptureRequest.JPEG_GPS_LOCATION, it) }
            }
            if (_state.value.range == PictureRange.HLG || request.camera.stillStalls) {
                // The HLG preview and a still of the standard profile can't share a request, nor can a still that stalls the stream: they alternate
                val preview = device.createCaptureRequest(template(request))
                preview.addTarget(surface)
                addRecordingTarget(preview)
                applyControls(preview, request)
                // The same exposure mode as the stills between them: no flash metering in a burst
                preview.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                if (request.camera.flash) preview.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                val stillRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                stillRequest.addTarget(output.reader.surface)
                stillControls(stillRequest)
                // The session's own keys the same in both (some cameras reconfigure when they change between requests)
                runCatching { manager.getCameraCharacteristics(request.camera.id).availableSessionKeys }.getOrNull()?.forEach { key ->
                    @Suppress("UNCHECKED_CAST")
                    (key as CaptureRequest.Key<Any?>).let { stillRequest.set(it, preview.get(it)) }
                }
                session.setRepeatingBurst(listOf(preview.build(), stillRequest.build()), results, handler)
            } else {
                // One profile: every preview frame is a still too (the still template, as measured: 30 fps for JPEG)
                val both = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                both.addTarget(surface)
                addRecordingTarget(both)
                both.addTarget(output.reader.surface)
                stillControls(both)
                session.setRepeatingRequest(both.build(), results, handler)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Burst refused: ${e.javaClass.simpleName}")
            false
        }
    }

    private suspend fun reconcile(want: Desired) {
        val request = want.request
        if (!want.started || request == null) {
            awaitCapture()
            closeDevice()
            _state.update { EngineState(request = request) }
            return
        }
        if (policy.getCameraDisabled(null)) {
            awaitCapture()
            closeDevice()
            _state.update { EngineState(CameraStatus.Blocked, request) }
            return
        }
        if (device?.id != request.camera.id || deviceLost || deviceRetry != want.retry) {
            awaitCapture()
            closeDevice()
            if (!openDevice(request)) return
            deviceRetry = want.retry
        }
        val size = previewSize(request)
        if (size == null) {
            // No preview size for this request: an error the viewfinder shows, not a wait for a surface that never comes
            awaitCapture()
            closeSession()
            failed(CameraDevice.StateCallback.ERROR_CAMERA_DEVICE)
            return
        }
        // A request of another preview size waits for the viewfinder's surface of that size: a session on the old one would show for a moment and be made
        // again when the new surface comes (two blinks per change)
        val surface = want.surface?.takeIf { want.surfaceSize == size }
        if (surface == null) {
            // No more preview into a surface that went away while Night photos are finished (the extension could fail them over it)
            nightSession?.let { runCatching { it.stopRepeating() } }
            awaitCapture()
            closeSession()
            return
        }
        if ((session != null || nightSession != null) && sessionKey?.shape() == want.shape()) {
            // 4:3 and a square cut from it share the session: only the aspect told (the screen and the next shot's crop follow it)
            sessionKey = want
            if (_state.value.request != request) _state.update { it.copy(request = request) }
            return
        }
        awaitCapture()
        closeSession()
        if (makeSession(want, request, surface)) {
            sessionKey = want
            attempts = 0
        }
    }

    /** A still in flight finishes before its session goes (at most the still's timeout), a motion photo's video gets its 1.5 s after the shot, and a recording is ended and saved. */
    private suspend fun awaitCapture() {
        // A burst ends at once when its session must go; its frames in flight still come (the wait for `capture` below)
        bursting?.let(::stopAsking)
        // Until nothing is in flight (a still, a motion photo's video, Night photos the extension is still putting together), as long as the waits make progress
        while (true) {
            val before = Triple(capture, clipping.get(), nightInFlight.get())
            if (before.first == null && before.second == 0 && before.third == 0) break
            // The photo's DNG may come a little after it
            capture?.let { inFlight -> withTimeoutOrNull(captureTimeoutMs + RAW_WAIT_MS) { inFlight.await() } }
            if (clipping.get() > 0) withTimeoutOrNull(CLIP_WAIT_MS) { while (clipping.get() > 0) delay(SETTLE_POLL_MS) }
            val night = nightInFlight.get()
            if (night > 0) withTimeoutOrNull(NIGHT_TIMEOUT_MS) { while (nightInFlight.get() >= night) delay(SETTLE_POLL_MS) }
            if (Triple(capture, clipping.get(), nightInFlight.get()) == before) break
        }
        finishRecording()
    }

    /** Waits (on the camera's thread, letting the reconcile run) until the session is the one wanted now; gives up after a few seconds. */
    private suspend fun awaitSession() {
        withTimeoutOrNull(SETTLE_MS) {
            while (!((session != null || nightSession != null) && sessionKey == desired.value && _state.value.status == CameraStatus.Ready)) delay(SETTLE_POLL_MS)
        }
    }

    /** Motion photos still gathering their video: the session waits for them before it closes. */
    private val clipping = AtomicInteger(0)

    private suspend fun openDevice(request: CameraRequest): Boolean {
        _state.update { it.copy(status = CameraStatus.Opening, request = request, frameShown = false) }
        deviceLost = false
        return try {
            device = suspendCancellableCoroutine { cont ->
                manager.openCamera(request.camera.id, executor, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (cont.isActive) cont.resumeWith(Result.success(camera)) else camera.close()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        if (cont.isActive) {
                            camera.close()
                            cont.resumeWith(Result.failure(DeviceError(CameraDevice.StateCallback.ERROR_CAMERA_IN_USE)))
                        } else {
                            lost(camera, CameraDevice.StateCallback.ERROR_CAMERA_IN_USE)
                        }
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        if (cont.isActive) {
                            camera.close()
                            cont.resumeWith(Result.failure(DeviceError(error)))
                        } else {
                            lost(camera, error)
                        }
                    }
                })
            }
            true
        } catch (e: DeviceError) {
            failed(e.error)
            false
        } catch (e: CameraAccessException) {
            failed(
                when (e.reason) {
                    CameraAccessException.CAMERA_IN_USE, CameraAccessException.MAX_CAMERAS_IN_USE -> CameraDevice.StateCallback.ERROR_CAMERA_IN_USE
                    CameraAccessException.CAMERA_DISABLED -> CameraDevice.StateCallback.ERROR_CAMERA_DISABLED
                    else -> CameraDevice.StateCallback.ERROR_CAMERA_DEVICE
                },
            )
            false
        } catch (e: SecurityException) {
            // No permission (yet): the viewfinder shows its own state for that
            _state.update { EngineState(CameraStatus.Idle, request) }
            false
        }
    }

    /** The device went away while open (another app took it, or an error). */
    private fun lost(camera: CameraDevice, error: Int) {
        camera.close()
        if (camera != device) return
        deviceLost = true
        scope.launch {
            // What was recorded up to here is kept
            finishRecording()
            closeDevice()
            failed(error)
        }
    }

    private fun failed(error: Int) {
        val request = desired.value.request
        when (error) {
            CameraDevice.StateCallback.ERROR_CAMERA_IN_USE, CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> _state.update { EngineState(CameraStatus.Busy, request) }
            // Blocked by the privacy switch or a policy: the system shows its own dialog for the privacy switch
            CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> _state.update { EngineState(CameraStatus.Blocked, request) }
            else -> {
                attempts++
                val failedNow = attempts > RESTARTS
                _state.update { EngineState(CameraStatus.Error("camera error $error", attempts.coerceAtMost(RESTARTS), failedNow), request) }
                deviceLost = true
                pendingRetry?.cancel()
                pendingRetry = if (failedNow) null else scope.launch {
                    delay(RESTART_DELAY_MS)
                    pendingRetry = null
                    desired.update { it.copy(retry = it.retry + 1) }
                }
            }
        }
    }

    /** One rung of a ladder: the range, the still stream (none in a video session without photos) and the video stream (Video mode). */
    private data class Rung(val range: PictureRange, val still: StillFormat?, val video: VideoFormat?, val analysis: Boolean = false, val motion: Boolean = false, val raw: Boolean = false, val raw50: Boolean = false)

    private fun rungs(request: CameraRequest): List<Rung> {
        val spec = request.camera
        return when (request.mode) {
            Mode.PHOTO, Mode.PRO -> {
                val photo = SessionLadder.photo(spec.hlg, spec.jpegR.isNotEmpty(), wantMotion = request.motion, wantAnalysis = true, wantUltraHdr = request.ultraHdr, motionFirst = request.motionFirst)
                    .map { Rung(it.range, it.still, null, it.analysis, it.motion) }
                // The DNG asked for: each rung first with the raw stream, then without it (the photo, its range and Pro's analysis stream before the DNG;
                // the viewfinder says when the session came without it)
                when {
                    // 50 MP: every rung with the maximum-resolution raw stream before any without it (the shot is that raw image alone; the rest only
                    // serves the preview)
                    request.mode == Mode.PRO && request.raw50 && spec.fiftyMp -> photo.map { it.copy(raw50 = true) } + photo
                    // Photo's own processing the same way, after the motion photo (D30: a rung with it and without the raw stream comes before one with
                    // the raw stream and without it)
                    (request.raw || request.processing) && spec.raw != null -> photo.flatMap { listOf(it.copy(raw = true), it) }
                    else -> photo
                }
            }
            Mode.VIDEO -> {
                // AVC only where chosen (Settings › Video) or HEVC is missing; HLG is always HEVC
                val hevc = request.video.hevc && Encoders.hevc()
                // Photos while recording follow the Ultra HDR setting as every photo does
                SessionLadder.video(request.video, spec.hlg, spec.jpegR.isNotEmpty() && request.ultraHdr, spec.video, spec.fixedFps, Encoders.limits(hevc))
                    .map { Rung(it.range, it.still, VideoFormat(it.size, it.fps, it.range == PictureRange.HLG, hevc || it.range == PictureRange.HLG)) }
                    // No encoder takes anything the camera offers: the preview alone, nothing to record
                    .ifEmpty { listOf(Rung(PictureRange.SDR, null, null)) }
            }
            // Night's session is the extension's, made apart ([makeNight])
            Mode.NIGHT -> emptyList()
        }
    }

    /**
     * Tries the ladder's rungs from the best. Where the camera can answer without being configured (Android 15+) a rung it refuses is skipped – except the last,
     * the plainest, which is always tried rather than leaving the viewfinder without a picture.
     */
    private suspend fun makeSession(want: Desired, request: CameraRequest, surface: Surface): Boolean {
        if (request.mode == Mode.NIGHT) return makeNight(want, request, surface)
        val device = device ?: return false
        val spec = request.camera
        val previewSize = previewSize(request)
        if (previewSize == null) {
            Log.w(TAG, "No preview size for this camera")
            failed(CameraDevice.StateCallback.ERROR_CAMERA_DEVICE)
            return false
        }
        val rungs = rungs(request)
        for ((index, rung) in rungs.withIndex()) {
            if (!surface.isValid) return false
            // The device went (another app, a call) while a rung was tried: that is already said, not a failure of this session
            if (this.device !== device || deviceLost) return false
            val stillPlan = rung.still?.let { Sizes.still(if (it == StillFormat.JPEG_R) spec.jpegR else spec.jpeg, request.pictureAspect) }
            if (rung.still != null && stillPlan == null) continue
            val format = if (rung.still == StillFormat.JPEG_R) ImageFormat.JPEG_R else ImageFormat.JPEG
            val last = index == rungs.lastIndex
            val confirmed = if (spec.sessionQueries && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) supported(spec.id, request, previewSize, rung, stillPlan, format) else null
            if (!last && confirmed == false) continue
            // A lens' own raw stream and the maximum-resolution mode only as the camera confirms them (CLAUDE.md, session safety: a refused physical or
            // maximum-resolution stream may restart the camera service)
            if (rung.raw && rawStream(request)?.second != null && confirmed != true) continue
            // Photo's own processing, the default mode's: its raw stream only as the camera confirms it, whatever the lens
            if (rung.raw && request.processing && confirmed != true) continue
            if (rung.raw50 && confirmed != true) continue
            // A rung with the analysis stream needs a size for it; without one the next rung (without analysis) is tried
            val analysisSize = if (rung.analysis) analysisSize(spec, rung.range) ?: continue else null
            var output: StillOutput? = null
            var video: VideoRecorder? = null
            var analysis: AnalysisOutput? = null
            var motion: MotionBuffer? = null
            var raw: ImageOutput? = null
            var raw50: ImageOutput? = null
            val made = try {
                output = stillPlan?.let { StillOutput(it, rung.still, format) }
                val rawStream = rawStream(request)?.takeIf { rung.raw }
                raw = rawStream?.let { (size, lens) -> ImageOutput(size.width, size.height, ImageFormat.RAW_SENSOR, if (request.processing) SERIES_BUFFERS else RAW_BUFFERS, lens) }
                // One 50 MP buffer (about 60 MB): its bytes are copied out at once, the buffer back for the next shot
                raw50 = spec.raw50?.takeIf { rung.raw50 }?.let { ImageOutput(it.width, it.height, ImageFormat.RAW10, 1) }
                video = rung.video?.let { VideoRecorder(it, audio, scope) }
                analysis = analysisSize?.let { AnalysisOutput(it, rung.range == PictureRange.HLG, spec) }
                motion = if (rung.motion) MotionBuffer(previewSize, rung.range == PictureRange.HLG, appContext.cacheDir) else null
                val profile = if (rung.range == PictureRange.HLG) DynamicRangeProfiles.HLG10 else DynamicRangeProfiles.STANDARD
                val outputs = listOfNotNull(
                    OutputConfiguration(surface).apply { dynamicRangeProfile = profile },
                    // Frames for the encoder in the monotonic clock, which the sound follows (the default for an encoder's surface, set so nothing hangs on it)
                    video?.let { OutputConfiguration(it.surface).apply { dynamicRangeProfile = profile; timestampBase = OutputConfiguration.TIMESTAMP_BASE_MONOTONIC } },
                    output?.let { OutputConfiguration(it.reader.surface) },
                    // The analysis frames share the preview's range (one request may not mix them): P010 next to HLG, YUV next to SDR
                    analysis?.let { OutputConfiguration(it.reader.surface).apply { dynamicRangeProfile = profile } },
                    // The motion photo's frames in the sensor's clock, as the photo's own timestamp
                    motion?.let { OutputConfiguration(it.surface).apply { dynamicRangeProfile = profile; timestampBase = OutputConfiguration.TIMESTAMP_BASE_SENSOR } },
                    raw?.let { OutputConfiguration(it.reader.surface).apply { it.lens?.let { lens -> setPhysicalCameraId(lens) } } },
                    raw50?.let { OutputConfiguration(it.reader.surface).apply { addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION) } },
                )
                val closeOutputs: () -> Unit = {
                    output?.close()
                    video?.release()
                    analysis?.close()
                    motion?.release()
                    raw?.close()
                    raw50?.close()
                }
                configure(device, sessionParameters(device.createCaptureRequest(template(request)), request, rung.video), outputs, closeOutputs)
            } catch (e: Exception) {
                // An abandoned surface, a size the reader or the encoder refuses: the next rung, or none
                Log.w(TAG, "Session rung failed: ${e.javaClass.simpleName}")
                null
            }
            if (made == null) {
                output?.close()
                video?.release()
                analysis?.close()
                motion?.release()
                raw?.close()
                raw50?.close()
                continue
            }
            // The world may have moved while the session was being made: the device lost, another request or surface wanted (only the aspect of a square cut
            // from 4:3 keeps it: the next reconcile just tells it)
            if (this.device !== device || deviceLost || desired.value.shape() != want.shape()) {
                runCatching { made.close() }
                return false
            }
            session = made
            still = output
            rawOutput = raw
            raw50Output = raw50
            recorder = video
            analyzing = analysis
            this.motion = motion
            previewSurface = surface
            controls = controls.copy(zoom = controls.zoom.coerceIn(spec.zoom.min, spec.zoom.max))
            _state.update {
                EngineState(
                    CameraStatus.Ready, request, rung.range, rung.video, frameShown = false, analysis = analysis != null, motion = motion != null,
                    raw = raw != null && !request.processing, raw50 = raw50 != null, processing = raw != null && request.processing,
                )
            }
            repeat()
            return true
        }
        Log.w(TAG, "No session of the ladder could be configured")
        if (surface.isValid && this.device === device && !deviceLost) failed(CameraDevice.StateCallback.ERROR_CAMERA_DEVICE)
        return false
    }

    private fun template(request: CameraRequest) = if (request.mode == Mode.VIDEO) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW

    /** The session parameters: a video session's frame rate and stabilization (D6: on, where the camera has it). */
    private fun sessionParameters(builder: CaptureRequest.Builder, request: CameraRequest, video: VideoFormat?): CaptureRequest {
        if (video != null) {
            if (video.fps in request.camera.fixedFps) builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(video.fps, video.fps))
            if (request.camera.videoStabilization) builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
        }
        return builder.build()
    }

    /** Asks the camera without configuring it; null when it can't say (then the session is tried). */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun supported(id: String, request: CameraRequest, previewSize: PixelSize, rung: Rung, still: StillPlan?, format: Int): Boolean? = runCatching {
        val setup = manager.getCameraDeviceSetup(id)
        val profile = if (rung.range == PictureRange.HLG) DynamicRangeProfiles.HLG10 else DynamicRangeProfiles.STANDARD
        val preview = OutputConfiguration(ImageFormat.PRIVATE, Size(previewSize.width, previewSize.height), HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_COMPOSER_OVERLAY)
        preview.dynamicRangeProfile = profile
        val video = rung.video?.let { OutputConfiguration(ImageFormat.PRIVATE, Size(it.size.width, it.size.height), HardwareBuffer.USAGE_VIDEO_ENCODE).apply { dynamicRangeProfile = profile; timestampBase = OutputConfiguration.TIMESTAMP_BASE_MONOTONIC } }
        val analysis = if (rung.analysis) analysisSize(request.camera, rung.range)?.let { size ->
            val tenBit = rung.range == PictureRange.HLG
            OutputConfiguration(if (tenBit) ImageFormat.YCBCR_P010 else ImageFormat.YUV_420_888, Size(size.width, size.height)).apply { dynamicRangeProfile = profile }
        } else null
        val motion = if (rung.motion) OutputConfiguration(ImageFormat.PRIVATE, Size(previewSize.width, previewSize.height), HardwareBuffer.USAGE_VIDEO_ENCODE).apply { dynamicRangeProfile = profile; timestampBase = OutputConfiguration.TIMESTAMP_BASE_SENSOR } else null
        val raw = rawStream(request)?.takeIf { rung.raw }?.let { (size, lens) -> OutputConfiguration(ImageFormat.RAW_SENSOR, Size(size.width, size.height)).apply { lens?.let { setPhysicalCameraId(it) } } }
        val raw50 = request.camera.raw50?.takeIf { rung.raw50 }?.let { OutputConfiguration(ImageFormat.RAW10, Size(it.width, it.height)).apply { addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION) } }
        val outputs = listOfNotNull(preview, video, still?.let { OutputConfiguration(format, Size(it.capture.width, it.capture.height)) }, analysis, motion, raw, raw50)
        val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs)
        config.setSessionParameters(sessionParameters(setup.createCaptureRequest(template(request)), request, rung.video))
        setup.isSessionConfigurationSupported(config)
    }.getOrNull()

    /** Configures a session; its still reader and video surface are let go when the session is closed (not before). */
    private suspend fun configure(device: CameraDevice, parameters: CaptureRequest, outputs: List<OutputConfiguration>, onClosed: () -> Unit): CameraCaptureSession? = suspendCancellableCoroutine { cont ->
        val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (cont.isActive) cont.resumeWith(Result.success(session)) else session.close()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                if (cont.isActive) cont.resumeWith(Result.success(null))
            }

            override fun onClosed(session: CameraCaptureSession) = onClosed()
        })
        config.setSessionParameters(parameters)
        device.createCaptureSession(config)
    }

    /** Starts recording into [target] (the sound when the microphone may be used); false when there is no video session or the encoder refused (the target is then removed). */
    suspend fun startRecording(target: VideoTarget, deviceRotation: Int, location: Pair<Double, Double>?): Boolean = scope.async { start(target, deviceRotation, location) }.await()

    private suspend fun start(target: VideoTarget, deviceRotation: Int, location: Pair<Double, Double>?): Boolean = withContext(NonCancellable) {
        awaitSession()
        stopping?.await()
        val recorder = recorder
        val request = desired.value.request
        if (recorder == null || request == null || recorder.recording) {
            target.close(false)
            return@withContext false
        }
        val spec = request.camera
        val sound = appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!recorder.start(target, Orientation.jpeg(spec.sensorOrientation, deviceRotation, spec.front), location, sound)) {
            target.close(false)
            return@withContext false
        }
        _state.update { it.copy(recording = Recording(SystemClock.elapsedRealtime())) }
        silenceWatch?.cancel()
        silenceWatch = scope.launch {
            while (true) {
                delay(SILENCE_CHECK_MS)
                val silenced = recorder.silenced
                _state.update { s -> s.recording?.let { if (it.silenced != silenced) s.copy(recording = it.copy(silenced = silenced)) else s } ?: s }
            }
        }
        repeat()
        true
    }

    fun pauseRecording() = scope.launch {
        val recorder = recorder ?: return@launch
        if (!recorder.recording || recorder.paused) return@launch
        recorder.pause()
        _state.update { s -> s.copy(recording = s.recording?.copy(pausedAt = SystemClock.elapsedRealtime())) }
        repeat()
    }

    fun resumeRecording() = scope.launch {
        val recorder = recorder ?: return@launch
        if (!recorder.paused) return@launch
        recorder.resume()
        _state.update { s -> s.copy(recording = s.recording?.let { r -> r.pausedAt?.let { r.copy(pausedAt = null, pausedMs = r.pausedMs + SystemClock.elapsedRealtime() - it) } ?: r }) }
        repeat()
    }

    /** The motion photo's clip around a photo (its sensor time); null without a motion stream or when nothing usable came. */
    suspend fun motionClip(photo: CapturedPhoto): MotionClip? {
        val source = photo.motionSource ?: return null
        clipping.incrementAndGet()
        return try {
            source.clip(photo.sensorTimeNs / 1000, photo.orientation)
        } finally {
            clipping.decrementAndGet()
        }
    }

    /** Ends the recording and saves it, in the engine's own scope (a screen going away doesn't cut it short); true when a video was saved. */
    suspend fun stopRecording(): Boolean = scope.async { finishRecording() }.await()

    /** Finishes the recording, or waits for one being finished. */
    private suspend fun finishRecording(): Boolean {
        stopping?.let { return it.await() }
        val recorder = recorder ?: return false
        if (!recorder.recording) return false
        silenceWatch?.cancel()
        silenceWatch = null
        _state.update { it.copy(recording = null, finishing = true) }
        // The encoder's surface leaves the repeating request before the stream ends
        repeat()
        val job = scope.async { recorder.stop() }
        stopping = job
        return try {
            job.await()
        } finally {
            if (stopping === job) stopping = null
            _state.update { it.copy(finishing = false) }
        }
    }

    /** Sets the repeating request again with the current controls. */
    private fun repeat() {
        nightSession?.let {
            repeatNight(it)
            return
        }
        // A burst asks for its own requests with the new controls (not once it is stopping); refused, it ends
        bursting?.takeIf { !it.stopping }?.let { state ->
            if (repeatBurst()) return
            state.stop()
        }
        val session = session ?: return
        val device = device ?: return
        val surface = previewSurface ?: return
        val request = desired.value.request ?: return
        try {
            session.setRepeatingRequest(previewRequest(device, surface, request), results, handler)
        } catch (e: Exception) {
            Log.w(TAG, "Repeating request failed: ${e.javaClass.simpleName}")
        }
    }

    /** The repeating request: the preview with what goes along with it. */
    private fun previewRequest(device: CameraDevice, surface: Surface, request: CameraRequest): CaptureRequest {
        val builder = device.createCaptureRequest(template(request))
        builder.addTarget(surface)
        addRecordingTarget(builder)
        applyControls(builder, request)
        return builder.build()
    }

    /** The encoder gets frames only while a recording runs and isn't paused (a trigger's frame too, so focusing doesn't drop one from the video). */
    private fun addRecordingTarget(builder: CaptureRequest.Builder) {
        val recording = _state.value.recording
        if (recording != null && recording.pausedAt == null) recorder?.let { builder.addTarget(it.surface) }
        // The analysis frames go with every preview frame (the analyzer takes what it can keep up with), the motion photo's too
        analyzing?.let { builder.addTarget(it.reader.surface) }
        motion?.let { builder.addTarget(it.surface) }
    }

    /** The analysis stream's size: the smallest 4:3 at least 640 wide (QR codes need the detail), in 10 bits next to an HLG preview. */
    private fun analysisSize(spec: CameraSpec, range: PictureRange): PixelSize? =
        // 4:3 only: the frame must cover whatever the preview shows (the QR corners and the glass are mapped from it)
        Sizes.analysis(if (range == PictureRange.HLG) spec.p010 else spec.yuv, minWidth = ANALYSIS_WIDTH)?.takeIf { Sizes.near(it, Aspect.FOUR_THREE) }

    /** Who reads the analysis frames (QR codes, the finder glass); called on the analysis thread, the frame valid only during the call. */
    @Volatile
    var analyzer: ((AnalysisFrame) -> Unit)? = null

    private val analysisThread = HandlerThread("camera-analysis").apply { start() }
    private val analysisHandler = Handler(analysisThread.looper)

    /** The analysis stream's reader; frames are handed to [analyzer] on their own thread, the newest only. */
    private inner class AnalysisOutput(size: PixelSize, val tenBit: Boolean, val spec: CameraSpec) {
        val reader: ImageReader = ImageReader.Builder(size.width, size.height).setImageFormat(if (tenBit) ImageFormat.YCBCR_P010 else ImageFormat.YUV_420_888).setMaxImages(ANALYSIS_BUFFERS).build()
        private var closed = false

        init {
            reader.setOnImageAvailableListener({ r ->
                val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                image.use { frame -> if (analyzing === this) analyzer?.let { runCatching { it(AnalysisFrame(frame, tenBit, spec.sensorOrientation, spec.front)) } } }
            }, analysisHandler)
        }

        /** Closed on the analysis thread, after a frame being read there (its buffers would go under it). */
        fun close() {
            if (closed) return
            closed = true
            analysisHandler.post { reader.close() }
        }
    }

    /**
     * A one-off request with an AF trigger (and the exposure's precapture metering), on top of the repeating one. [flashMetering]: the pre-capture meters with the
     * flash's exposure mode (a flash shot), else with plain auto-exposure (a lock).
     */
    private fun trigger(af: Int, precapture: Boolean, flashMetering: Boolean) {
        // Night's extension takes no one-off requests besides its photo: its focus follows the tapped region continuously
        val session = session ?: return
        val device = device ?: return
        val surface = previewSurface ?: return
        val request = desired.value.request ?: return
        if (!request.camera.autofocus && !precapture) return
        try {
            val builder = device.createCaptureRequest(template(request))
            builder.addTarget(surface)
            addRecordingTarget(builder)
            applyControls(builder, request)
            // Not with Pro's exposure set by hand: a one-off frame of the camera's own exposure would blink and mislead the metering
            if (!flashMetering && manualExposure(request) == null) builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            if (request.camera.autofocus) builder.set(CaptureRequest.CONTROL_AF_TRIGGER, af)
            if (precapture) builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            session.capture(builder.build(), null, handler)
        } catch (e: Exception) {
            Log.w(TAG, "Trigger failed: ${e.javaClass.simpleName}")
        }
    }

    /** How long a still is waited for: a long exposure set by hand (up to 16 s) on top of the usual. */
    private fun stillTimeoutMs() = STILL_TIMEOUT_MS + (controls.manual?.timeNs ?: 0L) / 1_000_000

    /** Pro's exposure set by hand, where it applies (Pro, a camera with the manual sensor). */
    private fun manualExposure(request: CameraRequest): Exposure? = controls.manual?.takeIf { request.mode == Mode.PRO && request.camera.manualSensor }

    /** [still]: a photo's request (the exposure set by hand as it is, the flash fired); otherwise a preview's. */
    private fun applyControls(builder: CaptureRequest.Builder, request: CameraRequest, still: Boolean = false) {
        val spec = request.camera
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, controls.zoom.toFloat())
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, controls.exposure.coerceIn(spec.exposureSteps))
        builder.set(CaptureRequest.CONTROL_AE_LOCK, controls.locked)
        // Off only for a still phone's long exposure (the preview too, so the lens has settled before the photo)
        if (controls.stabilizationOff && spec.stabilizationSwitchable) builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        if (spec.faceDetection) builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE)
        _state.value.video?.let { video ->
            if (video.fps in spec.fixedFps) builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(video.fps, video.fps))
            if (spec.videoStabilization) builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
        }
        if (spec.flash) {
            // The torch (flashlight, the timer's LED) needs plain auto-exposure; the photo flash is the exposure's mode
            val photoFlash = (request.mode == Mode.PHOTO || request.mode == Mode.PRO) && !controls.torch
            builder.set(
                CaptureRequest.CONTROL_AE_MODE,
                when {
                    photoFlash && controls.flash == Flash.AUTO -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
                    photoFlash && controls.flash == Flash.ON -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                    else -> CameraMetadata.CONTROL_AE_MODE_ON
                },
            )
            builder.set(CaptureRequest.FLASH_MODE, if (controls.torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
            // The strength is the photo flash's (the torch has its own range)
            if (photoFlash && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && spec.flashLevels > 1) {
                controls.flashLevel?.let { builder.set(CaptureRequest.FLASH_STRENGTH_LEVEL, it.coerceIn(1, spec.flashLevels)) }
            }
        }
        val point = controls.focus
        if (spec.autofocus) {
            val continuous = if (request.mode == Mode.VIDEO) CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            builder.set(CaptureRequest.CONTROL_AF_MODE, if (point != null) CameraMetadata.CONTROL_AF_MODE_AUTO else continuous)
        }
        if (point != null) {
            val region = Orientation.meteringRegion(point, spec.sensorOrientation, spec.front, visible(request))
            val rect = arrayOf(MeteringRectangle(region.left, region.top, region.width, region.height, MeteringRectangle.METERING_WEIGHT_MAX - 1))
            if (spec.afRegions > 0) builder.set(CaptureRequest.CONTROL_AF_REGIONS, rect)
            if (spec.aeRegions > 0) builder.set(CaptureRequest.CONTROL_AE_REGIONS, rect)
        }
        // Pro's focus and white balance set by hand, each on its own
        if (request.mode == Mode.PRO) {
            // The view model holds the distance to the active lens' range
            controls.focusDiopters?.takeIf { spec.autofocus }?.let { diopters ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, diopters.coerceAtLeast(0.0).toFloat())
            }
            controls.whiteBalance?.let { balance ->
                // The camera's HDR processing shifts red with its own balance off: undone in an HLG session (measured on a Pixel only)
                val red = if (_state.value.range == PictureRange.HLG && GOOGLE) balance.red * WhiteBalance.hdrRedCorrection(balance.kelvin) else balance.red
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(red.toFloat(), balance.green.toFloat(), balance.green.toFloat(), balance.blue.toFloat()))
                builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, ColorSpaceTransform(IntArray(18) { i -> if (i % 2 == 0) (balance.transform.values[i / 2] * TRANSFORM_DENOMINATOR).roundToInt() else TRANSFORM_DENOMINATOR }))
            } ?: controls.whiteBalancePreset?.takeIf { it in spec.awbModes }?.let { builder.set(CaptureRequest.CONTROL_AWB_MODE, it) }
        }
        manualExposure(request)?.let { manual ->
            val exposure = if (still) manual else ProExposure.preview(manual, spec.isoRange.last)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, exposure.iso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure.timeNs)
            // The preview keeps its pace where the time allows; a photo takes as long as its time
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, if (still) exposure.timeNs else maxOf(exposure.timeNs, PREVIEW_FRAME_NS))
            // The flash without the camera's exposure: fired once with the photo when it is on (automatic means nothing when the exposure is the user's)
            if (spec.flash && !controls.torch) builder.set(CaptureRequest.FLASH_MODE, if (still && controls.flash == Flash.ON) CameraMetadata.FLASH_MODE_SINGLE else CameraMetadata.FLASH_MODE_OFF)
        }
    }

    /** The part of the active array the preview shows: with the zoom ratio the whole array stands for the zoomed view, so only the preview's aspect cuts it. */
    private fun visible(request: CameraRequest): PixelRect {
        val array = request.camera.activeArray
        val ratio = previewSize(request)?.ratio ?: (array.width.toDouble() / array.height)
        return Orientation.visible(array.width, array.height, ratio)
    }

    private val results = object : CameraCaptureSession.CaptureCallback() {
        // Late results of a session already closed don't speak for the next one (its first frame lifts the cover)
        override fun onCaptureCompleted(session: CameraCaptureSession, captureRequest: CaptureRequest, result: TotalCaptureResult) {
            if (session === this@CameraEngine.session) onResult(result)
        }
    }

    /** The Night extension's preview results, read as the regular session's. */
    private val nightResults = object : CameraExtensionSession.ExtensionCaptureCallback() {
        // An extension that reports no results still shows its frames: the cover goes with the first one
        override fun onCaptureStarted(session: CameraExtensionSession, request: CaptureRequest, timestamp: Long) {
            if (session === nightSession && !_state.value.frameShown) _state.update { it.copy(frameShown = true) }
        }

        override fun onCaptureResultAvailable(session: CameraExtensionSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (session === nightSession) onResult(result)
        }
    }

    /**
     * The scene's brightness as EV at ISO 100, from what the exposure took: aperture, time and the whole gain (the sensor's and the one after RAW, where a phone may put what its analog
     * gain lacks). Thresholds on the ISO or the time alone may miss dark scenes: the gain may go into the boost, and a session with a video stream may keep the
     * time at 1/30 s. The user's compensation is added back (with +1 EV the exposure takes twice the light of the same scene).
     */
    private fun sceneEv(result: CaptureResult, request: CameraRequest): Double? {
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)?.takeIf { it > 0 } ?: return null
        val time = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.takeIf { it > 0 } ?: return null
        val boost = result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST)?.takeIf { it > 0 } ?: 100
        val aperture = result.get(CaptureResult.LENS_APERTURE)?.takeIf { it > 0 }?.toDouble() ?: DEFAULT_APERTURE
        val gain = iso * boost / 100.0
        // Night's extension takes no compensation
        val compensation = if (request.mode == Mode.NIGHT) 0.0 else controls.exposure.coerceIn(request.camera.exposureSteps) * request.camera.exposureStep
        return log2(aperture * aperture / (time / 1e9)) - log2(gain / 100) + compensation
    }

    /** The newest Night photo asked for: the next one waits for it (camera thread). */
    private var nightTail: Job? = null

    /** The sensor time of the newest frame the session reported (camera thread). */
    private var latestFrameNs = 0L

    private fun onResult(result: TotalCaptureResult) {
        lastResult = result
        result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { latestFrameNs = maxOf(latestFrameNs, it) }
        activeLens = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
        resultSeen = true
        // Pro's values: every frame's kept for the metering, a few a second shown (not with every frame: whoever shows them would redraw each time)
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
        val time = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val stamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
        if (iso != null && time != null) {
            val boost = result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST)?.takeIf { it > 0 } ?: 100
            val exposure = Exposure(iso * boost / 100, time)
            if (stamp != null) {
                val settled = result.get(CaptureResult.CONTROL_AE_STATE) == CameraMetadata.CONTROL_AE_STATE_CONVERGED
                synchronized(seen) {
                    seen.addLast(stamp to SeenExposure(exposure, settled))
                    while (seen.size > SEEN_KEPT) seen.removeFirst()
                }
            }
            val now = SystemClock.uptimeMillis()
            if (now - liveNowAt >= LIVE_NOW_MS) {
                liveNowAt = now
                val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.takeIf { it.greenEven > 0f }
                _liveNow.value = LiveNow(
                    exposure,
                    result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.toDouble(),
                    gains?.let { it.red.toDouble() / it.greenEven },
                    gains?.let { it.blue.toDouble() / it.greenEven },
                    result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID),
                    sessionNumber,
                )
            }
        }
        run {
            val request = desired.value.request ?: return
            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
            this@CameraEngine.aeState.value = aeState
            if (aeState == CameraMetadata.CONTROL_AE_STATE_PRECAPTURE) precaptureSeen = true
            aeSettled.value = aeState == null || aeState == CameraMetadata.CONTROL_AE_STATE_CONVERGED || aeState == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED || aeState == CameraMetadata.CONTROL_AE_STATE_LOCKED
            val focusing = afState == CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN || afState == CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN
            val face = steadyFace(focusedFace(result, request))
            val light = sceneEv(result, request)
            // Darker than the threshold to become dark, clearly lighter to stop being it: noise around the threshold would flicker the flash's amber and restart the Night suggestion's wait
            // A result without the exposure's numbers keeps what was known
            val dark = if (light == null) _state.value.dark else if (_state.value.dark) light < LIGHT_EV else light <= DARK_EV
            // The exposure's own word where the camera gives it; the Pixel doesn't in the preview, so a dark scene counts too (as for the front camera's screen flash)
            val flashNeeded = aeState == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED || dark
            val lens = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
            val macro = lens != null && lens in request.camera.ultrawideIds && controls.zoom >= (request.camera.zoom.group.firstOrNull()?.ratio ?: 1.0) - 1e-6
            _state.update {
                if (it.frameShown && it.focusing == focusing && it.face == face && it.dark == dark && it.flashNeeded == flashNeeded && it.macro == macro) it
                else it.copy(frameShown = true, focusing = focusing, face = face, dark = dark, flashNeeded = flashNeeded, macro = macro)
            }
        }
    }

    /** The face the camera focuses on: the one under the tapped point, else the largest. Face rectangles share the regions' coordinates (the zoomed view on the active array). */
    private fun focusedFace(result: TotalCaptureResult, request: CameraRequest): FaceMark? {
        val faces = result.get(CaptureResult.STATISTICS_FACES)?.takeIf { it.isNotEmpty() } ?: return null
        val spec = request.camera
        val visible = visible(request)
        val rects = faces.map { PixelRect(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom) }
        val point = controls.focus?.let { Orientation.viewToSensor(it, spec.sensorOrientation, spec.front) }
        val chosen = point?.let { p ->
            val x = visible.left + p.x * visible.width
            val y = visible.top + p.y * visible.height
            rects.firstOrNull { x >= it.left && x <= it.right && y >= it.top && y <= it.bottom }
        } ?: rects.maxBy { it.width.toLong() * it.height }
        val (a, b) = Orientation.regionToView(chosen, visible, spec.sensorOrientation, spec.front)
        // The Pixel 9 Pro's cameras (back and front) report only the inner face, eyes to mouth, centred on it (owner's screenshots, 23 Nov 2026): grown around its
        // centre to the whole face, taller than wide along the face, which stands as the phone is held
        val upright = deviceRotation == 0 || deviceRotation == 180
        val width = (b.x - a.x) * (if (upright) FACE_ACROSS else FACE_ALONG)
        val height = (b.y - a.y) * (if (upright) FACE_ALONG else FACE_ACROSS)
        val cx = (a.x + b.x) / 2
        val cy = (a.y + b.y) / 2
        return FaceMark(Point(cx - width / 2, cy - height / 2), Point(cx + width / 2, cy + height / 2))
    }

    /**
     * The face mark shown: a new detection replaces it only when a corner moved by more than a small share of the picture (detections jitter by a few pixels on
     * every frame), and a face missing from a few frames isn't dropped at once.
     */
    private fun steadyFace(found: FaceMark?): FaceMark? {
        val shown = shownFace
        if (found == null) {
            if (shown != null && ++faceMissing <= FACE_HOLD_FRAMES) return shown
            shownFace = null
            return null
        }
        faceMissing = 0
        fun close(p: Point, q: Point) = abs(p.x - q.x) < FACE_STEADY && abs(p.y - q.y) < FACE_STEADY
        if (shown != null && close(shown.topLeft, found.topLeft) && close(shown.bottomRight, found.bottomRight)) return shown
        shownFace = found
        return found
    }

    /** Night's session (the phone's extension, LOGIKA §17) in place of the regular one, with its photo's reader and the early postview's. */
    private var nightSession: CameraExtensionSession? = null
    private var postview: PostviewOutput? = null

    /** Night photos asked for and not yet out (the extension queues them). */
    private val nightInFlight = AtomicInteger(0)

    private val _postviewImage = MutableStateFlow<Bitmap?>(null)

    /** The newest Night photo's early postview, shown in the picture until its file is there; null when none is wanted. */
    val postviewImage: StateFlow<Bitmap?> = _postviewImage.asStateFlow()

    /** How the newest Night photo's postview lies: the sensor's turn, the photo's JPEG turn, the front camera. */
    private class PostviewTurn(val sensor: Int, val jpeg: Int, val front: Boolean)

    @Volatile
    private var postviewTurn: PostviewTurn? = null

    /**
     * The postview turned to lie as the live picture does on the portrait screen: the sensor's turn, less what the camera already turned the pixels by (the
     * JPEG's turn not left to the EXIF), and for the front camera mirrored as its preview is, so it covers the live picture without a jump.
     */
    private fun turnPostview(bytes: ByteArray, turn: PostviewTurn): Bitmap? {
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val exif = runCatching { ExifInterface(ByteArrayInputStream(bytes)).rotationDegrees }.getOrDefault(0)
        val pixels = ((turn.jpeg - exif) % 360 + 360) % 360
        val degrees = ((turn.sensor - pixels) % 360 + 360) % 360
        val matrix = Matrix().apply {
            postRotate(degrees.toFloat())
            if (turn.front) postScale(-1f, 1f)
        }
        val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (upright !== decoded) decoded.recycle()
        return upright
    }

    /** Clears the postview shown (its photo is saved, or it was shown long enough). */
    fun clearPostview() {
        _postviewImage.value = null
    }

    /**
     * Night's session: the extension with the preview (SDR: it takes no HLG, LOGIKA §21.1) and the photo (Ultra HDR where the extension and the setting allow, at
     * the extension's largest size for the aspect – 5 MP on the Pixel, D10), plus the early postview where the extension has one.
     */
    private suspend fun makeNight(want: Desired, request: CameraRequest, surface: Surface): Boolean {
        val device = device ?: return false
        val spec = request.camera
        val ext = spec.night
        if (ext == null) {
            Log.w(TAG, "No Night extension for this camera")
            failed(CameraDevice.StateCallback.ERROR_CAMERA_DEVICE)
            return false
        }
        // A ladder of its own: Ultra HDR (where the extension and the setting allow) before plain JPEG, each with the early postview before without it
        val formats = listOfNotNull(ImageFormat.JPEG_R.takeIf { request.ultraHdr && ext.jpegR.isNotEmpty() }, ImageFormat.JPEG)
        var made: CameraExtensionSession? = null
        var output: StillOutput? = null
        var post: PostviewOutput? = null
        ladder@ for (format in formats) {
            val formatPlan = Sizes.still(if (format == ImageFormat.JPEG_R) ext.jpegR else ext.jpeg, request.pictureAspect) ?: continue
            val postSize = if (ext.postview) postviewSize(spec.id, formatPlan.capture, format) else null
            for (withPostview in listOfNotNull(true.takeIf { postSize != null }, false)) {
                if (!surface.isValid || this.device !== device || deviceLost) return false
                val stillOutput = runCatching { StillOutput(formatPlan, if (format == ImageFormat.JPEG_R) StillFormat.JPEG_R else StillFormat.JPEG, format) }.getOrNull() ?: continue
                val postOutput = if (withPostview && postSize != null) runCatching { PostviewOutput(postSize, format) }.getOrNull() else null
                val session = try {
                    // An extension that never answers doesn't hold the camera's thread (a late session is closed in onConfigured)
                    withTimeoutOrNull(NIGHT_SETUP_MS) { suspendCancellableCoroutine<CameraExtensionSession?> { cont ->
                        val config = ExtensionSessionConfiguration(
                            CameraExtensionCharacteristics.EXTENSION_NIGHT,
                            listOf(OutputConfiguration(surface), OutputConfiguration(stillOutput.reader.surface)),
                            executor,
                            object : CameraExtensionSession.StateCallback() {
                                override fun onConfigured(session: CameraExtensionSession) {
                                    if (cont.isActive) cont.resumeWith(Result.success(session)) else session.close()
                                }

                                override fun onConfigureFailed(session: CameraExtensionSession) {
                                    if (cont.isActive) cont.resumeWith(Result.success(null))
                                }

                                override fun onClosed(session: CameraExtensionSession) {
                                    stillOutput.close()
                                    postOutput?.close()
                                }
                            },
                        )
                        postOutput?.let { config.postviewOutputConfiguration = OutputConfiguration(it.reader.surface) }
                        device.createExtensionSession(config)
                    } }
                } catch (e: Exception) {
                    Log.w(TAG, "Night session rung failed: ${e.javaClass.simpleName}")
                    null
                }
                if (session == null) {
                    stillOutput.close()
                    postOutput?.close()
                    continue
                }
                made = session
                output = stillOutput
                post = postOutput
                break@ladder
            }
        }
        if (made == null) {
            if (surface.isValid && this.device === device && !deviceLost) failed(CameraDevice.StateCallback.ERROR_CAMERA_DEVICE)
            return false
        }
        if (this.device !== device || deviceLost || desired.value.shape() != want.shape()) {
            runCatching { made.close() }
            return false
        }
        nightSession = made
        still = output
        postview = post
        previewSurface = surface
        controls = controls.copy(zoom = controls.zoom.coerceIn(spec.zoom.min, spec.zoom.max))
        _state.update { EngineState(CameraStatus.Ready, request, PictureRange.SDR, null, frameShown = false) }
        repeat()
        return true
    }

    /**
     * The postview's size for a photo: the photo's shape first (a 16:9 postview of a 4:3 photo is a cut of it and shows the photo zoomed in), then the width
     * closest to the preview's (a picture to look at, not to keep).
     */
    private fun postviewSize(id: String, capture: PixelSize, format: Int): PixelSize? = runCatching {
        val shape = capture.width.toDouble() / capture.height
        manager.getCameraExtensionCharacteristics(id).getPostviewSupportedSizes(CameraExtensionCharacteristics.EXTENSION_NIGHT, Size(capture.width, capture.height), format)
            .minWithOrNull(compareBy<Size> { abs(it.width.toDouble() / it.height - shape) > SHAPE_TOLERANCE }.thenBy { abs(it.width - POSTVIEW_WIDTH) })
            ?.let { PixelSize(it.width, it.height) }
    }.getOrNull()

    private fun repeatNight(session: CameraExtensionSession) {
        val device = device ?: return
        val surface = previewSurface ?: return
        val request = desired.value.request ?: return
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            nightControls(builder, request)
            session.setRepeatingRequest(builder.build(), executor, nightResults)
        } catch (e: Exception) {
            Log.w(TAG, "Night's repeating request failed: ${e.javaClass.simpleName}")
        }
    }

    /** The keys the Night extension takes (LOGIKA §16): the zoom and the tapped region for focus and exposure, focus following it continuously (no trigger). */
    private fun nightControls(builder: CaptureRequest.Builder, request: CameraRequest) {
        val spec = request.camera
        val night = spec.night
        // Only what the extension takes (it says so by name; nothing said: everything is set), the zoom within its own range
        fun takes(key: CaptureRequest.Key<*>) = night == null || night.requestKeys.isEmpty() || key.name in night.requestKeys
        if (takes(CaptureRequest.CONTROL_ZOOM_RATIO)) builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, (night?.zoom?.let { controls.zoom.coerceIn(it) } ?: controls.zoom).toFloat())
        if (spec.autofocus && takes(CaptureRequest.CONTROL_AF_MODE)) builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        controls.focus?.let { point ->
            val region = Orientation.meteringRegion(point, spec.sensorOrientation, spec.front, visible(request))
            val rect = arrayOf(MeteringRectangle(region.left, region.top, region.width, region.height, MeteringRectangle.METERING_WEIGHT_MAX - 1))
            if (spec.afRegions > 0 && takes(CaptureRequest.CONTROL_AF_REGIONS)) builder.set(CaptureRequest.CONTROL_AF_REGIONS, rect)
            if (spec.aeRegions > 0 && takes(CaptureRequest.CONTROL_AE_REGIONS)) builder.set(CaptureRequest.CONTROL_AE_REGIONS, rect)
        }
    }

    /**
     * A Night photo (LOGIKA §17): [onShutter] when its frames start, [NightShot.gathered] when they are all in (the phone may move again, another photo may be
     * asked for), then the photo once the extension has put it together. Null when there is no Night session.
     */
    suspend fun takeNightPhoto(deviceRotation: Int, location: Location?, onShutter: () -> Unit): NightShot? = withContext(dispatcher) {
        awaitSession()
        if (!sessionSettled()) return@withContext null
        val session = nightSession ?: return@withContext null
        val output = still ?: return@withContext null
        val request = desired.value.request ?: return@withContext null
        val device = device ?: return@withContext null
        val gathered = CompletableDeferred<Boolean>()
        val progress = MutableStateFlow(0)
        val failed = CompletableDeferred<Unit>()
        val orientation = Orientation.jpeg(request.camera.sensorOrientation, deviceRotation, request.camera.front)
        var startedAt = 0L
        // This photo's postview, turned to lie as the live picture does (an earlier one goes)
        _postviewImage.value = null
        postviewTurn = PostviewTurn(request.camera.sensorOrientation, orientation, request.camera.front)
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(output.reader.surface)
            postview?.let { builder.addTarget(it.reader.surface) }
            nightControls(builder, request)
            builder.set(CaptureRequest.JPEG_ORIENTATION, orientation)
            builder.set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY)
            location?.let { builder.set(CaptureRequest.JPEG_GPS_LOCATION, it) }
            session.capture(builder.build(), executor, object : CameraExtensionSession.ExtensionCaptureCallback() {
                override fun onCaptureStarted(session: CameraExtensionSession, request: CaptureRequest, timestamp: Long) {
                    startedAt = timestamp
                    onShutter()
                }

                override fun onCaptureProcessStarted(session: CameraExtensionSession, request: CaptureRequest) {
                    gathered.complete(true)
                }

                override fun onCaptureProcessProgressed(session: CameraExtensionSession, request: CaptureRequest, value: Int) {
                    progress.value = value
                }

                override fun onCaptureFailed(session: CameraExtensionSession, request: CaptureRequest) {
                    gathered.complete(false)
                    failed.complete(Unit)
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Night photo not asked for: ${e.javaClass.simpleName}")
            return@withContext null
        }
        val takenAt = System.currentTimeMillis()
        nightInFlight.incrementAndGet()
        // The extension's photos come in the order they were asked for: each waits for the next image, its time counted from the previous one's end (a
        // queue of several would otherwise run out of time while waiting its turn)
        val previous = nightTail
        val photo = scope.async {
            try {
                previous?.join()
                val image = withTimeoutOrNull(NIGHT_TIMEOUT_MS) {
                    var found: StillImage? = null
                    while (found == null) {
                        val next = select<StillImage?> {
                            output.images.onReceiveCatching { it.getOrNull() }
                            failed.onAwait { null }
                        } ?: break
                        // A late image of an earlier photo that timed out is older than this one's start: it isn't taken for this one
                        if (startedAt != 0L && next.timestamp < startedAt) next.close() else found = next
                    }
                    found
                } ?: return@async null
                val bytes = image.use { it.compressedBytes() }
                // Its frames are in, whether or not the extension said so
                gathered.complete(true)
                CapturedPhoto(bytes, output.format, cropFor(output.plan.capture, request), takenAt, if (startedAt != 0L) startedAt else image.timestamp, null, orientation)
            } catch (e: Exception) {
                Log.w(TAG, "Night photo failed: ${e.javaClass.simpleName}")
                null
            } finally {
                gathered.complete(false)
                nightInFlight.decrementAndGet()
            }
        }
        nightTail = photo
        NightShot(gathered, photo, progress.asStateFlow())
    }

    /** The early postview's reader: each image decoded small and shown (LOGIKA §17). */
    private inner class PostviewOutput(size: PixelSize, format: Int) {
        val reader: ImageReader = ImageReader.Builder(size.width, size.height).setImageFormat(format).setMaxImages(2).build()
        private var closed = false

        init {
            reader.setOnImageAvailableListener({ r ->
                val image = runCatching { r.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
                val bytes = image.use { it.compressedBytes() }
                val turn = postviewTurn ?: return@setOnImageAvailableListener
                // Decoded and turned off the camera's thread (tens of milliseconds for Ultra HDR)
                scope.launch(Dispatchers.Default) {
                    val picture = runCatching { turnPostview(bytes, turn) }.getOrNull()
                    withContext(dispatcher) { if (postview === this@PostviewOutput && postviewTurn === turn) _postviewImage.value = picture }
                }
            }, handler)
        }

        fun close() {
            if (closed) return
            closed = true
            reader.close()
        }
    }

    /** Closes the session; its still reader closes when the camera has really let go of it ([configure]'s `onClosed`). */
    private fun closeSession() {
        // The next session's values aren't this one's: its first result is published at once
        sessionNumber++
        liveNowAt = 0L
        // No picture any more: the viewfinder covers until the next session's first frame (a stale "shown" let a frozen frame go over nothing)
        if ((session != null || nightSession != null) && _state.value.frameShown) _state.update { it.copy(frameShown = false) }
        // A burst of this session asks for nothing more (a new session's repeat() must not ask for it)
        bursting?.stop()
        // Another camera or session may count its time differently
        latestFrameNs = 0L
        lockJob?.cancel()
        // The prepared encoder goes at once (the next session's may need the hardware); the surface goes when the session has closed
        recorder?.retire()
        // The motion photo's encoder too (the next session's may need the hardware at once)
        motion?.release()
        runCatching { session?.close() }
        runCatching { nightSession?.close() }
        session = null
        nightSession = null
        postview = null
        _postviewImage.value = null
        sessionKey = null
        still = null
        rawOutput = null
        raw50Output = null
        activeLens = null
        resultSeen = false
        lastResult = null
        recorder = null
        analyzing = null
        motion = null
        previewSurface = null
    }

    private fun closeDevice() {
        closeSession()
        runCatching { device?.close() }
        device = null
    }

    /** A still taken from the reader; closing it gives its buffer back and lets the reader hand on the next one waiting. */
    private class StillImage(private val image: Image, private val released: () -> Unit) : AutoCloseable {
        private var closed = false
        val timestamp: Long get() = image.timestamp
        val width: Int get() = image.width
        val height: Int get() = image.height

        fun compressedBytes() = image.compressedBytes()

        /** A raw image's single plane and the bytes from one row's start to the next; valid until closed. */
        fun plane(): Pair<ByteBuffer, Int> = image.planes[0].let { it.buffer to it.rowStride }

        /** A packed raw image's (RAW10) rows without the padding at their ends, copied out of the camera's buffer (it goes back at once). */
        fun packedPlane(): ByteBuffer {
            val plane = image.planes[0]
            val rowBytes = image.width * 5 / 4
            val out = ByteBuffer.allocateDirect(rowBytes * image.height)
            val buffer = plane.buffer
            val row = ByteArray(rowBytes)
            for (y in 0 until image.height) {
                buffer.position(y * plane.rowStride)
                buffer.get(row)
                out.put(row)
            }
            out.flip()
            return out
        }

        /** A raw image's 16-bit pixels packed row after row (DngCreator takes no padding at the rows' ends). */
        fun rawPixels(): Pair<PixelSize, ByteArray> {
            val plane = image.planes[0]
            val rowBytes = image.width * 2
            val pixels = ByteArray(rowBytes * image.height)
            val buffer = plane.buffer
            for (row in 0 until image.height) {
                buffer.position(row * plane.rowStride)
                buffer.get(pixels, row * rowBytes, rowBytes)
            }
            return PixelSize(image.width, image.height) to pixels
        }

        override fun close() {
            if (closed) return
            closed = true
            image.close()
            released()
        }
    }

    /** The still's reader and the images it delivers. */
    private inner class StillOutput(val plan: StillPlan, val format: StillFormat, imageFormat: Int) : ImageOutput(plan.capture.width, plan.capture.height, imageFormat, STILL_BUFFERS)

    /** A reader of stills (or raw images) and the images it delivers, at most [buffers] held at once. */
    private open inner class ImageOutput(width: Int, height: Int, imageFormat: Int, private val buffers: Int, val lens: String? = null) {
        val reader: ImageReader = ImageReader.Builder(width, height).setImageFormat(imageFormat).setMaxImages(buffers).build()
        // An image the channel can't hand on any more (closed, a receive cancelled) is closed, its buffer back
        val images = Channel<StillImage>(Channel.UNLIMITED, onUndeliveredElement = { it.close() })
        private var closed = false
        /** Images taken from the reader and not closed yet (on the camera thread). */
        private var held = 0

        init {
            reader.setOnImageAvailableListener({ pull() }, handler)
        }

        /**
         * Takes every image waiting, as far as the reader's buffers allow: past the limit an acquire fails and its image's one callback is spent, so a frame
         * left in the queue then would only come with the next one's callback – every later still a frame behind (a burst's end left stills like that).
         */
        private fun pull() {
            while (!closed && held < buffers) {
                val image = runCatching { reader.acquireNextImage() }.getOrNull() ?: return
                held++
                val still = StillImage(image) {
                    handler.post {
                        held--
                        // Closed meanwhile: the reader goes with its last image (a series' frame may be read on another thread until then)
                        if (closed) {
                            if (held == 0) reader.close()
                        } else {
                            pull()
                        }
                    }
                }
                if (images.trySend(still).isFailure) still.close()
            }
        }

        /** Closes the stills waiting (the ones still queued in the reader come after, older than what is asked next, and are dropped by their time). */
        fun drain() = generateSequence { images.tryReceive().getOrNull() }.forEach { it.close() }

        fun close() {
            if (closed) return
            closed = true
            drain()
            images.close()
            // An image still held (a series' frame going to the GPU) keeps the reader and its buffers until it is let go
            if (held == 0) reader.close()
        }
    }

    private class DeviceError(val error: Int) : Exception("camera error $error")

    // Last in the class: the collector runs on the camera thread at once, and every property above must be set by then (a field declared below an init block is still null while it runs)
    init {
        manager.registerAvailabilityCallback(executor, availability)
        scope.launch {
            desired.collect { want ->
                try {
                    reconcile(want)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Camera step failed: ${e.javaClass.simpleName}")
                    runCatching { finishRecording() }
                    closeDevice()
                    failed(CameraDevice.StateCallback.ERROR_CAMERA_DEVICE)
                }
            }
        }
    }

    private companion object {
        const val TAG = "CameraEngine"
        const val JPEG_QUALITY: Byte = 95
        const val STILL_BUFFERS = 3
        /** Raw images held at once: one photo's and the next one's (about 25 MB each). */
        const val RAW_BUFFERS = 2
        /** A series' raw images held at once while each goes to the GPU (about 25 MB each): room for a frame or two of delay. */
        const val SERIES_BUFFERS = 4
        /** Frames of a series (the computer prototype's; LOGIKA §38.1). */
        const val SERIES_FRAMES = 8
        /** How long a series may take beyond its frames' time (the first frame's start, the GPU's copies, results coming late). */
        const val SERIES_SLACK_MS = 3_000L
        /** A series' next frame is waited for this long beyond its exposure; its result this long after the image. */
        const val FRAME_WAIT_MS = 1_000L
        const val RESULT_WAIT_MS = 1_000L
        /** The series' frame requests (its HLG preview requests between them aren't). */
        const val SERIES_TAG = "series"
        /** The diagonal of a 35 mm frame, for the EXIF's equivalent focal length. */
        const val FULL_FRAME_DIAGONAL_MM = 43.267
        /** How long after its photo a raw image and the result may still come. */
        const val RAW_WAIT_MS = 3_000L
        /** How long a shot waits for a new session's first result (the lens it streams from) before it decides on the DNG. */
        const val FIRST_RESULT_MS = 500L
        const val STILL_TIMEOUT_MS = 10_000L
        /** Frames handed to the saving at once; beyond them the camera waits for the saving (memory stays bounded). */
        const val BURST_HANDOFF = 4
        /** After a burst's stop, its stills still in flight are waited for this long. */
        const val BURST_TAIL_MS = 400L
        /** The preview's frame time with an exposure set by hand: 30 fps where the time allows. */
        const val PREVIEW_FRAME_NS = 33_333_333L
        /** Pro's values are reported this often. */
        const val LIVE_NOW_MS = 200L
        /** A Google phone (the HDR white balance correction was measured on a Pixel). */
        private val GOOGLE = Build.MANUFACTURER.equals("Google", ignoreCase = true)

        /** How long after a change of Pro's focus or white balance a photo isn't taken from the buffer. */
        const val LOOK_SETTLE_MS = 500L
        /** The longest time the camera's EXIF holds: 2³² − 1 ns (about 4.29 s). */
        const val EXIF_MAX_EXPOSURE_NS = 0xFFFF_FFFFL
        /** The color transform's rationals: ten thousandths. */
        const val TRANSFORM_DENOMINATOR = 10_000
        /** Results kept to pair with analysis frames: about a second. */
        const val SEEN_KEPT = 32
        /** A burst's frame waits this long for its consumer, then the burst ends (the consumer is gone). */
        const val BURST_HANDOFF_WAIT_MS = 10_000L
        /** The Night extension's session is waited for this long. */
        const val NIGHT_SETUP_MS = 5_000L
        /** The Night extension's longest shot (8 s on the Pixel) and a margin. */
        const val NIGHT_TIMEOUT_MS = 15_000L
        const val POSTVIEW_WIDTH = 1920
        /** Two sizes of this much difference in width to height are the same shape (sizes are rounded to even pixels). */
        const val SHAPE_TOLERANCE = 0.01
        /** A session being built is waited for this long by a shot or a recording. */
        const val SETTLE_MS = 3_000L
        const val SETTLE_POLL_MS = 20L
        /** A motion photo's 1.5 s after the shot and its muxing, before the session closes under it. */
        const val CLIP_WAIT_MS = 2_500L
        const val RESTARTS = 2
        const val RESTART_DELAY_MS = 1_000L
        const val LOCK_MIN_WAIT_MS = 150L
        const val LOCK_MAX_WAIT_MS = 800L
        const val SILENCE_CHECK_MS = 500L
        const val ANALYSIS_WIDTH = 640
        const val ANALYSIS_BUFFERS = 4
        const val PRECAPTURE_MAX_MS = 1_500L
        const val PRECAPTURE_POLL_MS = 16L
        /** A dark scene (about 14 lux) and the light one it waits for to stop being dark (about 28 lux). */
        const val DARK_EV = 2.5
        const val LIGHT_EV = 3.5
        /** For a camera that doesn't report its aperture: a usual phone's main lens. */
        const val DEFAULT_APERTURE = 1.8
        /** The reported inner face grown to the whole face: across it (cheek to cheek) and along it (forehead to chin). */
        const val FACE_ACROSS = 1.6
        const val FACE_ALONG = 2.2
        /** A corner of the face mark moves only when the detection moved by more than this share of the picture. */
        const val FACE_STEADY = 0.03
        /** Frames a face may be missing before its mark goes (about 0.3 s). */
        const val FACE_HOLD_FRAMES = 9
    }
}

/** A JPEG orientation in degrees as EXIF says it (the DNG's). */
private fun exifOrientation(degrees: Int) = when (degrees) {
    90 -> ExifInterface.ORIENTATION_ROTATE_90
    180 -> ExifInterface.ORIENTATION_ROTATE_180
    270 -> ExifInterface.ORIENTATION_ROTATE_270
    else -> ExifInterface.ORIENTATION_NORMAL
}

/** The bytes of a compressed image (JPEG, JPEG_R) from its single plane. */
private fun Image.compressedBytes(): ByteArray {
    val buffer = planes[0].buffer
    return ByteArray(buffer.remaining()).also { buffer.get(it) }
}
