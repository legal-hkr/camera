package sh.hnet.camera.diag.live

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraExtensionSession
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.os.Build
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.annotation.StringRes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import sh.hnet.camera.capabilities.Names
import sh.hnet.camera.capabilities.area
import sh.hnet.camera.capabilities.jsonOf
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
