package sh.hnet.camera.diag.live

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraExtensionSession
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ExtensionSessionConfiguration
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.exifinterface.media.ExifInterface
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import sh.hnet.camera.capabilities.Names

/** A thread for camera and codec callbacks, so the tests never wait on the main thread. */
class CameraThread : AutoCloseable {
    private val thread = HandlerThread("camera-diag").apply { start() }
    val handler = Handler(thread.looper)
    val executor = Executor { handler.post(it) }
    override fun close() {
        thread.quitSafely()
    }
}

class CameraException(message: String) : Exception(message)

/** Opens the camera for the block and closes it (and its callback thread) afterwards, however the block ends. */
suspend fun <T> CameraManager.withCamera(id: String, block: suspend (CameraDevice, CameraThread) -> T): T {
    val thread = CameraThread()
    try {
        val device = open(id, thread)
        try {
            return block(device, thread)
        } finally {
            device.close()
        }
    } finally {
        thread.close()
    }
}

val noCallback = object : CameraCaptureSession.CaptureCallback() {}

fun CameraDevice.repeating(session: CameraCaptureSession, thread: CameraThread, targets: List<Surface>, template: Int = CameraDevice.TEMPLATE_PREVIEW, callback: CameraCaptureSession.CaptureCallback = noCallback, configure: CaptureRequest.Builder.() -> Unit = {}) {
    val request = createCaptureRequest(template).apply {
        targets.forEach { addTarget(it) }
        configure()
    }
    session.setSingleRepeatingRequest(request.build(), thread.executor, callback)
}

/** The last capture result of a repeating request: what the camera actually applied (stabilization, frame duration, exposure, the lens in use). */
class ResultWatch : CameraCaptureSession.CaptureCallback() {
    @Volatile
    var result: TotalCaptureResult? = null

    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
        this.result = result
    }

    fun describe(): JsonObject = buildJsonObject {
        val r = result ?: return@buildJsonObject
        r.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)?.let { put("stabilization", Names.stabilizationMode(it)) }
        r.get(CaptureResult.SENSOR_FRAME_DURATION)?.let { put("frameDurationMs", it / 1e6) }
        r.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { put("exposureMs", it / 1e6) }
        r.get(CaptureResult.SENSOR_SENSITIVITY)?.let { put("iso", it) }
        r.get(CaptureResult.CONTROL_ZOOM_RATIO)?.let { put("zoomRatio", it) }
        r.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)?.let { put("activePhysicalId", it) }
    }
}

fun exifOrientation(degrees: Int) = when (degrees) {
    90 -> ExifInterface.ORIENTATION_ROTATE_90
    180 -> ExifInterface.ORIENTATION_ROTATE_180
    270 -> ExifInterface.ORIENTATION_ROTATE_270
    else -> ExifInterface.ORIENTATION_NORMAL
}

/** Opens a camera; the permission is asked for by the activity before any test runs. */
@SuppressLint("MissingPermission")
suspend fun CameraManager.open(id: String, thread: CameraThread): CameraDevice = suspendCancellableCoroutine { cont ->
    openCamera(id, thread.executor, object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            cont.resume(device) { _, value, _ -> value.close() }
        }

        override fun onDisconnected(device: CameraDevice) {
            device.close()
            if (cont.isActive) cont.resumeWithException(CameraException("camera $id disconnected"))
        }

        override fun onError(device: CameraDevice, error: Int) {
            device.close()
            if (cont.isActive) cont.resumeWithException(CameraException("camera $id error $error"))
        }
    })
}

/** Creates a regular or high-speed session; a configuration the camera refuses ends the test with onConfigureFailed. */
suspend fun CameraDevice.session(outputs: List<OutputConfiguration>, thread: CameraThread, type: Int = SessionConfiguration.SESSION_REGULAR, parameters: CaptureRequest? = null): CameraCaptureSession =
    suspendCancellableCoroutine { cont ->
        val config = SessionConfiguration(type, outputs, thread.executor, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                cont.resume(session) { _, value, _ -> value.close() }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                if (cont.isActive) cont.resumeWithException(CameraException("session configuration failed"))
            }
        })
        parameters?.let { config.setSessionParameters(it) }
        createCaptureSession(config)
    }

suspend fun CameraDevice.extensionSession(extension: Int, outputs: List<OutputConfiguration>, thread: CameraThread): CameraExtensionSession =
    suspendCancellableCoroutine { cont ->
        val config = ExtensionSessionConfiguration(extension, outputs, thread.executor, object : CameraExtensionSession.StateCallback() {
            override fun onConfigured(session: CameraExtensionSession) {
                cont.resume(session) { _, value, _ -> value.close() }
            }

            override fun onConfigureFailed(session: CameraExtensionSession) {
                if (cont.isActive) cont.resumeWithException(CameraException("extension session configuration failed"))
            }
        })
        createExtensionSession(config)
    }

/** One capture, resumed with its result. */
suspend fun CameraCaptureSession.captureOnce(request: CaptureRequest, thread: CameraThread): TotalCaptureResult = suspendCancellableCoroutine { cont ->
    captureSingleRequest(request, thread.executor, object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (cont.isActive) cont.resume(result)
        }

        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            if (cont.isActive) cont.resumeWithException(CameraException("capture failed (reason ${failure.reason})"))
        }
    })
}

/** Images from a reader as they arrive; the receiver closes each image it takes. */
class ImageQueue(val reader: ImageReader, thread: CameraThread) : AutoCloseable {
    private val channel = Channel<Image>(Channel.UNLIMITED)

    init {
        // An image beyond maxImages (a test that didn't take the previous one) is dropped instead of crashing the callback thread
        reader.setOnImageAvailableListener({ r -> runCatching { r.acquireNextImage() }.getOrNull()?.let { channel.trySend(it) } }, thread.handler)
    }

    suspend fun next(timeoutMs: Long = 10_000): Image = withTimeout(timeoutMs) { channel.receive() }

    fun drain(): List<Image> = generateSequence { channel.tryReceive().getOrNull() }.toList()

    override fun close() {
        drain().forEach { it.close() }
        channel.close()
        reader.close()
    }
}

fun imageReader(width: Int, height: Int, format: Int, maxImages: Int): ImageReader =
    ImageReader.Builder(width, height).setImageFormat(format).setMaxImages(maxImages).build()

/** The bytes of a compressed image (JPEG, JPEG_R, HEIC) from its single plane. */
fun Image.compressedBytes(): ByteArray {
    val buffer = planes[0].buffer
    return ByteArray(buffer.remaining()).also { buffer.get(it) }
}
