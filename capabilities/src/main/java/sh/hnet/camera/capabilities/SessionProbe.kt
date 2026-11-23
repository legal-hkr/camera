package sh.hnet.camera.capabilities

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Range
import android.util.Size
import androidx.annotation.RequiresApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Asks the camera, without opening it (CameraDeviceSetup, Android 15+), whether the stream combinations a camera app needs can run in one session: preview with
 * photo formats, HDR video with stabilization and frame rates, video with stills (motion photo), RAW at full sensor resolution.
 */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
class SessionProbe(private val manager: CameraManager) {

    /** One output of a combination: an image format at a size, optionally 10-bit, with a stream use case or at the sensor's maximum resolution. */
    data class Output(
        val label: String,
        val format: Int,
        val size: Size,
        val usage: Long = 0,
        val profile: Long = DynamicRangeProfiles.STANDARD,
        val useCase: Long = CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_DEFAULT.toLong(),
        val maximumResolution: Boolean = false,
    )

    data class Combination(val name: String, val outputs: List<Output>, val fps: Range<Int>? = null, val stabilization: Int? = null)

    fun run(): JsonObject = buildJsonObject {
        for (id in manager.cameraIdList) {
            put(id, probe {
                if (!manager.isCameraDeviceSetupSupported(id)) return@probe buildJsonObject { put("supported", false) }
                val setup = manager.getCameraDeviceSetup(id)
                val chars = manager.getCameraCharacteristics(id)
                buildJsonObject {
                    for (combination in combinations(chars)) {
                        put(combination.name, probe { check(setup, combination) })
                    }
                }
            })
        }
    }

    private fun check(setup: CameraDevice.CameraDeviceSetup, combination: Combination): JsonObject {
        val outputs = combination.outputs.map { output ->
            val config = if (output.usage != 0L) OutputConfiguration(output.format, output.size, output.usage) else OutputConfiguration(output.format, output.size)
            config.dynamicRangeProfile = output.profile
            if (output.useCase != CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_DEFAULT.toLong()) config.streamUseCase = output.useCase
            if (output.maximumResolution) config.addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
            config
        }
        val session = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs)
        val request = setup.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        combination.fps?.let { request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        combination.stabilization?.let { request.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, it) }
        if (combination.outputs.any { it.maximumResolution }) request.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
        session.setSessionParameters(request.build())
        val supported = setup.isSessionConfigurationSupported(session)
        return buildJsonObject {
            put("supported", supported)
            put("outputs", jsonOf(combination.outputs.map { describe(it) }))
            combination.fps?.let { put("fps", "${it.lower}-${it.upper}") }
            combination.stabilization?.let { put("stabilization", Names.stabilizationMode(it)) }
            if (supported) {
                put("sessionZoomRatioRange", probe { jsonOf(setup.getSessionCharacteristics(session).get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)) })
            }
        }
    }

    private fun describe(output: Output) = buildString {
        append(output.label).append(": ").append(Names.format(output.format)).append(' ').append(sizeText(output.size))
        if (output.profile != DynamicRangeProfiles.STANDARD) append(' ').append(Names.dynamicRange(output.profile))
        if (output.useCase != CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_DEFAULT.toLong()) append(" use:").append(Names.streamUseCase(output.useCase))
        if (output.maximumResolution) append(" maximum-resolution")
    }

    /** The combinations worth knowing before the camera app is designed; sizes are taken from what this camera offers, a combination whose format is missing is left out. */
    @SuppressLint("InlinedApi") // HEIC_ULTRAHDR is used only when the release offered sizes for it
    fun combinations(chars: CameraCharacteristics): List<Combination> {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        val maxMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
        val profiles = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles ?: emptySet()
        val hlg = DynamicRangeProfiles.HLG10 in profiles
        val useCases = chars.get(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES)?.toSet() ?: emptySet()

        // A format the release doesn't know may throw instead of returning null
        fun largest(m: StreamConfigurationMap?, format: Int): Size? = m?.let { runCatching { it.getOutputSizes(format) }.getOrNull() }?.maxByOrNull { area(it) }
        fun has(format: Int, size: Size) = map.getOutputSizes(format)?.contains(size) == true

        val previewUsage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_COMPOSER_OVERLAY
        val videoUsage = HardwareBuffer.USAGE_VIDEO_ENCODE
        val previewSize = Size(1920, 1080).takeIf { has(ImageFormat.PRIVATE, it) } ?: largest(map, ImageFormat.PRIVATE) ?: return emptyList()
        val uhd = Size(3840, 2160).takeIf { has(ImageFormat.PRIVATE, it) }
        val fhd = Size(1920, 1080).takeIf { has(ImageFormat.PRIVATE, it) }
        val jpeg = largest(map, ImageFormat.JPEG)
        val jpegR = largest(map, ImageFormat.JPEG_R)
        val heicUltraHdr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) largest(map, ImageFormat.HEIC_ULTRAHDR) else null
        val yuv = largest(map, ImageFormat.YUV_420_888)
        val p010 = largest(map, ImageFormat.YCBCR_P010)
        val raw = largest(map, ImageFormat.RAW_SENSOR)
        val raw10Max = largest(maxMap, ImageFormat.RAW10)
        val rawMax = largest(maxMap, ImageFormat.RAW_SENSOR)
        val jpegMax = largest(maxMap, ImageFormat.JPEG)
        val stillUseCase = CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_STILL_CAPTURE.toLong().takeIf { it in useCases }
        val videoUseCase = CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD.toLong().takeIf { it in useCases }
        val previewUseCase = CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW.toLong().takeIf { it in useCases }

        fun preview(profile: Long = DynamicRangeProfiles.STANDARD) = Output("preview", ImageFormat.PRIVATE, previewSize, previewUsage, profile, previewUseCase ?: 0)
        fun video(size: Size, profile: Long = DynamicRangeProfiles.STANDARD) = Output("video", ImageFormat.PRIVATE, size, videoUsage, profile, videoUseCase ?: 0)
        fun still(format: Int, size: Size, profile: Long = DynamicRangeProfiles.STANDARD) = Output("still", format, size, profile = profile, useCase = stillUseCase ?: 0)

        val on = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
        val previewStab = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
        val fps30 = Range(30, 30)
        val fps60 = Range(60, 60)
        val h = DynamicRangeProfiles.HLG10

        return buildList {
            jpeg?.let { add(Combination("photo: preview + JPEG", listOf(preview(), still(ImageFormat.JPEG, it)))) }
            jpegR?.let { add(Combination("photo: preview + JPEG_R (Ultra HDR)", listOf(preview(), still(ImageFormat.JPEG_R, it)))) }
            heicUltraHdr?.let { add(Combination("photo: preview + HEIC_ULTRAHDR", listOf(preview(), still(ImageFormat.HEIC_ULTRAHDR, it)))) }
            if (jpegR != null && raw != null) add(Combination("photo: preview + JPEG_R + RAW", listOf(preview(), still(ImageFormat.JPEG_R, jpegR), Output("raw", ImageFormat.RAW_SENSOR, raw))))
            if (jpeg != null && raw != null) add(Combination("photo: preview + JPEG + RAW", listOf(preview(), still(ImageFormat.JPEG, jpeg), Output("raw", ImageFormat.RAW_SENSOR, raw))))
            if (hlg && p010 != null) add(Combination("photo: preview HLG + P010 (own Ultra HDR)", listOf(preview(h), Output("still", ImageFormat.YCBCR_P010, p010, profile = h))))
            if (hlg && p010 != null && jpeg != null) add(Combination("photo: preview + P010 HLG + JPEG", listOf(preview(), Output("hdr", ImageFormat.YCBCR_P010, p010, profile = h), still(ImageFormat.JPEG, jpeg))))
            yuv?.let { add(Combination("burst: preview + YUV full size", listOf(preview(), Output("burst", ImageFormat.YUV_420_888, it)))) }
            if (yuv != null && jpeg != null) add(Combination("burst: preview + YUV + JPEG full size", listOf(preview(), Output("burst", ImageFormat.YUV_420_888, yuv), still(ImageFormat.JPEG, jpeg))))
            raw10Max?.let { add(Combination("photo: RAW10 maximum resolution", listOf(Output("raw", ImageFormat.RAW10, it, maximumResolution = true)))) }
            rawMax?.let { add(Combination("photo: RAW_SENSOR maximum resolution", listOf(Output("raw", ImageFormat.RAW_SENSOR, it, maximumResolution = true)))) }
            jpegMax?.let { add(Combination("photo: JPEG maximum resolution", listOf(Output("still", ImageFormat.JPEG, it, maximumResolution = true)))) }
            if (raw10Max != null) add(Combination("photo: preview + RAW10 maximum resolution", listOf(preview(), Output("raw", ImageFormat.RAW10, raw10Max, maximumResolution = true))))

            for (size in listOfNotNull(uhd, fhd)) {
                val n = if (size == uhd) "4K" else "1080p"
                for ((fpsName, fps) in listOf("30" to fps30, "60" to fps60)) {
                    add(Combination("video $n$fpsName SDR", listOf(preview(), video(size)), fps))
                    add(Combination("video $n$fpsName SDR, stabilization on", listOf(preview(), video(size)), fps, on))
                    add(Combination("video $n$fpsName SDR, preview stabilization", listOf(preview(), video(size)), fps, previewStab))
                    if (hlg) {
                        add(Combination("video $n$fpsName HLG", listOf(preview(h), video(size, h)), fps))
                        add(Combination("video $n$fpsName HLG, stabilization on", listOf(preview(h), video(size, h)), fps, on))
                        add(Combination("video $n$fpsName HLG, preview stabilization", listOf(preview(h), video(size, h)), fps, previewStab))
                    }
                }
                jpeg?.let { add(Combination("video $n + JPEG snapshot", listOf(preview(), video(size), still(ImageFormat.JPEG, it)), fps30)) }
                if (hlg && jpegR != null) add(Combination("video $n HLG + JPEG_R snapshot", listOf(preview(h), video(size, h), still(ImageFormat.JPEG_R, jpegR)), fps30))
            }
            // Motion photo: a short video stream runs all the time while full-size stills are taken
            if (fhd != null) {
                jpeg?.let { add(Combination("motion photo: preview + video 1080p + JPEG", listOf(preview(), video(fhd), still(ImageFormat.JPEG, it)), fps30)) }
                jpegR?.let { add(Combination("motion photo: preview + video 1080p + JPEG_R", listOf(preview(), video(fhd), still(ImageFormat.JPEG_R, it)), fps30)) }
                if (hlg && jpegR != null) add(Combination("motion photo: preview HLG + video 1080p HLG + JPEG_R", listOf(preview(h), video(fhd, h), still(ImageFormat.JPEG_R, jpegR)), fps30))
                if (yuv != null) add(Combination("motion photo: preview + video 1080p + YUV full size", listOf(preview(), video(fhd), Output("still", ImageFormat.YUV_420_888, yuv)), fps30))
            }
            addAll(photoLadders(map, hlg, previewUsage, videoUsage, previewUseCase, videoUseCase, stillUseCase))
        }
    }

    /**
     * The camera app's photo sessions (LOGIKA §2.1), best first, for 4:3 and 16:9: preview, the motion photo's video stream in the preview's size, the still and a small analysis stream
     * (QR codes, the finder glass' blur) – 8-bit YUV or, next to an HLG preview, 10-bit P010, since one request may not mix every profile.
     */
    private fun photoLadders(map: StreamConfigurationMap, hlg: Boolean, previewUsage: Long, videoUsage: Long, previewUseCase: Long?, videoUseCase: Long?, stillUseCase: Long?): List<Combination> {
        fun sizes(format: Int): List<Size> = runCatching { map.getOutputSizes(format)?.toList() }.getOrNull().orEmpty()
        fun largestOf(format: Int, width: Int, height: Int) = sizes(format).filter { it.width * height == it.height * width }.maxByOrNull { area(it) }
        fun smallestAtLeast(format: Int, width: Int) = sizes(format).filter { it.width >= width && it.width * 3 == it.height * 4 }.minByOrNull { area(it) }
        val fps30 = Range(30, 30)
        val h = DynamicRangeProfiles.HLG10
        return buildList {
            for ((name, w, ht) in listOf(Triple("4:3", 4, 3), Triple("16:9", 16, 9))) {
                val previewSize = sizes(ImageFormat.PRIVATE).filter { it.width <= 1920 && it.width * ht == it.height * w }.maxByOrNull { area(it) } ?: continue
                val jpegR = largestOf(ImageFormat.JPEG_R, w, ht)
                val jpeg = largestOf(ImageFormat.JPEG, w, ht)
                val yuvSmall = smallestAtLeast(ImageFormat.YUV_420_888, 320)
                val p010Small = smallestAtLeast(ImageFormat.YCBCR_P010, 320)
                fun preview(profile: Long) = Output("preview", ImageFormat.PRIVATE, previewSize, previewUsage, profile, previewUseCase ?: 0)
                fun motion(profile: Long) = Output("motion", ImageFormat.PRIVATE, previewSize, videoUsage, profile, videoUseCase ?: 0)
                fun still(format: Int, size: Size) = Output("still", format, size, useCase = stillUseCase ?: 0)
                fun analysis(profile: Long) = (if (profile == h) p010Small?.let { Output("analysis", ImageFormat.YCBCR_P010, it, profile = h) } else yuvSmall?.let { Output("analysis", ImageFormat.YUV_420_888, it) })
                val stills = listOfNotNull(jpegR?.let { "JPEG_R" to still(ImageFormat.JPEG_R, it) }, jpeg?.let { "JPEG" to still(ImageFormat.JPEG, it) })
                val profiles = if (hlg) listOf("HLG" to h, "SDR" to DynamicRangeProfiles.STANDARD) else listOf("SDR" to DynamicRangeProfiles.STANDARD)
                for ((stillName, stillOutput) in stills) for ((profileName, profile) in profiles) {
                    val prefix = "app photo $name: preview $profileName + $stillName"
                    val analysisOutput = analysis(profile)
                    if (analysisOutput != null) add(Combination("$prefix + motion + analysis", listOf(preview(profile), motion(profile), stillOutput, analysisOutput), fps30))
                    add(Combination("$prefix + motion", listOf(preview(profile), motion(profile), stillOutput), fps30))
                    if (analysisOutput != null) add(Combination("$prefix + analysis", listOf(preview(profile), stillOutput, analysisOutput), fps30))
                    add(Combination(prefix, listOf(preview(profile), stillOutput), fps30))
                    // The 8-bit analysis stream next to an HLG preview, for when P010 is refused
                    if (profile == h && yuvSmall != null) add(Combination("$prefix + motion + YUV analysis", listOf(preview(profile), motion(profile), stillOutput, Output("analysis", ImageFormat.YUV_420_888, yuvSmall)), fps30))
                }
            }
        }
    }
}
