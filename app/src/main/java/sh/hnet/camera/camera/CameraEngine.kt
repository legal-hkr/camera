package sh.hnet.camera.camera

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
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
import sh.hnet.camera.capabilities.model.PhotoSession
import sh.hnet.camera.capabilities.model.PictureRange
import sh.hnet.camera.capabilities.model.PixelRect
import sh.hnet.camera.capabilities.model.PixelSize
import sh.hnet.camera.capabilities.model.Point
import sh.hnet.camera.capabilities.model.SessionLadder
import sh.hnet.camera.capabilities.model.Sizes
import sh.hnet.camera.capabilities.model.StillFormat
import sh.hnet.camera.capabilities.model.StillPlan

enum class Mode { PHOTO, VIDEO }

/** What the viewfinder wants: which camera, in which mode and aspect ratio. */
data class CameraRequest(val camera: CameraSpec, val mode: Mode, val aspect: Aspect) {
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

data class EngineState(
    val status: CameraStatus = CameraStatus.Idle,
    val request: CameraRequest? = null,
    /** The session in use (which rung of the ladder the camera confirmed). */
    val session: PhotoSession? = null,
    /** A frame of the current session has been shown (the viewfinder's crossfade ends then). */
    val frameShown: Boolean = false,
    val focusing: Boolean = false,
    val face: FaceMark? = null,
)

/** What the repeating request carries besides the template: zoom, exposure, the tapped point, the AE/AF lock. */
data class Controls(val zoom: Double = 1.0, val exposure: Int = 0, val focus: Point? = null, val locked: Boolean = false)

/** A still as it came from the camera, with the crop the app still has to make for its aspect ratio. */
class CapturedPhoto(val bytes: ByteArray, val format: StillFormat, val crop: PixelSize?, val takenAt: Long)

/**
 * The app's camera (LOGIKA §1, §2): one device and one session at a time on a thread of its own. The viewfinder says what it wants (camera, mode, aspect, preview
 * surface, started or not) and the engine gets there step by step, trying the session ladder from the best rung down.
 */
class CameraEngine(context: Context) {
    private val appContext = context.applicationContext
    private val manager = context.getSystemService(CameraManager::class.java)
    private val policy = context.getSystemService(DevicePolicyManager::class.java)
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

    private data class Desired(val started: Boolean = false, val request: CameraRequest? = null, val surface: Surface? = null, val retry: Int = 0)

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
    private var previewSurface: Surface? = null
    private var attempts = 0
    private var pendingRetry: Job? = null
    private var lockJob: Job? = null
    /** A still being taken: the session isn't closed under it (leaving right after the shutter still saves the photo). */
    private var capture: CompletableDeferred<Unit>? = null
    /** The exposure has settled on the last frame (converged, or decided it needs the flash). */
    private val aeSettled = MutableStateFlow(false)
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
                    closeDevice()
                    failed(CameraDevice.StateCallback.ERROR_CAMERA_DEVICE)
                }
            }
        }
    }

    /** The viewfinder is shown. Coming back also tries again a camera that was busy, blocked or failed. */
    fun start() = scope.launch {
        val status = _state.value.status
        if (status == CameraStatus.Busy || status == CameraStatus.Blocked || (status is CameraStatus.Error && status.failed)) {
            attempts = 0
            bump()
        }
        desired.update { it.copy(started = true) }
    }

    fun stop() = desired.update { it.copy(started = false) }

    fun configure(request: CameraRequest) = desired.update { if (it.request == request) it else it.copy(request = request) }

    fun setPreviewSurface(surface: Surface) = desired.update { it.copy(surface = surface) }

    /** The viewfinder's surface went away; a newer one set meanwhile (a new size) stays. */
    fun clearPreviewSurface(surface: Surface) = desired.update { if (it.surface == surface) it.copy(surface = null) else it }

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
    fun previewSize(request: CameraRequest): PixelSize? = Sizes.preview(request.camera.preview, request.pictureAspect)

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
        trigger(CameraMetadata.CONTROL_AF_TRIGGER_START, precapture = lock)
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
        trigger(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL, precapture = false)
        repeat()
    }

    /** Takes a still; [onShutter] runs on the camera thread when the exposure starts (the blink, the sound). Null when there is no session or the camera didn't deliver. */
    suspend fun takePhoto(deviceRotation: Int, onShutter: () -> Unit): CapturedPhoto? = withContext(dispatcher) {
        val done = CompletableDeferred<Unit>()
        capture = done
        try {
            shoot(deviceRotation, onShutter)
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

    private suspend fun shoot(deviceRotation: Int, onShutter: () -> Unit): CapturedPhoto? {
        val session = session ?: return null
        val output = still ?: return null
        val request = desired.value.request ?: return null
        val device = device ?: return null
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        builder.addTarget(output.reader.surface)
        applyControls(builder, request)
        if (request.camera.zsl) builder.set(CaptureRequest.CONTROL_ENABLE_ZSL, true)
        builder.set(CaptureRequest.JPEG_ORIENTATION, Orientation.jpeg(request.camera.sensorOrientation, deviceRotation, request.camera.front))
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
                failed.complete(Unit)
            }

            override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
                failed.complete(Unit)
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
        return CapturedPhoto(bytes, output.format, output.plan.crop, takenAt)
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
        val surface = want.surface
        if (surface == null) {
            awaitCapture()
            closeSession()
            return
        }
        if (session != null && sessionKey == want) return
        awaitCapture()
        closeSession()
        if (makeSession(want, request, surface)) {
            sessionKey = want
            attempts = 0
        }
    }

    /** A still in flight finishes before its session goes (at most the still's timeout). */
    private suspend fun awaitCapture() {
        val inFlight = capture ?: return
        withTimeoutOrNull(STILL_TIMEOUT_MS) { inFlight.await() }
    }

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

    /**
     * Tries the ladder's rungs from the best. Where the camera can answer without being configured (Android 15+) a rung it refuses is skipped – except the last,
     * the plainest, which is always tried rather than leaving the viewfinder without a picture.
     */
    private suspend fun makeSession(want: Desired, request: CameraRequest, surface: Surface): Boolean {
        val device = device ?: return false
        val spec = request.camera
        val previewSize = previewSize(request)
        if (previewSize == null) {
            Log.w(TAG, "No preview size for this camera")
            failed(CameraDevice.StateCallback.ERROR_CAMERA_DEVICE)
            return false
        }
        val template = if (request.mode == Mode.VIDEO) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
        val rungs: List<PhotoSession> = when (request.mode) {
            Mode.PHOTO -> SessionLadder.photo(spec.hlg, spec.jpegR.isNotEmpty(), wantMotion = false, wantAnalysis = false)
            // Recording comes with stage 1b; until then the video mode is its preview alone (the still format is unused)
            Mode.VIDEO -> listOfNotNull(if (spec.hlg) PhotoSession(PictureRange.HLG, StillFormat.JPEG, motion = false, analysis = false) else null, PhotoSession(PictureRange.SDR, StillFormat.JPEG, motion = false, analysis = false))
        }
        for ((index, rung) in rungs.withIndex()) {
            if (!surface.isValid) return false
            val stillPlan = if (request.mode == Mode.PHOTO) Sizes.still(if (rung.still == StillFormat.JPEG_R) spec.jpegR else spec.jpeg, request.aspect) ?: continue else null
            val format = if (rung.still == StillFormat.JPEG_R) ImageFormat.JPEG_R else ImageFormat.JPEG
            val last = index == rungs.lastIndex
            if (!last && spec.sessionQueries && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && supported(spec.id, template, previewSize, rung, stillPlan, format) == false) continue
            var output: StillOutput? = null
            val made = try {
                output = stillPlan?.let { StillOutput(it, rung.still, format) }
                val preview = OutputConfiguration(surface).apply { if (rung.range == PictureRange.HLG) dynamicRangeProfile = DynamicRangeProfiles.HLG10 }
                val outputs = listOfNotNull(preview, output?.let { OutputConfiguration(it.reader.surface) })
                configure(device, template, outputs, output)
            } catch (e: Exception) {
                // An abandoned surface, a size the reader refuses: the next rung, or none
                Log.w(TAG, "Session rung failed: ${e.javaClass.simpleName}")
                null
            }
            if (made == null) {
                output?.close()
                continue
            }
            // The world may have moved while the session was being made: the device lost, another request or surface wanted
            if (this.device !== device || deviceLost || desired.value != want) {
                runCatching { made.close() }
                return false
            }
            session = made
            still = output
            previewSurface = surface
            controls = controls.copy(zoom = controls.zoom.coerceIn(spec.zoom.min, spec.zoom.max))
            _state.update { EngineState(CameraStatus.Ready, request, rung, frameShown = false) }
            repeat()
            return true
        }
        Log.w(TAG, "No session of the ladder could be configured")
        if (surface.isValid) failed(CameraDevice.StateCallback.ERROR_CAMERA_DEVICE)
        return false
    }

    /** Asks the camera without configuring it; null when it can't say (then the session is tried). */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun supported(id: String, template: Int, previewSize: PixelSize, rung: PhotoSession, still: StillPlan?, format: Int): Boolean? = runCatching {
        val setup = manager.getCameraDeviceSetup(id)
        val preview = OutputConfiguration(ImageFormat.PRIVATE, Size(previewSize.width, previewSize.height), HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_COMPOSER_OVERLAY)
        if (rung.range == PictureRange.HLG) preview.dynamicRangeProfile = DynamicRangeProfiles.HLG10
        val outputs = listOfNotNull(preview, still?.let { OutputConfiguration(format, Size(it.capture.width, it.capture.height)) })
        val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs)
        config.setSessionParameters(setup.createCaptureRequest(template).build())
        setup.isSessionConfigurationSupported(config)
    }.getOrNull()

    /** Configures a session with the same session parameters the query used; its still reader is closed when the session is (not before). */
    private suspend fun configure(device: CameraDevice, template: Int, outputs: List<OutputConfiguration>, output: StillOutput?): CameraCaptureSession? = suspendCancellableCoroutine { cont ->
        val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (cont.isActive) cont.resumeWith(Result.success(session)) else session.close()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                if (cont.isActive) cont.resumeWith(Result.success(null))
            }

            override fun onClosed(session: CameraCaptureSession) {
                output?.close()
            }
        })
        config.setSessionParameters(device.createCaptureRequest(template).build())
        device.createCaptureSession(config)
    }

    /** Sets the repeating request again with the current controls. */
    private fun repeat() {
        val session = session ?: return
        val device = device ?: return
        val surface = previewSurface ?: return
        val request = desired.value.request ?: return
        try {
            val builder = device.createCaptureRequest(if (request.mode == Mode.VIDEO) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            applyControls(builder, request)
            session.setRepeatingRequest(builder.build(), results, handler)
        } catch (e: Exception) {
            Log.w(TAG, "Repeating request failed: ${e.javaClass.simpleName}")
        }
    }

    /** A one-off request with an AF trigger (and the exposure's precapture metering for a lock), on top of the repeating one. */
    private fun trigger(af: Int, precapture: Boolean) {
        val session = session ?: return
        val device = device ?: return
        val surface = previewSurface ?: return
        val request = desired.value.request ?: return
        if (!request.camera.autofocus && !precapture) return
        try {
            val builder = device.createCaptureRequest(if (request.mode == Mode.VIDEO) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            applyControls(builder, request)
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
        override fun onCaptureCompleted(session: CameraCaptureSession, captureRequest: CaptureRequest, result: TotalCaptureResult) {
            val request = desired.value.request ?: return
            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
            aeSettled.value = aeState == null || aeState == CameraMetadata.CONTROL_AE_STATE_CONVERGED || aeState == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED || aeState == CameraMetadata.CONTROL_AE_STATE_LOCKED
            val focusing = afState == CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN || afState == CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN
            val face = steadyFace(focusedFace(result, request))
            _state.update { if (it.frameShown && it.focusing == focusing && it.face == face) it else it.copy(frameShown = true, focusing = focusing, face = face) }
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

    /** Closes the session; its still reader closes when the camera has really let go of it ([configure]'s `onClosed`). */
    private fun closeSession() {
        lockJob?.cancel()
        runCatching { session?.close() }
        session = null
        sessionKey = null
        still = null
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

    private companion object {
        const val TAG = "CameraEngine"
        const val JPEG_QUALITY: Byte = 95
        const val STILL_BUFFERS = 3
        const val STILL_TIMEOUT_MS = 10_000L
        const val RESTARTS = 2
        const val RESTART_DELAY_MS = 1_000L
        const val LOCK_MIN_WAIT_MS = 150L
        const val LOCK_MAX_WAIT_MS = 800L
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
