package sh.hnet.camera.capabilities

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Camera2's view of every camera an app can open: the logical cameras from cameraIdList, the physical cameras behind them, and which can run together. */
class CameraProbe(private val manager: CameraManager) {

    /** Logical camera ids an app can open, then the physical ids reachable only through them. */
    fun cameraIds(): Pair<List<String>, List<String>> {
        val logical = manager.cameraIdList.toList()
        val physical = logical.flatMap { manager.getCameraCharacteristics(it).physicalCameraIds }.distinct().filter { it !in logical }
        return logical to physical
    }

    fun run(): JsonObject = buildJsonObject {
        val (logical, physical) = cameraIds()
        put("logicalIds", jsonOf(logical))
        put("physicalOnlyIds", jsonOf(physical))
        put("concurrent", probe { jsonOf(manager.concurrentCameraIds.map { it.sorted() }) })
        put("cameras", buildJsonObject {
            for (id in logical + physical) {
                put(id, probe {
                    val chars = manager.getCameraCharacteristics(id)
                    buildJsonObject {
                        put("logical", id in logical)
                        put("summary", probe { summary(chars) })
                        put("characteristics", probe { allKeys(chars) })
                    }
                })
            }
        })
    }

    /** The facts a camera app decides on, read and named; the rest stays in "characteristics". */
    fun summary(chars: CameraCharacteristics): JsonObject = buildJsonObject {
        fun <T> get(key: CameraCharacteristics.Key<T>): T? = chars.get(key)

        get(CameraCharacteristics.LENS_FACING)?.let { put("facing", Names.facing(it)) }
        get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)?.let { put("hardwareLevel", Names.hardwareLevel(it)) }
        put("capabilities", jsonOf(get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.map { Names.capability(it) }))
        put("physicalIds", jsonOf(chars.physicalCameraIds.toList()))
        put("focalLengthsMm", jsonOf(get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)))
        put("apertures", jsonOf(get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)))
        put("sensorPhysicalSizeMm", jsonOf(get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)))
        put("pixelArray", jsonOf(get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)))
        put("pixelArrayMaximumResolution", jsonOf(get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)))
        put("binningFactor", jsonOf(get(CameraCharacteristics.SENSOR_INFO_BINNING_FACTOR)))
        put("sensorOrientation", jsonOf(get(CameraCharacteristics.SENSOR_ORIENTATION)))
        put("zoomRatioRange", jsonOf(get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)))
        put("maxDigitalZoom", jsonOf(get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)))
        put("minimumFocusDistanceDiopters", jsonOf(get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)))
        put("ois", jsonOf(get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.map { it == CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON }))
        put("videoStabilization", jsonOf(get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.map { Names.stabilizationMode(it) }))
        put("exposureTimeRangeNs", jsonOf(get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)))
        put("sensitivityRange", jsonOf(get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)))
        put("maxAnalogSensitivity", jsonOf(get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY)))
        put("maxFrameDurationNs", jsonOf(get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)))
        put("aeCompensation", buildJsonObject {
            put("range", jsonOf(get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)))
            put("step", jsonOf(get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)))
        })
        put("aeModes", jsonOf(get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.map { Names.aeMode(it) }))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) put("aePriorityModes", jsonOf(get(CameraCharacteristics.CONTROL_AE_AVAILABLE_PRIORITY_MODES)))
        put("afModes", jsonOf(get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.map { Names.afMode(it) }))
        put("aeTargetFpsRanges", jsonOf(get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.map { "${it.lower}-${it.upper}" }))
        put("maxRegions", buildJsonObject {
            put("ae", jsonOf(get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE)))
            put("awb", jsonOf(get(CameraCharacteristics.CONTROL_MAX_REGIONS_AWB)))
            put("af", jsonOf(get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)))
        })
        put("flash", buildJsonObject {
            put("available", jsonOf(get(CameraCharacteristics.FLASH_INFO_AVAILABLE)))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                put("singleStrengthMaxLevel", jsonOf(get(CameraCharacteristics.FLASH_SINGLE_STRENGTH_MAX_LEVEL)))
                put("torchStrengthMaxLevel", jsonOf(get(CameraCharacteristics.FLASH_TORCH_STRENGTH_MAX_LEVEL)))
            }
        })
        put("extendedSceneModes", buildJsonArray {
            get(CameraCharacteristics.CONTROL_AVAILABLE_EXTENDED_SCENE_MODE_CAPABILITIES)?.forEach {
                add(jsonOf("${Names.extendedSceneMode(it.mode)} max ${it.maxStreamingSize.width}x${it.maxStreamingSize.height} zoom ${it.zoomRatioRange}"))
            }
        })
        put("autoframing", jsonOf(get(CameraCharacteristics.CONTROL_AUTOFRAMING_AVAILABLE)))
        put("settingsOverrides", jsonOf(get(CameraCharacteristics.CONTROL_AVAILABLE_SETTINGS_OVERRIDES)))
        put("lowLightBoost", Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.contains(CameraMetadata.CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY) == true)
        put("dynamicRangeProfiles", jsonOf(get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)))
        put("recommendedTenBitProfile", jsonOf(get(CameraCharacteristics.REQUEST_RECOMMENDED_TEN_BIT_DYNAMIC_RANGE_PROFILE)?.let { Names.dynamicRange(it) }))
        put("colorSpaceProfiles", jsonOf(get(CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES)))
        put("streamUseCases", jsonOf(get(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES)?.map { Names.streamUseCase(it) }))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) put("sessionConfigurationQueryVersion", jsonOf(get(CameraCharacteristics.INFO_SESSION_CONFIGURATION_QUERY_VERSION)))
        put("timestampSource", jsonOf(get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)))
        put("readoutTimestamp", jsonOf(get(CameraCharacteristics.SENSOR_READOUT_TIMESTAMP)))
        put("maxFaceCount", jsonOf(get(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT)))
        put("syncMaxLatency", jsonOf(get(CameraCharacteristics.SYNC_MAX_LATENCY)))
        put("pipelineMaxDepth", jsonOf(get(CameraCharacteristics.REQUEST_PIPELINE_MAX_DEPTH)))
        get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.let { put("largestOutputs", largestOutputs(it)) }
        get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)?.let { put("largestOutputsMaximumResolution", largestOutputs(it)) }
    }

    /** The largest size per output format with its frame duration and stall: what a capture at full size costs. */
    private fun largestOutputs(map: StreamConfigurationMap): JsonObject = buildJsonObject {
        for (format in map.outputFormats.sorted()) {
            val sizes = (map.getOutputSizes(format) ?: emptyArray()) + (map.getHighResolutionOutputSizes(format) ?: emptyArray())
            val largest = sizes.maxByOrNull { area(it) } ?: continue
            put(Names.format(format), "${sizeText(largest)} (frame: ${map.getOutputMinFrameDuration(format, largest) / 1_000_000.0} ms, stall: ${map.getOutputStallDuration(format, largest) / 1_000_000.0} ms)")
        }
        // JPEG_R and HEIC_ULTRAHDR (Android 16) are not in outputFormats on every release; asked directly as well, a format the release doesn't know may throw
        for (format in listOfNotNull(ImageFormat.JPEG_R, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) ImageFormat.HEIC_ULTRAHDR else null)) {
            if (format in map.outputFormats) continue
            val largest = runCatching { map.getOutputSizes(format) }.getOrNull()?.maxByOrNull { area(it) } ?: continue
            put(Names.format(format), sizeText(largest))
        }
    }

    /** Every characteristics key the camera reports, by its name. */
    fun allKeys(chars: CameraCharacteristics): JsonObject = buildJsonObject {
        for (key in chars.keys.sortedBy { it.name }) {
            put(key.name, probe { jsonOf(chars.get(key)) })
        }
    }
}
