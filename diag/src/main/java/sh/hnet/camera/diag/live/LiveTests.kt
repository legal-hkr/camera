package sh.hnet.camera.diag.live

import kotlinx.coroutines.coroutineScope
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
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
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.os.Build
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.annotation.StringRes
import androidx.core.graphics.get
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import sh.hnet.camera.capabilities.CameraCatalog
import sh.hnet.camera.capabilities.Names
import sh.hnet.camera.capabilities.area
import sh.hnet.camera.capabilities.jsonOf
import sh.hnet.camera.capabilities.lookOf
import sh.hnet.camera.capabilities.model.WhiteBalance
import sh.hnet.camera.capabilities.probe
import sh.hnet.camera.capabilities.sizeText
import sh.hnet.camera.diag.R

/** Where the tests show what the camera sees; the surface gets the buffer size a test needs (high-speed sessions need the preview at the video size). */
fun interface PreviewHost {
    suspend fun surface(size: Size): Surface
}

/** A test a person runs with the phone pointed at a scene; it returns what really happened, measured, next to what the camera declared. */
class LiveTest(val key: String, @StringRes val title: Int, val inRunAll: Boolean = true, val run: suspend (cameraId: String) -> JsonObject)

/**
 * Captures that check the declarations against reality: Ultra HDR and 10-bit stills, burst rates, HLG and high-speed video, still capture during video (motion photo),
 * every Camera2 extension, RAW at both sensor modes. Files go to DCIM/CameraDiag so the gallery can be tested with them.
 */
class LiveTests(context: Context, private val preview: PreviewHost) {

    private val manager = context.getSystemService(CameraManager::class.java)
    private val resolver = context.contentResolver

    val all = listOf(
        LiveTest("ultra_hdr", R.string.title_test_ultra_hdr) { ultraHdr(it) },
        LiveTest("p010", R.string.title_test_p010) { p010(it) },
        LiveTest("burst", R.string.title_test_burst) { burst(it) },
        LiveTest("video_hlg_4k30", R.string.title_test_video_hlg_4k30) { video(it, VideoSpec(3840, 2160, 30, true, 50_000_000)) },
        LiveTest("video_hlg_4k60", R.string.title_test_video_hlg_4k60) { video(it, VideoSpec(3840, 2160, 60, true, 80_000_000)) },
        LiveTest("video_hlg_1080p60", R.string.title_test_video_hlg_1080p60) { video(it, VideoSpec(1920, 1080, 60, true, 30_000_000)) },
        LiveTest("slow_motion", R.string.title_test_slow_motion) { slowMotion(it) },
        LiveTest("motion_photo", R.string.title_test_motion_photo) { motionPhoto(it) },
        LiveTest("extensions", R.string.title_test_extensions) { extensions(it) },
        LiveTest("night_queue", R.string.title_test_night_queue) { nightQueue(it) },
        LiveTest("burst_long", R.string.title_test_burst_long) { burstLong(it) },
        LiveTest("burst_hdr_preview", R.string.title_test_burst_hdr_preview) { burstHdrPreview(it) },
        LiveTest("pro_long_exposure", R.string.title_test_pro_long_exposure) { proLongExposure(it) },
        LiveTest("pro_raw_with_jpeg", R.string.title_test_pro_raw_with_jpeg) { proRawWithJpeg(it) },
        LiveTest("pro_50mp_dng", R.string.title_test_pro_50mp_dng) { pro50mpDng(it) },
        LiveTest("pro_white_balance", R.string.title_test_pro_white_balance) { proWhiteBalance(it) },
        LiveTest("pro_long_exposure_app", R.string.title_test_pro_long_exposure_app) { proLongExposureApp(it) },
        LiveTest("pro_manual_sdr", R.string.title_test_pro_manual_sdr) { proManualSdr(it) },
        LiveTest("pro_raw_lenses", R.string.title_test_pro_raw_lenses) { proRawLenses(it) },
        LiveTest("lens_by_zoom", R.string.title_test_lens_by_zoom) { lensByZoom(it) },
        LiveTest("pro_50mp_app", R.string.title_test_pro_50mp_app) { pro50mpApp(it) },
        LiveTest("raw", R.string.title_test_raw) { raw(it) },
    )

    private fun chars(id: String) = manager.getCameraCharacteristics(id)
    private fun map(id: String): StreamConfigurationMap = chars(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
    private fun largest(map: StreamConfigurationMap?, format: Int): Size? = map?.getOutputSizes(format)?.maxByOrNull { area(it) }
    private fun orientation(id: String) = chars(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
    private fun previewSize(map: StreamConfigurationMap) = Size(1920, 1080).takeIf { map.getOutputSizes(SurfaceTexture::class.java).contains(it) } ?: Size(1280, 720)

    private fun CaptureRequest.Builder.still(id: String) {
        set(CaptureRequest.JPEG_ORIENTATION, orientation(id))
        set(CaptureRequest.JPEG_QUALITY, 95.toByte())
    }

    private fun notOffered(what: String) = buildJsonObject { put("skipped", "$what not offered by this camera") }

    // Ultra HDR: the framework composes JPEG_R from a P010 and a JPEG stream; the file must decode with a gain map
    private suspend fun ultraHdr(id: String): JsonObject {
        val map = map(id)
        val size = largest(map, ImageFormat.JPEG_R) ?: return notOffered("JPEG_R")
        val surface = preview.surface(previewSize(map))
        return manager.withCamera(id) { device, thread ->
            ImageQueue(imageReader(size.width, size.height, ImageFormat.JPEG_R, 2), thread).use { queue ->
                val session = device.session(listOf(OutputConfiguration(surface), OutputConfiguration(queue.reader.surface)), thread)
                try {
                    device.repeating(session, thread, listOf(surface))
                    delay(1500)
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(surface)
                        addTarget(queue.reader.surface)
                        still(id)
                    }
                    val start = SystemClock.elapsedRealtime()
                    session.captureOnce(request.build(), thread)
                    val image = queue.next()
                    val latency = SystemClock.elapsedRealtime() - start
                    val bytes = image.use { it.compressedBytes() }
                    val uri = Media.saveImage(resolver, "UHDR_${Media.stamp()}_cam$id.jpg", "image/jpeg", bytes)
                    buildJsonObject {
                        put("size", sizeText(size))
                        put("bytes", bytes.size)
                        put("latencyMs", latency)
                        put("file", uri.toString())
                        put("decoded", probe { decodeInfo(bytes) })
                    }
                } finally {
                    session.close()
                }
            }
        }
    }

    /** Decodes a photo the way the gallery will and reports its gain map; markers show whether the Ultra HDR XMP and the MPF index are in the file. */
    private fun decodeInfo(bytes: ByteArray): JsonObject = buildJsonObject {
        val text = String(bytes, Charsets.ISO_8859_1)
        put("xmpHdrgm", text.contains("hdrgm:Version"))
        put("mpf", text.contains("MPF\u0000"))
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(bytes)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(4)
        }
        put("colorSpace", bitmap.colorSpace?.name)
        put("hasGainmap", bitmap.hasGainmap())
        bitmap.gainmap?.let { g ->
            put("gainmapSize", "${g.gainmapContents.width}x${g.gainmapContents.height} (image ${bitmap.width}x${bitmap.height}, sampled 1/4)")
            put("ratioMin", jsonOf(g.ratioMin))
            put("ratioMax", jsonOf(g.ratioMax))
            put("gamma", jsonOf(g.gamma))
            put("epsilonSdr", jsonOf(g.epsilonSdr))
            put("epsilonHdr", jsonOf(g.epsilonHdr))
            put("minDisplayRatioForHdrTransition", g.minDisplayRatioForHdrTransition)
            put("displayRatioForFullHdr", g.displayRatioForFullHdr)
        }
        bitmap.recycle()
    }

    // A 10-bit still the app could tone-map into its own Ultra HDR (e.g. at 50 MP, where JPEG_R is not offered)
    private suspend fun p010(id: String): JsonObject {
        val map = map(id)
        val size = largest(map, ImageFormat.YCBCR_P010) ?: return notOffered("YCBCR_P010")
        val surface = preview.surface(previewSize(map))
        val hlg = DynamicRangeProfiles.HLG10
        return manager.withCamera(id) { device, thread ->
            ImageQueue(imageReader(size.width, size.height, ImageFormat.YCBCR_P010, 2), thread).use { queue ->
                val outputs = listOf(OutputConfiguration(surface), OutputConfiguration(queue.reader.surface)).onEach { it.dynamicRangeProfile = hlg }
                val session = device.session(outputs, thread)
                try {
                    device.repeating(session, thread, listOf(surface))
                    delay(1500)
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(surface)
                        addTarget(queue.reader.surface)
                    }
                    val start = SystemClock.elapsedRealtime()
                    session.captureOnce(request.build(), thread)
                    queue.next().use { image ->
                        buildJsonObject {
                            put("size", "${image.width}x${image.height}")
                            put("latencyMs", SystemClock.elapsedRealtime() - start)
                            put("planes", buildJsonArray { image.planes.forEach { add(jsonOf("pixelStride ${it.pixelStride}, rowStride ${it.rowStride}, bytes ${it.buffer.remaining()}")) } })
                            put("dataSpace", image.dataSpace)
                        }
                    }
                } finally {
                    session.close()
                }
            }
        }
    }

    // Burst: a repeating still request into a full-size reader for two seconds per format; the frame times say what a burst mode can really do
    private suspend fun burst(id: String): JsonObject {
        val map = map(id)
        val surface = preview.surface(previewSize(map))
        val fps = chars(id).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.filter { it.lower == it.upper }?.maxByOrNull { it.upper }
        return manager.withCamera(id) { device, thread ->
            buildJsonObject {
                fps?.let { put("aeTargetFps", "${it.lower}-${it.upper}") }
                for (format in listOf(ImageFormat.YUV_420_888, ImageFormat.JPEG, ImageFormat.RAW_SENSOR)) {
                    val size = largest(map, format) ?: continue
                    put(Names.format(format), probe { burstOf(id, device, thread, surface, format, size, fps) })
                }
            }
        }
    }

    private suspend fun burstOf(id: String, device: CameraDevice, thread: CameraThread, surface: Surface, format: Int, size: Size, fps: Range<Int>?): JsonObject {
        val reader = imageReader(size.width, size.height, format, 4)
        val timestamps = mutableListOf<Long>()
        reader.setOnImageAvailableListener({ r -> r.acquireNextImage()?.use { timestamps += it.timestamp } }, thread.handler)
        try {
            val session = device.session(listOf(OutputConfiguration(surface), OutputConfiguration(reader.surface)), thread)
            try {
                device.repeating(session, thread, listOf(surface))
                delay(1000)
                device.repeating(session, thread, listOf(surface, reader.surface), CameraDevice.TEMPLATE_STILL_CAPTURE) {
                    still(id)
                    fps?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                }
                delay(2000)
                session.stopRepeating()
                delay(300)
            } finally {
                session.close()
            }
        } finally {
            reader.close()
        }
        val t = timestamps.sorted()
        return buildJsonObject {
            put("size", sizeText(size))
            put("frames", t.size)
            if (t.size > 1) {
                put("fps", (t.size - 1) * 1e9 / (t.last() - t.first()))
                put("maxGapMs", t.zipWithNext { a, b -> b - a }.max() / 1e6)
            }
        }
    }

    // HLG (or SDR) video into an MP4 in DCIM/CameraDiag; stabilization on where the camera offers it, as a camera app would record
    private suspend fun video(id: String, spec: VideoSpec): JsonObject {
        val map = map(id)
        if (map.getOutputSizes(SurfaceTexture::class.java).none { it.width == spec.width && it.height == spec.height }) return notOffered("${spec.width}x${spec.height}")
        val profiles = chars(id).get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles.orEmpty()
        if (spec.hlg && DynamicRangeProfiles.HLG10 !in profiles) return notOffered("HLG10")
        val stabilization = chars(id).get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.let {
            if (CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON in it) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON else null
        }
        val surface = preview.surface(previewSize(map))
        val uri = Media.newVideo(resolver, "${if (spec.hlg) "HLG" else "SDR"}_${spec.width}x${spec.height}_${spec.fps}_${Media.stamp()}_cam$id.mp4")
        return manager.withCamera(id) { device, thread ->
            val encoder = SurfaceEncoder(spec, thread, resolver.openFileDescriptor(uri, "rw"))
            val last = ResultWatch()
            try {
                val profile = if (spec.hlg) DynamicRangeProfiles.HLG10 else DynamicRangeProfiles.STANDARD
                val outputs = listOf(OutputConfiguration(surface), OutputConfiguration(encoder.surface)).onEach { it.dynamicRangeProfile = profile }
                val parameters = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(spec.fps, spec.fps))
                    stabilization?.let { set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, it) }
                }.build()
                val session = device.session(outputs, thread, parameters = parameters)
                try {
                    device.repeating(session, thread, listOf(surface, encoder.surface), CameraDevice.TEMPLATE_RECORD, last) {
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(spec.fps, spec.fps))
                        stabilization?.let { set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, it) }
                    }
                    delay(3000)
                    session.stopRepeating()
                } finally {
                    session.close()
                }
                encoder.finish()
                Media.publish(resolver, uri)
                buildJsonObject {
                    put("file", uri.toString())
                    put("encoder", encoder.timing())
                    put("encoderOutputFormat", encoder.outputFormat?.toString())
                    put("lastResult", last.describe())
                    put("container", probe { Media.describeVideo(resolver, uri) })
                }
            } catch (e: Exception) {
                encoder.abort()
                resolver.delete(uri, null, null)
                throw e
            }
        }
    }

    // Slow motion: a constrained high-speed session at the highest fixed rate the camera lists for 1080p (else 720p)
    private suspend fun slowMotion(id: String): JsonObject {
        val map = map(id)
        val size = map.highSpeedVideoSizes.filter { it.width == 1920 && it.height == 1080 }.firstOrNull() ?: map.highSpeedVideoSizes.maxByOrNull { area(it) } ?: return notOffered("high-speed video")
        val range = map.getHighSpeedVideoFpsRangesFor(size).filter { it.lower == it.upper }.maxByOrNull { it.upper } ?: return notOffered("a fixed high-speed rate")
        val surface = preview.surface(size)
        val uri = Media.newVideo(resolver, "SLOWMO_${size.width}x${size.height}_${range.upper}_${Media.stamp()}_cam$id.mp4")
        return manager.withCamera(id) { device, thread ->
            val encoder = SurfaceEncoder(VideoSpec(size.width, size.height, range.upper, false, 60_000_000), thread, resolver.openFileDescriptor(uri, "rw"))
            try {
                val session = device.session(listOf(OutputConfiguration(surface), OutputConfiguration(encoder.surface)), thread, SessionConfiguration.SESSION_HIGH_SPEED) as CameraConstrainedHighSpeedCaptureSession
                try {
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(surface)
                        addTarget(encoder.surface)
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                    }.build()
                    session.setRepeatingBurstRequests(session.createHighSpeedRequestList(request), thread.executor, noCallback)
                    delay(2000)
                    session.stopRepeating()
                } finally {
                    session.close()
                }
                encoder.finish()
                Media.publish(resolver, uri)
                buildJsonObject {
                    put("size", sizeText(size))
                    put("fpsRange", "${range.lower}-${range.upper}")
                    put("file", uri.toString())
                    put("encoder", encoder.timing())
                    put("container", probe { Media.describeVideo(resolver, uri) })
                }
            } catch (e: Exception) {
                encoder.abort()
                resolver.delete(uri, null, null)
                throw e
            }
        }
    }

    // Motion photo: 1080p video runs while full-size stills are taken; a still that stalls the video shows as a gap in the encoder's frame times
    private suspend fun motionPhoto(id: String): JsonObject {
        val map = map(id)
        val surface = preview.surface(previewSize(map))
        return manager.withCamera(id) { device, thread ->
            buildJsonObject {
                for (format in listOf(ImageFormat.JPEG, ImageFormat.JPEG_R)) {
                    val size = largest(map, format) ?: continue
                    put(Names.format(format), probe { stillDuringVideo(id, device, thread, surface, format, size) })
                }
            }
        }
    }

    private suspend fun stillDuringVideo(id: String, device: CameraDevice, thread: CameraThread, surface: Surface, format: Int, size: Size): JsonObject {
        val encoder = SurfaceEncoder(VideoSpec(1920, 1080, 30, false, 20_000_000), thread, null)
        try {
            ImageQueue(imageReader(size.width, size.height, format, 2), thread).use { queue ->
                val session = device.session(listOf(OutputConfiguration(surface), OutputConfiguration(encoder.surface), OutputConfiguration(queue.reader.surface)), thread)
                val stills = mutableListOf<Pair<String, JsonObject>>()
                val stillTimes = mutableListOf<Long>()
                try {
                    device.repeating(session, thread, listOf(surface, encoder.surface), CameraDevice.TEMPLATE_RECORD) {
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                    }
                    delay(1500)
                    for ((name, template) in listOf("videoSnapshot" to CameraDevice.TEMPLATE_VIDEO_SNAPSHOT, "stillCapture" to CameraDevice.TEMPLATE_STILL_CAPTURE)) {
                        val request = device.createCaptureRequest(template).apply {
                            addTarget(surface)
                            addTarget(encoder.surface)
                            addTarget(queue.reader.surface)
                            still(id)
                        }
                        val start = SystemClock.elapsedRealtime()
                        val result = session.captureOnce(request.build(), thread)
                        queue.next().close()
                        result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { stillTimes += it }
                        stills += name to buildJsonObject { put("latencyMs", SystemClock.elapsedRealtime() - start) }
                        delay(1000)
                    }
                    session.stopRepeating()
                } finally {
                    session.close()
                }
                encoder.finish()
                val frames = encoder.timestampsUs.sorted()
                return buildJsonObject {
                    put("size", sizeText(size))
                    put("video", encoder.timing())
                    stills.forEachIndexed { i, (name, info) ->
                        val at = stillTimes.getOrNull(i)?.div(1000)
                        put(name, buildJsonObject {
                            info.forEach { (k, v) -> put(k, v) }
                            // The largest gap between video frames within half a second of the still (same clock: sensor timestamps)
                            if (at != null) {
                                val near = frames.filter { it in (at - 500_000)..(at + 500_000) }
                                if (near.size > 1) put("maxVideoGapNearStillMs", near.zipWithNext { a, b -> b - a }.max() / 1000.0)
                            }
                        })
                    }
                }
            }
        } catch (e: Exception) {
            encoder.abort()
            throw e
        }
    }

    // Every Camera2 extension the camera lists: preview, one capture (JPEG_R where the extension offers it), its latency, progress and night-mode indicator
    private suspend fun extensions(id: String): JsonObject {
        val ext = manager.getCameraExtensionCharacteristics(id)
        if (ext.supportedExtensions.isEmpty()) return notOffered("Camera2 extensions")
        return manager.withCamera(id) { device, thread ->
            buildJsonObject {
                for (extension in ext.supportedExtensions) {
                    put(Names.extension(extension), probe {
                        val previewSizes = ext.getExtensionSupportedSizes(extension, SurfaceTexture::class.java)
                        val previewSize = previewSizes.firstOrNull { it.width == 1920 && it.height == 1080 } ?: previewSizes.filter { it.width <= 1920 }.maxByOrNull { area(it) } ?: previewSizes.first()
                        val jpegR = ext.getExtensionSupportedSizes(extension, ImageFormat.JPEG_R)
                        val format = if (jpegR.isNotEmpty()) ImageFormat.JPEG_R else ImageFormat.JPEG
                        val size = ext.getExtensionSupportedSizes(extension, format).maxBy { area(it) }
                        extensionCapture(id, device, thread, extension, preview.surface(previewSize), format, size)
                    })
                }
            }
        }
    }

    private suspend fun extensionCapture(id: String, device: CameraDevice, thread: CameraThread, extension: Int, surface: Surface, format: Int, size: Size): JsonObject {
        ImageQueue(imageReader(size.width, size.height, format, 2), thread).use { queue ->
            val session = device.extensionSession(extension, listOf(OutputConfiguration(surface), OutputConfiguration(queue.reader.surface)), thread)
            try {
                var nightIndicator: Int? = null
                val previewRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }.build()
                session.setRepeatingRequest(previewRequest, thread.executor, object : CameraExtensionSession.ExtensionCaptureCallback() {
                    override fun onCaptureResultAvailable(session: CameraExtensionSession, request: CaptureRequest, result: TotalCaptureResult) {
                        // The indicator key is Android 16's
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) result.get(CaptureResult.EXTENSION_NIGHT_MODE_INDICATOR)?.let { nightIndicator = it }
                    }
                })
                delay(2000)
                val progress = mutableListOf<Int>()
                val failed = CompletableDeferred<Unit>()
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(queue.reader.surface)
                    still(id)
                }.build()
                val start = SystemClock.elapsedRealtime()
                session.capture(request, thread.executor, object : CameraExtensionSession.ExtensionCaptureCallback() {
                    override fun onCaptureProcessProgressed(session: CameraExtensionSession, request: CaptureRequest, value: Int) {
                        progress += value
                    }

                    override fun onCaptureFailed(session: CameraExtensionSession, request: CaptureRequest) {
                        failed.complete(Unit)
                    }
                })
                val image: Image = queue.next(30_000)
                val latency = SystemClock.elapsedRealtime() - start
                val bytes = image.use { it.compressedBytes() }
                val uri = Media.saveImage(resolver, "EXT_${Names.extension(extension)}_${Media.stamp()}_cam$id.jpg", "image/jpeg", bytes)
                return buildJsonObject {
                    put("format", Names.format(format))
                    put("size", sizeText(size))
                    put("latencyMs", latency)
                    put("captureFailed", failed.isCompleted)
                    put("progress", jsonOf(progress))
                    put("nightModeIndicator", jsonOf(nightIndicator))
                    put("file", uri.toString())
                    put("decoded", probe { decodeInfo(bytes) })
                }
            } finally {
                session.close()
            }
        }
    }

    /** Whether the Night extension's session takes an HLG preview, and whether a second Night capture can be asked for while the first is still processing (queued, refused or failed). */
    private suspend fun nightQueue(id: String): JsonObject {
        val ext = manager.getCameraExtensionCharacteristics(id)
        if (CameraExtensionCharacteristics.EXTENSION_NIGHT !in ext.supportedExtensions) return notOffered("the Night extension")
        val night = CameraExtensionCharacteristics.EXTENSION_NIGHT
        val previewSizes = ext.getExtensionSupportedSizes(night, SurfaceTexture::class.java)
        val previewSize = previewSizes.firstOrNull { it.width == 1920 && it.height == 1440 } ?: previewSizes.filter { it.width <= 1920 }.maxByOrNull { area(it) } ?: previewSizes.first()
        val jpegR = ext.getExtensionSupportedSizes(night, ImageFormat.JPEG_R)
        val format = if (jpegR.isNotEmpty()) ImageFormat.JPEG_R else ImageFormat.JPEG
        val size = ext.getExtensionSupportedSizes(night, format).maxByOrNull { area(it) } ?: return notOffered("Night sizes")
        val surface = preview.surface(previewSize)
        return manager.withCamera(id) { device, thread ->
            buildJsonObject {
                put("previewSize", sizeText(previewSize))
                put("hlgPreview", probe {
                    ImageQueue(imageReader(size.width, size.height, format, 2), thread).use { queue ->
                        val hlg = OutputConfiguration(surface).apply { dynamicRangeProfile = DynamicRangeProfiles.HLG10 }
                        val session = device.extensionSession(night, listOf(hlg, OutputConfiguration(queue.reader.surface)), thread)
                        try {
                            session.setRepeatingRequest(device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }.build(), thread.executor, object : CameraExtensionSession.ExtensionCaptureCallback() {})
                            delay(1500)
                            buildJsonObject { put("configured", true) }
                        } finally {
                            session.close()
                        }
                    }
                })
                delay(500)
                put("secondCapture", probe {
                    ImageQueue(imageReader(size.width, size.height, format, 2), thread).use { queue ->
                        val session = device.extensionSession(night, listOf(OutputConfiguration(surface), OutputConfiguration(queue.reader.surface)), thread)
                        try {
                            session.setRepeatingRequest(device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }.build(), thread.executor, object : CameraExtensionSession.ExtensionCaptureCallback() {})
                            delay(2000)
                            val start = SystemClock.elapsedRealtime()
                            val events = mutableListOf<String>()
                            fun ask(n: Int): String = try {
                                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                    addTarget(queue.reader.surface)
                                    still(id)
                                }.build()
                                session.capture(request, thread.executor, object : CameraExtensionSession.ExtensionCaptureCallback() {
                                    override fun onCaptureStarted(session: CameraExtensionSession, request: CaptureRequest, timestamp: Long) {
                                        events += "started $n at ${SystemClock.elapsedRealtime() - start} ms"
                                    }

                                    override fun onCaptureProcessStarted(session: CameraExtensionSession, request: CaptureRequest) {
                                        events += "processing $n at ${SystemClock.elapsedRealtime() - start} ms"
                                    }

                                    override fun onCaptureFailed(session: CameraExtensionSession, request: CaptureRequest) {
                                        events += "failed $n at ${SystemClock.elapsedRealtime() - start} ms"
                                    }

                                    override fun onCaptureSequenceCompleted(session: CameraExtensionSession, sequenceId: Int) {
                                        events += "completed $n at ${SystemClock.elapsedRealtime() - start} ms"
                                    }
                                })
                                "asked"
                            } catch (e: Exception) {
                                "refused: ${e.javaClass.simpleName}: ${e.message}"
                            }
                            val first = ask(1)
                            delay(1000)
                            val second = ask(2)
                            val arrived = mutableListOf<Long>()
                            repeat(2) {
                                val image = runCatching { queue.next(20_000) }.getOrNull() ?: return@repeat
                                image.close()
                                arrived += SystemClock.elapsedRealtime() - start
                            }
                            buildJsonObject {
                                put("first", first)
                                put("second", second)
                                put("imagesArrivedMs", jsonOf(arrived))
                                put("events", jsonOf(events.toList()))
                            }
                        } finally {
                            session.close()
                        }
                    }
                })
            }
        }
    }

    /** A 5-second burst – about 150 frames – in JPEG and in JPEG_R at full size: the real rate, the longest gap and the frames' size. */
    private suspend fun burstLong(id: String): JsonObject {
        val map = map(id)
        val surface = preview.surface(previewSize(map))
        val fps = chars(id).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.filter { it.lower == it.upper }?.maxByOrNull { it.upper }
        return manager.withCamera(id) { device, thread ->
            buildJsonObject {
                for (format in listOf(ImageFormat.JPEG, ImageFormat.JPEG_R)) {
                    val size = largest(map, format) ?: continue
                    put(Names.format(format), probe { burstLongOf(id, device, thread, surface, format, size, fps) })
                }
            }
        }
    }

    private suspend fun burstLongOf(id: String, device: CameraDevice, thread: CameraThread, surface: Surface, format: Int, size: Size, fps: Range<Int>?): JsonObject {
        val reader = imageReader(size.width, size.height, format, 6)
        val timestamps = mutableListOf<Long>()
        val sizes = mutableListOf<Int>()
        reader.setOnImageAvailableListener({ r ->
            r.acquireNextImage()?.use { image ->
                timestamps += image.timestamp
                sizes += image.planes[0].buffer.remaining()
            }
        }, thread.handler)
        try {
            val session = device.session(listOf(OutputConfiguration(surface), OutputConfiguration(reader.surface)), thread)
            try {
                device.repeating(session, thread, listOf(surface))
                delay(1000)
                device.repeating(session, thread, listOf(surface, reader.surface), CameraDevice.TEMPLATE_STILL_CAPTURE) {
                    still(id)
                    fps?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                }
                delay(5000)
                session.stopRepeating()
                delay(500)
            } finally {
                session.close()
            }
        } finally {
            reader.close()
        }
        val t = timestamps.sorted()
        return buildJsonObject {
            put("size", sizeText(size))
            put("frames", t.size)
            if (t.size > 1) {
                put("fps", (t.size - 1) * 1e9 / (t.last() - t.first()))
                put("maxGapMs", t.zipWithNext { a, b -> b - a }.max() / 1e6)
            }
            if (sizes.isNotEmpty()) put("averageKB", sizes.average() / 1024)
        }
    }

    /**
     * A 5-second burst next to an HLG preview, as the camera app's Photo has it with Ultra HDR on: can one request hold the preview and the still, and if not,
     * what rates do the preview and the stills get when their requests alternate ([preview, still] and [preview, still, still])? At 30 fps: HLG sessions at
     * 60 fps are refused. A JPEG reader beside JPEG_R and the HLG preview was measured once and broke the Pixel's camera service: never configured again.
     */
    private suspend fun burstHdrPreview(id: String): JsonObject {
        val map = map(id)
        val previewSize = previewSize(map)
        val jpegR = largest(map, ImageFormat.JPEG_R) ?: return notOffered("JPEG_R")
        val fps = Range(30, 30)
        return manager.withCamera(id) { device, thread ->
            buildJsonObject {
                put("JPEG_R", probe { burstNextToHlg(device, thread, previewSize, listOf(ImageFormat.JPEG_R to jpegR), fps) })
            }
        }
    }

    /** One session: an HLG preview and the [stills] readers (the last one bursts); the three ways of bursting, 5 s each, with the preview's and the stills' rates. */
    private suspend fun burstNextToHlg(device: CameraDevice, thread: CameraThread, previewSize: Size, stills: List<Pair<Int, Size>>, fps: Range<Int>): JsonObject {
        val surface = preview.surface(previewSize)
        val readers = stills.map { (format, size) -> imageReader(size.width, size.height, format, 6) }
        val stillTimes = mutableListOf<Long>()
        readers.last().setOnImageAvailableListener({ r -> r.acquireNextImage()?.use { stillTimes += it.timestamp } }, thread.handler)
        readers.dropLast(1).forEach { reader -> reader.setOnImageAvailableListener({ r -> r.acquireNextImage()?.close() }, thread.handler) }
        val previewTimes = mutableListOf<Long>()
        val failures = mutableListOf<String>()
        val watch = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                if (request.tag == PREVIEW_TAG) result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { previewTimes += it }
            }

            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                if (failures.size < 5) failures += "${request.tag} reason ${failure.reason}"
            }
        }
        fun rates(times: List<Long>) = buildJsonObject {
            val t = times.sorted()
            put("frames", t.size)
            if (t.size > 1) {
                put("fps", (t.size - 1) * 1e9 / (t.last() - t.first()))
                put("maxGapMs", t.zipWithNext { a, b -> b - a }.max() / 1e6)
            }
        }
        try {
            val outputs = listOf(OutputConfiguration(surface).apply { dynamicRangeProfile = DynamicRangeProfiles.HLG10 }) + readers.map { OutputConfiguration(it.surface) }
            val session = device.session(outputs, thread)
            try {
                fun request(tag: String, template: Int, vararg targets: Surface) = device.createCaptureRequest(template).apply {
                    targets.forEach { addTarget(it) }
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
                    setTag(tag)
                }.build()
                val previewRequest = request(PREVIEW_TAG, CameraDevice.TEMPLATE_PREVIEW, surface)
                val stillRequest = request("still", CameraDevice.TEMPLATE_STILL_CAPTURE, readers.last().surface)
                val ways = listOf(
                    "alternating [preview, still]" to { listOf(previewRequest, stillRequest) },
                    "alternating [preview, still, still]" to { listOf(previewRequest, stillRequest, stillRequest) },
                    // Last: if the camera refuses it badly, the other two are measured already
                    "one request (preview + still)" to { listOf(request(PREVIEW_TAG, CameraDevice.TEMPLATE_STILL_CAPTURE, surface, readers.last().surface)) },
                )
                return buildJsonObject {
                    put("session", "configured")
                    for ((name, requests) in ways) {
                        put(name, probe {
                            session.setSingleRepeatingRequest(previewRequest, thread.executor, noCallback)
                            delay(1000)
                            stillTimes.clear()
                            previewTimes.clear()
                            failures.clear()
                            session.setRepeatingBurstRequests(requests(), thread.executor, watch)
                            delay(5000)
                            session.stopRepeating()
                            delay(500)
                            buildJsonObject {
                                put("stills", rates(stillTimes.toList()))
                                put("preview", rates(previewTimes.toList()))
                                if (failures.isNotEmpty()) put("failures", buildJsonArray { failures.toList().forEach { add(jsonOf(it)) } })
                            }
                        })
                    }
                }
            } finally {
                session.close()
            }
        } finally {
            readers.forEach { it.close() }
        }
    }

    /** The camera's own answer whether a session of [outputs] can run (Android 15+; nothing is configured). Null when it can't be asked. */
    private fun supported(id: String, outputs: List<OutputConfiguration>): Boolean? = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM || !manager.isCameraDeviceSetupSupported(id)) return null
        manager.getCameraDeviceSetup(id).isSessionConfigurationSupported(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs))
    }.getOrNull()

    /**
     * White balance in kelvins against the camera's own (Pro): with an HLG preview and with an SDR one, the camera's automatic balance (its gains, its color
     * transform, the lens), then the same temperature set by hand as the app sets it (our gains and transform), then our gains with the camera's own
     * transform. A still of each (saved to DCIM/CameraDiag) and the mean color of its middle: where a hand-set balance comes out warmer, which part differs.
     */
    private suspend fun proWhiteBalance(id: String): JsonObject {
        val chars = chars(id)
        val map = map(id)
        val spec = CameraCatalog.read(manager).firstOrNull { it.id == id } ?: return notOffered("the camera in the catalog")
        val surface = preview.surface(previewSize(map))
        val hlgOffered = largest(map, ImageFormat.JPEG_R) != null && chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles?.contains(DynamicRangeProfiles.HLG10) == true
        return buildJsonObject {
            for (hlg in listOf(true, false)) {
                if (hlg && !hlgOffered) continue
                // As the app pairs them: the HLG preview with Ultra HDR, the SDR one with JPEG
                val format = if (hlg) ImageFormat.JPEG_R else ImageFormat.JPEG
                val size = largest(map, format) ?: continue
                put(if (hlg) "hlg" else "sdr", probe {
                    manager.withCamera(id) { device, thread ->
                        ImageQueue(imageReader(size.width, size.height, format, 2), thread).use { queue ->
                            val outputs = listOf(OutputConfiguration(surface).apply { if (hlg) dynamicRangeProfile = DynamicRangeProfiles.HLG10 }, OutputConfiguration(queue.reader.surface))
                            if (supported(id, outputs) == false) return@withCamera buildJsonObject { put("skipped", "session not supported") }
                            val session = device.session(outputs, thread)
                            try {
                                val watch = ResultWatch()
                                device.repeating(session, thread, listOf(surface), callback = watch)
                                delay(2000)
                                val auto = watch.result ?: error("no preview result")
                                val gains = auto.get(CaptureResult.COLOR_CORRECTION_GAINS) ?: error("no gains")
                                val cameraTransform = auto.get(CaptureResult.COLOR_CORRECTION_TRANSFORM) ?: error("no transform")
                                val lens = auto.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
                                val calibration = spec.lookOf(lens).colorCalibration ?: error("no color calibration for lens $lens")
                                val red = gains.red.toDouble() / gains.greenEven
                                val blue = gains.blue.toDouble() / gains.greenEven
                                val kelvin = WhiteBalance.TemperatureReader(calibration).read(red, blue) ?: error("no temperature")
                                val ours = WhiteBalance.of(kelvin.toDouble(), calibration) ?: error("no balance at $kelvin K")
                                val ourTransform = ColorSpaceTransform(IntArray(18) { i -> if (i % 2 == 0) (ours.transform.values[i / 2] * 10_000).roundToInt() else 10_000 })
                                val ourGains = RggbChannelVector(ours.red.toFloat(), 1f, 1f, ours.blue.toFloat())
                                fun CaptureRequest.Builder.manual(transform: ColorSpaceTransform) {
                                    set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                                    set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                                    set(CaptureRequest.COLOR_CORRECTION_GAINS, ourGains)
                                    set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, transform)
                                }
                                // A still of what the preview has now, and the mean color of its middle half
                                suspend fun shot(name: String, configure: CaptureRequest.Builder.() -> Unit): JsonObject {
                                    device.repeating(session, thread, listOf(surface), callback = watch, configure = configure)
                                    delay(1500)
                                    // The still alone: one request never mixes the HLG preview with the still's standard range on the Pixel
                                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                        addTarget(queue.reader.surface)
                                        still(id)
                                        configure()
                                    }
                                    val result = session.captureOnce(request.build(), thread)
                                    val bytes = queue.next().use { image -> image.planes[0].buffer.let { b -> ByteArray(b.remaining()).also { b.get(it) } } }
                                    val uri = Media.saveImage(resolver, "WB_${Media.stamp()}_${if (hlg) "hlg" else "sdr"}_${name}_cam$id.jpg", "image/jpeg", bytes)
                                    return buildJsonObject {
                                        put("file", uri.toString())
                                        result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { put("resultGains", "${it.red}, ${it.greenEven}, ${it.greenOdd}, ${it.blue}") }
                                        result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { put("resultTransform", it.toString()) }
                                        put("resultAwbMode", jsonOf(result.get(CaptureResult.CONTROL_AWB_MODE)))
                                        put("resultColorCorrectionMode", jsonOf(result.get(CaptureResult.COLOR_CORRECTION_MODE)))
                                        meanColor(bytes)?.let { (r, g, b) ->
                                            put("meanRgb", "${r.roundToInt()}, ${g.roundToInt()}, ${b.roundToInt()}")
                                            put("redToGreen", r / g)
                                            put("blueToGreen", b / g)
                                        }
                                    }
                                }
                                buildJsonObject {
                                    put("lens", lens ?: "")
                                    put("cameraGains", "${gains.red}, ${gains.greenEven}, ${gains.greenOdd}, ${gains.blue}")
                                    put("cameraTransform", cameraTransform.toString())
                                    put("kelvinRead", kelvin)
                                    put("ourGains", "${ours.red}, 1, 1, ${ours.blue}")
                                    put("ourTransform", ourTransform.toString())
                                    put("auto", shot("auto") {})
                                    put("ours", shot("ours") { manual(ourTransform) })
                                    put("oursGainsCameraTransform", shot("camtransform") { manual(cameraTransform) })
                                    // As the app sets it in an HDR session: red corrected for the camera's HDR processing
                                    if (hlg) {
                                        val corrected = RggbChannelVector((ours.red * WhiteBalance.hdrRedCorrection(ours.kelvin)).toFloat(), 1f, 1f, ours.blue.toFloat())
                                        put("oursHdrCorrected", shot("corrected") {
                                            manual(ourTransform)
                                            set(CaptureRequest.COLOR_CORRECTION_GAINS, corrected)
                                        })
                                    }
                                    put("auto2", shot("auto2") {})
                                }
                            } finally {
                                session.close()
                            }
                        }
                    }
                })
            }
        }
    }

    /**
     * A 4 s exposure in the app's own session, step by step (the app's long Pro photos weren't delivered where this test's plain one was): with the HDR
     * preview and JPEG_R, and with the SDR preview and JPEG, each adding one thing the app has – the analysis stream, the preview's exposure set by hand
     * (1/15 s with the gain added), the still's ZSL off, the preview's request sent again every 100 ms during the exposure. For each: what the camera said
     * (start, result, failure, lost buffer), whether and when the still came, and its time against the start.
     */
    private suspend fun proLongExposureApp(id: String): JsonObject {
        val chars = chars(id)
        val map = map(id)
        val manual = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        if (!manual) return notOffered("manual exposure")
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: return notOffered("an ISO range")
        val hlgOffered = largest(map, ImageFormat.JPEG_R) != null && chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles?.contains(DynamicRangeProfiles.HLG10) == true
        val surface = preview.surface(previewSize(map))
        val exposureNs = 4_000_000_000L
        val previewNs = 66_666_667L
        val steps = listOf("base", "analysis", "manualPreview", "zslOff", "resend")
        return buildJsonObject {
            for (hlg in listOf(false, true)) {
                if (hlg && !hlgOffered) continue
                val format = if (hlg) ImageFormat.JPEG_R else ImageFormat.JPEG
                val size = largest(map, format) ?: continue
                // The app's analysis stream: the smallest 4:3 at least 640 wide, P010 next to an HLG preview, YUV next to SDR
                val analysisFormat = if (hlg) ImageFormat.YCBCR_P010 else ImageFormat.YUV_420_888
                val analysisSize = map.getOutputSizes(analysisFormat)?.filter { it.width >= 640 && abs(it.width * 3 - it.height * 4) <= it.width / 20 }?.minByOrNull { area(it) }
                put(if (hlg) "hlg" else "sdr", buildJsonObject {
                    for ((index, step) in steps.withIndex()) {
                        val withAnalysis = index >= 1
                        val manualPreview = index >= 2
                        val zslOff = index >= 3
                        val resend = index >= 4
                        put(step, probe {
                            manager.withCamera(id) { device, thread ->
                                ImageQueue(imageReader(size.width, size.height, format, 3), thread).use { stills ->
                                    val analysis = if (withAnalysis && analysisSize != null) imageReader(analysisSize.width, analysisSize.height, analysisFormat, 4).also { reader ->
                                        reader.setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage() }.getOrNull()?.close() }, thread.handler)
                                    } else null
                                    try {
                                        val outputs = buildList {
                                            add(OutputConfiguration(surface).apply { if (hlg) dynamicRangeProfile = DynamicRangeProfiles.HLG10 })
                                            analysis?.let { add(OutputConfiguration(it.surface).apply { if (hlg) dynamicRangeProfile = DynamicRangeProfiles.HLG10 }) }
                                            add(OutputConfiguration(stills.reader.surface))
                                        }
                                        if (supported(id, outputs) == false) return@withCamera buildJsonObject { put("skipped", "session not supported") }
                                        val session = device.session(outputs, thread)
                                        try {
                                            val repeatTargets = listOfNotNull(surface, analysis?.surface)
                                            val previewIso = (100 * exposureNs / previewNs).toInt().coerceIn(isoRange.lower, isoRange.upper)
                                            fun CaptureRequest.Builder.previewControls() {
                                                if (manualPreview) {
                                                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                                                    set(CaptureRequest.SENSOR_SENSITIVITY, previewIso)
                                                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, previewNs)
                                                    set(CaptureRequest.SENSOR_FRAME_DURATION, previewNs)
                                                }
                                            }
                                            device.repeating(session, thread, repeatTargets) { previewControls() }
                                            delay(1500)
                                            val events = mutableListOf<String>()
                                            var startTs = 0L
                                            val t0 = SystemClock.elapsedRealtime()
                                            fun at() = SystemClock.elapsedRealtime() - t0
                                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                                addTarget(stills.reader.surface)
                                                still(id)
                                                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                                                set(CaptureRequest.SENSOR_SENSITIVITY, 100.coerceIn(isoRange.lower, isoRange.upper))
                                                set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
                                                set(CaptureRequest.SENSOR_FRAME_DURATION, exposureNs)
                                                if (zslOff) set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
                                            }
                                            session.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                                                override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                                                    startTs = timestamp
                                                    synchronized(events) { events += "started ${at()} ms (frame $frameNumber)" }
                                                }

                                                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                                    val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.div(1_000_000)
                                                    val lens = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
                                                    synchronized(events) { events += "completed ${at()} ms (exposure $exposure ms, ISO ${result.get(CaptureResult.SENSOR_SENSITIVITY)}, lens $lens)" }
                                                }

                                                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                                                    synchronized(events) { events += "failed ${at()} ms (reason ${failure.reason}, image captured ${failure.wasImageCaptured()})" }
                                                }

                                                override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
                                                    synchronized(events) { events += "buffer lost ${at()} ms (${if (target == stills.reader.surface) "still" else "other"})" }
                                                }
                                            }, thread.handler)
                                            // The app sends the preview's request again while metering: every 100 ms here, during the exposure
                                            val image = coroutineScope {
                                                val resending = if (resend) launch {
                                                    while (isActive) {
                                                        delay(100)
                                                        runCatching { device.repeating(session, thread, repeatTargets) { previewControls() } }
                                                    }
                                                } else null
                                                runCatching { stills.next(14_000) }.getOrNull().also { resending?.cancel() }
                                            }
                                            buildJsonObject {
                                                put("events", JsonArray(synchronized(events) { events.toList() }.map { JsonPrimitive(it) }))
                                                if (image == null) {
                                                    put("still", "none within 14 s")
                                                } else {
                                                    val ts = image.timestamp
                                                    val bytes = image.use { it.planes[0].buffer.let { b -> ByteArray(b.remaining()).also { a -> b.get(a) } } }
                                                    put("still", "at ${at()} ms")
                                                    put("stillMinusStartUs", (ts - startTs) / 1000)
                                                    put("file", Media.saveImage(resolver, "LONG_${Media.stamp()}_${if (hlg) "hlg" else "sdr"}_${step}_cam$id.jpg", "image/jpeg", bytes).toString())
                                                }
                                            }
                                        } finally {
                                            session.close()
                                        }
                                    } finally {
                                        analysis?.close()
                                    }
                                }
                            }
                        })
                    }
                })
            }
        }
    }

    /** One way to ask for a still set by hand without HDR: its format, template, exposure and what else the request carries. */
    private class SdrVariant(val key: String, val format: Int, val exposureNs: Long, val template: Int = CameraDevice.TEMPLATE_STILL_CAPTURE, val withPreview: Boolean = false, val extra: CaptureRequest.Builder.() -> Unit = {})

    /**
     * A still set by hand next to an SDR preview, asked for in several ways: whether the camera takes the time and ISO it was given (the result's and the
     * image's), how long until the image, and in which order the camera's start, its result and the image come (all on one thread). JPEG files go to
     * DCIM/CameraDiag.
     */
    private suspend fun proManualSdr(id: String): JsonObject {
        val chars = chars(id)
        val map = map(id)
        val manual = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        if (!manual) return notOffered("manual exposure")
        val iso = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.clamp(100) ?: 100
        val surface = preview.surface(previewSize(map))
        val variants = buildList {
            add(SdrVariant("jpeg4s", ImageFormat.JPEG, 4_000_000_000L))
            add(SdrVariant("jpeg250ms", ImageFormat.JPEG, 250_000_000L))
            add(SdrVariant("jpeg4sManualTemplate", ImageFormat.JPEG, 4_000_000_000L, template = CameraDevice.TEMPLATE_MANUAL))
            add(SdrVariant("jpeg4sControlOff", ImageFormat.JPEG, 4_000_000_000L) { set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF) })
            add(SdrVariant("jpeg4sIntentManual", ImageFormat.JPEG, 4_000_000_000L) { set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_MANUAL) })
            add(SdrVariant("jpeg4sWithPreview", ImageFormat.JPEG, 4_000_000_000L, withPreview = true))
            add(SdrVariant("yuv4s", ImageFormat.YUV_420_888, 4_000_000_000L))
            if (largest(map, ImageFormat.JPEG_R) != null) add(SdrVariant("jpegR4s", ImageFormat.JPEG_R, 4_000_000_000L))
        }
        return buildJsonObject {
            put("iso", iso)
            for (variant in variants) {
                val size = largest(map, variant.format) ?: continue
                put(variant.key, probe {
                    manager.withCamera(id) { device, thread ->
                        val reader = imageReader(size.width, size.height, variant.format, 2)
                        try {
                            val outputs = listOf(OutputConfiguration(surface), OutputConfiguration(reader.surface))
                            if (supported(id, outputs) == false) return@withCamera buildJsonObject { put("skipped", "session not supported") }
                            val session = device.session(outputs, thread)
                            try {
                                device.repeating(session, thread, listOf(surface))
                                delay(1500)
                                val events = mutableListOf<String>()
                                val t0 = SystemClock.elapsedRealtime()
                                fun at() = SystemClock.elapsedRealtime() - t0
                                fun log(event: String) = synchronized(events) { events += "$event ${at()} ms" }
                                var startTs = 0L
                                val image = CompletableDeferred<Image>()
                                val completed = CompletableDeferred<TotalCaptureResult?>()
                                // The image's arrival logged on the same thread as the camera's callbacks: their real order
                                reader.setOnImageAvailableListener({ r ->
                                    val next = runCatching { r.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
                                    log("image")
                                    if (!image.complete(next)) next.close()
                                }, thread.handler)
                                val request = device.createCaptureRequest(variant.template).apply {
                                    addTarget(reader.surface)
                                    if (variant.withPreview) addTarget(surface)
                                    still(id)
                                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                                    set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, variant.exposureNs)
                                    set(CaptureRequest.SENSOR_FRAME_DURATION, variant.exposureNs)
                                    variant.extra(this)
                                }
                                session.captureSingleRequest(request.build(), thread.executor, object : CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                                        startTs = timestamp
                                        log("started")
                                    }

                                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                        log("completed")
                                        completed.complete(result)
                                    }

                                    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                                        log("failed (reason ${failure.reason}, image captured ${failure.wasImageCaptured()})")
                                        completed.complete(null)
                                    }

                                    override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
                                        log("buffer lost (${if (target == reader.surface) "still" else "preview"})")
                                    }
                                })
                                val still = withTimeoutOrNull(variant.exposureNs / 1_000_000 + 10_000) { image.await() }
                                val result = withTimeoutOrNull(2_000) { completed.await() }
                                buildJsonObject {
                                    put("askedMs", variant.exposureNs / 1e6)
                                    put("events", JsonArray(synchronized(events) { events.toList() }.map { JsonPrimitive(it) }))
                                    result?.let { r ->
                                        put("resultExposureMs", jsonOf(r.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.div(1e6)))
                                        put("resultIso", jsonOf(r.get(CaptureResult.SENSOR_SENSITIVITY)))
                                        put("resultAeMode", jsonOf(r.get(CaptureResult.CONTROL_AE_MODE)))
                                        put("resultControlMode", jsonOf(r.get(CaptureResult.CONTROL_MODE)))
                                        put("resultLens", jsonOf(r.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)))
                                    }
                                    if (still == null) {
                                        put("still", "none")
                                    } else still.use { img ->
                                        put("imageMinusStartUs", if (startTs == 0L) null else (img.timestamp - startTs) / 1000)
                                        if (variant.format != ImageFormat.YUV_420_888) {
                                            val name = "SDR_${Media.stamp()}_${variant.key}_cam$id.jpg"
                                            put("file", Media.saveImage(resolver, name, "image/jpeg", img.compressedBytes()).toString())
                                        }
                                    }
                                }
                            } finally {
                                session.close()
                            }
                        } finally {
                            reader.close()
                        }
                    }
                })
            }
        }
    }

    /**
     * RAW from the lens of each zoom stop in the app's session (preview, photo, analysis stream): the logical camera's raw stream comes from the main sensor at
     * every zoom (owner's test, 25 Nov 2026), so the raw stream is asked of the stop's physical lens. With an HLG and an SDR preview: whether the session is
     * confirmed, the lens the camera used, whether the raw image came and its size; then whether one session may hold every lens' raw stream at once. The
     * photo and the DNG (that lens' own metadata, with orientation) go to DCIM/CameraDiag.
     */
    private suspend fun proRawLenses(id: String): JsonObject {
        val spec = CameraCatalog.read(manager).firstOrNull { it.id == id } ?: return notOffered("the camera in the catalog")
        val map = map(id)
        val surface = preview.surface(previewSize(map))
        val stops = spec.zoom.stops.filter { it.lens != null }
        if (stops.isEmpty()) return notOffered("physical lenses")
        fun rawSize(lens: String) = largest(chars(lens).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP), ImageFormat.RAW_SENSOR)
        val hlgOffered = largest(map, ImageFormat.JPEG_R) != null && chars(id).get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles?.contains(DynamicRangeProfiles.HLG10) == true
        return buildJsonObject {
            for (hlg in listOf(true, false)) {
                if (hlg && !hlgOffered) continue
                val format = if (hlg) ImageFormat.JPEG_R else ImageFormat.JPEG
                val size = largest(map, format) ?: continue
                val analysisFormat = if (hlg) ImageFormat.YCBCR_P010 else ImageFormat.YUV_420_888
                val analysisSize = map.getOutputSizes(analysisFormat)?.filter { it.width >= 640 && abs(it.width * 3 - it.height * 4) <= it.width / 20 }?.minByOrNull { area(it) }
                val profile = if (hlg) DynamicRangeProfiles.HLG10 else DynamicRangeProfiles.STANDARD
                fun base(still: Surface, analysis: Surface?) = listOfNotNull(
                    OutputConfiguration(surface).apply { dynamicRangeProfile = profile },
                    OutputConfiguration(still),
                    analysis?.let { OutputConfiguration(it).apply { dynamicRangeProfile = profile } },
                )
                put(if (hlg) "hlg" else "sdr", buildJsonObject {
                    // Every lens' raw stream in one session: only asked
                    run {
                        val readers = stops.mapNotNull { stop -> rawSize(stop.lens!!)?.let { stop.lens to imageReader(it.width, it.height, ImageFormat.RAW_SENSOR, 1) } }
                        val still = imageReader(size.width, size.height, format, 1)
                        val analysis = analysisSize?.let { imageReader(it.width, it.height, analysisFormat, 1) }
                        try {
                            val outputs = base(still.surface, analysis?.surface) + readers.map { (lens, reader) -> OutputConfiguration(reader.surface).apply { setPhysicalCameraId(lens) } }
                            put("allLensesOneSession", jsonOf(supported(id, outputs)))
                        } finally {
                            readers.forEach { it.second.close() }
                            still.close()
                            analysis?.close()
                        }
                    }
                    for (stop in stops) {
                        val lens = stop.lens!!
                        val raw = rawSize(lens) ?: continue
                        put("${stop.ratio}x lens $lens", probe {
                            manager.withCamera(id) { device, thread ->
                                ImageQueue(imageReader(size.width, size.height, format, 2), thread).use { stills ->
                                    ImageQueue(imageReader(raw.width, raw.height, ImageFormat.RAW_SENSOR, 2), thread).use { raws ->
                                        val analysis = analysisSize?.let { a -> imageReader(a.width, a.height, analysisFormat, 4).also { r -> r.setOnImageAvailableListener({ runCatching { it.acquireLatestImage() }.getOrNull()?.close() }, thread.handler) } }
                                        try {
                                            val outputs = base(stills.reader.surface, analysis?.surface) + OutputConfiguration(raws.reader.surface).apply { setPhysicalCameraId(lens) }
                                            val confirmed = supported(id, outputs)
                                            // Configured only as the camera confirms it: a refused physical stream may break the camera service
                                            if (confirmed != true) return@withCamera buildJsonObject { put("supported", jsonOf(confirmed)) }
                                            val session = device.session(outputs, thread)
                                            try {
                                                val zoom = stop.ratio.toFloat()
                                                device.repeating(session, thread, listOfNotNull(surface, analysis?.surface)) { set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom) }
                                                delay(1500)
                                                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                                    addTarget(stills.reader.surface)
                                                    addTarget(raws.reader.surface)
                                                    still(id)
                                                    set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                                                    if (chars(lens).get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)?.contains(CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON) == true) {
                                                        set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
                                                    }
                                                }
                                                val result = runCatching { session.captureOnce(request.build(), thread) }
                                                val still = withTimeoutOrNull(6000) { stills.next(7000) }
                                                val rawImage = withTimeoutOrNull(3000) { raws.next(4000) }
                                                val stamp = Media.stamp()
                                                buildJsonObject {
                                                    put("supported", jsonOf(confirmed))
                                                    put("result", result.exceptionOrNull()?.message ?: "completed")
                                                    result.getOrNull()?.let { r ->
                                                        put("activePhysicalId", jsonOf(r.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)))
                                                        put("physicalResults", jsonOf(r.physicalCameraTotalResults.keys.sorted().joinToString(",")))
                                                    }
                                                    put("photo", still != null)
                                                    still?.use { img -> put("photoFile", Media.saveImage(resolver, "LENSRAW_${stamp}_${if (hlg) "hlg" else "sdr"}_${stop.ratio}x_cam$id.jpg", "image/jpeg", img.compressedBytes()).toString()) }
                                                    put("raw", rawImage?.let { "${it.width}x${it.height}" } ?: "none")
                                                    val physical = result.getOrNull()?.let { r -> r.physicalCameraTotalResults[lens] ?: r }
                                                    if (rawImage != null && physical != null) rawImage.use { img ->
                                                        put("rawMatchesResult", img.timestamp == physical.get(CaptureResult.SENSOR_TIMESTAMP))
                                                        val dng = Media.newImage(resolver, "LENSRAW_${stamp}_${if (hlg) "hlg" else "sdr"}_${stop.ratio}x_cam$id.dng", "image/x-adobe-dng")
                                                        val written = runCatching {
                                                            DngCreator(chars(lens), physical).use { creator ->
                                                                creator.setOrientation(exifOrientation(orientation(id)))
                                                                resolver.openOutputStream(dng)!!.use { creator.writeImage(it, img) }
                                                            }
                                                            Media.publish(resolver, dng)
                                                        }
                                                        put("dng", written.exceptionOrNull()?.let { e -> "${e.javaClass.simpleName}: ${e.message}" } ?: dng.toString())
                                                    } else rawImage?.close()
                                                }
                                            } finally {
                                                session.close()
                                            }
                                        } finally {
                                            analysis?.close()
                                        }
                                    }
                                }
                            }
                        })
                    }
                })
            }
        }
    }

    /**
     * Which lens the camera takes the picture with at each zoom, in the app's session (SDR preview, photo, analysis stream): the zoom slid up from the lowest
     * to the highest by 0.1 and back, each step held 0.7 s; the active physical lens and the logical crop. Where the lens changes between the stops (the DNG's
     * raw stream follows the lens), and whether up and down differ. Pointed at something farther than 2 m (the tele isn't used for near subjects).
     */
    private suspend fun lensByZoom(id: String): JsonObject {
        val chars = chars(id)
        if (chars.physicalCameraIds.isEmpty()) return notOffered("physical lenses")
        val map = map(id)
        val range = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: return notOffered("a zoom ratio range")
        val size = largest(map, ImageFormat.JPEG) ?: return notOffered("JPEG")
        val surface = preview.surface(previewSize(map))
        val top = minOf(range.upper, 6f)
        val up = buildList {
            var z = range.lower.toDouble()
            while (z <= top + 1e-6) {
                add((z * 100).roundToInt() / 100.0)
                z = if (z < 1.0) minOf(1.0, z + 0.1) else z + 0.1
            }
        }
        val watch = ResultWatch()
        return manager.withCamera(id) { device, thread ->
            ImageQueue(imageReader(size.width, size.height, ImageFormat.JPEG, 1), thread).use { stills ->
                val analysisSize = map.getOutputSizes(ImageFormat.YUV_420_888)?.filter { it.width >= 640 && abs(it.width * 3 - it.height * 4) <= it.width / 20 }?.minByOrNull { area(it) }
                val analysis = analysisSize?.let { a -> imageReader(a.width, a.height, ImageFormat.YUV_420_888, 4).also { r -> r.setOnImageAvailableListener({ runCatching { it.acquireLatestImage() }.getOrNull()?.close() }, thread.handler) } }
                try {
                    val outputs = listOfNotNull(OutputConfiguration(surface), OutputConfiguration(stills.reader.surface), analysis?.let { OutputConfiguration(it.surface) })
                    val session = device.session(outputs, thread)
                    try {
                        buildJsonObject {
                            for ((name, steps) in listOf("up" to up, "down" to up.reversed())) put(name, buildJsonArray {
                                for (zoom in steps) {
                                    device.repeating(session, thread, listOfNotNull(surface, analysis?.surface), callback = watch) { set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom.toFloat()) }
                                    delay(700)
                                    val r = watch.result
                                    add(JsonPrimitive("$zoom: lens ${r?.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)}, focus ${r?.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { String.format(java.util.Locale.ROOT, "%.2f", it) }} D"))
                                }
                            })
                        }
                    } finally {
                        session.close()
                    }
                } finally {
                    analysis?.close()
                }
            }
        }
    }

    /**
     * 50 MP in the app's Pro session: which sets with the maximum-resolution RAW10 the camera confirms (SDR or HLG preview, the analysis stream, a JPEG
     * beside it, the logical camera or its main lens), then in the best confirmed one two shots (the camera's exposure, and 1/4 s at ISO 100 set by hand):
     * time to the image, preview frames meanwhile, the result's exposure, sensor mode, crop and lens shading map. Only the main lens (the tele's 50 MP mode
     * breaks the camera service); nothing unconfirmed is configured. No files.
     */
    private suspend fun pro50mpApp(id: String): JsonObject {
        val chars = chars(id)
        val maxMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION) ?: return notOffered("maximum resolution")
        val raw = largest(maxMap, ImageFormat.RAW10) ?: return notOffered("RAW10 at maximum resolution")
        val map = map(id)
        val surface = preview.surface(previewSize(map))
        val spec = CameraCatalog.read(manager).firstOrNull { it.id == id }
        val main = spec?.zoom?.stops?.firstOrNull { abs(it.ratio - 1.0) < 1e-6 }?.lens
        val jpeg = largest(map, ImageFormat.JPEG)
        val yuv = map.getOutputSizes(ImageFormat.YUV_420_888)?.filter { it.width >= 640 && abs(it.width * 3 - it.height * 4) <= it.width / 20 }?.minByOrNull { area(it) }
        val p010 = map.getOutputSizes(ImageFormat.YCBCR_P010)?.filter { it.width >= 640 && abs(it.width * 3 - it.height * 4) <= it.width / 20 }?.minByOrNull { area(it) }
        class Set(val key: String, val hlg: Boolean, val analysis: Boolean, val photo: Boolean, val physical: Boolean)
        val sets = buildList {
            for (physical in listOfNotNull(false, true.takeIf { main != null })) {
                val tag = if (physical) "lens$main" else "logical"
                add(Set("${tag}_sdr", hlg = false, analysis = false, photo = false, physical = physical))
                add(Set("${tag}_sdr_analysis", hlg = false, analysis = true, photo = false, physical = physical))
                add(Set("${tag}_sdr_analysis_jpeg", hlg = false, analysis = true, photo = true, physical = physical))
                add(Set("${tag}_hlg_analysis", hlg = true, analysis = true, photo = false, physical = physical))
            }
        }
        fun outputs(set: Set, previewTarget: Surface, rawTarget: Surface, analysisTarget: Surface?, photoTarget: Surface?): List<OutputConfiguration> {
            val profile = if (set.hlg) DynamicRangeProfiles.HLG10 else DynamicRangeProfiles.STANDARD
            return listOfNotNull(
                OutputConfiguration(previewTarget).apply { dynamicRangeProfile = profile },
                OutputConfiguration(rawTarget).apply {
                    addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
                    if (set.physical) setPhysicalCameraId(main)
                },
                analysisTarget?.let { OutputConfiguration(it).apply { dynamicRangeProfile = profile } },
                photoTarget?.let { OutputConfiguration(it) },
            )
        }
        return buildJsonObject {
            put("raw", sizeText(raw))
            put("mainLens", jsonOf(main))
            // Asked only: readers of the right sizes stand in for the streams
            val confirmed = mutableListOf<Set>()
            put("supported", buildJsonObject {
                for (set in sets) {
                    val rawReader = imageReader(raw.width, raw.height, ImageFormat.RAW10, 1)
                    val analysisSize = if (set.hlg) p010 else yuv
                    val analysis = if (set.analysis) analysisSize?.let { imageReader(it.width, it.height, if (set.hlg) ImageFormat.YCBCR_P010 else ImageFormat.YUV_420_888, 1) } else null
                    val photo = if (set.photo) jpeg?.let { imageReader(it.width, it.height, ImageFormat.JPEG, 1) } else null
                    try {
                        val answer = supported(id, outputs(set, surface, rawReader.surface, analysis?.surface, photo?.surface))
                        put(set.key, jsonOf(answer))
                        if (answer == true) confirmed += set
                    } finally {
                        rawReader.close()
                        analysis?.close()
                        photo?.close()
                    }
                }
            })
            // The fullest confirmed set without a JPEG (DNG only, as the app's 50 MP), the logical camera first
            val chosen = confirmed.filter { !it.photo }.maxByOrNull { (if (it.analysis) 2 else 0) + (if (!it.physical) 1 else 0) - (if (it.hlg) 4 else 0) } ?: return@buildJsonObject
            put("used", chosen.key)
            put("shots", probe {
                manager.withCamera(id) { device, thread ->
                    ImageQueue(imageReader(raw.width, raw.height, ImageFormat.RAW10, 1), thread).use { raws ->
                        val analysisSize = if (chosen.hlg) p010 else yuv
                        val analysis = if (chosen.analysis) analysisSize?.let { a -> imageReader(a.width, a.height, if (chosen.hlg) ImageFormat.YCBCR_P010 else ImageFormat.YUV_420_888, 4).also { r -> r.setOnImageAvailableListener({ runCatching { it.acquireLatestImage() }.getOrNull()?.close() }, thread.handler) } } else null
                        try {
                            val session = device.session(outputs(chosen, surface, raws.reader.surface, analysis?.surface, null), thread)
                            try {
                                val frames = CopyOnWriteArrayList<Long>()
                                val watch = object : CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                        frames += SystemClock.elapsedRealtime()
                                    }
                                }
                                device.repeating(session, thread, listOfNotNull(surface, analysis?.surface), callback = watch) { set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f) }
                                delay(1500)
                                buildJsonObject {
                                    for ((name, manual) in listOf("auto" to false, "quarterSecondIso100" to true)) put(name, probe {
                                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                            addTarget(raws.reader.surface)
                                            set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
                                            set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f)
                                            set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
                                            if (manual) {
                                                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                                                set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_MANUAL)
                                                set(CaptureRequest.SENSOR_SENSITIVITY, 100)
                                                set(CaptureRequest.SENSOR_EXPOSURE_TIME, 250_000_000L)
                                                set(CaptureRequest.SENSOR_FRAME_DURATION, 250_000_000L)
                                            }
                                        }
                                        val start = SystemClock.elapsedRealtime()
                                        val result = session.captureOnce(request.build(), thread)
                                        val image = raws.next(10_000)
                                        val end = SystemClock.elapsedRealtime()
                                        image.use { img ->
                                            val physical = if (chosen.physical) result.physicalCameraTotalResults[main] ?: result else result
                                            buildJsonObject {
                                                put("latencyMs", end - start)
                                                put("previewFramesMeanwhile", frames.count { it in start..end })
                                                put("image", "${img.width}x${img.height}")
                                                put("activePhysicalId", jsonOf(result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)))
                                                put("sensorPixelMode", jsonOf(physical.get(CaptureResult.SENSOR_PIXEL_MODE)))
                                                put("exposureMs", jsonOf(physical.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.div(1e6)))
                                                put("iso", jsonOf(physical.get(CaptureResult.SENSOR_SENSITIVITY)))
                                                put("cropRegion", jsonOf(physical.get(CaptureResult.SCALER_CROP_REGION)))
                                                put("lensShadingMap", jsonOf(physical.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)?.let { "${it.columnCount}x${it.rowCount}" }))
                                                put("sameFrame", img.timestamp == physical.get(CaptureResult.SENSOR_TIMESTAMP))
                                            }
                                        }
                                    })
                                }
                            } finally {
                                session.close()
                            }
                        } finally {
                            analysis?.close()
                        }
                    }
                }
            })
        }
    }

    /** The mean RGB of a photo's middle half (decoded small): the color cast to compare. */
    private fun meanColor(bytes: ByteArray): Triple<Double, Double, Double>? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 8 }) ?: return null
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var n = 0
        for (y in bitmap.height / 4 until bitmap.height * 3 / 4) for (x in bitmap.width / 4 until bitmap.width * 3 / 4) {
            val c = bitmap[x, y]
            r += (c shr 16) and 0xFF
            g += (c shr 8) and 0xFF
            b += c and 0xFF
            n++
        }
        bitmap.recycle()
        return if (n == 0) null else Triple(r / n, g / n, b / n)
    }

    /**
     * Long exposures as Pro takes them (LOGIKA §26, §30): the preview automatic, then one still with the exposure off, ISO 100 and 4 s, then 16 s (or the
     * camera's longest): how long until the file, what the result says, and how many preview frames came meanwhile. The files go to DCIM/CameraDiag.
     */
    private suspend fun proLongExposure(id: String): JsonObject {
        val chars = chars(id)
        val map = map(id)
        val manual = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        val range = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        if (!manual || range == null) return notOffered("manual exposure")
        val format = if (largest(map, ImageFormat.JPEG_R) != null) ImageFormat.JPEG_R else ImageFormat.JPEG
        val size = largest(map, format) ?: return notOffered("a still size")
        val hlg = format == ImageFormat.JPEG_R && chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles?.contains(DynamicRangeProfiles.HLG10) == true
        val iso = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.clamp(100) ?: 100
        val surface = preview.surface(previewSize(map))
        return manager.withCamera(id) { device, thread ->
            ImageQueue(imageReader(size.width, size.height, format, 2), thread).use { queue ->
                val outputs = listOf(OutputConfiguration(surface).apply { if (hlg) dynamicRangeProfile = DynamicRangeProfiles.HLG10 }, OutputConfiguration(queue.reader.surface))
                val session = device.session(outputs, thread)
                try {
                    val previewDone = mutableListOf<Long>()
                    val watch = object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                            previewDone += SystemClock.elapsedRealtime()
                        }
                    }
                    device.repeating(session, thread, listOf(surface), callback = watch)
                    delay(1500)
                    buildJsonObject {
                        put("format", Names.format(format))
                        put("previewHlg", hlg)
                        put("iso", iso)
                        for (seconds in listOf(4L, 16L)) {
                            val exposure = (seconds * 1_000_000_000L).coerceAtMost(range.upper)
                            put("${seconds}s", probe {
                                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                    addTarget(queue.reader.surface)
                                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                                    set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
                                    set(CaptureRequest.SENSOR_FRAME_DURATION, exposure)
                                }
                                val start = SystemClock.elapsedRealtime()
                                val result = session.captureOnce(request.build(), thread)
                                val bytes = queue.next(exposure / 1_000_000 + 20_000).use { image -> image.planes[0].buffer.let { b -> ByteArray(b.remaining()).also { b.get(it) } } }
                                val end = SystemClock.elapsedRealtime()
                                val uri = Media.saveImage(resolver, "PRO_${Media.stamp()}_${seconds}s_cam$id.jpg", "image/jpeg", bytes)
                                buildJsonObject {
                                    put("askedMs", exposure / 1_000_000)
                                    put("latencyMs", end - start)
                                    put("resultExposureMs", jsonOf(result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.div(1_000_000)))
                                    put("resultIso", jsonOf(result.get(CaptureResult.SENSOR_SENSITIVITY)))
                                    put("previewFramesMeanwhile", previewDone.count { it in start..end })
                                    put("file", uri.toString())
                                }
                            })
                        }
                    }
                } finally {
                    session.close()
                }
            }
        }
    }

    /**
     * RAW next to the photo in one request (LOGIKA §28): the session asked about first (and with an HLG preview as the app would have it), then one still with
     * both targets: both images' times (one frame?), the latency, and the DNG written by DngCreator with the JPEG beside it in DCIM/CameraDiag.
     */
    private suspend fun proRawWithJpeg(id: String): JsonObject {
        val chars = chars(id)
        val map = map(id)
        val raw = largest(map, ImageFormat.RAW_SENSOR) ?: return notOffered("RAW_SENSOR")
        val format = if (largest(map, ImageFormat.JPEG_R) != null) ImageFormat.JPEG_R else ImageFormat.JPEG
        val size = largest(map, format) ?: return notOffered("a still size")
        val surface = preview.surface(previewSize(map))
        val hlgOffered = format == ImageFormat.JPEG_R && chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles?.contains(DynamicRangeProfiles.HLG10) == true
        return manager.withCamera(id) { device, thread ->
            ImageQueue(imageReader(size.width, size.height, format, 2), thread).use { stills ->
                ImageQueue(imageReader(raw.width, raw.height, ImageFormat.RAW_SENSOR, 2), thread).use { raws ->
                    fun outputs(hlg: Boolean) = listOf(OutputConfiguration(surface).apply { if (hlg) dynamicRangeProfile = DynamicRangeProfiles.HLG10 }, OutputConfiguration(stills.reader.surface), OutputConfiguration(raws.reader.surface))
                    // Configured only as the camera confirms it: a refused combination may break the camera service
                    val hlgSupported = if (hlgOffered) supported(id, outputs(true)) else false
                    val sdrSupported = supported(id, outputs(false))
                    val hlg = hlgSupported == true
                    if (!hlg && sdrSupported == false) return@withCamera buildJsonObject { put("hlgPreview", jsonOf(hlgSupported)); put("sdrPreview", false) }
                    val session = device.session(outputs(hlg), thread)
                    try {
                        device.repeating(session, thread, listOf(surface))
                        delay(1500)
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(stills.reader.surface)
                            addTarget(raws.reader.surface)
                            set(CaptureRequest.JPEG_ORIENTATION, orientation(id))
                        }
                        val start = SystemClock.elapsedRealtime()
                        val result = session.captureOnce(request.build(), thread)
                        val still = stills.next()
                        val rawImage = raws.next()
                        val latency = SystemClock.elapsedRealtime() - start
                        val stamp = Media.stamp()
                        val dng = Media.newImage(resolver, "PRO_${stamp}_cam$id.dng", "image/x-adobe-dng")
                        rawImage.use { image ->
                            DngCreator(chars, result).use { creator ->
                                creator.setOrientation(exifOrientation(orientation(id)))
                                resolver.openOutputStream(dng)!!.use { creator.writeImage(it, image) }
                            }
                        }
                        Media.publish(resolver, dng)
                        val stillTime = still.timestamp
                        val jpegBytes = still.use { image -> image.planes[0].buffer.let { b -> ByteArray(b.remaining()).also { b.get(it) } } }
                        val jpeg = Media.saveImage(resolver, "PRO_${stamp}_cam$id.jpg", "image/jpeg", jpegBytes)
                        buildJsonObject {
                            put("hlgPreview", jsonOf(hlgSupported))
                            put("sdrPreview", jsonOf(sdrSupported))
                            put("used", if (hlg) "HLG preview" else "SDR preview")
                            put("stillFormat", Names.format(format))
                            put("rawSize", sizeText(raw))
                            put("sameFrame", stillTime == result.get(CaptureResult.SENSOR_TIMESTAMP))
                            put("latencyMs", latency)
                            put("dng", dng.toString())
                            put("jpeg", jpeg.toString())
                        }
                    } finally {
                        session.close()
                    }
                }
            }
        }
    }

    /**
     * 50 MP as Pro would save it (D4, D18): one RAW10 at the sensor's maximum resolution written twice to DCIM/CameraDiag, to compare in a RAW converter: its
     * quad Bayer (2 × 2 pixels of one colour) rearranged into a plain Bayer by swapping the middle columns and rows of every 4 × 4 block (artefacts of so simple
     * a remosaic?), and as it is with a 4 × 4 colour pattern (does the converter read it at all?). Written by the app's own DNG writer: DngCreator refuses an
     * image larger than the sensor's normal pixel array. Only a camera that offers maximum-resolution RAW10 (never the telephoto's, whose mode breaks the
     * camera service).
     */
    private suspend fun pro50mpDng(id: String): JsonObject {
        val chars = chars(id)
        val maxMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION) ?: return notOffered("maximum resolution")
        val size = largest(maxMap, ImageFormat.RAW10) ?: return notOffered("RAW10 at maximum resolution")
        val surface = preview.surface(previewSize(map(id)))
        return manager.withCamera(id) { device, thread ->
            ImageQueue(imageReader(size.width, size.height, ImageFormat.RAW10, 1), thread).use { queue ->
                val rawOutput = OutputConfiguration(queue.reader.surface).apply { addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION) }
                val session = device.session(listOf(OutputConfiguration(surface), rawOutput), thread)
                try {
                    device.repeating(session, thread, listOf(surface))
                    delay(1500)
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(queue.reader.surface)
                        set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
                    }
                    val result = session.captureOnce(request.build(), thread)
                    val cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG
                    val stamp = Media.stamp()
                    var remosaicMs = 0L
                    val files = queue.next().use { image ->
                        listOf(true, false).map { rearranged ->
                            val started = SystemClock.elapsedRealtime()
                            val pixels = unpack(image, rearranged)
                            if (rearranged) remosaicMs = SystemClock.elapsedRealtime() - started
                            val dng = Media.newImage(resolver, "PRO50_${stamp}_cam${id}_${if (rearranged) "bayer" else "quad"}.dng", "image/x-adobe-dng")
                            resolver.openOutputStream(dng)!!.use { TinyDng.write(it, size.width, size.height, pixels, if (rearranged) TinyDng.bayer(cfa) else TinyDng.quad(cfa), chars, result, exifOrientation(orientation(id))) }
                            Media.publish(resolver, dng)
                            dng.toString()
                        }
                    }
                    buildJsonObject {
                        put("size", sizeText(size))
                        put("cfa", cfa)
                        put("unpackAndRemosaicMs", remosaicMs)
                        put("bayerDng", files[0])
                        put("quadDng", files[1])
                    }
                } finally {
                    session.close()
                }
            }
        }
    }

    /** A RAW10 image as 16-bit samples (four pixels in five bytes unpacked); [rearranged]: quad Bayer made plain, in each 4 × 4 block columns 1 and 2, and rows 1 and 2, swapped. */
    private fun unpack(image: Image, rearranged: Boolean): ByteBuffer {
        val width = image.width
        val height = image.height
        val plane = image.planes[0]
        val source = plane.buffer
        val stride = plane.rowStride
        val out = ByteBuffer.allocateDirect(width * height * 2).order(ByteOrder.nativeOrder())
        val row = ShortArray(width)
        val packed = ByteArray(width * 5 / 4)
        for (y in 0 until height) {
            source.position(y * stride)
            source.get(packed)
            for (group in 0 until width / 4) {
                val at = group * 5
                val low = packed[at + 4].toInt() and 0xFF
                for (i in 0 until 4) row[group * 4 + i] = (((packed[at + i].toInt() and 0xFF) shl 2) or ((low shr (2 * i)) and 3)).toShort()
            }
            // Columns 1 and 2 of every block swapped
            if (rearranged) for (x in 0 until width step 4) {
                val keep = row[x + 1]
                row[x + 1] = row[x + 2]
                row[x + 2] = keep
            }
            // Rows 1 and 2 of every block swapped: written to the other's place
            val target = when {
                !rearranged -> y
                y % 4 == 1 -> y + 1
                y % 4 == 2 -> y - 1
                else -> y
            }
            out.position(target * width * 2)
            out.asShortBuffer().put(row)
        }
        out.position(0)
        return out
    }

    // RAW: a DNG at the default (binned) size, then RAW10 at the sensor's full resolution where it is offered only in maximum-resolution mode
    private suspend fun raw(id: String): JsonObject {
        val chars = chars(id)
        val map = map(id)
        val maxMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
        val surface = preview.surface(previewSize(map))
        return manager.withCamera(id) { device, thread ->
            buildJsonObject {
                largest(map, ImageFormat.RAW_SENSOR)?.let { size -> put("dng", probe { dng(id, device, thread, surface, size) }) }
                for (format in listOf(ImageFormat.RAW10, ImageFormat.RAW_SENSOR)) {
                    val size = largest(maxMap, format) ?: continue
                    put("${Names.format(format)} maximum resolution", probe { maximumResolutionRaw(device, thread, surface, format, size) })
                }
            }
        }
    }

    private suspend fun dng(id: String, device: CameraDevice, thread: CameraThread, surface: Surface, size: Size): JsonObject {
        ImageQueue(imageReader(size.width, size.height, ImageFormat.RAW_SENSOR, 2), thread).use { queue ->
            val session = device.session(listOf(OutputConfiguration(surface), OutputConfiguration(queue.reader.surface)), thread)
            try {
                device.repeating(session, thread, listOf(surface))
                delay(1500)
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(surface)
                    addTarget(queue.reader.surface)
                }
                val start = SystemClock.elapsedRealtime()
                val result = session.captureOnce(request.build(), thread)
                queue.next().use { image ->
                    val latency = SystemClock.elapsedRealtime() - start
                    val uri = Media.newImage(resolver, "RAW_${Media.stamp()}_cam$id.dng", "image/x-adobe-dng")
                    DngCreator(chars(id), result).use { dng ->
                        dng.setOrientation(exifOrientation(orientation(id)))
                        resolver.openOutputStream(uri)!!.use { dng.writeImage(it, image) }
                    }
                    Media.publish(resolver, uri)
                    return buildJsonObject {
                        put("size", sizeText(size))
                        put("latencyMs", latency)
                        put("file", uri.toString())
                    }
                }
            } finally {
                session.close()
            }
        }
    }

    private suspend fun maximumResolutionRaw(device: CameraDevice, thread: CameraThread, surface: Surface, format: Int, size: Size): JsonObject {
        ImageQueue(imageReader(size.width, size.height, format, 1), thread).use { queue ->
            val rawOutput = OutputConfiguration(queue.reader.surface).apply { addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION) }
            val session = device.session(listOf(OutputConfiguration(surface), rawOutput), thread)
            try {
                device.repeating(session, thread, listOf(surface))
                delay(1500)
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(queue.reader.surface)
                    set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
                }
                val start = SystemClock.elapsedRealtime()
                val result = session.captureOnce(request.build(), thread)
                queue.next().use { image ->
                    return buildJsonObject {
                        put("size", "${image.width}x${image.height}")
                        put("latencyMs", SystemClock.elapsedRealtime() - start)
                        put("rowStride", image.planes[0].rowStride)
                        put("pixelMode", jsonOf(result.get(CaptureResult.SENSOR_PIXEL_MODE)))
                        put("rawBinningFactorUsed", jsonOf(result.get(CaptureResult.SENSOR_RAW_BINNING_FACTOR_USED)))
                    }
                }
            } finally {
                session.close()
            }
        }
    }
}

/** The tag of the preview's requests in a burst, to count its frames apart from the stills'. */
private const val PREVIEW_TAG = "preview"
