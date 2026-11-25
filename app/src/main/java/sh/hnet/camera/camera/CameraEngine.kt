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
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraExtensionSession
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.ExtensionSessionConfiguration
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
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
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.log2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
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
import sh.hnet.camera.capabilities.model.Aspect
import sh.hnet.camera.capabilities.model.Orientation
import sh.hnet.camera.capabilities.model.PictureRange
import sh.hnet.camera.capabilities.model.PixelRect
import sh.hnet.camera.capabilities.model.PixelSize
import sh.hnet.camera.capabilities.model.Point
import sh.hnet.camera.capabilities.model.SessionLadder
import sh.hnet.camera.capabilities.model.Sizes
import sh.hnet.camera.capabilities.model.StillFormat
import sh.hnet.camera.capabilities.model.StillPlan
import sh.hnet.camera.capabilities.model.VideoChoice

enum class Mode { PHOTO, VIDEO, NIGHT }

/** What the viewfinder wants: which camera, in which mode and aspect ratio. */
data class CameraRequest(val camera: CameraSpec, val mode: Mode, val aspect: Aspect, val video: VideoChoice = VideoChoice(), val ultraHdr: Boolean = true, val motion: Boolean = false, val motionFirst: Boolean = false) {
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
)

/** A Night photo on its way: its frames gathered (true) or not, the extension's progress (0–100) while it puts the photo together, and the photo. */
class NightShot(val gathered: Deferred<Boolean>, val photo: Deferred<CapturedPhoto?>, val progress: StateFlow<Int>)

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
)

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
    fun sameSession(a: CameraRequest, b: CameraRequest): Boolean = a.copy(aspect = sessionAspect(a)) == b.copy(aspect = sessionAspect(b))

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

    private fun Desired.shape(): Desired = copy(request = request?.let { it.copy(aspect = sessionAspect(it)) })

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
        controls = controls.copy(exposure = steps)
        repeat()
    }

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

    /** Takes a still; [onShutter] runs on the camera thread when the exposure starts (the blink, the sound). Null when there is no session or the camera didn't deliver. */
    suspend fun takePhoto(deviceRotation: Int, location: Location?, precapture: Boolean, onShutter: () -> Unit): CapturedPhoto? = withContext(dispatcher) {
        // A session being built (a mode, a setting, the end of a countdown that put it off) is waited for, not taken as none; before the still is counted
        // as in flight, which the session's close would wait for
        awaitSession()
        val done = CompletableDeferred<Unit>()
        capture = done
        try {
            shoot(deviceRotation, location, precapture, onShutter)
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
    private suspend fun shoot(deviceRotation: Int, location: Location?, precapture: Boolean, onShutter: () -> Unit): CapturedPhoto? {
        val session = session ?: return null
        val output = still ?: return null
        val request = desired.value.request ?: return null
        val device = device ?: return null
        // The flash meters first: always when on, when auto only if the exposure says the flash is needed (daylight shots stay quick, from the buffer)
        val flashShot = request.mode == Mode.PHOTO && request.camera.flash && !controls.torch && (controls.flash == Flash.ON || (controls.flash == Flash.AUTO && _state.value.flashNeeded))
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
        val zsl = request.camera.zsl && !video && !metered
        if (_state.value.range == PictureRange.SDR && !zsl) motion?.let { builder.addTarget(it.surface) }
        applyControls(builder, request)
        // Set either way: the still template may turn the buffer on by itself, and a flash photo must not come from it
        if (request.camera.zsl) builder.set(CaptureRequest.CONTROL_ENABLE_ZSL, zsl)
        location?.let { builder.set(CaptureRequest.JPEG_GPS_LOCATION, it) }
        val orientation = Orientation.jpeg(request.camera.sensorOrientation, deviceRotation, request.camera.front)
        builder.set(CaptureRequest.JPEG_ORIENTATION, orientation)
        val motionSource = motion
        builder.set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY)
        output.drain()
        val takenAt = System.currentTimeMillis()
        var startedAt = 0L
        val failed = CompletableDeferred<Unit>()
        session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                startedAt = timestamp
                onShutter()
            }

            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                // The image may still come (its metadata failed, not the picture)
                if (!failure.wasImageCaptured()) failed.complete(Unit)
            }

            override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
                // Only the still's own buffer matters; the motion video's encoder may drop one
                if (target == output.reader.surface) failed.complete(Unit)
            }
        }, handler)
        val image = withTimeoutOrNull(STILL_TIMEOUT_MS) {
            var found: Image? = null
            while (found == null) {
                val next = select<Image?> {
                    output.images.onReceiveCatching { it.getOrNull() }
                    failed.onAwait { null }
                } ?: break
                // A late image of an earlier shot that timed out is older than this exposure
                if (startedAt != 0L && next.timestamp < startedAt) next.close() else found = next
            }
            found
        } ?: return null
        val bytes = image.use { it.compressedBytes() }
        return CapturedPhoto(bytes, output.format, cropFor(output.plan.capture, request), takenAt, if (startedAt != 0L) startedAt else image.timestamp, motionSource, orientation)
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
        capture?.let { inFlight -> withTimeoutOrNull(STILL_TIMEOUT_MS) { inFlight.await() } }
        if (clipping.get() > 0) withTimeoutOrNull(CLIP_WAIT_MS) { while (clipping.get() > 0) delay(SETTLE_POLL_MS) }
        // Night photos the extension is still putting together: the session stays until they are out
        if (nightInFlight.get() > 0) withTimeoutOrNull(NIGHT_TIMEOUT_MS) { while (nightInFlight.get() > 0) delay(SETTLE_POLL_MS) }
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
    private data class Rung(val range: PictureRange, val still: StillFormat?, val video: VideoFormat?, val analysis: Boolean = false, val motion: Boolean = false)

    private fun rungs(request: CameraRequest): List<Rung> {
        val spec = request.camera
        return when (request.mode) {
            Mode.PHOTO -> SessionLadder.photo(spec.hlg, spec.jpegR.isNotEmpty(), wantMotion = request.motion, wantAnalysis = true, wantUltraHdr = request.ultraHdr, motionFirst = request.motionFirst).map { Rung(it.range, it.still, null, it.analysis, it.motion) }
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
            if (!last && spec.sessionQueries && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && supported(spec.id, request, previewSize, rung, stillPlan, format) == false) continue
            // A rung with the analysis stream needs a size for it; without one the next rung (without analysis) is tried
            val analysisSize = if (rung.analysis) analysisSize(spec, rung.range) ?: continue else null
            var output: StillOutput? = null
            var video: VideoRecorder? = null
            var analysis: AnalysisOutput? = null
            var motion: MotionBuffer? = null
            val made = try {
                output = stillPlan?.let { StillOutput(it, rung.still, format) }
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
                )
                val closeOutputs: () -> Unit = {
                    output?.close()
                    video?.release()
                    analysis?.close()
                    motion?.release()
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
            recorder = video
            analyzing = analysis
            this.motion = motion
            previewSurface = surface
            controls = controls.copy(zoom = controls.zoom.coerceIn(spec.zoom.min, spec.zoom.max))
            _state.update { EngineState(CameraStatus.Ready, request, rung.range, rung.video, frameShown = false, analysis = analysis != null, motion = motion != null) }
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
        val outputs = listOfNotNull(preview, video, still?.let { OutputConfiguration(format, Size(it.capture.width, it.capture.height)) }, analysis, motion)
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
        val session = session ?: return
        val device = device ?: return
        val surface = previewSurface ?: return
        val request = desired.value.request ?: return
        try {
            val builder = device.createCaptureRequest(template(request))
            builder.addTarget(surface)
            addRecordingTarget(builder)
            applyControls(builder, request)
            session.setRepeatingRequest(builder.build(), results, handler)
        } catch (e: Exception) {
            Log.w(TAG, "Repeating request failed: ${e.javaClass.simpleName}")
        }
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
            if (!flashMetering) builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            if (request.camera.autofocus) builder.set(CaptureRequest.CONTROL_AF_TRIGGER, af)
            if (precapture) builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            session.capture(builder.build(), null, handler)
        } catch (e: Exception) {
            Log.w(TAG, "Trigger failed: ${e.javaClass.simpleName}")
        }
    }

    private fun applyControls(builder: CaptureRequest.Builder, request: CameraRequest) {
        val spec = request.camera
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, controls.zoom.toFloat())
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, controls.exposure.coerceIn(spec.exposureSteps))
        builder.set(CaptureRequest.CONTROL_AE_LOCK, controls.locked)
        if (spec.faceDetection) builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE)
        _state.value.video?.let { video ->
            if (video.fps in spec.fixedFps) builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(video.fps, video.fps))
            if (spec.videoStabilization) builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
        }
        if (spec.flash) {
            // The torch (flashlight, the timer's LED) needs plain auto-exposure; the photo flash is the exposure's mode
            val photoFlash = request.mode == Mode.PHOTO && !controls.torch
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
        val compensation = controls.exposure.coerceIn(request.camera.exposureSteps) * request.camera.exposureStep
        return log2(aperture * aperture / (time / 1e9)) - log2(gain / 100) + compensation
    }

    private fun onResult(result: TotalCaptureResult) {
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
            val dark = light != null && if (_state.value.dark) light < LIGHT_EV else light <= DARK_EV
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
                    suspendCancellableCoroutine<CameraExtensionSession?> { cont ->
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
                    }
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
        builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, controls.zoom.toFloat())
        if (spec.autofocus) builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        controls.focus?.let { point ->
            val region = Orientation.meteringRegion(point, spec.sensorOrientation, spec.front, visible(request))
            val rect = arrayOf(MeteringRectangle(region.left, region.top, region.width, region.height, MeteringRectangle.METERING_WEIGHT_MAX - 1))
            if (spec.afRegions > 0) builder.set(CaptureRequest.CONTROL_AF_REGIONS, rect)
            if (spec.aeRegions > 0) builder.set(CaptureRequest.CONTROL_AE_REGIONS, rect)
        }
    }

    /**
     * A Night photo (LOGIKA §17): [onShutter] when its frames start, [NightShot.gathered] when they are all in (the phone may move again, another photo may be
     * asked for), then the photo once the extension has put it together. Null when there is no Night session.
     */
    suspend fun takeNightPhoto(deviceRotation: Int, location: Location?, onShutter: () -> Unit): NightShot? = withContext(dispatcher) {
        awaitSession()
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
        // The extension's photos come in the order they were asked for: each waits for the next image
        val photo = scope.async {
            try {
                val image = withTimeoutOrNull(NIGHT_TIMEOUT_MS) {
                    var found: Image? = null
                    while (found == null) {
                        val next = select<Image?> {
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
                if (postview === this) _postviewImage.value = runCatching { postviewTurn?.let { turnPostview(bytes, it) } }.getOrNull()
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
        // No picture any more: the viewfinder covers until the next session's first frame (a stale "shown" let a frozen frame go over nothing)
        if ((session != null || nightSession != null) && _state.value.frameShown) _state.update { it.copy(frameShown = false) }
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

    /** The still's reader and the images it delivers. */
    private inner class StillOutput(val plan: StillPlan, val format: StillFormat, imageFormat: Int) {
        val reader: ImageReader = ImageReader.Builder(plan.capture.width, plan.capture.height).setImageFormat(imageFormat).setMaxImages(STILL_BUFFERS).build()
        val images = Channel<Image>(Channel.UNLIMITED)
        private var closed = false

        init {
            // An image beyond maxImages is dropped instead of crashing the callback thread
            reader.setOnImageAvailableListener({ r -> runCatching { r.acquireNextImage() }.getOrNull()?.let { if (images.trySend(it).isFailure) it.close() } }, handler)
        }

        fun drain() = generateSequence { images.tryReceive().getOrNull() }.forEach { it.close() }

        fun close() {
            if (closed) return
            closed = true
            drain()
            images.close()
            reader.close()
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
        const val STILL_TIMEOUT_MS = 10_000L
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

/** The bytes of a compressed image (JPEG, JPEG_R) from its single plane. */
private fun Image.compressedBytes(): ByteArray {
    val buffer = planes[0].buffer
    return ByteArray(buffer.remaining()).also { buffer.get(it) }
}
