package sh.hnet.camera.diag.live

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import sh.hnet.camera.capabilities.Names
import sh.hnet.camera.capabilities.area
import sh.hnet.camera.capabilities.jsonOf
import sh.hnet.camera.capabilities.probe
import sh.hnet.camera.capabilities.sizeText

/** A lens behind a logical camera (or the camera itself when it has none) and the logical camera's zoom ratio at which it shows its own field of view. */
class Lens(val id: String, val physical: Boolean, val zoom: Float, val chars: CameraCharacteristics)

/**
 * Captures for the quality decisions: which lens and sensor mode the camera uses at each zoom stop, the 50 MP sensors' raw layout, and comparison scenes (the
 * camera's own single Ultra HDR photo next to RAW bursts for the multi-frame pipeline to be tried on a computer). Files go to Download/CameraDiag/<test>_<stamp>_cam<id>/.
 */
class QualityTests(context: Context, private val preview: PreviewHost) {

    private val manager = context.getSystemService(CameraManager::class.java)
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val resolver = context.contentResolver

    val all = listOf(
        LiveTest("zoom_sources", "Zoom stops: the lens and sensor mode behind each") { zoomSources(it) },
        LiveTest("raw_50mp", "50 MP RAW of every lens, sensor layout") { raw50(it) },
        LiveTest("compare", "Comparison scene: Ultra HDR vs RAW bursts (about 1 GB, one scene per run)", inRunAll = false) { compare(it) },
    )

    private fun chars(id: String) = manager.getCameraCharacteristics(id)
    private fun map(chars: CameraCharacteristics): StreamConfigurationMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
    private fun zoomRange(chars: CameraCharacteristics): Range<Float> = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: Range(1f, 1f)
    private fun previewSize(map: StreamConfigurationMap) = Size(1920, 1080).takeIf { map.getOutputSizes(SurfaceTexture::class.java).contains(it) } ?: Size(1280, 720)

    /** Every size of a format, the high-resolution ones (slower than 20 fps) included: the tele's and ultrawide's 50 MP modes are listed only there. */
    private fun largest(map: StreamConfigurationMap?, format: Int): Size? =
        map?.let { m -> (m.getOutputSizes(format)?.toList().orEmpty() + m.getHighResolutionOutputSizes(format)?.toList().orEmpty()).maxByOrNull { area(it) } }

    /** Focal length over sensor width: proportional to the zoom ratio at which a lens shows its whole field of view. */
    private fun reach(chars: CameraCharacteristics): Float? {
        val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val width = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width ?: return null
        return focal / width
    }

    private fun lenses(id: String): List<Lens> {
        val logical = chars(id)
        val ids = logical.physicalCameraIds
        if (ids.isEmpty()) return listOf(Lens(id, false, 1f, logical))
        val reference = reach(logical)
        return ids.map { physicalId ->
            val chars = chars(physicalId)
            val zoom = reference?.let { r -> reach(chars)?.div(r) } ?: 1f
            Lens(physicalId, true, zoom, chars)
        }.sortedBy { it.zoom }
    }

    /** The zoom stop a lens stands for: its own ratio rounded to the stops cameras name (a 4.5× tele is the 5× stop), kept within the camera's range. */
    private fun stop(zoom: Float, range: Range<Float>): Float =
        listOf(0.5f, 1f, 2f, 3f, 5f, 10f).minBy { abs(ln(it / zoom)) }.coerceIn(range.lower, range.upper)

    private fun zoomText(zoom: Float) = String.format(Locale.ROOT, "%.1f", zoom)

    private fun own(result: TotalCaptureResult, lens: Lens): CaptureResult = if (lens.physical) result.physicalCameraTotalResults[lens.id] ?: result else result

    private fun lensJson(lens: Lens): JsonObject = buildJsonObject {
        val c = lens.chars
        put("id", lens.id)
        put("zoom", lens.zoom)
        put("focalMm", jsonOf(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)))
        put("sensorMm", jsonOf(c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)))
        put("pixelArray", jsonOf(c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)))
        put("pixelArrayMaximumResolution", jsonOf(c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)))
        put("binningFactor", jsonOf(c.get(CameraCharacteristics.SENSOR_INFO_BINNING_FACTOR)))
        put("colorFilterArrangement", jsonOf(c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)))
        put("raw", jsonOf(largest(c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP), ImageFormat.RAW_SENSOR)))
        val max = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
        put("rawMaximumResolution", buildJsonObject {
            for (format in listOf(ImageFormat.RAW_SENSOR, ImageFormat.RAW10)) largest(max, format)?.let { put(Names.format(format), sizeText(it)) }
        })
    }

    /** What the camera did for one capture: the lens it used, the crop on that lens's sensor, exposure. */
    private fun resultJson(result: TotalCaptureResult): JsonObject = buildJsonObject {
        val active = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
        put("activePhysicalId", active)
        val physical = active?.let { result.physicalCameraTotalResults[it] } ?: result
        put("cropRegion", jsonOf(physical.get(CaptureResult.SCALER_CROP_REGION)))
        put("zoomRatio", jsonOf(result.get(CaptureResult.CONTROL_ZOOM_RATIO)))
        put("exposureMs", jsonOf(result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.div(1e6)))
        put("iso", jsonOf(result.get(CaptureResult.SENSOR_SENSITIVITY)))
    }

    private suspend fun writeDng(folder: String, name: String, chars: CameraCharacteristics, result: CaptureResult, image: Image) = withContext(Dispatchers.IO) {
        Media.writeDownload(resolver, folder, name, "image/x-adobe-dng") { out ->
            DngCreator(chars, result).use { dng ->
                dng.setOrientation(exifOrientation(chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0))
                dng.writeImage(out, image)
            }
        }
    }

    private fun CaptureRequest.Builder.lensShading(chars: CameraCharacteristics) {
        if (chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)?.contains(CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON) == true) {
            set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
        }
    }

    // The camera's own photo (Ultra HDR where offered) at a zoom ratio, with the lens it chose
    private suspend fun photo(device: CameraDevice, thread: CameraThread, surface: Surface, chars: CameraCharacteristics, zoom: Float, folder: String, name: String): JsonObject {
        val map = map(chars)
        val format = if (largest(map, ImageFormat.JPEG_R) != null) ImageFormat.JPEG_R else ImageFormat.JPEG
        val size = largest(map, format)!!
        ImageQueue(imageReader(size.width, size.height, format, 2), thread).use { queue ->
            val session = device.session(listOf(OutputConfiguration(surface), OutputConfiguration(queue.reader.surface)), thread)
            try {
                device.repeating(session, thread, listOf(surface)) { set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom) }
                delay(1500)
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(surface)
                    addTarget(queue.reader.surface)
                    set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                    set(CaptureRequest.JPEG_ORIENTATION, chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0)
                    set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                }
                val result = session.captureOnce(request.build(), thread)
                val bytes = queue.next().use { it.compressedBytes() }
                withContext(Dispatchers.IO) { Media.writeDownload(resolver, folder, name, "image/jpeg") { it.write(bytes) } }
                return buildJsonObject {
                    put("file", name)
                    put("format", Names.format(format))
                    put("size", sizeText(size))
                    resultJson(result).forEach { (k, v) -> put(k, v) }
                }
            } finally {
                session.close()
            }
        }
    }

    // Zoom stops: the camera's photo at each stop (which lens, which crop), then one RAW frame from every lens through its own stream at the lens's stop
    private suspend fun zoomSources(id: String): JsonObject {
        val chars = chars(id)
        val range = zoomRange(chars)
        val lenses = lenses(id)
        val stops = (lenses.map { stop(it.zoom, range) } + 1f).distinct().sorted()
        val folder = "zoom_${Media.stamp()}_cam$id"
        val surface = preview.surface(previewSize(map(chars)))
        return manager.withCamera(id) { device, thread ->
            buildJsonObject {
                put("folder", "${Media.REPORT_DIR}/$folder")
                put("zoomRange", jsonOf(range))
                put("lenses", buildJsonArray { lenses.forEach { add(lensJson(it)) } })
                put("photos", buildJsonObject {
                    for (zoom in stops) put("${zoomText(zoom)}x", probe { photo(device, thread, surface, chars, zoom, folder, "photo_${zoomText(zoom)}x.jpg") })
                })
                put("lensRaw", buildJsonObject {
                    for (lens in lenses) put(lens.id, probe { lensRaw(device, thread, surface, lens, stop(lens.zoom, range), folder) })
                })
            }
        }
    }

    private suspend fun lensRaw(device: CameraDevice, thread: CameraThread, surface: Surface, lens: Lens, zoom: Float, folder: String): JsonObject {
        val size = largest(lens.chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP), ImageFormat.RAW_SENSOR) ?: return buildJsonObject { put("skipped", "no RAW_SENSOR") }
        ImageQueue(imageReader(size.width, size.height, ImageFormat.RAW_SENSOR, 2), thread).use { queue ->
            val raw = OutputConfiguration(queue.reader.surface).apply { if (lens.physical) setPhysicalCameraId(lens.id) }
            val session = device.session(listOf(OutputConfiguration(surface), raw), thread)
            try {
                device.repeating(session, thread, listOf(surface)) { set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom) }
                delay(1500)
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(surface)
                    addTarget(queue.reader.surface)
                    set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                    lensShading(lens.chars)
                }
                val result = session.captureOnce(request.build(), thread)
                // A lens the camera isn't streaming at this zoom returns no buffer
                val image = withTimeoutOrNull(5000) { queue.next(6000) } ?: return buildJsonObject {
                    put("delivered", false)
                    put("zoom", zoom)
                    resultJson(result).forEach { (k, v) -> put(k, v) }
                }
                image.use {
                    val name = "lens${lens.id}_${zoomText(zoom)}x.dng"
                    val physical = own(result, lens)
                    writeDng(folder, name, lens.chars, physical, it)
                    return buildJsonObject {
                        put("delivered", true)
                        put("zoom", zoom)
                        put("size", "${it.width}x${it.height}")
                        put("file", name)
                        put("lensCropRegion", jsonOf(physical.get(CaptureResult.SCALER_CROP_REGION)))
                        resultJson(result).forEach { (k, v) -> put(k, v) }
                    }
                }
            } finally {
                session.close()
            }
        }
    }

    // 50 MP: every lens with a maximum-resolution RAW mode, through its own stream at its zoom stop; the raw buffer is saved as is and its colour layout measured
    private suspend fun raw50(id: String): JsonObject {
        val chars = chars(id)
        val range = zoomRange(chars)
        val lenses = lenses(id).filter { maximumRaw(it) != null }
        if (lenses.isEmpty()) return buildJsonObject { put("skipped", "no maximum-resolution RAW") }
        val folder = "raw50_${Media.stamp()}_cam$id"
        val surface = preview.surface(previewSize(map(chars)))
        // Each lens in its own opening of the camera: on Pixel 9 Pro configuring the tele's 50 MP mode restarts the camera service, which then lists no camera for seconds
        return buildJsonObject {
            put("folder", "${Media.REPORT_DIR}/$folder")
            for (lens in lenses) {
                val zoom = stop(lens.zoom, range)
                put("${lens.id} waitedMs", waitForCamera(id))
                val attempt = probe { manager.withCamera(id) { device, thread -> maximumResolutionRaw(device, thread, surface, lens, zoom, folder, withPreview = true) } }
                put(lens.id, attempt)
                // Some lenses offer 50 MP only as a slow high-resolution mode, which may refuse to share the session with a preview
                if ((attempt as? JsonObject)?.containsKey("error") == true) {
                    put("${lens.id} without preview waitedMs", waitForCamera(id))
                    put("${lens.id} without preview", probe { manager.withCamera(id) { device, thread -> maximumResolutionRaw(device, thread, surface, lens, zoom, folder, withPreview = false) } })
                }
            }
            put("end waitedMs", waitForCamera(id))
        }
    }

    /** Waits (up to 20 s) until the camera is listed and readable again after a failure restarted the camera service; returns how long that took. */
    private suspend fun waitForCamera(id: String): Long {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < 20_000) {
            if (runCatching { id in manager.cameraIdList && manager.getCameraCharacteristics(id).keys.isNotEmpty() }.getOrDefault(false)) break
            delay(250)
        }
        delay(500)
        return SystemClock.elapsedRealtime() - start
    }

    private fun maximumRaw(lens: Lens): Pair<Int, Size>? {
        val map = lens.chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION) ?: return null
        for (format in listOf(ImageFormat.RAW_SENSOR, ImageFormat.RAW10)) largest(map, format)?.let { return format to it }
        return null
    }

    private suspend fun maximumResolutionRaw(device: CameraDevice, thread: CameraThread, surface: Surface, lens: Lens, zoom: Float, folder: String, withPreview: Boolean): JsonObject {
        val (format, size) = maximumRaw(lens)!!
        ImageQueue(imageReader(size.width, size.height, format, 2), thread).use { queue ->
            val raw = OutputConfiguration(queue.reader.surface).apply {
                if (lens.physical) setPhysicalCameraId(lens.id)
                addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
            }
            val session = device.session(if (withPreview) listOf(OutputConfiguration(surface), raw) else listOf(raw), thread)
            try {
                if (withPreview) {
                    device.repeating(session, thread, listOf(surface)) { set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom) }
                    delay(1500)
                }
                val builder = if (lens.physical) device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE, setOf(lens.id)) else device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                val request = builder.apply {
                    addTarget(queue.reader.surface)
                    set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                    set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
                    if (lens.physical) setPhysicalCameraKey(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION, lens.id)
                }
                // Without a preview the automatic exposure has only these frames to settle on: a few are taken and dropped first
                if (!withPreview) repeat(7) {
                    session.captureOnce(request.build(), thread)
                    withTimeoutOrNull(5000) { queue.next(6000) }?.close()
                }
                val start = SystemClock.elapsedRealtime()
                val result = session.captureOnce(request.build(), thread)
                val image = withTimeoutOrNull(5000) { queue.next(6000) } ?: return buildJsonObject {
                    put("delivered", false)
                    put("withPreview", withPreview)
                    put("zoom", zoom)
                    resultJson(result).forEach { (k, v) -> put(k, v) }
                }
                image.use {
                    val latency = SystemClock.elapsedRealtime() - start
                    val plane = it.planes[0]
                    val physical = own(result, lens)
                    val name = "lens${lens.id}_${it.width}x${it.height}_stride${plane.rowStride}${if (withPreview) "" else "_alone"}.${if (format == ImageFormat.RAW10) "raw10" else "raw16"}"
                    val layout = withContext(Dispatchers.Default) { probe { layout(it, format) } }
                    withContext(Dispatchers.IO) {
                        Media.writeDownload(resolver, folder, name, "application/octet-stream") { out -> Channels.newChannel(out).write(plane.buffer.duplicate().apply { rewind() }) }
                    }
                    return buildJsonObject {
                        put("delivered", true)
                        put("withPreview", withPreview)
                        put("zoom", zoom)
                        put("format", Names.format(format))
                        put("size", "${it.width}x${it.height}")
                        put("rowStride", plane.rowStride)
                        put("latencyMs", latency)
                        put("file", name)
                        put("pixelMode", jsonOf(physical.get(CaptureResult.SENSOR_PIXEL_MODE)))
                        put("rawBinningFactorUsed", jsonOf(physical.get(CaptureResult.SENSOR_RAW_BINNING_FACTOR_USED)))
                        put("lensCropRegion", jsonOf(physical.get(CaptureResult.SCALER_CROP_REGION)))
                        resultJson(result).forEach { (k, v) -> put(k, v) }
                        put("layout", layout)
                        put("metadata", rawMetadata(lens.chars, physical))
                    }
                }
            } finally {
                session.close()
            }
        }
    }

    /** What a raw converter needs besides the pixels: levels, colour filter, colour matrices, white balance at capture. */
    private fun rawMetadata(chars: CameraCharacteristics, result: CaptureResult): JsonObject = buildJsonObject {
        put("colorFilterArrangement", jsonOf(chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)))
        put("blackLevelPattern", jsonOf(chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)))
        put("whiteLevel", jsonOf(chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)))
        put("dynamicBlackLevel", jsonOf(result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)))
        put("dynamicWhiteLevel", jsonOf(result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)))
        put("neutralColorPoint", jsonOf(result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)))
        put("referenceIlluminant1", jsonOf(chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)))
        put("referenceIlluminant2", jsonOf(chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)))
        put("colorTransform1", jsonOf(chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)))
        put("colorTransform2", jsonOf(chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)))
        put("forwardMatrix1", jsonOf(chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)))
        put("forwardMatrix2", jsonOf(chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)))
        put("calibrationTransform1", jsonOf(chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)))
        put("calibrationTransform2", jsonOf(chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)))
        put("exposureMs", jsonOf(result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.div(1e6)))
        put("iso", jsonOf(result.get(CaptureResult.SENSOR_SENSITIVITY)))
        put("sensorOrientation", jsonOf(chars.get(CameraCharacteristics.SENSOR_ORIENTATION)))
    }

    /**
     * The colour layout of a raw frame, from its centre: a Bayer sensor changes colour at every pixel, a quad-Bayer one (2×2 blocks of one colour, not rearranged)
     * only at every second; so the step between neighbours inside a pair (even x) against the step across pairs (odd x) tells them apart.
     */
    private fun layout(image: Image, format: Int): JsonObject {
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val stride = plane.rowStride
        fun pixel(x: Int, y: Int): Int = if (format == ImageFormat.RAW10) {
            val base = y * stride + (x / 4) * 5
            val high = buffer.get(base + x % 4).toInt() and 0xff
            val low = (buffer.get(base + 4).toInt() shr (2 * (x % 4))) and 3
            (high shl 2) or low
        } else {
            buffer.getShort(y * stride + x * 2).toInt() and 0xffff
        }
        val n = 1024
        val x0 = (image.width / 2 - n / 2) and 3.inv()
        val y0 = (image.height / 2 - n / 2) and 3.inv()
        val tile = Array(4) { DoubleArray(4) }
        val steps = DoubleArray(4) // horizontal even, horizontal odd, vertical even, vertical odd
        val counts = IntArray(4)
        for (y in y0 until y0 + n) for (x in x0 until x0 + n) {
            val p = pixel(x, y)
            tile[y % 4][x % 4] += p.toDouble()
            val h = if (x % 2 == 0) 0 else 1
            steps[h] += abs(p - pixel(x + 1, y)).toDouble()
            counts[h]++
            val v = if (y % 2 == 0) 2 else 3
            steps[v] += abs(p - pixel(x, y + 1)).toDouble()
            counts[v]++
        }
        val perTile = (n / 4.0) * (n / 4.0)
        val step = DoubleArray(4) { steps[it] / counts[it] }
        val quad = step[0] < 0.6 * step[1] && step[2] < 0.6 * step[3]
        return buildJsonObject {
            put("region", "${n}x$n at $x0,$y0")
            put("tileMeans4x4", buildJsonArray { tile.forEach { row -> add(buildJsonArray { row.forEach { add(String.format(Locale.ROOT, "%.1f", it / perTile).toDouble()) } }) } })
            put("stepHorizontalInsidePair", step[0])
            put("stepHorizontalAcrossPairs", step[1])
            put("stepVerticalInsidePair", step[2])
            put("stepVerticalAcrossPairs", step[3])
            put("guess", if (quad) "quad Bayer (2x2 blocks of one colour)" else "Bayer")
        }
    }

    // Comparison scene: at every native stop from 1× up, the camera's own photo and then RAW bursts at locked exposure (at 1× also one stop under, as HDR+ shoots)
    private suspend fun compare(id: String): JsonObject {
        val chars = chars(id)
        val range = zoomRange(chars)
        val lenses = lenses(id)
        val stops = (lenses.map { stop(it.zoom, range) } + 1f).filter { it >= 1f }.distinct().sorted()
        val folder = "compare_${Media.stamp()}_cam$id"
        val surface = preview.surface(previewSize(map(chars)))
        return manager.withCamera(id) { device, thread ->
            buildJsonObject {
                put("folder", "${Media.REPORT_DIR}/$folder")
                put("timestampSource", jsonOf(chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)?.let { if (it == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) "REALTIME" else "UNKNOWN" }))
                for (zoom in stops) put("${zoomText(zoom)}x", probe {
                    val tag = "z${zoomText(zoom)}"
                    val reference = photo(device, thread, surface, chars, zoom, folder, "${tag}_camera.jpg")
                    val active = reference["activePhysicalId"]?.let { (it as? JsonPrimitive)?.content }
                    val lens = lenses.firstOrNull { it.id == active } ?: lenses.minBy { abs(ln(it.zoom / zoom)) }
                    val first = zoom == 1f
                    buildJsonObject {
                        put("camera", reference)
                        put("raw", probe { rawBursts(device, thread, surface, lens, zoom, if (first) 8 else 6, if (first) listOf(0, -1) else listOf(0), folder, tag) })
                    }
                })
            }
        }
    }

    private suspend fun rawBursts(device: CameraDevice, thread: CameraThread, surface: Surface, lens: Lens, zoom: Float, frames: Int, evs: List<Int>, folder: String, tag: String): JsonObject {
        val size = largest(lens.chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP), ImageFormat.RAW_SENSOR) ?: return buildJsonObject { put("skipped", "no RAW_SENSOR") }
        // Room for a whole burst: the frames wait in the reader while the DNGs are written, and a reader that is full drops what the camera sends
        ImageQueue(imageReader(size.width, size.height, ImageFormat.RAW_SENSOR, frames + 2), thread).use { queue ->
            val raw = OutputConfiguration(queue.reader.surface).apply { if (lens.physical) setPhysicalCameraId(lens.id) }
            val session = device.session(listOf(OutputConfiguration(surface), raw), thread)
            try {
                val watch = ResultWatch()
                device.repeating(session, thread, listOf(surface), callback = watch) { set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom) }
                delay(2000)
                val metered = watch.result ?: error("no preview result to meter from")
                val exposure = metered.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: error("no exposure time in the preview result")
                val iso = metered.get(CaptureResult.SENSOR_SENSITIVITY) ?: error("no sensitivity in the preview result")
                val focus = metered.get(CaptureResult.LENS_FOCUS_DISTANCE)
                return buildJsonObject {
                    put("lens", lens.id)
                    put("size", sizeText(size))
                    put("metered", buildJsonObject {
                        put("exposureMs", exposure / 1e6)
                        put("iso", iso)
                        put("focusDiopters", jsonOf(focus))
                    })
                    for (ev in evs) {
                        val time = (exposure * 2.0.pow(ev)).toLong()
                        put("ev$ev", probe { burst(device, session, thread, surface, queue, lens, zoom, frames, time, iso, focus, folder, "${tag}_ev$ev") })
                    }
                }
            } finally {
                session.close()
            }
        }
    }

    private suspend fun burst(
        device: CameraDevice, session: CameraCaptureSession, thread: CameraThread, surface: Surface, queue: ImageQueue, lens: Lens, zoom: Float, frames: Int,
        exposure: Long, iso: Int, focus: Float?, folder: String, name: String,
    ): JsonObject {
        val results = ConcurrentLinkedQueue<TotalCaptureResult>()
        val lost = AtomicInteger()
        val requests = List(frames) {
            device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(surface)
                addTarget(queue.reader.surface)
                set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
                set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(exposure, 33_333_333L))
                if (focus != null) {
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, focus)
                }
                set(CaptureRequest.CONTROL_AWB_LOCK, true)
                lensShading(lens.chars)
            }.build()
        }
        val gyro = GyroLog(sensors).also { it.start(thread) }
        session.captureBurstRequests(requests, thread.executor, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                results += result
            }

            override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
                if (target == queue.reader.surface) lost.incrementAndGet()
            }
        })
        val written = buildJsonArray {
            for (i in 1..frames) {
                val image = withTimeoutOrNull(10_000) { queue.next(11_000) } ?: break
                image.use {
                    val result = resultFor(results, it.timestamp, lens)
                    val file = "${name}_${String.format(Locale.ROOT, "%02d", i)}.dng"
                    if (result == null) {
                        add(buildJsonObject { put("timestampNs", it.timestamp); put("error", "no capture result with this timestamp") })
                    } else {
                        writeDng(folder, file, lens.chars, result, it)
                        add(buildJsonObject {
                            put("file", file)
                            put("timestampNs", it.timestamp)
                            put("exposureMs", jsonOf(result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.div(1e6)))
                            put("iso", jsonOf(result.get(CaptureResult.SENSOR_SENSITIVITY)))
                            put("cropRegion", jsonOf(result.get(CaptureResult.SCALER_CROP_REGION)))
                            put("rollingShutterSkewMs", jsonOf(result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW)?.div(1e6)))
                        })
                    }
                }
            }
        }
        delay(300)
        gyro.stop()
        val gyroFile = "${name}_gyro.json"
        val samples = gyro.size
        withContext(Dispatchers.IO) { Media.writeDownload(resolver, folder, gyroFile, "application/json") { it.write(Json.encodeToString(JsonObject.serializer(), gyro.json()).toByteArray()) } }
        return buildJsonObject {
            put("exposureMs", exposure / 1e6)
            put("iso", iso)
            put("frames", written)
            put("buffersLost", lost.get())
            put("gyroFile", gyroFile)
            put("gyroSamples", samples)
        }
    }

    /** The capture result of the lens's frame with this sensor timestamp; results may come a little after their images. */
    private suspend fun resultFor(results: Collection<TotalCaptureResult>, timestamp: Long, lens: Lens): CaptureResult? {
        repeat(200) {
            results.firstNotNullOfOrNull { r -> own(r, lens).takeIf { it.get(CaptureResult.SENSOR_TIMESTAMP) == timestamp } }?.let { return it }
            delay(10)
        }
        // A lens whose result carries a slightly different clock: the nearest within a millisecond
        return results.map { own(it, lens) }.minByOrNull { abs((it.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L) - timestamp) }
            ?.takeIf { abs((it.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L) - timestamp) < 1_000_000L }
    }
}

/** Gyroscope samples (sensor clock in ns; rad/s around x, y, z) taken while a burst runs, for alignment and sharp-frame experiments; 200 Hz needs no permission. */
private class GyroLog(private val sensors: SensorManager) : SensorEventListener {
    private val samples = ConcurrentLinkedQueue<Pair<Long, FloatArray>>()
    val size get() = samples.size

    fun start(thread: CameraThread) {
        sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sensors.registerListener(this, it, 5000, thread.handler) }
    }

    fun stop() = sensors.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        samples += event.timestamp to event.values.copyOf(3)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    fun json(): JsonObject = buildJsonObject {
        put("columns", "timestampNs, x, y, z (rad/s)")
        put("samples", buildJsonArray {
            samples.forEach { (t, v) -> add(buildJsonArray { add(t); v.forEach { add(it) } }) }
        })
    }
}
